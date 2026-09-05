package com.example.motor.repository

import android.app.Application
import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.config.Playlist
import com.example.motor.config.StalkerDevice
import com.example.motor.db.*
import com.example.motor.parser.StalkerParser
import com.example.motor.parser.XtreamParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class IptvRepository private constructor(
    private val application: Application,
    private val sessionRepo: PortalSessionRepository
) {
    private val TAG = "IptvRepository"
    private val db = AppDatabase.getDatabase(application)
    private val historyDao = db.playbackHistoryDao()
    private val favoriteDao = db.favoriteItemDao()

    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    // Memory cache for series episodes
    private val seriesEpisodesCache = ConcurrentHashMap<String, List<EpgChannelData>>()

    fun getCachedEpisodes(key: String): List<EpgChannelData>? {
        seriesEpisodesCache[key]?.let { return it }
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val roomResult = mediaCacheRepo.getEpisodesCache(key)
            if (roomResult.data != null) {
                seriesEpisodesCache[key] = roomResult.data
                roomResult.data
            } else null
        }
    }

    fun cacheEpisodes(key: String, episodes: List<EpgChannelData>) {
        seriesEpisodesCache[key] = episodes
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            mediaCacheRepo.saveEpisodesCache(key, "", episodes)
        }
    }

    fun clearEpisodesCache() { seriesEpisodesCache.clear() }

    // Favorites & History Flows
    val favoriteItems: Flow<List<FavoriteItem>> = favoriteDao.getAllFavoritesFlow()
    val recentHistory: Flow<List<PlaybackHistory>> = historyDao.getAllHistoryFlow()

    suspend fun addFavorite(id: String, title: String, type: String, url: String, logo: String?) = withContext(Dispatchers.IO) {
        val fav = FavoriteItem(id, title, type, url, logo, System.currentTimeMillis())
        favoriteDao.insert(fav)
    }

    suspend fun removeFavorite(id: String) = withContext(Dispatchers.IO) {
        favoriteDao.deleteById(id)
    }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.getById(id) != null
    }

    suspend fun recordPlayback(url: String, title: String, position: Long, duration: Long, type: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || title.isBlank()) return@withContext
        val pct = if (duration > 0) (position.toFloat() / duration.toFloat()) else 0f
        val item = PlaybackHistory(url, title, position, duration, pct, type, System.currentTimeMillis())
        historyDao.insert(item)
    }

    suspend fun deletePlaybackHistory(src: String) = withContext(Dispatchers.IO) {
        historyDao.deleteBySrc(src)
    }

    suspend fun clearAllPlaybackHistory() = withContext(Dispatchers.IO) {
        historyDao.clearAll()
    }

    // ─── FILE CACHE MANAGEMENT ──────────────────────────────────────────

    private fun getCacheFile(playlistIdStr: String, type: String): File {
        val safeId = playlistIdStr.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(application.cacheDir, "iptv_${safeId}_${type}.json")
    }

    suspend fun loadCachedData(playlistIdStr: String, type: String): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val file = getCacheFile(playlistIdStr, type)
        if (!file.exists()) return@withContext emptyMap()
        try {
            val json = file.readText()
            JsonUtils.iptvItemsMapFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file cache for $type", e)
            emptyMap()
        }
    }

    suspend fun saveCachedData(playlistIdStr: String, type: String, data: Map<String, List<IptvItem>>) = withContext(Dispatchers.IO) {
        val file = getCacheFile(playlistIdStr, type)
        try {
            val json = JsonUtils.moshi.adapter(Any::class.java).toJson(data)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file cache for $type", e)
        }
    }

    suspend fun clearFileCache(playlistIdStr: String) = withContext(Dispatchers.IO) {
        listOf("tv", "movie", "series").forEach { type ->
            getCacheFile(playlistIdStr, type).delete()
        }
    }

    // ─── IPTV DATA FETCHING ─────────────────────────────────────────────

    suspend fun loadLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        when (playlist.type.lowercase()) {
            "mac", "stalker" -> loadStalkerLiveCategories(playlist)
            "xtream", "m3u" -> loadXtreamLiveCategories(playlist)
            else -> emptyMap()
        }
    }

    private suspend fun loadStalkerLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        val device = if (playlist.stalkerDeviceJson.isNotBlank()) JsonUtils.stalkerDeviceFromJson(playlist.stalkerDeviceJson) else StalkerDevice()
        val result = mutableMapOf<String, List<IptvItem>>()

        try {
            val catalogMap = StalkerParser.fetchStalkerCatalog(
                host = playlist.host,
                mac = playlist.mac,
                device = device,
                onProgress = {}
            )
            @Suppress("UNCHECKED_CAST")
            val channels = (catalogMap["tv"] as? List<IptvItem>) ?: emptyList()

            if (channels.isNotEmpty()) {
                result["Todos"] = channels
            }

            val grouped = channels.groupBy { if (it.group.isBlank()) "General" else it.group }
            grouped.forEach { (catName, items) ->
                if (catName.isNotBlank() && items.isNotEmpty()) {
                    result[catName] = items
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker live channels", e)
        }

        saveCachedData(plIdStr, "tv", result)
        result
    }

    private suspend fun loadXtreamLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        val result = mutableMapOf<String, List<IptvItem>>()
        try {
            var host = playlist.host
            var port = playlist.port
            var user = playlist.user
            var pass = playlist.pass

            if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
                val inputUrl = playlist.url.ifEmpty { playlist.host }
                val creds = XtreamParser.resolveXtreamCredentials(inputUrl)
                if (creds != null) {
                    host = creds.host
                    port = creds.port
                    user = creds.user
                    pass = creds.pass
                }
            }

            val catalog = XtreamParser.fetchXtreamCatalog(host, port, user, pass) { }
            val channels = catalog["tv"] ?: emptyList()

            if (channels.isNotEmpty()) {
                result["Todos"] = channels
            }

            val grouped = channels.groupBy { if (it.group.isBlank()) "General" else it.group }
            grouped.forEach { (catName, items) ->
                if (catName.isNotBlank() && items.isNotEmpty()) {
                    result[catName] = items
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Xtream live channels", e)
        }

        saveCachedData(plIdStr, "tv", result)
        result
    }

    // ─── SERIES EPISODES FETCHING ────────────────────────────────────────

    suspend fun fetchXtreamSeriesEpisodes(playlist: Playlist, seriesId: String): List<EpgChannelData> = withContext(Dispatchers.IO) {
        try {
            var host = playlist.host
            var port = playlist.port.ifEmpty { "80" }
            var user = playlist.user
            var pass = playlist.pass

            if (host.isBlank() || user.isBlank() || pass.isBlank()) {
                val creds = XtreamParser.resolveXtreamCredentials(playlist.url.ifEmpty { playlist.host })
                if (creds != null) {
                    host = creds.host
                    port = creds.port
                    user = creds.user
                    pass = creds.pass
                }
            }

            if (host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                XtreamParser.fetchXtreamSeriesEpisodes(
                    host = host,
                    port = port,
                    user = user,
                    pass = pass,
                    seriesId = seriesId
                )
            } else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Xtream series episodes", e)
            emptyList()
        }
    }

    suspend fun fetchStalkerSeriesEpisodes(playlist: Playlist, seriesId: String): List<EpgChannelData> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        if (session == null) return@withContext emptyList()
        val portalUrl = session["portalUrl"] as? String ?: return@withContext emptyList()
        val mac = session["mac"] as? String ?: return@withContext emptyList()
        val token = session["token"] as? String ?: ""
        val device = session["device"] as? StalkerDevice ?: return@withContext emptyList()

        try {
            StalkerParser.fetchStalkerSeriesEpisodes(portalUrl, mac, seriesId, token, device)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker series episodes", e)
            emptyList()
        }
    }

    suspend fun resolveStalkerPlayUrl(playlist: Playlist, cmd: String, mediaType: String = "series"): String = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }
        if (session == null) return@withContext cmd
        try {
            StalkerParser.resolveStalkerPlayLink(session, cmd, mediaType)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Stalker play link", e)
            cmd
        }
    }

    // ─── STREAM LINK RESOLUTION ─────────────────────────────────────────

    suspend fun resolveStreamUrl(playlist: Playlist, item: IptvItem, mediaType: String = "live"): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        when (playlist.type.lowercase()) {
            "mac", "stalker" -> resolveStalkerStreamUrl(playlist, item, mediaType)
            else -> Pair(item.url ?: "", emptyMap())
        }
    }

    private suspend fun resolveStalkerStreamUrl(playlist: Playlist, item: IptvItem, mediaType: String): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        if (session == null) {
            return@withContext Pair(item.url ?: "", emptyMap())
        }

        val cmd = item.stalkerCmd ?: item.url ?: ""
        val resolvedUrl = StalkerParser.resolveStalkerPlayLink(session, cmd, if (mediaType == "live") "itv" else mediaType)

        val macClean = playlist.mac.trim().uppercase()
        val device = if (playlist.stalkerDeviceJson.isNotBlank()) JsonUtils.stalkerDeviceFromJson(playlist.stalkerDeviceJson) else StalkerDevice()
        val token = (session["token"] as? String) ?: ""
        val userAgent = if (device.userAgent.isNotBlank()) device.userAgent else "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        val cookieHeader = if (token.isNotEmpty()) "mac=$macClean; stb_lang=en; timezone=Europe/Madrid; token=$token;" else "mac=$macClean; stb_lang=en; timezone=Europe/Madrid;"

        val portalUrl = (session["portalUrl"] as? String) ?: playlist.host
        val portalBase = portalUrl.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")

        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Cookie" to cookieHeader,
            "X-User-Agent" to (if (device.xUserAgent.isNotBlank()) device.xUserAgent else "Model: MAG254; Link: Ethernet"),
            "Accept" to "*/*"
        )
        if (portalBase.isNotBlank()) {
            headers["Referer"] = "$portalBase/c/"
        }

        Pair(resolvedUrl, headers)
    }

    companion object {
        @Volatile
        private var INSTANCE: IptvRepository? = null

        fun getInstance(application: Application, sessionRepo: PortalSessionRepository): IptvRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IptvRepository(application, sessionRepo).also { INSTANCE = it }
            }
        }
    }
}
