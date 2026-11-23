package com.example.motor.subtitles

import android.util.Log
import com.example.motor.subtitles.ExternalSubtitle
import java.util.Locale

data class MatchValidationResult(
    val isValid: Boolean,
    val reason: String,
    val detectedSeason: Int? = null,
    val detectedEpisode: Int? = null,
    val detectedYear: Int? = null
)

/**
 * Strict Subtitle Matcher and Hard Rejection Engine.
 *
 * Enforces hard boundaries that CANNOT be compensated by positive scores.
 * Priority: ES PREFERIBLE NO ENCONTRAR SUBTÍTULO QUE ASIGNAR UN SUBTÍTULO INCORRECTO.
 */
object SubtitleMatcher {

    private const val TAG = "SUBTITLE_MATCH"

    val NOISE_TAGS = setOf(
        "1080p", "720p", "2160p", "4k", "uhd", "bluray", "blu-ray", "webrip", "web-dl", "webdl",
        "hdtv", "dvdrip", "x264", "x265", "h264", "h265", "hevc", "ac3", "dts", "aac", "dd5.1",
        "repack", "proper", "vostfr", "subfrench", "ita", "esp", "eng", "spa", "subtitles", "subs",
        "srt", "vtt", "ass", "spanish", "english", "castellano", "latino", "season", "episode",
        "temporada", "episodio", "capitulo", "cap", "complete", "pack", "hdrip", "bdrip"
    )

    val STOP_WORDS = setOf(
        "the", "a", "an", "el", "la", "los", "las", "un", "una", "de", "del", "and", "y", "en", "in", "of", "to", "s"
    )

