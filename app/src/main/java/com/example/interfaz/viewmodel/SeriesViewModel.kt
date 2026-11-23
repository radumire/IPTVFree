package com.example.interfaz.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.computeSeriesData
import com.example.interfaz.groupM3uEpisodes
import com.example.interfaz.state.SeriesState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.db.PlaybackHistory
import com.example.motor.db.SeriesEpisode
import com.example.motor.db.SeriesSeason
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.MediaDetails
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.TmdbRepository
import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SeriesViewModel"

class SeriesViewModel(application: Application) : AndroidViewModel(application) {
    val seriesGridState = LazyGridState()
    val selectedCategoryFlow = MutableStateFlow("Todos")
    val pendingSelectedSeries = MutableStateFlow<IptvItem?>(null)

    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    private val _uiState = MutableStateFlow(SeriesState())
    val uiState: StateFlow<SeriesState> = _uiState.asStateFlow()

    val settings: StateFlow<PlayerSettings> = sessionRepo.settings
    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist

    private val _precomputedSeriesData = MutableStateFlow<PrecomputedSeriesData?>(null)
    val precomputedSeriesData: StateFlow<PrecomputedSeriesData?> = _precomputedSeriesData.asStateFlow()

    val series: StateFlow<Map<String, List<IptvItem>>> = _precomputedSeriesData
        .map { it?.groupToUniqueSeries ?: emptyMap() }
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
                val favIds = filtered.filter { it.type == "series" }.flatMap { listOfNotNull(it.id, it.url, it.title) }.toSet()
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
                    _precomputedSeriesData.value = null
                    lastLoadedPlaylistId = null
                    selectedCategoryFlow.value = "Todos"
                    viewModelScope.launch {
                        try { seriesGridState.scrollToItem(0) } catch (_: Exception) {}
                    }
                }
            }
        }
        viewModelScope.launch {
            mediaCacheRepo.cacheUpdateEvents.collect { updatedId ->
                val currentActive = sessionRepo.activePlaylist.value
                if (currentActive != null && currentActive.id == updatedId && _precomputedSeriesData.value != null) {
                    loadJob?.cancel()
                    loadJob = viewModelScope.launch {
                        loadSeriesForPlaylist(currentActive)
                    }
                }
            }
        }
        viewModelScope.launch {
            searchQueryFlow
                .debounce(250L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val currentSeries = _uiState.value.seriesList
                    val filtered = withContext(Dispatchers.Default) {
                        if (query.isBlank()) currentSeries
                        else currentSeries.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    _uiState.update { it.copy(filteredSeries = filtered) }
                }
        }
    }

    fun ensureSeriesLoaded() {
        val pl = sessionRepo.activePlaylist.value ?: return
        if (_precomputedSeriesData.value != null && lastLoadedPlaylistId == pl.id) {
            return
        }
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            loadSeriesForPlaylist(pl)
        }
    }

    private suspend fun loadSeriesForPlaylist(pl: Playlist?) {
        val generation = ++playlistGeneration
        val oldId = lastLoadedPlaylistId
        val newId = pl?.id

        if (oldId != newId) {
            _precomputedSeriesData.value = null
        }

        if (pl == null) {
            lastLoadedPlaylistId = null
            Log.d(TAG, "SERIES_MIGRATION: [Gen $generation] Cleared series (active playlist is null)")
            return
        }

        Log.d(TAG, "SERIES_MIGRATION activePlaylist=${pl.id} (${pl.name}) generation=$generation oldPlaylistId=$oldId")

        val seriesResult = withContext(Dispatchers.IO) {
            mediaCacheRepo.getCategoryCache(pl.id, "series")
        }

        if (generation != playlistGeneration) {
            Log.d(TAG, "SERIES_MIGRATION: [Gen $generation] Discarded stale series cache (current is $playlistGeneration)")
            return
        }

        val cachedData = seriesResult.data
        if (!cachedData.isNullOrEmpty()) {
            val precomputed = withContext(Dispatchers.Default) {
                computeSeriesData(cachedData)
            }

            if (generation != playlistGeneration) {
                Log.d(TAG, "SERIES_MIGRATION: [Gen $generation] Discarded stale precomputed data (current is $playlistGeneration)")
                return
            }

            _precomputedSeriesData.value = precomputed
            lastLoadedPlaylistId = pl.id
            val totalSeries = cachedData.values.sumOf { it.size }
            Log.d(TAG, "SERIES_MIGRATION cacheLoaded=true seriesCount=$totalSeries categoriesCount=${cachedData.size} generation=$generation")
        } else {
            lastLoadedPlaylistId = pl.id
            Log.d(TAG, "SERIES_MIGRATION cacheLoaded=false seriesCount=0 generation=$generation (No cache found for playlist ${pl.id})")
        }
    }

    // ─── EPISODES LOADING & RESOLUTION (CENTRALIZED) ───────────────────────
    fun getCachedEpisodesForSeries(item: IptvItem): List<SeriesSeason>? {
        val pl = sessionRepo.activePlaylist.value
        val isRemoteApi = pl != null && (pl.type.lowercase() in listOf("xtream", "m3u", "stalker", "mac"))
        if (!isRemoteApi) return null
        val cacheKey = if (pl != null) "${pl.id}_${item.seriesId ?: item.name}" else "m3u_${item.name}"
        return iptvRepo.getCachedEpisodes(cacheKey)
    }

    suspend fun loadEpisodesForSeries(item: IptvItem): List<SeriesSeason> = withContext(Dispatchers.IO) {
        val pl = sessionRepo.activePlaylist.value
        val cacheKey = if (pl != null) {
            "${pl.id}_${item.seriesId ?: item.name}"
        } else {
            "m3u_${item.name}"
        }
        val isRemoteApi = pl != null && (pl.type.lowercase() in listOf("xtream", "m3u", "stalker", "mac"))
        val cached = if (isRemoteApi) iptvRepo.getCachedEpisodes(cacheKey) else null
        if (cached != null) {
            return@withContext cached
        }

        val allSeries = _precomputedSeriesData.value?.allSeries ?: emptyList()
        val baseNameCache = _precomputedSeriesData.value?.seriesBaseNameCache
        val result = try {
            fetchSeriesEpisodes(item, allSeries, baseNameCache)
        } catch (e: Exception) {
            withContext(Dispatchers.Default) {
                groupM3uEpisodes(item, allSeries, baseNameCache)
            }
        }
        iptvRepo.cacheEpisodes(cacheKey, result)
        result
    }

    suspend fun resolveEpisodePlayUrl(rawCategory: String): String = withContext(Dispatchers.IO) {
        val pl = sessionRepo.activePlaylist.value
        Log.i("STALKER_SERIES", "resolveEpisodePlayUrl called with descriptor/category: $rawCategory")
        val resolved = if (pl != null) {
            try {
                iptvRepo.resolvePlayUrl(pl, rawCategory, "series")
            } catch (e: Exception) {
                Log.e("STALKER_SERIES", "Error resolving episode play url: $rawCategory", e)
                if (rawCategory.startsWith("http://", ignoreCase = true) || rawCategory.startsWith("https://", ignoreCase = true)) {
                    rawCategory
                } else {
                    throw e
                }
            }
        } else {
            rawCategory
        }
        resolveEpisodeUrl(resolved, pl)
    }

    suspend fun resolveEpisodePlayUrl(episode: SeriesEpisode): String = withContext(Dispatchers.IO) {
        val descriptor = episode.playUrl
        resolveEpisodePlayUrl(descriptor)
    }

    suspend fun fetchSeriesDetails(item: IptvItem): MediaDetails? {
        val pl = sessionRepo.activePlaylist.value
        return tmdbRepo.fetchXtreamMediaDetails(item, pl, isSeries = true)
    }

    // ─── TMDB DELEGATION ────────────────────────────────────────────────────
    fun cleanTitleAndExtractYear(rawTitle: String) = tmdbRepo.cleanTitleAndExtractYear(rawTitle)
    fun getCachedYear(title: String, isSeries: Boolean = true) = tmdbRepo.getCachedYear(title, isSeries)
    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean = true) = tmdbRepo.fetchPosterAndYear(title, isSeries)
    suspend fun fetchXtreamMediaDetails(item: IptvItem, activePlaylist: Playlist?, isSeries: Boolean = true) =
        tmdbRepo.fetchXtreamMediaDetails(item, activePlaylist, isSeries)
    suspend fun fetchMediaDetails(title: String, isSeries: Boolean = true, defaultLogo: String? = null) =
        tmdbRepo.fetchMediaDetails(title, isSeries, defaultLogo)
    suspend fun searchSubtitles(rawTitle: String, isSeries: Boolean = true, season: Int? = null, episode: Int? = null) =
        tmdbRepo.searchSubtitles(rawTitle, isSeries, season, episode)

    // ─── EPISODES CACHE & FETCHING ──────────────────────────────────────────
    fun getCachedEpisodes(key: String): List<SeriesSeason>? = iptvRepo.getCachedEpisodes(key)

    fun cacheEpisodes(key: String, episodes: List<SeriesSeason>) {
        iptvRepo.cacheEpisodes(key, episodes)
    }

    suspend fun fetchSeriesEpisodes(
        item: IptvItem,
        allVod: List<IptvItem>,
        baseNameCache: Map<String, String>?
    ): List<SeriesSeason> = withContext(Dispatchers.IO) {
        val activePl = sessionRepo.activePlaylist.value
        val isStalker = activePl != null && (activePl.type.lowercase() in listOf("stalker", "mac"))
        val seriesIdToUse = if (isStalker) {
            item.seriesId?.ifEmpty { null } ?: item.stalkerCmd ?: item.name
        } else {
            item.seriesId
        }

        if (activePl != null && !seriesIdToUse.isNullOrEmpty()) {
            val eps = if (isStalker) {
                iptvRepo.fetchSeriesEpisodes(activePl, seriesIdToUse, item.stalkerCmd)
            } else {
                iptvRepo.fetchSeriesEpisodes(activePl, seriesIdToUse)
            }
            if (eps.isNotEmpty()) return@withContext eps
            if (isStalker) {
                Log.i("STALKER_SERIES", "Portal Stalker devolvió 0 episodios para la serie '${item.name}' (id=$seriesIdToUse)")
                return@withContext emptyList()
            }
        }

        withContext(Dispatchers.Default) {
            groupM3uEpisodes(item, allVod, baseNameCache)
        }
    }

    suspend fun fetchStalkerSeriesEpisodes(seriesId: String): List<SeriesSeason> = withContext(Dispatchers.IO) {
        val activePl = sessionRepo.activePlaylist.value ?: return@withContext emptyList()
        iptvRepo.fetchStalkerSeriesEpisodes(activePl, seriesId)
    }

    suspend fun resolveStalkerPlayUrl(cmd: String, mediaType: String = "series"): String = withContext(Dispatchers.IO) {
        val activePl = sessionRepo.activePlaylist.value ?: return@withContext cmd
        iptvRepo.resolveStalkerPlayUrl(activePl, cmd, mediaType)
    }

    fun resolveEpisodeUrl(rawUrl: String, activePl: Playlist?): String {
        if (activePl == null || rawUrl.isEmpty()) return rawUrl
        if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
            return rawUrl
        }
        val baseUrl = if (activePl.host.isNotBlank()) activePl.host else {
            val creds = iptvRepo.parseXtreamCredentialsDirect(activePl.url)
            creds?.host ?: activePl.url
        }
        if (baseUrl.isNotBlank()) {
            val hostClean = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
            val pathClean = if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"
            return "$hostClean$pathClean"
        }
        return rawUrl
    }

    fun selectSeries(series: IptvItem) {
        _uiState.update { it.copy(selectedSeries = series) }
        viewModelScope.launch {
            val poster = tmdbRepo.getSeriesPoster(series.name)
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
                state.copy(searchQuery = "", filteredSeries = state.seriesList)
            }
            searchQueryFlow.value = ""
        } else {
            _uiState.update { it.copy(searchQuery = query) }
            searchQueryFlow.value = query
        }
    }

    // ─── FAVORITES ──────────────────────────────────────────────────────────
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
            val id = item.name
            if (_uiState.value.favoriteIds.contains(id) || (item.url != null && _uiState.value.favoriteIds.contains(item.url))) {
                iptvRepo.removeFavorite(id, pl.id)
                item.url?.let { iptvRepo.removeFavorite(it, pl.id) }
            } else {
                iptvRepo.addFavorite(id, item.name, "series", item.url ?: "", item.logo, pl.id)
            }
        }
    }

    // ─── EPISODE WATCH STATE & PROGRESS ───────────────────────────────────
    val playbackHistory: StateFlow<List<PlaybackHistory>> = iptvRepo.recentHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getEpisodeWatchState(
        episode: SeriesEpisode,
        seasonName: String,
        seriesTitle: String,
        historyList: List<PlaybackHistory> = playbackHistory.value
    ): EpisodeWatchState {
        if (historyList.isEmpty()) return EpisodeWatchState()

        val pl = sessionRepo.activePlaylist.value
        val rawUrl = episode.playUrl
        val resolvedUrl = resolveEpisodeUrl(rawUrl, pl)
        val expectedTitle = "$seriesTitle - $seasonName - ${episode.title}"

        // 1. Primary: exact match on resolved stream URL
        var match = if (resolvedUrl.isNotEmpty()) {
            historyList.firstOrNull { it.src == resolvedUrl }
        } else null

        // 2. Secondary: match on raw URL/descriptor if different
        if (match == null && rawUrl.isNotEmpty() && rawUrl != resolvedUrl) {
            match = historyList.firstOrNull { it.src == rawUrl }
        }

        // 3. Fallback: match by title, scoped to current playlist host to avoid cross-playlist collisions
        if (match == null && expectedTitle.isNotBlank()) {
            val plHost = pl?.host?.trim()?.takeIf { it.isNotEmpty() }
            match = historyList.firstOrNull { h ->
                h.type == "series" &&
                    h.title.equals(expectedTitle, ignoreCase = true) &&
                    (plHost == null || h.src.contains(plHost, ignoreCase = true))
            }
        }

        if (match == null) return EpisodeWatchState()

        val pct = match.pct
        return when {
            pct >= 0.95f -> EpisodeWatchState(
                status = EpisodeWatchStatus.WATCHED,
                progressPct = pct,
                positionSec = match.position,
                durationSec = match.duration
            )
            pct > 0.01f -> EpisodeWatchState(
                status = EpisodeWatchStatus.IN_PROGRESS,
                progressPct = pct,
                positionSec = match.position,
                durationSec = match.duration
            )
            else -> EpisodeWatchState()
        }
    }
}

enum class EpisodeWatchStatus {
    NOT_WATCHED,
    IN_PROGRESS,
    WATCHED
}

data class EpisodeWatchState(
    val status: EpisodeWatchStatus = EpisodeWatchStatus.NOT_WATCHED,
    val progressPct: Float = 0f,
    val positionSec: Long = 0L,
    val durationSec: Long = 0L
)
