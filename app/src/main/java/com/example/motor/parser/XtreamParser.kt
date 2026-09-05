package com.example.motor.parser

import android.util.Log
import com.example.motor.db.IptvItem
import com.example.motor.db.EpgChannelData
import com.example.motor.db.EpgProgramme
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URL
import java.util.ArrayList
import java.util.HashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class XtreamCredentials(
    val host: String,
    val port: String,
    val user: String,
    val pass: String
)

object XtreamParser {
    private const val TAG = "XtreamParser"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun parseXtreamCredentialsDirect(rawInput: String): XtreamCredentials? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        try {
            // First check if it contains query parameters (e.g. ?username=xxx&password=yyy or ?user=xxx&pass=yyy)
            if (trimmed.contains("username=", ignoreCase = true) || trimmed.contains("user=", ignoreCase = true) ||
                trimmed.contains("password=", ignoreCase = true) || trimmed.contains("pass=", ignoreCase = true)) {
                val parsedUri = java.net.URI(if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) "http://$trimmed" else trimmed)
                val query = parsedUri.query ?: ""
                val queryParams = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0].lowercase() to java.net.URLDecoder.decode(parts[1], "UTF-8") else "" to ""
                }
                val u = queryParams["username"] ?: queryParams["user"] ?: queryParams["usr"] ?: queryParams["u"]
                val p = queryParams["password"] ?: queryParams["pass"] ?: queryParams["pwd"] ?: queryParams["p"]

                if (!u.isNullOrEmpty() && !p.isNullOrEmpty()) {
                    val scheme = if (trimmed.startsWith("https://", true)) "https" else "http"
                    val hostStr = parsedUri.host ?: ""
                    val portVal = if (parsedUri.port != -1) parsedUri.port.toString() else "80"
                    val hostUrl = if (hostStr.isNotEmpty()) "$scheme://$hostStr" else trimmed.substringBefore("?")
                    return XtreamCredentials(host = hostUrl, port = portVal, user = u, pass = p)
                }
            }

            // Next check if path matches /live/{user}/{pass}/... or /movie/{user}/{pass}/... or /series/{user}/{pass}/... or /{user}/{pass}.m3u8
            val pathRegex1 = Regex("(?i)^(https?://[^/:]+)(?::(\\d+))?/(?:live|movie|series|play|stream)/([^/\\s:]+)/([^/\\s:.]+)(?:/.*)?$")
            val match1 = pathRegex1.find(trimmed)
            if (match1 != null) {
                val host = match1.groupValues[1]
                val port = match1.groupValues[2].ifEmpty { "80" }
                val user = match1.groupValues[3]
                val pass = match1.groupValues[4]
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    return XtreamCredentials(host = host, port = port, user = user, pass = pass)
                }
            }

            // Path matches /{user}/{pass} or /{user}/{pass}.m3u or /{user}/{pass}.m3u8 or /{user}/{pass}/m3u
            val pathRegex2 = Regex("(?i)^(https?://[^/:]+)(?::(\\d+))?/([^/\\s:?]+)/([^/\\s:?.]+)(?:\\.(?:m3u|m3u8)|/(?:m3u|m3u8))?$")
            val match2 = pathRegex2.find(trimmed)
            if (match2 != null) {
                val host = match2.groupValues[1]
                val port = match2.groupValues[2].ifEmpty { "80" }
                val user = match2.groupValues[3]
                val pass = match2.groupValues[4]
                val nonUserWords = setOf("get.php", "player_api.php", "xmltv.php", "client_api.php", "panel_api.php", "c", "stalker_portal")
                if (user.isNotEmpty() && pass.isNotEmpty() && !nonUserWords.contains(user.lowercase()) && !nonUserWords.contains(pass.lowercase())) {
                    return XtreamCredentials(host = host, port = port, user = user, pass = pass)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in direct credentials parsing: ${e.message}")
        }
        return null
    }

    suspend fun resolveXtreamCredentials(rawInput: String): XtreamCredentials? = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return@withContext null

        // 1. Direct regex/URL inspection
        parseXtreamCredentialsDirect(trimmed)?.let { return@withContext it }

        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            return@withContext null
        }

        // 2. Follow redirects via OkHttp (for shorteners like bit.ly, tinyurl, or custom short links)
        try {
            val req = okhttp3.Request.Builder()
                .url(trimmed)
                .header("User-Agent", "IPTVSmartersPro/3.1.5 (Linux; Android 11)")
                .build()

            val client = BaseEngine.client.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                parseXtreamCredentialsDirect(finalUrl)?.let { return@use it }

                // Check prior responses / redirect Location headers
                var prior = resp.priorResponse
                while (prior != null) {
                    val loc = prior.header("Location")
                    if (!loc.isNullOrEmpty()) {
                        parseXtreamCredentialsDirect(loc)?.let { return@use it }
                    }
                    prior = prior.priorResponse
                }

                // If it downloaded M3U content, inspect the first stream lines to extract user & pass!
                val body = resp.body
                if (body != null) {
                    val reader = body.charStream().buffered()
                    var linesChecked = 0
                    while (linesChecked < 100) {
                        val line = reader.readLine() ?: break
                        linesChecked++
                        val trimmedLine = line.trim()
                        if (trimmedLine.startsWith("http://", true) || trimmedLine.startsWith("https://", true)) {
                            parseXtreamCredentialsDirect(trimmedLine)?.let { return@use it }
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving short link or M3U stream lines: ${e.message}")
            null
        }
    }

    fun xtreamBaseUrl(host: String, port: String): String {
        var raw = host.trim()
        if (raw.isEmpty()) return ""
        if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
            raw = "http://$raw"
        }
        return try {
            val u = URL(raw)
            val scheme = u.protocol
            val hostPart = u.host
            val finalPort = if (u.port != -1) u.port.toString() else port.trim().ifEmpty { "" }
            val portSuffix = if (finalPort.isNotEmpty()) ":$finalPort" else ""
            var path = u.path.trim().removeSuffix("/")
            
            val lowerPath = path.lowercase()
            if (lowerPath.endsWith("player_api.php") || lowerPath.endsWith("client_api.php") ||
                lowerPath.endsWith("get.php") || lowerPath.endsWith("xmltv.php") || lowerPath.endsWith("panel_api.php")) {
                path = path.substringBeforeLast("/").removeSuffix("/")
            }

            if (path.isNotEmpty() && path != "/") {
                "$scheme://$hostPart$portSuffix$path"
            } else {
                "$scheme://$hostPart$portSuffix"
            }
        } catch (e: Exception) {
            raw.substringBefore("?").removeSuffix("/").removeSuffix("/get.php").removeSuffix("/player_api.php")
        }
    }

    private fun getCustomQueryParams(host: String): String {
        return try {
            val u = URL(if (!host.startsWith("http") && !host.startsWith("https")) "http://$host" else host)
            u.query ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun xtreamApiUrl(host: String, port: String, user: String, pass: String, action: String? = null): String {
        val base = xtreamBaseUrl(host, port)
        val customQueryParams = getCustomQueryParams(host)

        val auth = "username=${URLParamEncode(user)}&password=${URLParamEncode(pass)}"
        var finalUrl = "$base/player_api.php?$auth"
        if (action != null) {
            finalUrl += "&action=$action"
        }
        if (customQueryParams.isNotEmpty()) {
            val filtered = customQueryParams.split("&").filterNot { 
                it.startsWith("username=", ignoreCase = true) || 
                it.startsWith("password=", ignoreCase = true) || 
                it.startsWith("type=", ignoreCase = true) || 
                it.startsWith("output=", ignoreCase = true)
            }.joinToString("&")
            if (filtered.isNotEmpty()) {
                finalUrl += "&$filtered"
            }
        }
        return finalUrl
    }

    private fun URLParamEncode(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            s.replace("&", "%26").replace("?", "%3F").replace("=", "%3D")
        }
    }

    suspend fun checkXtreamAuth(host: String, port: String, user: String, pass: String): Map<String, Any?> {
        val url = xtreamApiUrl(host, port, user, pass)
        val json = BaseEngine.executeGetRequest(url, timeoutSeconds = 30)
        
        if (json.trim().startsWith("#EXTM3U") || json.trim().startsWith("#EXTINF")) {
            throw Exception("El servidor devolvió una lista M3U en lugar de la API Xtream JSON")
        }

        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val adapter = moshi.adapter<Map<String, Any?>>(type)
        val map = try {
            adapter.fromJson(json)
        } catch (e: Exception) {
            throw Exception("Respuesta no es JSON válido de API Xtream")
        } ?: throw Exception("Respuesta vacía del panel Xtream")
        
        val userInfo = map["user_info"] as? Map<*, *>
        if (userInfo == null) {
            throw Exception("El panel no devolvió información de la cuenta Xtream")
        }
        if (userInfo["auth"].toString() != "1" && userInfo["auth"].toString() != "1.0") {
            throw NetworkError.Auth("Usuario o contraseña incorrectos")
        }
        if (userInfo["status"]?.toString() == "Expired") {
            throw NetworkError.Auth("Tu cuenta ha expirado")
        }
        return map
    }

    private fun cleanJsonId(value: Any?): String {
        if (value == null) return ""
        if (value is Double) {
            if (value % 1.0 == 0.0) {
                return value.toLong().toString()
            }
            return value.toString()
        }
        if (value is Float) {
            if (value % 1.0f == 0.0f) {
                return value.toLong().toString()
            }
            return value.toString()
        }
        return value.toString()
    }

    private suspend fun executeStreamedArrayRequest(
        url: String,
        timeoutSeconds: Long = 180,
        onItem: (Map<String, Any?>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val builder = okhttp3.Request.Builder().url(url)
        builder.header("User-Agent", "IPTVSmartersPro/3.1.5 (Linux; Android 11)")
        builder.header("Accept", "*/*")

        val okClient = BaseEngine.client.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            okClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Streamed request HTTP error: ${response.code} for URL: $url")
                    return@use
                }
                val body = response.body ?: return@use
                val source = body.source()
                val reader = com.squareup.moshi.JsonReader.of(source)

                val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                val adapter = moshi.adapter<Map<String, Any?>>(mapType)

                try {
                    if (reader.peek() == com.squareup.moshi.JsonReader.Token.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            try {
                                val item = adapter.fromJson(reader)
                                if (item != null) {
                                    onItem(item)
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "Skipping malformed item in JSON array: ${e.message}")
                            }
                        }
                        reader.endArray()
                    } else if (reader.peek() == com.squareup.moshi.JsonReader.Token.BEGIN_OBJECT) {
                        val item = adapter.fromJson(reader)
                        if (item != null) onItem(item)
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Error streaming JSON reader for URL $url: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Network error executing streamed request for URL $url: ${e.message}")
        }
    }

    suspend fun fetchXtreamCatalog(host: String, port: String, user: String, pass: String, onProgress: (String) -> Unit): Map<String, List<IptvItem>> {
        onProgress("Verificando cuenta...")
        val authData = checkXtreamAuth(host, port, user, pass)
        
        onProgress("Obteniendo categorías...")
        val liveCatsUrl = xtreamApiUrl(host, port, user, pass, "get_live_categories")
        val vodCatsUrl = xtreamApiUrl(host, port, user, pass, "get_vod_categories")
        val serCatsUrl = xtreamApiUrl(host, port, user, pass, "get_series_categories")

        val catMapType = Types.newParameterizedType(List::class.java, Map::class.java)
        val catAdapter = moshi.adapter<List<Map<String, Any?>>>(catMapType)

        val liveCatMap = HashMap<String, String>()
        try {
            val liveCatsJson = BaseEngine.executeGetRequest(liveCatsUrl, timeoutSeconds = 30)
            catAdapter.fromJson(liveCatsJson)?.forEach { c ->
                val catId = cleanJsonId(c["category_id"])
                liveCatMap[catId] = c["category_name"]?.toString() ?: "General"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error fetching live cats", e)
        }

        val vodCatMap = HashMap<String, String>()
        try {
            val vodCatsJson = BaseEngine.executeGetRequest(vodCatsUrl, timeoutSeconds = 30)
            catAdapter.fromJson(vodCatsJson)?.forEach { c ->
                val catId = cleanJsonId(c["category_id"])
                vodCatMap[catId] = c["category_name"]?.toString() ?: "General"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error fetching vod cats", e)
        }

        val serCatMap = HashMap<String, String>()
        try {
            val serCatsJson = BaseEngine.executeGetRequest(serCatsUrl, timeoutSeconds = 30)
            catAdapter.fromJson(serCatsJson)?.forEach { c ->
                val catId = cleanJsonId(c["category_id"] ?: c["id"])
                val catName = (c["category_name"] ?: c["name"] ?: c["category"])?.toString()?.trim() ?: "General"
                if (catId.isNotEmpty()) {
                    serCatMap[catId] = catName
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error fetching series cats", e)
        }

        onProgress("Descargando canales TV...")
        val liveStreamsUrl = xtreamApiUrl(host, port, user, pass, "get_live_streams")
        val channels = ArrayList<IptvItem>()
        val customParams = getCustomQueryParams(host)
        val suffix = if (customParams.isNotEmpty()) {
            val filtered = customParams.split("&").filterNot { 
                it.startsWith("username=", ignoreCase = true) || it.startsWith("password=", ignoreCase = true)
            }.joinToString("&")
            if (filtered.isNotEmpty()) "?$filtered" else ""
        } else ""
        
        try {
            executeStreamedArrayRequest(liveStreamsUrl, timeoutSeconds = 120) { s ->
                val id = cleanJsonId(s["stream_id"])
                val group = liveCatMap[cleanJsonId(s["category_id"])] ?: "General"
                channels.add(IptvItem(
                    name = s["name"]?.toString() ?: "Sin nombre",
                    group = group,
                    logo = s["stream_icon"]?.toString(),
                    tvgId = s["epg_channel_id"]?.toString(),
                    tvgLogo = s["stream_icon"]?.toString(),
                    tvgChno = cleanJsonId(s["num"]),
                    catchup = if (s["tv_archive"].toString() == "1" || s["tv_archive"].toString() == "true") "default" else null,
                    catchupDays = s["tv_archive_duration"]?.toString()?.toIntOrNull(),
                    url = "${xtreamBaseUrl(host, port)}/live/${URLParamEncode(user)}/${URLParamEncode(pass)}/$id.ts$suffix"
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching live streams", e) }

        onProgress("Descargando VOD...")
        val vodStreamsUrl = xtreamApiUrl(host, port, user, pass, "get_vod_streams")
        val movies = ArrayList<IptvItem>()
        try {
            executeStreamedArrayRequest(vodStreamsUrl, timeoutSeconds = 180) { s ->
                val id = cleanJsonId(s["stream_id"])
                val ext = s["container_extension"]?.toString() ?: "mp4"
                movies.add(IptvItem(
                    name = s["name"]?.toString() ?: "Sin nombre",
                    group = vodCatMap[cleanJsonId(s["category_id"])] ?: "General",
                    logo = s["stream_icon"]?.toString(),
                    tvgLogo = s["stream_icon"]?.toString(),
                    url = "${xtreamBaseUrl(host, port)}/movie/${URLParamEncode(user)}/${URLParamEncode(pass)}/$id.$ext$suffix",
                    rating = s["rating"]?.toString()?.toFloatOrNull(),
                    year = s["releaseDate"]?.toString()?.take(4)?.toIntOrNull() ?: s["release_date"]?.toString()?.take(4)?.toIntOrNull()
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching vod streams", e) }

        onProgress("Descargando series...")
        val seriesUrl = xtreamApiUrl(host, port, user, pass, "get_series")
        val series = ArrayList<IptvItem>()
        try {
            executeStreamedArrayRequest(seriesUrl, timeoutSeconds = 180) { s ->
                val sId = cleanJsonId(s["series_id"] ?: s["id"])
                val rawCatId = cleanJsonId(s["category_id"] ?: s["category"])
                var group = serCatMap[rawCatId]
                if (group.isNullOrEmpty()) {
                    val catIds = s["category_ids"] as? List<*>
                    if (catIds != null) {
                        for (cid in catIds) {
                            val cStr = cleanJsonId(cid)
                            if (serCatMap.containsKey(cStr)) {
                                group = serCatMap[cStr]
                                break
                            }
                        }
                    }
                }
                if (group.isNullOrEmpty()) {
                    val directName = (s["category_name"] ?: s["genre"])?.toString()?.trim()
                    if (!directName.isNullOrEmpty()) {
                        group = directName
                    }
                }
                if (group.isNullOrEmpty()) {
                    group = "General"
                }

                val cover = s["cover"]?.toString() ?: s["stream_icon"]?.toString() ?: s["poster"]?.toString() ?: s["backdrop_path"]?.toString()
                val name = (s["name"] ?: s["title"])?.toString() ?: "Sin nombre"
                val rating = s["rating"]?.toString()?.toFloatOrNull() ?: s["rating_5based"]?.toString()?.toFloatOrNull()?.let { it * 2 }
                val year = s["releaseDate"]?.toString()?.take(4)?.toIntOrNull() 
                    ?: s["release_date"]?.toString()?.take(4)?.toIntOrNull() 
                    ?: s["year"]?.toString()?.take(4)?.toIntOrNull()

                series.add(IptvItem(
                    name = name,
                    group = group,
                    logo = cover,
                    tvgLogo = cover,
                    seriesId = sId,
                    rating = rating,
                    year = year
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching series", e) }

        return mapOf("tv" to channels, "movie" to movies, "series" to series)
    }

    private fun parseSeasonAndEpisodeFromTitle(name: String): Pair<Int?, Int?> {
        val cleanedName = name.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
        val s0e0Match = Regex("(?i)\\b[ST](\\d+)\\s*[Ee.]?\\s*(\\d+)").find(cleanedName)
        val xMatch = Regex("(?i)\\b(\\d+)x(\\d+)\\b").find(cleanedName)
        if (s0e0Match != null) {
            return Pair(s0e0Match.groupValues[1].toIntOrNull(), s0e0Match.groupValues[2].toIntOrNull())
        } else if (xMatch != null) {
            return Pair(xMatch.groupValues[1].toIntOrNull(), xMatch.groupValues[2].toIntOrNull())
        } else {
            val epMatch = Regex("(?i)(?:capitulo|episodio|episode|ep|cap|e|c)\\s*(\\d+)").find(cleanedName)
            val sMatch = Regex("(?i)(?:temporada|season|temp|t|s)\\s*(\\d+)").find(cleanedName)
            return Pair(sMatch?.groupValues?.get(1)?.toIntOrNull(), epMatch?.groupValues?.get(1)?.toIntOrNull())
        }
    }

    suspend fun fetchXtreamSeriesEpisodes(host: String, port: String, user: String, pass: String, seriesId: String): List<EpgChannelData> {
        val cleanId = seriesId.toDoubleOrNull()?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else seriesId
        } ?: seriesId
        val url = xtreamApiUrl(host, port, user, pass, "get_series_info") + "&series_id=$cleanId"
        val json = BaseEngine.executeGetRequest(url)
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val adapter = moshi.adapter<Map<String, Any?>>(type)
        val map = adapter.fromJson(json) ?: return emptyList()

        val episodesRaw = map["episodes"] ?: return emptyList()
        val outSeasons = ArrayList<EpgChannelData>()

        if (episodesRaw is Map<*, *>) {
            episodesRaw.forEach { (seasonNum, epsList) ->
                val eps = ArrayList<EpgProgramme>()
                val cleanedSeasonNum = cleanJsonId(seasonNum).let { raw ->
                    val digits = Regex("\\d+").find(raw)?.value
                    if (!digits.isNullOrEmpty() && digits != "0") digits else "1"
                }
                val rawEpisodes: Collection<*> = when (epsList) {
                    is List<*> -> epsList
                    is Map<*, *> -> {
                        val m = epsList as Map<*, *>
                        if (m["id"] != null || m["stream_id"] != null || m["title"] != null || m["episode_num"] != null || m["container_extension"] != null) {
                            listOf(m)
                        } else {
                            epsList.values
                        }
                    }
                    else -> return@forEach
                }
                rawEpisodes.forEachIndexed { idx, ep ->
                    val epMap = ep as? Map<*, *> ?: return@forEachIndexed
                    val id = cleanJsonId(epMap["id"] ?: epMap["stream_id"])
                    val ext = epMap["container_extension"]?.toString() ?: "mp4"
                    val customParams = getCustomQueryParams(host)
                    val suffix = if (customParams.isNotEmpty()) "?$customParams" else ""
                    val playUrl = "${xtreamBaseUrl(host, port)}/series/${URLParamEncode(user)}/${URLParamEncode(pass)}/$id.$ext$suffix"
                    
                    val rawEpNum = cleanJsonId(epMap["episode_num"] ?: epMap["episode"] ?: epMap["episode_number"] ?: epMap["episode_no"] ?: epMap["num"])
                    val title = epMap["title"]?.toString() ?: epMap["name"]?.toString() ?: ""
                    
                    val parsed = if (title.isNotEmpty()) parseSeasonAndEpisodeFromTitle(title) else Pair(null, null)
                    var epNum = rawEpNum.ifEmpty { parsed.second?.toString() ?: "" }
                    if (epNum.isEmpty() || epNum == "0") {
                        epNum = (idx + 1).toString()
                    }
                    
                    val finalTitle = run {
                        val cleanTitle = title
                            .replace(Regex("(?i)\\b[ST]\\d+E\\d+\\b"), "")
                            .replace(Regex("(?i)\\b\\d+x\\d+\\b"), "")
                            .replace(Regex("(?i)episodio\\s*\\d+"), "")
                            .replace(Regex("(?i)episode\\s*\\d+"), "")
                            .trim().removePrefix(":").removePrefix("-").trim()
                        if (cleanTitle.isNotEmpty()) "S$cleanedSeasonNum E$epNum: $cleanTitle" else "S$cleanedSeasonNum E$epNum"
                    }
                    
                    eps.add(EpgProgramme(
                        start = 0,
                        stop = 0,
                        title = finalTitle,
                        desc = (epMap["info"] as? Map<*, *>)?.get("plot")?.toString() ?: epMap["plot"]?.toString() ?: "",
                        category = playUrl, // Store stream url here for playback navigation
                        rating = epNum
                    ))
                }
                outSeasons.add(EpgChannelData(
                    id = cleanedSeasonNum,
                    name = "Temporada $cleanedSeasonNum",
                    programmes = eps.sortedBy { it.rating.toIntOrNull() ?: 0 }
                ))
            }
        } else if (episodesRaw is List<*>) {
            val episodesBySeason = HashMap<String, ArrayList<EpgProgramme>>()
            episodesRaw.forEachIndexed { idx, ep ->
                val epMap = ep as? Map<*, *> ?: return@forEachIndexed
                val title = epMap["title"]?.toString() ?: ""
                val rawSeasonNum = cleanJsonId(epMap["season"] ?: epMap["season_num"] ?: epMap["season_number"] ?: epMap["season_no"])
                
                val parsed = if (title.isNotEmpty()) parseSeasonAndEpisodeFromTitle(title) else Pair(null, null)
                var seasonNum = rawSeasonNum.ifEmpty { parsed.first?.toString() ?: "" }.ifEmpty { "1" }
                if (seasonNum == "0") seasonNum = "1"

                val id = cleanJsonId(epMap["id"] ?: epMap["stream_id"])
                val ext = epMap["container_extension"]?.toString() ?: "mp4"
                val customParams = getCustomQueryParams(host)
                val suffix = if (customParams.isNotEmpty()) "?$customParams" else ""
                val playUrl = "${xtreamBaseUrl(host, port)}/series/${URLParamEncode(user)}/${URLParamEncode(pass)}/$id.$ext$suffix"
                
                val rawEpNum = cleanJsonId(epMap["episode_num"] ?: epMap["episode"] ?: epMap["episode_number"] ?: epMap["episode_no"] ?: epMap["num"])
                var epNum = rawEpNum.ifEmpty { parsed.second?.toString() ?: "" }
                if (epNum.isEmpty() || epNum == "0") {
                    epNum = (idx + 1).toString()
                }

                val finalTitle = run {
                    val cleanTitle = title
                        .replace(Regex("(?i)\\b[ST]\\d+E\\d+\\b"), "")
                        .replace(Regex("(?i)\\b\\d+x\\d+\\b"), "")
                        .replace(Regex("(?i)episodio\\s*\\d+"), "")
                        .replace(Regex("(?i)episode\\s*\\d+"), "")
                        .trim().removePrefix(":").removePrefix("-").trim()
                    if (cleanTitle.isNotEmpty()) "S$seasonNum E$epNum: $cleanTitle" else "S$seasonNum E$epNum"
                }
                
                val prog = EpgProgramme(
                    start = 0,
                    stop = 0,
                    title = finalTitle,
                    desc = (epMap["info"] as? Map<*, *>)?.get("plot")?.toString() ?: "",
                    category = playUrl,
                    rating = epNum
                )
                if (!episodesBySeason.containsKey(seasonNum)) {
                    episodesBySeason[seasonNum] = ArrayList()
                }
                episodesBySeason[seasonNum]?.add(prog)
            }
            
            episodesBySeason.forEach { (seasonNum, eps) ->
                outSeasons.add(EpgChannelData(
                    id = seasonNum,
                    name = "Temporada $seasonNum",
                    programmes = eps.sortedBy { it.rating.toIntOrNull() ?: 0 }
                ))
            }
        }
        return outSeasons.sortedBy { it.id.toIntOrNull() ?: 0 }
    }

    suspend fun fetchXtreamVodCategories(host: String, port: String, user: String, pass: String): List<Map<String, String>> {
        val vodCatsUrl = xtreamApiUrl(host, port, user, pass, "get_vod_categories")
        val catMapType = Types.newParameterizedType(List::class.java, Map::class.java)
        val catAdapter = moshi.adapter<List<Map<String, Any?>>>(catMapType)
        val out = ArrayList<Map<String, String>>()
        try {
            val vodCatsJson = BaseEngine.executeGetRequest(vodCatsUrl)
            catAdapter.fromJson(vodCatsJson)?.forEach { c ->
                val id = cleanJsonId(c["category_id"])
                val name = c["category_name"]?.toString() ?: "General"
                out.add(mapOf("id" to id, "name" to name))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching vod categories", e) }
        return out
    }

    suspend fun fetchXtreamSeriesCategories(host: String, port: String, user: String, pass: String): List<Map<String, String>> {
        val serCatsUrl = xtreamApiUrl(host, port, user, pass, "get_series_categories")
        val catMapType = Types.newParameterizedType(List::class.java, Map::class.java)
        val catAdapter = moshi.adapter<List<Map<String, Any?>>>(catMapType)
        val out = ArrayList<Map<String, String>>()
        try {
            val serCatsJson = BaseEngine.executeGetRequest(serCatsUrl)
            catAdapter.fromJson(serCatsJson)?.forEach { c ->
                val id = cleanJsonId(c["category_id"])
                val name = c["category_name"]?.toString() ?: "General"
                out.add(mapOf("id" to id, "name" to name))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching series categories", e) }
        return out
    }

    suspend fun fetchXtreamVodForCategory(host: String, port: String, user: String, pass: String, categoryId: String, categoryName: String): List<IptvItem> {
        val url = xtreamApiUrl(host, port, user, pass, "get_vod_streams") + "&category_id=$categoryId"
        val listType = Types.newParameterizedType(List::class.java, Map::class.java)
        val listAdapter = moshi.adapter<List<Map<String, Any?>>>(listType)
        val movies = ArrayList<IptvItem>()
        val customParams = getCustomQueryParams(host)
        val suffix = if (customParams.isNotEmpty()) "?$customParams" else ""
        try {
            val json = BaseEngine.executeGetRequest(url)
            listAdapter.fromJson(json)?.forEach { s ->
                val id = cleanJsonId(s["stream_id"])
                val ext = s["container_extension"]?.toString() ?: "mp4"
                movies.add(IptvItem(
                    name = s["name"]?.toString() ?: "Sin nombre",
                    group = categoryName,
                    logo = s["stream_icon"]?.toString(),
                    tvgLogo = s["stream_icon"]?.toString(),
                    url = "${xtreamBaseUrl(host, port)}/movie/${URLParamEncode(user)}/${URLParamEncode(pass)}/$id.$ext$suffix",
                    rating = s["rating"]?.toString()?.toFloatOrNull(),
                    year = s["releaseDate"]?.toString()?.take(4)?.toIntOrNull() ?: s["release_date"]?.toString()?.take(4)?.toIntOrNull()
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching vod for category $categoryId", e) }
        return movies
    }

    suspend fun fetchXtreamSeriesForCategory(host: String, port: String, user: String, pass: String, categoryId: String, categoryName: String): List<IptvItem> {
        val url = xtreamApiUrl(host, port, user, pass, "get_series") + "&category_id=$categoryId"
        val listType = Types.newParameterizedType(List::class.java, Map::class.java)
        val listAdapter = moshi.adapter<List<Map<String, Any?>>>(listType)
        val series = ArrayList<IptvItem>()
        try {
            val json = BaseEngine.executeGetRequest(url)
            listAdapter.fromJson(json)?.forEach { s ->
                val sId = cleanJsonId(s["series_id"])
                series.add(IptvItem(
                    name = s["name"]?.toString() ?: "Sin nombre",
                    group = categoryName,
                    logo = s["cover"]?.toString(),
                    tvgLogo = s["cover"]?.toString(),
                    seriesId = sId,
                    rating = s["rating"]?.toString()?.toFloatOrNull(),
                    year = s["releaseDate"]?.toString()?.take(4)?.toIntOrNull()
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "Error fetching series for category $categoryId", e) }
        return series
    }

    suspend fun fetchXtreamVodInfo(host: String, port: String, user: String, pass: String, vodId: String): Map<String, Any?>? {
        val cleanId = vodId.toDoubleOrNull()?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else vodId
        } ?: vodId
        val url = xtreamApiUrl(host, port, user, pass, "get_vod_info") + "&vod_id=$cleanId"
        return try {
            val json = BaseEngine.executeGetRequest(url)
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            val adapter = moshi.adapter<Map<String, Any?>>(type)
            adapter.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching xtream vod info for id $vodId: ${e.message}")
            null
        }
    }

    suspend fun fetchXtreamSeriesInfo(host: String, port: String, user: String, pass: String, seriesId: String): Map<String, Any?>? {
        val cleanId = seriesId.toDoubleOrNull()?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else seriesId
        } ?: seriesId
        val url = xtreamApiUrl(host, port, user, pass, "get_series_info") + "&series_id=$cleanId"
        return try {
            val json = BaseEngine.executeGetRequest(url)
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            val adapter = moshi.adapter<Map<String, Any?>>(type)
            adapter.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching xtream series info for id $seriesId: ${e.message}")
            null
        }
    }
}