    /**
     * Extracts Season and Episode from text (supporting S01E01, 1x01, EP01, 第1集, Episode 1).
     * Distinguishes authentic series episodes from accidental numbers, language codes or audio tags.
     */
    fun extractSeasonEpisode(text: String?): Pair<Int?, Int?>? {
        if (text.isNullOrBlank()) return null

        // 1. S01E01, S1E1, S01.E01, S01-E02, S01_E02
        val seRegex = Regex("""(?i)\b[sS](\d{1,2})\s*[-._xX ]?\s*[eE](\d{1,3})\b""").find(text)
        if (seRegex != null) {
            val s = seRegex.groupValues[1].toIntOrNull()
            val e = seRegex.groupValues[2].toIntOrNull()
            if (s != null && e != null) return Pair(s, e)
        }

        // 2. 1x01 or 01x01 (explicit season x episode)
        val xRegex = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""").find(text)
        if (xRegex != null) {
            val s = xRegex.groupValues[1].toIntOrNull()
            val e = xRegex.groupValues[2].toIntOrNull()
            if (s != null && e != null) return Pair(s, e)
        }

        // 3. Temporada / Season followed by Episode / Capitulo
        val sMatch = Regex("""(?i)\b(?:season|temporada|temp)\s*(\d{1,2})\b""").find(text)
        val eMatch = Regex("""(?i)\b(?:episode|episodio|capitulo)\s*(\d{1,3})\b""").find(text)
        if (sMatch != null && eMatch != null) {
            val s = sMatch.groupValues[1].toIntOrNull()
            val e = eMatch.groupValues[1].toIntOrNull()
            if (s != null && e != null) return Pair(s, e)
        }

        // 4. Chinese episode patterns: 第1集, 第01话, 第1期 and 第1季
        val cnSeasonMatch = Regex("""第\s*(\d{1,2})\s*季""").find(text)
        val cnEpMatch = Regex("""第\s*(\d{1,3})\s*[集话話期]""").find(text)
        if (cnEpMatch != null) {
            val e = cnEpMatch.groupValues[1].toIntOrNull()
            val s = cnSeasonMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: sMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            if (e != null) return Pair(s, e)
        }

        // 5. Explicit episode prefix: "Episode 1", "Episodio 1", "Capitulo 1", "EP01", "ep 01"
        val epPrefixedMatch = Regex("""(?i)\b(?:episode|episodio|capitulo)\s*(\d{1,3})\b|\bep\s*[-._]?\s*(\d{1,3})\b""").find(text)
        if (epPrefixedMatch != null) {
            val eStr = epPrefixedMatch.groupValues[1].ifEmpty { epPrefixedMatch.groupValues[2] }
            val e = eStr.toIntOrNull()
            val s = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: cnSeasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (e != null) return Pair(s, e)
        }

        // 6. Explicit standalone E01, E12 (at least 2 digits to avoid matching letters like 'E')
        val standaloneEMatch = Regex("""(?i)(?<=\b|[-._ ])[eE](\d{2,3})\b""").find(text)
        if (standaloneEMatch != null) {
            val e = standaloneEMatch.groupValues[1].toIntOrNull()
            val s = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: cnSeasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (e != null) return Pair(s, e)
        }

        return null
    }

    /**
     * Checks if a target episode is contained within a multi-episode range in the text
     * (e.g. "01-30集", "1~50", "E01-E24", "第01-30话").
     */
    fun isEpisodeInMultiEpisodeRange(text: String?, targetEpisode: Int): Boolean {
        if (text.isNullOrBlank() || targetEpisode <= 0) return false
        val rangeRegex = Regex("""(?i)(?:ep|e|第)?\s*0*(\d{1,3})\s*[-~～–]\s*(?:ep|e|第)?\s*0*(\d{1,3})\s*(?:集|话|話|期|ep|eps)?""")
        for (m in rangeRegex.findAll(text)) {
            val start = m.groupValues[1].toIntOrNull() ?: continue
            val end = m.groupValues[2].toIntOrNull() ?: continue
            if (start == end) continue
            val minEp = minOf(start, end)
            val maxEp = maxOf(start, end)
            if (minEp >= 1 && maxEp <= 300 && (maxEp - minEp) >= 1 && targetEpisode in minEp..maxEp) {
                return true
            }
        }
        return false
    }

    /**
     * Extracts a 4-digit release year (1900-2099).
     */
    fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = Regex("""\b(19\d\d|20\d\d)\b""").find(text)
        return m?.value?.toIntOrNull()
    }

    /**
     * Extracts all 4-digit release years (1900-2099).
     */
    fun extractAllYears(text: String?): List<Int> {
        if (text.isNullOrBlank()) return emptyList()
        return Regex("""\b(19\d\d|20\d\d)\b""").findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    /**
     * Tokenizes title into meaningful, normalized search terms.
     */
    fun tokenizeTitle(title: String?): List<String> {
        if (title.isNullOrBlank()) return emptyList()

        val cleaned = title.lowercase(Locale.ROOT)
            .replace(Regex("""['’`′]"""), "")
            .replace(Regex("""(?i)\b[sS]\d{1,2}\s*[-._xX]?\s*[eE]\d{1,3}\b"""), " ")
            .replace(Regex("""(?i)\b\d{1,2}x\d{1,3}\b"""), " ")
            .replace(Regex("""(?i)\b(19\d\d|20\d\d)\b"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]"""), " ")

        return cleaned.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in NOISE_TAGS && it !in STOP_WORDS }
    }

