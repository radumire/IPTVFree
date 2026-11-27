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
import com.example.motor.repository.MediaDetails
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.TmdbRepository
import androidx.compose.foundation.lazy.grid.LazyGridState
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoviesViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MoviesViewModel"
    }

    val moviesGridState = LazyGridState()
    val selectedCategoryFlow = MutableStateFlow("Todos")
    val pendingSelectedMovie = MutableStateFlow<IptvItem?>(null)

    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    private val _uiState = MutableStateFlow(MoviesState())
    val uiState: StateFlow<MoviesState> = _uiState.asStateFlow()

    val settings: StateFlow<PlayerSettings> = sessionRepo.settings
    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist

    private val _precomputedMoviesData = MutableStateFlow<PrecomputedMoviesData?>(null)
    val precomputedMoviesData: StateFlow<PrecomputedMoviesData?> = _precomputedMoviesData.asStateFlow()

    val movies: StateFlow<Map<String, List<IptvItem>>> = _precomputedMoviesData
        .map { it?.groupToMovies ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val favoritesList: StateFlow<List<FavoriteItem>> = iptvRepo.favoriteItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var playlistGeneration = 0
    private var lastLoadedPlaylistId: Long? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private val searchQueryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            iptvRepo.favoriteItems.collect { favs ->
                val activeId = sessionRepo.activePlaylist.value?.id
                val filtered = if (activeId != null) favs.filter { it.playlistId == activeId } else emptyList()
                val favIds = filtered.filter { IptvRepository.normalizeFavoriteType(it.type) == "movie" }
                    .flatMap { listOfNotNull(it.id, it.url.takeIf { u -> u.isNotBlank() }, it.stalkerCmd, it.title.takeIf { t -> t.isNotBlank() }) }
                    .filter { it.isNotBlank() }
                    .toSet()
                _uiState.update { state -> state.copy(favoriteIds = favIds) }
            }
        }
        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                val activeId = pl?.id
                val isDifferent = activeId != lastLoadedPlaylistId
                if (isDifferent) {
                    loadJob?.cancel()
                    _uiState.update { it.copy(favoriteIds = emptySet()) }
                    _precomputedMoviesData.value = null
                    lastLoadedPlaylistId = null
                    selectedCategoryFlow.value = "Todos"
                    viewModelScope.launch {
                        try { moviesGridState.scrollToItem(0) } catch (_: Exception) {}
                    }
                }
            }
        }
        viewModelScope.launch {
            mediaCacheRepo.cacheUpdateEvents.collect { updatedId ->
                val currentActive = sessionRepo.activePlaylist.value
                if (currentActive != null && currentActive.id == updatedId && _precomputedMoviesData.value != null) {
                    loadJob?.cancel()
                    loadJob = viewModelScope.launch {
                        loadMoviesForPlaylist(currentActive)
                    }
                }
            }
        }
        viewModelScope.launch {
            searchQueryFlow
                .debounce(250L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val currentMovies = _uiState.value.movies
                    val filtered = withContext(Dispatchers.Default) {
                        if (query.isBlank()) currentMovies
                        else currentMovies.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    _uiState.update { it.copy(filteredMovies = filtered) }
                }
        }
    }

    fun ensureMoviesLoaded() {
        val pl = sessionRepo.activePlaylist.value ?: return
        if (_precomputedMoviesData.value != null && lastLoadedPlaylistId == pl.id) {
            return
        }
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            loadMoviesForPlaylist(pl)
        }
    }

    private suspend fun loadMoviesForPlaylist(pl: Playlist?) {
        val generation = ++playlistGeneration
        val oldId = lastLoadedPlaylistId
        val newId = pl?.id

        if (oldId != newId) {
            _precomputedMoviesData.value = null
        }

        if (pl == null) {
            lastLoadedPlaylistId = null
            Log.d(TAG, "MOVIES_MIGRATION: [Gen $generation] Cleared movies (active playlist is null)")
            return
        }

        Log.d(TAG, "MOVIES_MIGRATION activePlaylist=${pl.id} (${pl.name}) generation=$generation oldPlaylistId=$oldId")

        val movieResult = withContext(Dispatchers.IO) {
            mediaCacheRepo.getCategoryCache(pl.id, "movie")
        }

        if (generation != playlistGeneration) {
            Log.d(TAG, "MOVIES_MIGRATION: [Gen $generation] Discarded stale movie cache (current is $playlistGeneration)")
            return
        }

        val cachedData = movieResult.data
        if (!cachedData.isNullOrEmpty()) {
            val precomputed = withContext(Dispatchers.Default) {
                computeMoviesData(cachedData)
            }

            if (generation != playlistGeneration) {
                Log.d(TAG, "MOVIES_MIGRATION: [Gen $generation] Discarded stale precomputed data (current is $playlistGeneration)")
                return
            }

            _precomputedMoviesData.value = precomputed
            lastLoadedPlaylistId = pl.id
            val totalMovies = cachedData.values.sumOf { it.size }
            Log.d(TAG, "MOVIES_MIGRATION cacheLoaded=true moviesCount=$totalMovies categoriesCount=${cachedData.size} generation=$generation")
        } else {
            lastLoadedPlaylistId = pl.id
            Log.d(TAG, "MOVIES_MIGRATION cacheLoaded=false moviesCount=0 generation=$generation (No cache found for playlist ${pl.id})")
        }
    }

    fun cleanTitleAndExtractYear(rawTitle: String) = tmdbRepo.cleanTitleAndExtractYear(rawTitle)
    fun getCachedYear(title: String, isSeries: Boolean = false) = tmdbRepo.getCachedYear(title, isSeries)
    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean = false) = tmdbRepo.fetchPosterAndYear(title, isSeries)
    suspend fun fetchXtreamMediaDetails(item: IptvItem, activePlaylist: Playlist?) =
        tmdbRepo.fetchXtreamMediaDetails(item, activePlaylist, isSeries = false)
    suspend fun searchSubtitles(rawTitle: String) =
        tmdbRepo.searchSubtitles(rawTitle, isSeries = false)

    suspend fun fetchMovieDetails(item: IptvItem): MediaDetails? = withContext(Dispatchers.IO) {
        val pl = sessionRepo.activePlaylist.value
        try {
            tmdbRepo.fetchXtreamMediaDetails(item, pl, isSeries = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching movie details for ${item.name}", e)
            null
        }
    }

    suspend fun searchMovieSubtitles(movieName: String) = withContext(Dispatchers.IO) {
        try {
            tmdbRepo.searchSubtitles(rawTitle = movieName, isSeries = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error searching subtitles for $movieName", e)
        }
    }

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
        if (query.isBlank()) {
            _uiState.update { state ->
                state.copy(searchQuery = "", filteredMovies = state.movies)
            }
            searchQueryFlow.value = ""
        } else {
            _uiState.update { it.copy(searchQuery = query) }
            searchQueryFlow.value = query
        }
    }

    suspend fun resolvePlayUrl(movie: IptvItem): String = withContext(Dispatchers.IO) {
        val rawUrl = movie.url ?: ""
        // 1. Direct URL check: already remote and not requiring Stalker link resolution
        if (rawUrl.isNotBlank() && !rawUrl.startsWith("http://localhost") && movie.stalkerCmd.isNullOrEmpty()) {
            return@withContext rawUrl
        }

        // 2. Resolve via active playlist & IptvRepository
        val pl = sessionRepo.activePlaylist.value
        if (pl != null) {
            try {
                val (resolvedUrl, _) = iptvRepo.resolveStreamUrl(pl, movie, "vod")
                if (resolvedUrl.isNotBlank()) {
                    return@withContext resolvedUrl
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving play URL for movie ${movie.name}", e)
            }
        }

        // 3. Fallback to rawUrl
        rawUrl
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

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        val plId = sessionRepo.activePlaylist.value?.id
        iptvRepo.isFavorite(id, plId)
    }

    fun toggleFavorite(item: IptvItem) {
        viewModelScope.launch {
            val pl = sessionRepo.activePlaylist.value ?: return@launch
            iptvRepo.toggleFavoriteItem(item, "movie", pl.id)
        }
    }
}
