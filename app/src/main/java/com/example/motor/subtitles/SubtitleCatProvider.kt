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
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SubtitleCat provider implementation.
 *
 * Implements strict search and extraction:
 * buscar → extraer → identificar → validar → aceptar/rechazar.
 *
 * CRITICAL: Zero fallback to unvalidated raw candidates.
 */
class SubtitleCatProvider(
    private val client: OkHttpClient = defaultClient
) : SubtitleProvider {

    override val name: String = "SubtitleCat"

    companion object {
        private const val TAG = "SUBTITLECAT"
        const val BASE_URL = "https://www.subtitlecat.com"

        val defaultClient: OkHttpClient by lazy {
            BaseEngine.client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private val subcatHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,ro;q=0.8,en;q=0.7"
        )

        fun extractCandidatePages(html: String): List<SubcatCandidate> {
            val candidates = mutableListOf<SubcatCandidate>()
            val anchorRegex = Regex("""<a\s+[^>]*href=["']((?:https?://(?:www\.)?subtitlecat\.com)?/?subs/[^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)

            for (m in anchorRegex.findAll(html)) {
                val rawPath = m.groupValues[1].trim()
                if (rawPath.isBlank()) continue
                val lower = rawPath.lowercase(Locale.ROOT)
                if (lower.contains("search") || lower.contains("privacy") || lower.contains("contact") ||
                    lower.contains("login") || lower.contains("register") || lower.contains("dmca") ||
                    lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png") || lower.endsWith(".jpg")
                ) {
                    continue
                }

                val fullUrl = if (rawPath.startsWith("http")) rawPath else "$BASE_URL/${rawPath.removePrefix("/")}"
                val rawText = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                val pathName = rawPath.substringAfterLast("/").removeSuffix(".html").replace(Regex("[._-]"), " ").trim()
                val title = if (rawText.isNotBlank() && !rawText.equals("download", true)) rawText else pathName

                val se = SubtitleMatcher.extractSeasonEpisode(title) ?: SubtitleMatcher.extractSeasonEpisode(rawPath)
                val yr = SubtitleMatcher.extractYear(title) ?: SubtitleMatcher.extractYear(rawPath)

                candidates.add(
                    SubcatCandidate(
                        pageUrl = fullUrl,
                        candidateTitle = title,
                        season = se?.first,
                        episode = se?.second,
                        year = yr
                    )
                )
                if (candidates.size >= 20) break
            }
            return candidates
        }

        fun extractLanguageEntries(
            html: String,
            pageUrl: String = "",
            pageTitle: String = "",
            season: Int? = null,
            episode: Int? = null
        ): List<SubcatLangEntry> {
            val entries = mutableListOf<SubcatLangEntry>()
            val seenUrls = mutableSetOf<String>()

            // 0. Modern SubtitleCat download links: <a id="download_es" ... href="..."> or <a href="..." ... id="download_es">
            val modernDlRegex1 = Regex(
                """<a\s+[^>]*id=["']download_([a-zA-Z0-9_\-]+)["'][^>]*href=["']([^"']+)["'][^>]*>""",
                RegexOption.IGNORE_CASE
            )
            val modernDlRegex2 = Regex(
                """<a\s+[^>]*href=["']([^"']+)["'][^>]*id=["']download_([a-zA-Z0-9_\-]+)["'][^>]*>""",
                RegexOption.IGNORE_CASE
            )
            for (m in modernDlRegex1.findAll(html)) {
                val langTag = m.groupValues[1].trim()
                val rawUrl = m.groupValues[2].trim()
                if (rawUrl.isBlank()) continue
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$BASE_URL/${rawUrl.removePrefix("/")}"

                if (seenUrls.add(fullUrl)) {
                    val lang = SubtitleLanguage.normalizeLanguageCode(langTag, fullUrl)
                    entries.add(
                        SubcatLangEntry(
                            language = lang,
                            downloadUrl = fullUrl,
                            titleOrDesc = pageTitle,
                            season = season,
                            episode = episode
                        )
                    )
                }
            }
            for (m in modernDlRegex2.findAll(html)) {
                val rawUrl = m.groupValues[1].trim()
                val langTag = m.groupValues[2].trim()
                if (rawUrl.isBlank()) continue
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$BASE_URL/${rawUrl.removePrefix("/")}"

                if (seenUrls.add(fullUrl)) {
                    val lang = SubtitleLanguage.normalizeLanguageCode(langTag, fullUrl)
                    entries.add(
                        SubcatLangEntry(
                            language = lang,
                            downloadUrl = fullUrl,
                            titleOrDesc = pageTitle,
                            season = season,
                            episode = episode
                        )
                    )
                }
            }

            // 0b. Direct subtitle file links in detail page: <a ... href=".../subs/...-(lang).srt">
            val directSubLinkRegex = Regex(
                """<a\s+[^>]*href=["']((?:https?://(?:www\.)?subtitlecat\.com)?/?subs/[^"']+\.(?:srt|vtt|sub))["'][^>]*>(.*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (m in directSubLinkRegex.findAll(html)) {
                val rawUrl = m.groupValues[1].trim()
                val text = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$BASE_URL/${rawUrl.removePrefix("/")}"

                if (seenUrls.add(fullUrl)) {
                    // Extract language tag from URL if possible (e.g., Inception-es.srt -> es)
                    val langHint = Regex("""-([a-zA-Z0-9_\-]+)\.(?:srt|vtt|sub)$""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.get(1) ?: text
                    val lang = SubtitleLanguage.normalizeLanguageCode(langHint, fullUrl)
                    entries.add(
                        SubcatLangEntry(
                            language = lang,
                            downloadUrl = fullUrl,
                            titleOrDesc = pageTitle,
                            season = season,
                            episode = episode
                        )
                    )
                }
            }

            // 1. Fallback: Direct download links: href="download/..." or href=".../download/..."
            val dlRegex = Regex("""href=["']((?:https?://(?:www\.)?subtitlecat\.com)?/?download/[^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)
            for (m in dlRegex.findAll(html)) {
                val rawUrl = m.groupValues[1].trim()
                val text = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$BASE_URL/${rawUrl.removePrefix("/")}"

                if (seenUrls.add(fullUrl)) {
                    val lang = SubtitleLanguage.normalizeLanguageCode(text, fullUrl)
                    entries.add(
                        SubcatLangEntry(
                            language = lang,
                            downloadUrl = fullUrl,
                            titleOrDesc = pageTitle,
                            season = season,
                            episode = episode
                        )
                    )
                }
            }

            // 2. Fallback: Language table links: td language / href="..."
            val tableRegex = Regex(
                """<tr[^>]*>.*?<td[^>]*>(.*?)</td>.*?href=["']([^"']+)["'].*?</tr>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            for (m in tableRegex.findAll(html)) {
                val langText = m.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
                val rawUrl = m.groupValues[2].trim()
                if (rawUrl.isBlank() || rawUrl.startsWith("#") || rawUrl.startsWith("javascript:")) continue
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "$BASE_URL/${rawUrl.removePrefix("/")}"

                if (seenUrls.add(fullUrl)) {
                    val lang = SubtitleLanguage.normalizeLanguageCode(langText, rawUrl)
                    entries.add(
                        SubcatLangEntry(
                            language = lang,
                            downloadUrl = fullUrl,
                            titleOrDesc = pageTitle,
                            season = season,
                            episode = episode
                        )
                    )
                }
            }

            return entries
        }
    }

    data class SubcatCandidate(
        val pageUrl: String,
        val candidateTitle: String,
        val season: Int? = null,
        val episode: Int? = null,
        val year: Int? = null
    )

    data class SubcatLangEntry(
        val language: String,
        val downloadUrl: String,
        val titleOrDesc: String = "",
        val season: Int? = null,
        val episode: Int? = null
    )

    private val searchCache = ConcurrentHashMap<String, List<ExternalSubtitle>>()

    override suspend fun search(request: SubtitleSearchRequest): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        if (request.title.isBlank()) return@withContext emptyList()

        val cacheKey = if (request.isSeries) {
            "subcat:series:${request.title.lowercase(Locale.ROOT)}:${request.season ?: 0}:${request.episode ?: 0}"
        } else {
            "subcat:movie:${request.title.lowercase(Locale.ROOT)}:${request.year ?: 0}"
        }

        searchCache[cacheKey]?.let { cached ->
            Log.i(TAG, "Returning ${cached.size} cached SubtitleCat results for key=$cacheKey")
            return@withContext cached
        }

        val queriesToTry = buildSearchQueries(request)
        Log.i(TAG, "SUBTITLECAT_SEARCH starting for title=\"${request.title}\" queries=${queriesToTry.size}")

        val rawCandidates = searchCandidatePages(queriesToTry)
        if (rawCandidates.isEmpty()) {
            Log.i(TAG, "SUBTITLECAT: No candidates found for target=\"${request.title}\"")
            return@withContext emptyList()
        }

        // ── STRICT VALIDATION: ZERO FALLBACK TO UNVALIDATED RAW CANDIDATES ───────────
        val validCandidates = mutableListOf<SubcatCandidate>()
        for (cand in rawCandidates) {
            val validation = SubtitleMatcher.validateCandidate(
                candidateTitle = cand.candidateTitle,
                request = request,
                candidateSeason = cand.season,
                candidateEpisode = cand.episode,
                candidateYear = cand.year
            )

            Log.i(
                TAG,
                "SUBTITLECAT_VALIDATION candidate=\"${cand.candidateTitle}\" accepted=${validation.isValid} reason=\"${validation.reason}\""
            )

            if (validation.isValid) {
                validCandidates.add(
                    cand.copy(
                        season = validation.detectedSeason ?: cand.season,
                        episode = validation.detectedEpisode ?: cand.episode,
                        year = validation.detectedYear ?: cand.year
                    )
                )
            }
        }

        // CRITICAL ENFORCEMENT: If no candidates strictly matched, DESCARTAR!
        if (validCandidates.isEmpty()) {
            Log.w(TAG, "SUBTITLECAT: All ${rawCandidates.size} candidates rejected by strict validation for \"${request.title}\". Discarding all.")
            return@withContext emptyList()
        }

        Log.i(TAG, "SUBTITLECAT: ${validCandidates.size} candidates passed strict validation. Resolving downloads...")

        val entries = fetchLanguageEntries(validCandidates.take(3))
        if (entries.isEmpty()) {
            return@withContext emptyList()
        }

        // Filter and prioritize entries
        val finalResults = resolveExternalSubtitles(entries, request)
        if (finalResults.isNotEmpty()) {
            searchCache[cacheKey] = finalResults
        }

        finalResults
    }

    private fun buildSearchQueries(request: SubtitleSearchRequest): List<String> {
        val queries = mutableListOf<String>()
        val baseTitles = listOfNotNull(request.title, request.originalTitle)
            .distinct()
            .filter { it.isNotBlank() }

        if (request.isSeries) {
            val sNum = request.season ?: 1
            val eNum = request.episode ?: 1
            val sStr = String.format(Locale.US, "%02d", sNum)
            val eStr = String.format(Locale.US, "%02d", eNum)

            for (t in baseTitles) {
                queries.add("$t S${sStr}E${eStr}")
                queries.add("$t ${sNum}x${eStr}")
                if (request.year != null && request.year > 1900) {
                    queries.add("$t S${sStr}E${eStr} ${request.year}")
                }
                queries.add("$t Episode $eNum")
            }
        } else {
            for (t in baseTitles) {
                if (request.year != null && request.year > 1900) {
                    queries.add("$t ${request.year}")
                }
                queries.add(t)
            }
        }

        return queries.distinct().take(6)
    }

    private suspend fun searchCandidatePages(queries: List<String>): List<SubcatCandidate> = coroutineScope {
        val deferreds = queries.map { query ->
            async(Dispatchers.IO) {
                try {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val searchUrl = "$BASE_URL/index.php?search=$encoded"
                    val html = executeGetHtml(searchUrl)
                    if (html.isNotBlank()) {
                        extractCandidatePages(html)
                    } else emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Search exception for query=\"$query\": ${e.message}")
                    emptyList<SubcatCandidate>()
                }
            }
        }

        val seen = mutableSetOf<String>()
        val all = mutableListOf<SubcatCandidate>()
        for (def in deferreds) {
            for (c in def.await()) {
                if (seen.add(c.pageUrl)) {
                    all.add(c)
                }
            }
        }
        all
    }

    private suspend fun fetchLanguageEntries(candidates: List<SubcatCandidate>): List<SubcatLangEntry> = coroutineScope {
        val deferreds = candidates.map { cand ->
            async(Dispatchers.IO) {
                try {
                    val html = executeGetHtml(cand.pageUrl)
                    if (html.isNotBlank()) {
                        extractLanguageEntries(html, cand.pageUrl, cand.candidateTitle, cand.season, cand.episode)
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList<SubcatLangEntry>()
                }
            }
        }

        val all = mutableListOf<SubcatLangEntry>()
        for (def in deferreds) {
            all.addAll(def.await())
        }
        all
    }

    private fun resolveExternalSubtitles(
        entries: List<SubcatLangEntry>,
        request: SubtitleSearchRequest
    ): List<ExternalSubtitle> {
        val finalExternalSubs = mutableListOf<ExternalSubtitle>()
        val byLanguage = entries.groupBy { it.language.lowercase(Locale.ROOT) }

        for ((langCode, entriesForLang) in byLanguage) {
            val langDisplayName = SubtitleLanguage.getDisplayName(langCode)
            val totalInLang = entriesForLang.size

            entriesForLang.take(2).forEachIndexed { index, item ->
                val label = if (totalInLang > 1) {
                    "$langDisplayName ${index + 1} · SubtitleCat"
                } else {
                    "$langDisplayName · SubtitleCat"
                }

                val extSub = ExternalSubtitle(
                    id = "subcat_${item.downloadUrl.hashCode()}_${langCode}_${index + 1}",
                    url = item.downloadUrl,
                    label = label,
                    language = langCode,
                    source = "SubtitleCat",
                    isTranslated = false,
                    displayTitle = item.titleOrDesc.ifBlank { request.title },
                    season = item.season ?: request.season,
                    episode = item.episode ?: request.episode,
                    year = request.year,
                    score = 200
                )
                finalExternalSubs.add(extSub)
            }
        }

        return finalExternalSubs
    }

    private fun executeGetHtml(url: String): String {
        return try {
            val reqBuilder = Request.Builder().url(url)
            subcatHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            val response = client.newCall(reqBuilder.build()).execute()
            response.use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