    /**
     * Normalizes a text string for comparison (removes noise, spaces, accents, symbols).
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
            .trim()
    }

    /**
     * Hard rejection validator: returns MatchValidationResult with isValid = false if
     * any hard rejection rule is violated.
     */
    fun validateCandidate(
        candidateTitle: String,
        request: SubtitleSearchRequest,
        candidateSeason: Int? = null,
        candidateEpisode: Int? = null,
        candidateYear: Int? = null
    ): MatchValidationResult {
        val extractedSe = extractSeasonEpisode(candidateTitle)
        val effectiveCandSeason = candidateSeason ?: extractedSe?.first
        val effectiveCandEpisode = candidateEpisode ?: extractedSe?.second

        val metadataYear = candidateYear
        val extractedYears = extractAllYears(candidateTitle)
        val effectiveCandYear = metadataYear ?: extractedYears.firstOrNull()

        // ── Series Validation ────────────────────────────────────────────────────────
        if (request.isSeries) {
            // Rule 1: Series Title Identity (Strict Check)
            val titleMatches = checkSeriesTitleIdentity(
                candidateTitle = candidateTitle,
                targetTitle = request.title,
                originalTitle = request.originalTitle
            )

            val candHasChinese = containsChinese(candidateTitle)
            val requestHasChinese = containsChinese(request.title) || 
                (request.originalTitle != null && containsChinese(request.originalTitle))
            val isRangeMatch = request.episode != null && isEpisodeInMultiEpisodeRange(candidateTitle, request.episode)
            val isEpisodeMatch = request.episode != null && (effectiveCandEpisode == request.episode || isRangeMatch) &&
                (request.season == null || effectiveCandSeason == null || request.season == effectiveCandSeason)
            val isCompletePack = isRangeMatch ||
                candidateTitle.contains("Complete", ignoreCase = true) ||
                candidateTitle.contains("全集") || candidateTitle.contains("Pack", ignoreCase = true)
            val isCrossLanguage = (candHasChinese && !requestHasChinese) || (!candHasChinese && requestHasChinese)
            val allowCrossLanguageCandidate = !titleMatches.first && isCrossLanguage && (isEpisodeMatch || isCompletePack)

            if (!titleMatches.first && !allowCrossLanguageCandidate) {
                Log.w(TAG, "SUBTITLE_REJECT: Series identity mismatch for candidate=\"$candidateTitle\" vs target=\"${request.title}\" (${titleMatches.second})")
                return MatchValidationResult(
                    isValid = false,
                    reason = "Series identity mismatch: ${titleMatches.second}",
                    detectedSeason = effectiveCandSeason,
                    detectedEpisode = effectiveCandEpisode,
                    detectedYear = effectiveCandYear
                )
            }

            // Rule 2: Season Mismatch Hard Rejection
            if (request.season != null && effectiveCandSeason != null) {
                if (effectiveCandSeason != request.season) {
                    Log.w(TAG, "SUBTITLE_REJECT: Season mismatch (expected S${request.season}, found S$effectiveCandSeason in \"$candidateTitle\")")
                    return MatchValidationResult(
                        isValid = false,
                        reason = "Season mismatch: expected S${request.season}, found S$effectiveCandSeason",
                        detectedSeason = effectiveCandSeason,
                        detectedEpisode = effectiveCandEpisode,
                        detectedYear = effectiveCandYear
                    )
                }
            }

            // Rule 3: Episode Mismatch Hard Rejection
            if (request.episode != null) {
                val isExplicitPack = candidateTitle.contains("Complete", ignoreCase = true) ||
                        candidateTitle.contains("全集") ||
                        candidateTitle.contains("Pack", ignoreCase = true)

                if (effectiveCandEpisode == null) {
                    val isSeasonPackNoEpisode = isRangeMatch || isExplicitPack ||
                            candidateTitle.contains("S${String.format(Locale.US, "%02d", request.season ?: 1)}", ignoreCase = true)

                    if (!isSeasonPackNoEpisode) {
                        Log.w(TAG, "SUBTITLE_REJECT: Missing episode number in candidate \"$candidateTitle\" for target E${request.episode}")
                        return MatchValidationResult(
                            isValid = false,
                            reason = "Candidate does not specify episode number for target E${request.episode}",
                            detectedSeason = effectiveCandSeason,
                            detectedEpisode = null,
                            detectedYear = effectiveCandYear
                        )
                    }
                } else if (effectiveCandEpisode != request.episode) {
                    val isAcceptedPack = isRangeMatch || isExplicitPack
                    if (!isAcceptedPack) {
                        Log.w(TAG, "SUBTITLE_REJECT: Episode mismatch (expected E${request.episode}, found E$effectiveCandEpisode in \"$candidateTitle\")")
                        return MatchValidationResult(
                            isValid = false,
                            reason = "Episode mismatch: expected E${request.episode}, found E$effectiveCandEpisode",
                            detectedSeason = effectiveCandSeason,
                            detectedEpisode = effectiveCandEpisode,
                            detectedYear = effectiveCandYear
                        )
                    }
                }
            }

            // Note: For series episodes, do NOT hard-reject because episode air year or rip year
            // differs from series premiere year (e.g. S04 airing in 2022 for series starting in 2016).

            return MatchValidationResult(
                isValid = true,
                reason = "Series episode validated successfully",
                detectedSeason = effectiveCandSeason,
                detectedEpisode = effectiveCandEpisode,
                detectedYear = effectiveCandYear
            )
        }

        // ── Movie Validation ─────────────────────────────────────────────────────────
        // Rule 1: Movie Title Identity
        val movieTitleMatches = checkMovieTitleIdentity(
            candidateTitle = candidateTitle,
            targetTitle = request.title,
            originalTitle = request.originalTitle
        )

        val candHasChinese = containsChinese(candidateTitle)
        val requestHasChinese = containsChinese(request.title) || 
            (request.originalTitle != null && containsChinese(request.originalTitle))
        val isYearMatch = request.year != null && effectiveCandYear != null && Math.abs(request.year - effectiveCandYear) <= 1
        val isCrossLanguage = (candHasChinese && !requestHasChinese) || (!candHasChinese && requestHasChinese)
        val allowCrossLanguageMovie = !movieTitleMatches.first && isCrossLanguage && isYearMatch

        if (!movieTitleMatches.first && !allowCrossLanguageMovie) {
            Log.w(TAG, "SUBTITLE_REJECT: Movie title mismatch for candidate=\"$candidateTitle\" vs target=\"${request.title}\" (${movieTitleMatches.second})")
            return MatchValidationResult(
                isValid = false,
                reason = "Movie title mismatch: ${movieTitleMatches.second}",
                detectedSeason = null,
                detectedEpisode = null,
                detectedYear = effectiveCandYear
            )
        }

        // Rule 2: Cannot be a series episode when searching for a movie
        if (extractedSe != null) {
            // Check if the detected episode is actually part of the target movie title (e.g. "Star Wars Episode 1")
            val targetSe = extractSeasonEpisode(request.title)
            val isMovieTitleFeature = targetSe != null && targetSe.second == extractedSe.second
            if (!isMovieTitleFeature) {
                Log.w(TAG, "SUBTITLE_REJECT: Candidate is a series episode (${extractedSe.first}x${extractedSe.second}) but target is a movie")
                return MatchValidationResult(
                    isValid = false,
                    reason = "Candidate appears to be a series episode, not a movie",
                    detectedSeason = effectiveCandSeason,
                    detectedEpisode = effectiveCandEpisode,
                    detectedYear = effectiveCandYear
                )
            }
        }

        // Rule 3: Year Validation for Movies
        if (request.year != null && request.year > 1900) {
            if (metadataYear != null && metadataYear > 1900) {
                // Authoritative metadata release year: must match within 1 year
                val diff = Math.abs(request.year - metadataYear)
                if (diff > 1) {
                    Log.w(TAG, "SUBTITLE_REJECT: Movie year mismatch from metadata (target ${request.year} vs metadata $metadataYear in \"$candidateTitle\")")
                    return MatchValidationResult(
                        isValid = false,
                        reason = "Year mismatch: target ${request.year} vs metadata $metadataYear (diff=$diff years)",
                        detectedSeason = null,
                        detectedEpisode = null,
                        detectedYear = metadataYear
                    )
                }
            } else if (extractedYears.isNotEmpty()) {
                // If any extracted year matches target year (diff <= 1), accept (e.g., rip year alongside release year)
                val anyYearMatches = extractedYears.any { Math.abs(request.year - it) <= 1 }
                if (!anyYearMatches) {
                    val primaryYear = extractedYears.first()
                    val diff = Math.abs(request.year - primaryYear)
                    if (diff > 1) {
                        Log.w(TAG, "SUBTITLE_REJECT: Movie year mismatch (target ${request.year} vs candidate year $primaryYear in \"$candidateTitle\")")
                        return MatchValidationResult(
                            isValid = false,
                            reason = "Year mismatch: target ${request.year} vs candidate $primaryYear (diff=$diff years)",
                            detectedSeason = null,
                            detectedEpisode = null,
                            detectedYear = primaryYear
                        )
                    }
                }
            }
        }

        return MatchValidationResult(
            isValid = true,
            reason = "Movie validated successfully",
            detectedSeason = null,
            detectedEpisode = null,
            detectedYear = effectiveCandYear
        )
    }

