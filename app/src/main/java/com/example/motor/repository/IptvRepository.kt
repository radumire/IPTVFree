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

    fun getCachedEpisodes(key: String): List<SeriesSeason>? = seriesEpisodesCache[key]

    suspend fun getCachedEpisodesAsync(key: String): List<SeriesSeason>? {
        seriesEpisodesCache[key]?.let { return it }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
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
        playlistId: Long? = null,
        stalkerCmd: String? = null,
        seriesId: String? = null,
        categoryGroup: String? = null,
        drmScheme: String? = null,
        drmLicenseUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: 0L
        val safeId = id.trim().ifEmpty { "${normalizeFavoriteType(type)}::${title.trim()}" }
        val fav = FavoriteItem(
            id = safeId,
            title = title,
            type = normalizeFavoriteType(type),
            url = url,
            logo = logo,
            addedAt = System.currentTimeMillis(),
            playlistId = targetPlId,
            stalkerCmd = stalkerCmd?.takeIf { it.isNotBlank() },
            seriesId = seriesId?.takeIf { it.isNotBlank() },
            categoryGroup = categoryGroup?.takeIf { it.isNotBlank() },
            drmScheme = drmScheme?.takeIf { it.isNotBlank() },
            drmLicenseUrl = drmLicenseUrl?.takeIf { it.isNotBlank() }
        )
        favoriteDao.insert(fav)
    }

    suspend fun addFavoriteItem(
        item: IptvItem,
        type: String,
        playlistId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val normType = normalizeFavoriteType(type)
        val favId = buildFavoriteId(normType, item)
        addFavorite(
            id = favId,
            title = item.name,
            type = normType,
            url = item.url ?: "",
            logo = item.logo,
            playlistId = playlistId,
            stalkerCmd = item.stalkerCmd,
            seriesId = item.seriesId,
            categoryGroup = item.group,
            drmScheme = item.drmScheme,
            drmLicenseUrl = item.drmLicenseUrl
        )
    }

    suspend fun removeFavorite(id: String, playlistId: Long? = null) = withContext(Dispatchers.IO) {
        val cleanKey = id.trim()
        if (cleanKey.isEmpty()) return@withContext
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            favoriteDao.deleteByAnyKeyAndPlaylist(cleanKey, targetPlId)
            favoriteDao.deleteByIdAndPlaylist(cleanKey, targetPlId)
        } else {
            favoriteDao.deleteById(cleanKey)
        }
    }

    suspend fun removeFavoriteById(id: String, playlistId: Long? = null) = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id
        if (targetPlId != null && targetPlId > 0) {
            favoriteDao.deleteByIdAndPlaylist(id, targetPlId)
        } else {
            favoriteDao.deleteById(id)
        }
    }

    suspend fun removeFavoriteItem(
        item: IptvItem,
        type: String,
        playlistId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val normType = normalizeFavoriteType(type)
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: return@withContext
        if (targetPlId <= 0L) return@withContext

        val favId = buildFavoriteId(normType, item)
        favoriteDao.deleteByIdTypeAndPlaylist(favId, normType, targetPlId)

        // Clean up any matching row stored under legacy keys or identical source metadata
        val currentFavs = favoriteDao.getByTypeAndPlaylist(normType, targetPlId)
        for (fav in currentFavs) {
            if (matchesFavorite(item, normType, fav)) {
                favoriteDao.deleteByIdAndPlaylist(fav.id, targetPlId)
            }
        }
    }

    suspend fun toggleFavoriteItem(
        item: IptvItem,
        type: String,
        playlistId: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val normType = normalizeFavoriteType(type)
        val targetPlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: return@withContext false
        if (targetPlId <= 0L) return@withContext false

        val existing = favoriteDao.getByTypeAndPlaylist(normType, targetPlId)
        val matched = existing.filter { matchesFavorite(item, normType, it) }
        if (matched.isNotEmpty()) {
            for (fav in matched) {
                favoriteDao.deleteByIdAndPlaylist(fav.id, targetPlId)
            }
            false
        } else {
            addFavoriteItem(item, normType, targetPlId)
            true
        }
    }

    suspend fun isFavorite(id: String, playlistId: Long? = null): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext false
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
            val current = INSTANCE
            if (current != null && current.application === application && current.sessionRepo === sessionRepo) {
                return current
            }
            return synchronized(this) {
                val existing = INSTANCE
                if (existing != null && existing.application === application && existing.sessionRepo === sessionRepo) {
                    existing
                } else {
                    IptvRepository(application, sessionRepo).also { INSTANCE = it }
                }
            }
        }

        fun isDividerOrHeader(name: String): Boolean {
            return BaseEngine.isDividerOrHeader(name)
        }

        fun cleanTitleAndExtractYear(rawTitle: String): TmdbService.CleanedTitle {
            return TmdbRepository.getInstance().cleanTitleAndExtractYear(rawTitle)
        }

        fun normalizeFavoriteType(type: String): String {
            return when (type.trim().lowercase()) {
                "live", "tv" -> "tv"
                "vod", "movie" -> "movie"
                "series" -> "series"
                else -> type.trim().lowercase().ifEmpty { "movie" }
            }
        }

        fun buildFavoriteId(type: String, item: IptvItem): String {
            val normType = normalizeFavoriteType(type)
            val uniqueSourceKey = item.stalkerCmd?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.url?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it|${item.name.trim()}" }
                ?: "${item.group.trim()}|${item.name.trim()}"
            return "$normType::$uniqueSourceKey"
        }

        fun matchesFavorite(item: IptvItem, type: String, fav: FavoriteItem): Boolean {
            val normType = normalizeFavoriteType(type)
            if (normalizeFavoriteType(fav.type) != normType) return false

            if (fav.id == buildFavoriteId(normType, item)) return true

            val favCmd = fav.stalkerCmd?.trim()?.takeIf { it.isNotEmpty() }
            if (favCmd != null) {
                return item.stalkerCmd?.trim() == favCmd
            }

            val favSeriesId = fav.seriesId?.trim()?.takeIf { it.isNotEmpty() }
            if (favSeriesId != null) {
                return item.seriesId?.trim() == favSeriesId
            }

            val favUrl = fav.url.trim().takeIf { it.isNotEmpty() }
            if (favUrl != null) {
                return item.url?.trim() == favUrl
            }

            // Legacy v5 fallback (rows saved without stalkerCmd/seriesId and with empty url)
            val cleanName = item.name.trim()
            val cleanTvgId = item.tvgId?.trim()?.takeIf { it.isNotEmpty() }
            return (cleanName.isNotEmpty() && (fav.title.trim() == cleanName || fav.id.trim() == cleanName)) ||
                (cleanTvgId != null && fav.id.trim() == cleanTvgId)
        }

        fun isItemFavorited(item: IptvItem, type: String, favorites: List<FavoriteItem>): Boolean {
            return favorites.any { matchesFavorite(item, type, it) }
        }

        fun toFallbackIptvItem(fav: FavoriteItem): IptvItem {
            return IptvItem(
                name = fav.title,
                group = fav.categoryGroup?.takeIf { it.isNotBlank() } ?: "Favoritos",
                logo = fav.logo,
                tvgId = fav.id.takeIf { !it.contains("::") },
                url = fav.url.takeIf { it.isNotBlank() },
                stalkerCmd = fav.stalkerCmd?.takeIf { it.isNotBlank() },
                seriesId = fav.seriesId?.takeIf { it.isNotBlank() },
                drmScheme = fav.drmScheme?.takeIf { it.isNotBlank() },
                drmLicenseUrl = fav.drmLicenseUrl?.takeIf { it.isNotBlank() }
            )
        }

        fun resolveCatalogItemForFavorite(
            fav: FavoriteItem,
            catalogMap: Map<String, List<IptvItem>>
        ): IptvItem {
            val normType = normalizeFavoriteType(fav.type)
            val matched = catalogMap.values.asSequence().flatten().firstOrNull { item ->
                matchesFavorite(item, normType, fav)
            }
            return matched ?: toFallbackIptvItem(fav)
        }
    }
}
