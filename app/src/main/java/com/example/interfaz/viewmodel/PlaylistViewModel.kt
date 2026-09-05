package com.example.interfaz.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.JsonUtils
import com.example.interfaz.state.PlaylistState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.config.StalkerDevice
import com.example.motor.db.IptvItem
import com.example.motor.db.UserProfile
import com.example.motor.parser.NetworkError
import com.example.motor.parser.StalkerParser
import com.example.motor.parser.XtreamParser
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.sync.SyncServerDataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlaylistViewModel(application: Application) : AndroidViewModel(application), SyncServerDataProvider {
    private val TAG = "PlaylistViewModel"
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    val currentProfile: StateFlow<UserProfile?> = sessionRepo.currentProfile
    val playlists: StateFlow<List<Playlist>> = sessionRepo.playlists
    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist
    val activePlaylistName: StateFlow<String> = sessionRepo.activePlaylist.map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val isOnline: StateFlow<Boolean> = sessionRepo.isOnline

    private val _connectStatus = MutableStateFlow<String?>(null)
    val connectStatus: StateFlow<String?> = _connectStatus.asStateFlow()

    private val _connectErrorMsg = MutableStateFlow<String?>(null)
    val connectErrorMsg: StateFlow<String?> = _connectErrorMsg.asStateFlow()

    private val _connectLog = MutableStateFlow("")
    val connectLog: StateFlow<String> = _connectLog.asStateFlow()

    private val _connectingPlaylistId = MutableStateFlow<Long?>(null)
    val connectingPlaylistId: StateFlow<Long?> = _connectingPlaylistId.asStateFlow()

    private val _stalkerDownloading = MutableStateFlow(false)
    val stalkerDownloading: StateFlow<Boolean> = _stalkerDownloading.asStateFlow()

    private val _stalkerDownloadStats = MutableStateFlow("")
    val stalkerDownloadStats: StateFlow<String> = _stalkerDownloadStats.asStateFlow()

    private var playlistConnectionJob: Job? = null

    private val _uiState = MutableStateFlow(PlaylistState())
    val uiState: StateFlow<PlaylistState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sessionRepo.playlists,
                sessionRepo.activePlaylist,
                sessionRepo.currentProfile,
                sessionRepo.isOnline
            ) { plList, activePl, prof, online ->
                _uiState.update { state ->
                    state.copy(
                        playlists = plList,
                        activePlaylist = activePl,
                        activePlaylistName = activePl?.name ?: "",
                        currentProfile = prof,
                        isOnline = online
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            combine(
                _connectStatus,
                _connectLog,
                _connectErrorMsg,
                _stalkerDownloading,
                _stalkerDownloadStats
            ) { status, log, err, downloading, stats ->
                _uiState.update { state ->
                    state.copy(
                        connectStatus = status,
                        connectLog = log,
                        connectErrorMsg = err,
                        isConnecting = status == "connecting",
                        isLoading = status == "connecting",
                        stalkerDownloading = downloading,
                        stalkerDownloadStats = stats,
                        error = err
                    )
                }
            }.collect()
        }
    }

    private var connectGeneration = 0

    // ─── CONNECTION / PLAYLIST LOADING ────────────────────────────────────────

    fun connectPlaylist(pl: Playlist, silent: Boolean = false) {
        val currentGen = ++connectGeneration
        Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] connectPlaylist started for id=${pl.id} name=${pl.name} (silent=$silent)")

        if (_connectingPlaylistId.value == pl.id && playlistConnectionJob?.isActive == true) {
            if (!silent) {
                _connectStatus.value = "connecting"
                if (_connectLog.value.isEmpty()) _connectLog.value = "Conectando al servidor..."
                _connectErrorMsg.value = null
            }
            return
        }

        _connectingPlaylistId.value = pl.id
        if (!silent) {
            _connectStatus.value = "connecting"
            _connectLog.value = "Conectando al servidor..."
            _connectErrorMsg.value = null
        }
        playlistConnectionJob?.cancel()
        _stalkerDownloading.value = false
        _stalkerDownloadStats.value = ""

        playlistConnectionJob = viewModelScope.launch {
            try {
                if (currentGen != connectGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Cancelled before connection work (current=$connectGeneration)")
                    return@launch
                }

                if (!silent) _connectLog.value = "Conectando al servidor..."

                var finalCatalog: Map<String, List<IptvItem>>? = null
                var expiryDate: String = pl.expires
                var finalPlaylist = pl

                val isStalkerPl = pl.type.lowercase() in listOf("stalker", "mac")
                if (isStalkerPl) {
                    if (!silent) _connectLog.value = "Conectando al portal Stalker..."
                    val dev = JsonUtils.stalkerDeviceFromJson(pl.stalkerDeviceJson)
                    _stalkerDownloading.value = true
                    _stalkerDownloadStats.value = "Conectando al portal Stalker..."
                    val catalog = withContext(Dispatchers.IO) {
                        StalkerParser.fetchStalkerCatalog(
                            host = pl.host,
                            mac = pl.mac,
                            device = dev,
                            onProgress = { msg ->
                                if (currentGen == connectGeneration) {
                                    if (!silent) _connectLog.value = msg
                                    _stalkerDownloadStats.value = msg
                                }
                            },
                            onPartialUpdate = { type, items ->
                                if (currentGen == connectGeneration) {
                                    val grouped = items.groupBy { it.group }
                                    if (type == "tv") {
                                        viewModelScope.launch(Dispatchers.IO) {
                                            if (currentGen != connectGeneration) return@launch
                                            try {
                                                savePlaylistCacheToFile(pl.id, "tv", grouped)
                                                mediaCacheRepo.saveCategoryCache(pl.id, "tv", grouped)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Error guardando caché de canales TV", e)
                                            }
                                        }
                                    }
                                }
                            },
                            onSessionReady = { session ->
                                if (currentGen == connectGeneration) {
                                    sessionRepo.setStalkerSession(pl.id.toString(), session)
                                }
                            }
                        )
                    }
                    _stalkerDownloading.value = false
                    _stalkerDownloadStats.value = ""
                    val tv = catalog["tv"] as? List<IptvItem> ?: emptyList()
                    val movie = catalog["movie"] as? List<IptvItem> ?: emptyList()
                    val series = catalog["series"] as? List<IptvItem> ?: emptyList()
                    finalCatalog = mapOf("tv" to tv, "movie" to movie, "series" to series)
                    val sessionMap = catalog["stalkerSession"] as? Map<String, Any>
                    if (sessionMap != null && currentGen == connectGeneration) {
                        sessionRepo.setStalkerSession(pl.id.toString(), sessionMap)
                    }
                } else {
                    if (!silent) _connectLog.value = "Conectando..."
                    var host = pl.host
                    var port = pl.port
                    var user = pl.user
                    var pass = pl.pass

                    if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
                        val inputUrl = pl.url.ifEmpty { pl.host }
                        if (!silent) _connectLog.value = "Analizando lista..."
                        val creds = withContext(Dispatchers.IO) {
                            XtreamParser.resolveXtreamCredentials(inputUrl)
                        }
                        if (creds != null) {
                            host = creds.host
                            port = creds.port
                            user = creds.user
                            pass = creds.pass
                            finalPlaylist = pl.copy(
                                host = host,
                                port = port,
                                user = user,
                                pass = pass
                            )
                        }
                    }

                    if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                        throw NetworkError.NotFound("No se pudieron extraer las credenciales del enlace proporcionado")
                    }

                    val catalog = withContext(Dispatchers.IO) {
                        XtreamParser.fetchXtreamCatalog(host, port, user, pass) { msg ->
                            if (currentGen == connectGeneration && !silent) {
                                _connectLog.value = msg
                            }
                        }
                    }
                    finalCatalog = catalog
                    val userInfo = authDataToExpiry(catalog)
                    if (!userInfo.isNullOrEmpty()) expiryDate = userInfo
                }

                if (currentGen != connectGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Discarding fetched catalog for id=${pl.id} (current=$connectGeneration)")
                    return@launch
                }

                val tvItems = finalCatalog["tv"] ?: emptyList()
                val movieItems = finalCatalog["movie"] ?: emptyList()
                val seriesItems = finalCatalog["series"] ?: emptyList()
                val tvMap = tvItems.groupBy { it.group }
                val movieMap = movieItems.groupBy { it.group }
                val seriesMap = seriesItems.groupBy { it.group }
                val total = tvItems.size + movieItems.size + seriesItems.size

                if (total == 0) throw NetworkError.NotFound("La lista está vacía")

                Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Catalog parsed: tv=${tvItems.size}, movies=${movieItems.size}, series=${seriesItems.size}. Saving cache synchronously...")

                // Guardar la caché de forma síncrona en disco y base de datos ANTES de activar el perfil
                // De este modo, cuando MainViewModel escuche el cambio de activePlaylist, toda la caché ya está escrita
                withContext(Dispatchers.IO) {
                    if (currentGen != connectGeneration) return@withContext
                    try {
                        savePlaylistCacheToFile(pl.id, "tv", tvMap)
                        savePlaylistCacheToFile(pl.id, "movie", movieMap)
                        savePlaylistCacheToFile(pl.id, "series", seriesMap)

                        mediaCacheRepo.saveCategoryCache(pl.id, "tv", tvMap)
                        mediaCacheRepo.saveCategoryCache(pl.id, "movie", movieMap)
                        mediaCacheRepo.saveCategoryCache(pl.id, "series", seriesMap)
                        Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Cache saved synchronously to disk and Room.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error guardando caché", e)
                    }
                }

                if (currentGen != connectGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Discarding profile update (current=$connectGeneration)")
                    return@launch
                }

                val currentLists = playlists.value.map { p ->
                    if (p.id == pl.id) {
                        finalPlaylist.copy(active = true, expires = if (expiryDate.isNotEmpty()) expiryDate else p.expires)
                    } else {
                        p.copy(active = false)
                    }
                }
                updateCurrentProfileField(playlists = currentLists)

                if (!silent) {
                    _connectStatus.value = "ok"
                    _connectLog.value = "¡Conectado! $total elementos cargados."
                }
                Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Profile updated with active playlist id=${pl.id}. Finished.")
            } catch (e: Throwable) {
                _stalkerDownloading.value = false
                _stalkerDownloadStats.value = ""
                if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Connection cancelled for id=${pl.id}")
                    throw e
                }
                Log.e(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Error connecting playlist id=${pl.id}", e)
                val netErr = if (e is NetworkError) e else NetworkError.Unknown(e.message ?: "Error desconocido")
                if (!silent && currentGen == connectGeneration) {
                    _connectStatus.value = "error"
                    _connectErrorMsg.value = netErr.message
                    _connectLog.value = "✗ " + netErr.message
                }
            }
        }
    }

    fun dismissConnectDialog() {
        _connectingPlaylistId.value = null
        _connectStatus.value = null
        _connectErrorMsg.value = null
        _connectLog.value = ""
    }

    // ─── PROFILE / PLAYLIST UPDATE ────────────────────────────────────────────

    fun updateCurrentProfileField(
        name: String? = null,
        avatar: String? = null,
        pin: String? = null,
        themeId: String? = null,
        playlists: List<Playlist>? = null,
        settings: PlayerSettings? = null,
        customThemesMap: Map<String, Map<String, String>>? = null
    ) {
        viewModelScope.launch {
            sessionRepo.updateCurrentProfile { prof ->
                prof.copy(
                    name = name ?: prof.name,
                    avatar = avatar ?: prof.avatar,
                    pin = pin ?: prof.pin,
                    themeId = themeId ?: prof.themeId,
                    playlistsJson = playlists?.let { JsonUtils.playlistsToJson(it) } ?: prof.playlistsJson,
                    settingsJson = settings?.let { JsonUtils.settingsToJson(it) } ?: prof.settingsJson,
                    customThemesJson = customThemesMap?.let { JsonUtils.customThemesToJson(it) } ?: prof.customThemesJson
                )
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val current = currentProfile.value ?: return@launch
            val plList = JsonUtils.playlistsFromJson(current.playlistsJson)
            val deleted = plList.find { it.id == playlistId } ?: return@launch

            if (_connectingPlaylistId.value == playlistId || deleted.active) {
                playlistConnectionJob?.cancel()
                dismissConnectDialog()
            }

            withContext(Dispatchers.IO) {
                try {
                    listOf("tv", "movie", "series").forEach { type ->
                        val file = getPlaylistCacheFile(playlistId, type)
                        if (file.exists()) file.delete()
                    }
                    mediaCacheRepo.clearAllCaches()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting cache files for ID $playlistId", e)
                }
            }

            val remaining = plList.filter { it.id != playlistId }
            val updatedList = if (deleted.active && remaining.isNotEmpty()) {
                val first = remaining.first().copy(active = true)
                listOf(first) + remaining.drop(1)
            } else {
                remaining
            }

            sessionRepo.updateCurrentProfile { prof ->
                prof.copy(playlistsJson = JsonUtils.playlistsToJson(updatedList))
            }
        }
    }

    // ─── FILE CACHE HELPERS ───────────────────────────────────────────────────

    private fun getPlaylistCacheFile(playlistId: Long, type: String): File {
        return File(getApplication<Application>().filesDir, "playlist_cache_${playlistId}_${type}.json")
    }

    private fun savePlaylistCacheToFile(playlistId: Long, type: String, data: Map<String, List<IptvItem>>) {
        try {
            val file = getPlaylistCacheFile(playlistId, type)
            val json = JsonUtils.iptvItemsMapToJson(data)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file cache for $type", e)
        }
    }

    private fun authDataToExpiry(catalog: Map<String, List<IptvItem>>): String? {
        return try {
            val auth = catalog["auth"]?.firstOrNull() ?: return null
            auth.url
        } catch (e: Exception) {
            null
        }
    }

    // ─── SyncServerDataProvider Implementation ────────────────────────────────

    override fun getPlaylists(): List<Playlist> {
        val current = playlists.value
        if (current.isNotEmpty()) return current
        val prof = currentProfile.value ?: sessionRepo.currentProfile.value
        if (prof != null) {
            return JsonUtils.playlistsFromJson(prof.playlistsJson)
        }
        return emptyList()
    }

    override fun updatePlaylists(playlists: List<Playlist>) {
        updateCurrentProfileField(playlists = playlists)
    }

    override fun connectPlaylist(playlist: Playlist) {
        connectPlaylist(playlist, silent = false)
    }

    override fun getApplicationContext(): Context? = getApplication()
}