    /**
     * Checks series title identity.
     * Prevents cases like "Sword and Beloved" matching "Frasier".
     */
    fun checkSeriesTitleIdentity(
        candidateTitle: String,
        targetTitle: String,
        originalTitle: String?
    ): Pair<Boolean, String> {
        val candTokens = tokenizeTitle(candidateTitle).toSet()
        val targetTokens = tokenizeTitle(targetTitle)
        val origTokens = originalTitle?.let { tokenizeTitle(it) } ?: emptyList()

        if (targetTokens.isEmpty() && origTokens.isEmpty()) {
            val normCand = normalize(candidateTitle)
            val normTarget = normalize(targetTitle)
            if (normCand.contains(normTarget) || normTarget.contains(normCand)) {
                return Pair(true, "Normalized substring match")
            }
            return Pair(false, "Empty tokens and no substring match")
        }

        // Check target tokens overlap
        val targetMatch = calculateTokenOverlap(targetTokens, candTokens, candidateTitle)
        if (targetMatch.first) return targetMatch

        // Check original title tokens overlap (e.g. Chinese titles)
        if (origTokens.isNotEmpty()) {
            val origMatch = calculateTokenOverlap(origTokens, candTokens, candidateTitle)
            if (origMatch.first) return origMatch
        }

        // Chinese character substring check (for titles with Chinese characters)
        if (containsChinese(targetTitle) || containsChinese(originalTitle)) {
            val cnTarget = targetTitle.filter { it in '\u4E00'..'\u9FFF' }
            val cnOrig = originalTitle?.filter { it in '\u4E00'..'\u9FFF' } ?: ""
            val cnCand = candidateTitle.filter { it in '\u4E00'..'\u9FFF' }

            if (cnTarget.length >= 2 && cnCand.contains(cnTarget)) {
                return Pair(true, "Chinese title substring match ($cnTarget)")
            }
            if (cnOrig.length >= 2 && cnCand.contains(cnOrig)) {
                return Pair(true, "Chinese original title substring match ($cnOrig)")
            }
            if (cnOrig.length >= 2) {
                val commonCount = cnOrig.filter { it in cnCand }.length
                if (commonCount >= 2 && commonCount >= (cnOrig.length * 0.6)) {
                    return Pair(true, "Chinese original title character match ($cnOrig in $cnCand)")
                }
            }
            if (cnTarget.length >= 2) {
                val commonCount = cnTarget.filter { it in cnCand }.length
                if (commonCount >= 2 && commonCount >= (cnTarget.length * 0.6)) {
                    return Pair(true, "Chinese target title character match ($cnTarget in $cnCand)")
                }
            }
        }

        return Pair(false, "Tokens [${targetTokens.joinToString()}] not found in candidate tokens [${candTokens.joinToString()}]")
    }

