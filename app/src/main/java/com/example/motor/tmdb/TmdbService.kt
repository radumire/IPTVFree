package com.example.motor.tmdb

import android.util.Log
import com.example.motor.parser.BaseEngine
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

object TmdbService {
    private const val TAG = "TmdbService"
    private const val BASE_IMAGE_URL = "https://image.tmdb.org/t/p/w500"
    
    // TMDb Keys to try in order (rotated)
    private val TMDB_KEYS = listOf(
        "4ef0d7355d9ffb5151e987764708ce96",
        "8476a7ab80ad76f0936744df0430e67c",
        "2c46288716a18fb7aadcc2a801f3fc6b",
        "e9d8e5781a7b6b8b0e8b2b7e19b88e1a",
        "223b2c7e0c4b281f621a60f95ad1b4f4",
        "50e40f81078c3da999b79fbfd7521d68"
    )

    // OMDb Keys to try in order as fallback (rotated)
    private val OMDB_KEYS = listOf(
        "thewid",
        "3a0058b8",
        "7b27568c",
        "f9ee4256",
        "9a35e16b",
        "8ea7fa56"
    )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val yearCache = ConcurrentHashMap<String, Int>()
    private var cacheFile: java.io.File? = null
    private var yearCacheFile: java.io.File? = null

    fun init(cacheDir: java.io.File) {
        if (cacheFile == null) {
            cacheFile = java.io.File(cacheDir, "tmdb_poster_cache.json")
            loadCache()
        }
        if (yearCacheFile == null) {
            yearCacheFile = java.io.File(cacheDir, "tmdb_year_cache.json")
            loadYearCache()
        }
    }

