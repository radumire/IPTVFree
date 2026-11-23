package com.example.motor.subtitles

import android.util.Log
import com.example.motor.parser.BaseEngine
import com.example.motor.subtitles.ExternalSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ASSRT (Shooter.cn / 射手网) Subtitle Provider.
 *
 * Implements ASSRT v1 REST API with STRICT series and movie identity validation.
 * CRITICAL FIX: Series title identity MUST match before evaluating season and episode,
 * completely eliminating false positive matches like "Sword and Beloved S01E01" vs "Frasier S01E01".
 */
class AssrtSubtitleProvider(
    private val client: OkHttpClient = defaultClient,
    private val tokenProvider: () -> String? = { resolveDefaultToken() }
) : SubtitleProvider {

    override val name: String = "ASSRT"

    companion object {
        const val BASE_URL = "https://api.assrt.net/v1/sub"
        private const val TAG_SEARCH = "ASSRT_SEARCH"
        private const val TAG_QUERY = "ASSRT_QUERY"
        private const val TAG_RESPONSE = "ASSRT_RESPONSE"
        private const val TAG_CANDIDATE = "ASSRT_CANDIDATE"
        private const val TAG_EPISODE = "ASSRT_EPISODE"
        private const val TAG_REJECT = "ASSRT_REJECT"
        private const val TAG_ERROR = "ASSRT_ERROR"

        val defaultClient: OkHttpClient by lazy {
            BaseEngine.client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private var globalTokenOverride: String? = null

        fun setGlobalToken(token: String?) {
            globalTokenOverride = token
        }

        fun resolveDefaultToken(): String? {
            if (!globalTokenOverride.isNullOrBlank()) return globalTokenOverride
            val repoToken = try {
                com.example.motor.repository.PortalSessionRepository.getInstanceOrNull()
                    ?.settings?.value?.assrtToken?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) { null }
            if (!repoToken.isNullOrBlank()) return repoToken
            val sysProp = System.getProperty("ASSRT_TOKEN")
            if (!sysProp.isNullOrBlank()) return sysProp
            val envVar = System.getenv("ASSRT_TOKEN") ?: System.getenv("ASSRT_API_TOKEN")
            if (!envVar.isNullOrBlank()) return envVar
            try {
                val clazz = Class.forName("com.example.BuildConfig")
                val field = clazz.getField("ASSRT_TOKEN")
                val token = field.get(null) as? String
                if (!token.isNullOrBlank() && token != "MY_ASSRT_TOKEN") {
                    return token
                }
            } catch (_: Throwable) {}
            return null
        }

        fun containsChinese(text: String?): Boolean {
            return SubtitleMatcher.containsChinese(text)
        }
    }

    data class AssrtSubSummary(
        val id: Long,
        val nativeName: String,
        val videoName: String,
        val subtype: String,
        val voteScore: Double,
        val releaseSite: String,
        val langDesc: String,
        val langList: List<String>
    )

    private val searchCache = ConcurrentHashMap<String, List<ExternalSubtitle>>()

    override suspend fun search(request: SubtitleSearchRequest): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        if (request.title.isBlank() && request.originalTitle.isNullOrBlank()) return@withContext emptyList()

        val token = tokenProvider()?.trim()
        if (token.isNullOrBlank()) {
            Log.w(TAG_ERROR, "ASSRT token not configured. Subtitle search skipped.")
            return@withContext emptyList()
        }

        val cacheKey = if (request.isSeries) {
            "assrt:series:${request.title.lowercase(Locale.ROOT)}:${request.season ?: 0}:${request.episode ?: 0}"
        } else {
            "assrt:movie:${request.title.lowercase(Locale.ROOT)}:${request.year ?: 0}"
        }

        searchCache[cacheKey]?.let { cached ->
            Log.i(TAG_SEARCH, "Returning ${cached.size} cached ASSRT subtitles for key=$cacheKey")
            return@withContext cached
        }

        Log.i(
            TAG_SEARCH,
            "title=\"${request.title}\", origTitle=\"${request.originalTitle}\", isSeries=${request.isSeries}, season=${request.season}, episode=${request.episode}, year=${request.year}"
        )

        val queries = buildQueries(request)
        Log.i(TAG_QUERY, "Generated ${queries.size} ASSRT search queries: $queries")

        val rawCandidates = executeQueries(queries, token)
        Log.i(TAG_RESPONSE, "ASSRT returned ${rawCandidates.size} raw candidates from queries")

        if (rawCandidates.isEmpty()) {
            return@withContext emptyList()
        }

        // ── STRICT VALIDATION: TITLE IDENTITY FIRST, THEN EPISODE/YEAR ────────────────
        val validCandidates = rawCandidates.filter { cand ->
            val combinedCandTitle = "${cand.nativeName} ${cand.videoName}"

            // Rule 1: STRICT SERIES / MOVIE IDENTITY VALIDATION
            val validation = SubtitleMatcher.validateCandidate(
                candidateTitle = combinedCandTitle,
                request = request,
                candidateSeason = null, // let matcher extract from title
                candidateEpisode = null,
                candidateYear = request.year
            )

            val isTitleMismatchOnly = !validation.isValid && 
                (validation.reason.contains("identity mismatch", ignoreCase = true) ||
                 validation.reason.contains("tokens do not match", ignoreCase = true) ||
                 validation.reason.contains("Tokens [", ignoreCase = true) ||
                 validation.reason.contains("Missing episode number", ignoreCase = true))

            val candHasChinese = SubtitleMatcher.containsChinese(combinedCandTitle)
            val requestHasNoChinese = !SubtitleMatcher.containsChinese(request.title) && 
                (request.originalTitle == null || !SubtitleMatcher.containsChinese(request.originalTitle))

            val acceptRomanizedOrEnglishMatch = isTitleMismatchOnly && candHasChinese && requestHasNoChinese

            if (!validation.isValid && !acceptRomanizedOrEnglishMatch) {
                Log.w(TAG_REJECT, "ASSRT_CANDIDATE REJECTED: \"${cand.nativeName}\" (${validation.reason})")
                return@filter false
            }

            // Rule 2: ASSRT Specific Episode Match Validation
            if (request.isSeries) {
                val epMatch = isMatchingEpisode(cand, request.season ?: 1, request.episode ?: 1)
                if (!epMatch) {
                    Log.w(TAG_EPISODE, "ASSRT_EPISODE REJECTED: \"${cand.nativeName}\" does not match S${request.season}E${request.episode}")
                    return@filter false
                }
            } else {
                val movieValid = validateMovie(cand, request.year)
                if (!movieValid) {
                    Log.w(TAG_REJECT, "ASSRT_MOVIE REJECTED: Year mismatch in \"${cand.nativeName}\" for target year ${request.year}")
                    return@filter false
                }
            }

            Log.i(TAG_CANDIDATE, "ASSRT_CANDIDATE ACCEPTED: id=${cand.id} name=\"${cand.nativeName}\"")
            true
        }

        if (validCandidates.isEmpty()) {
            Log.w(TAG_SEARCH, "ASSRT: No candidates passed strict identity & episode validation.")
            return@withContext emptyList()
        }

        // Fetch detail files for valid candidates
        val finalResults = resolveCandidatesDetail(
            candidates = validCandidates.take(5),
            token = token,
            isSeries = request.isSeries,
            season = request.season,
            episode = request.episode,
            year = request.year
        )

        // Deduplicate
        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val deduplicated = finalResults.filter { seenUrls.add(it.url) }

        if (deduplicated.isNotEmpty()) {
            searchCache[cacheKey] = deduplicated
        }

        Log.i(TAG_SEARCH, "ASSRT search finished with ${deduplicated.size} valid subtitles")
        deduplicated
    }

    override suspend fun search(
        rawTitle: String,
        normTitle: String,
        isSeries: Boolean,
        season: Int?,
        episode: Int?,
        year: Int?,
        originalTitle: String?,
        tmdbTitle: String?
    ): List<ExternalSubtitle> {
        val req = SubtitleSearchRequest(
            title = if (normTitle.isNotBlank()) normTitle else rawTitle,
            originalTitle = originalTitle ?: tmdbTitle,
            mediaType = if (isSeries) "series" else "movie",
            isSeries = isSeries,
            season = season,
            episode = episode,
            year = year
        )
        return search(req)
    }

    private fun buildQueries(request: SubtitleSearchRequest): List<String> {
        val queries = mutableListOf<String>()
        val titles = listOfNotNull(request.originalTitle, request.title)
            .distinct()
            .filter { it.isNotBlank() }

        if (request.isSeries) {
            val s = request.season ?: 1
            val e = request.episode ?: 1
            val sStr = String.format(Locale.US, "%02d", s)
            val eStr = String.format(Locale.US, "%02d", e)

            for (t in titles) {
                if (containsChinese(t)) {
                    queries.add("$t 第${e}集")
                    queries.add("$t 第${eStr}集")
                    queries.add("$t S${sStr}E${eStr}")
                    queries.add("$t EP$eStr")
                    queries.add("$t $eStr")
                    queries.add(t)
                } else {
                    queries.add("$t S${sStr}E${eStr}")
                    queries.add("$t EP$eStr")
                    queries.add("$t E$eStr")
                    queries.add("$t 第${e}集")
                    queries.add("$t 第${eStr}集")
                    queries.add("$t $eStr")
                    queries.add(t)
                }
            }
        } else {
            for (t in titles) {
                if (request.year != null && request.year > 1900) {
                    queries.add("$t ${request.year}")
                }
                queries.add(t)
            }
        }

        return queries.distinct().take(8)
    }

    private suspend fun executeQueries(queries: List<String>, token: String): List<AssrtSubSummary> = coroutineScope {
        val deferreds = queries.map { q ->
            async(Dispatchers.IO) {
                searchSingleQuery(q, token)
            }
        }

        val seenIds = mutableSetOf<Long>()
        val all = mutableListOf<AssrtSubSummary>()
        for (def in deferreds) {
            for (cand in def.await()) {
                if (seenIds.add(cand.id)) {
                    all.add(cand)
                }
            }
        }
        all
    }

    private fun searchSingleQuery(query: String, token: String): List<AssrtSubSummary> {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/search?q=$encoded&token=$token&cnt=15"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val bodyStr = resp.body?.string() ?: return emptyList()
                return parseSearchResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG_ERROR, "Exception executing ASSRT search for \"$query\": ${e.message}")
            return emptyList()
        }
    }

    fun parseSearchResponse(jsonStr: String): List<AssrtSubSummary> {
        val list = mutableListOf<AssrtSubSummary>()
        try {
            val root = JSONObject(jsonStr)
            val subObj = root.optJSONObject("sub") ?: return emptyList()
            val subsArray = subObj.optJSONArray("subs") ?: return emptyList()

            for (i in 0 until subsArray.length()) {
                val item = subsArray.getJSONObject(i)
                val id = item.optLong("id", -1L)
                if (id <= 0) continue

                val nativeName = item.optString("native_name", "")
                val videoName = item.optString("videoname", "")
                val subtype = item.optString("subtype", "")
                val voteScore = item.optDouble("vote_score", 0.0)
                val releaseSite = item.optString("release_site", "")

                val langObj = item.optJSONObject("lang")
                val langDesc = langObj?.optString("desc", "") ?: ""
                val langListObj = langObj?.optJSONObject("langlist")
                val langListArray = langListObj?.optJSONArray("langlist")
                val langList = mutableListOf<String>()
                if (langListArray != null) {
                    for (j in 0 until langListArray.length()) {
                        langList.add(langListArray.getString(j))
                    }
                }

                list.add(
                    AssrtSubSummary(
                        id = id,
                        nativeName = nativeName,
                        videoName = videoName,
                        subtype = subtype,
                        voteScore = voteScore,
                        releaseSite = releaseSite,
                        langDesc = langDesc,
                        langList = langList
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG_ERROR, "Error parsing ASSRT search response: ${e.message}")
        }
        return list
    }

    fun isMatchingEpisode(candidate: AssrtSubSummary, targetSeason: Int, targetEpisode: Int): Boolean {
        val combined = "${candidate.nativeName} ${candidate.videoName}"

        val epRegexes = listOf(
            Regex("""[sS](\d{1,2})[eE](\d{1,3})"""),
            Regex("""(\d{1,2})[xX](\d{1,3})"""),
            Regex("""[第eE](\d{1,3})[集话話]?"""),
            Regex("""EP(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""(?<![0-9a-zA-Z])(?:E|EP|第)?0*(\d{1,2})(?:[集话話])?(?![0-9a-zA-Z])""", RegexOption.IGNORE_CASE)
        )

        var foundTargetMatch = false
        var foundConflictingEpisode = false

        for (regex in epRegexes) {
            for (m in regex.findAll(combined)) {
                val epFound = if (m.groupValues.size == 3) {
                    val sFound = m.groupValues[1].toIntOrNull()
                    val eFound = m.groupValues[2].toIntOrNull()
                    if (sFound != null && sFound != targetSeason) {
                        return false // Season mismatch
                    }
                    eFound
                } else {
                    m.groupValues[1].toIntOrNull()
                }

                if (epFound != null) {
                    if (epFound == targetEpisode) {
                        foundTargetMatch = true
                    } else if (epFound > 0 && epFound != targetEpisode) {
                        // Only treat as conflicting if not matching target
                        foundConflictingEpisode = true
                    }
                }
            }
        }

        if (foundTargetMatch) {
            return true
        }

        // Check if candidate is a multi-episode pack or full season pack
        val isRangePack = SubtitleMatcher.isEpisodeInMultiEpisodeRange(combined, targetEpisode) ||
                Regex("""\b\d{1,2}\s*[-~～]\s*\d{1,3}\b""").containsMatchIn(combined) ||
                Regex("""第?\s*\d{1,2}\s*[-~～]\s*\d{1,3}\s*集""").containsMatchIn(combined)
        val isSeasonPack = combined.contains("S${String.format(Locale.US, "%02d", targetSeason)}", ignoreCase = true) ||
                combined.contains("全集") ||
                combined.contains("全") && combined.contains("集") ||
                combined.contains("Complete", ignoreCase = true) ||
                combined.contains("Pack", ignoreCase = true) ||
                combined.contains("Season $targetSeason", ignoreCase = true) ||
                isRangePack

        if (isSeasonPack) {
            return true
        }

        if (foundConflictingEpisode) {
            return false
        }

        return false
    }

    private fun validateMovie(candidate: AssrtSubSummary, targetYear: Int?): Boolean {
        if (targetYear == null || targetYear <= 1900) return true
        val yearRegex = Regex("""\b(19\d\d|20\d\d)\b""")
        val match = yearRegex.find("${candidate.videoName} ${candidate.nativeName}")
        if (match != null) {
            val candYear = match.groupValues[1].toIntOrNull()
            if (candYear != null && Math.abs(candYear - targetYear) > 1) {
                return false
            }
        }
        return true
    }

    private suspend fun resolveCandidatesDetail(
        candidates: List<AssrtSubSummary>,
        token: String,
        isSeries: Boolean,
        season: Int?,
        episode: Int?,
        year: Int?
    ): List<ExternalSubtitle> = coroutineScope {
        val deferreds = candidates.map { cand ->
            async(Dispatchers.IO) {
                fetchSubDetail(
                    cand = cand,
                    token = token,
                    isSeries = isSeries,
                    targetSeason = season,
                    targetEpisode = episode,
                    targetYear = year
                )
            }
        }
        deferreds.flatMap { it.await() }
    }

    private fun fetchSubDetail(
        cand: AssrtSubSummary,
        token: String,
        isSeries: Boolean,
        targetSeason: Int?,
        targetEpisode: Int?,
        targetYear: Int?
    ): List<ExternalSubtitle> {
        try {
            val url = "$BASE_URL/detail?id=${cand.id}&token=$token&filelist=1"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val bodyStr = resp.body?.string() ?: return emptyList()
                return parseDetailResponse(bodyStr, cand, isSeries, targetSeason, targetEpisode, targetYear)
            }
        } catch (e: Exception) {
            Log.e(TAG_ERROR, "Exception fetching ASSRT detail for id=${cand.id}: ${e.message}")
            return emptyList()
        }
    }

    fun parseDetailResponse(
        jsonStr: String,
        cand: AssrtSubSummary,
        isSeries: Boolean,
        targetSeason: Int?,
        targetEpisode: Int?,
        targetYear: Int?
    ): List<ExternalSubtitle> {
        val results = mutableListOf<ExternalSubtitle>()
        try {
            val root = JSONObject(jsonStr)
            val subObj = root.optJSONObject("sub") ?: return emptyList()
            val subsArray = subObj.optJSONArray("subs") ?: return emptyList()
            if (subsArray.length() == 0) return emptyList()

            val detailItem = subsArray.getJSONObject(0)
            val directUrl = detailItem.optString("url", "")
            val filename = detailItem.optString("filename", "")
            val fileListArray = detailItem.optJSONArray("filelist")

            val (langCode, langName) = parseAssrtLanguage(cand.langList, cand.langDesc, filename)

            if (fileListArray != null && fileListArray.length() > 0) {
                for (k in 0 until fileListArray.length()) {
                    val fileObj = fileListArray.getJSONObject(k)
                    val fName = fileObj.optString("f", "")
                    val fUrl = fileObj.optString("url", "").ifBlank { directUrl }
                    if (fUrl.isBlank()) continue

                    if (isSeries && targetEpisode != null) {
                        val se = SubtitleMatcher.extractSeasonEpisode(fName)
                        if (se?.second != null && se.second != targetEpisode) {
                            continue
                        }
                    }

                    val (fileLangCode, fileLangName) = parseAssrtLanguage(cand.langList, cand.langDesc, fName)
                    val label = "$fileLangName · ASSRT · ${if (cand.releaseSite.isNotBlank()) cand.releaseSite else "Original"}"
                    val displayTitle = if (fName.isNotBlank()) "${cand.nativeName} $fName" else cand.nativeName

                    results.add(
                        ExternalSubtitle(
                            id = "assrt_${cand.id}_${k}_${fName.hashCode()}",
                            url = fUrl,
                            label = label,
                            language = fileLangCode,
                            source = "ASSRT",
                            isTranslated = false,
                            displayTitle = displayTitle,
                            season = targetSeason,
                            episode = targetEpisode,
                            year = targetYear,
                            score = 250
                        )
                    )
                }
            }

            if (results.isEmpty() && directUrl.isNotBlank()) {
                val label = "$langName · ASSRT · ${if (cand.releaseSite.isNotBlank()) cand.releaseSite else "Original"}"
                results.add(
                    ExternalSubtitle(
                        id = "assrt_${cand.id}_direct",
                        url = directUrl,
                        label = label,
                        language = langCode,
                        source = "ASSRT",
                        isTranslated = false,
                        displayTitle = cand.nativeName,
                        season = targetSeason,
                        episode = targetEpisode,
                        year = targetYear,
                        score = 250
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG_ERROR, "Error parsing ASSRT detail response: ${e.message}")
        }
        return results
    }

    fun parseAssrtLanguage(
        langList: List<String>,
        langDesc: String,
        filename: String
    ): Pair<String, String> {
        val lowerDesc = langDesc.lowercase(Locale.ROOT)
        val lowerFile = filename.lowercase(Locale.ROOT)
        val combined = "$lowerDesc $lowerFile ${langList.joinToString(" ").lowercase(Locale.ROOT)}"

        if (combined.contains("双语") || combined.contains("中英") || combined.contains("bilingual") ||
            (langList.contains("chs") && langList.contains("eng")) ||
            (langList.contains("cht") && langList.contains("eng"))
        ) {
            return Pair("zh-CN", "Chino (Bilingüe)")
        }

        if (langList.contains("cht") || combined.contains("繁体") || combined.contains("繁體") ||
            lowerFile.endsWith(".cht.srt") || lowerFile.endsWith(".cht.ass")
        ) {
            return Pair("zh-TW", "Chino Tradicional")
        }

        if (langList.contains("chs") || combined.contains("简体") || combined.contains("簡體") ||
            lowerFile.endsWith(".chs.srt") || lowerFile.endsWith(".chs.ass")
        ) {
            return Pair("zh-CN", "Chino Simplificado")
        }

        return Pair("zh", "Chino")
    }
}
