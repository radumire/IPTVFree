package com.example.interfaz.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.computeMoviesData
import com.example.interfaz.state.MoviesState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoviesViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    private val _uiState = MutableStateFlow(MoviesState())
    val uiState: StateFlow<MoviesState> = _uiState.asStateFlow()

    val settings: StateFlow<PlayerSettings> = sessionRepo.settings
    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist

    private val _movies = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val movies: StateFlow<Map<String, List<IptvItem>>> = _movies.asStateFlow()

    private val _precomputedMoviesData = MutableStateFlow<PrecomputedMoviesData?>(null)
    val precomputedMoviesData: StateFlow<PrecomputedMoviesData?> = _precomputedMoviesData.asStateFlow()

    val favoritesList: StateFlow<List<FavoriteItem>> = iptvRepo.favoriteItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            iptvRepo.favoriteItems.collect { favs ->
                val favIds = favs.filter { it.type == "movie" }.map { it.id }.toSet()
                _uiState.update { state -> state.copy(favoriteIds = favIds) }
            }
        }
        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                if (pl != null) {
                    val cached = mediaCacheRepo.getCategoryCache(pl.id, "movie").data
                    if (!cached.isNullOrEmpty() && _movies.value.isEmpty()) {
                        updateMovies(cached)
                    }
                }
            }
        }
    }

    fun updateMovies(moviesMap: Map<String, List<IptvItem>>) {
        _movies.value = moviesMap
        if (moviesMap.isEmpty()) {
            _precomputedMoviesData.value = null
        } else if (_precomputedMoviesData.value == null) {
            _precomputedMoviesData.value = computeMoviesData(moviesMap)
        }
    }

    fun updatePrecomputedData(data: PrecomputedMoviesData?) {
        _precomputedMoviesData.value = data
    }

    fun cleanTitleAndExtractYear(rawTitle: String) = tmdbRepo.cleanTitleAndExtractYear(rawTitle)
    fun getCachedYear(title: String, isSeries: Boolean = false) = tmdbRepo.getCachedYear(title, isSeries)
    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean = false) = tmdbRepo.fetchPosterAndYear(title, isSeries)
    suspend fun fetchXtreamMediaDetails(item: IptvItem, activePlaylist: Playlist?) =
        tmdbRepo.fetchXtreamMediaDetails(item, activePlaylist, isSeries = false)
    suspend fun searchSubtitles(rawTitle: String) =
        tmdbRepo.searchSubtitles(rawTitle, isSeries = false)

    fun selectMovie(item: IptvItem) {
        _uiState.update { it.copy(selectedMovie = item) }
        viewModelScope.launch {
            val poster = tmdbRepo.getMoviePoster(item.name)
            if (poster != null) {
                _uiState.update { 
                    it.copy(
                        tmdbBackdrop = poster
                    ) 
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            val filtered = state.movies.filter { it.name.contains(query, ignoreCase = true) }
            state.copy(searchQuery = query, filteredMovies = filtered)
        }
    }

    suspend fun resolvePlayUrl(movie: IptvItem): String = withContext(Dispatchers.IO) {
        val pl = sessionRepo.activePlaylist.value
        if (pl != null) {
            val (resolvedUrl, _) = iptvRepo.resolveStreamUrl(pl, movie, "vod")
            resolvedUrl.ifBlank { movie.url ?: "" }
        } else {
            movie.url ?: ""
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

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        iptvRepo.isFavorite(id)
    }

    fun toggleFavorite(item: IptvItem) {
        viewModelScope.launch {
            val pl = sessionRepo.activePlaylist.value ?: return@launch
            val id = "movie-${pl.id}-${item.name}"
            if (_uiState.value.favoriteIds.contains(id)) {
                iptvRepo.removeFavorite(id)
            } else {
                iptvRepo.addFavorite(id, item.name, "movie", item.url ?: "", item.logo)
            }
        }
    }
}
