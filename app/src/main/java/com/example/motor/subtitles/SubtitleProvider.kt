package com.example.motor.subtitles

import com.example.motor.subtitles.ExternalSubtitle

/**
 * Common abstraction for subtitle providers (OpenSubtitles, SubtitleCat, ASSRT).
 */
interface SubtitleProvider {
    val name: String

    suspend fun search(request: SubtitleSearchRequest): List<ExternalSubtitle>

    suspend fun search(
        rawTitle: String,
        normTitle: String,
        isSeries: Boolean,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        originalTitle: String? = null,
        tmdbTitle: String? = null
    ): List<ExternalSubtitle> {
        val req = SubtitleSearchRequest(
            title = if (normTitle.isNotBlank()) normTitle else rawTitle,
            originalTitle = originalTitle,
            mediaType = if (isSeries) "series" else "movie",
            isSeries = isSeries,
            year = year,
            season = season,
            episode = episode
        )
        return search(req)
    }
}
