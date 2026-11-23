package com.example.motor.repository

import android.app.Application
import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.config.Playlist
import com.example.motor.config.StalkerDevice
import com.example.motor.db.*
import com.example.motor.parser.BaseEngine
import com.example.motor.parser.XtreamCredentials
import com.example.motor.source.IptvSourceType
import com.example.motor.source.M3uSource
import com.example.motor.source.StalkerSource
import com.example.motor.source.XtreamSource
import com.example.motor.tmdb.TmdbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.squareup.moshi.Types
import okio.buffer
import okio.sink
import okio.source

class IptvRepository private constructor(
    private val application: Application,
    private val sessionRepo: PortalSessionRepository
) {
    private val TAG = "IptvRepository"
    private val db = AppDatabase.getDatabase(application)
    private val historyDao = db.playbackHistoryDao()
    private val favoriteDao = db.favoriteItemDao()

    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    // Isolated IPTV Source Handlers
    val stalkerSource = StalkerSource(sessionRepo)
    val xtreamSource = XtreamSource()
    val m3uSource = M3uSource(xtreamSource)

    // Memory cache for series episodes
    private val seriesEpisodesCache = ConcurrentHashMap<String, List<SeriesSeason>>()

    fun getCachedEpisodes(key: String): List<SeriesSeason>? {
        seriesEpisodesCache[key]?.let { return it }
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val roomResult = mediaCacheRepo.getEpisodesCache(key)
            if (roomResult.data != null) {
                seriesEpisodesCache[key] = roomResult.data
                roomResult.data
            } else null
        }
    }

    fun cacheEpisodes(key: String, episodes: List<SeriesSeason>) {
        seriesEpisodesCache[key] = episodes
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            mediaCacheRepo.saveEpisodesCache(key, "", episodes)
        }
    }

    fun clearEpisodesCache() { seriesEpisodesCache.clear() }

    // Favorites & History Flows
    @OptIn(ExperimentalCoroutinesApi::class)
    val favoriteItems: Flow<List<FavoriteItem>> = sessionRepo.activePlaylist
        .flatMapLatest { activePl ->
            val plId = activePl?.id
            if (plId != null && plId > 0) {
                favoriteDao.getFavoritesForPlaylistFlow(plId)
            } else {
                flowOf(emptyList())
            }
        }

    fun getFavoritesForPlaylist(playlistId: Long): Flow<List<FavoriteItem>> {
        return favoriteDao.getFavoritesForPlaylistFlow(playlistId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentHistory: Flow<List<PlaybackHistory>> = sessionRepo.activePlaylist
        .flatMapLatest { activePl ->
            val plId = activePl?.id
            if (plId != null && plId > 0) {
                historyDao.getHistoryForPlaylistFlow(plId)
            } else {
                flowOf(emptyList())
            }
        }

    fun getHistoryForPlaylist(playlistId: Long): Flow<List<PlaybackHistory>> {
        return historyDao.getHistoryForPlaylistFlow(playlistId)
    }

    suspend fun addFavorite(
        id: String,
        title: String,
        type: String,
        url: String,
        logo: String?,
        playlistId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: 0L
        val fav = FavoriteItem(id, title, type, url, logo, System.currentTimeMillis(), targetPlId)
        favoriteDao.insert(fav)
    }

    suspend fun removeFavorite(id: String, playlistId: Long? = null) = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            favoriteDao.deleteByAnyKeyAndPlaylist(id, targetPlId)
            favoriteDao.deleteByIdAndPlaylist(id, targetPlId)
        } else {
            favoriteDao.deleteById(id)
        }
    }

    suspend fun isFavorite(id: String, playlistId: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            favoriteDao.getByIdAndPlaylist(id, targetPlId) != null
        } else {
            favoriteDao.getById(id) != null
        }
    }

    suspend fun clearAllFavorites(playlistId: Long? = null) = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            favoriteDao.clearForPlaylist(targetPlId)
        } else {
            favoriteDao.clearAll()
        }
    }

    suspend fun recordPlayback(
        url: String,
        title: String,
        position: Long,
        duration: Long,
        type: String,
        playlistId: Long? = null
    ) = withContext(Dispatchers.IO) {
        if (url.isBlank() || title.isBlank()) return@withContext
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: 0L
        val pct = if (duration > 0) (position.toFloat() / duration.toFloat()) else 0f
        val item = PlaybackHistory(url, title, position, duration, pct, type, System.currentTimeMillis(), targetPlId)
        historyDao.insert(item)
    }

    suspend fun deletePlaybackHistory(src: String, playlistId: Long? = null) = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            historyDao.deleteBySrcAndPlaylist(src, targetPlId)
        } else {
            historyDao.deleteBySrc(src)
        }
    }

    suspend fun clearAllPlaybackHistory(playlistId: Long? = null) = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            historyDao.clearForPlaylist(targetPlId)
        } else {
            historyDao.clearAll()
        }
    }

    // ─── FILE CACHE MANAGEMENT ──────────────────────────────────────────

    private fun getCacheFile(playlistIdStr: String, type: String): File {
        val safeId = playlistIdStr.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(application.cacheDir, "iptv_${safeId}_${type}.json")
    }

    suspend fun loadCachedData(playlistIdStr: String, type: String): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val file = getCacheFile(playlistIdStr, type)
        if (!file.exists() || file.length() == 0L) return@withContext emptyMap()
        try {
            file.source().buffer().use { source ->
                val typeAdapter = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
                    Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        Types.newParameterizedType(List::class.java, IptvItem::class.java)
                    )
                )
                typeAdapter.fromJson(source) ?: emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file cache for $type", e)
            emptyMap()
        }
    }

    suspend fun saveCachedData(playlistIdStr: String, type: String, data: Map<String, List<IptvItem>>) = withContext(Dispatchers.IO) {
        val file = getCacheFile(playlistIdStr, type)
        try {
            file.sink().buffer().use { sink ->
                val typeAdapter = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
                    Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        Types.newParameterizedType(List::class.java, IptvItem::class.java)
                    )
                )
                typeAdapter.toJson(sink, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file cache for $type", e)
        }
    }

    suspend fun clearFileCache(playlistIdStr: String) = withContext(Dispatchers.IO) {
        listOf("tv", "movie", "series").forEach { type ->
            getCacheFile(playlistIdStr, type).delete()
        }
    }

    // ─── IPTV DATA FETCHING (FACADE DELEGATION) ─────────────────────────

    suspend fun loadLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        val result = when (IptvSourceType.fromPlaylist(playlist)) {
            IptvSourceType.STALKER -> stalkerSource.loadLiveCategories(playlist)
            IptvSourceType.XTREAM -> xtreamSource.loadLiveCategories(playlist)
            IptvSourceType.M3U -> m3uSource.loadLiveCategories(playlist)
            IptvSourceType.UNKNOWN -> emptyMap()
        }
        if (result.isNotEmpty()) {
            saveCachedData(plIdStr, "tv", result)
        }
        result
    }

    // ─── SERIES EPISODES FETCHING ────────────────────────────────────────

    suspend fun fetchSeriesEpisodes(playlist: Playlist, seriesId: String, seriesCmd: String? = null): List<SeriesSeason> = withContext(Dispatchers.IO) {
        when (IptvSourceType.fromPlaylist(playlist)) {
            IptvSourceType.STALKER -> stalkerSource.fetchSeriesEpisodes(playlist, seriesId, seriesCmd)
            IptvSourceType.XTREAM -> xtreamSource.fetchSeriesEpisodes(playlist, seriesId)
            IptvSourceType.M3U -> m3uSource.fetchSeriesEpisodes(playlist, seriesId)
            IptvSourceType.UNKNOWN -> emptyList()
        }
    }

    suspend fun fetchXtreamSeriesEpisodes(playlist: Playlist, seriesId: String): List<SeriesSeason> = withContext(Dispatchers.IO) {
        xtreamSource.fetchSeriesEpisodes(playlist, seriesId)
    }

    suspend fun fetchStalkerSeriesEpisodes(playlist: Playlist, seriesId: String, seriesCmd: String? = null): List<SeriesSeason> = withContext(Dispatchers.IO) {
        stalkerSource.fetchSeriesEpisodes(playlist, seriesId, seriesCmd)
    }

    // ─── STREAM & PLAY URL RESOLUTION ───────────────────────────────────

    suspend fun resolvePlayUrl(playlist: Playlist, cmdOrUrl: String, mediaType: String = "series"): String = withContext(Dispatchers.IO) {
        val resolved = when (IptvSourceType.fromPlaylist(playlist)) {
            IptvSourceType.STALKER -> stalkerSource.resolvePlayUrl(playlist, cmdOrUrl, mediaType)
            else -> cmdOrUrl
        }
        if (IptvSourceType.fromPlaylist(playlist) == IptvSourceType.STALKER && resolved.isNotBlank()) {
            com.example.motor.player.PlayerRepository.registerStreamReResolver(resolved) {
                val freshUrl = stalkerSource.resolvePlayUrl(playlist, cmdOrUrl, mediaType)
                Pair(freshUrl, stalkerSource.buildHeaders(playlist))
            }
        }
        resolved
    }

    suspend fun resolveStalkerPlayUrl(playlist: Playlist, cmd: String, mediaType: String = "series"): String = withContext(Dispatchers.IO) {
        val resolved = stalkerSource.resolvePlayUrl(playlist, cmd, mediaType)
        if (resolved.isNotBlank()) {
            com.example.motor.player.PlayerRepository.registerStreamReResolver(resolved) {
                val freshUrl = stalkerSource.resolvePlayUrl(playlist, cmd, mediaType)
                Pair(freshUrl, stalkerSource.buildHeaders(playlist))
            }
        }
        resolved
    }

    fun getStreamHeaders(playlist: Playlist? = null): Map<String, String> {
        val targetPl = playlist ?: sessionRepo.activePlaylist.value
        if (targetPl == null) {
            return mapOf("User-Agent" to "IPTVSmartersPro/3.1.5 (Linux; Android 11)")
        }
        return when (IptvSourceType.fromPlaylist(targetPl)) {
            IptvSourceType.STALKER -> stalkerSource.buildHeaders(targetPl)
            IptvSourceType.M3U -> m3uSource.getStreamHeaders(targetPl)
            IptvSourceType.XTREAM -> mapOf("User-Agent" to "IPTVSmartersPro/3.1.5 (Linux; Android 11)")
            else -> mapOf("User-Agent" to "IPTVSmartersPro/3.1.5 (Linux; Android 11)")
        }
    }

    suspend fun resolveStreamUrl(playlist: Playlist, item: IptvItem, mediaType: String = "live"): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val result = when (IptvSourceType.fromPlaylist(playlist)) {
            IptvSourceType.STALKER -> stalkerSource.resolveStreamUrl(playlist, item, mediaType)
            IptvSourceType.XTREAM -> Pair(item.url ?: "", getStreamHeaders(playlist))
            IptvSourceType.M3U -> m3uSource.resolveStreamUrl(playlist, item)
            IptvSourceType.UNKNOWN -> Pair(item.url ?: "", getStreamHeaders(playlist))
        }
        if (result.first.isNotBlank()) {
            com.example.motor.player.PlayerRepository.registerStreamReResolver(result.first) {
                when (IptvSourceType.fromPlaylist(playlist)) {
                    IptvSourceType.STALKER -> stalkerSource.resolveStreamUrl(playlist, item, mediaType)
                    IptvSourceType.XTREAM -> Pair(item.url ?: "", getStreamHeaders(playlist))
                    IptvSourceType.M3U -> m3uSource.resolveStreamUrl(playlist, item)
                    IptvSourceType.UNKNOWN -> Pair(item.url ?: "", getStreamHeaders(playlist))
                }
            }
        }
        result
    }

    // ─── CATALOG FETCHING & CREDENTIAL RESOLUTION ───────────────────────

    fun parseXtreamCredentialsDirect(rawInput: String): XtreamCredentials? {
        return xtreamSource.parseCredentialsDirect(rawInput)
    }

    suspend fun resolveXtreamCredentials(rawInput: String): XtreamCredentials? = withContext(Dispatchers.IO) {
        xtreamSource.resolveCredentials(rawInput)
    }

    suspend fun fetchXtreamCatalog(
        host: String,
        port: String,
        user: String,
        pass: String,
        onProgress: (String) -> Unit
    ): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        xtreamSource.fetchCatalog(host, port, user, pass, onProgress)
    }

    suspend fun fetchM3uCatalog(
        playlist: Playlist,
        onProgress: (String) -> Unit
    ): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        m3uSource.fetchCatalog(playlist, onProgress)
    }

    suspend fun fetchStalkerCatalog(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit,
        onPartialUpdate: (String, List<IptvItem>) -> Unit,
        onSessionReady: (Map<String, Any>) -> Unit,
        playlistId: Long? = null
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        stalkerSource.fetchCatalog(
            host = host,
            mac = mac,
            device = device,
            onProgress = onProgress,
            onPartialUpdate = onPartialUpdate,
            onSessionReady = onSessionReady,
            playlistId = playlistId
        )
    }

    suspend fun performStalkerHandshake(
        host: String,
        mac: String,
        device: StalkerDevice,
        playlistId: Long? = null
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        stalkerSource.performHandshake(host, mac, device, playlistId)
    }

    suspend fun fetchStalkerTv(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> = withContext(Dispatchers.IO) {
        stalkerSource.fetchTv(host, mac, device, onProgress, onPartialUpdate, existingSession)
    }

    suspend fun fetchStalkerVod(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> = withContext(Dispatchers.IO) {
        stalkerSource.fetchVod(host, mac, device, onProgress, onPartialUpdate, existingSession)
    }

    suspend fun fetchStalkerSeries(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> = withContext(Dispatchers.IO) {
        stalkerSource.fetchSeries(host, mac, device, onProgress, onPartialUpdate, existingSession)
    }

    // ─── MEDIA METADATA FETCHING (XTREAM / VOD) ──────────────────────────

    suspend fun fetchXtreamVodInfo(
        host: String,
        port: String,
        user: String,
        pass: String,
        vodId: String
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
        xtreamSource.fetchVodInfo(host, port, user, pass, vodId)
    }

    suspend fun fetchXtreamSeriesInfo(
        host: String,
        port: String,
        user: String,
        pass: String,
        seriesId: String
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
        xtreamSource.fetchSeriesInfo(host, port, user, pass, seriesId)
    }


    companion object {
        @Volatile
        private var INSTANCE: IptvRepository? = null

        fun getInstance(application: Application, sessionRepo: PortalSessionRepository): IptvRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IptvRepository(application, sessionRepo).also { INSTANCE = it }
            }
        }

        fun isDividerOrHeader(name: String): Boolean {
            return BaseEngine.isDividerOrHeader(name)
        }

        fun cleanTitleAndExtractYear(rawTitle: String): TmdbService.CleanedTitle {
            return TmdbRepository.getInstance().cleanTitleAndExtractYear(rawTitle)
        }
    }
}
