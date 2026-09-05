package com.example.motor.parser

import android.util.Log
import com.example.motor.db.IptvItem
import com.example.motor.db.EpgChannelData
import com.example.motor.db.EpgProgramme
import com.example.motor.config.StalkerDevice
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.util.ArrayList
import java.util.HashMap
import kotlin.random.Random

object StalkerParser {
    private const val TAG = "StalkerParser"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

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
        val fakeTimestamp = "1500000000"
        val deviceId = "$macClean:$serialCleanLast10:$model:$fakeTimestamp"
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

    private fun URLParamEncode(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            s.replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
        }
    }

    private fun stalkerRequest(portalUrl: String, mac: String, params: String, token: String?, device: StalkerDevice): String {
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
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        if (finalToken.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $finalToken")
        }

        try {
            BaseEngine.client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    return response.body?.string() ?: ""
                }
                throw NetworkError.ServerError(response.code, "El portal Stalker devolvió un error")
            }
        } catch (e: java.io.IOException) {
            val msg = e.message?.lowercase() ?: ""
            if (currentUrl.startsWith("https://", ignoreCase = true) &&
                (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                val fallbackUrl = "http" + currentUrl.substring(5)
                Log.w(TAG, "TLS/SSL error on stalkerRequest for $currentUrl, falling back to $fallbackUrl")
                return stalkerRequest(fallbackUrl, mac, params, token, device)
            }
            throw e
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (currentUrl.startsWith("https://", ignoreCase = true) &&
                (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                val fallbackUrl = "http" + currentUrl.substring(5)
                Log.w(TAG, "TLS/SSL error on stalkerRequest for $currentUrl, falling back to $fallbackUrl")
                return stalkerRequest(fallbackUrl, mac, params, token, device)
            }
            throw e
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing stalker response: $trimmed", e)
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
        device: StalkerDevice
    ): Map<String, Any> {
        val macClean = mac.trim().uppercase()
        if (macClean.isEmpty()) throw NetworkError.Auth("Falta la dirección MAC")

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

        var apiSignature = ""

        var baseHost = host.trim()
        if (baseHost.startsWith("https://", ignoreCase = true) && baseHost.contains(":80")) {
            baseHost = "http" + baseHost.substring(5)
        }
        if (!baseHost.startsWith("http://", ignoreCase = true) && !baseHost.startsWith("https://", ignoreCase = true)) {
            baseHost = "http://$baseHost"
        }
        baseHost = baseHost.removeSuffix("/")

        val candidates = mutableListOf<String>()
        if (baseHost.contains(".php") || baseHost.contains("/c/") || baseHost.endsWith("/portal") || baseHost.contains("?")) {
            candidates.add(baseHost)
        }
        candidates.addAll(listOf(
            "$baseHost/portal.php",
            "$baseHost/stalker_portal/server/load.php",
            "$baseHost/server/load.php",
            "$baseHost/c/portal.php"
        ))

        var portalUrl: String? = null
        var token: String? = null
        var lastError: Exception? = null

        for (cand in candidates) {
            try {
                var currentCand = cand
                if (currentCand.startsWith("https://", ignoreCase = true) && currentCand.contains(":80")) {
                    currentCand = "http" + currentCand.substring(5)
                }

                val handshakeParamsCommon = "&device_id=${URLParamEncode(deviceId)}" +
                        "&device_id2=${URLParamEncode(deviceId2)}" +
                        "&signature=${URLParamEncode(signature)}" +
                        "&hw_version=${URLParamEncode(device.hwVersion)}" +
                        "&hw_version_2=${URLParamEncode(device.hwVersion2)}" +
                        "&stb_type=${URLParamEncode(device.hwVersion)}" +
                        "&vendor=${URLParamEncode(device.vendor)}" +
                        "&image_version=${URLParamEncode(device.imageVersion)}" +
                        "&sn=${URLParamEncode(snVal)}"

                val handshakes = listOf(
                    "type=stb&action=handshake&token=&prehash=0$handshakeParamsCommon",
                    "type=stb&action=create_token&token=&prehash=0$handshakeParamsCommon"
                )
                for (hParams in handshakes) {
                    var resp: String
                    try {
                        resp = stalkerRequest(currentCand, macClean, hParams, null, device)
                    } catch (e: Exception) {
                        val msg = e.message?.lowercase() ?: ""
                        if (currentCand.startsWith("https://", ignoreCase = true) &&
                            (e is javax.net.ssl.SSLException || msg.contains("tls") || msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake") || msg.contains("unrecognized ssl message"))) {
                            val fallbackCand = "http" + currentCand.substring(5)
                            Log.w(TAG, "TLS/SSL error on stalker handshake for $currentCand, trying $fallbackCand")
                            resp = stalkerRequest(fallbackCand, macClean, hParams, null, device)
                            currentCand = fallbackCand
                        } else {
                            throw e
                        }
                    }

                    val parsed = parseStalkerJsResponse(resp)
                    val js = parsed["js"] as? Map<*, *>
                    val t = js?.get("token")?.toString()
                    if (!t.isNullOrEmpty()) {
                        token = t
                        portalUrl = currentCand
                        val rand = js["random"]?.toString()
                        if (!rand.isNullOrEmpty()) {
                            apiSignature = generateApiSignature(t, rand, macClean)
                        }
                        break
                    }
                }
                if (portalUrl != null) break
            } catch (e: Exception) {
                lastError = e
            }
        }

        val finalPortalUrl = portalUrl ?: throw BaseEngine.classifyException(lastError ?: NetworkError.ServerDown("No se pudo contactar con el portal Stalker"))
        val finalToken = token ?: ""

        val metrics = "{\"mac\":\"$macClean\",\"model\":\"${device.hwVersion}\",\"vendor\":\"${device.vendor}\"}"
        val apiSigValue = apiSignature.ifEmpty { "262" }
        val profileParams = "type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200%20version&num_banks=2" +
                "&stb_type=${URLParamEncode(device.hwVersion)}&image_version=${URLParamEncode(device.imageVersion)}" +
                "&auth_second_step=0&hw_version=${URLParamEncode(device.hwVersion)}&hw_version_2=${URLParamEncode(device.hwVersion2)}&not_valid_token=0" +
                "&device_id=${URLParamEncode(deviceId)}&device_id2=${URLParamEncode(deviceId2)}&signature=${URLParamEncode(signature)}&sn=${URLParamEncode(snVal)}" +
                "&metrics=${URLParamEncode(metrics)}&api_signature=${URLParamEncode(apiSigValue)}"

        val profResp = stalkerRequest(finalPortalUrl, macClean, profileParams, finalToken, device)
        val profParsed = parseStalkerJsResponse(profResp)
        val jsProf = profParsed["js"] as? Map<*, *>

        if (jsProf?.get("block_msg") != null) {
            throw NetworkError.Auth("MAC bloqueada por el portal: " + jsProf["block_msg"].toString())
        }

        val activeToken = jsProf?.get("token")?.toString()?.ifEmpty { finalToken } ?: finalToken

        return mapOf(
            "portalUrl" to finalPortalUrl,
            "mac" to macClean,
            "token" to activeToken,
            "device" to device
        )
    }

    suspend fun fetchStalkerCatalog(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        onSessionReady: ((Map<String, Any>) -> Unit)? = null
    ): Map<String, Any> {
        currentCoroutineContext().ensureActive()
        onProgress("Handshake con el portal Stalker...")
        val sessionMap = performStalkerHandshake(host, mac, device)
        currentCoroutineContext().ensureActive()
        val finalPortalUrl = sessionMap["portalUrl"] as String
        val macClean = sessionMap["mac"] as String
        val activeToken = sessionMap["token"] as String
        onSessionReady?.invoke(sessionMap)

        onProgress("Obteniendo categorías...")
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

        val chAttempts = listOf(
            "type=itv&action=get_all_channels",
            "type=itv&action=get_channels",
            "type=itv&action=get_short_channels",
            "type=itv&action=get_ordered_list&genre=0",
            "type=itv&action=get_ordered_list&genre=all",
            "type=itv&action=get_ordered_list&genre=*",
            "type=itv&action=get_ordered_list&genre_id=0",
            "type=itv&action=get_ordered_list"
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
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching ITV channels with params: $params", e)
            }
        }

        if (rawChannels.isEmpty() && genreMap.isNotEmpty()) {
            for (genreId in genreMap.keys) {
                currentCoroutineContext().ensureActive()
                try {
                    val chParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=itv&action=get_ordered_list&genre=$genreId&p=1", activeToken, device)
                    val list = extractListFromJsField(chParsed)
                    list.forEach { c ->
                        val m = c as? Map<*, *> ?: return@forEach
                        val id = m["id"]?.toString() ?: m["number"]?.toString() ?: m["name"]?.toString() ?: ""
                        val cmd = m["cmd"]?.toString() ?: m["url"]?.toString() ?: ""
                        val key = "$id|$cmd"
                        if (seenChKeys.add(key)) {
                            rawChannels.add(c)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching channels for genre $genreId", e)
                }
            }
        }

        if (rawChannels.isEmpty()) {
            try {
                var chPage = 1
                var chHasMore = true
                while (chHasMore && chPage <= 1000) {
                    currentCoroutineContext().ensureActive()
                    val chParsed = stalkerRequestAndParse(finalPortalUrl, macClean, "type=itv&action=get_ordered_list&genre=0&p=$chPage", activeToken, device)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching paginated ITV channels", e)
            }
        }

        val channels = ArrayList<IptvItem>()
        rawChannels.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val genreId = m["tv_genre_id"]?.toString()
                ?: m["category_id"]?.toString()
                ?: m["genre_id"]?.toString()
                ?: ""
            var cmd = m["cmd"]?.toString()
                ?: m["url"]?.toString()
                ?: m["uri"]?.toString()
                ?: m["path"]?.toString()
                ?: ""
            val name = m["name"]?.toString()
                ?: m["title"]?.toString()
                ?: m["caption"]?.toString()
                ?: "Sin nombre"
            val idStr = m["id"]?.toString() ?: m["number"]?.toString() ?: ""
            if (cmd.isEmpty() && idStr.isNotEmpty()) {
                cmd = "ffrt http://localhost/ch/$idStr"
            }

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

        onProgress("Descargando VOD...")
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

        val vodCatMap = HashMap<String, String>()
        rawVodCats.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString() ?: m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: ""
            val title = m["title"]?.toString() ?: m["name"]?.toString() ?: m["category_name"]?.toString() ?: ""
            if (id.isNotEmpty() && title.isNotEmpty() && id != "*" && id != "all" && id != "0") {
                vodCatMap[id] = title
            }
        }

        val movies = ArrayList<IptvItem>()
        try {
            val seenMovies = HashSet<String>()

            // 1. Descarga por categorías completas
            if (vodCatMap.isNotEmpty()) {
                for ((catId, catName) in vodCatMap) {
                    var catPage = 1
                    var catHasMore = true
                    while (catHasMore && catPage <= 1000) {
                        currentCoroutineContext().ensureActive()
                        val paramsToTry = listOf(
                            "type=vod&action=get_ordered_list&category=$catId&p=$catPage",
                            "type=vod&action=get_ordered_list&genre=$catId&p=$catPage"
                        )
                        var rawMovies = emptyList<Any>()
                        for (param in paramsToTry) {
                            try {
                                val vodParsed = stalkerRequestAndParse(finalPortalUrl, macClean, param, activeToken, device)
                                val list = extractListFromJsField(vodParsed)
                                if (list.isNotEmpty()) {
                                    rawMovies = list
                                    break
                                }
                            } catch (e: Exception) {
                                // continue
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
                                val key = "$id|$name|$cmd"
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
                                onProgress("Descargando VOD (${movies.size} películas)...")
                                onPartialUpdate?.invoke("movie", ArrayList(movies))
                            }
                        }
                    }
                }
            }

            // 2. Paginación global para abarcar todo el catálogo o elementos sin categoría
            var mPage = 1
            var mHasMore = true
            while (mHasMore && mPage <= 1500) {
                currentCoroutineContext().ensureActive()
                val paramsToTry = listOf(
                    "type=vod&action=get_ordered_list&p=$mPage",
                    "type=vod&action=get_ordered_list&category=0&p=$mPage",
                    "type=vod&action=get_ordered_list&genre=0&p=$mPage",
                    "type=vod&action=get_ordered_list&category=*&p=$mPage",
                    "type=vod&action=get_ordered_list&genre=*&p=$mPage"
                )
                var rawMovies = emptyList<Any>()
                for (param in paramsToTry) {
                    try {
                        val vodParsed = stalkerRequestAndParse(finalPortalUrl, macClean, param, activeToken, device)
                        val list = extractListFromJsField(vodParsed)
                        if (list.isNotEmpty()) {
                            rawMovies = list
                            break
                        }
                    } catch (e: Exception) {
                        // continue
                    }
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
                        val key = "$id|$name|$cmd"
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
            onPartialUpdate?.invoke("movie", ArrayList(movies))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker VOD", e)
        }

        onProgress("Descargando series...")
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

        val seriesCatMap = HashMap<String, String>()
        rawSeriesCats.forEach { c ->
            val m = c as? Map<*, *> ?: return@forEach
            val id = m["id"]?.toString() ?: m["category_id"]?.toString() ?: m["genre_id"]?.toString() ?: ""
            val title = m["title"]?.toString() ?: m["name"]?.toString() ?: m["category_name"]?.toString() ?: ""
            if (id.isNotEmpty() && title.isNotEmpty() && id != "*" && id != "all" && id != "0") {
                seriesCatMap[id] = title
            }
        }

        val series = ArrayList<IptvItem>()
        try {
            val seenSeries = HashSet<String>()

            // 1. Descarga por categorías completas
            if (seriesCatMap.isNotEmpty()) {
                for ((catId, catName) in seriesCatMap) {
                    var sCatPage = 1
                    var sCatHasMore = true
                    while (sCatHasMore && sCatPage <= 1000) {
                        currentCoroutineContext().ensureActive()
                        val paramsToTry = listOf(
                            "type=series&action=get_ordered_list&category=$catId&p=$sCatPage",
                            "type=vod&action=get_ordered_list&movie_type=series&category=$catId&p=$sCatPage",
                            "type=series&action=get_ordered_list&genre=$catId&p=$sCatPage",
                            "type=vod&action=get_ordered_list&movie_type=series&genre=$catId&p=$sCatPage"
                        )
                        var rawSeries = emptyList<Any>()
                        for (param in paramsToTry) {
                            try {
                                val seriesParsed = stalkerRequestAndParse(finalPortalUrl, macClean, param, activeToken, device)
                                val list = extractListFromJsField(seriesParsed)
                                if (list.isNotEmpty()) {
                                    rawSeries = list
                                    break
                                }
                            } catch (e: Exception) {
                                // continue
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
                                val key = "$cleanId|$name|$cmd"
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
                                onProgress("Descargando series (${series.size} series)...")
                                onPartialUpdate?.invoke("series", ArrayList(series))
                            }
                        }
                    }
                }
            }

            // 2. Paginación global para abarcar todo el catálogo o elementos sin categoría
            var sPage = 1
            var sHasMore = true
            while (sHasMore && sPage <= 1500) {
                currentCoroutineContext().ensureActive()
                val paramsToTry = listOf(
                    "type=series&action=get_ordered_list&p=$sPage",
                    "type=vod&action=get_ordered_list&movie_type=series&p=$sPage",
                    "type=series&action=get_ordered_list&category=0&p=$sPage",
                    "type=series&action=get_ordered_list&genre=0&p=$sPage",
                    "type=series&action=get_ordered_list&category=*&p=$sPage",
                    "type=series&action=get_ordered_list&genre=*&p=$sPage"
                )
                var rawSeries = emptyList<Any>()
                for (param in paramsToTry) {
                    try {
                        val seriesParsed = stalkerRequestAndParse(finalPortalUrl, macClean, param, activeToken, device)
                        val list = extractListFromJsField(seriesParsed)
                        if (list.isNotEmpty()) {
                            rawSeries = list
                            break
                        }
                    } catch (e: Exception) {
                        // continue
                    }
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
                        val key = "$cleanId|$name|$cmd"
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
            onPartialUpdate?.invoke("series", ArrayList(series))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker Series", e)
        }

        val activeSessionMap = sessionMap.toMutableMap()
        activeSessionMap["vodCatMap"] = vodCatMap
        activeSessionMap["seriesCatMap"] = seriesCatMap

        return mapOf(
            "tv" to channels,
            "movie" to movies,
            "series" to series,
            "stalkerSession" to activeSessionMap
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
                            "id" to "$seriesId:$finalSNum:$str",
                            "name" to "Episodio $str",
                            "season_num" to finalSNum,
                            "episode_num" to str,
                            "cmd" to "$seriesId:$finalSNum:$str"
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
                                    "cmd" to "$seriesId:$sNumMatch:$epNumMatch"
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
                                "cmd" to "$seriesId:$sNumMatch:$ep"
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
        device: StalkerDevice
    ): List<EpgChannelData> {
        val macClean = mac.trim().uppercase()
        val cleanSeriesId = seriesId.trim()
        
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
                    val resp = stalkerRequest(portalUrl, macClean, params, token, device)
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
                                                rawEpisodes.add(mapOf(
                                                    "id" to "$candidate:$itemSNum:$epStr",
                                                    "name" to "Episodio $epStr",
                                                    "season_num" to itemSNum,
                                                    "episode_num" to epStr,
                                                    "cmd" to "$candidate:$itemSNum:$epStr"
                                                ))
                                            }
                                        } else {
                                            sVal.forEachIndexed { sIdx, sElem ->
                                                if (sElem is List<*> && sElem.isNotEmpty()) {
                                                    val sNum = (sIdx + 1).toString()
                                                    sElem.forEach { epElem ->
                                                        val epStr = cleanNumberString(epElem)
                                                        rawEpisodes.add(mapOf(
                                                            "id" to "$candidate:$sNum:$epStr",
                                                            "name" to "Episodio $epStr",
                                                            "season_num" to sNum,
                                                            "episode_num" to epStr,
                                                            "cmd" to "$candidate:$sNum:$epStr"
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
                                                            rawEpisodes.add(mapOf(
                                                                "id" to "$candidate:$sNum:$epStr",
                                                                "name" to "Episodio $epStr",
                                                                "season_num" to sNum,
                                                                "episode_num" to epStr,
                                                                "cmd" to "$candidate:$sNum:$epStr"
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
                                            "id" to "$candidate:$sNum:$epStr",
                                            "name" to "Episodio $epStr",
                                            "season_num" to sNum,
                                            "episode_num" to epStr,
                                            "cmd" to "$candidate:$sNum:$epStr"
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
                                "id" to "$cleanSeriesId:$sNum:$epStr",
                                "name" to "Episodio $epStr",
                                "season_num" to sNum,
                                "episode_num" to epStr,
                                "cmd" to "$cleanSeriesId:$sNum:$epStr"
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
                        val resp = stalkerRequest(portalUrl, macClean, sParams, token, device)
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
                                        "id" to "$cleanSeriesId:$sNum:$epStr",
                                        "name" to "Episodio $epStr",
                                        "season_num" to sNum,
                                        "episode_num" to epStr,
                                        "cmd" to "$cleanSeriesId:$sNum:$epStr"
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
                                            "id" to "$cleanSeriesId:$sNum:$epStr",
                                            "name" to "Episodio $epStr",
                                            "season_num" to sNum,
                                            "episode_num" to epStr,
                                            "cmd" to "$cleanSeriesId:$sNum:$epStr"
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

                // Si no se encontró respuesta específica para la temporada, agregar como fallback episodio 1
                if (!seasonEpisodesFound) {
                    rawEpisodes.add(mapOf(
                        "id" to "$cleanSeriesId:$sNum:1",
                        "name" to "Episodio 1",
                        "season_num" to sNum,
                        "episode_num" to "1",
                        "cmd" to "$cleanSeriesId:$sNum:1"
                    ))
                }
            }
        }

        val episodesBySeason = HashMap<String, ArrayList<EpgProgramme>>()

        rawEpisodes.forEachIndexed { idx, epMap ->
            val id = epMap["id"]?.toString() ?: ""
            val cmd = epMap["cmd"]?.toString()
                ?: epMap["url"]?.toString()
                ?: epMap["uri"]?.toString()
                ?: epMap["link"]?.toString()
                ?: epMap["file"]?.toString()
                ?: epMap["path"]?.toString()
                ?: ""

            val finalCmd = cmd.ifEmpty { id }.ifEmpty { cleanSeriesId }
            if (finalCmd.isEmpty()) return@forEachIndexed

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

            val list = episodesBySeason.getOrPut(seasonNum) { ArrayList() }
            list.add(EpgProgramme(
                start = 0,
                stop = 0,
                title = title,
                desc = desc,
                category = finalCmd,
                rating = epNum
            ))
        }

        val outSeasons = ArrayList<EpgChannelData>()
        episodesBySeason.forEach { (seasonNum, eps) ->
            // Evitar duplicados de episodios con el mismo rating/epNum y título dentro de una temporada
            val uniqueEps = ArrayList<EpgProgramme>()
            val seenKeys = HashSet<String>()
            eps.forEach { ep ->
                val key = "${ep.rating}|${ep.category}"
                if (seenKeys.add(key)) {
                    uniqueEps.add(ep)
                }
            }
            outSeasons.add(EpgChannelData(
                id = seasonNum,
                name = "Temporada $seasonNum",
                programmes = uniqueEps.sortedWith(compareBy { it.rating.toIntOrNull() ?: 0 })
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

    suspend fun resolveStalkerPlayLink(session: Map<String, Any>, cmd: String, itemType: String? = null): String {
        val portalUrl = session["portalUrl"] as String
        val mac = session["mac"] as String
        val token = session["token"] as String
        val device = session["device"] as StalkerDevice

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

        var extractedNum: String? = null
        if (cmd.contains("localhost/")) {
            val relative = cmd.substringAfter("localhost/").removePrefix("ch/").removeSuffix("_")
            cmdsToTry.add("http://localhost/ch/$relative")
            cmdsToTry.add("ffrt http://localhost/ch/$relative")
            cmdsToTry.add("ch/$relative")
            cmdsToTry.add(relative)
            cmdsToTry.add("${relative}_")
            extractedNum = relative
        } else {
            val matchNum = Regex("(\\d+)").find(cmd)
            if (matchNum != null) {
                extractedNum = matchNum.value
            }
        }

        if (extractedNum != null && extractedNum.isNotEmpty()) {
            cmdsToTry.add(extractedNum)
            cmdsToTry.add("http://localhost/ch/$extractedNum")
            cmdsToTry.add("${extractedNum}_")
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
                } catch (e: Exception) {
                    lastError = e
                }
            }
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

        val cleanCmd = cmd.removePrefix("ffrt ").removePrefix("ffmpeg ").removePrefix("auto ").trim()
        if (cleanCmd.startsWith("ch/") || cleanCmd.startsWith("/ch/") ||
            cleanCmd.startsWith("live/") || cleanCmd.startsWith("/live/") ||
            cleanCmd.startsWith("vod/") || cleanCmd.startsWith("/vod/") ||
            cleanCmd.startsWith("media/") || cleanCmd.startsWith("/media/") ||
            cleanCmd.startsWith("series/") || cleanCmd.startsWith("/series/")) {
            val path = if (cleanCmd.startsWith("/")) cleanCmd else "/$cleanCmd"
            return "$portalHostBase$path"
        }
        if (isTv && extractedNum != null && extractedNum.isNotEmpty()) {
            return "$portalHostBase/ch/$extractedNum"
        }

        throw NetworkError.ServerDown(lastError?.message ?: "No se pudo obtener el enlace de reproducción")
    }

    private fun performResolveLink(portalUrl: String, mac: String, cmd: String, token: String, device: StalkerDevice, type: String): String {
        val parsedUri = try { java.net.URI(portalUrl) } catch (e: Exception) { null }
        val portalHostBase = if (parsedUri != null) {
            val scheme = parsedUri.scheme ?: "http"
            val host = parsedUri.host ?: ""
            val port = if (parsedUri.port != -1) ":${parsedUri.port}" else ""
            "$scheme://$host$port"
        } else {
            portalUrl.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")
        }

        fun normalizeResultUrl(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null

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
                val resp = stalkerRequest(portalUrl, mac, params, token, device)
                Log.d(TAG, "performResolveLink response for $type with params $params: $resp")

                val parsed = parseStalkerJsResponse(resp)
                val jsObj = parsed["js"]

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
}