    private fun loadCache() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText()
            val json = JSONObject(content)
            json.keys().forEach { key ->
                posterCache[key] = json.getString(key)
            }
            Log.d(TAG, "Loaded ${posterCache.size} posters from persistent disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load poster persistent cache", e)
        }
    }

    private fun saveCache() {
        val file = cacheFile ?: return
        synchronized(this) {
            try {
                val json = JSONObject()
                posterCache.forEach { (key, value) ->
                    json.put(key, value)
                }
                file.writeText(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save poster persistent cache", e)
            }
        }
    }

    private fun loadYearCache() {
        val file = yearCacheFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText()
            val json = JSONObject(content)
            json.keys().forEach { key ->
                val y = json.optInt(key, 0)
                if (y > 1900) {
                    yearCache[key] = y
                }
            }
            Log.d(TAG, "Loaded ${yearCache.size} real release years from persistent disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load year persistent cache", e)
        }
    }

    private fun saveYearCache() {
        val file = yearCacheFile ?: return
        synchronized(yearCache) {
            try {
                val json = JSONObject()
                yearCache.forEach { (key, value) ->
                    json.put(key, value)
                }
                file.writeText(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save year persistent cache", e)
            }
        }
    }

    fun getCachedYear(title: String?, isSeries: Boolean): Int? {
        if (title.isNullOrBlank()) return null
        val cleanedResult = cleanTitleAndExtractYear(title)
        val searchTitle = if (cleanedResult.title.isNotBlank()) cleanedResult.title else cleanSimple(title)
        val prefix = if (isSeries) "series" else "movie"
        
        yearCache["${prefix}_${searchTitle}_${cleanedResult.year}"]?.let { if (it > 1900) return it }
        yearCache["${prefix}_${searchTitle}"]?.let { if (it > 1900) return it }
        yearCache["${prefix}_${title.trim()}"]?.let { if (it > 1900) return it }
        
        detailsCache["${prefix}_${cleanedResult.title}_${cleanedResult.year}"]?.year?.let { if (it > 1900) return it }
        
        return null
    }

    data class CleanedTitle(val title: String, val year: Int?)

    private val videoExtensionsRegex = Regex("(?i)\\.(mkv|mp4|avi|srt|ts|ts4|m3u8|flv|wmv|vob|iso)$")
    private val bracketPrefixRegex = Regex("^(?:\\[[^\\]]+\\]|\\{[^\\}]+\\}|\\((?:[a-zA-Z0-9]{1,6}|latino|castellano|español|espanol|spanish|english|french|german|sub|dual|multi|uhd|fhd|hd|4k|3d|sd)\\))\\s*")
    private val yearRegex = Regex("\\b(19[5-9]\\d|20[0-9]\\d)\\b")
    private val seasonRegex = Regex("(?i)\\b(s\\d+e\\d+|t\\d+e\\d+|s\\d+|t\\d+|e\\d+|c\\d+|ep\\d+|cap\\d+|season\\s*\\d+|temporada\\s*\\d+|temp\\.?\\s*\\d+|capitulo\\s*\\d+|cap\\.?\\s*\\d+|episodio\\s*\\d+|\\d+x\\d+)\\b")
    private val qualityKeywordRegex = Regex("(?i)\\b(1080p|720p|2160p|4k|uhd|bluray|blu-ray|h264|h265|x264|x265|hevc|webrip|web-?dl|dvdrip|remux|bdrip|microhd|ac3|dts|dd5\\.1|hdtv|xvid|divx|vostfr|subfrench)\\b")

    private val TECHNICAL_NUMBERS_STR = setOf("1920", "1080", "2160", "3840", "720", "480", "576")
    private val PROTECTED_NUMERIC_TITLES = setOf(
        "1917", "1984", "2001", "2010", "2012", "2049", "2077", "2000", "300",
        "1922", "1941", "1942", "1944", "1987", "1408"
    )

    private val KNOWN_PREFIX_SET = setOf(
        "es", "esp", "lat", "latino", "castellano", "en", "eng", "english", "spanish",
        "fr", "fra", "french", "de", "ger", "german", "it", "ita", "italian", "pt", "por", "portuguese",
        "ro", "ru", "rus", "ch", "cn", "chinese", "jp", "jap", "kr", "kor", "ar", "arab", "tr", "pl",
        "mx", "us", "uk", "4k", "uhd", "fhd", "hd", "sd", "3d", "hdr", "1080p", "720p", "2160p",
        "vod", "cine", "pelicula", "peliculas", "pelis", "movie", "movies", "serie", "series",
        "estreno", "estrenos", "novedad", "novedades", "latest", "vip", "premium", "top"
    )

    fun stripDelimitedPrefix(text: String): String {
        var str = text.trim()
        var matched = true
        var loops = 0
        while (matched && loops < 5) {
            loops++
            val match = Regex("""^(?:([a-zA-Z0-9+]{1,12})\s*[:|\-/•]\s*|:([a-zA-Z0-9+]{1,6}):?\s*)""").find(str)
            if (match != null) {
                val tag = (match.groupValues[1].ifEmpty { match.groupValues[2] }).lowercase()
                val isKnown = tag in KNOWN_PREFIX_SET
                val isShortTag = tag.length in 2..3 && (match.value.contains(":") || match.value.contains("|"))
                if (isKnown || isShortTag) {
                    str = str.substring(match.range.last + 1).trim()
                    continue
                }
            }
            val spaceMatch = Regex("""^([a-zA-Z0-9+]{2,6})\s+""").find(str)
            if (spaceMatch != null) {
                val tag = spaceMatch.groupValues[1].lowercase()
                if (tag in KNOWN_PREFIX_SET) {
                    str = str.substring(spaceMatch.range.last + 1).trim()
                    continue
                }
            }
            matched = false
        }
        return str
    }

    fun cleanTitleAndExtractYear(rawTitle: String?): CleanedTitle {
        if (rawTitle.isNullOrBlank()) return CleanedTitle("", null)

        var clean = rawTitle.trim()
        
        // Remove file extension
        clean = clean.replace(videoExtensionsRegex, "")

        // Strip trailing dots, ellipsis, spaces, hyphens, colons
        clean = clean.replace(Regex("[. :\\-_/|#+]+$"), "").trim()

        // 1. Check for explicit year enclosed in parentheses or brackets FIRST: (2017), [2017]
        // Extracting this before stripping bracket prefixes ensures that leading years like [2024] or (2024)
        // are preserved as years rather than mistakenly consumed as prefix tags.
        var year: Int? = null
        val bracketYearRegex = Regex("""(?i)[\[\(]\s*(19[5-9]\d|20[0-9]\d)\s*[\]\)]""")
        val bracketMatches = bracketYearRegex.findAll(clean).toList()
        if (bracketMatches.isNotEmpty()) {
            val lastMatch = bracketMatches.last()
            val yVal = lastMatch.groupValues[1].toIntOrNull()
            if (yVal != null && yVal in 1900..2099) {
                year = yVal
                clean = clean.removeRange(lastMatch.range).trim()
            }
        }

        // 2. Strip bracket prefixes like [ES] [1080p], (LAT)
        var matched = true
        var loops = 0
        while (matched && loops < 5) {
            loops++
            val before = clean
            clean = clean.replace(bracketPrefixRegex, "").trim()
            matched = clean != before
        }

        // 3. Strip delimited prefixes like "ES:", "ES -", "LAT |", "4K -"
        clean = stripDelimitedPrefix(clean)

        // Strip leading symbols (e.g. "- ", ": ", "| ")
        clean = clean.replace(Regex("^[\\s:|\\-_/•#+]+"), "").trim()

        // Replace dots, underscores, pipes with spaces if they are used as word separators
        var processed = clean.replace(Regex("[._|/+]"), " ")
        var truncateIndex = processed.length

        val seasonMatch = seasonRegex.find(processed)
        if (seasonMatch != null && seasonMatch.range.first > 1) {
            truncateIndex = minOf(truncateIndex, seasonMatch.range.first)
        }

        val qualityMatch = qualityKeywordRegex.find(processed)
        if (qualityMatch != null && qualityMatch.range.first > 1) {
            truncateIndex = minOf(truncateIndex, qualityMatch.range.first)
        }

        // If no year in brackets, check if there's a release year before quality tags or in scene release
        if (year == null) {
            val textBeforeQuality = processed.substring(0, truncateIndex).trim()
            val candidateYears = yearRegex.findAll(textBeforeQuality).toList()
            if (candidateYears.isNotEmpty()) {
                val lastYearMatch = candidateYears.last()
                val yVal = lastYearMatch.value.toIntOrNull()

                // Check technical numbers (e.g. 1920 in 1920x1080)
                val isTechnicalNumber = lastYearMatch.value in TECHNICAL_NUMBERS_STR

                // Check if this number is protected as part of the title itself (e.g. "1917", "Blade Runner 2049")
                val isEntireTitleNumeric = textBeforeQuality.trim().equals(lastYearMatch.value, ignoreCase = true)
                val isProtectedNumericTitle = lastYearMatch.value in PROTECTED_NUMERIC_TITLES &&
                        (isEntireTitleNumeric ||
                         textBeforeQuality.contains("Blade Runner", ignoreCase = true) ||
                         textBeforeQuality.contains("Wonder Woman", ignoreCase = true) ||
                         textBeforeQuality.contains("Space Odyssey", ignoreCase = true) ||
                         textBeforeQuality.contains("Death Race", ignoreCase = true) ||
                         textBeforeQuality.contains("Blues Brothers", ignoreCase = true) ||
                         textBeforeQuality.contains("Cyberpunk", ignoreCase = true))

                // Only consider it a release year if <= 2026, not technical, and not a protected numeric title
                if (!isTechnicalNumber && !isProtectedNumericTitle && yVal != null && yVal in 1950..2026) {
                    val isFollowedByQuality = truncateIndex < processed.length
                    val isSceneDotted = clean.contains(".${lastYearMatch.value}.", ignoreCase = true) ||
                            clean.contains("_${lastYearMatch.value}_", ignoreCase = true) ||
                            clean.endsWith(".${lastYearMatch.value}", ignoreCase = true)
                    if (isFollowedByQuality || isSceneDotted) {
                        year = yVal
                        if (lastYearMatch.range.first > 1) {
                            truncateIndex = minOf(truncateIndex, lastYearMatch.range.first)
                        }
                    }
                }
            }

            // Fallback: If no year before quality tags, check if a year exists in the trailing metadata (e.g. "Pelicula 1080p 2021")
            if (year == null && truncateIndex < processed.length) {
                val textAfterQuality = processed.substring(truncateIndex).trim()
                val candidateYearsAfter = yearRegex.findAll(textAfterQuality).toList()
                if (candidateYearsAfter.isNotEmpty()) {
                    val validTrailingYears = candidateYearsAfter.filter { match ->
                        val v = match.value.toIntOrNull()
                        v != null && v in 1950..2026 && match.value !in TECHNICAL_NUMBERS_STR
                    }
                    if (validTrailingYears.isNotEmpty()) {
                        year = validTrailingYears.last().value.toIntOrNull()
                    }
                }
            }
        }

        var titlePart = if (truncateIndex in 1 until processed.length) {
            processed.substring(0, truncateIndex)
        } else {
            processed
        }

        // Clean up remaining brackets, parentheses, unwanted characters
        titlePart = titlePart.replace(Regex("\\[.*?\\]"), " ")
        titlePart = titlePart.replace(Regex("\\(.*?\\)"), " ")
        titlePart = titlePart.replace(Regex("[^\\p{L}\\p{N}:;\\-'!?, ]"), " ")
        titlePart = titlePart.replace(Regex("\\s+"), " ").replace(Regex("[. :\\-_/|#+]+$"), "").trim()

        // If for any reason titlePart became empty or too short, fallback to basic cleaned title
        if (titlePart.length < 2) {
            titlePart = clean.replace(videoExtensionsRegex, "").replace(Regex("\\[.*?\\]"), " ").replace(Regex("\\(.*?\\)"), " ").replace(Regex("\\s+"), " ").trim()
        }
        if (titlePart.isBlank()) {
            titlePart = rawTitle.trim()
        }

        return CleanedTitle(titlePart, year)
    }

    private val API_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json"
    )

    fun cleanSimple(rawTitle: String): String {
        var clean = rawTitle.replace(Regex("\\.(mkv|mp4|avi|srt|ts|ts4|m3u8)$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\[.*?\\]"), " ")
        clean = clean.replace(Regex("\\(.*?\\)"), " ")
        clean = clean.replace(Regex("[.\\-_/|+]"), " ")
        return clean.replace(Regex("\\s+"), " ").replace(Regex("[. :\\-_/|#+]+$"), "").trim()
    }

    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean): Pair<String?, Int?> {
        val cleanedResult = cleanTitleAndExtractYear(title)
        val cleanedTitle = cleanedResult.title
        val extractedYear = cleanedResult.year
        if (cleanedTitle.isBlank() && title.isBlank()) return Pair(null, null)
        
        val searchTitle = if (cleanedTitle.isNotBlank()) cleanedTitle else cleanSimple(title)
        val prefix = if (isSeries) "series" else "movie"
        val cacheKey = "${prefix}_${searchTitle}_$extractedYear"
        
        val cachedPoster = posterCache[cacheKey]?.takeIf { it.isNotBlank() }
        val cachedYear = getCachedYear(title, isSeries)
        
        if (cachedPoster != null && cachedYear != null) {
            return Pair(cachedPoster, cachedYear)
        }

        return withContext(Dispatchers.IO) {
            var posterUrl: String? = cachedPoster
            var realYear: Int? = cachedYear
            val simpleTitle = cleanSimple(title)

            // Construir lista de términos de búsqueda inteligentes
            val queryTerms = mutableListOf<Pair<String, Int?>>()
            if (cleanedTitle.isNotEmpty()) {
                queryTerms.add(Pair(cleanedTitle, extractedYear))
                if (extractedYear != null) {
                    queryTerms.add(Pair(cleanedTitle, null))
                }
                // Si el título contiene dos puntos o guion (ej: "Ramy Youssef: In Love" -> "Ramy Youssef")
                if (cleanedTitle.contains(":")) {
                    val prefixStr = cleanedTitle.substringBefore(":").trim()
                    if (prefixStr.length >= 3) {
                        queryTerms.add(Pair(prefixStr, extractedYear))
                        if (extractedYear != null) queryTerms.add(Pair(prefixStr, null))
                    }
                }
                if (cleanedTitle.contains(" - ")) {
                    val prefixStr = cleanedTitle.substringBefore(" - ").trim()
                    if (prefixStr.length >= 3) {
                        queryTerms.add(Pair(prefixStr, extractedYear))
                        if (extractedYear != null) queryTerms.add(Pair(prefixStr, null))
                    }
                }
            }
            if (simpleTitle.isNotEmpty() && simpleTitle != cleanedTitle) {
                queryTerms.add(Pair(simpleTitle, extractedYear))
                if (extractedYear != null) {
                    queryTerms.add(Pair(simpleTitle, null))
                }
            }

            val distinctTerms = queryTerms.distinct()

            // 1. TMDb Search (revisando TODOS los resultados, no solo el primero, y con fallback multi/tv/movie)
            val endpoints = if (isSeries) listOf("tv", "multi", "movie") else listOf("movie", "multi", "tv")
            for (apiKey in TMDB_KEYS) {
                try {
                    var found = false
                    for ((queryText, yearVal) in distinctTerms) {
                        val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                        for (endpoint in endpoints) {
                            var url = "https://api.themoviedb.org/3/search/$endpoint?api_key=$apiKey&query=$encodedTitle&include_adult=true&language=es-ES"
                            if (yearVal != null && endpoint != "multi") {
                                url += if (endpoint == "tv") "&first_air_date_year=$yearVal" else "&year=$yearVal"
                            }
                            
                            val responseJson = BaseEngine.executeGetRequest(url, API_HEADERS)
                            val jsonObject = JSONObject(responseJson)
                            val results = jsonObject.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                var bestPosterMatch: Pair<String?, Int?>? = null
                                var bestScore = -100.0

                                for (idx in 0 until minOf(results.length(), 8)) {
                                    val item = results.getJSONObject(idx)
                                    val posterPath = item.optString("poster_path", "")
                                    val releaseDate = item.optString("release_date", item.optString("first_air_date", ""))
                                    val itemYear = releaseDate.take(4).toIntOrNull()
                                    val candMedia = item.optString("media_type", if (endpoint == "tv") "tv" else "movie")
                                    val candTitle = if (candMedia == "tv") item.optString("name", "") else item.optString("title", "")
                                    val origTitle = if (candMedia == "tv") item.optString("original_name", "") else item.optString("original_title", "")

                                    val scoreMain = calculateMovieMatchScore(
                                        sourceTitle = title,
                                        cleanTitle = cleanedTitle,
                                        candidateTitle = candTitle,
                                        sourceYear = extractedYear,
                                        candidateYear = itemYear,
                                        isSeries = isSeries,
                                        candidateMediaType = candMedia
                                    )
                                    val scoreOrig = if (origTitle.isNotBlank() && origTitle != candTitle) {
                                        calculateMovieMatchScore(
                                            sourceTitle = title,
                                            cleanTitle = cleanedTitle,
                                            candidateTitle = origTitle,
                                            sourceYear = extractedYear,
                                            candidateYear = itemYear,
                                            isSeries = isSeries,
                                            candidateMediaType = candMedia
                                        )
                                    } else 0.0

                                    val candScore = maxOf(scoreMain, scoreOrig)
                                    val hasPoster = posterPath.isNotEmpty() && posterPath != "null"
                                    val effectiveScore = candScore + if (hasPoster) 5.0 else 0.0

                                    if (effectiveScore > bestScore && candScore >= 35.0) {
                                        bestScore = effectiveScore
                                        val pUrl = if (hasPoster) "$BASE_IMAGE_URL$posterPath" else null
                                        bestPosterMatch = Pair(pUrl, itemYear)
                                    }
                                }

                                if (bestPosterMatch != null) {
                                    if (posterUrl == null && bestPosterMatch.first != null) {
                                        posterUrl = bestPosterMatch.first
                                    }
                                    if (bestPosterMatch.second != null && bestPosterMatch.second!! > 1900) {
                                        realYear = bestPosterMatch.second
                                    }
                                    if (posterUrl != null || realYear != null) {
                                        Log.d(TAG, "TMDb scored match for '$queryText' ($yearVal, $endpoint, score=$bestScore): poster=$posterUrl, year=$realYear")
                                        found = true
                                    }
                                }
                            }
                            if (found) break
                        }
                        if (found) break
                    }
                    if (found) break
                } catch (e: Exception) {
                    Log.e(TAG, "TMDb API query failed for key $apiKey: ${e.message}")
                }
            }

            // 2. Cinemeta (Stremio Metadata - IMDb Catalog) Fallback
            if (posterUrl == null || realYear == null) {
                val cinemetaTypes = if (isSeries) listOf("series", "movie") else listOf("movie", "series")
                for (queryText in distinctTerms.map { it.first }.distinct()) {
                    val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                    for (cType in cinemetaTypes) {
                        try {
                            val cUrl = "https://v3-cinemeta.strem.io/catalog/$cType/top/search=$encodedTitle.json"
                            val responseJson = BaseEngine.executeGetRequest(cUrl, API_HEADERS)
                            val jsonObject = JSONObject(responseJson)
                            val metas = jsonObject.optJSONArray("metas")
                            if (metas != null && metas.length() > 0) {
                                for (idx in 0 until minOf(metas.length(), 5)) {
                                    val meta = metas.getJSONObject(idx)
                                    val poster = meta.optString("poster", "")
                                    val cYearStr = meta.optString("year", meta.optString("releaseInfo", ""))
                                    val cYear = cYearStr.filter { it.isDigit() }.take(4).toIntOrNull()
                                    val metaName = meta.optString("name", "")
                                    val metaType = meta.optString("type", if (isSeries) "series" else "movie")

                                    val cScore = calculateMovieMatchScore(
                                        sourceTitle = title,
                                        cleanTitle = cleanedTitle,
                                        candidateTitle = metaName,
                                        sourceYear = extractedYear,
                                        candidateYear = cYear,
                                        isSeries = isSeries,
                                        candidateMediaType = if (metaType == "series") "tv" else "movie"
                                    )

                                    if (cScore >= 35.0) {
                                        if (poster.isNotEmpty() && poster != "null" && poster.startsWith("http") && posterUrl == null) {
                                            posterUrl = poster
                                        }
                                        if (cYear != null && cYear > 1900 && realYear == null) {
                                            realYear = cYear
                                        }
                                        if (posterUrl != null && realYear != null) {
                                            Log.d(TAG, "Cinemeta match found for query '$queryText' ($cType, score=$cScore): poster=$posterUrl, year=$realYear")
                                            break
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Cinemeta fallback error for query '$queryText': ${e.message}")
                        }
                        if (posterUrl != null && realYear != null) break
                    }
                    if (posterUrl != null && realYear != null) break
                }
            }

            // 3. TVmaze Fallback (para series, especiales y shows)
            if (posterUrl == null || realYear == null) {
                for (queryText in distinctTerms.map { it.first }.distinct()) {
                    try {
                        val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                        val url = "https://api.tvmaze.com/singlesearch/shows?q=$encodedTitle"
                        val responseJson = BaseEngine.executeGetRequest(url, API_HEADERS)
                        val jsonObject = JSONObject(responseJson)
                        val premiered = jsonObject.optString("premiered", "")
                        val tvYear = premiered.take(4).toIntOrNull()
                        if (tvYear != null && tvYear > 1900 && realYear == null) {
                            realYear = tvYear
                        }
                        val imageObj = jsonObject.optJSONObject("image")
                        if (imageObj != null && posterUrl == null) {
                            val original = imageObj.optString("original", "")
                            val medium = imageObj.optString("medium", "")
                            val candidate = if (original.isNotEmpty()) original else medium
                            if (candidate.isNotEmpty() && candidate != "null") {
                                posterUrl = candidate
                                Log.d(TAG, "TVmaze match found for query '$queryText': poster=$posterUrl, year=$realYear")
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                    if (posterUrl != null && realYear != null) break
                }
            }

            // 4. iTunes Search Fallback (Global / Internacional, sin restringir a solo España)
            if (posterUrl == null || realYear == null) {
                for (queryText in distinctTerms.map { it.first }.distinct()) {
                    try {
                        val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                        val mediaParam = if (isSeries) "tvShow" else "movie"
                        val url = "https://itunes.apple.com/search?term=$encodedTitle&media=$mediaParam&limit=5"
                        val responseJson = BaseEngine.executeGetRequest(url, API_HEADERS)
                        val jsonObject = JSONObject(responseJson)
                        val results = jsonObject.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            for (idx in 0 until results.length()) {
                                val item = results.getJSONObject(idx)
                                val trackName = item.optString("trackName", item.optString("collectionName", ""))
                                val artwork100 = item.optString("artworkUrl100", "")
                                val relDate = item.optString("releaseDate", "")
                                val itYear = relDate.take(4).toIntOrNull()

                                val itScore = calculateMovieMatchScore(
                                    sourceTitle = title,
                                    cleanTitle = cleanedTitle,
                                    candidateTitle = trackName,
                                    sourceYear = extractedYear,
                                    candidateYear = itYear,
                                    isSeries = isSeries
                                )

                                if (itScore >= 35.0) {
                                    if (artwork100.isNotEmpty() && posterUrl == null) {
                                        posterUrl = artwork100.replace("100x100bb.jpg", "600x600bb.jpg")
                                    }
                                    if (itYear != null && itYear > 1900 && realYear == null) {
                                        realYear = itYear
                                    }
                                    if (posterUrl != null && realYear != null) {
                                        Log.d(TAG, "iTunes match found for query '$queryText' (score=$itScore): poster=$posterUrl, year=$realYear")
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                    if (posterUrl != null && realYear != null) break
                }
            }

            // 5. OMDb Fallback
            if (posterUrl == null || realYear == null) {
                for (apiKey in OMDB_KEYS) {
                    try {
                        var found = false
                        for ((queryText, yearVal) in distinctTerms) {
                            val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                            val typeParam = if (isSeries) "series" else "movie"
                            var url = "https://www.omdbapi.com/?apikey=$apiKey&t=$encodedTitle&type=$typeParam"
                            if (yearVal != null) {
                                url += "&y=$yearVal"
                            }
                            val responseJson = BaseEngine.executeGetRequest(url, API_HEADERS)
                            val jsonObject = JSONObject(responseJson)
                            val responseStatus = jsonObject.optString("Response", "False")
                            if (responseStatus.equals("True", ignoreCase = true)) {
                                val omdbTitle = jsonObject.optString("Title", "")
                                val omdbYear = jsonObject.optString("Year", "").filter { it.isDigit() }.take(4).toIntOrNull()
                                val omdbScore = calculateMovieMatchScore(
                                    sourceTitle = title,
                                    cleanTitle = cleanedTitle,
                                    candidateTitle = omdbTitle,
                                    sourceYear = extractedYear,
                                    candidateYear = omdbYear,
                                    isSeries = isSeries
                                )
                                if (omdbScore >= 35.0) {
                                    val posterPath = jsonObject.optString("Poster", "")
                                    if (posterPath.isNotEmpty() && posterPath != "N/A" && posterPath != "null" && posterUrl == null) {
                                        posterUrl = posterPath
                                    }
                                    if (omdbYear != null && omdbYear > 1900 && realYear == null) {
                                        realYear = omdbYear
                                    }
                                    if (posterUrl != null && realYear != null) {
                                        Log.d(TAG, "OMDb match found for query '$queryText' ($yearVal, score=$omdbScore): poster=$posterUrl, year=$realYear")
                                        found = true
                                        break
                                    }
                                }
                            }
                        }
                        if (found) break
                    } catch (e: Exception) {
                        Log.e(TAG, "OMDb fallback failed with key $apiKey: ${e.message}")
                    }
                }
            }

            if (posterUrl != null) {
                posterCache[cacheKey] = posterUrl
                saveCache()
            } else {
                posterCache[cacheKey] = "" // Marcador para evitar reintentos continuos en la misma sesión
            }

            if (realYear != null && realYear > 1900) {
                yearCache[cacheKey] = realYear
                yearCache["${prefix}_${searchTitle}"] = realYear
                yearCache["${prefix}_${title.trim()}"] = realYear
                saveYearCache()
            }

            Pair(posterUrl, realYear)
        }
    }

    suspend fun fetchPoster(title: String, isSeries: Boolean): String? {
        return fetchPosterAndYear(title, isSeries).first
    }

    suspend fun fetchReleaseYear(title: String, isSeries: Boolean): Int? {
        return getCachedYear(title, isSeries) ?: fetchPosterAndYear(title, isSeries).second
    }

    const val MIN_MATCH_SCORE = 50.0

    private fun normalizeForMatching(text: String): String {
        return text.lowercase()
            .replace("á", "a")
            .replace("é", "e")
            .replace("í", "i")
            .replace("ó", "o")
            .replace("ú", "u")
            .replace("ü", "u")
            .replace("ñ", "n")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun calculateMovieMatchScore(
        sourceTitle: String,
        cleanTitle: String,
        candidateTitle: String,
        sourceYear: Int?,
        candidateYear: Int?,
        isSeries: Boolean,
        candidateMediaType: String? = null
    ): Double {
        if (candidateTitle.isBlank()) return 0.0

        val normSource = normalizeForMatching(sourceTitle)
        val normClean = normalizeForMatching(cleanTitle)
        val normCandidate = normalizeForMatching(candidateTitle)

        if (normCandidate.isBlank()) return 0.0

        var titleScore = 0.0
        val isExactMatch = normCandidate == normClean || normCandidate == normSource

        // Exact match with clean title or source title
        if (isExactMatch) {
            titleScore = 70.0
        } else {
            // Check containment
            val isCleanInCand = normClean.isNotEmpty() && normCandidate.contains(normClean)
            val isCandInClean = normCandidate.isNotEmpty() && normClean.contains(normCandidate)

            if (isCleanInCand || isCandInClean) {
                val len1 = normCandidate.length.toDouble()
                val len2 = normClean.length.toDouble()
                val ratio = if (maxOf(len1, len2) > 0) minOf(len1, len2) / maxOf(len1, len2) else 0.0
                titleScore = 40.0 + (20.0 * ratio)
            } else {
                // Word token similarity (Jaccard on words, ignoring common stop words)
                val stopWords = setOf("the", "el", "la", "los", "las", "un", "una", "de", "del", "a", "and", "y", "of", "in", "en", "to", "on", "al")
                val cleanWords = normClean.split(" ").filter { it.length > 1 && it !in stopWords }.toSet()
                val candWords = normCandidate.split(" ").filter { it.length > 1 && it !in stopWords }.toSet()

                if (cleanWords.isNotEmpty() && candWords.isNotEmpty()) {
                    val intersection = cleanWords.intersect(candWords).size
                    val union = cleanWords.union(candWords).size
                    val jaccard = intersection.toDouble() / union.toDouble()
                    if (jaccard >= 0.8) {
                        titleScore = 45.0
                    } else if (jaccard >= 0.5) {
                        titleScore = 30.0
                    } else if (jaccard >= 0.3) {
                        titleScore = 15.0
                    } else {
                        // Heavy penalty for different title
                        titleScore = -35.0
                    }
                } else {
                    titleScore = -25.0
                }
            }
        }

        // Year score & penalty: exact year (+30), ±1 year (+15), >= 2 years penalized
        var yearScore = 0.0
        if (sourceYear != null && candidateYear != null && sourceYear > 1900 && candidateYear > 1900) {
            val yearDiff = kotlin.math.abs(sourceYear - candidateYear)
            when (yearDiff) {
                0 -> yearScore = 30.0
                1 -> yearScore = 15.0
                2 -> yearScore = if (isExactMatch) -5.0 else -20.0
                else -> yearScore = if (isExactMatch) -35.0 else -60.0
            }
        }

        // Media type penalty / bonus
        var typeScore = 0.0
        if (candidateMediaType != null) {
            if (isSeries) {
                if (candidateMediaType == "tv") typeScore = 10.0
                else if (candidateMediaType == "movie") typeScore = -35.0 // Heavy penalty
            } else {
                if (candidateMediaType == "movie") typeScore = 10.0
                else if (candidateMediaType == "tv") typeScore = if (isExactMatch) 0.0 else -35.0
            }
        }

        return titleScore + yearScore + typeScore
    }

    data class MediaDetails(
        val title: String,
        val overview: String,
        val posterUrl: String?,
        val backdropUrl: String?,
        val releaseDate: String?,
        val year: Int?,
        val voteAverage: Double?,
        val genres: List<String>,
        val runtime: Int? = null,
        val numberOfSeasons: Int? = null,
        val tmdbId: Int? = null,
        val cast: String? = null,
        val director: String? = null,
        val originalTitle: String? = null
    )

    private val detailsCache = ConcurrentHashMap<String, MediaDetails>()

    private val TMDB_GENRES = mapOf(
        28 to "Acción",
        12 to "Aventura",
        16 to "Animación",
        35 to "Comedia",
        80 to "Crimen",
        99 to "Documental",
        18 to "Drama",
        10751 to "Familia",
        14 to "Fantasía",
        36 to "Historia",
        27 to "Terror",
        10402 to "Música",
        9648 to "Misterio",
        10749 to "Romance",
        878 to "Ciencia ficción",
        10770 to "Película de TV",
        53 to "Suspense",
        10752 to "Bélica",
        37 to "Western",
        10759 to "Acción y Aventura",
        10762 to "Infantil",
        10763 to "Noticias",
        10764 to "Reality",
        10765 to "Sci-Fi y Fantasía",
        10766 to "Telenovela",
        10767 to "Talk Show",
        10768 to "Guerra y Política"
    )

    private data class CandidateInfo(
        val id: Int,
        val title: String,
        val year: Int?,
        val mediaType: String,
        val posterPath: String,
        val backdropPath: String,
        val voteAverage: Double?,
        val releaseDate: String,
        val genreIds: List<Int>,
        val endpoint: String,
        val apiKey: String,
        val score: Double,
        val originalTitle: String? = null
    )

    fun getCachedMediaDetails(title: String, isSeries: Boolean): MediaDetails? {
        val cleanedResult = cleanTitleAndExtractYear(title)
        val cacheKey = "${if (isSeries) "series" else "movie"}_${cleanedResult.title}_${cleanedResult.year}"
        return detailsCache[cacheKey]?.takeIf {
            it.overview.isNotBlank() && it.overview != "Sin sinopsis disponible." && it.overview != "N/A"
        }
    }

    fun putCachedMediaDetails(title: String, isSeries: Boolean, details: MediaDetails) {
        val cleanedResult = cleanTitleAndExtractYear(title)
        val cacheKey = "${if (isSeries) "series" else "movie"}_${cleanedResult.title}_${cleanedResult.year}"
        if (details.overview.isNotBlank() && details.overview != "Sin sinopsis disponible." && details.overview != "N/A") {
            detailsCache[cacheKey] = details
        }
        if (details.year != null && details.year > 1900) {
            val prefix = if (isSeries) "series" else "movie"
            yearCache["${prefix}_${cleanedResult.title}_${cleanedResult.year}"] = details.year
            yearCache["${prefix}_${cleanedResult.title}"] = details.year
            yearCache["${prefix}_${title.trim()}"] = details.year
            saveYearCache()
        }
    }

    suspend fun fetchMediaDetails(title: String, isSeries: Boolean, defaultLogo: String? = null): MediaDetails {
        val cleanedResult = cleanTitleAndExtractYear(title)
        val cleanedTitle = cleanedResult.title
        val extractedYear = cleanedResult.year
        val cacheKey = "${if (isSeries) "series" else "movie"}_${cleanedTitle}_$extractedYear"
        
        // Only return cached details if it has a valid overview
        detailsCache[cacheKey]?.let { cached ->
            if (cached.overview.isNotBlank() && cached.overview != "Sin sinopsis disponible." && cached.overview != "N/A") {
                return cached
            }
        }

        return withContext(Dispatchers.IO) {
            val simpleTitle = cleanSimple(title)
            val queryTerms = mutableListOf<Pair<String, Int?>>()
            if (cleanedTitle.isNotEmpty()) {
                queryTerms.add(Pair(cleanedTitle, extractedYear))
                if (extractedYear != null) {
                    queryTerms.add(Pair(cleanedTitle, null))
                }
            }
            if (simpleTitle.isNotEmpty() && simpleTitle != cleanedTitle) {
                queryTerms.add(Pair(simpleTitle, extractedYear))
                if (extractedYear != null) {
                    queryTerms.add(Pair(simpleTitle, null))
                }
            }
            if (queryTerms.isEmpty() && title.isNotBlank()) {
                queryTerms.add(Pair(title.trim(), extractedYear))
            }

            var resolvedDetails: MediaDetails? = null
            var finalSource = "NONE"

            // 1. Evaluate TMDb candidates with calculateMovieMatchScore
            val candidates = mutableListOf<CandidateInfo>()
            val endpoints = if (isSeries) listOf("tv", "multi") else listOf("movie", "multi")

            for (apiKey in TMDB_KEYS) {
                try {
                    for ((queryText, yearVal) in queryTerms.distinct()) {
                        val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                        for (endpoint in endpoints) {
                            var url = "https://api.themoviedb.org/3/search/$endpoint?api_key=$apiKey&query=$encodedTitle&include_adult=true&language=es-ES"
                            if (yearVal != null && endpoint != "multi") {
                                url += if (endpoint == "tv") "&first_air_date_year=$yearVal" else "&year=$yearVal"
                            }

                            val responseJson = BaseEngine.executeGetRequest(url, API_HEADERS)
                            val jsonObject = JSONObject(responseJson)
                            val results = jsonObject.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                for (idx in 0 until minOf(results.length(), 5)) {
                                    val item = results.getJSONObject(idx)
                                    val tmdbId = item.optInt("id", 0)
                                    if (tmdbId <= 0) continue

                                    val mediaType = item.optString("media_type", if (endpoint == "tv") "tv" else "movie")
                                    val actualEndpoint = if (mediaType == "tv") "tv" else "movie"
                                    val candName = if (actualEndpoint == "tv") item.optString("name", "") else item.optString("title", "")
                                    val origName = if (actualEndpoint == "tv") item.optString("original_name", "") else item.optString("original_title", "")
                                    val date = if (actualEndpoint == "tv") item.optString("first_air_date", "") else item.optString("release_date", "")
                                    val candYear = date.take(4).toIntOrNull()
                                    val posterPath = item.optString("poster_path", "")
                                    val backdropPath = item.optString("backdrop_path", "")
                                    val voteAvg = if (item.has("vote_average")) item.optDouble("vote_average", 0.0).takeIf { it > 0.0 } else null

                                    val genreIds = item.optJSONArray("genre_ids")
                                    val gList = mutableListOf<Int>()
                                    if (genreIds != null) {
                                        for (i in 0 until genreIds.length()) {
                                            gList.add(genreIds.optInt(i, 0))
                                        }
                                    }

                                    val score = calculateMovieMatchScore(
                                        sourceTitle = title,
                                        cleanTitle = cleanedTitle,
                                        candidateTitle = candName,
                                        sourceYear = extractedYear,
                                        candidateYear = candYear,
                                        isSeries = isSeries,
                                        candidateMediaType = mediaType
                                    )

                                    candidates.add(
                                        CandidateInfo(
                                            id = tmdbId,
                                            title = candName,
                                            year = candYear,
                                            mediaType = mediaType,
                                            posterPath = posterPath,
                                            backdropPath = backdropPath,
                                            voteAverage = voteAvg,
                                            releaseDate = date,
                                            genreIds = gList,
                                            endpoint = actualEndpoint,
                                            apiKey = apiKey,
                                            score = score,
                                            originalTitle = origName.takeIf { it.isNotBlank() }
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (candidates.isNotEmpty()) break
                } catch (e: Exception) {
                    Log.e(TAG, "TMDb candidate query failed for key $apiKey: ${e.message}")
                }
            }

            // Find best candidate
            val bestCandidate = candidates.maxByOrNull { it.score }

            // If best candidate passes safety threshold, fetch full details using tmdbId
            if (bestCandidate != null && bestCandidate.score >= MIN_MATCH_SCORE) {
                try {
                    val tmdbId = bestCandidate.id
                    val actualEndpoint = bestCandidate.endpoint
                    val apiKey = bestCandidate.apiKey
                    val detailsUrl = "https://api.themoviedb.org/3/$actualEndpoint/$tmdbId?api_key=$apiKey&append_to_response=translations&language=es-ES"
                    val detailsJson = JSONObject(BaseEngine.executeGetRequest(detailsUrl, API_HEADERS))

                    var overview = detailsJson.optString("overview", "").trim()
                    var voteAvg = detailsJson.optDouble("vote_average", 0.0).takeIf { it > 0.0 } ?: bestCandidate.voteAverage
                    val runtime = detailsJson.optInt("runtime", 0).takeIf { it > 0 }
                    val numberOfSeasons = detailsJson.optInt("number_of_seasons", 0).takeIf { it > 0 }

                    // Look for Spanish translation if overview is empty
                    if (overview.isBlank()) {
                        val transObj = detailsJson.optJSONObject("translations")
                        val transList = transObj?.optJSONArray("translations")
                        if (transList != null) {
                            for (t in 0 until transList.length()) {
                                val transItem = transList.optJSONObject(t) ?: continue
                                val isoLang = transItem.optString("iso_639_1", "")
                                if (isoLang.equals("es", ignoreCase = true)) {
                                    val data = transItem.optJSONObject("data")
                                    val esOverview = data?.optString("overview", "")?.trim() ?: ""
                                    if (esOverview.isNotBlank()) {
                                        overview = esOverview
                                        break
                                    }
                                }
                            }
                        }
                    }

                    // Fallback to English overview and translate
                    if (overview.isBlank()) {
                        val enOverview = detailsJson.optString("overview", "").trim()
                        if (enOverview.isNotBlank()) {
                            overview = translateToSpanishIfNeeded(enOverview)
                        } else {
                            val tagline = detailsJson.optString("tagline", "").trim()
                            if (tagline.isNotBlank()) {
                                overview = translateToSpanishIfNeeded(tagline)
                            }
                        }
                    } else {
                        overview = translateToSpanishIfNeeded(overview)
                    }

                    val genreList = mutableListOf<String>()
                    val genresArr = detailsJson.optJSONArray("genres")
                    if (genresArr != null && genresArr.length() > 0) {
                        for (i in 0 until genresArr.length()) {
                            val gName = genresArr.getJSONObject(i).optString("name", "")
                            if (gName.isNotBlank()) genreList.add(gName)
                        }
                    } else {
                        bestCandidate.genreIds.forEach { gId ->
                            TMDB_GENRES[gId]?.let { genreList.add(it) }
                        }
                    }

                    val poster = if (bestCandidate.posterPath.isNotBlank() && bestCandidate.posterPath != "null") {
                        "$BASE_IMAGE_URL${bestCandidate.posterPath}"
                    } else defaultLogo

                    val backdrop = if (bestCandidate.backdropPath.isNotBlank() && bestCandidate.backdropPath != "null") {
                        "https://image.tmdb.org/t/p/w780${bestCandidate.backdropPath}"
                    } else null

                    resolvedDetails = MediaDetails(
                        title = bestCandidate.title.ifBlank { cleanedTitle.ifBlank { title } },
                        overview = overview.ifBlank { "Sin sinopsis disponible." },
                        posterUrl = poster,
                        backdropUrl = backdrop,
                        releaseDate = bestCandidate.releaseDate.ifBlank { null },
                        year = bestCandidate.year ?: extractedYear,
                        voteAverage = voteAvg,
                        genres = genreList,
                        runtime = runtime,
                        numberOfSeasons = numberOfSeasons,
                        tmdbId = tmdbId,
                        originalTitle = bestCandidate.originalTitle
                    )
                    finalSource = "TMDB"
                } catch (e: Exception) {
                    Log.e(TAG, "Failed fetching full details for TMDb ID ${bestCandidate.id}: ${e.message}")
                }
            }

            // 2. If TMDb does not have a reliable match or overview, try OMDb with strict matching validation
            var currentOverview = resolvedDetails?.overview?.trim() ?: ""
            if (resolvedDetails == null || currentOverview.isBlank() || currentOverview == "Sin sinopsis disponible." || currentOverview == "N/A") {
                val omdbResult = fetchOmdbDetailsWithValidation(title, cleanedTitle, extractedYear, queryTerms, isSeries, defaultLogo)
                if (omdbResult != null && omdbResult.overview.isNotBlank() && omdbResult.overview != "Sin sinopsis disponible.") {
                    resolvedDetails = omdbResult
                    currentOverview = resolvedDetails.overview
                    finalSource = "OMDB"
                }
            }

            // 3. Fallback to Wikipedia ONLY as last resort and only if title/year match reasonably
            if (resolvedDetails == null || currentOverview.isBlank() || currentOverview == "Sin sinopsis disponible." || currentOverview == "N/A") {
                val wikiSummary = fetchWikipediaSummaryWithValidation(cleanedTitle.ifBlank { title }, extractedYear, isSeries)
                if (!wikiSummary.isNullOrBlank()) {
                    currentOverview = wikiSummary
                    resolvedDetails = (resolvedDetails ?: MediaDetails(
                        title = cleanedTitle.ifBlank { title },
                        overview = currentOverview,
                        posterUrl = defaultLogo,
                        backdropUrl = null,
                        releaseDate = null,
                        year = extractedYear,
                        voteAverage = null,
                        genres = emptyList()
                    )).copy(overview = currentOverview)
                    finalSource = "WIKIPEDIA"
                }
            }

            val finalDetails = (resolvedDetails ?: MediaDetails(
                title = cleanedTitle.ifBlank { title },
                overview = "Sin sinopsis disponible.",
                posterUrl = defaultLogo,
                backdropUrl = null,
                releaseDate = null,
                year = extractedYear,
                voteAverage = null,
                genres = emptyList()
            )).let {
                if (it.overview.isBlank()) it.copy(overview = "Sin sinopsis disponible.") else it
            }

            // Only cache if we got a valid, non-placeholder overview
            if (finalDetails.overview.isNotBlank() && finalDetails.overview != "Sin sinopsis disponible." && finalDetails.overview != "N/A") {
                detailsCache[cacheKey] = finalDetails
            }
            if (finalDetails.year != null && finalDetails.year > 1900) {
                val prefix = if (isSeries) "series" else "movie"
                yearCache["${prefix}_${cleanedTitle}_$extractedYear"] = finalDetails.year
                yearCache["${prefix}_${cleanedTitle}"] = finalDetails.year
                yearCache["${prefix}_${title.trim()}"] = finalDetails.year
                saveYearCache()
            }
            finalDetails
        }
    }

    private suspend fun fetchOmdbDetailsWithValidation(
        sourceTitle: String,
        cleanTitle: String,
        extractedYear: Int?,
        queryTerms: List<Pair<String, Int?>>,
        isSeries: Boolean,
        defaultLogo: String?
    ): MediaDetails? = withContext(Dispatchers.IO) {
        for (apiKey in OMDB_KEYS) {
            try {
                for ((queryText, yearVal) in queryTerms) {
                    val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                    val typeParam = if (isSeries) "series" else "movie"
                    var url = "https://www.omdbapi.com/?apikey=$apiKey&t=$encodedTitle&type=$typeParam&plot=full"
                    if (yearVal != null) {
                        url += "&y=$yearVal"
                    }
                    val responseJson = BaseEngine.executeGetRequest(url)
                    val jsonObject = JSONObject(responseJson)
                    if (jsonObject.optString("Response", "False").equals("True", ignoreCase = true)) {
                        val candTitle = jsonObject.optString("Title", "")
                        val yearStr = jsonObject.optString("Year", "")
                        val parsedYear = yearStr.filter { it.isDigit() }.take(4).toIntOrNull() ?: yearVal

                        // Validate match score for OMDb candidate
                        val score = calculateMovieMatchScore(
                            sourceTitle = sourceTitle,
                            cleanTitle = cleanTitle,
                            candidateTitle = candTitle,
                            sourceYear = extractedYear,
                            candidateYear = parsedYear,
                            isSeries = isSeries,
                            candidateMediaType = if (isSeries) "tv" else "movie"
                        )

                        if (score >= MIN_MATCH_SCORE) {
                            val plot = jsonObject.optString("Plot", "").trim()
                            if (plot.isNotBlank() && plot != "N/A") {
                                val imdbRating = jsonObject.optString("imdbRating", "").toDoubleOrNull()
                                val posterPath = jsonObject.optString("Poster", "")
                                val genresStr = jsonObject.optString("Genre", "")
                                val genres = genresStr.split(",").map { it.trim() }.filter { it.isNotBlank() && it != "N/A" }
                                val poster = if (posterPath.isNotBlank() && posterPath != "N/A") posterPath else defaultLogo
                                val translatedPlot = translateToSpanishIfNeeded(plot)

                                return@withContext MediaDetails(
                                    title = candTitle.ifBlank { cleanTitle },
                                    overview = translatedPlot,
                                    posterUrl = poster,
                                    backdropUrl = null,
                                    releaseDate = jsonObject.optString("Released", ""),
                                    year = parsedYear,
                                    voteAverage = imdbRating,
                                    genres = genres
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OMDb query failed: ${e.message}")
            }
        }
        null
    }

    private suspend fun fetchWikipediaSummaryWithValidation(query: String, year: Int?, isSeries: Boolean): String? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        
        // Exact page summary lookup with movie/series suffixes
        val titlesToTry = mutableListOf(
            if (isSeries) "${query} (serie de televisión)" else "${query} (película)",
            if (year != null) "${query} (película de $year)" else null,
            if (isSeries) "${query} (TV series)" else "${query} (film)",
            if (year != null) "${query} (${year} film)" else null
        ).filterNotNull()

        for (t in titlesToTry) {
            try {
                val isSpanish = t.contains("película") || t.contains("serie de televisión")
                val lang = if (isSpanish) "es" else "en"
                val encoded = URLEncoder.encode(t.replace(" ", "_"), "UTF-8")
                val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"
                val resp = BaseEngine.executeGetRequest(url)
                val json = JSONObject(resp)
                val pageTitle = json.optString("title", "")
                val extract = json.optString("extract", "").trim()

                // Validate reasonable match
                if (extract.length > 30 && !extract.contains("referirse a", ignoreCase = true) && !extract.contains("may refer to", ignoreCase = true)) {
                    val normQuery = normalizeForMatching(query)
                    val normPage = normalizeForMatching(pageTitle)
                    if (normPage.contains(normQuery) || normQuery.contains(normPage)) {
                        return@withContext if (isSpanish) extract else translateToSpanishIfNeeded(extract)
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    private suspend fun translateToSpanishIfNeeded(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || text == "Sin sinopsis disponible." || text == "N/A") return@withContext text
        
        // If the text is obviously already in Spanish, return it
        val lower = text.lowercase()
        val spanishIndicators = listOf(" y ", " de ", " la ", " el ", " en ", " un ", " una ", " que ", " por ", " para ", " con ", " película ", " cuenta la historia ")
        var spanishScore = 0
        for (indicator in spanishIndicators) {
            if (lower.contains(indicator)) spanishScore++
        }
        if (spanishScore >= 3) {
            return@withContext text
        }

        try {
            val safeText = if (text.length > 1000) text.take(1000) else text
            val encodedText = URLEncoder.encode(safeText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=es&dt=t&q=$encodedText"
            val response = BaseEngine.executeGetRequest(url)
            val mainArray = org.json.JSONArray(response)
            val sb = java.lang.StringBuilder()
            if (mainArray.length() > 0) {
                val firstNested = mainArray.optJSONArray(0)
                if (firstNested != null) {
                    for (i in 0 until firstNested.length()) {
                        val group = firstNested.optJSONArray(i)
                        if (group != null && group.length() > 0) {
                            sb.append(group.optString(0, ""))
                        }
                    }
                }
            }
            val translated = sb.toString().trim()
            if (translated.isNotBlank()) return@withContext translated
        } catch (e: Exception) {
            Log.e(TAG, "Failed to translate overview to Spanish: ${e.message}")
        }
        return@withContext text
    }

    data class SeasonEpisode(val season: Int, val episode: Int)

    fun cleanSeriesName(title: String): String {
        if (title.isBlank()) return ""
        var name = title.trim()
        name = name.replace(videoExtensionsRegex, "")

        // Strip bracket prefixes like [ES] [1080p] (LAT)
        var matched = true
        var loops = 0
        while (matched && loops < 5) {
            loops++
            val before = name
            name = name.replace(bracketPrefixRegex, "").trim()
            matched = name != before
        }

        // Strip delimited prefixes like "ES:", "ES -", "LAT |", "SERIES -"
        name = stripDelimitedPrefix(name)

        name = name.replace(Regex("^[\\s:|\\-_/•#+]+"), "").trim()

        // Season and episode patterns to cut off metadata after series title
        val sEPattern1 = Regex("(?i)\\bS\\d+\\s*[-._]?\\s*E\\d+\\b.*")
        val sEPattern2 = Regex("\\b\\d+x\\d+\\b.*")
        val sPattern = Regex("(?i)\\b(?:Temporada|Season|Temp|T)\\s*\\d+.*")
        val ePattern = Regex("(?i)\\b(?:Episodio|Capitulo|Episode|Cap|Ep)\\s*\\d+.*")

        name = name.replace(sEPattern1, "")
        name = name.replace(sEPattern2, "")
        name = name.replace(sPattern, "")
        name = name.replace(ePattern, "")

        name = name.replace(Regex("\\((?:19\\d\\d|20\\d\\d)\\)"), "")
        name = name.replace(Regex("\\[.*?\\]"), " ")
        name = name.replace(Regex("\\(.*?\\)"), " ")
        name = name.replace(Regex("[.\\-_/|+]"), " ")
        name = name.replace(Regex("\\s+"), " ").trim()

        // Check for duplicate half (e.g. "CH Ching : CH Ching" -> "CH Ching")
        if (name.contains(":")) {
            val parts = name.split(":").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size == 2 && parts[0].equals(parts[1], ignoreCase = true)) {
                name = parts[0]
            }
        }

        if (name.length < 2) {
            val fallback = cleanTitleAndExtractYear(title).title
            if (fallback.isNotBlank()) name = fallback
        }

        return name.trim()
    }

    fun extractSeasonEpisode(title: String): SeasonEpisode? {
        val pair = com.example.motor.subtitles.SubtitleMatcher.extractSeasonEpisode(title) ?: return null
        val s = pair.first ?: return null
        val e = pair.second ?: return null
        return SeasonEpisode(s, e)
    }

    suspend fun findImdbId(title: String, isSeries: Boolean): String? {
        val cleanedResult = cleanTitleAndExtractYear(title)
        val cleanedTitleFromBoth = cleanedResult.title
        val cleanedTitleFromSeries = cleanSeriesName(title)
        val extractedYear = cleanedResult.year
        
        return withContext(Dispatchers.IO) {
            val queryTerms = mutableListOf<Pair<String, Int?>>()
            
            // 1. Pristine clean title from cleanTitleAndExtractYear
            if (cleanedTitleFromBoth.isNotEmpty()) {
                queryTerms.add(Pair(cleanedTitleFromBoth, extractedYear))
                if (extractedYear != null) {
                    queryTerms.add(Pair(cleanedTitleFromBoth, null))
                }
            }
            
            // 2. Cleaned title from series cleaning as fallback
            if (cleanedTitleFromSeries.isNotEmpty() && cleanedTitleFromSeries != cleanedTitleFromBoth) {
                queryTerms.add(Pair(cleanedTitleFromSeries, extractedYear))
                if (extractedYear != null) {
                    queryTerms.add(Pair(cleanedTitleFromSeries, null))
                }
            }
            
            // 3. Simple title as final fallback
            val simpleTitle = cleanSimple(title)
            if (simpleTitle.isNotEmpty() && simpleTitle != cleanedTitleFromBoth && simpleTitle != cleanedTitleFromSeries) {
                queryTerms.add(Pair(simpleTitle, null))
            }

            val uniqueQueries = queryTerms.distinct()
            if (uniqueQueries.isEmpty()) return@withContext null

            // Try the main category endpoint first, then the other as fallback
            val endpointsToTry = if (isSeries) {
                listOf("tv", "movie")
            } else {
                listOf("movie", "tv")
            }

            for (apiKey in TMDB_KEYS) {
                try {
                    for (endpoint in endpointsToTry) {
                        for ((queryText, yearVal) in uniqueQueries) {
                            val encodedTitle = URLEncoder.encode(queryText, "UTF-8")
                            var url = "https://api.themoviedb.org/3/search/$endpoint?api_key=$apiKey&query=$encodedTitle"
                            if (yearVal != null) {
                                url += if (endpoint == "tv") {
                                    "&first_air_date_year=$yearVal"
                                } else {
                                    "&year=$yearVal"
                                }
                            }
                            
                            val responseJson = BaseEngine.executeGetRequest(url, API_HEADERS)
                            val jsonObject = JSONObject(responseJson)
                            val results = jsonObject.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                val firstResult = results.getJSONObject(0)
                                val tmdbId = firstResult.optInt("id", -1)
                                if (tmdbId != -1) {
                                    val extUrl = if (endpoint == "tv") {
                                        "https://api.themoviedb.org/3/tv/$tmdbId/external_ids?api_key=$apiKey"
                                    } else {
                                        "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$apiKey"
                                    }
                                    val extResponse = BaseEngine.executeGetRequest(extUrl, API_HEADERS)
                                    val extJson = JSONObject(extResponse)
                                    val imdb = extJson.optString("imdb_id", "")
                                    if (imdb.isNotEmpty() && imdb != "null") {
                                        Log.d(TAG, "Resolved IMDb ID '$imdb' via endpoint '$endpoint' for query '$queryText'")
                                        return@withContext imdb
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TMDb API IMDb search failed for key $apiKey: ${e.message}")
                }
            }
            null
        }
    }

    /**
     * Specialized metadata/synopsis flow for Xtream content.
     * 1. Check cache first.
     * 2. If item is Xtream (has host, user, pass and stream_id/series_id), call player_api.php?action=get_vod_info (movies) or get_series_info (series).
     * 3. Directly map plot, cast, director, genre, releaseDate, duration, rating, backdrop, poster into MediaDetails.
     * 4. Only if plot is null/empty or placeholder, fallback to TmdbService.fetchMediaDetails().
     * 5. Emit XTREAM_METADATA logs indicating the source.
     */
    suspend fun fetchXtreamMediaDetails(
        item: com.example.motor.db.IptvItem,
        activePlaylist: com.example.motor.config.Playlist?,
        isSeries: Boolean
    ): MediaDetails {
        val title = item.name
        val defaultLogo = item.logo

        // 1. Check existing cache
        getCachedMediaDetails(title, isSeries)?.let { cached ->
            Log.d("XTREAM_METADATA", "Source: CACHE | Title: $title | isSeries: $isSeries")
            return cached
        }

        // Determine if playlist is Xtream or M3U convertible
        var host = activePlaylist?.host ?: ""
        var port = activePlaylist?.port?.ifBlank { "80" } ?: "80"
        var user = activePlaylist?.user ?: ""
        var pass = activePlaylist?.pass ?: ""

        val xtreamSource = com.example.motor.source.XtreamSource()
        if ((host.isBlank() || user.isBlank() || pass.isBlank()) && activePlaylist != null && activePlaylist.url.isNotBlank()) {
            val creds = xtreamSource.resolveCredentials(activePlaylist.url)
            if (creds != null) {
                host = creds.host
                port = creds.port.ifBlank { "80" }
                user = creds.user
                pass = creds.pass
            }
        }

        val isXtream = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

        if (isXtream && activePlaylist != null) {
            try {
                if (!isSeries) {
                    // Movie / VOD
                    val vodId = extractVodId(item)
                    if (!vodId.isNullOrBlank()) {
                        val vodInfoMap = xtreamSource.fetchVodInfo(
                            host = host,
                            port = port,
                            user = user,
                            pass = pass,
                            vodId = vodId
                        )
                        val infoObj = vodInfoMap?.get("info") as? Map<*, *> ?: vodInfoMap
                        if (infoObj != null) {
                            val plot = (infoObj["plot"] ?: infoObj["description"] ?: infoObj["overview"])?.toString()?.trim()
                            if (!plot.isNullOrBlank() && !plot.equals("null", ignoreCase = true) && plot != "Sin sinopsis disponible.") {
                                val cast = (infoObj["cast"] ?: infoObj["actors"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val director = infoObj["director"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val genreStr = (infoObj["genre"] ?: infoObj["genres"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val genreList = genreStr?.split(",", "/", ";", "•", "|")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                                val releaseDate = (infoObj["releasedate"] ?: infoObj["release_date"] ?: infoObj["releaseDate"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val extractedYear = releaseDate?.take(4)?.toIntOrNull() ?: item.year
                                
                                val durationSec = (infoObj["duration_secs"] ?: infoObj["duration"])?.toString()?.toIntOrNull()
                                val durationMin = (infoObj["duration"]?.toString()?.filter { it.isDigit() }?.toIntOrNull())?.let {
                                    if (it > 300) it / 60 else it
                                } ?: durationSec?.let { it / 60 }

                                val ratingDouble = (infoObj["rating"] ?: infoObj["rating_5based"])?.toString()?.toDoubleOrNull()
                                val movieImage = (infoObj["movie_image"] ?: infoObj["cover_big"] ?: infoObj["stream_icon"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val backdropList = infoObj["backdrop_path"] as? List<*>
                                val backdropStr = backdropList?.firstOrNull()?.toString()
                                    ?: infoObj["backdrop_path"]?.toString()?.takeIf { !it.startsWith("[") && it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                    ?: infoObj["cover_big"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

                                val xtreamDetails = MediaDetails(
                                    title = (infoObj["name"] ?: title).toString(),
                                    overview = plot,
                                    posterUrl = movieImage ?: defaultLogo,
                                    backdropUrl = backdropStr,
                                    releaseDate = releaseDate,
                                    year = extractedYear,
                                    voteAverage = ratingDouble,
                                    genres = genreList,
                                    runtime = durationMin,
                                    numberOfSeasons = null,
                                    tmdbId = infoObj["tmdb_id"]?.toString()?.toIntOrNull(),
                                    cast = cast,
                                    director = director
                                )

                                Log.d("XTREAM_METADATA", "Source: XTREAM (get_vod_info) | Title: $title | Plot: ${plot.take(50)}...")
                                putCachedMediaDetails(title, isSeries = false, details = xtreamDetails)
                                return xtreamDetails
                            }
                        }
                    }
                } else {
                    // Series
                    val seriesId = extractSeriesId(item)
                    if (!seriesId.isNullOrBlank()) {
                        val seriesInfoMap = xtreamSource.fetchSeriesInfo(
                            host = host,
                            port = port,
                            user = user,
                            pass = pass,
                            seriesId = seriesId
                        )
                        val infoObj = seriesInfoMap?.get("info") as? Map<*, *> ?: seriesInfoMap
                        if (infoObj != null) {
                            val plot = (infoObj["plot"] ?: infoObj["description"] ?: infoObj["overview"])?.toString()?.trim()
                            if (!plot.isNullOrBlank() && !plot.equals("null", ignoreCase = true) && plot != "Sin sinopsis disponible.") {
                                val cast = (infoObj["cast"] ?: infoObj["actors"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val director = infoObj["director"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val genreStr = (infoObj["genre"] ?: infoObj["genres"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val genreList = genreStr?.split(",", "/", ";", "•", "|")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                                val releaseDate = (infoObj["releaseDate"] ?: infoObj["release_date"] ?: infoObj["releasedate"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val extractedYear = releaseDate?.take(4)?.toIntOrNull() ?: item.year
                                
                                val ratingDouble = (infoObj["rating"] ?: infoObj["rating_5based"])?.toString()?.toDoubleOrNull()
                                val cover = (infoObj["cover"] ?: infoObj["cover_big"])?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                val backdropList = infoObj["backdrop_path"] as? List<*>
                                val backdropStr = backdropList?.firstOrNull()?.toString()
                                    ?: infoObj["backdrop_path"]?.toString()?.takeIf { !it.startsWith("[") && it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                    ?: infoObj["cover_big"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

                                val seasonsMap = seriesInfoMap?.get("seasons") as? List<*>
                                val numSeasons = seasonsMap?.size ?: infoObj["season_count"]?.toString()?.toIntOrNull()

                                val xtreamDetails = MediaDetails(
                                    title = (infoObj["name"] ?: title).toString(),
                                    overview = plot,
                                    posterUrl = cover ?: defaultLogo,
                                    backdropUrl = backdropStr,
                                    releaseDate = releaseDate,
                                    year = extractedYear,
                                    voteAverage = ratingDouble,
                                    genres = genreList,
                                    runtime = null,
                                    numberOfSeasons = numSeasons,
                                    tmdbId = infoObj["tmdb_id"]?.toString()?.toIntOrNull(),
                                    cast = cast,
                                    director = director
                                )

                                Log.d("XTREAM_METADATA", "Source: XTREAM (get_series_info) | Title: $title | Plot: ${plot.take(50)}...")
                                putCachedMediaDetails(title, isSeries = true, details = xtreamDetails)
                                return xtreamDetails
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("XTREAM_METADATA", "Error fetching Xtream info for $title: ${e.message}")
            }
        }

        // Fallback to TMDb / OMDb / Wikipedia
        Log.d("XTREAM_METADATA", "Source: TMDB (Fallback) | Title: $title | isSeries: $isSeries")
        val tmdbDetails = fetchMediaDetails(title = title, isSeries = isSeries, defaultLogo = defaultLogo)
        return tmdbDetails
    }

    private fun extractVodId(item: com.example.motor.db.IptvItem): String? {
        val url = item.url ?: return null
        // Matches .../movie/user/pass/{id}.{ext}
        val match = Regex("""/movie/[^/]+/[^/]+/([^/.]+)""").find(url)
        if (match != null) {
            return match.groupValues[1]
        }
        // Matches stream_id query param if present
        val paramMatch = Regex("""stream_id=([^&]+)""").find(url)
        if (paramMatch != null) {
            return paramMatch.groupValues[1]
        }
        val idMatch = Regex("""[?&]id=([^&]+)""").find(url)
        return idMatch?.groupValues?.get(1)
    }

    private fun extractSeriesId(item: com.example.motor.db.IptvItem): String? {
        if (!item.seriesId.isNullOrBlank()) {
            return item.seriesId
        }
        val url = item.url ?: return null
        val match = Regex("""/series/[^/]+/[^/]+/([^/.]+)""").find(url)
        if (match != null) {
            return match.groupValues[1]
        }
        val paramMatch = Regex("""series_id=([^&]+)""").find(url)
        if (paramMatch != null) {
            return paramMatch.groupValues[1]
        }
        val idMatch = Regex("""[?&]id=([^&]+)""").find(url)
        return idMatch?.groupValues?.get(1)
    }
}
