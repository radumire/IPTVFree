package com.example.motor.subtitles

import android.util.Log
import com.example.motor.subtitles.ExternalSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for subtitle discovery and validation.
 *
 * Enforces strict cascading:
 * 1. Exclusion of Live TV
 * 2. MovieHash calculation (if seekable media file)
 * 3. Canonical identity enrichment (clean titles, TMDB with strict confidence, original title)
 * 4. Content-based cache check (independent of user preferredLanguage)
 * 5. Parallel Provider Cascade (OpenSubtitles, SubtitleCat, ASSRT - each called exactly ONCE)
 * 6. Hard Rejection across all collected candidates (IDENTITY FIRST, NO EXCEPTIONS)
 * 7. Soft Scoring with language preference weighting AFTER hard identity validation
 * 8. SubtitleResolver: resolves OpenSubtitles file_id & archives to local file:// or real GET URL
 */
class SubtitleEngine(
    val openSubtitlesProvider: OpenSubtitlesProvider = OpenSubtitlesProvider(),
    val subtitleCatProvider: SubtitleCatProvider = SubtitleCatProvider(),
    val assrtSubtitleProvider: AssrtSubtitleProvider = AssrtSubtitleProvider()
) {

    companion object {
        private const val TAG_SEARCH = "SUBTITLE_SEARCH"
        private const val TAG_HASH = "SUBTITLE_HASH"
        private const val TAG_MATCH = "SUBTITLE_MATCH"
        private const val TAG_REJECT = "SUBTITLE_REJECT"

        @Volatile
        private var INSTANCE: SubtitleEngine? = null

        fun getInstance(): SubtitleEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubtitleEngine().also { INSTANCE = it }
            }
        }
    }

    private val cache = ConcurrentHashMap<String, List<ExternalSubtitle>>()

    /**
     * Executes the unified subtitle search cascade.
     */
    suspend fun searchSubtitles(request: SubtitleSearchRequest): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        // ── 1. EXPLICIT EXCLUSION: LIVE TV ──────────────────────────────────────────
        if (request.isLiveTv) {
            Log.i(TAG_SEARCH, "SUBTITLE_SEARCH skipped: Live TV content is explicitly excluded.")
            return@withContext emptyList()
        }

        if (request.title.isBlank()) {
            return@withContext emptyList()
        }

        var effectiveRequest = request

        // Canonical clean title and provider prefix extraction
        val (detectedPrefix, titleWithoutPrefix) = extractProviderPrefix(effectiveRequest.title)
        val hasChineseProviderPrefix = isChineseProviderPrefix(detectedPrefix)

        val (cleanedTitle, extractedYear) = if (effectiveRequest.isSeries) {
            val sTitle = com.example.motor.tmdb.TmdbService.cleanSeriesName(titleWithoutPrefix)
            val finalTitle = if (sTitle.isNotBlank()) sTitle else titleWithoutPrefix.trim()
            val year = if (effectiveRequest.year == null || effectiveRequest.year <= 1900) {
                com.example.motor.tmdb.TmdbService.cleanTitleAndExtractYear(titleWithoutPrefix).year
            } else effectiveRequest.year
            Pair(finalTitle, year)
        } else {
            val parsedMovie = com.example.motor.tmdb.TmdbService.cleanTitleAndExtractYear(titleWithoutPrefix)
            val finalTitle = if (parsedMovie.title.isNotBlank()) parsedMovie.title else titleWithoutPrefix.trim()
            val year = if (effectiveRequest.year == null || effectiveRequest.year <= 1900) {
                parsedMovie.year
            } else effectiveRequest.year
            Pair(finalTitle, year)
        }

        effectiveRequest = effectiveRequest.copy(
            title = cleanedTitle,
            year = extractedYear
        )

        val preferredLang = SubtitleLanguage.normalizeLanguageCode(request.preferredLanguage)

        // ── 2. CALCULATE MOVIE HASH (IF SEEKABLE) ───────────────────────────────────
        if (effectiveRequest.movieHash.isNullOrBlank() && !effectiveRequest.videoUrl.isNullOrBlank()) {
            if (MovieHash.isSeekableMediaUrl(effectiveRequest.videoUrl)) {
                val hashResult = MovieHash.calculateMovieHash(effectiveRequest.videoUrl, effectiveRequest.headers)
                if (hashResult != null) {
                    effectiveRequest = effectiveRequest.copy(
                        movieHash = hashResult.hash,
                        fileSize = hashResult.fileSize
                    )
                    Log.i(TAG_HASH, "MovieHash obtained: ${hashResult.hash} for url=${effectiveRequest.videoUrl}")
                }
            } else {
                Log.d(TAG_HASH, "URL is not a seekable file, skipping MovieHash calculation: ${effectiveRequest.videoUrl}")
            }
        }

        // ── 2b. ENRICH METADATA IF TMDB/ORIGINAL TITLE IS MISSING (STRICT VALIDATION) ──
        if (effectiveRequest.tmdbId == null && effectiveRequest.originalTitle.isNullOrBlank()) {
            try {
                val tmdbDetails = com.example.motor.tmdb.TmdbService.getCachedMediaDetails(cleanedTitle, effectiveRequest.isSeries)
                    ?: runCatching { com.example.motor.tmdb.TmdbService.fetchMediaDetails(cleanedTitle, effectiveRequest.isSeries) }.getOrNull()
                
                if (tmdbDetails != null && tmdbDetails.tmdbId != null && tmdbDetails.tmdbId > 0) {
                    // Check if match is reliable before adopting tmdbId
                    val isReliableMatch = tmdbDetails.title.equals(cleanedTitle, ignoreCase = true) ||
                            (tmdbDetails.originalTitle != null && tmdbDetails.originalTitle.equals(cleanedTitle, ignoreCase = true)) ||
                            (effectiveRequest.isSeries && (tmdbDetails.title.contains(cleanedTitle, ignoreCase = true) || cleanedTitle.contains(tmdbDetails.title, ignoreCase = true))) ||
                            (extractedYear != null && tmdbDetails.year != null && Math.abs(extractedYear - tmdbDetails.year) <= 1)

                    if (isReliableMatch) {
                        effectiveRequest = effectiveRequest.copy(
                            tmdbId = tmdbDetails.tmdbId,
                            originalTitle = tmdbDetails.originalTitle ?: effectiveRequest.originalTitle,
                            year = effectiveRequest.year ?: tmdbDetails.year
                        )
                    } else {
                        // Only adopt original title or year if plausible, but not tmdbId to avoid forcing wrong metadata on OpenSubtitles
                        effectiveRequest = effectiveRequest.copy(
                            originalTitle = tmdbDetails.originalTitle ?: effectiveRequest.originalTitle,
                            year = effectiveRequest.year ?: tmdbDetails.year
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG_SEARCH, "Error enriching metadata from TMDB: ${e.message}")
            }
        }

        // ── 3. STRONG CACHE CHECK (CONTENT BASED, INDEPENDENT OF PREFERRED LANGUAGE) ──
        val cacheKey = buildCacheKey(effectiveRequest)
        cache[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) {
                Log.i(TAG_SEARCH, "Returning ${cached.size} cached universal subtitles for key=$cacheKey")
                // Re-rank cached universal subtitles for current preferred language
                return@withContext rankSubtitles(cached, effectiveRequest)
            }
        }

        Log.i(
            TAG_SEARCH,
            "SUBTITLE_SEARCH_START canonicalTitle=\"${effectiveRequest.title}\", originalTitle=\"${effectiveRequest.originalTitle}\", isSeries=${effectiveRequest.isSeries}, S=${effectiveRequest.season}, E=${effectiveRequest.episode}, year=${effectiveRequest.year}, tmdbId=${effectiveRequest.tmdbId}, imdbId=${effectiveRequest.imdbId}, hash=${effectiveRequest.movieHash}, preferredLang=$preferredLang"
        )

        val collectedCandidates = mutableListOf<ExternalSubtitle>()

        // ── 4. PARALLEL PROVIDER CASCADE (EACH PROVIDER RUNS EXACTLY ONCE) ───────────
        // Determine if content is Chinese / Asian by checking characters, prefix, or language
        val isChineseContent = SubtitleMatcher.containsChinese(effectiveRequest.title) ||
                SubtitleMatcher.containsChinese(effectiveRequest.originalTitle) ||
                hasChineseProviderPrefix ||
                preferredLang.startsWith("zh")

        // ASSRT is queried in parallel when token is configured, for series (supporting romanized titles), or Chinese content
        val hasAssrtToken = !AssrtSubtitleProvider.resolveDefaultToken().isNullOrBlank()
        val shouldQueryAssrt = hasAssrtToken || effectiveRequest.isSeries || isChineseContent

        coroutineScope {
            // OpenSubtitles manages its own internal cascade: Level 1 Hash, fallback to Level 2 Metadata
            val osDeferred = async<List<ExternalSubtitle>>(Dispatchers.IO) {
                try {
                    openSubtitlesProvider.search(effectiveRequest)
                } catch (e: Exception) {
                    Log.e(TAG_SEARCH, "OpenSubtitles search error: ${e.message}")
                    emptyList()
                }
            }

            val subcatDeferred = async<List<ExternalSubtitle>>(Dispatchers.IO) {
                try {
                    subtitleCatProvider.search(effectiveRequest)
                } catch (e: Exception) {
                    Log.e(TAG_SEARCH, "SubtitleCat search error: ${e.message}")
                    emptyList()
                }
            }

            val assrtDeferred = if (shouldQueryAssrt) {
                async<List<ExternalSubtitle>>(Dispatchers.IO) {
                    try {
                        assrtSubtitleProvider.search(effectiveRequest)
                    } catch (e: Exception) {
                        Log.e(TAG_SEARCH, "ASSRT search error: ${e.message}")
                        emptyList()
                    }
                }
            } else null

            collectedCandidates.addAll(osDeferred.await())
            collectedCandidates.addAll(subcatDeferred.await())
            if (assrtDeferred != null) {
                collectedCandidates.addAll(assrtDeferred.await())
            }
        }

        // ── 5. STRICT HARD REJECTION & SCORING ACROSS ALL CANDIDATES ─────────────────
        val validCandidates = mutableListOf<ExternalSubtitle>()
        for (cand in collectedCandidates) {
            val isHashMatch = cand.searchStrategy == "HASH" || (cand.score != null && cand.score >= 10000)
            val isAssrt = cand.source.equals("ASSRT", ignoreCase = true)
            val v = SubtitleMatcher.validateCandidate(
                candidateTitle = "${cand.displayTitle ?: ""} ${cand.label}",
                request = effectiveRequest,
                candidateSeason = cand.season,
                candidateEpisode = cand.episode,
                candidateYear = cand.year
            )

            if (v.isValid || isHashMatch || isAssrt) {
                val score = SubtitleMatcher.calculateScore(cand, effectiveRequest, isHashMatch = isHashMatch)
                validCandidates.add(cand.copy(score = score))
                Log.i(TAG_MATCH, "SUBTITLE_ACCEPTED: [${cand.source}] ${cand.label} strategy=${cand.searchStrategy ?: "DEFAULT"} score=$score lang=${cand.language}")
            } else {
                Log.w(TAG_REJECT, "SUBTITLE_REJECTED: [${cand.source}] ${cand.label} reason=\"${v.reason}\"")
            }
        }

        // ── 6. DEDUPLICATION & RANKING ──────────────────────────────────────────────
        val seenUrls = ConcurrentHashMap.newKeySet<String>()
        val seenKeys = mutableSetOf<String>()
        val finalRanked = mutableListOf<ExternalSubtitle>()

        val sorted = validCandidates.sortedByDescending { it.score ?: 0 }
        for (sub in sorted) {
            val key = when {
                sub.fileId != null && sub.fileId > 0 -> "${sub.source}_file_${sub.fileId}"
                sub.id.isNotBlank() -> "${sub.source}_id_${sub.id}"
                else -> "${sub.source}_url_${sub.url}"
            }
            if (!seenKeys.add(key)) continue
            if (sub.url.isNotBlank() && !seenUrls.add(sub.url)) continue
            finalRanked.add(sub)
        }

        // ── 7. RETURN RANKED SEARCH CANDIDATES AND CACHE RESULTS ────────────────────
        Log.i(TAG_SEARCH, "SUBTITLE_RESULTS: total=${finalRanked.size} for title=\"${effectiveRequest.title}\"")

        if (finalRanked.isNotEmpty()) {
            cache[cacheKey] = finalRanked
        }

        finalRanked
    }

    fun extractProviderPrefix(rawTitle: String): Pair<String?, String> {
        if (rawTitle.isBlank()) return Pair(null, rawTitle)
        val trimmed = rawTitle.trim()
        val match = Regex("""^(?:\[([A-Za-z0-9_+\-]{1,10})\]|\(([A-Za-z0-9_+\-]{1,10})\)|([A-Za-z0-9_+\-]{2,8})\s*[:\-|–])\s*""").find(trimmed)
        return if (match != null) {
            val prefix = (match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }).trim()
            val clean = trimmed.substring(match.range.last + 1).trim()
            Pair(prefix, clean)
        } else {
            Pair(null, trimmed)
        }
    }

    fun isChineseProviderPrefix(prefix: String?): Boolean {
        if (prefix.isNullOrBlank()) return false
        val p = prefix.uppercase(Locale.ROOT)
        return p in setOf("CH", "CN", "HK", "TW", "CHINA", "CHINESE", "CDRAMA", "C-DRAMA", "ASIAN", "ASIANDRAMA")
    }

    private fun rankSubtitles(subs: List<ExternalSubtitle>, request: SubtitleSearchRequest): List<ExternalSubtitle> {
        return subs.map { sub ->
            val isHash = sub.searchStrategy == "HASH" || (sub.score != null && sub.score >= 10000)
            val score = SubtitleMatcher.calculateScore(sub, request, isHashMatch = isHash)
            sub.copy(score = score)
        }.sortedByDescending { it.score ?: 0 }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun buildCacheKey(request: SubtitleSearchRequest): String {
        return if (!request.movieHash.isNullOrBlank()) {
            "hash:${request.movieHash}"
        } else if (request.isSeries) {
            "series:${request.title.lowercase(Locale.ROOT).trim()}:${request.season ?: 1}:${request.episode ?: 1}:${request.tmdbId ?: 0}"
        } else {
            "movie:${request.title.lowercase(Locale.ROOT).trim()}:${request.year ?: 0}:${request.tmdbId ?: 0}"
        }
    }
}
