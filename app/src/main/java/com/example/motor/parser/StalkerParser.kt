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
import kotlinx.coroutines.sync.Semaphore
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

    fun generateDeviceId(mac: String, serialNumber: String, model: String): DeviceIdResult {
        val macClean = mac.replace(":", "").uppercase()
        val serialClean = serialNumber.replace(":", "")
        val serialCleanLast10 = if (serialClean.length >= 10) {
            serialClean.substring(serialClean.length - 10)
        } else {
            serialClean
        }
        // Timestamp realista basado en época reciente para compatibilidad con middlewares estrictos
        val epochSeconds = (System.currentTimeMillis() / 1000L).toString()
        val deviceId = "$macClean:$serialCleanLast10:$model:$epochSeconds"
        return DeviceIdResult(deviceId, macClean, serialCleanLast10)
    }

    fun generateDeviceId2(deviceId: String, mac: String): String {
        val macClean = mac.replace(":", "").uppercase()
        var hash = 0
        for (i in 0 until deviceId.length) {
            val charCode = deviceId[i].code
            hash = ((hash shl 5) - hash) + charCode
        }
        val uHash = java.lang.Long.toUnsignedString(hash.toLong() and 0xFFFFFFFFL, 36).uppercase()
        return "${macClean}_$uHash"
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
        val macClean = mac.replace(":", "").uppercase()
        val baseString = listOf(
            macClean,
            serialNumber,
            model,
            hwVersion,
            swVersion,
            deviceId,
            imageVersion,
            vendor
        ).joinToString("|")

        var hash1 = 0
        var hash2 = 0

        for (i in 0 until baseString.length) {
            val char = baseString[i].code
            hash1 = ((hash1 shl 5) - hash1) + char
            if (i % 2 == 0) {
                hash2 = ((hash2 shl 3) - hash2) + char
            }
        }

        val absHash1 = kotlin.math.abs(hash1.toLong())
        val absHash2 = kotlin.math.abs(hash2.toLong())

        val signature1 = absHash1.toString(16).uppercase()
        val signature2 = absHash2.toString(16).uppercase()

        return signature1.padStart(8, '0') + signature2.padStart(8, '0')
    }

    fun generateApiSignature(token: String, random: String, mac: String): String {
        val macClean = mac.replace(":", "").uppercase()
        val baseString = "$token|$random|$macClean"

        var hash = 0
        for (i in 0 until baseString.length) {
            val char = baseString[i].code
            hash = ((hash shl 5) - hash) + char
        }

        val absHash = kotlin.math.abs(hash.toLong())
        return absHash.toString(16).uppercase()
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

    private fun stalkerRequestWithResponse(portalUrl: String, mac: String, params: String, token: String?, device: StalkerDevice): StalkerHttpResponse {
        val macClean = mac.trim().uppercase()
        val finalToken = token ?: ""
        val tokenParam = if (finalToken.isNotEmpty()) "&token=${URLParamEncode(finalToken)}" else ""

        val snVal = if (device.serialNumber.isNotBlank()) {
            device.serialNumber
        } else {
            val suffix = if (macClean.length >= 6) macClean.substring(macClean.length - 6) else "123456"
            "001A79$suffix"
        }

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
        
        var currentUrl = portalUrl
        if (currentUrl.startsWith("https://", ignoreCase = true) && currentUrl.contains(":80")) {
            currentUrl = "http" + currentUrl.substring(5)
        }

        val url = if (currentUrl.contains("?")) {
            "$currentUrl&$combinedParams"
        } else {
            "$currentUrl?$combinedParams"
        }

        val cookieHeaderVal = if (finalToken.isNotEmpty()) {
            "mac=$macClean; stb_lang=en; timezone=Europe/Madrid; token=$finalToken;"
        } else {
            "mac=$macClean; stb_lang=en; timezone=Europe/Madrid;"
        }
        val headers = mapOf(
            "User-Agent" to device.userAgent,
            "Cookie" to cookieHeaderVal,
            "X-User-Agent" to device.xUserAgent,
            "Accept" to "*/*"
        )
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
        if (resp.code in 200..299) {
            return resp.body
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
        
        // If jsObj is not null, try to extract list from it
        if (jsObj != null) {
            val list = extractListFromField(jsObj)
            if (list.isNotEmpty()) return list
        }
        
        // If jsObj is null or returned empty list, try to extract list from the root parsed map
        val rootList = extractListFromField(parsed)
        if (rootList.isNotEmpty()) return rootList
        
        return emptyList()
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
        return try {
            val json = stalkerRequest(portalUrl, mac, params, token, device)
            parseStalkerJsResponse(json)
        } catch (e: Exception) {
            Log.w(TAG, "stalkerRequestAndParse error for $params: ${e.message}")
            emptyMap()
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
        val savedCompatState = if (playlistId != null) repo?.getStalkerCompatState(playlistId.toString()) else null

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

        // Normalización y preparación de endpoints candidatos
        var baseHost = host.trim()
        if (baseHost.startsWith("https://", ignoreCase = true) && baseHost.contains(":80")) {
            baseHost = "http" + baseHost.substring(5)
        }
        if (!baseHost.startsWith("http://", ignoreCase = true) && !baseHost.startsWith("https://", ignoreCase = true)) {
            baseHost = "http://$baseHost"
        }
        baseHost = baseHost.removeSuffix("/")

        val candidates = mutableListOf<String>()
        if (savedCompatState != null && savedCompatState.resolvedPortalUrl.isNotBlank()) {
            candidates.add(savedCompatState.resolvedPortalUrl)
        }
        if (baseHost.contains(".php") || baseHost.contains("/c/") || baseHost.endsWith("/portal") || baseHost.contains("?")) {
            if (!candidates.contains(baseHost)) candidates.add(baseHost)
        }
        val standardEndpoints = listOf(
            "$baseHost/portal.php",
            "$baseHost/stalker_portal/server/load.php",
            "$baseHost/server/load.php",
            "$baseHost/c/portal.php"
        )
        for (ep in standardEndpoints) {
            if (!candidates.contains(ep)) candidates.add(ep)
        }

        var portalUrl: String? = null
        var token: String? = null
        var apiSignature = ""
        var activeDeviceModel = candidateModels.first()
        var activeDevice = device
        var activeProfileVariant = candidateProfiles.first()
        var lastError: Exception? = null

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

            val snVal = if (testDevice.serialNumber.isNotBlank()) {
                testDevice.serialNumber
            } else {
                val suffix = if (macClean.length >= 6) macClean.substring(macClean.length - 6) else "123456"
                "001A79$suffix"
            }

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

            for (cand in candidates) {
                try {
                    var currentCand = cand
                    if (currentCand.startsWith("https://", ignoreCase = true) && currentCand.contains(":80")) {
                        currentCand = "http" + currentCand.substring(5)
                    }

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

                    for (hParams in handshakes) {
                        var httpResp: StalkerHttpResponse
                        try {
                            httpResp = stalkerRequestWithResponse(currentCand, macClean, hParams, null, testDevice)
                        } catch (e: Exception) {
                            val msg = e.message?.lowercase() ?: ""
                            if (currentCand.startsWith("https://", ignoreCase = true) &&
                                (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                                val fallbackCand = "http" + currentCand.substring(5)
                                Log.w(TAG, "TLS/SSL error on stalker handshake for $currentCand, trying $fallbackCand")
                                httpResp = stalkerRequestWithResponse(fallbackCand, macClean, hParams, null, testDevice)
                                currentCand = fallbackCand
                            } else {
                                throw e
                            }
                        }

                        val resp = httpResp.body
                        val parsed = parseStalkerJsResponse(resp)
                        val js = parsed["js"] as? Map<*, *>
                        val t = js?.get("token")?.toString()
                        if (!t.isNullOrEmpty()) {
                            token = t
                            portalUrl = currentCand
                            activeDeviceModel = modelCand
                            activeDevice = testDevice
                            val rand = js["random"]?.toString()
                            if (!rand.isNullOrEmpty()) {
                                apiSignature = generateApiSignature(t, rand, macClean)
                            }
                            break@outerLoop
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        val finalPortalUrl = portalUrl ?: throw BaseEngine.classifyException(lastError ?: NetworkError.ServerDown("No se pudo contactar con el portal Stalker"))
        val finalToken = token ?: ""

        // FASE 2: GET_PROFILE NEGOCIACIÓN
        val snVal = if (activeDevice.serialNumber.isNotBlank()) {
            activeDevice.serialNumber
        } else {
            val suffix = if (macClean.length >= 6) macClean.substring(macClean.length - 6) else "123456"
            "001A79$suffix"
        }
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
        val apiSigValue = apiSignature.ifEmpty { "262" }

        var profileSuccess = false
        var activeToken = finalToken

        for (profVariant in candidateProfiles) {
            val profileParams = when (profVariant) {
                StalkerProfileVariant.STANDARD_V1 -> {
                    "type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200%20version&num_banks=2" +
                            "&stb_type=${URLParamEncode(activeDevice.hwVersion)}&image_version=${URLParamEncode(activeDevice.imageVersion)}" +
                            "&auth_second_step=0&hw_version=${URLParamEncode(activeDevice.hwVersion)}&hw_version_2=${URLParamEncode(activeDevice.hwVersion2)}&not_valid_token=0" +
                            "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                            "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"
                }
                StalkerProfileVariant.SIMPLIFIED_V2 -> {
                    "type=stb&action=get_profile&hd=1&num_banks=2" +
                            "&stb_type=${URLParamEncode(activeDevice.hwVersion)}&image_version=${URLParamEncode(activeDevice.imageVersion)}" +
                            "&auth_second_step=0&hw_version=${URLParamEncode(activeDevice.hwVersion)}&hw_version_2=${URLParamEncode(activeDevice.hwVersion2)}&not_valid_token=0" +
                            "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                            "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"
                }
                StalkerProfileVariant.MINISTRA_MODERN -> {
                    "type=stb&action=get_profile&hd=1&client_type=STB&ver=5.1.0" +
                            "&stb_type=${URLParamEncode(activeDevice.hwVersion)}&image_version=${URLParamEncode(activeDevice.imageVersion)}" +
                            "&auth_second_step=0&hw_version=${URLParamEncode(activeDevice.hwVersion)}&hw_version_2=${URLParamEncode(activeDevice.hwVersion2)}&not_valid_token=0" +
                            "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                            "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"
                }
            }

            try {
                val profResp = stalkerRequest(finalPortalUrl, macClean, profileParams, finalToken, activeDevice)
                val profParsed = parseStalkerJsResponse(profResp)
                val jsProf = profParsed["js"] as? Map<*, *>

                if (jsProf?.get("block_msg") != null) {
                    throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal: " + jsProf["block_msg"].toString())
                }

                activeToken = jsProf?.get("token")?.toString()?.ifEmpty { finalToken } ?: finalToken
                activeProfileVariant = profVariant
                profileSuccess = true
                break
            } catch (authEx: NetworkError.PortalBlockedMac) {
                throw authEx
            } catch (e: Exception) {
                Log.w(TAG, "Profile variant $profVariant failed on $finalPortalUrl: ${e.message}")
            }
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

        return mapOf(
            "portalUrl" to finalPortalUrl,
            "mac" to macClean,
            "token" to activeToken,
            "device" to activeDevice
        )
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
        val finalPortalUrl = sessionMap["portalUrl"] as String
        val macClean = sessionMap["mac"] as String
        val activeToken = sessionMap["token"] as String

        onProgress("Obteniendo categorías de TV...")
        var rawGenres = emptyList<Any>()
        val genresAttempts = listOf(
            "type=itv&action=get_genres",
            "type=itv&action=get_categories"
        )
        for (params in genresAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val genresParsed = stalkerRequestAndParse(finalPortalUrl, macClean, params, activeToken, device)
                val list = extractListFromJsField(genresParsed)
                if (list.isNotEmpty()) {
                    rawGenres = list
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching ITV genres with params: $params", e)
            }
        }

        val genreMap = HashMap<String, String>()
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
                val chParsed = stalkerRequestAndParse(finalPortalUrl, macClean, params, activeToken, device)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching ITV channels with params: $params", e)
            }
        }

        // Si get_all_channels falló y hay géneros, paginación adaptativa y concurrente
        if (rawChannels.isEmpty() && genreMap.isNotEmpty()) {
            fallbackUsed = "genres_pagination"
            tvStrategyUsed = "get_ordered_list_genre"
            val downloadSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            coroutineScope {
                genreMap.keys.map { genreId ->
                    async(Dispatchers.IO) {
                        downloadSemaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            try {
                                val chParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=itv&action=get_ordered_list&genre=$genreId&p=1&max_rows=$INITIAL_PAGE_SIZE", activeToken, device)
                                val list = extractListFromJsField(chParsed)
                                synchronized(rawChannels) {
                                    list.forEach { c ->
                                        val m = c as? Map<*, *> ?: return@forEach
                                        val id = m["id"]?.toString() ?: m["number"]?.toString() ?: m["name"]?.toString() ?: ""
                                        val cmd = m["cmd"]?.toString() ?: m["url"]?.toString() ?: ""
                                        val key = "$id|$cmd"
                                        if (seenChKeys.add(key)) {
                                            rawChannels.add(c)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error fetching channels for genre $genreId", e)
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
                val chParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=itv&action=get_ordered_list&genre=0&p=$chPage&max_rows=$INITIAL_PAGE_SIZE", activeToken, device)
                var list = extractListFromJsField(chParsed)
                if (list.isEmpty()) {
                    val chParsedAlt = stalkerRequestAndParse(finalPortalUrl, macClean, "type=itv&action=get_ordered_list&p=$chPage", activeToken, device)
                    list = extractListFromJsField(chParsedAlt)
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
        val finalPortalUrl = sessionMap["portalUrl"] as String
        val macClean = sessionMap["mac"] as String
        val activeToken = sessionMap["token"] as String

        onProgress("Obteniendo categorías VOD...")
        var rawVodCats = emptyList<Any>()
        val vodCatAttempts = listOf(
            "type=vod&action=get_categories",
            "type=vod&action=get_genres"
        )
        for (params in vodCatAttempts) {
            try {
                val vodCatParsed = stalkerRequestAndParse(finalPortalUrl, macClean, params, activeToken, device)
                val list = extractListFromJsField(vodCatParsed)
                if (list.isNotEmpty()) {
                    rawVodCats = list
                    break
                }
            } catch (e: Exception) {
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

            // Estrategia 1: category con INITIAL_PAGE_SIZE (100)
            pagesRequested.incrementAndGet()
            var probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=vod&action=get_ordered_list&category=$probeCatId&p=1&max_rows=$INITIAL_PAGE_SIZE", activeToken, device)
            var probeList = extractListFromJsField(probeParsed)

            if (probeList.isNotEmpty()) {
                pagesCompleted.incrementAndGet()
                activeStrategy = "category"
                fallbackUsed = "none"
                detected = true
            } else {
                // Estrategia 2: genre con INITIAL_PAGE_SIZE
                pagesRequested.incrementAndGet()
                probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=vod&action=get_ordered_list&genre=$probeCatId&p=1&max_rows=$INITIAL_PAGE_SIZE", activeToken, device)
                probeList = extractListFromJsField(probeParsed)
                if (probeList.isNotEmpty()) {
                    pagesCompleted.incrementAndGet()
                    activeStrategy = "genre"
                    fallbackUsed = "genre"
                    detected = true
                }
            }

            // Si falló con max_rows=100, probar con 50 o sin max_rows
            if (!detected) {
                pagesRequested.incrementAndGet()
                probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=vod&action=get_ordered_list&category=$probeCatId&p=1&max_rows=$FALLBACK_PAGE_SIZE", activeToken, device)
                probeList = extractListFromJsField(probeParsed)
                if (probeList.isNotEmpty()) {
                    pagesCompleted.incrementAndGet()
                    activeStrategy = "category"
                    fallbackUsed = "fallback_page_size_50"
                    activePageSize = FALLBACK_PAGE_SIZE
                    pageSizeParam = "&max_rows=$FALLBACK_PAGE_SIZE"
                    detected = true
                } else {
                    // Probar sin max_rows (comportamiento clásico)
                    pagesRequested.incrementAndGet()
                    probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=vod&action=get_ordered_list&category=$probeCatId&p=1", activeToken, device)
                    probeList = extractListFromJsField(probeParsed)
                    if (probeList.isNotEmpty()) {
                        pagesCompleted.incrementAndGet()
                        activeStrategy = "category"
                        fallbackUsed = "no_max_rows"
                        activePageSize = probeList.size
                        pageSizeParam = ""
                        detected = true
                    } else {
                        // Último intento de detección: sin max_rows con genre
                        pagesRequested.incrementAndGet()
                        probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=vod&action=get_ordered_list&genre=$probeCatId&p=1", activeToken, device)
                        probeList = extractListFromJsField(probeParsed)
                        if (probeList.isNotEmpty()) {
                            pagesCompleted.incrementAndGet()
                            activeStrategy = "genre"
                            fallbackUsed = "no_max_rows_genre"
                            activePageSize = probeList.size
                            pageSizeParam = ""
                            detected = true
                        } else {
                            pagesFailed.incrementAndGet()
                        }
                    }
                }
            }

            // Procesar la página detectada si trajo items para no perderla
            if (probeList.isNotEmpty()) {
                val catName = vodCatMap[probeCatId] ?: "General"
                probeList.forEach { mo ->
                    val m = mo as? Map<*, *> ?: return@forEach
                    val name = m["name"]?.toString() ?: m["title"]?.toString() ?: "Sin nombre"
                    val cmd = m["cmd"]?.toString() ?: ""
                    val id = m["id"]?.toString() ?: m["movie_id"]?.toString() ?: m["vod_id"]?.toString() ?: ""
                    val key = if (id.isNotEmpty()) "$id|$cmd" else "$name|$cmd"
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
                            var catPage = if (catId == probeCatId && probeList.isNotEmpty()) 2 else 1
                            var catHasMore = true
                            while (catHasMore && catPage <= 1000) {
                                currentCoroutineContext().ensureActive()
                                val queryParams = "type=vod&action=get_ordered_list&$activeStrategy=$catId&p=$catPage$pageSizeParam"
                                pagesRequested.incrementAndGet()

                                var rawMovies = emptyList<Any>()
                                var attempt = 0
                                var success = false
                                val pageReqStart = System.currentTimeMillis()
                                while (attempt <= MAX_RETRIES_PER_PAGE && !success) {
                                    attempt++
                                    try {
                                        val vodParsed = stalkerRequestAndParse(finalPortalUrl, macClean, queryParams, activeToken, device)
                                        rawMovies = extractListFromJsField(vodParsed)
                                        success = true
                                        pagesCompleted.incrementAndGet()
                                    } catch (e: Exception) {
                                        if (attempt > MAX_RETRIES_PER_PAGE) {
                                            pagesFailed.incrementAndGet()
                                            Log.w(TAG, "Failed page $catPage for cat $catId after retries: ${e.message}")
                                        } else {
                                            delay(150)
                                        }
                                    }
                                }

                                if (rawMovies.isEmpty()) {
                                    catHasMore = false
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

                                    if (newCount == 0) {
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
                val testParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$strategy&p=1$pageSizeParam", activeToken, device)
                val testList = extractListFromJsField(testParsed)
                if (testList.isNotEmpty()) {
                    chosenGlobalPrefix = strategy
                    break
                }
            }

            var mPage = 1
            var mHasMore = true
            while (mHasMore && mPage <= 1500) {
                currentCoroutineContext().ensureActive()
                pagesRequested.incrementAndGet()
                var rawMovies = emptyList<Any>()
                try {
                    val vodParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$chosenGlobalPrefix&p=$mPage$pageSizeParam", activeToken, device)
                    rawMovies = extractListFromJsField(vodParsed)
                    pagesCompleted.incrementAndGet()
                } catch (e: Exception) {
                    pagesFailed.incrementAndGet()
                }

                if (rawMovies.isEmpty()) {
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
        val finalPortalUrl = sessionMap["portalUrl"] as String
        val macClean = sessionMap["mac"] as String
        val activeToken = sessionMap["token"] as String

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
                val seriesCatParsed = stalkerRequestAndParse(finalPortalUrl, macClean, params, activeToken, device)
                val list = extractListFromJsField(seriesCatParsed)
                if (list.isNotEmpty()) {
                    rawSeriesCats = list
                    break
                }
            } catch (e: Exception) {
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
                pagesRequested.incrementAndGet()
                try {
                    val probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$prefix$probeCatId&p=1&max_rows=$INITIAL_PAGE_SIZE", activeToken, device)
                    val list = extractListFromJsField(probeParsed)
                    if (list.isNotEmpty()) {
                        pagesCompleted.incrementAndGet()
                        probeList = list
                        activeStrategyPrefix = prefix
                        fallbackUsed = if (idx == 0) "none" else "prefix_variant_$idx"
                        detected = true
                        break
                    }
                } catch (e: Exception) {
                    pagesFailed.incrementAndGet()
                }
            }

            // Si falló con max_rows=100, probar con 50 o sin max_rows
            if (!detected) {
                for (prefix in candidatePrefixes) {
                    pagesRequested.incrementAndGet()
                    try {
                        val probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$prefix$probeCatId&p=1&max_rows=$FALLBACK_PAGE_SIZE", activeToken, device)
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
                    } catch (e: Exception) {
                        pagesFailed.incrementAndGet()
                    }
                }
            }

            if (!detected) {
                for (prefix in candidatePrefixes) {
                    pagesRequested.incrementAndGet()
                    try {
                        val probeParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$prefix$probeCatId&p=1", activeToken, device)
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
                    } catch (e: Exception) {
                        pagesFailed.incrementAndGet()
                    }
                }
            }

            // Procesar la página detectada si trajo items para no perderla
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
                            var sCatPage = if (catId == probeCatId && probeList.isNotEmpty()) 2 else 1
                            var sCatHasMore = true
                            while (sCatHasMore && sCatPage <= 1000) {
                                currentCoroutineContext().ensureActive()
                                val queryParams = "$activeStrategyPrefix$catId&p=$sCatPage$pageSizeParam"
                                pagesRequested.incrementAndGet()

                                var rawSeries = emptyList<Any>()
                                var attempt = 0
                                var success = false
                                while (attempt <= MAX_RETRIES_PER_PAGE && !success) {
                                    attempt++
                                    try {
                                        val seriesParsed = stalkerRequestAndParse(finalPortalUrl, macClean, queryParams, activeToken, device)
                                        rawSeries = extractListFromJsField(seriesParsed)
                                        success = true
                                        pagesCompleted.incrementAndGet()
                                    } catch (e: Exception) {
                                        if (attempt > MAX_RETRIES_PER_PAGE) {
                                            pagesFailed.incrementAndGet()
                                            Log.w(TAG, "Failed page $sCatPage for series cat $catId after retries: ${e.message}")
                                        } else {
                                            delay(150)
                                        }
                                    }
                                }

                                if (rawSeries.isEmpty()) {
                                    sCatHasMore = false
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

                                    if (newCount == 0) {
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
                val testParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$strategy&p=1$pageSizeParam", activeToken, device)
                val testList = extractListFromJsField(testParsed)
                if (testList.isNotEmpty()) {
                    chosenGlobalPrefix = strategy
                    break
                }
            }

            var sPage = 1
            var sHasMore = true
            while (sHasMore && sPage <= 1500) {
                currentCoroutineContext().ensureActive()
                pagesRequested.incrementAndGet()
                var rawSeries = emptyList<Any>()
                try {
                    val seriesParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "$chosenGlobalPrefix&p=$sPage$pageSizeParam", activeToken, device)
                    rawSeries = extractListFromJsField(seriesParsed)
                    pagesCompleted.incrementAndGet()
                } catch (e: Exception) {
                    pagesFailed.incrementAndGet()
                }

                if (rawSeries.isEmpty()) {
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
            val isOnlyEpNum = cleanTitleStr == epNum || cleanTitleStr == String.format("%02d", epNum.toIntOrNull() ?: -1)
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
