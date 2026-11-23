package com.example.motor.subtitles

import android.util.Log
import com.example.motor.parser.BaseEngine
import com.example.motor.subtitles.ExternalSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Modern OpenSubtitles.com REST API v1 Subtitle Provider.
 *
 * Implements:
 * - Priority Level 1: MovieHash search (maximum confidence)
 * - Priority Level 2: Metadata / TMDB / IMDb / title search
 * - Universal search across ANY language (never hard filters query by languages=)
 * - Comprehensive diagnostic parsing without silent drops
 * - Robust multi-file selection with valid file_id
 */
class OpenSubtitlesProvider(
    private val client: OkHttpClient = defaultClient,
    private val apiKeyProvider: () -> String? = { resolveDefaultApiKey() }
) : SubtitleProvider {

    override val name: String = "OpenSubtitles"

    companion object {
        private const val TAG = "OPENSUBTITLES"
        const val BASE_API_URL = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "IPTVPlayer v1.0"

        val defaultClient: OkHttpClient by lazy {
            BaseEngine.client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private var globalApiKeyOverride: String? = null

        fun setGlobalApiKey(key: String?) {
            globalApiKeyOverride = key
        }

        fun resolveDefaultApiKey(): String? {
            if (!globalApiKeyOverride.isNullOrBlank()) return globalApiKeyOverride
            val repoKey = try {
                com.example.motor.repository.PortalSessionRepository.getInstanceOrNull()
                    ?.settings?.value?.openSubtitlesKey?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) { null }
            if (!repoKey.isNullOrBlank()) return repoKey

            val sysProp = System.getProperty("OPENSUBTITLES_API_KEY")
            if (!sysProp.isNullOrBlank()) return sysProp

            val envVar = System.getenv("OPENSUBTITLES_API_KEY") ?: System.getenv("OPENSUBTITLES_KEY")
            if (!envVar.isNullOrBlank()) return envVar

            // Try BuildConfig via reflection safely
            try {
                val clazz = Class.forName("com.example.BuildConfig")
                val field = clazz.getField("OPENSUBTITLES_API_KEY")
                val key = field.get(null) as? String
                if (!key.isNullOrBlank() && key != "MY_OPENSUBTITLES_API_KEY") {
                    return key
                }
            } catch (_: Throwable) {}

            return null
        }

        /**
         * Obtains the direct CDN download URL for a file_id using the OpenSubtitles download endpoint.
         * Never logs the API key.
         */
        fun resolveDownloadLink(fileId: Long, apiKey: String, client: OkHttpClient = defaultClient): String? {
            if (fileId <= 0 || apiKey.isBlank()) return null
            return try {
                val url = "$BASE_API_URL/download"
                Log.i(TAG, "OS_DOWNLOAD_REQUEST file_id=$fileId url=$url")
                val jsonBody = JSONObject().put("file_id", fileId).toString()
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Api-Key", apiKey)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .build()

                val resp = client.newCall(req).execute()
                resp.use { response ->
                    Log.i(TAG, "OS_DOWNLOAD_RESPONSE file_id=$fileId code=${response.code}")
                    if (response.isSuccessful) {
                        val respStr = response.body?.string() ?: return null
                        val obj = JSONObject(respStr)
                        val link = obj.optString("link").takeIf { it.isNotBlank() }
                        if (link != null) {
                            Log.i(TAG, "OS_DOWNLOAD_SUCCESS file_id=$fileId linkFound=true")
                        } else {
                            Log.w(TAG, "OS_DOWNLOAD_EMPTY file_id=$fileId response body had no link property")
                        }
                        link
                    } else {
                        Log.w(TAG, "OS_DOWNLOAD_FAILED file_id=$fileId code=${response.code} msg=${response.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OS_DOWNLOAD_ERROR file_id=$fileId error=${e.message}")
                null
            }
        }
    }

    private val searchCache = ConcurrentHashMap<String, List<ExternalSubtitle>>()

    override suspend fun search(request: SubtitleSearchRequest): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "OpenSubtitles API key not configured. Skipping provider.")
            return@withContext emptyList()
        }

        // Cache is based strictly on content identity, independent of preferred language
        val cacheKey = if (!request.movieHash.isNullOrBlank()) {
            "os:hash:${request.movieHash}"
        } else if (request.isSeries) {
            "os:series:${request.title.lowercase(Locale.ROOT)}:${request.season ?: 0}:${request.episode ?: 0}:${request.tmdbId ?: 0}"
        } else {
            "os:movie:${request.title.lowercase(Locale.ROOT)}:${request.year ?: 0}:${request.tmdbId ?: 0}"
        }

        searchCache[cacheKey]?.let { cached ->
            Log.i(TAG, "Returning ${cached.size} cached OpenSubtitles for key=$cacheKey")
            return@withContext cached
        }

        val results = mutableListOf<ExternalSubtitle>()

        // ── LEVEL 1: Search by MovieHash if available ──────────────────────────────
        if (!request.movieHash.isNullOrBlank()) {
            Log.i(TAG, "OS_HASH_REQUEST moviehash=${request.movieHash}")
            val hashResults = searchByHash(request.movieHash, apiKey, request)
            if (hashResults.isNotEmpty()) {
                Log.i(TAG, "OS_HASH_RESPONSE success candidates=${hashResults.size}")
                results.addAll(hashResults)
            } else {
                Log.i(TAG, "OS_HASH_RESPONSE no candidates found for moviehash=${request.movieHash}. Proceeding to LEVEL 2 metadata.")
            }
        }

        // ── LEVEL 2: Search by Metadata / IDs ──────────────────────────────────────
        if (results.isEmpty()) {
            Log.i(TAG, "OS_METADATA_REQUEST title=\"${request.title}\" isSeries=${request.isSeries} season=${request.season} episode=${request.episode} tmdbId=${request.tmdbId} imdbId=${request.imdbId}")
            val metaResults = searchByMetadata(apiKey, request)
            Log.i(TAG, "OS_METADATA_RESPONSE candidates=${metaResults.size}")
            results.addAll(metaResults)
        }

        if (results.isNotEmpty()) {
            searchCache[cacheKey] = results
        }

        results
    }

    private fun searchByHash(
        hash: String,
        apiKey: String,
        request: SubtitleSearchRequest
    ): List<ExternalSubtitle> {
        return try {
            // Universal search: do NOT send languages= param to retrieve all available languages
            val url = "$BASE_API_URL/subtitles?moviehash=$hash"
            Log.d(TAG, "OpenSubtitles hash query url: $url")
            val jsonStr = executeGetJson(url, apiKey, strategy = "HASH") ?: return emptyList()
            parseSearchResponse(jsonStr, request, apiKey, isHashMatch = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching OpenSubtitles by hash: ${e.message}")
            emptyList()
        }
    }

    private fun searchByMetadata(
        apiKey: String,
        request: SubtitleSearchRequest
    ): List<ExternalSubtitle> {
        return try {
            val queryParams = mutableListOf<String>()
            val queryTitle = if (!request.title.isNullOrBlank()) request.title else request.originalTitle
            val hasId = (request.tmdbId != null && request.tmdbId > 0) || !request.imdbId.isNullOrBlank()

            if (request.isSeries) {
                queryParams.add("type=episode")
                if (request.season != null && request.season > 0) queryParams.add("season_number=${request.season}")
                if (request.episode != null && request.episode > 0) queryParams.add("episode_number=${request.episode}")
            } else {
                queryParams.add("type=movie")
                if (request.year != null && request.year > 1900) {
                    queryParams.add("year=${request.year}")
                }
            }

            if (request.tmdbId != null && request.tmdbId > 0) {
                if (request.isSeries) {
                    queryParams.add("parent_tmdb_id=${request.tmdbId}")
                } else {
                    queryParams.add("tmdb_id=${request.tmdbId}")
                }
            }
            if (!request.imdbId.isNullOrBlank()) {
                val cleaned = request.imdbId.removePrefix("tt").filter { it.isDigit() }
                if (cleaned.isNotBlank()) {
                    queryParams.add("imdb_id=$cleaned")
                }
            }

            // Only add text query if no ID is present, or for movies where query complements the search
            if (!queryTitle.isNullOrBlank() && (!hasId || !request.isSeries)) {
                val encodedQuery = URLEncoder.encode(queryTitle, "UTF-8")
                queryParams.add("query=$encodedQuery")
            }

            val url = "$BASE_API_URL/subtitles?${queryParams.joinToString("&")}"
            Log.d(TAG, "OpenSubtitles metadata query url: $url")
            val jsonStr = executeGetJson(url, apiKey, strategy = "METADATA") ?: return emptyList()
            parseSearchResponse(jsonStr, request, apiKey, isHashMatch = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching OpenSubtitles by metadata: ${e.message}")
            emptyList()
        }
    }

    fun parseSearchResponse(
        jsonStr: String,
        request: SubtitleSearchRequest,
        apiKey: String,
        isHashMatch: Boolean
    ): List<ExternalSubtitle> {
        val strategy = if (isHashMatch) "HASH" else "METADATA"
        val root = JSONObject(jsonStr)
        val dataArray = root.optJSONArray("data")
        val dataCount = dataArray?.length() ?: 0
        Log.i(TAG, "SUBTITLE_RESPONSE strategy=$strategy dataCount=$dataCount")

        if (dataArray == null || dataCount == 0) {
            Log.i(TAG, "SUBTITLE_CANDIDATES strategy=$strategy accepted=0 rejected=0")
            return emptyList()
        }

        val candidates = mutableListOf<ExternalSubtitle>()
        var acceptedCount = 0
        var rejectedCount = 0

        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i)
            if (item == null) {
                rejectedCount++
                Log.w(TAG, "OS_ITEM_REJECTED idx=$i reason=null_item")
                continue
            }

            val subtitleId = item.optString("id", "")
            val attributes = item.optJSONObject("attributes")
            if (attributes == null) {
                rejectedCount++
                Log.w(TAG, "OS_ITEM_REJECTED idx=$i subtitleId=$subtitleId reason=missing_attributes")
                continue
            }

            val filesArray = attributes.optJSONArray("files")
            if (filesArray == null) {
                rejectedCount++
                Log.w(TAG, "OS_ITEM_REJECTED idx=$i subtitleId=$subtitleId reason=missing_files_array")
                continue
            }

            if (filesArray.length() == 0) {
                rejectedCount++
                Log.w(TAG, "OS_ITEM_REJECTED idx=$i subtitleId=$subtitleId reason=empty_files_array")
                continue
            }

            // Robust multi-file selection: iterate files, prefer valid file_id, prefer .srt/.vtt
            var chosenFileId: Long? = null
            var chosenFileName = ""

            for (fIdx in 0 until filesArray.length()) {
                val fObj = filesArray.optJSONObject(fIdx) ?: continue
                val fId = fObj.optLong("file_id", -1L)
                val fName = fObj.optString("file_name", "")
                if (fId > 0) {
                    if (chosenFileId == null) {
                        chosenFileId = fId
                        chosenFileName = fName
                    } else if (fName.endsWith(".srt", ignoreCase = true) && !chosenFileName.endsWith(".srt", ignoreCase = true)) {
                        chosenFileId = fId
                        chosenFileName = fName
                    }
                }
            }

            if (chosenFileId == null || chosenFileId <= 0) {
                rejectedCount++
                Log.w(TAG, "OS_ITEM_NO_FILE_ID idx=$i subtitleId=$subtitleId reason=no_valid_file_id_in_files")
                continue
            }

            val fileId = chosenFileId
            val fileName = chosenFileName
            val rawLang = attributes.optString("language", "und")
            val featureDetails = attributes.optJSONObject("feature_details")
            val featureTitle = featureDetails?.optString("title", "") ?: ""
            val parentTitle = featureDetails?.optString("parent_title", "") ?: ""
            val candYear = featureDetails?.optInt("year", 0).takeIf { it != null && it > 0 }
            val candSeason = featureDetails?.optInt("season_number", 0).takeIf { it != null && it > 0 }
            val candEpisode = featureDetails?.optInt("episode_number", 0).takeIf { it != null && it > 0 }

            val candidateTitle = when {
                parentTitle.isNotBlank() -> "$parentTitle $featureTitle $fileName"
                featureTitle.isNotBlank() -> "$featureTitle $fileName"
                else -> fileName
            }

            // ── Hard Rejection Check (Must Pass) ───────────────────────────────────
            val validation = SubtitleMatcher.validateCandidate(
                candidateTitle = candidateTitle,
                request = request,
                candidateSeason = candSeason,
                candidateEpisode = candEpisode,
                candidateYear = candYear
            )

            val isTitleMismatchOnly = !validation.isValid && 
                (validation.reason.contains("identity mismatch", ignoreCase = true) ||
                 validation.reason.contains("tokens do not match", ignoreCase = true) ||
                 validation.reason.contains("Tokens [", ignoreCase = true) ||
                 validation.reason.contains("Missing episode number", ignoreCase = true))

            val candHasChinese = SubtitleMatcher.containsChinese(candidateTitle)
            val requestHasNoChinese = !SubtitleMatcher.containsChinese(request.title) && 
                (request.originalTitle == null || !SubtitleMatcher.containsChinese(request.originalTitle))

            val isEpisodeOrYearMatch = if (request.isSeries) {
                (candSeason == null || request.season == null || candSeason == request.season) &&
                (candEpisode == null || request.episode == null || candEpisode == request.episode)
            } else {
                (candYear == null || request.year == null || Math.abs(candYear - request.year) <= 1)
            }

            val acceptRomanizedOrEnglishMatch = isTitleMismatchOnly && candHasChinese && requestHasNoChinese && isEpisodeOrYearMatch

            if (!validation.isValid && !isHashMatch && !acceptRomanizedOrEnglishMatch) {
                rejectedCount++
                Log.w(TAG, "OS_ITEM_REJECTED idx=$i subtitleId=$subtitleId file_id=$fileId candidate=\"$candidateTitle\" reason=${validation.reason}")
                continue
            }

            acceptedCount++
            val canonicalLang = SubtitleLanguage.normalizeLanguageCode(rawLang, fileName)
            val langName = SubtitleLanguage.getDisplayName(canonicalLang)

            // CRITICAL: subtitle id != file_id. ExternalSubtitle.id is strictly based on file_id: "os_$fileId"
            val subId = "os_$fileId"
            val downloadUrl = "https://api.opensubtitles.com/api/v1/download?file_id=$fileId"
            val displayTitle = when {
                parentTitle.isNotBlank() && featureTitle.isNotBlank() -> "$parentTitle $featureTitle"
                parentTitle.isNotBlank() -> parentTitle
                featureTitle.isNotBlank() -> featureTitle
                else -> fileName
            }
            val label = "$langName · OpenSubtitles${if (isHashMatch) " (Hash)" else ""}"

            Log.i(
                TAG,
                "OS_ITEM_ACCEPTED idx=$i subtitleId=$subtitleId file_id=$fileId lang=$canonicalLang title=\"$displayTitle\" strategy=$strategy"
            )

            val extSub = ExternalSubtitle(
                id = subId,
                url = downloadUrl,
                label = label,
                language = canonicalLang,
                source = "OpenSubtitles",
                isTranslated = false,
                displayTitle = displayTitle,
                season = candSeason ?: request.season,
                episode = candEpisode ?: request.episode,
                year = candYear ?: request.year,
                score = if (isHashMatch) 10000 else 500,
                fileId = fileId,
                searchStrategy = strategy
            )

            candidates.add(extSub)
        }

        Log.i(TAG, "SUBTITLE_CANDIDATES strategy=$strategy accepted=$acceptedCount rejected=$rejectedCount")

        val byLanguage = candidates.groupBy { it.language }
        val adjustedCandidates = mutableListOf<ExternalSubtitle>()
        for ((_, list) in byLanguage) {
            if (list.size == 1) {
                adjustedCandidates.add(list[0])
            } else {
                list.forEachIndexed { idx, sub ->
                    val newLabel = if (sub.label.contains(" · OpenSubtitles")) {
                        sub.label.replace(" · OpenSubtitles", " #${idx + 1} · OpenSubtitles")
                    } else {
                        "${sub.label} #${idx + 1}"
                    }
                    adjustedCandidates.add(sub.copy(label = newLabel))
                }
            }
        }

        return adjustedCandidates
    }

    private fun executeGetJson(url: String, apiKey: String, strategy: String = "METADATA"): String? {
        val req = Request.Builder()
            .url(url)
            .header("Api-Key", apiKey)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        val resp = client.newCall(req).execute()
        resp.use { response ->
            if (response.isSuccessful) {
                return response.body?.string()
            } else {
                Log.w(TAG, "SUBTITLE_HTTP_ERROR strategy=$strategy code=${response.code}")
                return null
            }
        }
    }

    /**
     * Preserved helper for mapping language codes to API formats when an explicit language query is requested.
     */
    fun getApiLanguages(preferredLang: String): String {
        val norm = SubtitleLanguage.normalizeLanguageCode(preferredLang)
        return when (norm) {
            SubtitleLanguage.LANG_ES, SubtitleLanguage.LANG_ES_419 -> "es"
            SubtitleLanguage.LANG_RO -> "ro"
            SubtitleLanguage.LANG_EN -> "en"
            SubtitleLanguage.LANG_ZH_CN -> "zh-cn"
            SubtitleLanguage.LANG_ZH_TW -> "zh-tw"
            SubtitleLanguage.LANG_ZH_BILINGUAL -> "ze"
            else -> norm.lowercase(Locale.ROOT)
        }
    }
}
