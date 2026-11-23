package com.example.motor.repository

import com.example.motor.subtitles.ExternalSubtitle
import com.example.motor.subtitles.SubtitleEngine
import com.example.motor.subtitles.SubtitleResolver
import com.example.motor.subtitles.SubtitleSearchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Unified Repository for Subtitles across the application.
 * Central access point for ViewModels.
 */
class SubtitleRepository private constructor(
    private val engine: SubtitleEngine = SubtitleEngine.getInstance()
) {

    fun init(cacheDir: File) {
        SubtitleResolver.init(cacheDir)
    }

    suspend fun searchSubtitles(request: SubtitleSearchRequest): List<ExternalSubtitle> {
        return engine.searchSubtitles(request)
    }

    suspend fun resolveSubtitle(sub: ExternalSubtitle): ExternalSubtitle? {
        return SubtitleResolver.resolveSubtitle(sub)
    }

    suspend fun searchSubtitles(
        rawTitle: String,
        isSeries: Boolean,
        season: Int? = null,
        episode: Int? = null,
        videoUrl: String? = null,
        preferredLanguage: String = "es",
        headers: Map<String, String> = emptyMap()
    ): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        val request = SubtitleSearchRequest(
            videoUrl = videoUrl,
            title = rawTitle,
            mediaType = if (isSeries) "series" else "movie",
            isSeries = isSeries,
            season = season,
            episode = episode,
            preferredLanguage = preferredLanguage,
            headers = headers
        )
        engine.searchSubtitles(request)
    }

    fun clearCache() {
        engine.clearCache()
    }

    companion object {
        @Volatile
        private var INSTANCE: SubtitleRepository? = null

        fun getInstance(): SubtitleRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubtitleRepository().also { INSTANCE = it }
            }
        }
    }
}
