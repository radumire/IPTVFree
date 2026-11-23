package com.example.motor.subtitles

/**
 * Canonical model for an external subtitle track across the entire application.
 * Replaces all previous fragmented definitions in TmdbService and TmdbRepository.
 */
data class ExternalSubtitle(
    val id: String,
    val url: String,
    val label: String,
    val language: String,
    val source: String = "SubtitleCat",
    val isTranslated: Boolean = false,
    val displayTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val release: String? = null,
    val pageUrl: String? = null,
    val score: Int? = null,
    val fileId: Long? = null,
    val searchStrategy: String? = null
)