    /**
     * Checks movie title identity.
     */
    fun checkMovieTitleIdentity(
        candidateTitle: String,
        targetTitle: String,
        originalTitle: String?
    ): Pair<Boolean, String> {
        val candTokens = tokenizeTitle(candidateTitle).toSet()
        val targetTokens = tokenizeTitle(targetTitle)
        val origTokens = originalTitle?.let { tokenizeTitle(it) } ?: emptyList()

        // Reject generic single-word matching for common words like "The", "Last", "Man"
        if (targetTokens.size == 1 && candTokens.size >= 4 && !candidateTitle.contains(targetTitle, ignoreCase = true)) {
            return Pair(false, "Target title is a single generic word and candidate is too distinct")
        }

        val targetMatch = calculateTokenOverlap(targetTokens, candTokens, candidateTitle)
        if (targetMatch.first) return targetMatch

        if (origTokens.isNotEmpty()) {
            val origMatch = calculateTokenOverlap(origTokens, candTokens, candidateTitle)
            if (origMatch.first) return origMatch
        }

        if (containsChinese(targetTitle) || containsChinese(originalTitle)) {
            val cnTarget = targetTitle.filter { it in '\u4E00'..'\u9FFF' }
            val cnOrig = originalTitle?.filter { it in '\u4E00'..'\u9FFF' } ?: ""
            val cnCand = candidateTitle.filter { it in '\u4E00'..'\u9FFF' }

            if (cnTarget.length >= 2 && cnCand.contains(cnTarget)) {
                return Pair(true, "Chinese movie substring match")
            }
            if (cnOrig.length >= 2 && cnCand.contains(cnOrig)) {
                return Pair(true, "Chinese original movie substring match")
            }
        }

        return Pair(false, "Movie tokens do not match")
    }

