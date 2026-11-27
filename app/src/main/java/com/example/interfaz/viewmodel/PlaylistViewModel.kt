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
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PlaylistConnectionGuard
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.ThrottledCategoryCacheSaver
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
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)

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
        // DETECCIÓN Y RESOLUCIÓN DE CRASH EN ARRANQUE:
        // Si el proceso murió en una conexión previa, recuperamos el estado seguro
        val crashInfo = PlaylistConnectionGuard.checkAndResolveCrashOnStartup(application)
        if (crashInfo != null) {
            val crashedId = crashInfo.crashedPlaylistId
            Log.e(TAG, "CRASH GUARD: Proceso murió en conexión previa de playlist $crashedId. Restaurando estado seguro.")
            val currentActive = sessionRepo.activePlaylist.value
            if (currentActive?.id == crashedId) {
                val allPls = getPlaylists()
                val stable = allPls.find { it.id != crashedId && mediaCacheRepo.hasAnyCache(it.id) }
                    ?: allPls.find { it.id != crashedId }
                if (stable != null) {
                    sessionRepo.setActivePlaylist(stable.id)
                    mediaCacheRepo.setActivePlaylist(stable.id)
                }
            }
        }

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

        // Cancel any ongoing connection/download immediately when switching profiles or logging out
        viewModelScope.launch {
            sessionRepo.currentProfile
                .map { it?.id }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    backgroundRefreshJob?.cancel()
                    _isBackgroundRefreshing.value = false
                    cancelConnection()
                }
        }
    }

    private var connectGeneration = 0
    private var backgroundRefreshJob: Job? = null
    private var stalkerBackgroundJob: Job? = null
    private val _isBackgroundRefreshing = MutableStateFlow(false)
    val isBackgroundRefreshing: StateFlow<Boolean> = _isBackgroundRefreshing.asStateFlow()

    /**
     * Determina de forma segura la playlist para el arranque:
     * Si la lista activa registrada falló o crasheó y no tiene caché, busca una alternativa estable con caché.
     */
    fun getSafeStartupPlaylist(playlistsList: List<Playlist>): Playlist? {
        if (playlistsList.isEmpty()) return null
        val active = playlistsList.find { it.active }
        if (active != null) {
            val isFailed = PlaylistConnectionGuard.isPlaylistFailed(getApplication(), active.id)
            val hasCache = mediaCacheRepo.hasAnyCache(active.id)
            if (!isFailed || hasCache) {
                return active
            }
            // La playlist activa falló y no tiene caché. Buscar alternativa estable con caché:
            val stableWithCache = playlistsList.find { it.id != active.id && mediaCacheRepo.hasAnyCache(it.id) }
            if (stableWithCache != null) {
                Log.i(TAG, "getSafeStartupPlaylist: Lista activa ${active.id} sin caché/fallida. Seleccionando lista estable ${stableWithCache.id}")
                sessionRepo.setActivePlaylist(stableWithCache.id)
                mediaCacheRepo.setActivePlaylist(stableWithCache.id)
                return stableWithCache
            }
        }
        val withCache = playlistsList.find { mediaCacheRepo.hasAnyCache(it.id) }
        return withCache ?: playlistsList.firstOrNull()
    }

    /**
     * Gestión del arranque y cambios de perfil según la regla de prioridad de caché:
     * - Si existe caché local: se usa inmediatamente para que Home cargue sin demora.
     * - Si la lista es Stalker: reautenticación/handshake silencioso en segundo plano sin descargar catálogo.
     * - Si autoUpdate = true: se dispara actualización silenciosa en segundo plano (sin bloquear UI).
     * - Si autoUpdate = false: NO se descarga nada del servidor.
     * - Si NO existe caché alguna: se realiza la primera conexión normal con descarga,
     *   protegido por PlaylistConnectionGuard para evitar cualquier crash loop.
     */
    fun startupPlaylist(pl: Playlist, autoUpdate: Boolean) {
        val hasCache = mediaCacheRepo.hasAnyCache(pl.id)

        if (hasCache) {
            // Re-handshake silencioso para Stalker si corresponde (sin volver a descargar canales/películas/series)
            val isStalkerPl = pl.type.lowercase() in listOf("stalker", "mac")
            if (isStalkerPl) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        sessionRepo.reconnectActivePlaylistSilently()
                    } catch (_: Throwable) {
                    }
                }
            }

            // Iniciar precarga de caché caliente en memoria en segundo plano
            mediaCacheRepo.preloadWarmCache(pl.id)

            // Si autoUpdate está activado, actualizar en segundo plano
            if (autoUpdate) {
                refreshPlaylistInBackground(pl)
            }
        } else {
            // PROTECCIÓN CONTRA CRASH LOOP:
            // Si la playlist no tiene caché y fue marcada como fallida o causó un crash anterior:
            // NUNCA auto-conectar en el arranque; permitir que la app inicie de forma segura y estable.
            val isFailed = PlaylistConnectionGuard.isPlaylistFailed(getApplication(), pl.id)
            if (isFailed) {
                val reason = PlaylistConnectionGuard.getFailedReason(getApplication(), pl.id)
                Log.w(TAG, "startupPlaylist: Omitiendo auto-conexión en arranque para playlist ${pl.id} (${pl.name}). Razón: $reason")
                _connectStatus.value = null
                return
            }

            connectPlaylist(pl, silent = false)
        }
    }

    /**
     * Actualización silenciosa en segundo plano:
     * - Descarga la versión nueva sin mostrar loaders modales ni interrumpir la UI.
     * - Si la descarga o parseo falla: NO borra la caché existente, mantiene intacta la última versión.
     * - Si tiene éxito: guarda la nueva caché y notifica a los ViewModels (via mediaCacheRepo.notifyCacheUpdated).
     */
    fun refreshPlaylistInBackground(pl: Playlist) {
        if (backgroundRefreshJob?.isActive == true) {
            return
        }

        backgroundRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            _isBackgroundRefreshing.value = true

            try {
                var finalCatalog: Map<String, List<IptvItem>>? = null
                var expiryDate: String = pl.expires
                var finalPlaylist = pl

                val isStalkerPl = pl.type.lowercase() in listOf("stalker", "mac")
                val isM3uPl = pl.type.lowercase() in listOf("m3u", "m3u8")

                if (isStalkerPl) {
                    val dev = JsonUtils.stalkerDeviceFromJson(pl.stalkerDeviceJson)
                    val catalog = iptvRepo.fetchStalkerCatalog(
                        host = pl.host,
                        mac = pl.mac,
                        device = dev,
                        onProgress = { /* background silent */ },
                        onPartialUpdate = { _, _ -> /* no-op */ },
                        onSessionReady = { session ->
                            sessionRepo.setStalkerSession(pl.id.toString(), session)
                            Log.i("STALKER_PLAYLIST_READY", "PLAYLIST_ID=${pl.id} SESSION_READY=true")
                        }
                    )
                    val tv = catalog["tv"] as? List<IptvItem> ?: emptyList()
                    val movie = catalog["movie"] as? List<IptvItem> ?: emptyList()
                    val series = catalog["series"] as? List<IptvItem> ?: emptyList()
                    finalCatalog = mapOf("tv" to tv, "movie" to movie, "series" to series)
                    val sessionMap = catalog["stalkerSession"] as? Map<String, Any>
                    if (sessionMap != null) {
                        sessionRepo.setStalkerSession(pl.id.toString(), sessionMap)
                    }
                } else if (isM3uPl) {
                    val inputUrl = pl.url.ifEmpty { pl.host }
                    val creds = iptvRepo.resolveXtreamCredentials(inputUrl)
                    if (creds != null) {
                        finalPlaylist = pl.copy(
                            host = creds.host,
                            port = creds.port,
                            user = creds.user,
                            pass = creds.pass
                        )
                    }
                    val catalog = iptvRepo.fetchM3uCatalog(finalPlaylist) { /* background silent */ }
                    finalCatalog = catalog
                    val userInfo = authDataToExpiry(catalog)
                    if (!userInfo.isNullOrEmpty()) expiryDate = userInfo
                } else {
                    var host = pl.host
                    var port = pl.port
                    var user = pl.user
                    var pass = pl.pass

                    if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
                        val inputUrl = pl.url.ifEmpty { pl.host }
                        val creds = iptvRepo.resolveXtreamCredentials(inputUrl)
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

                    if (host.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) {
                        val catalog = iptvRepo.fetchXtreamCatalog(host, port, user, pass) { /* background silent */ }
                        finalCatalog = catalog
                        val userInfo = authDataToExpiry(catalog)
                        if (!userInfo.isNullOrEmpty()) expiryDate = userInfo
                    }
                }

                if (finalCatalog != null) {
                    val tvItems = finalCatalog["tv"] ?: emptyList()
                    val movieItems = finalCatalog["movie"] ?: emptyList()
                    val seriesItems = finalCatalog["series"] ?: emptyList()
                    val total = tvItems.size + movieItems.size + seriesItems.size

                    // REGLA CRÍTICA: Solo si la descarga trajo datos válidos sobreescribimos la caché
                    if (total > 0) {
                        val tvMap = tvItems.groupBy { it.group }
                        val movieMap = movieItems.groupBy { it.group }
                        val seriesMap = seriesItems.groupBy { it.group }

                        try {
                            mediaCacheRepo.saveCategoryCache(pl.id, "tv", tvMap)
                            mediaCacheRepo.saveCategoryCache(pl.id, "movie", movieMap)
                            mediaCacheRepo.saveCategoryCache(pl.id, "series", seriesMap)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Error saving background cache", t)
                        }

                        if (expiryDate.isNotEmpty() && expiryDate != pl.expires) {
                            val currentLists = playlists.value.map { p ->
                                if (p.id == pl.id) finalPlaylist.copy(active = true, expires = expiryDate) else p
                            }
                            updateCurrentProfileField(playlists = currentLists)
                        }

                        // Notificar a todos los ViewModels para actualizar Home, Movies, Series, Live TV
                        mediaCacheRepo.notifyCacheUpdated(pl.id)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                _isBackgroundRefreshing.value = false
            }
        }
    }

    // ─── CONNECTION / PLAYLIST LOADING ────────────────────────────────────────

    fun connectPlaylist(pl: Playlist, silent: Boolean = false) {
        val currentGen = ++connectGeneration

        // Cancel any pending background refresh if a manual connection is initiated
        backgroundRefreshJob?.cancel()
        _isBackgroundRefreshing.value = false

        // Limpiar cualquier marca de fallo anterior ya que el usuario/app está reintentando explícitamente
        PlaylistConnectionGuard.clearFailure(getApplication(), pl.id)

        // Capturar la playlist previa que estaba activa de forma estable
        val previousActivePlaylistId = sessionRepo.activePlaylist.value?.id

        // 1. REGISTRO EN DISCO DEL INTENTO (CRASH GUARD):
        // Si el proceso muere durante esta conexión, el próximo arranque sabrá exactamente qué lista falló.
        PlaylistConnectionGuard.recordConnectionAttempt(getApplication(), pl.id, previousActivePlaylistId)

        // REGLA FUNDAMENTAL DE ESTABILIDAD:
        // NO llamar a sessionRepo.setActivePlaylist(pl.id) aquí.
        // La lista NUNCA se persiste como activa en el perfil / BD hasta que la conexión y descarga
        // hayan sido verificadas con éxito total. Si falla o crashea, la lista previa queda intacta.

        // Si ya tiene caché local, notificarla como lista para su uso inmediato en memoria
        if (mediaCacheRepo.hasAnyCache(pl.id)) {
            mediaCacheRepo.preloadWarmCache(pl.id)
        }

        if (_connectingPlaylistId.value == pl.id && playlistConnectionJob?.isActive == true) {
            if (!silent) {
                _connectStatus.value = "connecting"
                if (_connectLog.value.isEmpty()) _connectLog.value = "Conectando al servidor..."
                _connectErrorMsg.value = null
            }
            return
        }

        // Reducir retención de memoria de la lista saliente antes de iniciar conexión/descarga de la nueva:
        mediaCacheRepo.clearMemoryCache(keepActiveTvOnly = true)
        mediaCacheRepo.cancelLoadsExcept(previousActivePlaylistId ?: pl.id)

        _connectingPlaylistId.value = pl.id
        if (!silent) {
            _connectStatus.value = "connecting"
            _connectLog.value = "Conectando al servidor..."
            _connectErrorMsg.value = null
        }
        playlistConnectionJob?.cancel()
        stalkerBackgroundJob?.cancel()
        _stalkerDownloading.value = false
        _stalkerDownloadStats.value = ""

        playlistConnectionJob = viewModelScope.launch {
            try {
                if (currentGen != connectGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Cancelled before connection work (current=$connectGeneration)")
                    PlaylistConnectionGuard.recordConnectionCancelled(getApplication(), pl.id)
                    return@launch
                }

                if (!silent) _connectLog.value = "Conectando al servidor..."

                var finalCatalog: Map<String, List<IptvItem>>? = null
                var expiryDate: String = pl.expires
                var finalPlaylist = pl

                val isStalkerPl = pl.type.lowercase() in listOf("stalker", "mac")
                val isM3uPl = pl.type.lowercase() in listOf("m3u", "m3u8")
                if (isStalkerPl) {
                    if (!silent) _connectLog.value = "Conectando al portal Stalker (handshake)..."
                    val dev = JsonUtils.stalkerDeviceFromJson(pl.stalkerDeviceJson)

                    // PASO 1: Handshake y autenticación / sesión
                    val sessionMap = iptvRepo.performStalkerHandshake(
                        host = pl.host,
                        mac = pl.mac,
                        device = dev,
                        playlistId = pl.id
                    )
                    if (currentGen != connectGeneration) {
                        PlaylistConnectionGuard.recordConnectionCancelled(getApplication(), pl.id)
                        return@launch
                    }
                    sessionRepo.setStalkerSession(pl.id.toString(), sessionMap)
                    val negotiatedDev = (sessionMap["device"] as? StalkerDevice) ?: dev

                    // PASO 2: Obtener estructura/categorías y canales de TV
                    if (!silent) _connectLog.value = "Obteniendo categorías y canales de TV..."
                    val tvChannels = iptvRepo.fetchStalkerTv(
                        host = pl.host,
                        mac = pl.mac,
                        device = negotiatedDev,
                        onProgress = { msg ->
                            if (currentGen == connectGeneration && !silent) {
                                _connectLog.value = msg
                            }
                        },
                        existingSession = sessionMap
                    )

                    if (currentGen != connectGeneration) {
                        PlaylistConnectionGuard.recordConnectionCancelled(getApplication(), pl.id)
                        return@launch
                    }

                    // ─── CONEXIÓN STALKER VÁLIDA: ACTIVAR NUEVA PLAYLIST INMEDIATAMENTE ───
                    // A. TV disponible: Guardar canales de TV en la caché
                    if (tvChannels.isNotEmpty()) {
                        val tvMap = tvChannels.groupBy { it.group }
                        withContext(Dispatchers.IO) {
                            if (currentGen != connectGeneration) return@withContext
                            try {
                                mediaCacheRepo.saveCategoryCache(pl.id, "tv", tvMap)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Error guardando caché TV Stalker", t)
                            }
                        }
                    }

                    // B. Limpiar guardián y registrar éxito de conexión
                    PlaylistConnectionGuard.recordConnectionSuccess(getApplication(), pl.id)

                    // C. ACTIVAR NUEVA PLAYLIST INMEDIATAMENTE en sesión y Room
                    sessionRepo.setActivePlaylist(pl.id)
                    mediaCacheRepo.setActivePlaylist(pl.id)

                    val updatedPlaylists = playlists.value.map { p ->
                        if (p.id == pl.id) {
                            finalPlaylist.copy(
                                active = true,
                                expires = if (expiryDate.isNotEmpty()) expiryDate else p.expires
                            )
                        } else {
                            p.copy(active = false)
                        }
                    }
                    updateCurrentProfileField(playlists = updatedPlaylists)

                    // D. Notificar actualización de caché (Home y Live TV listos de inmediato)
                    mediaCacheRepo.notifyCacheUpdated(pl.id)

                    val wasDismissed = _connectStatus.value == null
                    if (!silent && !wasDismissed) {
                        _connectStatus.value = "ok"
                        _connectLog.value = "¡Conectado! TV disponible (${tvChannels.size} canales)."
                    }

                    // E. Movies y Series ──► descarga/procesamiento en background
                    stalkerBackgroundJob?.cancel()
                    val stalkerJobGen = currentGen
                    stalkerBackgroundJob = viewModelScope.launch(Dispatchers.IO) {
                        val bgScope = this
                        val partialCacheSaver = ThrottledCategoryCacheSaver { type, grouped ->
                            mediaCacheRepo.saveCategoryCache(pl.id, type, grouped)
                        }
                        _stalkerDownloading.value = true
                        _stalkerDownloadStats.value = "Descargando películas en segundo plano..."
                        try {
                            // 1. Descargar Películas (VOD)
                            val movies = iptvRepo.fetchStalkerVod(
                                host = pl.host,
                                mac = pl.mac,
                                device = negotiatedDev,
                                onProgress = { msg ->
                                    if (stalkerJobGen == connectGeneration) {
                                        _stalkerDownloadStats.value = msg
                                    }
                                },
                                onPartialUpdate = { type, items ->
                                    partialCacheSaver.submitPartial(
                                        scope = bgScope,
                                        type = type,
                                        itemsSnapshot = items,
                                        shouldContinue = { stalkerJobGen == connectGeneration }
                                    )
                                },
                                existingSession = sessionMap
                            )
                            if (movies.isNotEmpty() && stalkerJobGen == connectGeneration) {
                                try {
                                    partialCacheSaver.saveFinal(
                                        type = "movie",
                                        items = movies,
                                        shouldContinue = { stalkerJobGen == connectGeneration }
                                    )
                                    mediaCacheRepo.notifyCacheUpdated(pl.id)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Error guardando caché películas Stalker", t)
                                }
                            }

                            // 2. Descargar Series
                            if (stalkerJobGen == connectGeneration) {
                                _stalkerDownloadStats.value = "Descargando series en segundo plano..."
                                val series = iptvRepo.fetchStalkerSeries(
                                    host = pl.host,
                                    mac = pl.mac,
                                    device = negotiatedDev,
                                    onProgress = { msg ->
                                        if (stalkerJobGen == connectGeneration) {
                                            _stalkerDownloadStats.value = msg
                                        }
                                    },
                                    onPartialUpdate = { type, items ->
                                        partialCacheSaver.submitPartial(
                                            scope = bgScope,
                                            type = type,
                                            itemsSnapshot = items,
                                            shouldContinue = { stalkerJobGen == connectGeneration }
                                        )
                                    },
                                    existingSession = sessionMap
                                )
                                if (series.isNotEmpty() && stalkerJobGen == connectGeneration) {
                                    try {
                                        partialCacheSaver.saveFinal(
                                            type = "series",
                                            items = series,
                                            shouldContinue = { stalkerJobGen == connectGeneration }
                                        )
                                        mediaCacheRepo.notifyCacheUpdated(pl.id)
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "Error guardando caché series Stalker", t)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error en descarga en segundo plano de películas/series Stalker para playlist ${pl.id}", t)
                        } finally {
                            if (stalkerJobGen == connectGeneration) {
                                _stalkerDownloading.value = false
                                _stalkerDownloadStats.value = ""
                            }
                        }
                    }

                    return@launch
                } else if (isM3uPl) {
                    if (!silent) _connectLog.value = "Conectando con motor M3U (Xtream)..."
                    val inputUrl = pl.url.ifEmpty { pl.host }
                    val creds = iptvRepo.resolveXtreamCredentials(inputUrl)
                    if (creds != null) {
                        finalPlaylist = pl.copy(
                            host = creds.host,
                            port = creds.port,
                            user = creds.user,
                            pass = creds.pass
                        )
                    }
                    val catalog = iptvRepo.fetchM3uCatalog(finalPlaylist) { msg ->
                        if (currentGen == connectGeneration && !silent) {
                            _connectLog.value = msg
                        }
                    }
                    finalCatalog = catalog
                    val userInfo = authDataToExpiry(catalog)
                    if (!userInfo.isNullOrEmpty()) expiryDate = userInfo
                } else {
                    if (!silent) _connectLog.value = "Conectando..."
                    var host = pl.host
                    var port = pl.port
                    var user = pl.user
                    var pass = pl.pass

                    if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
                        val inputUrl = pl.url.ifEmpty { pl.host }
                        if (!silent) _connectLog.value = "Analizando lista..."
                        val creds = iptvRepo.resolveXtreamCredentials(inputUrl)
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

                    val catalog = iptvRepo.fetchXtreamCatalog(host, port, user, pass) { msg ->
                        if (currentGen == connectGeneration && !silent) {
                            _connectLog.value = msg
                        }
                    }
                    finalCatalog = catalog
                    val userInfo = authDataToExpiry(catalog)
                    if (!userInfo.isNullOrEmpty()) expiryDate = userInfo
                }

                if (currentGen != connectGeneration) {
                    PlaylistConnectionGuard.recordConnectionCancelled(getApplication(), pl.id)
                    return@launch
                }

                val catalogMutable = (finalCatalog ?: emptyMap()).toMutableMap()
                finalCatalog = null

                val tvItems = catalogMutable.remove("tv") ?: emptyList()
                val tvCount = tvItems.size
                if (tvCount > 0) {
                    val tvMap = tvItems.groupBy { it.group }
                    withContext(Dispatchers.IO) {
                        if (currentGen != connectGeneration) return@withContext
                        try {
                            mediaCacheRepo.saveCategoryCache(pl.id, "tv", tvMap)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error guardando caché TV", t)
                        }
                    }
                }

                val movieItems = catalogMutable.remove("movie") ?: emptyList()
                val movieCount = movieItems.size
                if (movieCount > 0) {
                    val movieMap = movieItems.groupBy { it.group }
                    withContext(Dispatchers.IO) {
                        if (currentGen != connectGeneration) return@withContext
                        try {
                            mediaCacheRepo.saveCategoryCache(pl.id, "movie", movieMap)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error guardando caché Películas", t)
                        }
                    }
                }

                val seriesItems = catalogMutable.remove("series") ?: emptyList()
                val seriesCount = seriesItems.size
                if (seriesCount > 0) {
                    val seriesMap = seriesItems.groupBy { it.group }
                    withContext(Dispatchers.IO) {
                        if (currentGen != connectGeneration) return@withContext
                        try {
                            mediaCacheRepo.saveCategoryCache(pl.id, "series", seriesMap)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error guardando caché Series", t)
                        }
                    }
                }
                catalogMutable.clear()

                val total = tvCount + movieCount + seriesCount
                if (total == 0) throw NetworkError.NotFound("La lista está vacía")

                Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Catalog parsed & saved: tv=$tvCount, movies=$movieCount, series=$seriesCount.")

                if (currentGen != connectGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Discarding profile update (current=$connectGeneration)")
                    PlaylistConnectionGuard.recordConnectionCancelled(getApplication(), pl.id)
                    return@launch
                }

                // ─── CONEXIÓN EXITOSA CONFIRMADA ──────────────────────────────────
                // 1. Limpiar el estado de intento en vuelo y marcas de error en el guardián
                PlaylistConnectionGuard.recordConnectionSuccess(getApplication(), pl.id)

                // 2. Establecer la nueva playlist como activa en la sesión y persistir en Room DB
                sessionRepo.setActivePlaylist(pl.id)
                mediaCacheRepo.setActivePlaylist(pl.id)

                val updatedPlaylists = playlists.value.map { p ->
                    if (p.id == pl.id) {
                        finalPlaylist.copy(
                            active = true,
                            expires = if (expiryDate.isNotEmpty()) expiryDate else p.expires
                        )
                    } else {
                        p.copy(active = false)
                    }
                }
                updateCurrentProfileField(playlists = updatedPlaylists)

                // 3. Notificar a los ViewModels para recargar inmediatamente Home, LiveTv, Movies, Series
                mediaCacheRepo.notifyCacheUpdated(pl.id)

                val wasDismissed = _connectStatus.value == null
                if (!silent && !wasDismissed) {
                    _connectStatus.value = "ok"
                    _connectLog.value = "¡Conectado! $total elementos cargados."
                }
                Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Connection finished for id=${pl.id}.")
            } catch (e: Throwable) {
                stalkerBackgroundJob?.cancel()
                _stalkerDownloading.value = false
                _stalkerDownloadStats.value = ""

                val isCancelled = e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException

                if (isCancelled) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Connection cancelled for id=${pl.id}")
                    PlaylistConnectionGuard.recordConnectionCancelled(getApplication(), pl.id)
                    // Si se cancela la conexión de una nueva lista, restaurar la lista previa activa
                    if (previousActivePlaylistId != null && previousActivePlaylistId != pl.id) {
                        sessionRepo.setActivePlaylist(previousActivePlaylistId)
                        mediaCacheRepo.setActivePlaylist(previousActivePlaylistId)
                    }
                    throw e
                }

                Log.e(TAG, "PLAYLIST_SWITCH: [PlaylistVM Gen $currentGen] Error connecting playlist id=${pl.id}", e)
                val rawErrMsg = e.message ?: "Error de conexión"
                val friendlyMessage = if (rawErrMsg.contains("La lista no funciona", ignoreCase = true)) {
                    rawErrMsg
                } else {
                    "La lista no funciona: $rawErrMsg"
                }

                // 1. Registrar fallo en el guardián para impedir que en el próximo arranque se intente auto-conectar
                PlaylistConnectionGuard.recordConnectionFailed(getApplication(), pl.id, friendlyMessage)

                // 2. CONSERVAR LA PLAYLIST ANTERIOR ESTABLE COMO ACTIVA:
                // Si la conexión falló, nunca dejamos la lista fallida como activa ni persistida
                if (previousActivePlaylistId != null && previousActivePlaylistId != pl.id) {
                    sessionRepo.setActivePlaylist(previousActivePlaylistId)
                    mediaCacheRepo.setActivePlaylist(previousActivePlaylistId)
                }

                if (!silent && currentGen == connectGeneration) {
                    _connectStatus.value = "error"
                    _connectErrorMsg.value = friendlyMessage
                    _connectLog.value = "✗ $friendlyMessage"
                }
            } finally {
                if (currentGen == connectGeneration) {
                    _connectingPlaylistId.value = null
                }
            }
        }
    }

    fun dismissConnectDialog() {
        _connectStatus.value = null
        _connectErrorMsg.value = null
        _connectLog.value = ""
    }

    fun hideConnectDialog() {
        _connectStatus.value = null
        _connectErrorMsg.value = null
        _connectLog.value = ""
    }

    fun cancelConnection() {
        playlistConnectionJob?.cancel()
        stalkerBackgroundJob?.cancel()
        _stalkerDownloading.value = false
        _stalkerDownloadStats.value = ""
        _connectingPlaylistId.value = null
        _connectStatus.value = null
        _connectErrorMsg.value = null
        _connectLog.value = ""
    }

    fun parseXtreamCredentialsDirect(rawUrl: String): com.example.motor.parser.XtreamCredentials? {
        return iptvRepo.parseXtreamCredentialsDirect(rawUrl)
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
            val currentList = JsonUtils.playlistsFromJson(current.playlistsJson)
            val deleted = currentList.find { it.id == playlistId }

            if (_connectingPlaylistId.value == playlistId || deleted?.active == true) {
                backgroundRefreshJob?.cancel()
                _isBackgroundRefreshing.value = false
                cancelConnection()
            }

            var newActivePlaylist: Playlist? = null
            var wasDeletedActive = false

            // 1. Atomic removal inside profileUpdateMutex using the freshest profile row
            sessionRepo.updateCurrentProfile { prof ->
                val plList = JsonUtils.playlistsFromJson(prof.playlistsJson)
                val target = plList.find { it.id == playlistId } ?: return@updateCurrentProfile prof
                wasDeletedActive = target.active
                val remaining = plList.filter { it.id != playlistId }
                val updatedList = if (target.active && remaining.isNotEmpty()) {
                    val first = remaining.first().copy(active = true)
                    newActivePlaylist = first
                    listOf(first) + remaining.drop(1)
                } else {
                    newActivePlaylist = remaining.find { it.active } ?: remaining.firstOrNull()
                    remaining
                }
                prof.copy(playlistsJson = JsonUtils.playlistsToJson(updatedList))
            }

            // 2. Clean caches, DB favorites/history, and crash/failure flags for the deleted playlist
            withContext(Dispatchers.IO) {
                try {
                    listOf("tv", "movie", "series").forEach { type ->
                        val file = mediaCacheRepo.getCacheFile(playlistId, type)
                        if (file.exists()) file.delete()
                    }
                    mediaCacheRepo.clearCategoryCache(playlistId)
                    if (newActivePlaylist != null) {
                        mediaCacheRepo.setActivePlaylist(newActivePlaylist!!.id)
                    } else {
                        mediaCacheRepo.setActivePlaylist(-1L)
                    }
                    iptvRepo.clearAllFavorites(playlistId)
                    iptvRepo.clearAllPlaybackHistory(playlistId)
                    PlaylistConnectionGuard.clearAllForPlaylist(getApplication(), playlistId)
                    sessionRepo.clearStalkerSession(playlistId.toString())
                    sessionRepo.clearStalkerCompatState(playlistId.toString())
                } catch (t: Throwable) {
                    Log.e(TAG, "Error deleting cache/DB state for playlist ID $playlistId", t)
                }
            }

            // 3. If the active playlist was deleted and another playlist took over, prepare its cache safely
            val nextActive = newActivePlaylist
            if (wasDeletedActive && nextActive != null) {
                val autoUpd = sessionRepo.settings.value.autoUpdate
                startupPlaylist(nextActive, autoUpdate = autoUpd)
            }
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

    override fun getSubtitleKeys(): Pair<String, String> {
        val s = sessionRepo.settings.value
        return Pair(s.openSubtitlesKey, s.assrtToken)
    }

    override fun updateSubtitleKeys(openSubtitlesKey: String?, assrtToken: String?) {
        viewModelScope.launch {
            val current = sessionRepo.settings.value
            val newOpenSub = if (!openSubtitlesKey.isNullOrBlank()) openSubtitlesKey.trim() else current.openSubtitlesKey
            val newAssrt = if (!assrtToken.isNullOrBlank()) assrtToken.trim() else current.assrtToken
            val updated = current.copy(
                openSubtitlesKey = newOpenSub,
                assrtToken = newAssrt
            )
            updateCurrentProfileField(settings = updated)
            com.example.motor.subtitles.OpenSubtitlesProvider.setGlobalApiKey(newOpenSub.takeIf { it.isNotBlank() })
            com.example.motor.subtitles.AssrtSubtitleProvider.setGlobalToken(newAssrt.takeIf { it.isNotBlank() })
            android.util.Log.i("PlaylistViewModel", "Subtitle keys updated via sync: OpenSubtitles=${newOpenSub.isNotBlank()}, Assrt=${newAssrt.isNotBlank()}")
        }
    }

    override fun syncData(playlists: List<Playlist>, openSubtitlesKey: String?, assrtToken: String?) {
        viewModelScope.launch {
            val current = sessionRepo.settings.value
            val newOpenSub = if (!openSubtitlesKey.isNullOrBlank()) openSubtitlesKey.trim() else current.openSubtitlesKey
            val newAssrt = if (!assrtToken.isNullOrBlank()) assrtToken.trim() else current.assrtToken
            val updatedSettings = current.copy(
                openSubtitlesKey = newOpenSub,
                assrtToken = newAssrt
            )
            updateCurrentProfileField(
                playlists = playlists,
                settings = updatedSettings
            )
            com.example.motor.subtitles.OpenSubtitlesProvider.setGlobalApiKey(newOpenSub.takeIf { it.isNotBlank() })
            com.example.motor.subtitles.AssrtSubtitleProvider.setGlobalToken(newAssrt.takeIf { it.isNotBlank() })
            android.util.Log.i("PlaylistViewModel", "syncData atomic update completed: playlists=${playlists.size}, OpenSubtitles=${newOpenSub.isNotBlank()}, Assrt=${newAssrt.isNotBlank()}")
        }
    }
}
