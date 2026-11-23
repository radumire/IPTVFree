package com.example.motor.repository

import com.example.motor.config.Playlist
import com.example.motor.db.IptvItem
import com.example.motor.subtitles.ExternalSubtitle
import com.example.motor.tmdb.TmdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

typealias CleanedTitle = TmdbService.CleanedTitle
typealias MediaDetails = TmdbService.MediaDetails

class TmdbRepository private constructor() {

    fun init(cacheDir: File) {
        TmdbService.init(cacheDir)
    }

    fun cleanTitleAndExtractYear(rawTitle: String): TmdbService.CleanedTitle {
        return TmdbService.cleanTitleAndExtractYear(rawTitle)
    }

    fun getCachedYear(title: String, isSeries: Boolean): Int? {
        return TmdbService.getCachedYear(title, isSeries)
    }

    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean): Pair<String?, Int?> = withContext(Dispatchers.IO) {
        try {
            TmdbService.fetchPosterAndYear(title, isSeries)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    suspend fun getMoviePoster(title: String): String? = withContext(Dispatchers.IO) {
        try {
            TmdbService.fetchPoster(title, isSeries = false)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getSeriesPoster(title: String): String? = withContext(Dispatchers.IO) {
        try {
            TmdbService.fetchPoster(title, isSeries = true)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchXtreamMediaDetails(
        item: IptvItem,
        activePlaylist: Playlist?,
        isSeries: Boolean
    ): TmdbService.MediaDetails = withContext(Dispatchers.IO) {
        TmdbService.fetchXtreamMediaDetails(item, activePlaylist, isSeries)
    }

    suspend fun fetchMediaDetails(
        title: String,
        isSeries: Boolean,
        defaultLogo: String? = null
    ): TmdbService.MediaDetails = withContext(Dispatchers.IO) {
        TmdbService.fetchMediaDetails(title, isSeries, defaultLogo)
    }

    suspend fun searchSubtitles(
        rawTitle: String,
        isSeries: Boolean,
        season: Int? = null,
        episode: Int? = null
    ): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        try {
            SubtitleRepository.getInstance().searchSubtitles(rawTitle, isSeries, season, episode)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: TmdbRepository? = null

        fun getInstance(): TmdbRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TmdbRepository().also { INSTANCE = it }
            }
        }
    }
}

