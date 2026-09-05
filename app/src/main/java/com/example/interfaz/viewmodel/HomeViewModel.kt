package com.example.interfaz.viewmodel

import android.app.Application
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
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()

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

    private val _isStalkerDownloading = MutableStateFlow(false)
    val isStalkerDownloading: StateFlow<Boolean> = _isStalkerDownloading.asStateFlow()
    val stalkerDownloading: StateFlow<Boolean> = _isStalkerDownloading.asStateFlow()

    private val _stalkerDownloadStats = MutableStateFlow("")
    val stalkerDownloadStats: StateFlow<String> = _stalkerDownloadStats.asStateFlow()

    private val _newestMovies = MutableStateFlow<List<IptvItem>>(emptyList())
    val newestMovies: StateFlow<List<IptvItem>> = _newestMovies.asStateFlow()

    private val _isResolvingMovie = MutableStateFlow(false)
    val isResolvingMovie: StateFlow<Boolean> = _isResolvingMovie.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sessionRepo.activePlaylist,
                sessionRepo.currentProfile,
                sessionRepo.isOnline,
                iptvRepo.recentHistory,
                iptvRepo.favoriteItems
            ) { activePl, profile, isOnline, history, favorites ->
                val continueWatching = history
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
                        recentChannels = history.take(10),
                        favorites = favorites,
                        continueWatching = continueWatching,
                        isOnline = isOnline,
                        isLoading = false
                    )
                }
            }.collect()
        }
    }

    fun updateChannels(channels: Map<String, List<IptvItem>>) {
        _liveChannels.value = channels
        val total = channels.values.sumOf { it.size }
        _uiState.update { it.copy(totalLiveCount = total) }
    }

    fun updateMoviesData(data: PrecomputedMoviesData?) {
        _precomputedMoviesData.value = data
        val count = data?.totalMoviesCount ?: 0
        _uiState.update { it.copy(totalMoviesCount = count) }
        viewModelScope.launch(Dispatchers.Default) {
            val newest = calculateNewestMovies(data)
            _newestMovies.value = newest
            _uiState.update { it.copy(newestMovies = newest) }
        }
    }

    fun updateSeriesData(data: PrecomputedSeriesData?) {
        _precomputedSeriesData.value = data
        val count = data?.allSeries?.size ?: 0
        _uiState.update { it.copy(totalSeriesCount = count) }
    }

    fun updateStalkerDownloadState(isDownloading: Boolean, stats: String) {
        _isStalkerDownloading.value = isDownloading
        _stalkerDownloadStats.value = stats
        _uiState.update {
            it.copy(
                isStalkerDownloading = isDownloading,
                stalkerDownloadStats = stats
            )
        }
    }

    private suspend fun calculateNewestMovies(precomputedMovies: PrecomputedMoviesData?): List<IptvItem> = withContext(Dispatchers.Default) {
        val allMovies = (precomputedMovies?.allMovies ?: emptyList())
            .filter { !it.url.isNullOrEmpty() || !it.stalkerCmd.isNullOrEmpty() }
        if (allMovies.isEmpty()) {
            emptyList()
        } else {
            allMovies
                .take(500)
                .map { item ->
                    val nameStr = item.name ?: ""
                    val groupStr = item.group ?: ""
                    val cleanedInfo = tmdbRepo.cleanTitleAndExtractYear(nameStr)
                    val yearInGroup = tmdbRepo.cleanTitleAndExtractYear(groupStr).year
                    val effectiveYear = item.year ?: cleanedInfo.year ?: yearInGroup ?: 0
                    val isEstrenoGroup = groupStr.contains("ESTRENO", ignoreCase = true) ||
                            groupStr.contains("NOVEDAD", ignoreCase = true) ||
                            groupStr.contains("NUEV", ignoreCase = true) ||
                            groupStr.contains("LATEST", ignoreCase = true) ||
                            groupStr.contains("RELEASE", ignoreCase = true)
                    Triple(item, effectiveYear, isEstrenoGroup)
                }
                .sortedWith(
                    compareByDescending<Triple<IptvItem, Int, Boolean>> { it.second }
                        .thenByDescending { it.third }
                        .thenByDescending { it.first.rating ?: 0f }
                        .thenBy { it.first.name ?: "" }
                )
                .map { it.first }
                .take(15)
        }
    }

    fun addFavorite(id: String, title: String, type: String, url: String, logo: String?) {
        viewModelScope.launch {
            iptvRepo.addFavorite(id, title, type, url, logo)
        }
    }

    fun removeFavorite(id: String) {
        viewModelScope.launch {
            iptvRepo.removeFavorite(id)
        }
    }

    suspend fun isFavorite(id: String): Boolean = iptvRepo.isFavorite(id)

    fun deletePlaybackHistory(src: String) {
        viewModelScope.launch {
            iptvRepo.deletePlaybackHistory(src)
        }
    }

    fun clearAllPlaybackHistory() {
        viewModelScope.launch {
            iptvRepo.clearAllPlaybackHistory()
        }
    }

    fun cleanTitleAndExtractYear(rawTitle: String) = tmdbRepo.cleanTitleAndExtractYear(rawTitle)
    fun getCachedYear(title: String, isSeries: Boolean = false) = tmdbRepo.getCachedYear(title, isSeries)
    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean = false) = tmdbRepo.fetchPosterAndYear(title, isSeries)
    suspend fun fetchXtreamMediaDetails(item: IptvItem, activePlaylist: Playlist?) =
        tmdbRepo.fetchXtreamMediaDetails(item, activePlaylist, isSeries = false)
    suspend fun fetchMediaDetails(title: String, isSeries: Boolean = false, defaultLogo: String? = null) =
        tmdbRepo.fetchMediaDetails(title, isSeries, defaultLogo)

    suspend fun resolveStalkerPlayUrl(cmd: String, type: String = "vod"): String {
        val activePl = sessionRepo.activePlaylist.value ?: return cmd
        return iptvRepo.resolveStalkerPlayUrl(activePl, cmd, type)
    }

    fun setResolvingMovie(resolving: Boolean) {
        _isResolvingMovie.value = resolving
        _uiState.update { it.copy(isResolvingMovie = resolving) }
    }
}
