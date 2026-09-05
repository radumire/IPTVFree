package com.example.interfaz.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.computeSeriesData
import com.example.interfaz.groupM3uEpisodes
import com.example.interfaz.state.SeriesState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.EpgChannelData
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

class SeriesViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    private val _uiState = MutableStateFlow(SeriesState())
    val uiState: StateFlow<SeriesState> = _uiState.asStateFlow()

    val settings: StateFlow<PlayerSettings> = sessionRepo.settings
    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist

    private val _series = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val series: StateFlow<Map<String, List<IptvItem>>> = _series.asStateFlow()

    private val _precomputedSeriesData = MutableStateFlow<PrecomputedSeriesData?>(null)
    val precomputedSeriesData: StateFlow<PrecomputedSeriesData?> = _precomputedSeriesData.asStateFlow()

    val favoritesList: StateFlow<List<FavoriteItem>> = iptvRepo.favoriteItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            iptvRepo.favoriteItems.collect { favs ->
                val favIds = favs.filter { it.type == "series" }.map { it.id }.toSet()
                _uiState.update { state -> state.copy(favoriteIds = favIds) }
            }
        }
        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                if (pl != null) {
                    val cached = mediaCacheRepo.getCategoryCache(pl.id, "series").data
                    if (!cached.isNullOrEmpty() && _series.value.isEmpty()) {
                        updateSeries(cached)
                    }
                }
            }
        }
    }

    fun updateSeries(seriesMap: Map<String, List<IptvItem>>) {
        _series.value = seriesMap
        if (seriesMap.isEmpty()) {
            _precomputedSeriesData.value = null
        } else if (_precomputedSeriesData.value == null) {
            _precomputedSeriesData.value = computeSeriesData(seriesMap)
        }
    }

    fun updatePrecomputedData(data: PrecomputedSeriesData?) {
        _precomputedSeriesData.value = data
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
    fun getCachedEpisodes(key: String): List<EpgChannelData>? = iptvRepo.getCachedEpisodes(key)

    fun cacheEpisodes(key: String, episodes: List<EpgChannelData>) {
        iptvRepo.cacheEpisodes(key, episodes)
    }

    suspend fun fetchSeriesEpisodes(
        item: IptvItem,
        allVod: List<IptvItem>,
        baseNameCache: Map<String, String>?
    ): List<EpgChannelData> = withContext(Dispatchers.IO) {
        val activePl = sessionRepo.activePlaylist.value
        val isXtreamCompatible = activePl != null && (activePl.type == "xtream" || activePl.type == "m3u")

        if (isXtreamCompatible && !item.seriesId.isNullOrEmpty()) {
            val eps = iptvRepo.fetchXtreamSeriesEpisodes(activePl!!, item.seriesId!!)
            if (eps.isNotEmpty()) {
                eps
            } else {
                withContext(Dispatchers.Default) {
                    groupM3uEpisodes(item, allVod, baseNameCache)
                }
            }
        } else if (activePl != null && activePl.type.lowercase() in listOf("stalker", "mac")) {
            val stalkerIdToUse = item.seriesId?.ifEmpty { null } ?: item.stalkerCmd ?: item.name
            val eps = iptvRepo.fetchStalkerSeriesEpisodes(activePl, stalkerIdToUse)
            if (eps.isNotEmpty()) {
                eps
            } else {
                withContext(Dispatchers.Default) {
                    groupM3uEpisodes(item, allVod, baseNameCache)
                }
            }
        } else {
            withContext(Dispatchers.Default) {
                groupM3uEpisodes(item, allVod, baseNameCache)
            }
        }
    }

    suspend fun fetchStalkerSeriesEpisodes(seriesId: String): List<EpgChannelData> = withContext(Dispatchers.IO) {
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
            val creds = com.example.motor.parser.XtreamParser.parseXtreamCredentialsDirect(activePl.url)
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
        _uiState.update { state ->
            val filtered = state.seriesList.filter { it.name.contains(query, ignoreCase = true) }
            state.copy(searchQuery = query, filteredSeries = filtered)
        }
    }

    // ─── FAVORITES ──────────────────────────────────────────────────────────
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
            val id = "series-${pl.id}-${item.name}"
            if (_uiState.value.favoriteIds.contains(id)) {
                iptvRepo.removeFavorite(id)
            } else {
                iptvRepo.addFavorite(id, item.name, "series", item.url ?: "", item.logo)
            }
        }
    }
}
