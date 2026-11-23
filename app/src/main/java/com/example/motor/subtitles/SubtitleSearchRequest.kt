package com.example.motor.subtitles

/**
 * Centralized request model for subtitle search.
 * Contains all metadata, video references, and language preferences.
 */
data class SubtitleSearchRequest(
    val videoUrl: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val mediaType: String = "movie", // "movie", "series", "tv", "live"
    val isSeries: Boolean = (mediaType.equals("series", ignoreCase = true)),
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val preferredLanguage: String = "es",
    val headers: Map<String, String> = emptyMap(),
    val movieHash: String? = null,
    val fileSize: Long? = null
) {
    val isLiveTv: Boolean
        get() = mediaType.equals("live", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)

    val isMovie: Boolean
        get() = !isSeries && !isLiveTv
}
