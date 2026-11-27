package com.example.motor.parser

import android.util.Log
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesEpisode
import com.example.motor.db.SeriesSeason
import com.example.motor.config.StalkerDevice
import com.example.motor.repository.PortalSessionRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

object StalkerParser {
    private const val TAG = "StalkerParser"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    const val MAX_CONCURRENT_REQUESTS = 4
    const val INITIAL_PAGE_SIZE = 100
    const val FALLBACK_PAGE_SIZE = 50
    const val MAX_RETRIES_PER_PAGE = 2


    fun generateRandomMac(): String {
        val r = Random(System.currentTimeMillis())
        val hex = { String.format("%02X", r.nextInt(256)) }
        return "00:1A:79:${hex()}:${hex()}:${hex()}"
    }

    data class DeviceIdResult(
        val primary: String,
        val secondary: String,
        val short: String
    )

    private fun sha256HexUpper(input: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            input.hashCode().toUInt().toString(16).uppercase().padStart(64, '0')
        }
    }

    internal fun resolveSerialNumber(mac: String, customSerial: String = ""): String {
        val cleanedCustom = customSerial.replace(":", "").replace("-", "").trim().uppercase()
        if (cleanedCustom.isNotEmpty()) {
            return cleanedCustom
        }
        val macHex = mac.replace(":", "").replace("-", "").trim().uppercase()
        val suffix = if (macHex.length >= 6) macHex.takeLast(6) else macHex.padStart(6, '0')
        return "001A79$suffix"
    }

    fun generateDeviceId(mac: String, serialNumber: String, model: String): DeviceIdResult {
        val macClean = mac.replace(":", "").replace("-", "").trim().uppercase()
        val serialClean = resolveSerialNumber(mac, serialNumber)
        val serialCleanLast10 = if (serialClean.length >= 10) {
            serialClean.takeLast(10)
        } else {
            serialClean
        }
        // Determinístico por dispositivo/MAC durante toda la sesión (sin marcas de tiempo cambiantes)
        val deviceId = sha256HexUpper("$macClean|$serialClean|$model")
        return DeviceIdResult(deviceId, macClean, serialCleanLast10)
    }

    fun generateDeviceId2(deviceId: String, mac: String): String {
        val macClean = mac.replace(":", "").replace("-", "").trim().uppercase()
        return sha256HexUpper("$deviceId|$macClean|v2")
    }

    fun generateSignature(
        mac: String,
        serialNumber: String,
        model: String,
        hwVersion: String,
        swVersion: String,
        deviceId: String,
        imageVersion: String,
        vendor: String
    ): String {
        val macClean = mac.replace(":", "").replace("-", "").trim().uppercase()
        val serialClean = resolveSerialNumber(mac, serialNumber)
        val baseString = listOf(
            macClean,
            serialClean,
            model,
            hwVersion,
            swVersion,
            deviceId,
            imageVersion,
            vendor
        ).joinToString("|")
        return sha256HexUpper(baseString)
    }

    fun generateApiSignature(token: String, random: String, mac: String): String {
        val macClean = mac.replace(":", "").replace("-", "").trim().uppercase()
        return sha256HexUpper("$token|$random|$macClean")
    }

    internal fun URLParamEncode(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            s.replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
        }
    }

    data class StalkerEpisodeInfo(
        val seriesId: String = "",
        val seriesCmd: String = "",
        val seasonId: String = "",
        val seasonNum: String = "",
        val episodeId: String = "",
        val episodeNum: String = "",
        val seriesNumber: String = "",
        val videoId: String = "",
        val fileId: String = "",
        val file: String = "",
        val cmd: String = "",
        val url: String = "",
        val path: String = "",
        val name: String = "",
        val portalType: String = "",
        val isRealEpisode: Boolean = true
    )

    fun encodeStalkerEpisodeDescriptor(info: StalkerEpisodeInfo): String {
        return "stalker_ep://play?" + listOf(
            "series_id" to info.seriesId,
            "series_cmd" to info.seriesCmd,
            "season_id" to info.seasonId,
            "season_num" to info.seasonNum,
            "episode_id" to info.episodeId,
            "episode_num" to info.episodeNum,
            "series_number" to info.seriesNumber,
            "video_id" to info.videoId,
            "file_id" to info.fileId,
            "file" to info.file,
            "cmd" to info.cmd,
            "url" to info.url,
            "path" to info.path,
            "name" to info.name,
            "portal_type" to info.portalType,
            "real" to (if (info.isRealEpisode) "1" else "0")
        ).joinToString("&") { (k, v) -> "$k=${URLParamEncode(v)}" }
    }

    fun parseStalkerEpisodeDescriptor(descriptor: String): StalkerEpisodeInfo? {
        if (!descriptor.startsWith("stalker_ep://play?") && !descriptor.startsWith("stalker_ep://")) {
            return null
        }
        val query = descriptor.substringAfter("?", "")
        if (query.isEmpty()) return null
        val params = query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = parts[0]
            val value = if (parts.size > 1) {
                try { java.net.URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
            } else ""
            key to value
        }
        return StalkerEpisodeInfo(
            seriesId = params["series_id"] ?: "",
            seriesCmd = params["series_cmd"] ?: "",
            seasonId = params["season_id"] ?: "",
            seasonNum = params["season_num"] ?: "",
            episodeId = params["episode_id"] ?: "",
            episodeNum = params["episode_num"] ?: "",
            seriesNumber = params["series_number"] ?: "",
            videoId = params["video_id"] ?: "",
            fileId = params["file_id"] ?: "",
            file = params["file"] ?: "",
            cmd = params["cmd"] ?: "",
            url = params["url"] ?: "",
            path = params["path"] ?: "",
            name = params["name"] ?: "",
            portalType = params["portal_type"] ?: "",
            isRealEpisode = (params["real"] ?: "1") != "0"
        )
    }

    fun logStalkerSeries(
        stage: String,
        seriesId: String? = null,
        seriesCmd: String? = null,
        seasonId: String? = null,
        seasonNum: String? = null,
        episodeId: String? = null,
        episodeNum: String? = null,
        videoId: String? = null,
        fileId: String? = null,
        cmd: String? = null,
        file: String? = null,
        url: String? = null,
        requestUrl: String? = null,
        rawResponse: String? = null,
        extractedFields: Map<String, Any?>? = null,
        finalUrl: String? = null,
        error: String? = null
    ) {
        // Diagnostic logs removed for production performance
    }

    fun normalizeResultUrl(raw: String, portalUrl: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        val parsedUri = try { java.net.URI(portalUrl) } catch (e: Exception) { null }
        val portalHostBase = if (parsedUri != null) {
            val scheme = parsedUri.scheme ?: "http"
            val host = parsedUri.host ?: ""
            val port = if (parsedUri.port != -1) ":${parsedUri.port}" else ""
            "$scheme://$host$port"
        } else {
            portalUrl.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")
        }

        val prefixes = listOf("ffmpeg ", "ffrt ", "auto ", "udp ", "rtp ", "redirect ", "play ", "stream ")
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (s.startsWith(p, ignoreCase = true)) {
                    s = s.substring(p.length).trim()
                    changed = true
                }
            }
        }

        val httpMatch = Regex("(?i)(https?|rtsp|rtmp)://[^\\s\"'\\\\{}<>,]+").find(s)
        if (httpMatch != null) {
            s = httpMatch.value
        }

        s = s.trimEnd('"', '\'', ';', '}', ']', ')', ',', '>', '`')

        val portalScheme = parsedUri?.scheme ?: "http"
        val portalHost = parsedUri?.host ?: "localhost"
        val portalPortStr = if (parsedUri != null && parsedUri.port != -1) ":${parsedUri.port}" else ""

        if (s.startsWith("http://localhost/") || s.startsWith("http://127.0.0.1/")) {
            s = s.replaceFirst(Regex("http://(localhost|127\\.0\\.0\\.1)/"), "$portalScheme://$portalHost$portalPortStr/")
        } else if (s.startsWith("https://localhost/") || s.startsWith("https://127.0.0.1/")) {
            s = s.replaceFirst(Regex("https://(localhost|127\\.0\\.0\\.1)/"), "$portalScheme://$portalHost$portalPortStr/")
        } else if (s.contains("localhost") || s.contains("127.0.0.1")) {
            s = s.replace("localhost", portalHost).replace("127.0.0.1", portalHost)
        }

        if (!s.startsWith("http://") && !s.startsWith("https://") && !s.startsWith("rtsp://") && !s.startsWith("rtmp://")) {
            s = if (s.startsWith("/")) "$portalHostBase$s" else "$portalHostBase/$s"
        }

        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("rtsp://") || s.startsWith("rtmp://")) {
            return s
        }
        return null
    }

    internal fun extractCandidateUrls(obj: Any?, targetList: MutableList<String>) {
        when (obj) {
            is String -> {
                if (obj.isNotBlank()) targetList.add(obj)
            }
            is Map<*, *> -> {
                val urlKeys = listOf("cmd", "url", "uri", "link", "stream", "path", "location", "direct_link", "stream_url", "file", "src")
                urlKeys.forEach { k ->
                    val v = obj[k]?.toString()
                    if (!v.isNullOrBlank()) targetList.add(v)
                }
                listOf("data", "result", "js", "item", "info").forEach { subKey ->
                    if (obj.containsKey(subKey)) {
                        extractCandidateUrls(obj[subKey], targetList)
                    }
                }
            }
            is List<*> -> {
                obj.forEach { item -> extractCandidateUrls(item, targetList) }
            }
        }
    }

    data class StalkerHttpResponse(val code: Int, val body: String)

    fun sanitizeStalkerLog(input: String?): String {
        if (input == null) return ""
        var res = input
        res = res.replace(Regex("(?i)token=[^&\\s\"']+"), "token=***")
        res = res.replace(Regex("(?i)bearer\\s+[^&\\s\"']+", RegexOption.IGNORE_CASE), "Bearer ***")
        res = res.replace(Regex("(?i)mac=[^&;\\s\"']+"), "mac=***")
        res = res.replace(Regex("(?i)password=[^&\\s\"']+"), "password=***")
        res = res.replace(Regex("(?i)signature=[^&\\s\"']+"), "signature=***")
        res = res.replace(Regex("(?i)device_id2?=[^&\\s\"']+"), "device_id=***")
        res = res.replace(Regex("(?i)sn=[^&\\s\"']+"), "sn=***")
        return res
    }

    private val reservedStalkerCookieNames = setOf("mac", "stb_lang", "timezone", "token")
    private val portalSessionCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    internal fun clearPortalCookiesForTests() {
        portalSessionCookies.clear()
    }

    internal fun sanitizeSchemeAndPort(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        if (url.startsWith("https://", ignoreCase = true) && Regex("(?i)^https://[^/:?#]+:80(?:/|$|\\?)").containsMatchIn(url)) {
            url = "http" + url.substring(5)
        }
        return try {
            val encodedForUri = url.replace(" ", "%20")
            val uri = java.net.URI(encodedForUri)
            val scheme = (uri.scheme ?: "http").lowercase()
            val host = uri.host
            if (!host.isNullOrBlank()) {
                val rawPort = uri.port
                val portStr = when {
                    rawPort == -1 -> ""
                    scheme == "http" && rawPort == 80 -> ""
                    scheme == "https" && rawPort == 443 -> ""
                    else -> ":$rawPort"
                }
                val path = uri.rawPath ?: ""
                val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
                "$scheme://$host$portStr$path$query"
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    internal fun extractPortalOrigin(portalUrl: String): String {
        val cleaned = sanitizeSchemeAndPort(portalUrl)
        return try {
            val uri = java.net.URI(cleaned.replace(" ", "%20"))
            val scheme = (uri.scheme ?: "http").lowercase()
            val host = uri.host
            if (!host.isNullOrBlank()) {
                val portStr = when {
                    uri.port == -1 -> ""
                    scheme == "http" && uri.port == 80 -> ""
                    scheme == "https" && uri.port == 443 -> ""
                    else -> ":${uri.port}"
                }
                "$scheme://$host$portStr"
            } else {
                cleaned.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")
            }
        } catch (_: Exception) {
            cleaned.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")
        }
    }

    internal fun derivePortalReferer(portalUrl: String): String {
        val cleaned = sanitizeSchemeAndPort(portalUrl)
        val origin = extractPortalOrigin(cleaned)
        if (origin.isBlank()) return ""
        return try {
            val uri = java.net.URI(cleaned.replace(" ", "%20"))
            val rawPath = uri.rawPath ?: ""
            val baseSubPath = when {
                rawPath.contains("/stalker_portal/", ignoreCase = true) ||
                    rawPath.equals("/stalker_portal", ignoreCase = true) -> "/stalker_portal"
                rawPath.contains("/ministra/", ignoreCase = true) ||
                    rawPath.equals("/ministra", ignoreCase = true) -> "/ministra"
                else -> {
                    val stripped = rawPath
                        .replace(Regex("(?i)/server/load\\.php$"), "")
                        .replace(Regex("(?i)/c/portal\\.php$"), "")
                        .replace(Regex("(?i)/portal\\.php$"), "")
                        .replace(Regex("(?i)/c(?:/index\\.html)?/?$"), "")
                        .removeSuffix("/")
                    if (stripped.isNotBlank() && stripped != "/") stripped else ""
                }
            }
            "$origin$baseSubPath/c/"
        } catch (_: Exception) {
            "$origin/c/"
        }
    }

    internal fun buildStalkerPortalCandidates(rawHost: String, savedPortalUrl: String? = null): List<String> {
        val candidates = LinkedHashSet<String>()
        if (!savedPortalUrl.isNullOrBlank()) {
            val normSaved = sanitizeSchemeAndPort(savedPortalUrl).removeSuffix("/")
            if (normSaved.isNotEmpty()) candidates.add(normSaved)
        }

        val cleanedInput = sanitizeSchemeAndPort(rawHost)
        if (cleanedInput.isBlank()) return candidates.toList()

        val uri = try { java.net.URI(cleanedInput.replace(" ", "%20")) } catch (_: Exception) { null }
        val origin = extractPortalOrigin(cleanedInput).removeSuffix("/")
        val rawPath = (uri?.rawPath ?: "").trim()
        val rawQuery = uri?.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        val lowerPath = rawPath.lowercase()

        val hasExplicitPhp = lowerPath.contains(".php") || rawQuery.isNotEmpty()
        if (hasExplicitPhp || lowerPath.endsWith("/portal")) {
            val explicitUrl = "$origin${rawPath.removeSuffix("/")}$rawQuery"
            if (explicitUrl.isNotBlank()) candidates.add(explicitUrl)
        }

        val baseSubPath = rawPath
            .replace(Regex("(?i)/server/load\\.php$"), "")
            .replace(Regex("(?i)/c/portal\\.php$"), "")
            .replace(Regex("(?i)/portal\\.php$"), "")
            .replace(Regex("(?i)/c(?:/index\\.html)?/?$"), "")
            .replace(Regex("(?i)/index\\.html$"), "")
            .removeSuffix("/")

        if (lowerPath.contains("/stalker_portal")) {
            candidates.add("$origin/stalker_portal/server/load.php")
            candidates.add("$origin/stalker_portal/c/portal.php")
        } else if (lowerPath.contains("/ministra")) {
            candidates.add("$origin/ministra/server/load.php")
            candidates.add("$origin/stalker_portal/server/load.php")
        } else if (baseSubPath.isNotEmpty() && baseSubPath != "/" && !baseSubPath.contains(".php")) {
            candidates.add("$origin$baseSubPath/portal.php")
            candidates.add("$origin$baseSubPath/server/load.php")
        }

        val standardEndpoints = listOf(
            "$origin/portal.php",
            "$origin/stalker_portal/server/load.php",
            "$origin/server/load.php",
            "$origin/c/portal.php"
        )
        candidates.addAll(standardEndpoints)
        return candidates.toList()
    }

    private fun portalCookieBucketKey(portalUrl: String, macClean: String): String {
        return "${extractPortalOrigin(portalUrl)}|$macClean"
    }

    internal fun getPortalCookiesSnapshot(portalUrl: String, mac: String): Map<String, String> {
        val macClean = mac.trim().uppercase()
        val bucket = portalSessionCookies[portalCookieBucketKey(portalUrl, macClean)] ?: return emptyMap()
        return HashMap(bucket)
    }

    private fun captureSetCookies(portalUrl: String, macClean: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        val bucketKey = portalCookieBucketKey(portalUrl, macClean)
        val bucket = portalSessionCookies.getOrPut(bucketKey) { ConcurrentHashMap() }
        for (rawHeader in setCookieHeaders) {
            val pairPart = rawHeader.substringBefore(";").trim()
            val eqIdx = pairPart.indexOf('=')
            if (eqIdx <= 0) continue
            val name = pairPart.substring(0, eqIdx).trim()
            val value = pairPart.substring(eqIdx + 1).trim()
            val nameLower = name.lowercase()
            if (nameLower in reservedStalkerCookieNames) continue
            if (value.isEmpty() || value.equals("deleted", ignoreCase = true) || value.equals("null", ignoreCase = true)) {
                bucket.remove(name)
            } else {
                bucket[name] = value
            }
        }
    }

    private fun buildCookieHeaderValue(portalUrl: String, macClean: String, token: String): String {
        val sb = StringBuilder()
        sb.append("mac=").append(macClean).append("; stb_lang=en; timezone=Europe/Madrid;")
        if (token.isNotEmpty()) {
            sb.append(" token=").append(token).append(";")
        }
        val extra = portalSessionCookies[portalCookieBucketKey(portalUrl, macClean)]
        if (!extra.isNullOrEmpty()) {
            extra.forEach { (k, v) ->
                if (k.lowercase() !in reservedStalkerCookieNames && v.isNotBlank()) {
                    sb.append(" ").append(k).append("=").append(v).append(";")
                }
            }
        }
        return sb.toString()
    }

    private fun warmUpPortalRefererCookies(refererUrl: String, macClean: String, device: StalkerDevice) {
        if (refererUrl.isBlank()) return
        try {
            val reqBuilder = Request.Builder()
                .url(refererUrl)
                .header("User-Agent", device.userAgent)
                .header("X-User-Agent", device.xUserAgent)
                .header("Cookie", buildCookieHeaderValue(refererUrl, macClean, ""))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            BaseEngine.client.newCall(reqBuilder.build()).execute().use { resp ->
                captureSetCookies(refererUrl, macClean, resp.headers("Set-Cookie"))
                resp.body?.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Warm-up GET $refererUrl failed (non-fatal): ${e.message}")
        }
    }

    private fun classifyStalkerException(e: Exception): NetworkError {
        if (e is NetworkError) return e
        val msg = e.message?.lowercase() ?: ""
        return when {
            e is java.net.SocketTimeoutException || msg.contains("timeout") || msg.contains("timed out") ->
                NetworkError.Timeout("Tiempo de espera agotado al conectar con el portal Stalker")
            e is java.net.UnknownHostException || msg.contains("unable to resolve host") || msg.contains("unknownhost") || msg.contains("dns") ->
                NetworkError.Dns("No se pudo resolver la dirección del portal Stalker")
            e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") ->
                NetworkError.TlsError("Error de certificado TLS/SSL al conectar con el portal")
            e is java.net.ConnectException || msg.contains("econnrefused") || msg.contains("connection refused") || msg.contains("failed to connect") ->
                NetworkError.ServerDown("El servidor rechazó la conexión o no está disponible")
            else -> BaseEngine.classifyException(e)
        }
    }

    private fun errorPriority(err: NetworkError): Int = when (err) {
        is NetworkError.PortalBlockedMac -> 100
        is NetworkError.Auth -> 90
        is NetworkError.RateLimit -> 85
        is NetworkError.TlsError -> 80
        is NetworkError.Timeout -> 70
        is NetworkError.ServerError -> 60
        is NetworkError.ServerDown -> 50
        is NetworkError.InvalidHandshake -> 40
        is NetworkError.InvalidProfile -> 35
        is NetworkError.NotFound -> 20
        else -> 10
    }

    private fun stalkerRequestWithResponse(portalUrl: String, mac: String, params: String, token: String?, device: StalkerDevice): StalkerHttpResponse {
        val macClean = mac.trim().uppercase()
        val finalToken = token ?: ""
        val tokenParam = if (finalToken.isNotEmpty()) "&token=${URLParamEncode(finalToken)}" else ""

        val snVal = resolveSerialNumber(macClean, device.serialNumber)

        val deviceIdResult = generateDeviceId(macClean, snVal, device.hwVersion)
        val deviceId = deviceIdResult.primary
        val deviceId2 = generateDeviceId2(deviceId, macClean)
        val signature = generateSignature(
            mac = macClean,
            serialNumber = snVal,
            model = device.hwVersion,
            hwVersion = device.hwVersion,
            swVersion = device.swVersion,
            deviceId = deviceId,
            imageVersion = device.imageVersion,
            vendor = device.vendor
        )

        var finalParams = params
        if (!finalParams.contains("device_id=")) {
            finalParams += "&device_id=${URLParamEncode(deviceId)}"
        }
        if (!finalParams.contains("device_id2=")) {
            finalParams += "&device_id2=${URLParamEncode(deviceId2)}"
        }
        if (!finalParams.contains("signature=")) {
            finalParams += "&signature=${URLParamEncode(signature)}"
        }
        if (!finalParams.contains("sn=")) {
            finalParams += "&sn=${URLParamEncode(snVal)}"
        }

        val combinedParams = "$finalParams$tokenParam&JsHttpRequest=1-xml"

        val currentUrl = sanitizeSchemeAndPort(portalUrl)
        val url = if (currentUrl.contains("?")) {
            "$currentUrl&$combinedParams"
        } else {
            "$currentUrl?$combinedParams"
        }

        val cookieHeaderVal = buildCookieHeaderValue(currentUrl, macClean, finalToken)
        val refererVal = derivePortalReferer(currentUrl)
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", device.userAgent)
            put("Cookie", cookieHeaderVal)
            put("X-User-Agent", device.xUserAgent)
            put("Accept", "*/*")
            if (refererVal.isNotBlank()) {
                put("Referer", refererVal)
            }
        }
        try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                try {
                    reqBuilder.header(k, v)
                } catch (_: Throwable) {}
            }
            if (finalToken.isNotEmpty()) {
                reqBuilder.header("Authorization", "Bearer $finalToken")
            }

            BaseEngine.client.newCall(reqBuilder.build()).execute().use { response ->
                captureSetCookies(currentUrl, macClean, response.headers("Set-Cookie"))
                val bodyStr = try {
                    response.body?.string() ?: ""
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "OOM reading stalker response from $currentUrl", oom)
                    throw NetworkError.ServerError(response.code, "Respuesta del servidor demasiado grande para procesar")
                }
                return StalkerHttpResponse(response.code, bodyStr)
            }
        } catch (e: java.io.IOException) {
            val msg = e.message?.lowercase() ?: ""
            if (currentUrl.startsWith("https://", ignoreCase = true) &&
                (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                val fallbackUrl = "http" + currentUrl.substring(5)
                Log.w(TAG, "TLS/SSL error on stalkerRequest for $currentUrl, falling back to $fallbackUrl")
                return stalkerRequestWithResponse(fallbackUrl, mac, params, token, device)
            }
            throw e
        } catch (netEx: NetworkError) {
            throw netEx
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid URL or parameter for Stalker request: $currentUrl", e)
            throw NetworkError.ServerDown("URL o parámetros de portal Stalker no válidos")
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (currentUrl.startsWith("https://", ignoreCase = true) &&
                (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                val fallbackUrl = "http" + currentUrl.substring(5)
                Log.w(TAG, "TLS/SSL error on stalkerRequest for $currentUrl, falling back to $fallbackUrl")
                return stalkerRequestWithResponse(fallbackUrl, mac, params, token, device)
            }
            throw e
        }
    }

    private fun stalkerRequest(portalUrl: String, mac: String, params: String, token: String?, device: StalkerDevice): String {
        val resp = stalkerRequestWithResponse(portalUrl, mac, params, token, device)
        val body = resp.body
        if (resp.code == 401) {
            throw NetworkError.TokenExpired("Token de sesión Stalker expirado (HTTP 401)")
        }
        if (resp.code == 403) {
            val parsed = parseStalkerJsResponse(body.trim())
            val js = parsed["js"] as? Map<*, *>
            val blockMsg = (js?.get("block_msg") ?: parsed["block_msg"])?.toString()?.trim()
            if (!blockMsg.isNullOrEmpty() && !blockMsg.equals("false", ignoreCase = true) && !blockMsg.equals("null", ignoreCase = true)) {
                throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $blockMsg")
            }
            throw NetworkError.Auth("Acceso denegado por el portal Stalker (HTTP 403)")
        }
        if (resp.code == 404 || resp.code == 410) {
            throw NetworkError.NotFound("Endpoint o acción no disponible en el portal (HTTP ${resp.code})")
        }
        if (resp.code == 429) {
            throw NetworkError.RateLimit("Límite de peticiones alcanzado en el portal (HTTP 429)")
        }
        if (resp.code in 200..299) {
            return body
        }
        throw NetworkError.ServerError(resp.code, "El portal Stalker devolvió un error (HTTP ${resp.code})")
    }

    private fun parseStalkerJsResponse(json: String): Map<String, Any?> {
        val trimmed = json.trim()
        val jsonToParse = if (trimmed.startsWith("[")) {
            "{\"js\":$trimmed}"
        } else {
            trimmed
        }
        return try {
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            val adapter = moshi.adapter<Map<String, Any?>>(type)
            adapter.fromJson(jsonToParse) ?: emptyMap()
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing stalker response: ${trimmed.take(300)}", t)
            emptyMap()
        }
    }

    private fun extractListFromJsField(parsed: Map<String, Any?>): List<Any> {
        val jsObj = parsed["js"]

        // If jsObj is present (object or array), extract strictly from it so an empty {"js":{"data":[]}}
        // pagination envelope is never mistaken for a 1-item map containing "js".
        if (jsObj != null && jsObj !is Boolean) {
            return extractListFromField(jsObj)
        }

        // Only if jsObj is null/boolean, try to extract list from the root parsed map (excluding "js")
        val rootWithoutJs = if (parsed.containsKey("js")) parsed - "js" else parsed
        return extractListFromField(rootWithoutJs)
    }

    private fun extractListFromField(obj: Any?): List<Any> {
        if (obj == null) return emptyList()
        if (obj is List<*>) {
            return obj.filterNotNull()
        }
        if (obj is Map<*, *>) {
            // Check common list keys that contain lists or maps of items
            val knownKeys = listOf("data", "channels", "series", "row", "files", "items", "results", "movies", "list", "episodes", "seasons", "season", "categories")
            for (key in knownKeys) {
                val value = obj[key]
                if (value is List<*> && value.isNotEmpty()) {
                    return value.filterNotNull()
                }
                if (value is Map<*, *> && value.isNotEmpty()) {
                    val mapValues = value.values.filterNotNull()
                    if (mapValues.isNotEmpty() && mapValues.any { it is Map<*, *> || it is List<*> }) {
                        return mapValues
                    }
                }
            }
            // Check all values in the map for nested lists or maps of items
            for ((_, value) in obj) {
                if (value is List<*> && value.isNotEmpty()) {
                    return value.filterNotNull()
                }
                if (value is Map<*, *> && value.isNotEmpty()) {
                    val mapValues = value.values.filterNotNull()
                    if (mapValues.isNotEmpty() && mapValues.all { it is Map<*, *> }) {
                        return mapValues
                    }
                }
            }
            // Check if obj itself is a map of item objects (e.g. {"1": {item1}, "2": {item2}})
            val directValues = obj.values.filterNotNull()
            if (directValues.isNotEmpty() && directValues.all { it is Map<*, *> }) {
                return directValues
            }
        }
        return emptyList()
    }

    fun extractPaginationAndMeta(parsed: Map<String, Any?>): Pair<Map<String, Any?>, List<String>> {
        val jsObj = parsed["js"] as? Map<*, *> ?: parsed
        val meta = LinkedHashMap<String, Any?>()
        val checkKeys = listOf("total_items", "total", "max_page_items", "cur_page", "selected_item", "page_size", "total_pages", "has_more", "pages")
        for (k in checkKeys) {
            if (jsObj.containsKey(k)) {
                meta[k] = jsObj[k]
            }
        }
        val relevantKeys = jsObj.keys.map { it.toString() }.take(10)
        return Pair(meta, relevantKeys)
    }

    private fun stalkerRequestAndParse(portalUrl: String, mac: String, params: String, token: String?, device: StalkerDevice): Map<String, Any?> {
        val json = try {
            stalkerRequest(portalUrl, mac, params, token, device)
        } catch (netErr: NetworkError) {
            throw netErr
        } catch (e: Exception) {
            throw classifyStalkerException(e)
        }

        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyMap()

        if (trimmed.startsWith("<html", ignoreCase = true) || trimmed.startsWith("<!doctype", ignoreCase = true)) {
            throw NetworkError.InvalidStalkerResponse("El portal devolvió HTML en lugar de JSON")
        }

        val parsed = parseStalkerJsResponse(trimmed)
        if (parsed.isEmpty()) {
            val lower = trimmed.lowercase()
            if (lower.contains("authorization failed") || lower.contains("token expired") || lower.contains("unauthorized") || lower.contains("access denied")) {
                throw NetworkError.TokenExpired("Sesión Stalker expirada o no autorizada")
            }
            throw NetworkError.InvalidStalkerResponse("Respuesta JSON no válida del portal Stalker")
        }

        val jsVal = parsed["js"]
        val jsMap = jsVal as? Map<*, *>
        val blockMsg = (jsMap?.get("block_msg") ?: parsed["block_msg"])?.toString()?.trim()
        if (!blockMsg.isNullOrEmpty() && !blockMsg.equals("false", ignoreCase = true) && !blockMsg.equals("null", ignoreCase = true)) {
            throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $blockMsg")
        }

        if (jsVal == null || jsVal is Boolean || jsVal is String) {
            val errStr = (jsVal as? String ?: parsed["error"]?.toString() ?: parsed["message"]?.toString() ?: trimmed).lowercase()
            if (errStr.contains("authorization failed") || errStr.contains("token expired") || errStr.contains("unauthorized") || errStr.contains("access denied")) {
                throw NetworkError.TokenExpired("Sesión Stalker expirada o no autorizada")
            }
        }

        return parsed
    }

    private class StalkerCatalogSession(
        val host: String,
        val mac: String,
        initialSession: Map<String, Any>
    ) {
        @Volatile var portalUrl: String = initialSession["portalUrl"] as String
        @Volatile var token: String = initialSession["token"] as String
        @Volatile var device: StalkerDevice = (initialSession["device"] as? StalkerDevice) ?: StalkerDevice()
        private val playlistId: Long? = (initialSession["playlistId"] as? Number)?.toLong()
        private val refreshMutex = Mutex()

        suspend fun requestParsed(
            params: String,
            maxRetries: Int = MAX_RETRIES_PER_PAGE
        ): Map<String, Any?> {
            var attempt = 0
            var tokenRefreshAttempted = false
            while (true) {
                currentCoroutineContext().ensureActive()
                attempt++
                try {
                    return stalkerRequestAndParse(portalUrl, mac, params, token, device)
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (expired: NetworkError.TokenExpired) {
                    if (!tokenRefreshAttempted) {
                        tokenRefreshAttempted = true
                        refreshSession(failedToken = token)
                        attempt = 0
                        continue
                    }
                    throw expired
                } catch (authEx: NetworkError.Auth) {
                    if (!tokenRefreshAttempted) {
                        tokenRefreshAttempted = true
                        refreshSession(failedToken = token)
                        attempt = 0
                        continue
                    }
                    throw authEx
                } catch (notFound: NetworkError.NotFound) {
                    // 404/410 indica acción o parámetro no soportado por este endpoint: fallar rápido al fallback
                    throw notFound
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                    if (attempt > maxRetries) {
                        throw e
                    }
                    val backoffMs = if (e is NetworkError.RateLimit) 450L * attempt else 150L * attempt
                    delay(backoffMs)
                }
            }
        }

        private suspend fun refreshSession(failedToken: String) {
            refreshMutex.withLock {
                if (token != failedToken && token.isNotBlank()) {
                    return
                }
                val newSession = performStalkerHandshake(host, mac, device, playlistId)
                portalUrl = newSession["portalUrl"] as String
                token = newSession["token"] as String
                (newSession["device"] as? StalkerDevice)?.let { device = it }
                if (playlistId != null) {
                    PortalSessionRepository.getInstanceOrNull()?.setStalkerSession(playlistId.toString(), newSession)
                }
            }
        }
    }

    suspend fun performStalkerHandshake(
        host: String,
        mac: String,
        device: StalkerDevice,
        playlistId: Long? = null
    ): Map<String, Any> {
        val macClean = mac.trim().uppercase()
        if (macClean.isEmpty()) throw NetworkError.Auth("Falta la dirección MAC")

        val repo = PortalSessionRepository.getInstanceOrNull()
        val plKey = playlistId?.toString() ?: host
        val savedCompatState = if (playlistId != null) {
            repo?.getStalkerCompatState(playlistId.toString())
                ?.takeIf { it.host.trim().equals(host.trim(), ignoreCase = true) }
        } else null

        // Modelos candidatos ordenados por prioridad:
        // 1. Si existe estado previo aprendido para este portal, probar ese primero.
        // 2. MAG254_COHERENT (perfil 100% coherente)
        // 3. LEGACY_HYBRID (perfil original de la app para máxima compatibilidad histórica)
        // 4. MAG250_COHERENT (clásico)
        // 5. MINISTRA_STB (moderno)
        val candidateModels = mutableListOf<StalkerDeviceModel>()
        if (savedCompatState != null) {
            candidateModels.add(savedCompatState.deviceModel)
        }
        val defaultHierarchy = listOf(
            StalkerDeviceModel.MAG254_COHERENT,
            StalkerDeviceModel.LEGACY_HYBRID,
            StalkerDeviceModel.MAG250_COHERENT,
            StalkerDeviceModel.MINISTRA_STB
        )
        for (m in defaultHierarchy) {
            if (!candidateModels.contains(m)) {
                candidateModels.add(m)
            }
        }

        // Variantes de perfil
        val candidateProfiles = mutableListOf<StalkerProfileVariant>()
        if (savedCompatState != null) {
            candidateProfiles.add(savedCompatState.profileVariant)
        }
        val defaultProfileVariants = listOf(
            StalkerProfileVariant.STANDARD_V1,
            StalkerProfileVariant.SIMPLIFIED_V2,
            StalkerProfileVariant.MINISTRA_MODERN
        )
        for (pv in defaultProfileVariants) {
            if (!candidateProfiles.contains(pv)) {
                candidateProfiles.add(pv)
            }
        }

        // Normalización URI y construcción inteligente de endpoints candidatos
        val candidates = buildStalkerPortalCandidates(host, savedCompatState?.resolvedPortalUrl)

        var portalUrl: String? = null
        var token: String? = null
        var apiSignature = ""
        var apiRandom = ""
        var activeDeviceModel = candidateModels.first()
        var activeDevice = device
        var activeProfileVariant = candidateProfiles.first()
        var bestError: NetworkError? = null

        fun recordError(err: NetworkError) {
            val current = bestError
            if (current == null || errorPriority(err) > errorPriority(current)) {
                bestError = err
            }
        }

        val deadEndpoints = HashSet<String>()
        val jsonResponsiveEndpoints = LinkedHashSet<String>()
        val warmedUpReferers = HashSet<String>()

        // FASE 1: HANDSHAKE DISCOVERY & NEGOCIACIÓN
        outerLoop@ for (modelCand in candidateModels) {
            // Si el usuario especificó parámetros custom en device, respetarlos
            val testDevice = if (device.serialNumber.isNotBlank() || device.hwVersion.isNotBlank()) {
                val customDev = modelCand.toStalkerDevice(device.serialNumber)
                if (device.hwVersion.isNotBlank() && modelCand == StalkerDeviceModel.LEGACY_HYBRID) {
                    customDev.copy(hwVersion = device.hwVersion, vendor = device.vendor.ifEmpty { customDev.vendor })
                } else {
                    customDev
                }
            } else {
                modelCand.toStalkerDevice()
            }

            val snVal = resolveSerialNumber(macClean, testDevice.serialNumber)

            val deviceIdResult = generateDeviceId(macClean, snVal, testDevice.hwVersion)
            val deviceId = deviceIdResult.primary
            val deviceId2 = generateDeviceId2(deviceId, macClean)
            val signature = generateSignature(
                mac = macClean,
                serialNumber = snVal,
                model = testDevice.hwVersion,
                hwVersion = testDevice.hwVersion,
                swVersion = testDevice.swVersion,
                deviceId = deviceId,
                imageVersion = testDevice.imageVersion,
                vendor = testDevice.vendor
            )

            // Priorizar endpoints que ya respondieron JSON válido y descartar los 404/410 definitivos
            val modelCandidates = LinkedHashSet<String>().apply {
                jsonResponsiveEndpoints.filterTo(this) { it !in deadEndpoints }
                candidates.filterTo(this) { it !in deadEndpoints }
            }
            if (modelCandidates.isEmpty()) break

            var timeoutsInModel = 0

            for (cand in modelCandidates) {
                if (cand in deadEndpoints) continue
                var currentCand = sanitizeSchemeAndPort(cand)
                val handshakeParamsCommon = "&device_id=${URLParamEncode(deviceId)}" +
                        "&device_id2=${URLParamEncode(deviceId2)}" +
                        "&signature=${URLParamEncode(signature)}" +
                        "&hw_version=${URLParamEncode(testDevice.hwVersion)}" +
                        "&hw_version_2=${URLParamEncode(testDevice.hwVersion2)}" +
                        "&stb_type=${URLParamEncode(testDevice.hwVersion)}" +
                        "&vendor=${URLParamEncode(testDevice.vendor)}" +
                        "&image_version=${URLParamEncode(testDevice.imageVersion)}" +
                        "&sn=${URLParamEncode(snVal)}"

                val handshakes = listOf(
                    "type=stb&action=handshake&token=&prehash=0$handshakeParamsCommon",
                    "type=stb&action=create_token&token=&prehash=0$handshakeParamsCommon"
                )

                var endpointDead404 = false
                for (hParams in handshakes) {
                    if (endpointDead404) break
                    try {
                        var httpResp = try {
                            stalkerRequestWithResponse(currentCand, macClean, hParams, null, testDevice)
                        } catch (e: Exception) {
                            val msg = e.message?.lowercase() ?: ""
                            if (currentCand.startsWith("https://", ignoreCase = true) &&
                                (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                                val fallbackCand = "http" + currentCand.substring(5)
                                Log.w(TAG, "TLS/SSL error on stalker handshake for $currentCand, trying $fallbackCand")
                                currentCand = fallbackCand
                                stalkerRequestWithResponse(fallbackCand, macClean, hParams, null, testDevice)
                            } else {
                                throw e
                            }
                        }

                        var resp = httpResp.body
                        var parsed = parseStalkerJsResponse(resp)

                        // Si el servidor responde 403/HTML challenge sin cookies de sesión, hacer 1 warm-up GET /c/ y reintentar
                        val refererUrl = derivePortalReferer(currentCand)
                        if ((httpResp.code == 403 || (httpResp.code in 200..299 && parsed.isEmpty() && resp.contains("<html", ignoreCase = true))) &&
                            refererUrl.isNotBlank() && warmedUpReferers.add(refererUrl)) {
                            warmUpPortalRefererCookies(refererUrl, macClean, testDevice)
                            httpResp = stalkerRequestWithResponse(currentCand, macClean, hParams, null, testDevice)
                            resp = httpResp.body
                            parsed = parseStalkerJsResponse(resp)
                        }

                        val js = parsed["js"] as? Map<*, *>
                        val blockMsg = (js?.get("block_msg") ?: parsed["block_msg"])?.toString()?.trim()
                        if (!blockMsg.isNullOrEmpty() && !blockMsg.equals("false", ignoreCase = true) && !blockMsg.equals("null", ignoreCase = true)) {
                            throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $blockMsg")
                        }

                        if (httpResp.code == 404 || httpResp.code == 410) {
                            recordError(NetworkError.NotFound("Endpoint no encontrado (${httpResp.code})"))
                            deadEndpoints.add(cand)
                            deadEndpoints.add(currentCand)
                            endpointDead404 = true
                            break
                        }

                        if (httpResp.code == 401 || httpResp.code == 403) {
                            recordError(NetworkError.Auth("Portal rechazó la autenticación MAC (HTTP ${httpResp.code})"))
                            continue
                        }
                        if (httpResp.code == 429) {
                            recordError(NetworkError.RateLimit("Demasiadas peticiones al portal (429)"))
                            continue
                        }
                        if (httpResp.code >= 500) {
                            recordError(NetworkError.ServerError(httpResp.code, "Error interno del portal Stalker (HTTP ${httpResp.code})"))
                            continue
                        }

                        if (parsed.isNotEmpty()) {
                            jsonResponsiveEndpoints.add(currentCand)
                        } else if (httpResp.code in 200..299) {
                            recordError(NetworkError.InvalidHandshake("El endpoint respondió sin JSON válido de Stalker"))
                        }

                        var t = (js?.get("token") ?: parsed["token"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        var rand = (js?.get("random") ?: parsed["random"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

                        // Challenge-response / prehash step si el portal devolvió random sin token
                        if (t.isNullOrEmpty() && !rand.isNullOrEmpty() && hParams.contains("action=handshake")) {
                            val prehashVal = sha256HexUpper("$rand|$macClean")
                            val prehashParams = "type=stb&action=handshake&token=&prehash=${URLParamEncode(prehashVal)}$handshakeParamsCommon"
                            val preResp = stalkerRequestWithResponse(currentCand, macClean, prehashParams, null, testDevice)
                            val preParsed = parseStalkerJsResponse(preResp.body)
                            val preJs = preParsed["js"] as? Map<*, *>
                            val preBlock = (preJs?.get("block_msg") ?: preParsed["block_msg"])?.toString()?.trim()
                            if (!preBlock.isNullOrEmpty() && !preBlock.equals("false", ignoreCase = true) && !preBlock.equals("null", ignoreCase = true)) {
                                throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $preBlock")
                            }
                            t = (preJs?.get("token") ?: preParsed["token"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                            val preRand = (preJs?.get("random") ?: preParsed["random"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                            if (!preRand.isNullOrEmpty()) rand = preRand
                        }

                        if (!t.isNullOrEmpty()) {
                            token = t
                            portalUrl = currentCand
                            activeDeviceModel = modelCand
                            activeDevice = testDevice
                            if (!rand.isNullOrEmpty()) {
                                apiRandom = rand
                                apiSignature = generateApiSignature(t, rand, macClean)
                            }
                            break@outerLoop
                        } else if (parsed.isNotEmpty()) {
                            if (resp.contains("Authorization failed", ignoreCase = true) ||
                                resp.contains("Access denied", ignoreCase = true) ||
                                resp.contains("Unauthorized", ignoreCase = true)) {
                                recordError(NetworkError.Auth("Acceso denegado por el portal para esta MAC"))
                            } else {
                                recordError(NetworkError.InvalidHandshake("El portal no devolvió token de sesión"))
                            }
                        }
                    } catch (blocked: NetworkError.PortalBlockedMac) {
                        throw blocked
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) {
                            throw e
                        }
                        val classified = classifyStalkerException(e)
                        recordError(classified)
                        when (classified) {
                            is NetworkError.Dns,
                            is NetworkError.ServerDown -> {
                                // Mismo host/puerto no podrá responder en otros paths ni otros modelos
                                throw classified
                            }
                            is NetworkError.Timeout -> {
                                timeoutsInModel++
                                break // pasar al siguiente endpoint o salir si todos timeout
                            }
                            else -> {}
                        }
                    }
                }
            }
            if (timeoutsInModel >= modelCandidates.size && jsonResponsiveEndpoints.isEmpty()) {
                break@outerLoop
            }
        }

        val finalPortalUrl = portalUrl ?: throw (bestError ?: NetworkError.ServerDown("No se pudo contactar con el portal Stalker"))
        val finalToken = token ?: ""

        // FASE 2: GET_PROFILE NEGOCIACIÓN
        val snVal = resolveSerialNumber(macClean, activeDevice.serialNumber)
        val deviceIdResult = generateDeviceId(macClean, snVal, activeDevice.hwVersion)
        val deviceId = deviceIdResult.primary
        val deviceId2 = generateDeviceId2(deviceId, macClean)
        val signature = generateSignature(
            mac = macClean,
            serialNumber = snVal,
            model = activeDevice.hwVersion,
            hwVersion = activeDevice.hwVersion,
            swVersion = activeDevice.swVersion,
            deviceId = deviceId,
            imageVersion = activeDevice.imageVersion,
            vendor = activeDevice.vendor
        )
        val metrics = "{\"mac\":\"$macClean\",\"model\":\"${activeDevice.hwVersion}\",\"vendor\":\"${activeDevice.vendor}\"}"

        fun isTruthyFlag(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toInt() == 1
            else -> {
                val s = v.toString().trim()
                s.equals("true", ignoreCase = true) || cleanNumberString(s) == "1"
            }
        }

        var profileSuccess = false
        var activeToken = finalToken
        var profileAuthRejections = 0

        for (profVariant in candidateProfiles) {
            fun buildProfileParams(authSecondStep: Int): String {
                val apiSigValue = apiSignature.ifEmpty { "262" }
                return when (profVariant) {
                    StalkerProfileVariant.STANDARD_V1 -> {
                        "type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200%20version&num_banks=2" +
                                "&stb_type=${URLParamEncode(activeDevice.hwVersion)}&image_version=${URLParamEncode(activeDevice.imageVersion)}" +
                                "&auth_second_step=$authSecondStep&hw_version=${URLParamEncode(activeDevice.hwVersion)}&hw_version_2=${URLParamEncode(activeDevice.hwVersion2)}&not_valid_token=0" +
                                "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                                "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"
                    }
                    StalkerProfileVariant.SIMPLIFIED_V2 -> {
                        "type=stb&action=get_profile&hd=1&num_banks=2" +
                                "&stb_type=${URLParamEncode(activeDevice.hwVersion)}&image_version=${URLParamEncode(activeDevice.imageVersion)}" +
                                "&auth_second_step=$authSecondStep&hw_version=${URLParamEncode(activeDevice.hwVersion)}&hw_version_2=${URLParamEncode(activeDevice.hwVersion2)}&not_valid_token=0" +
                                "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                                "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"
                    }
                    StalkerProfileVariant.MINISTRA_MODERN -> {
                        "type=stb&action=get_profile&hd=1&client_type=STB&ver=5.1.0" +
                                "&stb_type=${URLParamEncode(activeDevice.hwVersion)}&image_version=${URLParamEncode(activeDevice.imageVersion)}" +
                                "&auth_second_step=$authSecondStep&hw_version=${URLParamEncode(activeDevice.hwVersion)}&hw_version_2=${URLParamEncode(activeDevice.hwVersion2)}&not_valid_token=0" +
                                "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                                "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"
                    }
                }
            }

            try {
                var profHttp = stalkerRequestWithResponse(finalPortalUrl, macClean, buildProfileParams(0), activeToken, activeDevice)
                if (profHttp.code == 401 || profHttp.code == 403) {
                    val errParsed = parseStalkerJsResponse(profHttp.body)
                    val errJs = errParsed["js"] as? Map<*, *>
                    val blockMsg = (errJs?.get("block_msg") ?: errParsed["block_msg"])?.toString()?.trim()
                    if (!blockMsg.isNullOrEmpty() && !blockMsg.equals("false", ignoreCase = true) && !blockMsg.equals("null", ignoreCase = true)) {
                        throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $blockMsg")
                    }
                    profileAuthRejections++
                    continue
                }
                if (profHttp.code !in 200..299) {
                    throw NetworkError.ServerError(profHttp.code, "El portal Stalker devolvió un error (HTTP ${profHttp.code})")
                }

                var profParsed = parseStalkerJsResponse(profHttp.body)
                var jsProf = profParsed["js"] as? Map<*, *>

                val blockMsg = (jsProf?.get("block_msg") ?: profParsed["block_msg"])?.toString()?.trim()
                if (!blockMsg.isNullOrEmpty() && !blockMsg.equals("false", ignoreCase = true) && !blockMsg.equals("null", ignoreCase = true)) {
                    throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $blockMsg")
                }

                // Soporte de verificación auth_second_step=1 si el portal lo solicita explícitamente
                val requiresSecondStep = isTruthyFlag(jsProf?.get("auth_second_step")) ||
                    isTruthyFlag(jsProf?.get("not_valid_token"))
                if (requiresSecondStep) {
                    if (apiRandom.isNotBlank()) {
                        apiSignature = generateApiSignature(activeToken, apiRandom, macClean)
                    }
                    profHttp = stalkerRequestWithResponse(finalPortalUrl, macClean, buildProfileParams(1), activeToken, activeDevice)
                    if (profHttp.code == 401 || profHttp.code == 403) {
                        profileAuthRejections++
                        continue
                    }
                    if (profHttp.code in 200..299) {
                        profParsed = parseStalkerJsResponse(profHttp.body)
                        jsProf = profParsed["js"] as? Map<*, *>
                        val blockMsg2 = (jsProf?.get("block_msg") ?: profParsed["block_msg"])?.toString()?.trim()
                        if (!blockMsg2.isNullOrEmpty() && !blockMsg2.equals("false", ignoreCase = true) && !blockMsg2.equals("null", ignoreCase = true)) {
                            throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: $blockMsg2")
                        }
                    }
                }

                val bodyLower = profHttp.body.lowercase()
                if (profParsed.isEmpty() && (bodyLower.contains("authorization failed") || bodyLower.contains("access denied") || bodyLower.contains("unauthorized"))) {
                    profileAuthRejections++
                    continue
                }

                activeToken = jsProf?.get("token")?.toString()?.ifEmpty { activeToken } ?: activeToken
                activeProfileVariant = profVariant
                profileSuccess = true
                break
            } catch (authEx: NetworkError.PortalBlockedMac) {
                throw authEx
            } catch (e: Exception) {
                Log.w(TAG, "Profile variant $profVariant failed on $finalPortalUrl: ${e.message}")
            }
        }

        if (!profileSuccess && profileAuthRejections == candidateProfiles.size) {
            throw NetworkError.Auth("El portal rechazó el perfil STB o la dirección MAC en get_profile")
        }

        // FASE 3: PERSISTENCIA DEL ESTADO APRENDIDO EN PortalSessionRepository
        val effectiveScheme = if (finalPortalUrl.startsWith("https", ignoreCase = true)) "https" else "http"
        val stateToSave = PortalCompatibilityState(
            playlistId = playlistId ?: 0L,
            host = host,
            resolvedPortalUrl = finalPortalUrl,
            effectiveScheme = effectiveScheme,
            deviceModel = activeDeviceModel,
            profileVariant = activeProfileVariant,
            authMode = StalkerAuthMode.BOTH_HEADER_AND_COOKIE
        )
        if (playlistId != null && repo != null) {
            repo.setStalkerCompatState(playlistId.toString(), stateToSave)
        }

        val capturedCookies = getPortalCookiesSnapshot(finalPortalUrl, macClean)
        val resultMap = mutableMapOf<String, Any>(
            "portalUrl" to finalPortalUrl,
            "mac" to macClean,
            "token" to activeToken,
            "device" to activeDevice
        )
        if (playlistId != null) {
            resultMap["playlistId"] = playlistId
        }
        if (capturedCookies.isNotEmpty()) {
            resultMap["cookies"] = capturedCookies
        }
        return resultMap
    }

    suspend fun fetchStalkerTv(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> {

        val sessionMap = existingSession ?: performStalkerHandshake(host, mac, device)
        val macClean = sessionMap["mac"] as String
        val activeDevice = (sessionMap["device"] as? StalkerDevice) ?: device
        val catalogSession = StalkerCatalogSession(
            host = host,
            mac = macClean,
            initialSession = sessionMap + ("device" to activeDevice)
        )
        var unrecoveredAuthError: Exception? = null

        onProgress("Obteniendo categorías de TV...")
        var rawGenres = emptyList<Any>()
        val genresAttempts = listOf(
            "type=itv&action=get_genres",
            "type=itv&action=get_categories"
        )
        for (params in genresAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val genresParsed = catalogSession.requestParsed(params, maxRetries = 1)
                val list = extractListFromJsField(genresParsed)
                if (list.isNotEmpty()) {
                    rawGenres = list
                    break
                }
            } catch (blocked: NetworkError.PortalBlockedMac) {
                throw blocked
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is NetworkError.TokenExpired || e is NetworkError.Auth) {
                    unrecoveredAuthError = e
                }
                Log.e(TAG, "Error fetching ITV genres with params: $params", e)
            }
        }

        val genreMap = LinkedHashMap<String, String>()
        rawGenres.forEach { g ->
            val m = g as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString() ?: m["tv_genre_id"]?.toString() ?: ""
            val title = m["title"]?.toString() ?: m["name"]?.toString() ?: ""
            if (id.isNotEmpty() && title.isNotEmpty()) {
                genreMap[id] = title
            }
        }

        onProgress("Descargando canales TV...")
        val rawChannels = ArrayList<Any>()
        val seenChKeys = HashSet<String>()
        var tvStrategyUsed = "get_all_channels"
        var fallbackUsed = "none"

        // Regla 6: Mantener get_all_channels como estrategia preferente cuando funcione.
        // NO paginar TV si get_all_channels devuelve correctamente el catálogo.
        val chAttempts = listOf(
            "type=itv&action=get_all_channels",
            "type=itv&action=get_channels",
            "type=itv&action=get_short_channels"
        )
        for (params in chAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val chParsed = catalogSession.requestParsed(params)
                val list = extractListFromJsField(chParsed)
                if (list.isNotEmpty()) {
                    list.forEach { c ->
                        val m = c as? Map<*, *> ?: return@forEach
                        val id = m["id"]?.toString() ?: m["number"]?.toString() ?: m["name"]?.toString() ?: ""
                        val cmd = m["cmd"]?.toString() ?: m["url"]?.toString() ?: ""
                        val key = "$id|$cmd"
                        if (seenChKeys.add(key)) {
                            rawChannels.add(c)
                        }
                    }
                    if (rawChannels.isNotEmpty()) {
                        tvStrategyUsed = params.substringAfter("action=").substringBefore("&")
                        break
                    }
                }
            } catch (blocked: NetworkError.PortalBlockedMac) {
                throw blocked
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is NetworkError.TokenExpired || e is NetworkError.Auth) {
                    unrecoveredAuthError = e
                }
                Log.e(TAG, "Error fetching ITV channels with params: $params", e)
            }
        }

        // Si get_all_channels falló y hay géneros, paginación adaptativa completa y concurrente por género
        if (rawChannels.isEmpty() && genreMap.isNotEmpty()) {
            fallbackUsed = "genres_pagination"
            tvStrategyUsed = "get_ordered_list_genre"

            val probeGenreId = genreMap.keys.first()
            var tvFilterKey = "genre"
            var tvPageSizeParam = "&max_rows=$INITIAL_PAGE_SIZE"
            var probeList = emptyList<Any>()
            val probeConfigs = listOf(
                "genre" to "&max_rows=$INITIAL_PAGE_SIZE",
                "genre" to "&max_rows=$FALLBACK_PAGE_SIZE",
                "genre" to "",
                "category" to "&max_rows=$INITIAL_PAGE_SIZE",
                "category" to ""
            )
            for ((fKey, sizeParam) in probeConfigs) {
                currentCoroutineContext().ensureActive()
                try {
                    val probeParsed = catalogSession.requestParsed(
                        "type=itv&action=get_ordered_list&$fKey=$probeGenreId&p=1$sizeParam",
                        maxRetries = 1
                    )
                    val list = extractListFromJsField(probeParsed)
                    if (list.isNotEmpty()) {
                        tvFilterKey = fKey
                        tvPageSizeParam = sizeParam
                        probeList = list
                        break
                    }
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is NetworkError.TokenExpired || e is NetworkError.Auth) {
                        unrecoveredAuthError = e
                    }
                }
            }

            val probeSeenKeys = HashSet<String>()
            if (probeList.isNotEmpty()) {
                probeList.forEach { c ->
                    val m = c as? Map<*, *> ?: return@forEach
                    val id = m["id"]?.toString() ?: m["number"]?.toString() ?: m["name"]?.toString() ?: ""
                    val cmd = m["cmd"]?.toString() ?: m["url"]?.toString() ?: ""
                    val key = "$id|$cmd"
                    probeSeenKeys.add(key)
                    if (seenChKeys.add(key)) {
                        rawChannels.add(c)
                    }
                }
            }

            val downloadSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            coroutineScope {
                genreMap.keys.map { genreId ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            val seenInGenre = if (genreId == probeGenreId && probeList.isNotEmpty()) {
                                HashSet(probeSeenKeys)
                            } else {
                                HashSet()
                            }
                            var gPage = if (genreId == probeGenreId && probeList.isNotEmpty()) 2 else 1
                            var gHasMore = true
                            while (gHasMore && gPage <= 1000) {
                                currentCoroutineContext().ensureActive()
                                val chParsed = try {
                                    catalogSession.requestParsed(
                                        "type=itv&action=get_ordered_list&$tvFilterKey=$genreId&p=$gPage$tvPageSizeParam"
                                    )
                                } catch (blocked: NetworkError.PortalBlockedMac) {
                                    throw blocked
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    if (e is NetworkError.TokenExpired || e is NetworkError.Auth) {
                                        unrecoveredAuthError = e
                                    }
                                    Log.e(TAG, "Error fetching channels for genre $genreId page $gPage", e)
                                    gHasMore = false
                                    break
                                }
                                val list = extractListFromJsField(chParsed)
                                if (list.isEmpty()) {
                                    gHasMore = false
                                } else {
                                    var genreNewCount = 0
                                    synchronized(rawChannels) {
                                        list.forEach { c ->
                                            val m = c as? Map<*, *> ?: return@forEach
                                            val id = m["id"]?.toString() ?: m["number"]?.toString() ?: m["name"]?.toString() ?: ""
                                            val cmd = m["cmd"]?.toString() ?: m["url"]?.toString() ?: ""
                                            val key = "$id|$cmd"
                                            if (seenInGenre.add(key)) {
                                                genreNewCount++
                                            }
                                            if (seenChKeys.add(key)) {
                                                rawChannels.add(c)
                                            }
                                        }
                                    }
                                    if (genreNewCount == 0) {
                                        gHasMore = false
                                    } else {
                                        gPage++
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // Si sigue vacío, fallback a paginación global adaptativa
        if (rawChannels.isEmpty()) {
            fallbackUsed = "global_pagination"
            tvStrategyUsed = "get_ordered_list"
            var chPage = 1
            var chHasMore = true
            while (chHasMore && chPage <= 1000) {
                currentCoroutineContext().ensureActive()
                var list: List<Any> = emptyList()
                try {
                    val chParsed = catalogSession.requestParsed(
                        "type=itv&action=get_ordered_list&genre=0&p=$chPage&max_rows=$INITIAL_PAGE_SIZE"
                    )
                    list = extractListFromJsField(chParsed)
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is NetworkError.TokenExpired || e is NetworkError.Auth) {
                        unrecoveredAuthError = e
                    }
                }
                if (list.isEmpty()) {
                    try {
                        val chParsedAlt = catalogSession.requestParsed(
                            "type=itv&action=get_ordered_list&p=$chPage"
                        )
                        list = extractListFromJsField(chParsedAlt)
                    } catch (blocked: NetworkError.PortalBlockedMac) {
                        throw blocked
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (e is NetworkError.TokenExpired || e is NetworkError.Auth) {
                            unrecoveredAuthError = e
                        }
                        chHasMore = false
                        break
                    }
                }
                if (list.isEmpty()) {
                    chHasMore = false
                } else {
                    var newCount = 0
                    list.forEach { c ->
                        val m = c as? Map<*, *> ?: return@forEach
                        val id = m["id"]?.toString() ?: m["number"]?.toString() ?: m["name"]?.toString() ?: ""
                        val cmd = m["cmd"]?.toString() ?: m["url"]?.toString() ?: ""
                        val key = "$id|$cmd"
                        if (seenChKeys.add(key)) {
                            rawChannels.add(c)
                            newCount++
                        }
                    }
                    if (newCount == 0) {
                        chHasMore = false
                    } else {
                        chPage++
                    }
                }
            }
        }

        if (rawChannels.isEmpty() && unrecoveredAuthError != null) {
            throw unrecoveredAuthError!!
        }

        val channels = ArrayList<IptvItem>(rawChannels.size)
        rawChannels.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val genreId = m["tv_genre_id"]?.toString()
                ?: m["category_id"]?.toString()
                ?: m["genre_id"]?.toString()
                ?: ""
            var cmd = m["cmd"]?.toString()?.trim() ?: ""
            if (cmd.isEmpty()) {
                val candidateUrl = m["url"]?.toString()?.trim()
                    ?: m["uri"]?.toString()?.trim()
                    ?: m["path"]?.toString()?.trim()
                    ?: ""
                if (candidateUrl.isNotEmpty()) {
                    cmd = candidateUrl
                }
            }
            if (cmd.isEmpty()) {
                cmd = "CHANNEL_NO_PLAYABLE_CMD"
            }

            val name = m["name"]?.toString()
                ?: m["title"]?.toString()
                ?: m["caption"]?.toString()
                ?: "Sin nombre"

            val logo = m["logo"]?.toString()
                ?: m["screenshot_uri"]?.toString()
                ?: m["icon"]?.toString()
            val number = m["number"]?.toString()
                ?: m["ch_id"]?.toString()
                ?: m["id"]?.toString()
            val xmltvId = m["xmltv_id"]?.toString()
                ?: m["epg_id"]?.toString()

            channels.add(IptvItem(
                name = name,
                group = genreMap[genreId] ?: "General",
                logo = logo,
                tvgId = xmltvId,
                tvgChno = number,
                stalkerCmd = cmd
            ))
        }

        onPartialUpdate?.invoke("tv", ArrayList(channels))
        return channels
    }

    suspend fun fetchStalkerVod(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> {

        val sessionMap = existingSession ?: performStalkerHandshake(host, mac, device)
        val macClean = sessionMap["mac"] as String
        val activeDevice = (sessionMap["device"] as? StalkerDevice) ?: device
        val catalogSession = StalkerCatalogSession(
            host = host,
            mac = macClean,
            initialSession = sessionMap + ("device" to activeDevice)
        )

        onProgress("Obteniendo categorías VOD...")
        var rawVodCats = emptyList<Any>()
        val vodCatAttempts = listOf(
            "type=vod&action=get_categories",
            "type=vod&action=get_genres"
        )
        for (params in vodCatAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val vodCatParsed = catalogSession.requestParsed(params, maxRetries = 1)
                val list = extractListFromJsField(vodCatParsed)
                if (list.isNotEmpty()) {
                    rawVodCats = list
                    break
                }
            } catch (blocked: NetworkError.PortalBlockedMac) {
                throw blocked
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error fetching VOD categories with params: $params", e)
            }
        }

        val vodCatMap = LinkedHashMap<String, String>()
        rawVodCats.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString() ?: m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: ""
            val title = m["title"]?.toString() ?: m["name"]?.toString() ?: m["category_name"]?.toString() ?: ""
            if (id.isNotEmpty() && title.isNotEmpty() && id != "*" && id != "all" && id != "0") {
                vodCatMap[id] = title
            }
        }

        val pagesRequested = AtomicInteger(0)
        val pagesCompleted = AtomicInteger(0)
        val pagesFailed = AtomicInteger(0)
        val movies = Collections.synchronizedList(ArrayList<IptvItem>())
        val seenMovies = ConcurrentHashMap.newKeySet<String>()

        var activeStrategy = "category"
        var fallbackUsed = "none"
        var activePageSize = INITIAL_PAGE_SIZE
        var pageSizeParam = "&max_rows=$INITIAL_PAGE_SIZE"

        // ─── DETECCIÓN INTELIGENTE DE ESTRATEGIA Y TAMAÑO DE PÁGINA ───
        if (vodCatMap.isNotEmpty()) {
            val probeCatId = vodCatMap.keys.first()
            val probeCatName = vodCatMap[probeCatId] ?: probeCatId

            var detected = false
            var probeList = emptyList<Any>()

            val vodProbeConfigs = listOf(
                Triple("category", "&max_rows=$INITIAL_PAGE_SIZE", "none"),
                Triple("genre", "&max_rows=$INITIAL_PAGE_SIZE", "genre"),
                Triple("category", "&max_rows=$FALLBACK_PAGE_SIZE", "fallback_page_size_50"),
                Triple("category", "", "no_max_rows"),
                Triple("genre", "", "no_max_rows_genre")
            )
            for ((strategyKey, sizeParam, fbLabel) in vodProbeConfigs) {
                currentCoroutineContext().ensureActive()
                pagesRequested.incrementAndGet()
                try {
                    val probeParsed = catalogSession.requestParsed(
                        "type=vod&action=get_ordered_list&$strategyKey=$probeCatId&p=1$sizeParam",
                        maxRetries = 1
                    )
                    val list = extractListFromJsField(probeParsed)
                    if (list.isNotEmpty()) {
                        pagesCompleted.incrementAndGet()
                        probeList = list
                        activeStrategy = strategyKey
                        fallbackUsed = fbLabel
                        pageSizeParam = sizeParam
                        activePageSize = when (sizeParam) {
                            "&max_rows=$INITIAL_PAGE_SIZE" -> INITIAL_PAGE_SIZE
                            "&max_rows=$FALLBACK_PAGE_SIZE" -> FALLBACK_PAGE_SIZE
                            else -> list.size
                        }
                        detected = true
                        break
                    }
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    pagesFailed.incrementAndGet()
                }
            }
            if (!detected && probeList.isEmpty()) {
                pagesFailed.incrementAndGet()
            }

            // Procesar la página detectada si trajo items para no perderla
            val probeSeenInCat = HashSet<String>()
            if (probeList.isNotEmpty()) {
                val catName = vodCatMap[probeCatId] ?: "General"
                probeList.forEach { mo ->
                    val m = mo as? Map<*, *> ?: return@forEach
                    val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                    val cmd = m["cmd"]?.toString() ?: ""
                    val id = m["id"]?.toString() ?: m["movie_id"]?.toString() ?: m["vod_id"]?.toString() ?: ""
                    val key = if (id.isNotEmpty()) "$id|$cmd" else "$name|$cmd"
                    probeSeenInCat.add(key)
                    if (seenMovies.add(key)) {
                        val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: probeCatId
                        movies.add(IptvItem(
                            name = name,
                            group = vodCatMap[cId] ?: catName,
                            logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                            stalkerCmd = m["cmd"]?.toString(),
                            year = m["year"]?.toString()?.toIntOrNull(),
                            rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                        ))
                    }
                }
            }

            // ─── DESCARGA CONCURRENTE CONTROLADA POR CATEGORÍAS ───
            val downloadSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            coroutineScope {
                vodCatMap.entries.map { (catId, catName) ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            val seenInCat = if (catId == probeCatId && probeList.isNotEmpty()) {
                                HashSet(probeSeenInCat)
                            } else {
                                HashSet()
                            }
                            var catPage = if (catId == probeCatId && probeList.isNotEmpty()) 2 else 1
                            var catHasMore = true
                            while (catHasMore && catPage <= 1000) {
                                currentCoroutineContext().ensureActive()
                                val queryParams = "type=vod&action=get_ordered_list&$activeStrategy=$catId&p=$catPage$pageSizeParam"
                                pagesRequested.incrementAndGet()

                                var rawMovies = emptyList<Any>()
                                var success = false
                                try {
                                    val vodParsed = catalogSession.requestParsed(queryParams)
                                    rawMovies = extractListFromJsField(vodParsed)
                                    success = true
                                    pagesCompleted.incrementAndGet()
                                } catch (blocked: NetworkError.PortalBlockedMac) {
                                    throw blocked
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    pagesFailed.incrementAndGet()
                                    Log.w(TAG, "Failed page $catPage for cat $catId after retries: ${e.message}")
                                }

                                if (!success || rawMovies.isEmpty()) {
                                    catHasMore = false
                                } else {
                                    var catNewCount = 0
                                    rawMovies.forEach { mo ->
                                        val m = mo as? Map<*, *> ?: return@forEach
                                        val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                                        val cmd = m["cmd"]?.toString() ?: ""
                                        val id = m["id"]?.toString() ?: m["movie_id"]?.toString() ?: m["vod_id"]?.toString() ?: ""
                                        val key = if (id.isNotEmpty()) "$id|$cmd" else "$name|$cmd"
                                        if (seenInCat.add(key)) {
                                            catNewCount++
                                        }
                                        if (seenMovies.add(key)) {
                                            val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: catId
                                            movies.add(IptvItem(
                                                name = name,
                                                group = vodCatMap[cId] ?: catName,
                                                logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                                                stalkerCmd = m["cmd"]?.toString(),
                                                year = m["year"]?.toString()?.toIntOrNull(),
                                                rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                                            ))
                                        }
                                    }

                                    if (catNewCount == 0) {
                                        catHasMore = false
                                    } else {
                                        catPage++
                                        if (movies.size % 200 == 0 || movies.size < 500) {
                                            onProgress("Descargando VOD (${movies.size} películas)...")
                                            onPartialUpdate?.invoke("movie", ArrayList(movies))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // ─── REGLA 1: ELIMINAR DESCARGAS REDUNDANTES ───
        // Si la descarga por categorías ha producido un catálogo válido, NO volver a descargar globalmente.
        // El recorrido global solo debe utilizarse como fallback si las categorías no produjeron resultados válidos.
        if (movies.isEmpty()) {
            fallbackUsed = "global_pagination_fallback"

            val globalStrategies = listOf(
                "type=vod&action=get_ordered_list",
                "type=vod&action=get_ordered_list&category=0",
                "type=vod&action=get_ordered_list&genre=0",
                "type=vod&action=get_ordered_list&category=*",
                "type=vod&action=get_ordered_list&genre=*"
            )
            var chosenGlobalPrefix = globalStrategies.first()
            for (strategy in globalStrategies) {
                currentCoroutineContext().ensureActive()
                try {
                    val testParsed = catalogSession.requestParsed("$strategy&p=1$pageSizeParam", maxRetries = 1)
                    val testList = extractListFromJsField(testParsed)
                    if (testList.isNotEmpty()) {
                        chosenGlobalPrefix = strategy
                        break
                    }
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            var mPage = 1
            var mHasMore = true
            while (mHasMore && mPage <= 1500) {
                currentCoroutineContext().ensureActive()
                pagesRequested.incrementAndGet()
                var rawMovies = emptyList<Any>()
                var pageOk = false
                try {
                    val vodParsed = catalogSession.requestParsed("$chosenGlobalPrefix&p=$mPage$pageSizeParam")
                    rawMovies = extractListFromJsField(vodParsed)
                    pageOk = true
                    pagesCompleted.incrementAndGet()
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    pagesFailed.incrementAndGet()
                }

                if (!pageOk || rawMovies.isEmpty()) {
                    mHasMore = false
                } else {
                    var newCount = 0
                    rawMovies.forEach { mo ->
                        val m = mo as? Map<*, *> ?: return@forEach
                        val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                        val cmd = m["cmd"]?.toString() ?: ""
                        val id = m["id"]?.toString() ?: m["movie_id"]?.toString() ?: m["vod_id"]?.toString() ?: ""
                        val key = if (id.isNotEmpty()) "$id|$cmd" else "$name|$cmd"
                        if (seenMovies.add(key)) {
                            newCount++
                            val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: ""
                            movies.add(IptvItem(
                                name = name,
                                group = vodCatMap[cId] ?: "General",
                                logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                                stalkerCmd = m["cmd"]?.toString(),
                                year = m["year"]?.toString()?.toIntOrNull(),
                                rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                            ))
                        }
                    }
                    if (newCount == 0) {
                        mHasMore = false
                    } else {
                        mPage++
                        onProgress("Descargando VOD (${movies.size} películas)...")
                        onPartialUpdate?.invoke("movie", ArrayList(movies))
                    }
                }
            }
        }

        onPartialUpdate?.invoke("movie", ArrayList(movies))
        return movies
    }

    suspend fun fetchStalkerSeries(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> {

        val sessionMap = existingSession ?: performStalkerHandshake(host, mac, device)
        val macClean = sessionMap["mac"] as String
        val activeDevice = (sessionMap["device"] as? StalkerDevice) ?: device
        val catalogSession = StalkerCatalogSession(
            host = host,
            mac = macClean,
            initialSession = sessionMap + ("device" to activeDevice)
        )

        onProgress("Obteniendo categorías de series...")
        var rawSeriesCats = emptyList<Any>()
        val seriesCatAttempts = listOf(
            "type=series&action=get_categories",
            "type=series&action=get_genres",
            "type=vod&action=get_categories&movie_type=series",
            "type=vod&action=get_genres&movie_type=series"
        )
        for (params in seriesCatAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val seriesCatParsed = catalogSession.requestParsed(params, maxRetries = 1)
                val list = extractListFromJsField(seriesCatParsed)
                if (list.isNotEmpty()) {
                    rawSeriesCats = list
                    break
                }
            } catch (blocked: NetworkError.PortalBlockedMac) {
                throw blocked
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error fetching series categories with params: $params", e)
            }
        }

        val seriesCatMap = LinkedHashMap<String, String>()
        rawSeriesCats.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString() ?: m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: ""
            val title = m["title"]?.toString() ?: m["name"]?.toString() ?: m["category_name"]?.toString() ?: ""
            if (id.isNotEmpty() && title.isNotEmpty() && id != "*" && id != "all" && id != "0") {
                seriesCatMap[id] = title
            }
        }

        val pagesRequested = AtomicInteger(0)
        val pagesCompleted = AtomicInteger(0)
        val pagesFailed = AtomicInteger(0)
        val series = Collections.synchronizedList(ArrayList<IptvItem>())
        val seenSeries = ConcurrentHashMap.newKeySet<String>()

        var activeStrategyPrefix = "type=series&action=get_ordered_list&category="
        var fallbackUsed = "none"
        var activePageSize = INITIAL_PAGE_SIZE
        var pageSizeParam = "&max_rows=$INITIAL_PAGE_SIZE"

        // ─── DETECCIÓN INTELIGENTE DE ESTRATEGIA Y TAMAÑO DE PÁGINA ───
        if (seriesCatMap.isNotEmpty()) {
            val probeCatId = seriesCatMap.keys.first()
            var detected = false

            val candidatePrefixes = listOf(
                "type=series&action=get_ordered_list&category=",
                "type=vod&action=get_ordered_list&movie_type=series&category=",
                "type=series&action=get_ordered_list&genre=",
                "type=vod&action=get_ordered_list&movie_type=series&genre="
            )

            var probeList = emptyList<Any>()
            for ((idx, prefix) in candidatePrefixes.withIndex()) {
                currentCoroutineContext().ensureActive()
                pagesRequested.incrementAndGet()
                try {
                    val probeParsed = catalogSession.requestParsed("$prefix$probeCatId&p=1&max_rows=$INITIAL_PAGE_SIZE", maxRetries = 1)
                    val list = extractListFromJsField(probeParsed)
                    if (list.isNotEmpty()) {
                        pagesCompleted.incrementAndGet()
                        probeList = list
                        activeStrategyPrefix = prefix
                        fallbackUsed = if (idx == 0) "none" else "prefix_variant_$idx"
                        detected = true
                        break
                    }
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    pagesFailed.incrementAndGet()
                }
            }

            // Si falló con max_rows=100, probar con 50 o sin max_rows
            if (!detected) {
                for (prefix in candidatePrefixes) {
                    currentCoroutineContext().ensureActive()
                    pagesRequested.incrementAndGet()
                    try {
                        val probeParsed = catalogSession.requestParsed("$prefix$probeCatId&p=1&max_rows=$FALLBACK_PAGE_SIZE", maxRetries = 1)
                        val list = extractListFromJsField(probeParsed)
                        if (list.isNotEmpty()) {
                            pagesCompleted.incrementAndGet()
                            probeList = list
                            activeStrategyPrefix = prefix
                            activePageSize = FALLBACK_PAGE_SIZE
                            pageSizeParam = "&max_rows=$FALLBACK_PAGE_SIZE"
                            fallbackUsed = "fallback_page_size_50"
                            detected = true
                            break
                        }
                    } catch (blocked: NetworkError.PortalBlockedMac) {
                        throw blocked
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        pagesFailed.incrementAndGet()
                    }
                }
            }

            if (!detected) {
                for (prefix in candidatePrefixes) {
                    currentCoroutineContext().ensureActive()
                    pagesRequested.incrementAndGet()
                    try {
                        val probeParsed = catalogSession.requestParsed("$prefix$probeCatId&p=1", maxRetries = 1)
                        val list = extractListFromJsField(probeParsed)
                        if (list.isNotEmpty()) {
                            pagesCompleted.incrementAndGet()
                            probeList = list
                            activeStrategyPrefix = prefix
                            activePageSize = list.size
                            pageSizeParam = ""
                            fallbackUsed = "no_max_rows"
                            detected = true
                            break
                        }
                    } catch (blocked: NetworkError.PortalBlockedMac) {
                        throw blocked
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        pagesFailed.incrementAndGet()
                    }
                }
            }

            // Procesar la página detectada si trajo items para no perderla
            val probeSeenInCat = HashSet<String>()
            if (probeList.isNotEmpty()) {
                val catName = seriesCatMap[probeCatId] ?: "General"
                probeList.forEach { se ->
                    val m = se as? Map<*, *> ?: return@forEach
                    val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                    val cmd = m["cmd"]?.toString() ?: ""
                    var cleanId = m["id"]?.toString()
                        ?: m["series_id"]?.toString()
                        ?: m["movie_id"]?.toString()
                        ?: m["vod_id"]?.toString()
                        ?: m["id_series"]?.toString()
                        ?: m["video_id"]?.toString()
                        ?: ""
                    if (cleanId.isEmpty()) {
                        val cmdStr = m["cmd"]?.toString() ?: ""
                        val matchNum = Regex("(\\d+)").find(cmdStr)
                        if (matchNum != null) {
                            cleanId = matchNum.value
                        }
                    }
                    val key = if (cleanId.isNotEmpty()) "$cleanId|$cmd" else "$name|$cmd"
                    probeSeenInCat.add(key)
                    if (seenSeries.add(key)) {
                        val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: probeCatId
                        series.add(IptvItem(
                            name = name,
                            group = seriesCatMap[cId] ?: catName,
                            logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                            stalkerCmd = m["cmd"]?.toString(),
                            seriesId = cleanId,
                            year = m["year"]?.toString()?.toIntOrNull(),
                            rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                        ))
                    }
                }
            }

            // ─── DESCARGA CONCURRENTE CONTROLADA POR CATEGORÍAS ───
            val downloadSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            coroutineScope {
                seriesCatMap.entries.map { (catId, catName) ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            val seenInCat = if (catId == probeCatId && probeList.isNotEmpty()) {
                                HashSet(probeSeenInCat)
                            } else {
                                HashSet()
                            }
                            var sCatPage = if (catId == probeCatId && probeList.isNotEmpty()) 2 else 1
                            var sCatHasMore = true
                            while (sCatHasMore && sCatPage <= 1000) {
                                currentCoroutineContext().ensureActive()
                                val queryParams = "$activeStrategyPrefix$catId&p=$sCatPage$pageSizeParam"
                                pagesRequested.incrementAndGet()

                                var rawSeries = emptyList<Any>()
                                var success = false
                                try {
                                    val seriesParsed = catalogSession.requestParsed(queryParams)
                                    rawSeries = extractListFromJsField(seriesParsed)
                                    success = true
                                    pagesCompleted.incrementAndGet()
                                } catch (blocked: NetworkError.PortalBlockedMac) {
                                    throw blocked
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    pagesFailed.incrementAndGet()
                                    Log.w(TAG, "Failed page $sCatPage for series cat $catId after retries: ${e.message}")
                                }

                                if (!success || rawSeries.isEmpty()) {
                                    sCatHasMore = false
                                } else {
                                    var catNewCount = 0
                                    rawSeries.forEach { se ->
                                        val m = se as? Map<*, *> ?: return@forEach
                                        val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                                        val cmd = m["cmd"]?.toString() ?: ""
                                        var cleanId = m["id"]?.toString()
                                            ?: m["series_id"]?.toString()
                                            ?: m["movie_id"]?.toString()
                                            ?: m["vod_id"]?.toString()
                                            ?: m["id_series"]?.toString()
                                            ?: m["video_id"]?.toString()
                                            ?: ""
                                        if (cleanId.isEmpty()) {
                                            val cmdStr = m["cmd"]?.toString() ?: ""
                                            val matchNum = Regex("(\\d+)").find(cmdStr)
                                            if (matchNum != null) {
                                                cleanId = matchNum.value
                                            }
                                        }
                                        val key = if (cleanId.isNotEmpty()) "$cleanId|$cmd" else "$name|$cmd"
                                        if (seenInCat.add(key)) {
                                            catNewCount++
                                        }
                                        if (seenSeries.add(key)) {
                                            val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: catId
                                            series.add(IptvItem(
                                                name = name,
                                                group = seriesCatMap[cId] ?: catName,
                                                logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                                                stalkerCmd = m["cmd"]?.toString(),
                                                seriesId = cleanId,
                                                year = m["year"]?.toString()?.toIntOrNull(),
                                                rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                                            ))
                                        }
                                    }

                                    if (catNewCount == 0) {
                                        sCatHasMore = false
                                    } else {
                                        sCatPage++
                                        if (series.size % 200 == 0 || series.size < 500) {
                                            onProgress("Descargando series (${series.size} series)...")
                                            onPartialUpdate?.invoke("series", ArrayList(series))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // ─── REGLA 1: ELIMINAR DESCARGAS REDUNDANTES ───
        // Si la descarga por categorías ha producido resultados válidos, NO volver a descargar globalmente.
        // El recorrido global solo debe utilizarse como fallback cuando no haya items en categorías.
        if (series.isEmpty()) {
            fallbackUsed = "global_pagination_fallback"

            val globalStrategies = listOf(
                "type=series&action=get_ordered_list",
                "type=vod&action=get_ordered_list&movie_type=series",
                "type=series&action=get_ordered_list&category=0",
                "type=series&action=get_ordered_list&genre=0",
                "type=series&action=get_ordered_list&category=*",
                "type=series&action=get_ordered_list&genre=*"
            )
            var chosenGlobalPrefix = globalStrategies.first()
            for (strategy in globalStrategies) {
                currentCoroutineContext().ensureActive()
                try {
                    val testParsed = catalogSession.requestParsed("$strategy&p=1$pageSizeParam", maxRetries = 1)
                    val testList = extractListFromJsField(testParsed)
                    if (testList.isNotEmpty()) {
                        chosenGlobalPrefix = strategy
                        break
                    }
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            var sPage = 1
            var sHasMore = true
            while (sHasMore && sPage <= 1500) {
                currentCoroutineContext().ensureActive()
                pagesRequested.incrementAndGet()
                var rawSeries = emptyList<Any>()
                var pageOk = false
                try {
                    val seriesParsed = catalogSession.requestParsed("$chosenGlobalPrefix&p=$sPage$pageSizeParam")
                    rawSeries = extractListFromJsField(seriesParsed)
                    pageOk = true
                    pagesCompleted.incrementAndGet()
                } catch (blocked: NetworkError.PortalBlockedMac) {
                    throw blocked
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    pagesFailed.incrementAndGet()
                }

                if (!pageOk || rawSeries.isEmpty()) {
                    sHasMore = false
                } else {
                    var newCount = 0
                    rawSeries.forEach { se ->
                        val m = se as? Map<*, *> ?: return@forEach
                        val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                        val cmd = m["cmd"]?.toString() ?: ""
                        var cleanId = m["id"]?.toString()
                            ?: m["series_id"]?.toString()
                            ?: m["movie_id"]?.toString()
                            ?: m["vod_id"]?.toString()
                            ?: m["id_series"]?.toString()
                            ?: m["video_id"]?.toString()
                            ?: ""
                        if (cleanId.isEmpty()) {
                            val cmdStr = m["cmd"]?.toString() ?: ""
                            val matchNum = Regex("(\\d+)").find(cmdStr)
                            if (matchNum != null) {
                                cleanId = matchNum.value
                            }
                        }
                        val key = if (cleanId.isNotEmpty()) "$cleanId|$cmd" else "$name|$cmd"
                        if (seenSeries.add(key)) {
                            newCount++
                            val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: ""
                            series.add(IptvItem(
                                name = name,
                                group = seriesCatMap[cId] ?: "General",
                                logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                                stalkerCmd = m["cmd"]?.toString(),
                                seriesId = cleanId,
                                year = m["year"]?.toString()?.toIntOrNull(),
                                rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                            ))
                        }
                    }
                    if (newCount == 0) {
                        sHasMore = false
                    } else {
                        sPage++
                        onProgress("Descargando series (${series.size} series)...")
                        onPartialUpdate?.invoke("series", ArrayList(series))
                    }
                }
            }
        }

        onPartialUpdate?.invoke("series", ArrayList(series))
        return series
    }

    suspend fun fetchStalkerCatalog(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        onSessionReady: ((Map<String, Any>) -> Unit)? = null,
        playlistId: Long? = null
    ): Map<String, Any> {

        currentCoroutineContext().ensureActive()
        onProgress("Handshake con el portal Stalker...")
        val sessionMap = performStalkerHandshake(host, mac, device, playlistId)
        currentCoroutineContext().ensureActive()
        onSessionReady?.invoke(sessionMap)

        val channels = fetchStalkerTv(
            host = host,
            mac = mac,
            device = device,
            onProgress = onProgress,
            onPartialUpdate = onPartialUpdate,
            existingSession = sessionMap
        )

        val movies = fetchStalkerVod(
            host = host,
            mac = mac,
            device = device,
            onProgress = onProgress,
            onPartialUpdate = onPartialUpdate,
            existingSession = sessionMap
        )

        val series = fetchStalkerSeries(
            host = host,
            mac = mac,
            device = device,
            onProgress = onProgress,
            onPartialUpdate = onPartialUpdate,
            existingSession = sessionMap
        )


        return mapOf(
            "tv" to channels,
            "movie" to movies,
            "series" to series,
            "stalkerSession" to sessionMap
        )
    }

    private fun cleanNumberString(raw: Any?): String {
        if (raw == null) return ""
        if (raw is Number) {
            val d = raw.toDouble()
            if (d == raw.toLong().toDouble()) {
                return raw.toLong().toString()
            }
            return raw.toString()
        }
        val str = raw.toString().trim()
        val d = str.toDoubleOrNull()
        if (d != null && d == d.toLong().toDouble()) {
            return d.toLong().toString()
        }
        return str
    }

    private fun extractEpisodesList(parsed: Map<String, Any?>, seriesId: String): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        val jsObj = parsed["js"] ?: parsed

        fun parseEpisodeEntry(item: Any?, seasonHint: String? = null): List<Map<String, Any?>> {
            val list = mutableListOf<Map<String, Any?>>()
            when (item) {
                is Map<*, *> -> {
                    val m = item.entries.associate { it.key.toString() to it.value }.toMutableMap()
                    val sName = m["name"]?.toString() ?: m["title"]?.toString() ?: ""
                    val sFound = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(sName)?.groupValues?.get(1)
                        ?: Regex("(?i)\\b[ST](\\d+)").find(sName)?.groupValues?.get(1)
                    val sNum = cleanNumberString(sFound ?: m["season_num"] ?: m["season"] ?: seasonHint ?: "1")
                    m["season_num"] = if (sNum.isEmpty() || sNum == "0") "1" else sNum
                    list.add(m)
                }
                is Number, is String -> {
                    val str = cleanNumberString(item)
                    if (str.isNotEmpty()) {
                        val sNum = cleanNumberString(seasonHint ?: "1")
                        val finalSNum = if (sNum.isEmpty() || sNum == "0") "1" else sNum
                        list.add(mapOf(
                            "id" to "",
                            "name" to "Episodio $str",
                            "season_num" to finalSNum,
                            "episode_num" to str,
                            "series_number" to str,
                            "series_id" to seriesId,
                            "cmd" to ""
                        ))
                    }
                }
                is List<*> -> {
                    item.forEachIndexed { idx, sub ->
                        val subSeason = (idx + 1).toString()
                        list.addAll(parseEpisodeEntry(sub, seasonHint ?: subSeason))
                    }
                }
            }
            return list
        }

        if (jsObj is List<*>) {
            var allSubLists = true
            jsObj.forEachIndexed { idx, item ->
                if (item is List<*>) {
                    val sNum = (idx + 1).toString()
                    item.forEach { subItem ->
                        result.addAll(parseEpisodeEntry(subItem, sNum))
                    }
                } else if (item is Map<*, *>) {
                    allSubLists = false
                    val m = item.entries.associate { it.key.toString() to it.value }
                    val sName = m["name"]?.toString() ?: m["title"]?.toString() ?: ""
                    val sNum = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(sName)?.groupValues?.get(1)
                        ?: cleanNumberString(m["season_num"] ?: m["season_id"] ?: m["season"] ?: (idx + 1).toString())
                    val sList = m["series"] ?: m["files"] ?: m["episodes"] ?: m["data"]
                    if (sList is List<*> && sList.isNotEmpty()) {
                        sList.forEach { subItem ->
                            result.addAll(parseEpisodeEntry(subItem, sNum))
                        }
                    } else {
                        result.addAll(parseEpisodeEntry(item, sNum))
                    }
                } else if (item is Number || item is String) {
                    allSubLists = false
                    result.addAll(parseEpisodeEntry(item, "1"))
                }
            }
            if (result.isNotEmpty()) return result
        }

        if (jsObj is Map<*, *>) {
            val dataObj = jsObj["data"] ?: jsObj["files"] ?: jsObj["episodes"] ?: jsObj["items"] ?: jsObj["list"]
            if (dataObj is List<*> && dataObj.isNotEmpty()) {
                val allScalar = dataObj.all { it is Number || it is String }
                if (allScalar) {
                    dataObj.forEach { item ->
                        result.addAll(parseEpisodeEntry(item, "1"))
                    }
                } else {
                    dataObj.forEachIndexed { idx, item ->
                        if (item is List<*>) {
                            val sNum = (idx + 1).toString()
                            item.forEach { subItem ->
                                result.addAll(parseEpisodeEntry(subItem, sNum))
                            }
                        } else if (item is Map<*, *>) {
                            val m = item.entries.associate { it.key.toString() to it.value }
                            val sName = m["name"]?.toString() ?: m["title"]?.toString() ?: ""
                            val sNum = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(sName)?.groupValues?.get(1)
                                ?: cleanNumberString(m["season_num"] ?: m["season_id"] ?: m["season"] ?: (idx + 1).toString())
                            val sList = m["series"] ?: m["files"] ?: m["episodes"] ?: m["data"]
                            if (sList is List<*> && sList.isNotEmpty()) {
                                sList.forEach { subItem ->
                                    result.addAll(parseEpisodeEntry(subItem, sNum))
                                }
                            } else {
                                result.addAll(parseEpisodeEntry(item, sNum))
                            }
                        }
                    }
                }
                if (result.isNotEmpty()) return result
            }

            val seriesVal = jsObj["series"] ?: jsObj["seasons"] ?: jsObj["season"]
            if (seriesVal is List<*>) {
                val allScalar = seriesVal.all { it is Number || it is String }
                if (allScalar) {
                    // Check if these numbers represent season numbers or episode numbers
                    seriesVal.forEach { item ->
                        result.addAll(parseEpisodeEntry(item, "1"))
                    }
                } else {
                    seriesVal.forEachIndexed { sIdx, item ->
                        if (item is List<*>) {
                            val sNum = (sIdx + 1).toString()
                            item.forEach { subItem ->
                                result.addAll(parseEpisodeEntry(subItem, sNum))
                            }
                        } else if (item is Map<*, *>) {
                            val sMap = item.entries.associate { it.key.toString() to it.value }
                            val sName = sMap["name"]?.toString() ?: sMap["title"]?.toString() ?: ""
                            val sNum = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(sName)?.groupValues?.get(1)
                                ?: Regex("(\\d+)").find(sName)?.value
                                ?: cleanNumberString(sMap["season_num"] ?: sMap["season_id"] ?: (sIdx + 1).toString())
                            val subSeries = sMap["series"] ?: sMap["files"] ?: sMap["episodes"] ?: sMap["data"]
                            if (subSeries is List<*> && subSeries.isNotEmpty()) {
                                subSeries.forEach { subEp ->
                                    result.addAll(parseEpisodeEntry(subEp, sNum))
                                }
                            } else {
                                result.addAll(parseEpisodeEntry(sMap, sNum))
                            }
                        }
                    }
                }
                if (result.isNotEmpty()) return result
            }
            if (seriesVal is Map<*, *>) {
                seriesVal.forEach { (sKey, sVal) ->
                    val sNumMatch = cleanNumberString(Regex("(\\d+)").find(sKey.toString())?.value ?: sKey.toString())
                    if (sVal is List<*>) {
                        sVal.forEach { item ->
                            result.addAll(parseEpisodeEntry(item, sNumMatch))
                        }
                    } else if (sVal is Map<*, *>) {
                        sVal.forEach { (epKey, epVal) ->
                            val epNumMatch = cleanNumberString(Regex("(\\d+)").find(epKey.toString())?.value ?: epKey.toString())
                            if (epVal is Map<*, *>) {
                                val m = epVal.entries.associate { it.key.toString() to it.value }.toMutableMap()
                                if (m["season_num"] == null) m["season_num"] = sNumMatch
                                if (m["episode_num"] == null) m["episode_num"] = epNumMatch
                                result.add(m)
                            } else {
                                result.add(mapOf(
                                    "id" to "$seriesId:$sNumMatch:$epNumMatch",
                                    "name" to "Episodio $epNumMatch",
                                    "season_num" to sNumMatch,
                                    "episode_num" to epNumMatch,
                                    "series_number" to epNumMatch,
                                    "series_id" to seriesId,
                                    "cmd" to ""
                                ))
                            }
                        }
                    } else if (sVal is Number) {
                        val count = sVal.toInt()
                        for (ep in 1..count) {
                            result.add(mapOf(
                                "id" to "$seriesId:$sNumMatch:$ep",
                                "name" to "Episodio $ep",
                                "season_num" to sNumMatch,
                                "episode_num" to ep.toString(),
                                "series_number" to ep.toString(),
                                "series_id" to seriesId,
                                "cmd" to ""
                            ))
                        }
                    }
                }
                if (result.isNotEmpty()) return result
            }
        }

        return result
    }

    suspend fun fetchStalkerSeriesEpisodes(
        portalUrl: String,
        mac: String,
        seriesId: String,
        token: String,
        device: StalkerDevice,
        seriesCmd: String? = null
    ): List<SeriesSeason> {
        val macClean = mac.trim().uppercase()
        val cleanSeriesId = seriesId.trim()
        val targetSeriesCmd = seriesCmd?.trim() ?: ""

        logStalkerSeries(
            stage = "FETCH_SERIES_EPISODES_START",
            seriesId = cleanSeriesId,
            seriesCmd = targetSeriesCmd
        )
        
        val idCandidates = LinkedHashSet<String>()
        if (cleanSeriesId.isNotEmpty()) {
            idCandidates.add(cleanSeriesId)
            if (cleanSeriesId.contains(":")) {
                idCandidates.add(cleanSeriesId.substringBefore(":").trim())
            }
            val numMatches = Regex("(\\d+)").findAll(cleanSeriesId).map { it.value }.toList()
            numMatches.forEach { num ->
                if (num.isNotEmpty()) idCandidates.add(num)
            }
            if (cleanSeriesId.contains("/media/")) {
                val mediaClean = cleanSeriesId.substringAfter("/media/").substringBefore(".").trim()
                if (mediaClean.isNotEmpty()) idCandidates.add(mediaClean)
            }
        }
        if (targetSeriesCmd.isNotEmpty()) {
            val cmdNumMatches = Regex("(\\d+)").findAll(targetSeriesCmd).map { it.value }.toList()
            cmdNumMatches.forEach { num ->
                if (num.isNotEmpty()) idCandidates.add(num)
            }
            if (targetSeriesCmd.contains("/media/")) {
                val mediaClean = targetSeriesCmd.substringAfter("/media/").substringBefore(".").trim()
                if (mediaClean.isNotEmpty()) idCandidates.add(mediaClean)
            }
        }

        var rawEpisodes = mutableListOf<Map<String, Any?>>()
        val seasonObjects = mutableListOf<Map<String, Any?>>()
        val seasonIdsToQuery = LinkedHashSet<Pair<String, String>>() // seasonId to seasonNum

        for (candidate in idCandidates) {
            val attempts = listOf(
                "type=series&action=get_seasons&movie_id=${URLParamEncode(candidate)}",
                "type=series&action=get_seasons&series_id=${URLParamEncode(candidate)}",
                "type=series&action=get_seasons&id=${URLParamEncode(candidate)}",
                "type=vod&action=get_seasons&movie_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_seasons&id=${URLParamEncode(candidate)}",
                "type=series&action=get_ordered_list&movie_id=${URLParamEncode(candidate)}",
                "type=series&action=get_ordered_list&series_id=${URLParamEncode(candidate)}",
                "type=series&action=get_ordered_list&id=${URLParamEncode(candidate)}",
                "type=vod&action=get_ordered_list&movie_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_ordered_list&series_id=${URLParamEncode(candidate)}",
                "type=series&action=get_ordered_list&category=${URLParamEncode(candidate)}",
                "type=vod&action=get_ordered_list&category=${URLParamEncode(candidate)}",
                "type=series&action=get_ordered_list&movie_type=series&movie_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_ordered_list&movie_type=series&movie_id=${URLParamEncode(candidate)}",
                "type=series&action=get_series_files&id=${URLParamEncode(candidate)}",
                "type=series&action=get_series_files&movie_id=${URLParamEncode(candidate)}",
                "type=series&action=get_series_files&series_id=${URLParamEncode(candidate)}",
                "type=series&action=get_series_file&id=${URLParamEncode(candidate)}",
                "type=series&action=get_series_file&series_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_movie_files&movie_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_movie_files&id=${URLParamEncode(candidate)}",
                "type=series&action=get_episodes&id=${URLParamEncode(candidate)}",
                "type=series&action=get_episodes&series_id=${URLParamEncode(candidate)}",
                "type=series&action=get_episodes&movie_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_episodes&id=${URLParamEncode(candidate)}",
                "type=vod&action=get_episodes&series_id=${URLParamEncode(candidate)}",
                "type=stb&action=get_episodes&id=${URLParamEncode(candidate)}",
                "type=stb&action=get_series_files&id=${URLParamEncode(candidate)}",
                "type=stb&action=get_ordered_list&movie_id=${URLParamEncode(candidate)}",
                "type=vod&action=get_single_item&id=${URLParamEncode(candidate)}",
                "type=series&action=get_single_item&id=${URLParamEncode(candidate)}"
            )

            for (params in attempts) {
                try {
                    val httpResp = stalkerRequestWithResponse(portalUrl, macClean, params, token, device)
                    val resp = httpResp.body
                    val trimmed = resp.trim()
                    if (trimmed.isEmpty()) continue

                    val parsed = parseStalkerJsResponse(resp)
                    val jsObj = parsed["js"] ?: parsed

                    val dataList = when (jsObj) {
                        is List<*> -> jsObj
                        is Map<*, *> -> (jsObj["data"] ?: jsObj["items"] ?: jsObj["list"] ?: jsObj["seasons"]) as? List<*>
                        else -> null
                    }

                    if (dataList != null && dataList.isNotEmpty()) {
                        val detectedSeasons = mutableListOf<Map<String, Any?>>()
                        val detectedEpisodes = mutableListOf<Map<String, Any?>>()

                        dataList.forEachIndexed { dIdx, item ->
                            if (item is Map<*, *>) {
                                val m = item.entries.associate { it.key.toString() to it.value }
                                val name = m["name"]?.toString() ?: m["title"]?.toString() ?: ""
                                val isSeasonFlag = m["is_season"] == 1 || m["is_season"] == "1"
                                val hasSeasonWord = name.contains("temporada", ignoreCase = true) ||
                                        name.contains("season", ignoreCase = true) ||
                                        name.contains("saison", ignoreCase = true) ||
                                        name.contains("staffel", ignoreCase = true) ||
                                        Regex("(?i)^t\\s*\\d+$").matches(name.trim())
                                val hasFile = m["file"] != null || m["file_id"] != null || m["cmd"]?.toString()?.contains("/media/") == true
                                val hasEpNum = m["episode_num"] != null || m["episode"] != null

                                val sNumFound = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(name)?.groupValues?.get(1)
                                    ?: Regex("(\\d+)").find(name)?.value
                                    ?: cleanNumberString(m["season_num"] ?: m["season_id"] ?: m["season"] ?: (dIdx + 1).toString())
                                val itemSNum = if (sNumFound.isEmpty() || sNumFound == "0") (dIdx + 1).toString() else sNumFound

                                val sVal = m["series"] ?: m["seasons"]
                                if (sVal is Map<*, *> || (sVal is List<*> && sVal.isNotEmpty())) {
                                    // Este elemento es el objeto de la serie con sus temporadas/episodios
                                    if (sVal is Map<*, *>) {
                                        val eps = extractEpisodesList(mapOf("js" to m), candidate)
                                        if (eps.isNotEmpty()) rawEpisodes.addAll(eps)
                                    } else if (sVal is List<*>) {
                                        val allScalar = sVal.all { it is Number || it is String }
                                        if (allScalar) {
                                            sVal.forEach { epElem ->
                                                val epStr = cleanNumberString(epElem)
                                                val realCmd = m["cmd"]?.toString() ?: ""
                                                rawEpisodes.add(mapOf(
                                                    "id" to "",
                                                    "name" to "Episodio $epStr",
                                                    "season_num" to itemSNum,
                                                    "episode_num" to epStr,
                                                    "series_number" to epStr,
                                                    "series_id" to candidate,
                                                    "cmd" to realCmd.ifBlank { targetSeriesCmd }
                                                ))
                                            }
                                        } else {
                                            sVal.forEachIndexed { sIdx, sElem ->
                                                if (sElem is List<*> && sElem.isNotEmpty()) {
                                                    val sNum = (sIdx + 1).toString()
                                                    sElem.forEach { epElem ->
                                                        val epStr = cleanNumberString(epElem)
                                                        val realCmd = m["cmd"]?.toString() ?: ""
                                                        rawEpisodes.add(mapOf(
                                                            "id" to "",
                                                            "name" to "Episodio $epStr",
                                                            "season_num" to sNum,
                                                            "episode_num" to epStr,
                                                            "series_number" to epStr,
                                                            "series_id" to candidate,
                                                            "cmd" to realCmd.ifBlank { targetSeriesCmd }
                                                        ))
                                                    }
                                                } else if (sElem is Map<*, *>) {
                                                    val sMap = sElem.entries.associate { it.key.toString() to it.value }
                                                    val sName = sMap["name"]?.toString() ?: sMap["title"]?.toString() ?: ""
                                                    val sNum = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(sName)?.groupValues?.get(1)
                                                        ?: Regex("(\\d+)").find(sName)?.value
                                                        ?: cleanNumberString(sMap["season_num"] ?: sMap["season_id"] ?: (sIdx + 1).toString())
                                                    val subSeries = sMap["series"] ?: sMap["files"] ?: sMap["episodes"] ?: sMap["data"]
                                                    if (subSeries is List<*> && subSeries.isNotEmpty()) {
                                                        subSeries.forEach { subEp ->
                                                            val epStr = cleanNumberString(subEp)
                                                            val realCmd = sMap["cmd"]?.toString() ?: m["cmd"]?.toString() ?: ""
                                                            rawEpisodes.add(mapOf(
                                                                "id" to "",
                                                                "name" to "Episodio $epStr",
                                                                "season_num" to sNum,
                                                                "episode_num" to epStr,
                                                                "series_number" to epStr,
                                                                "series_id" to candidate,
                                                                "cmd" to realCmd.ifBlank { targetSeriesCmd }
                                                            ))
                                                        }
                                                    } else {
                                                        val sId = cleanNumberString(sMap["id"] ?: sMap["season_id"] ?: sNum)
                                                        seasonIdsToQuery.add(sId to sNum)
                                                    }
                                                } else {
                                                    val sNum = (sIdx + 1).toString()
                                                    val sId = cleanNumberString(sElem ?: sNum)
                                                    seasonIdsToQuery.add(sId to sNum)
                                                }
                                            }
                                        }
                                    }
                                } else if (isSeasonFlag || (hasSeasonWord && !hasFile && !hasEpNum)) {
                                    detectedSeasons.add(m)
                                } else {
                                    detectedEpisodes.add(m)
                                }
                            }
                        }

                        if (rawEpisodes.isNotEmpty()) {
                            Log.d(TAG, "Extracted ${rawEpisodes.size} episodes directly from series list item for $candidate")
                            break
                        } else if (detectedSeasons.isNotEmpty() && detectedEpisodes.isEmpty()) {
                            seasonObjects.addAll(detectedSeasons)
                            Log.d(TAG, "Detected ${detectedSeasons.size} season objects for $candidate with params: $params")
                            break
                        } else if (detectedEpisodes.isNotEmpty()) {
                            rawEpisodes.addAll(detectedEpisodes)
                            Log.d(TAG, "Detected ${detectedEpisodes.size} direct episode objects for $candidate with params: $params")
                            break
                        }
                    }

                    // Comprobar si es un objeto con campo series
                    if (jsObj is Map<*, *>) {
                        val seriesVal = jsObj["series"] ?: jsObj["seasons"]
                        if (seriesVal is Map<*, *>) {
                            // Mapa de temporadas a episodios: {"1": [1,2,3..], "2": [1,2..]}
                            val eps = extractEpisodesList(parsed, candidate)
                            if (eps.isNotEmpty()) {
                                rawEpisodes.addAll(eps)
                                Log.d(TAG, "Extracted ${eps.size} episodes from series map for $candidate")
                                break
                            }
                        } else if (seriesVal is List<*>) {
                            // Lista de temporadas: [1, 2, 3] o [ [1,2..], [1,2..] ] o [ {"id": 1}, {"id": 2} ]
                            var allSubLists = true
                            seriesVal.forEachIndexed { sIdx, sElem ->
                                if (sElem is List<*> && sElem.isNotEmpty()) {
                                    val sNum = (sIdx + 1).toString()
                                    sElem.forEach { epElem ->
                                        val epStr = cleanNumberString(epElem)
                                        rawEpisodes.add(mapOf(
                                            "id" to "",
                                            "name" to "Episodio $epStr",
                                            "season_num" to sNum,
                                            "episode_num" to epStr,
                                            "series_number" to epStr,
                                            "series_id" to candidate,
                                            "cmd" to targetSeriesCmd
                                        ))
                                    }
                                } else {
                                    allSubLists = false
                                    val sNum = (sIdx + 1).toString()
                                    val sId = when (sElem) {
                                        is Map<*, *> -> cleanNumberString(sElem["id"] ?: sElem["season_id"] ?: sNum)
                                        else -> cleanNumberString(sElem ?: sNum)
                                    }
                                    seasonIdsToQuery.add(sId to sNum)
                                }
                            }
                            if (allSubLists && rawEpisodes.isNotEmpty()) {
                                Log.d(TAG, "Extracted ${rawEpisodes.size} episodes from nested series lists for $candidate")
                                break
                            }
                            if (seasonIdsToQuery.isNotEmpty()) {
                                Log.d(TAG, "Detected ${seasonIdsToQuery.size} season IDs from series list for $candidate")
                                break
                            }
                        }
                    }

                    val fallbackList = extractEpisodesList(parsed, candidate)
                    if (fallbackList.isNotEmpty()) {
                        rawEpisodes.addAll(fallbackList)
                        Log.d(TAG, "Fetched ${rawEpisodes.size} episodes from fallback parse for $candidate with params: $params")
                        break
                    }
                } catch (e: Exception) {
                    // try next attempt
                }
            }

            if (rawEpisodes.isNotEmpty() || seasonObjects.isNotEmpty() || seasonIdsToQuery.isNotEmpty()) {
                break
            }
        }

        // Si tenemos seasonObjects, extraer las tuplas (seasonId, seasonNum)
        if (seasonObjects.isNotEmpty()) {
            seasonObjects.forEachIndexed { idx, sObj ->
                val sId = cleanNumberString(sObj["id"] ?: sObj["season_id"] ?: (idx + 1).toString())
                val sName = sObj["name"]?.toString() ?: sObj["title"]?.toString() ?: ""
                val sNum = Regex("(?i)(?:temporada|season|saison|staffel|t|s)\\s*(\\d+)").find(sName)?.groupValues?.get(1)
                    ?: Regex("(\\d+)").find(sName)?.value
                    ?: cleanNumberString(sObj["season_num"] ?: sObj["season"] ?: (idx + 1).toString())
                
                // Comprobar si el objeto de temporada ya tiene sus episodios embebidos
                val embeddedSeries = sObj["series"] ?: sObj["files"] ?: sObj["episodes"] ?: sObj["data"]
                if (embeddedSeries is List<*> && embeddedSeries.isNotEmpty()) {
                    embeddedSeries.forEachIndexed { epIdx, epElem ->
                        if (epElem is Map<*, *>) {
                            val m = epElem.entries.associate { it.key.toString() to it.value }.toMutableMap()
                            if (m["season_num"] == null) m["season_num"] = sNum
                            rawEpisodes.add(m)
                        } else {
                            val epStr = cleanNumberString(epElem)
                            rawEpisodes.add(mapOf(
                                "id" to "",
                                "name" to "Episodio $epStr",
                                "season_num" to sNum,
                                "episode_num" to epStr,
                                "series_number" to epStr,
                                "series_id" to cleanSeriesId,
                                "cmd" to targetSeriesCmd
                            ))
                        }
                    }
                } else {
                    seasonIdsToQuery.add(sId to sNum)
                }
            }
        }

        // Si tenemos temporadas identificadas para consultar sus episodios individuales
        if (seasonIdsToQuery.isNotEmpty()) {
            for ((sId, sNum) in seasonIdsToQuery) {
                var seasonEpisodesFound = false
                val seasonSpecificAttempts = listOf(
                    "type=series&action=get_ordered_list&movie_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_ordered_list&series_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_ordered_list&movie_id=${URLParamEncode(cleanSeriesId)}&season=${URLParamEncode(sNum)}",
                    "type=series&action=get_ordered_list&series_id=${URLParamEncode(cleanSeriesId)}&season=${URLParamEncode(sNum)}",
                    "type=series&action=get_ordered_list&id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_ordered_list&id=${URLParamEncode(cleanSeriesId)}&season=${URLParamEncode(sNum)}",
                    "type=series&action=get_ordered_list&season_id=${URLParamEncode(sId)}",
                    "type=vod&action=get_ordered_list&movie_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=vod&action=get_ordered_list&series_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=vod&action=get_ordered_list&movie_id=${URLParamEncode(cleanSeriesId)}&season=${URLParamEncode(sNum)}",
                    "type=vod&action=get_ordered_list&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_ordered_list&category=${URLParamEncode(sId)}",
                    "type=vod&action=get_ordered_list&category=${URLParamEncode(sId)}",
                    "type=series&action=get_series_files&id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_series_files&movie_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_series_files&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_episodes&movie_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_episodes&series_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_episodes&season_id=${URLParamEncode(sId)}",
                    "type=vod&action=get_movie_files&id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=vod&action=get_movie_files&season_id=${URLParamEncode(sId)}",
                    "type=vod&action=get_episodes&movie_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=stb&action=get_episodes&movie_id=${URLParamEncode(cleanSeriesId)}&season_id=${URLParamEncode(sId)}",
                    "type=stb&action=get_episodes&season_id=${URLParamEncode(sId)}",
                    "type=series&action=get_single_item&id=${URLParamEncode(sId)}",
                    "type=vod&action=get_single_item&id=${URLParamEncode(sId)}"
                )

                for (sParams in seasonSpecificAttempts) {
                    try {
                        val httpResp = stalkerRequestWithResponse(portalUrl, macClean, sParams, token, device)
                        val resp = httpResp.body
                        if (resp.isBlank()) continue
                        val parsed = parseStalkerJsResponse(resp)
                        val jsObj = parsed["js"] ?: parsed

                        val epsList = when (jsObj) {
                            is List<*> -> jsObj
                            is Map<*, *> -> (jsObj["data"] ?: jsObj["files"] ?: jsObj["episodes"] ?: jsObj["items"] ?: jsObj["list"]) as? List<*>
                            else -> null
                        }

                        if (epsList != null && epsList.isNotEmpty()) {
                            epsList.forEachIndexed { epIdx, epElem ->
                                if (epElem is Map<*, *>) {
                                    val m = epElem.entries.associate { it.key.toString() to it.value }.toMutableMap()
                                    if (m["season_num"] == null && m["season"] == null) {
                                        m["season_num"] = sNum
                                    }
                                    rawEpisodes.add(m)
                                } else {
                                    val epStr = cleanNumberString(epElem)
                                    rawEpisodes.add(mapOf(
                                        "id" to "",
                                        "name" to "Episodio $epStr",
                                        "season_num" to sNum,
                                        "episode_num" to epStr,
                                        "series_number" to epStr,
                                        "series_id" to cleanSeriesId,
                                        "cmd" to targetSeriesCmd
                                    ))
                                }
                            }
                            seasonEpisodesFound = true
                            Log.d(TAG, "Found ${epsList.size} episodes for season $sNum with params: $sParams")
                            break
                        }

                        // Comprobar si jsObj tiene campo series con lista de episodios
                        if (jsObj is Map<*, *>) {
                            val sVal = jsObj["series"] ?: jsObj["episodes"]
                            if (sVal is List<*> && sVal.isNotEmpty()) {
                                sVal.forEach { epElem ->
                                    if (epElem is Map<*, *>) {
                                        val m = epElem.entries.associate { it.key.toString() to it.value }.toMutableMap()
                                        if (m["season_num"] == null) m["season_num"] = sNum
                                        rawEpisodes.add(m)
                                    } else {
                                        val epStr = cleanNumberString(epElem)
                                        rawEpisodes.add(mapOf(
                                            "id" to "",
                                            "name" to "Episodio $epStr",
                                            "season_num" to sNum,
                                            "episode_num" to epStr,
                                            "series_number" to epStr,
                                            "series_id" to cleanSeriesId,
                                            "cmd" to targetSeriesCmd
                                        ))
                                    }
                                }
                                seasonEpisodesFound = true
                                Log.d(TAG, "Found ${sVal.size} episodes from series list for season $sNum")
                                break
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Si no se encontró respuesta específica para la temporada, no fabricar episodio falso
                if (!seasonEpisodesFound) {
                    Log.d(TAG, "No specific episodes returned from portal for season $sNum - omitting artificial fallback")
                }
            }
        }

        val episodesBySeason = HashMap<String, ArrayList<SeriesEpisode>>()

        rawEpisodes.forEachIndexed { idx, epMap ->
            val nameOrTitle = epMap["name"]?.toString() ?: epMap["title"]?.toString() ?: ""
            var parsedSeason: String? = null
            var parsedEpisode: String? = null

            if (nameOrTitle.isNotEmpty()) {
                val cleanedNameOrTitle = nameOrTitle.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
                val s0e0Match = Regex("(?i)[ST](\\d+)\\s*[Ee.]?\\s*(\\d+)").find(cleanedNameOrTitle)
                val xMatch = Regex("(?i)\\b(\\d+)x(\\d+)\\b").find(cleanedNameOrTitle)
                if (s0e0Match != null) {
                    parsedSeason = s0e0Match.groupValues[1].toIntOrNull()?.toString()
                    parsedEpisode = s0e0Match.groupValues[2].toIntOrNull()?.toString()
                } else if (xMatch != null) {
                    parsedSeason = xMatch.groupValues[1].toIntOrNull()?.toString()
                    parsedEpisode = xMatch.groupValues[2].toIntOrNull()?.toString()
                } else {
                    val epMatch = Regex("(?i)(?:capitulo|capítulo|episodio|episode|ep|cap|e|c)\\s*(\\d+)").find(cleanedNameOrTitle)
                    if (epMatch != null) {
                        parsedEpisode = epMatch.groupValues[1].toIntOrNull()?.toString()
                    }
                    val sMatch = Regex("(?i)(?:temporada|season|saison|staffel|temp|s)\\s*(\\d+)").find(cleanedNameOrTitle)
                    if (sMatch != null) {
                        parsedSeason = sMatch.groupValues[1].toIntOrNull()?.toString()
                    }
                }
            }

            var seasonNum = cleanNumberString(epMap["season_num"] ?: epMap["season"] ?: epMap["season_number"] ?: epMap["season_no"] ?: epMap["s_num"])
            if (parsedSeason != null && (seasonNum.isEmpty() || seasonNum == "1" || seasonNum == "0")) {
                seasonNum = parsedSeason
            }
            if (seasonNum.isEmpty() || seasonNum == "0" || seasonNum == "null") {
                seasonNum = parsedSeason ?: "1"
            }

            val numStr = cleanNumberString(epMap["num"] ?: epMap["number"])
            var epNum = cleanNumberString(epMap["episode_num"] ?: epMap["episode"] ?: epMap["episode_number"] ?: epMap["episode_no"] ?: epMap["ep_num"] ?: parsedEpisode)
            if (epNum.isEmpty() && numStr.isNotEmpty() && (numStr.toIntOrNull() ?: 9999) < 500) {
                epNum = numStr
            }

            if (epNum.isEmpty() || epNum == "0" || epNum == "null") {
                epNum = (idx + 1).toString()
            }

            val rawTitle = epMap["name"]?.toString() ?: epMap["title"]?.toString() ?: ""
            val cleanTitleStr = rawTitle
                .replace(Regex("(?i)\\b[ST]\\d+\\s*[Ee.]?\\s*\\d+\\b"), "")
                .replace(Regex("(?i)\\b\\d+x\\d+\\b"), "")
                .replace(Regex("(?i)(?:temporada|season|saison|staffel|temp)\\s*\\d+"), "")
                .replace(Regex("(?i)(?:capitulo|capítulo|episodio|episode|ep|cap)\\s*\\d+"), "")
                .trim().removePrefix(":").removePrefix("-").trim()
            val isOnlyEpNum = cleanTitleStr == epNum || cleanTitleStr == String.format(java.util.Locale.ROOT, "%02d", epNum.toIntOrNull() ?: -1)
            val title = if (cleanTitleStr.isNotEmpty() && !isOnlyEpNum) "S$seasonNum E$epNum: $cleanTitleStr" else "S$seasonNum E$epNum"

            val desc = (epMap["info"] as? Map<*, *>)?.get("plot")?.toString()
                ?: epMap["description"]?.toString()
                ?: epMap["overview"]?.toString()
                ?: ""

            val epSeriesId = cleanNumberString(epMap["series_id"] ?: epMap["movie_id"] ?: cleanSeriesId)
            val epSeasonId = cleanNumberString(epMap["season_id"] ?: epMap["season"] ?: seasonNum)
            val rawEpId = epMap["episode_id"] ?: epMap["id"]
            val epEpisodeId = if (rawEpId != null && !rawEpId.toString().contains(":")) cleanNumberString(rawEpId) else ""
            val epVideoId = cleanNumberString(epMap["video_id"])
            val epFileId = cleanNumberString(epMap["file_id"])
            val epFile = epMap["file"]?.toString() ?: ""
            val rawCmd = epMap["cmd"]?.toString() ?: ""
            val cleanCmd = if (rawCmd.contains(":") && !rawCmd.startsWith("http", ignoreCase = true) && !rawCmd.startsWith("ffmpeg", ignoreCase = true) && !rawCmd.startsWith("ffrt", ignoreCase = true)) "" else rawCmd
            val epUrl = epMap["url"]?.toString() ?: epMap["uri"]?.toString() ?: epMap["link"]?.toString() ?: epMap["path"]?.toString() ?: ""
            val epPath = epMap["path"]?.toString() ?: ""
            val epSeriesNum = cleanNumberString(epMap["series_number"] ?: epMap["series_num"] ?: epNum)

            val epInfo = StalkerEpisodeInfo(
                seriesId = epSeriesId,
                seriesCmd = targetSeriesCmd,
                seasonId = epSeasonId,
                seasonNum = seasonNum,
                episodeId = epEpisodeId,
                episodeNum = epNum,
                seriesNumber = epSeriesNum,
                videoId = epVideoId,
                fileId = epFileId,
                file = epFile,
                cmd = cleanCmd,
                url = epUrl,
                path = epPath,
                name = rawTitle,
                isRealEpisode = true
            )
            val finalDescriptor = encodeStalkerEpisodeDescriptor(epInfo)

            logStalkerSeries(
                stage = "EPISODE_METADATA_EXTRACTED",
                seriesId = epSeriesId.ifBlank { null },
                seriesCmd = targetSeriesCmd.ifBlank { null },
                seasonId = epSeasonId.ifBlank { null },
                seasonNum = seasonNum.ifBlank { null },
                episodeId = epEpisodeId.ifBlank { null },
                episodeNum = epNum.ifBlank { null },
                videoId = epVideoId.ifBlank { null },
                fileId = epFileId.ifBlank { null },
                cmd = cleanCmd.ifBlank { null },
                file = epFile.ifBlank { null },
                url = epUrl.ifBlank { null }
            )

            val list = episodesBySeason.getOrPut(seasonNum) { ArrayList() }
            list.add(SeriesEpisode(
                id = epEpisodeId.ifBlank { epNum },
                episodeNum = epNum,
                seasonNum = seasonNum,
                title = title,
                desc = desc,
                category = finalDescriptor,
                rating = epNum,
                playbackUrl = if (epUrl.startsWith("http://") || epUrl.startsWith("https://")) epUrl else null,
                stalkerCmd = cleanCmd.ifBlank { null },
                seriesId = epSeriesId.ifBlank { null },
                seasonId = epSeasonId.ifBlank { null },
                episodeId = epEpisodeId.ifBlank { null },
                videoId = epVideoId.ifBlank { null },
                fileId = epFileId.ifBlank { null },
                file = epFile.ifBlank { null },
                path = epPath.ifBlank { null }
            ))
        }

        val outSeasons = ArrayList<SeriesSeason>()
        episodesBySeason.forEach { (seasonNum, eps) ->
            // Evitar duplicados de episodios con el mismo rating/epNum y título dentro de una temporada
            val uniqueEps = ArrayList<SeriesEpisode>()
            val seenKeys = HashSet<String>()
            eps.forEach { ep ->
                val key = "${ep.rating}|${ep.category}"
                if (seenKeys.add(key)) {
                    uniqueEps.add(ep)
                }
            }
            outSeasons.add(SeriesSeason(
                id = seasonNum,
                name = "Temporada $seasonNum",
                episodes = uniqueEps.sortedWith(compareBy { it.rating.toIntOrNull() ?: 0 })
            ))
        }

        return outSeasons.sortedWith(compareBy { it.id.toIntOrNull() ?: 0 })
    }

    suspend fun fetchRemainingStalkerVOD(
        portalUrl: String,
        mac: String,
        token: String,
        device: StalkerDevice,
        startPage: Int,
        vodCatMap: Map<String, String>,
        onPageFetched: (List<IptvItem>) -> Unit
    ) {
        val seenMovies = HashSet<String>()
        val catIds = vodCatMap.keys.toList() + listOf("0", "*", "")
        for (catId in catIds) {
            var mPage = 1
            var mHasMore = true
            var emptyStreak = 0
            while (mHasMore && mPage <= 1000) {
                currentCoroutineContext().ensureActive()
                try {
                    val paramsToTry = listOf(
                        "type=vod&action=get_ordered_list&category=$catId&p=$mPage",
                        "type=vod&action=get_ordered_list&genre=$catId&p=$mPage",
                        "type=vod&action=get_ordered_list&p=$mPage"
                    )
                    var rawMovies = emptyList<Any>()
                    for (param in paramsToTry) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val vodParsed = stalkerRequestAndParse(portalUrl, mac, param, token, device)
                            val list = extractListFromJsField(vodParsed)
                            if (list.isNotEmpty()) {
                                rawMovies = list
                                break
                            }
                        } catch (e: Exception) {
                            // try next param
                        }
                    }
                    if (rawMovies.isEmpty()) {
                        emptyStreak++
                        if (emptyStreak >= 2) mHasMore = false else mPage++
                    } else {
                        emptyStreak = 0
                        val movies = ArrayList<IptvItem>()
                        var newCount = 0
                        rawMovies.forEach { mo ->
                            val m = mo as? Map<*, *> ?: return@forEach
                            val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                            val cmd = m["cmd"]?.toString() ?: ""
                            val key = "$name|$cmd"
                            if (seenMovies.add(key)) {
                                newCount++
                            }
                            val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: catId
                            movies.add(IptvItem(
                                name = name,
                                group = vodCatMap[cId] ?: vodCatMap[catId] ?: "General",
                                logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                                stalkerCmd = m["cmd"]?.toString(),
                                year = m["year"]?.toString()?.toIntOrNull(),
                                rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                            ))
                        }
                        if (newCount == 0) {
                            mHasMore = false
                        } else {
                            onPageFetched(movies)
                            mPage++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching Stalker VOD cat $catId page $mPage", e)
                    mHasMore = false
                }
            }
        }
    }

    suspend fun fetchRemainingStalkerSeries(
        portalUrl: String,
        mac: String,
        token: String,
        device: StalkerDevice,
        startPage: Int,
        seriesCatMap: Map<String, String>,
        onPageFetched: (List<IptvItem>) -> Unit
    ) {
        val seenSeries = HashSet<String>()
        val catIds = seriesCatMap.keys.toList() + listOf("0", "*", "")
        for (catId in catIds) {
            var sPage = 1
            var sHasMore = true
            var emptySeriesStreak = 0
            while (sHasMore && sPage <= 1000) {
                currentCoroutineContext().ensureActive()
                try {
                    val paramsToTry = listOf(
                        "type=series&action=get_ordered_list&category=$catId&p=$sPage",
                        "type=vod&action=get_ordered_list&movie_type=series&category=$catId&p=$sPage",
                        "type=series&action=get_ordered_list&genre=$catId&p=$sPage",
                        "type=series&action=get_ordered_list&p=$sPage"
                    )
                    var rawSeries = emptyList<Any>()
                    for (param in paramsToTry) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val seriesParsed = stalkerRequestAndParse(portalUrl, mac, param, token, device)
                            val list = extractListFromJsField(seriesParsed)
                            if (list.isNotEmpty()) {
                                rawSeries = list
                                break
                            }
                        } catch (e: Exception) {
                            // try next param
                        }
                    }
                    if (rawSeries.isEmpty()) {
                        emptySeriesStreak++
                        if (emptySeriesStreak >= 2) sHasMore = false else sPage++
                    } else {
                        emptySeriesStreak = 0
                        val series = ArrayList<IptvItem>()
                        var newCount = 0
                        rawSeries.forEach { se ->
                            val m = se as? Map<*, *> ?: return@forEach
                            val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                            val cmd = m["cmd"]?.toString() ?: ""
                            val key = "$name|$cmd"
                            if (seenSeries.add(key)) {
                                newCount++
                            }
                            var cleanId = m["id"]?.toString()
                                ?: m["series_id"]?.toString()
                                ?: m["movie_id"]?.toString()
                                ?: m["vod_id"]?.toString()
                                ?: m["id_series"]?.toString()
                                ?: m["video_id"]?.toString()
                                ?: ""
                            if (cleanId.isEmpty()) {
                                val cmdStr = m["cmd"]?.toString() ?: ""
                                val matchNum = Regex("(\\d+)").find(cmdStr)
                                if (matchNum != null) {
                                    cleanId = matchNum.value
                                }
                            }
                            val cId = m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: catId
                            series.add(IptvItem(
                                name = name,
                                group = seriesCatMap[cId] ?: seriesCatMap[catId] ?: "General",
                                logo = m["screenshot_uri"]?.toString() ?: m["logo"]?.toString(),
                                stalkerCmd = m["cmd"]?.toString(),
                                seriesId = cleanId,
                                year = m["year"]?.toString()?.toIntOrNull(),
                                rating = m["rating_imdb"]?.toString()?.toFloatOrNull()
                            ))
                        }
                        if (newCount == 0) {
                            sHasMore = false
                        } else {
                            onPageFetched(series)
                            sPage++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching Stalker series cat $catId page $sPage", e)
                    sHasMore = false
                }
            }
        }
    }

    suspend fun resolveStalkerSeriesEpisodePlayLink(
        session: Map<String, Any>,
        descriptorOrCmd: String
    ): String {
        val portalUrl = session["portalUrl"] as? String ?: throw NetworkError.NotFound("URL de portal no configurada")
        val mac = session["mac"] as? String ?: throw NetworkError.NotFound("MAC no configurada")
        val token = session["token"] as? String ?: ""
        val device = (session["device"] as? StalkerDevice) ?: StalkerDevice()

        if (token.isBlank()) {
            throw NetworkError.Auth("Token Stalker no disponible")
        }

        val epInfo = parseStalkerEpisodeDescriptor(descriptorOrCmd) ?: run {
            if (descriptorOrCmd.contains(":")) {
                val parts = descriptorOrCmd.split(":")
                StalkerEpisodeInfo(
                    seriesId = parts[0],
                    seasonNum = parts.getOrNull(1) ?: "1",
                    episodeNum = parts.getOrNull(2) ?: "1",
                    cmd = "",
                    isRealEpisode = true
                )
            } else {
                StalkerEpisodeInfo(
                    cmd = descriptorOrCmd,
                    isRealEpisode = true
                )
            }
        }

        logStalkerSeries(
            stage = "UI_REQUEST_RECEIVED",
            seriesId = epInfo.seriesId.ifBlank { null },
            seriesCmd = epInfo.seriesCmd.ifBlank { null },
            seasonId = epInfo.seasonId.ifBlank { null },
            seasonNum = epInfo.seasonNum.ifBlank { null },
            episodeId = epInfo.episodeId.ifBlank { null },
            episodeNum = epInfo.episodeNum.ifBlank { null },
            videoId = epInfo.videoId.ifBlank { null },
            fileId = epInfo.fileId.ifBlank { null },
            cmd = epInfo.cmd.ifBlank { null },
            file = epInfo.file.ifBlank { null },
            url = epInfo.url.ifBlank { null }
        )

        suspend fun tryExecuteCreateLink(reqType: String, actionParams: String): String? {
            val fullParams = "type=$reqType&$actionParams"

            logStalkerSeries(
                stage = "TRY_CREATE_LINK",
                seriesId = epInfo.seriesId.ifBlank { null },
                seriesCmd = epInfo.seriesCmd.ifBlank { null },
                episodeNum = epInfo.episodeNum.ifBlank { null },
                requestUrl = fullParams
            )
            try {
                val httpResp = stalkerRequestWithResponse(portalUrl, mac, fullParams, token, device)
                val resp = httpResp.body
                val httpCode = httpResp.code
                val trimmed = resp.trim()
                if (trimmed.isEmpty()) return null

                if (trimmed.contains("Authorization failed", ignoreCase = true) ||
                    trimmed.contains("Access denied", ignoreCase = true) ||
                    trimmed.contains("Token expired", ignoreCase = true) ||
                    trimmed.contains("Unauthorized", ignoreCase = true)
                ) {
                    logStalkerSeries(
                        stage = "AUTH_ERROR_DETECTED",
                        rawResponse = trimmed,
                        error = "Token expirado o no autorizado"
                    )
                    throw NetworkError.Auth("Sesión Stalker expirada o no autorizada")
                }

                val parsed = parseStalkerJsResponse(resp)
                val jsObj = parsed["js"]
                val topLevelKeys = parsed.keys.joinToString(",")
                val jsType = when (jsObj) {
                    null -> "null"
                    is Map<*, *> -> "object"
                    is List<*> -> "array"
                    is String -> "string"
                    is Number -> "number"
                    is Boolean -> "boolean"
                    else -> jsObj.javaClass.simpleName
                }
                val jsKeys = (jsObj as? Map<*, *>)?.keys?.joinToString(",") ?: ""
                val cmdVal = (jsObj as? Map<*, *>)?.get("cmd")?.toString() ?: (if (jsObj is String) jsObj else null)
                val cmdPresent = !cmdVal.isNullOrBlank()
                val cmdLength = cmdVal?.length ?: 0
                val cmdPrefix = if (cmdPresent) cmdVal!!.take(15) else ""

                val errVal = parsed["error"]?.toString() ?: (jsObj as? Map<*, *>)?.get("error")?.toString()
                val errCodeVal = parsed["errorCode"]?.toString() ?: (jsObj as? Map<*, *>)?.get("errorCode")?.toString()
                val msgVal = parsed["message"]?.toString() ?: (jsObj as? Map<*, *>)?.get("message")?.toString()
                val statusVal = parsed["status"]?.toString() ?: (jsObj as? Map<*, *>)?.get("status")?.toString()
                val resultsVal = parsed["results"]?.toString() ?: (jsObj as? Map<*, *>)?.get("results")?.toString()
                val dataVal = parsed["data"]?.toString() ?: (jsObj as? Map<*, *>)?.get("data")?.toString()

                val candidateList = mutableListOf<String>()
                extractCandidateUrls(jsObj, candidateList)
                extractCandidateUrls(parsed, candidateList)

                for (candidate in candidateList) {
                    val norm = normalizeResultUrl(candidate, portalUrl)
                    if (norm != null) {
                        logStalkerSeries(
                            stage = "CREATE_LINK_SUCCESS",
                            requestUrl = fullParams,
                            cmd = candidate,
                            finalUrl = norm
                        )
                        return norm
                    }
                }

                val rawMatch = Regex("((https?|rtsp|rtmp)://[^\\s\"'\\\\{}<>,]+)").find(resp)
                if (rawMatch != null) {
                    val norm = normalizeResultUrl(rawMatch.value, portalUrl)
                    if (norm != null) {
                        logStalkerSeries(
                            stage = "CREATE_LINK_SUCCESS",
                            requestUrl = fullParams,
                            finalUrl = norm
                        )
                        return norm
                    }
                }

                logStalkerSeries(
                    stage = "CREATE_LINK_EMPTY",
                    requestUrl = fullParams,
                    rawResponse = trimmed
                )
            } catch (authEx: NetworkError.Auth) {
                throw authEx
            } catch (e: Exception) {
                logStalkerSeries(
                    stage = "CREATE_LINK_EXCEPTION",
                    requestUrl = fullParams,
                    error = e.message
                )
            }
            return null
        }

        // CASO F: URL directa ya presente en el recurso
        val directCandidate = listOf(epInfo.url, epInfo.path, epInfo.cmd, descriptorOrCmd)
            .mapNotNull { it.ifBlank { null } }
            .firstNotNullOfOrNull { raw ->
                if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("rtsp://") || raw.startsWith("rtmp://")) {
                    normalizeResultUrl(raw, portalUrl)
                } else null
            }

        val epNum = epInfo.episodeNum.ifEmpty { epInfo.seriesNumber }.ifEmpty { "1" }
        val sNum = epInfo.seasonNum.ifEmpty { "1" }

        val validEpisodeCmd = epInfo.cmd.takeIf {
            it.isNotBlank() && it != "CHANNEL_NO_PLAYABLE_CMD" && !it.startsWith("stalker_ep://") && !it.contains(":")
        }
        val targetSeriesCmd = epInfo.seriesCmd.takeIf { it.isNotBlank() && !it.contains(":") }
        val realSeriesId = epInfo.seriesId.takeIf { it.isNotBlank() && !it.contains(":") }

        if (directCandidate != null) {
            logStalkerSeries(
                stage = "DIRECT_STREAM_URL_FOUND",
                finalUrl = directCandidate
            )
            return directCandidate
        }

        // CASO B: Cmd real del episodio
        if (validEpisodeCmd != null) {
            val cmdAttempts = listOf(
                "series" to "action=create_link&cmd=${URLParamEncode(validEpisodeCmd)}&forced_storage=0&disable_ad=0&download=0",
                "series" to "action=create_link&cmd=${URLParamEncode(validEpisodeCmd)}&series=${URLParamEncode(epNum)}",
                "series" to "action=create_link&cmd=${URLParamEncode(validEpisodeCmd)}",
                "vod" to "action=create_link&cmd=${URLParamEncode(validEpisodeCmd)}&series=${URLParamEncode(epNum)}&forced_storage=0&disable_ad=0&download=0",
                "vod" to "action=create_link&cmd=${URLParamEncode(validEpisodeCmd)}&series=${URLParamEncode(epNum)}",
                "vod" to "action=create_link&cmd=${URLParamEncode(validEpisodeCmd)}"
            )
            for ((type, query) in cmdAttempts) {
                val res = tryExecuteCreateLink(type, query)
                if (res != null) return res
            }
        }

        // CASO C / D: file_id o file real del episodio
        if (epInfo.fileId.isNotBlank()) {
            val fileIdAttempts = listOf(
                "vod" to "action=create_link&file_id=${URLParamEncode(epInfo.fileId)}&forced_storage=0&disable_ad=0&download=0",
                "vod" to "action=create_link&file_id=${URLParamEncode(epInfo.fileId)}",
                "series" to "action=create_link&file_id=${URLParamEncode(epInfo.fileId)}"
            )
            for ((type, query) in fileIdAttempts) {
                val res = tryExecuteCreateLink(type, query)
                if (res != null) return res
            }
        }

        if (epInfo.file.isNotBlank()) {
            val fileAttempts = listOf(
                "vod" to "action=create_link&cmd=${URLParamEncode(epInfo.file)}&forced_storage=0&disable_ad=0&download=0",
                "vod" to "action=create_link&cmd=${URLParamEncode(epInfo.file)}",
                "series" to "action=create_link&cmd=${URLParamEncode(epInfo.file)}"
            )
            for ((type, query) in fileAttempts) {
                val res = tryExecuteCreateLink(type, query)
                if (res != null) return res
            }
        }

        // CASO A: Real series cmd + series parameter
        if (targetSeriesCmd != null) {
            val seriesCmdAttempts = listOf(
                "vod" to "action=create_link&cmd=${URLParamEncode(targetSeriesCmd)}&series=${URLParamEncode(epNum)}&forced_storage=0&disable_ad=0&download=0",
                "vod" to "action=create_link&cmd=${URLParamEncode(targetSeriesCmd)}&series=${URLParamEncode(epNum)}",
                "series" to "action=create_link&cmd=${URLParamEncode(targetSeriesCmd)}&series=${URLParamEncode(epNum)}"
            )
            for ((type, query) in seriesCmdAttempts) {
                val res = tryExecuteCreateLink(type, query)
                if (res != null) return res
            }
        }

        // CASO E: Parámetros estructurados por portal con IDs reales
        if (realSeriesId != null) {
            val structuredAttempts = mutableListOf<Pair<String, String>>()
            if (epInfo.seasonId.isNotBlank() && epInfo.episodeId.isNotBlank()) {
                structuredAttempts.add("series" to "action=create_link&series_id=${URLParamEncode(realSeriesId)}&season_id=${URLParamEncode(epInfo.seasonId)}&episode_id=${URLParamEncode(epInfo.episodeId)}")
            }
            structuredAttempts.add("series" to "action=create_link&series_id=${URLParamEncode(realSeriesId)}&season=${URLParamEncode(sNum)}&episode=${URLParamEncode(epNum)}")
            structuredAttempts.add("vod" to "action=create_link&cmd=${URLParamEncode(realSeriesId)}&series=${URLParamEncode(epNum)}&forced_storage=0&disable_ad=0&download=0")
            structuredAttempts.add("vod" to "action=create_link&cmd=${URLParamEncode(realSeriesId)}&series=${URLParamEncode(epNum)}")
            structuredAttempts.add("vod" to "action=create_link&movie_id=${URLParamEncode(realSeriesId)}&episode=${URLParamEncode(epNum)}")

            for ((type, query) in structuredAttempts) {
                val res = tryExecuteCreateLink(type, query)
                if (res != null) return res
            }
        }

        // Episode ID / Video ID directo como file_id
        val idToTry = epInfo.episodeId.takeIf { it.isNotBlank() && !it.contains(":") }
            ?: epInfo.videoId.takeIf { it.isNotBlank() && !it.contains(":") }
        if (idToTry != null && idToTry != realSeriesId) {
            val idAttempts = listOf(
                "vod" to "action=create_link&file_id=${URLParamEncode(idToTry)}",
                "series" to "action=create_link&file_id=${URLParamEncode(idToTry)}"
            )
            for ((type, query) in idAttempts) {
                val res = tryExecuteCreateLink(type, query)
                if (res != null) return res
            }
        }

        logStalkerSeries(
            stage = "EPISODE_RESOLUTION_FAILED",
            seriesId = epInfo.seriesId.ifBlank { null },
            seriesCmd = epInfo.seriesCmd.ifBlank { null },
            seasonId = epInfo.seasonId.ifBlank { null },
            seasonNum = epInfo.seasonNum.ifBlank { null },
            episodeId = epInfo.episodeId.ifBlank { null },
            episodeNum = epInfo.episodeNum.ifBlank { null },
            fileId = epInfo.fileId.ifBlank { null },
            cmd = epInfo.cmd.ifBlank { null },
            error = "Ningún método de create_link devolvió enlace reproducible"
        )
        throw NetworkError.NotFound("El portal Stalker no devolvió un enlace de reproducción válido para el episodio")
    }

    suspend fun resolveStalkerPlayLink(session: Map<String, Any>, cmd: String, itemType: String? = null): String {
        if (itemType == "series" || cmd.startsWith("stalker_ep://")) {
            return resolveStalkerSeriesEpisodePlayLink(session, cmd)
        }

        if (cmd == "CHANNEL_NO_PLAYABLE_CMD" || cmd.isBlank()) {
            throw NetworkError.NotFound("El canal no cuenta con un comando o URL de reproducción en el portal Stalker")
        }

        val portalUrl = session["portalUrl"] as String
        val mac = session["mac"] as String
        val token = session["token"] as String
        val device = session["device"] as StalkerDevice

        if (token.isBlank()) {
            throw NetworkError.Auth("Token Stalker no disponible")
        }

        val parsedUri = try { java.net.URI(portalUrl) } catch (e: Exception) { null }
        val portalHostBase = if (parsedUri != null) {
            val scheme = parsedUri.scheme ?: "http"
            val host = parsedUri.host ?: ""
            val port = if (parsedUri.port != -1) ":${parsedUri.port}" else ""
            "$scheme://$host$port"
        } else {
            portalUrl.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")
        }

        val directMatch = Regex("((https?|rtsp|rtmp)://[^\\s\"'\\\\{}<>,]+)").find(cmd)
        if (directMatch != null) {
            var v = directMatch.value
            if (v.contains("localhost")) {
                v = v.replace("http://localhost", portalHostBase).replace("https://localhost", portalHostBase).replace("localhost", parsedUri?.host ?: "localhost")
            }
            if (v.contains("127.0.0.1")) {
                v = v.replace("http://127.0.0.1", portalHostBase).replace("https://127.0.0.1", portalHostBase).replace("127.0.0.1", parsedUri?.host ?: "127.0.0.1")
            }
            return v
        }

        val isTv = itemType == "itv" || (itemType == null && (cmd.contains("/ch/", ignoreCase = true) || cmd.contains("ch/", ignoreCase = true) || cmd.contains("ffrt", ignoreCase = true) || cmd.contains("ffmpeg", ignoreCase = true)))
        
        val typesToTry = when {
            isTv -> listOf("itv", "stb")
            itemType == "series" -> listOf("vod", "series")
            itemType == "vod" -> listOf("vod")
            else -> listOf("vod", "series", "itv")
        }

        val cmdsToTry = LinkedHashSet<String>()
        cmdsToTry.add(cmd)

        if (cmd.startsWith("ffrt ") || cmd.startsWith("ffmpeg ") || cmd.startsWith("auto ") || cmd.startsWith("udp ") || cmd.startsWith("rtp ")) {
            val cleaned = cmd.substringAfter(" ").trim()
            cmdsToTry.add(cleaned)
        }

        val matchNum = Regex("(\\d+)").find(cmd)
        val extractedNum = matchNum?.value

        if (extractedNum != null && extractedNum.isNotEmpty()) {
            cmdsToTry.add(extractedNum)
        }

        var lastError: Exception? = null
        for (t in typesToTry) {
            for (c in cmdsToTry) {
                try {
                    val resolved = performResolveLink(portalUrl, mac, c, token, device, t)
                    if (resolved.isNotBlank()) {
                        Log.d(TAG, "Successfully resolved Stalker link: $resolved (type=$t, cmd=$c)")
                        return resolved
                    }
                } catch (authEx: NetworkError.Auth) {
                    throw authEx
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        val cleanCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").removePrefix("auto ").trim()
        if (cleanCmd.startsWith("ch/") || cleanCmd.startsWith("/ch/") ||
            cleanCmd.startsWith("live/") || cleanCmd.startsWith("/live/") ||
            cleanCmd.startsWith("vod/") || cleanCmd.startsWith("/vod/") ||
            cleanCmd.startsWith("media/") || cleanCmd.startsWith("/media/") ||
            cleanCmd.startsWith("series/") || cleanCmd.startsWith("/series/")) {
            val path = if (cleanCmd.startsWith("/")) cleanCmd else "/$cleanCmd"
            return "$portalHostBase$path"
        }

        throw NetworkError.NotFound(lastError?.message ?: "No se pudo obtener el enlace de reproducción para este canal en Stalker")
    }

    private fun performResolveLink(portalUrl: String, mac: String, cmd: String, token: String, device: StalkerDevice, type: String): String {
        // Delegates to canonical top-level normalizeResultUrl(raw, portalUrl)
        fun normalizeResultUrl(raw: String): String? = normalizeResultUrl(raw, portalUrl)

        val createLinkParamOptions = mutableListOf<String>()
        if (type == "itv" || type == "stb") {
            createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0")
            createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}&series=&forced_storage=0&disable_ad=0&download=0")
            createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}")
            createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}&forced_storage=1")
            createLinkParamOptions.add("type=$type&action=create_link&ch_id=${URLParamEncode(cmd)}")
        } else {
            if (cmd.contains(":")) {
                val parts = cmd.split(":")
                val sId = parts[0]
                val sNum = parts.getOrNull(1) ?: "1"
                val eNum = parts.getOrNull(2) ?: "1"
                createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(sId)}&series=${URLParamEncode(eNum)}&forced_storage=0&disable_ad=0&download=0")
                createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(sId)}&series=${URLParamEncode(eNum)}")
                createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(sId)}&season=${URLParamEncode(sNum)}&episode=${URLParamEncode(eNum)}")
                createLinkParamOptions.add("type=$type&action=create_link&series_id=${URLParamEncode(sId)}&season=${URLParamEncode(sNum)}&episode=${URLParamEncode(eNum)}")
                createLinkParamOptions.add("type=$type&action=create_link&movie_id=${URLParamEncode(sId)}&episode=${URLParamEncode(eNum)}")
                createLinkParamOptions.add("type=$type&action=create_link&file_id=${URLParamEncode(eNum)}")
                createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}")
            } else {
                createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0")
                createLinkParamOptions.add("type=$type&action=create_link&cmd=${URLParamEncode(cmd)}")
                createLinkParamOptions.add("type=$type&action=create_link&file_id=${URLParamEncode(cmd)}")
                createLinkParamOptions.add("type=$type&action=create_link&movie_id=${URLParamEncode(cmd)}")
            }
        }

        fun extractCandidateUrls(obj: Any?, targetList: MutableList<String>) {
            when (obj) {
                is String -> {
                    if (obj.isNotBlank()) targetList.add(obj)
                }
                is Map<*, *> -> {
                    val urlKeys = listOf("cmd", "url", "uri", "link", "stream", "path", "location", "direct_link", "stream_url", "file", "src")
                    urlKeys.forEach { k ->
                        val v = obj[k]?.toString()
                        if (!v.isNullOrBlank()) targetList.add(v)
                    }
                    listOf("data", "result", "js", "item", "info").forEach { subKey ->
                        if (obj.containsKey(subKey)) {
                            extractCandidateUrls(obj[subKey], targetList)
                        }
                    }
                }
                is List<*> -> {
                    obj.forEach { item -> extractCandidateUrls(item, targetList) }
                }
            }
        }

        for (params in createLinkParamOptions) {
            try {
                val httpResp = stalkerRequestWithResponse(portalUrl, mac, params, token, device)
                val resp = httpResp.body
                val httpCode = httpResp.code

                if (resp.contains("Authorization failed", ignoreCase = true) ||
                    resp.contains("Access denied", ignoreCase = true) ||
                    resp.contains("Token expired", ignoreCase = true) ||
                    resp.contains("Unauthorized", ignoreCase = true)) {
                    throw NetworkError.Auth("Sesión Stalker expirada o no autorizada")
                }

                val parsed = parseStalkerJsResponse(resp)
                val jsObj = parsed["js"]
                val topLevelKeys = parsed.keys.joinToString(",")
                val jsType = when (jsObj) {
                    null -> "null"
                    is Map<*, *> -> "object"
                    is List<*> -> "array"
                    is String -> "string"
                    is Number -> "number"
                    is Boolean -> "boolean"
                    else -> jsObj.javaClass.simpleName
                }
                val jsKeys = (jsObj as? Map<*, *>)?.keys?.joinToString(",") ?: ""
                val cmdVal = (jsObj as? Map<*, *>)?.get("cmd")?.toString() ?: (if (jsObj is String) jsObj else null)
                val cmdPresent = !cmdVal.isNullOrBlank()
                val cmdLength = cmdVal?.length ?: 0
                val cmdPrefix = if (cmdPresent) cmdVal!!.take(15) else ""

                val errVal = parsed["error"]?.toString() ?: (jsObj as? Map<*, *>)?.get("error")?.toString()
                val errCodeVal = parsed["errorCode"]?.toString() ?: (jsObj as? Map<*, *>)?.get("errorCode")?.toString()
                val msgVal = parsed["message"]?.toString() ?: (jsObj as? Map<*, *>)?.get("message")?.toString()
                val statusVal = parsed["status"]?.toString() ?: (jsObj as? Map<*, *>)?.get("status")?.toString()
                val resultsVal = parsed["results"]?.toString() ?: (jsObj as? Map<*, *>)?.get("results")?.toString()
                val dataVal = parsed["data"]?.toString() ?: (jsObj as? Map<*, *>)?.get("data")?.toString()

                val possibleStrings = mutableListOf<String>()
                extractCandidateUrls(jsObj, possibleStrings)
                extractCandidateUrls(parsed, possibleStrings)

                for (str in possibleStrings) {
                    val normalized = normalizeResultUrl(str)
                    if (normalized != null) {
                        return normalized
                    }
                }

                val rawMatch = Regex("((https?|rtsp|rtmp)://[^\\s\"'\\\\{}<>,]+)").find(resp)
                if (rawMatch != null) {
                    val normalized = normalizeResultUrl(rawMatch.value)
                    if (normalized != null) {
                        return normalized
                    }
                }
            } catch (authEx: NetworkError.Auth) {
                throw authEx
            } catch (e: Exception) {
                Log.e(TAG, "Error in performResolveLink attempt with params $params", e)
            }
        }

        val directMatch = Regex("((https?|rtsp|rtmp)://[^\\s\"'\\\\{}<>,]+)").find(cmd)
        if (directMatch != null) {
            val normalized = normalizeResultUrl(directMatch.value)
            if (normalized != null) return normalized
        }

        throw NetworkError.NotFound("El portal Stalker no devolvió un enlace de reproducción válido para tipo $type")
    }

    /**
     * Envía un ping de watchdog ligero para mantener activa la sesión del portal Stalker
     * durante reproducciones prolongadas (>10 minutos).
     * Devuelve false si el servidor reporta token expirado o acceso denegado.
     */
    suspend fun sendStalkerWatchdogPing(session: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        val portalUrl = session["portalUrl"] as? String ?: return@withContext false
        val mac = session["mac"] as? String ?: return@withContext false
        val token = session["token"] as? String ?: return@withContext false
        val device = session["device"] as? StalkerDevice ?: return@withContext false
        if (portalUrl.isBlank() || mac.isBlank() || token.isBlank()) return@withContext false

        return@withContext try {
            val params = "type=watchdog&action=get_events&cur_play_type=1&event_active_id=0&init=0"
            val httpResp = stalkerRequestWithResponse(portalUrl, mac, params, token, device)
            val body = httpResp.body
            if (httpResp.code == 401 || httpResp.code == 403 ||
                body.contains("Authorization failed", ignoreCase = true) ||
                body.contains("Token expired", ignoreCase = true) ||
                body.contains("Unauthorized", ignoreCase = true)
            ) {
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stalker watchdog ping transient error: ${e.message}")
            true
        }
    }
}