    private fun calculateTokenOverlap(
        expectedTokens: List<String>,
        candidateTokens: Set<String>,
        candidateRaw: String
    ): Pair<Boolean, String> {
        if (expectedTokens.isEmpty()) return Pair(false, "No expected tokens")

        val matchCount = expectedTokens.count { token ->
            candidateTokens.contains(token)
        }

        val ratio = matchCount.toDouble() / expectedTokens.size.toDouble()

        return when {
            expectedTokens.size == 1 -> {
                if (matchCount == 1) Pair(true, "Single token matched")
                else Pair(false, "Single token mismatch")
            }
            expectedTokens.size == 2 -> {
                if (matchCount == 2) Pair(true, "Both tokens matched")
                else Pair(false, "Only $matchCount of 2 tokens matched")
            }
            else -> {
                if (ratio >= 0.65) Pair(true, "Token overlap ${(ratio * 100).toInt()}% >= 65%")
                else Pair(false, "Token overlap ${(ratio * 100).toInt()}% < 65%")
            }
        }
    }

    /**
     * Calculates soft ranking score between valid candidates.
     */
    fun calculateScore(
        sub: ExternalSubtitle,
        request: SubtitleSearchRequest,
        isHashMatch: Boolean = false
    ): Int {
        var score = 0

        // LEVEL 1: MovieHash match gets absolute highest score
        if (isHashMatch) {
            score += 10000
        }

        // Language matching
        val isLangMatch = SubtitleLanguage.matchesPreferred(sub.language, request.preferredLanguage)
        if (isLangMatch) {
            score += 1500
        } else {
            val normLang = SubtitleLanguage.normalizeLanguageCode(sub.language)
            score += when (normLang) {
                SubtitleLanguage.LANG_ES, SubtitleLanguage.LANG_ES_419 -> 1000
                SubtitleLanguage.LANG_EN -> 800
                SubtitleLanguage.LANG_RO -> 600
                SubtitleLanguage.LANG_ZH_CN, SubtitleLanguage.LANG_ZH_TW, SubtitleLanguage.LANG_ZH_BILINGUAL -> 400
                else -> 100
            }
        }

        // Series Season & Episode match
        if (request.isSeries) {
            if (sub.season != null && sub.season == request.season &&
                sub.episode != null && sub.episode == request.episode
            ) {
                score += 500
            } else if (sub.season != null && sub.season == request.season) {
                score += 200
            }
        } else {
            // Movie year match
            if (request.year != null && sub.year != null && request.year == sub.year) {
                score += 300
            }
        }

        // Title precision
        val normTarget = normalize(request.title)
        val normCand = normalize(sub.displayTitle ?: sub.label)
        if (normCand.contains(normTarget)) {
            score += 200
        }

        // Provider weight
        score += when (sub.source) {
            "OpenSubtitles" -> 300
            "SubtitleCat" -> 200
            "ASSRT" -> if (containsChinese(request.title) || containsChinese(request.originalTitle)) 400 else 150
            else -> 100
        }

        return score
    }

    fun containsChinese(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.any { ch ->
            ch in '\u4E00'..'\u9FFF' ||
            ch in '\u3400'..'\u4DBF' ||
            ch in '\uF900'..'\uFAFF'
        }
    }
}
