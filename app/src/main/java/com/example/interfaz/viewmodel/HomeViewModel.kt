package com.example.interfaz.viewmodel

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.state.HomeState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.db.PlaybackHistory
import com.example.motor.db.UserProfile
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HomeViewModel"

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    private val _uiState = MutableStateFlow(HomeState())
    val uiState: StateFlow<HomeState> = _uiState.asStateFlow()

    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist
    val activePlaylistName: StateFlow<String> = sessionRepo.activePlaylist.map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val currentProfile: StateFlow<UserProfile?> = sessionRepo.currentProfile
    val isOnline: StateFlow<Boolean> = sessionRepo.isOnline
    val playlists: StateFlow<List<Playlist>> = sessionRepo.playlists
    val settings: StateFlow<PlayerSettings> = sessionRepo.settings

    val favoritesList: StateFlow<List<FavoriteItem>> = iptvRepo.favoriteItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackHistory: StateFlow<List<PlaybackHistory>> = iptvRepo.recentHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isContentHydrating = MutableStateFlow(false)
    val isContentHydrating: StateFlow<Boolean> = _isContentHydrating.asStateFlow()

    private val _liveChannels = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val liveChannels: StateFlow<Map<String, List<IptvItem>>> = _liveChannels.asStateFlow()

    private val _precomputedMoviesData = MutableStateFlow<PrecomputedMoviesData?>(null)
    val precomputedMoviesData: StateFlow<PrecomputedMoviesData?> = _precomputedMoviesData.asStateFlow()

    private val _precomputedSeriesData = MutableStateFlow<PrecomputedSeriesData?>(null)
    val precomputedSeriesData: StateFlow<PrecomputedSeriesData?> = _precomputedSeriesData.asStateFlow()

    private val _cinemaBarSelectedTab = MutableStateFlow("series")
    val cinemaBarSelectedTab: StateFlow<String> = _cinemaBarSelectedTab.asStateFlow()

    fun setCinemaBarSelectedTab(tabKey: String) {
        _cinemaBarSelectedTab.value = tabKey
    }

    private val _isStalkerDownloading = MutableStateFlow(false)
    val isStalkerDownloading: StateFlow<Boolean> = _isStalkerDownloading.asStateFlow()
    val stalkerDownloading: StateFlow<Boolean> = _isStalkerDownloading.asStateFlow()

    private val _stalkerDownloadStats = MutableStateFlow("")
    val stalkerDownloadStats: StateFlow<String> = _stalkerDownloadStats.asStateFlow()

    private val _newestMovies = MutableStateFlow<List<IptvItem>>(emptyList())
    val newestMovies: StateFlow<List<IptvItem>> = _newestMovies.asStateFlow()

    private val _isResolvingMovie = MutableStateFlow(false)
    val isResolvingMovie: StateFlow<Boolean> = _isResolvingMovie.asStateFlow()

    private var playlistGeneration = 0
    private var lastLoadedPlaylistId: Long? = null
    private var tvLoadJob: kotlinx.coroutines.Job? = null
    private var newestMoviesJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            combine(
                sessionRepo.activePlaylist,
                sessionRepo.currentProfile,
                sessionRepo.isOnline,
                iptvRepo.recentHistory,
                iptvRepo.favoriteItems
            ) { activePl, profile, isOnline, history, favorites ->
                val activePlId = activePl?.id
                val filteredHistory = if (activePlId != null) {
                    history.filter { it.playlistId == activePlId }
                } else {
                    emptyList()
                }

                val filteredFavorites = if (activePlId != null) {
                    favorites.filter { it.playlistId == activePlId }
                } else {
                    emptyList()
                }

                val continueWatching = filteredHistory
                    .filter { it.type == "movie" || it.type == "series" }
                    .filter { it.pct > 0.05f && it.pct < 0.95f }

                _uiState.update { current ->
                    current.copy(
                        activePlaylist = activePl,
                        activePlaylistName = activePl?.name ?: "",
                        activePlaylistHost = activePl?.host ?: "",
                        activePlaylistType = activePl?.type ?: "",
                        activeProfile = profile,
                        activeProfileName = profile?.name ?: "",
                        recentChannels = filteredHistory.take(10),
                        favorites = filteredFavorites,
                        continueWatching = continueWatching,
                        isOnline = isOnline,
                        isLoading = false
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                val activeId = pl?.id
                val isDifferent = activeId != lastLoadedPlaylistId
                if (isDifferent) {
                    // Instantly isolate continueWatching, recentChannels, and favorites on playlist change
                    _uiState.update { current ->
                        current.copy(
                            activePlaylist = pl,
                            activePlaylistName = pl?.name ?: "",
                            activePlaylistHost = pl?.host ?: "",
                            activePlaylistType = pl?.type ?: "",
                            continueWatching = if (activeId != null) current.continueWatching.filter { it.playlistId == activeId } else emptyList(),
                            recentChannels = if (activeId != null) current.recentChannels.filter { it.playlistId == activeId } else emptyList(),
                            favorites = if (activeId != null) current.favorites.filter { it.playlistId == activeId } else emptyList()
                        )
                    }
                }
                if (isDifferent || (pl != null && _liveChannels.value.isEmpty() && _precomputedMoviesData.value == null)) {
                    loadCatalogForPlaylist(pl)
                }
            }
        }

        viewModelScope.launch {
            mediaCacheRepo.cacheUpdateEvents.collect { updatedId ->
                val currentActive = sessionRepo.activePlaylist.value
                if (currentActive != null && currentActive.id == updatedId) {
                    loadCatalogForPlaylist(currentActive)
                }
            }
        }
    }

    private suspend fun loadCatalogForPlaylist(pl: Playlist?) {
        val generation = ++playlistGeneration
        val oldId = lastLoadedPlaylistId
        val newId = pl?.id

        tvLoadJob?.cancel()
        newestMoviesJob?.cancel()

        if (oldId != newId) {
            _liveChannels.value = emptyMap()
            _precomputedMoviesData.value = null
            _precomputedSeriesData.value = null
            _newestMovies.value = emptyList()
            _uiState.update {
                it.copy(
                    totalLiveCount = 0,
                    totalMoviesCount = 0,
                    totalSeriesCount = 0,
                    newestMovies = emptyList()
                )
            }
        }

        if (pl == null) {
            lastLoadedPlaylistId = null
            return
        }

        // 1. Obtener contadores básicos inmediatamente desde metadatos/caché sin deserializar Movies ni Series
        val tvCount = withContext(Dispatchers.IO) { mediaCacheRepo.getCategoryCount(pl.id, "tv") }
        val movieCount = withContext(Dispatchers.IO) { mediaCacheRepo.getCategoryCount(pl.id, "movie") }
        val seriesCount = withContext(Dispatchers.IO) { mediaCacheRepo.getCategoryCount(pl.id, "series") }

        if (generation != playlistGeneration) {
            return
        }

        // ─── PASO 1: Publicar inmediatamente los contadores básicos en Home sin esperar nada más ───
        _uiState.update {
            it.copy(
                totalLiveCount = tvCount,
                totalMoviesCount = movieCount,
                totalSeriesCount = seriesCount,
                isLoading = false
            )
        }
        lastLoadedPlaylistId = pl.id

        // ─── PASO 2: Cargar canales de TV en segundo plano (para reproducción rápida en Home) ───
        tvLoadJob = viewModelScope.launch(Dispatchers.IO) {
            if (generation != playlistGeneration) return@launch
            try {
                val tvResult = mediaCacheRepo.getCategoryCache(pl.id, "tv")
                if (generation != playlistGeneration) return@launch
                val tvMap = tvResult.data ?: emptyMap()
                _liveChannels.value = tvMap
                val actualLive = tvMap.values.sumOf { it.size }
                if (actualLive > 0 && actualLive != tvCount) {
                    _uiState.update { it.copy(totalLiveCount = actualLive) }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading TV channels for Home", e)
            }
        }

        // ─── PASO 3: Obtener las películas más nuevas de forma ultraligera sin deserializar el catálogo completo ───
        newestMoviesJob = viewModelScope.launch(Dispatchers.IO) {
            if (generation != playlistGeneration) return@launch
            try {
                val newest = mediaCacheRepo.getNewestMoviesPreview(pl.id, limit = 15)
                if (generation != playlistGeneration) return@launch
                _newestMovies.value = newest
                _uiState.update {
                    it.copy(newestMovies = newest)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error loading newest movies preview for Home", e)
            }
        }
    }

    fun updateStalkerDownloadState(isDownloading: Boolean, stats: String) {
        val wasDownloading = _isStalkerDownloading.value
        _isStalkerDownloading.value = isDownloading
        _stalkerDownloadStats.value = stats
        _uiState.update {
            it.copy(
                isStalkerDownloading = isDownloading,
                stalkerDownloadStats = stats
            )
        }
        if (wasDownloading && !isDownloading) {
            sessionRepo.activePlaylist.value?.let { pl ->
                viewModelScope.launch {
                    loadCatalogForPlaylist(pl)
                }
            }
        }
    }

    fun addFavorite(id: String, title: String, type: String, url: String, logo: String?) {
        viewModelScope.launch {
            val plId = sessionRepo.activePlaylist.value?.id ?: 0L
            iptvRepo.addFavorite(id, title, type, url, logo, plId)
        }
    }

    fun removeFavorite(id: String) {
        viewModelScope.launch {
            val plId = sessionRepo.activePlaylist.value?.id
            iptvRepo.removeFavorite(id, plId)
        }
    }

    fun removeFavoriteById(id: String) {
        viewModelScope.launch {
            val plId = sessionRepo.activePlaylist.value?.id
            iptvRepo.removeFavoriteById(id, plId)
        }
    }

    fun toggleFavoriteItem(item: IptvItem, type: String) {
        viewModelScope.launch {
            val plId = sessionRepo.activePlaylist.value?.id ?: return@launch
            iptvRepo.toggleFavoriteItem(item, type, plId)
        }
    }

    suspend fun isFavorite(id: String): Boolean {
        val plId = sessionRepo.activePlaylist.value?.id
        return iptvRepo.isFavorite(id, plId)
    }

    fun getFavoriteTvChannelPeers(favorites: List<FavoriteItem>): List<IptvItem> {
        val tvCatalog = _liveChannels.value
        return favorites
            .filter { IptvRepository.normalizeFavoriteType(it.type) == "tv" }
            .map { IptvRepository.resolveCatalogItemForFavorite(it, tvCatalog) }
    }

    fun deletePlaybackHistory(src: String) {
        viewModelScope.launch {
            val activePlId = sessionRepo.activePlaylist.value?.id
            iptvRepo.deletePlaybackHistory(src, activePlId)
            _uiState.update { current ->
                current.copy(
                    continueWatching = current.continueWatching.filter { !(it.src == src && (activePlId == null || it.playlistId == activePlId)) },
                    recentChannels = current.recentChannels.filter { !(it.src == src && (activePlId == null || it.playlistId == activePlId)) }
                )
            }
        }
    }

    fun clearAllPlaybackHistory() {
        viewModelScope.launch {
            val activePlId = sessionRepo.activePlaylist.value?.id
            iptvRepo.clearAllPlaybackHistory(activePlId)
            _uiState.update { current ->
                current.copy(
                    continueWatching = emptyList(),
                    recentChannels = emptyList()
                )
            }
        }
    }

    fun canPlayHistoryItem(item: PlaybackHistory): Boolean {
        val activePl = sessionRepo.activePlaylist.value ?: return false
        if (item.playlistId != activePl.id) return false
        if (item.src.isBlank()) return false
        if (activePl.host.isNotBlank() && item.src.contains("://")) {
            try {
                val plHost = com.example.motor.config.normalizeHost(activePl.host)
                    .substringAfter("://").substringBefore(':').substringBefore('/').trim()
                val itemHost = java.net.URI(item.src).host?.lowercase()?.trim() ?: ""
                if (plHost.isNotEmpty() && itemHost.isNotEmpty() && !itemHost.equals(plHost, ignoreCase = true)) {
                    return false
                }
            } catch (_: Exception) {}
        }
        return true
    }

    fun cleanTitleAndExtractYear(rawTitle: String) = tmdbRepo.cleanTitleAndExtractYear(rawTitle)
    fun getCachedYear(title: String, isSeries: Boolean = false) = tmdbRepo.getCachedYear(title, isSeries)
    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean = false) = tmdbRepo.fetchPosterAndYear(title, isSeries)
    suspend fun fetchXtreamMediaDetails(item: IptvItem, activePlaylist: Playlist?) =
        tmdbRepo.fetchXtreamMediaDetails(item, activePlaylist, isSeries = false)
    suspend fun searchMovieSubtitles(rawTitle: String) =
        tmdbRepo.searchSubtitles(rawTitle, isSeries = false)
    suspend fun fetchMediaDetails(title: String, isSeries: Boolean = false, defaultLogo: String? = null) =
        tmdbRepo.fetchMediaDetails(title, isSeries, defaultLogo)

    suspend fun resolveStalkerPlayUrl(cmd: String, type: String = "vod"): String {
        val activePl = sessionRepo.activePlaylist.value ?: return cmd
        return iptvRepo.resolveStalkerPlayUrl(activePl, cmd, type)
    }

    sealed class FavoriteResolution {
        data class PlayStream(
            val url: String,
            val title: String,
            val type: String,
            val headers: Map<String, String> = emptyMap(),
            val drmScheme: String? = null,
            val drmLicenseUrl: String? = null
        ) : FavoriteResolution()
        data class OpenSeries(val seriesItem: IptvItem) : FavoriteResolution()
        data class Error(val message: String) : FavoriteResolution()
    }

    suspend fun resolveFavorite(fav: FavoriteItem): FavoriteResolution = withContext(Dispatchers.IO) {
        val pl = sessionRepo.activePlaylist.value
        val cacheType = IptvRepository.normalizeFavoriteType(fav.type)

        val catalogMap: Map<String, List<IptvItem>> = if (pl != null) {
            if (cacheType == "tv" && _liveChannels.value.isNotEmpty()) {
                _liveChannels.value
            } else {
                mediaCacheRepo.getCategoryCache(pl.id, cacheType).data ?: emptyMap()
            }
        } else emptyMap()

        val matchedItem = catalogMap.values.asSequence().flatten().firstOrNull { item ->
            IptvRepository.matchesFavorite(item, cacheType, fav)
        }

        val effectiveItem = matchedItem ?: IptvRepository.toFallbackIptvItem(fav)

        if (cacheType == "series") {
            if (matchedItem != null || !effectiveItem.seriesId.isNullOrBlank() || !effectiveItem.stalkerCmd.isNullOrBlank()) {
                return@withContext FavoriteResolution.OpenSeries(effectiveItem)
            }
            if (fav.url.isNotBlank() && pl != null) {
                return@withContext try {
                    val resolved = iptvRepo.resolvePlayUrl(pl, fav.url, "series")
                    val headers = iptvRepo.getStreamHeaders(pl)
                    if (resolved.isNotBlank()) {
                        FavoriteResolution.PlayStream(resolved, fav.title, "series", headers)
                    } else {
                        FavoriteResolution.Error("Serie no encontrada en la lista activa")
                    }
                } catch (e: Exception) {
                    FavoriteResolution.Error("No se pudo abrir la serie: ${e.message ?: "error de conexión"}")
                }
            }
            return@withContext FavoriteResolution.Error("Serie no encontrada en la lista activa")
        }

        val mediaType = if (cacheType == "tv") "live" else "vod"
        val playType = if (cacheType == "tv") "live" else "movie"

        val hasDirectPlaySource = matchedItem != null ||
            !effectiveItem.stalkerCmd.isNullOrBlank() ||
            !effectiveItem.url.isNullOrBlank()

        if (hasDirectPlaySource && pl != null) {
            return@withContext try {
                val (resolvedUrl, headers) = iptvRepo.resolveStreamUrl(pl, effectiveItem, mediaType)
                if (resolvedUrl.isNotBlank()) {
                    FavoriteResolution.PlayStream(
                        url = resolvedUrl,
                        title = effectiveItem.name,
                        type = playType,
                        headers = headers,
                        drmScheme = effectiveItem.drmScheme,
                        drmLicenseUrl = effectiveItem.drmLicenseUrl
                    )
                } else {
                    FavoriteResolution.Error("No se pudo obtener el enlace de reproducción")
                }
            } catch (e: Exception) {
                val fallbackUrl = effectiveItem.url?.takeIf { it.isNotBlank() } ?: fav.url.takeIf { it.isNotBlank() }
                if (fallbackUrl != null) {
                    FavoriteResolution.PlayStream(
                        url = fallbackUrl,
                        title = effectiveItem.name,
                        type = playType,
                        headers = iptvRepo.getStreamHeaders(pl),
                        drmScheme = effectiveItem.drmScheme,
                        drmLicenseUrl = effectiveItem.drmLicenseUrl
                    )
                } else {
                    FavoriteResolution.Error("No se pudo resolver el favorito: ${e.message ?: "error"}")
                }
            }
        }

        if (fav.url.isNotBlank()) {
            val headers = iptvRepo.getStreamHeaders(pl)
            return@withContext FavoriteResolution.PlayStream(
                url = fav.url,
                title = fav.title,
                type = playType,
                headers = headers,
                drmScheme = fav.drmScheme,
                drmLicenseUrl = fav.drmLicenseUrl
            )
        }

        FavoriteResolution.Error("Elemento favorito no disponible en la lista activa")
    }

    fun setResolvingMovie(resolving: Boolean) {
        _isResolvingMovie.value = resolving
        _uiState.update { it.copy(isResolvingMovie = resolving) }
    }
}
