package com.example.interfaz.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.state.LiveTvState
import com.example.motor.config.PlayerSettings
import com.example.motor.db.EpgChannelData
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.repository.EpgRepository
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveTvViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)
    private val epgRepo = EpgRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LiveTvState())
    val uiState: StateFlow<LiveTvState> = _uiState.asStateFlow()

    val settings: StateFlow<PlayerSettings> = sessionRepo.settings

    private val _liveChannels = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val liveChannels: StateFlow<Map<String, List<IptvItem>>> = _liveChannels.asStateFlow()

    private val _epgData = MutableStateFlow<Map<String, EpgChannelData>>(emptyMap())
    val epgData: StateFlow<Map<String, EpgChannelData>> = _epgData.asStateFlow()

    private val _epgLoaded = MutableStateFlow(false)
    val epgLoaded: StateFlow<Boolean> = _epgLoaded.asStateFlow()

    val favoritesList: StateFlow<List<FavoriteItem>> = iptvRepo.favoriteItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            iptvRepo.favoriteItems.collect { favs ->
                val favIds = favs.filter { it.type == "tv" }.map { it.id }.toSet()
                _uiState.update { state -> state.copy(favoriteIds = favIds) }
            }
        }
        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                if (pl != null) {
                    val cached = mediaCacheRepo.getCategoryCache(pl.id, "tv").data
                    if (!cached.isNullOrEmpty() && _liveChannels.value.isEmpty()) {
                        _liveChannels.value = cached
                    }
                }
            }
        }
    }

    fun updateLiveChannels(channels: Map<String, List<IptvItem>>) {
        _liveChannels.value = channels
    }

    fun updateEpgData(data: Map<String, EpgChannelData>) {
        _epgData.value = data
        _epgLoaded.value = data.isNotEmpty()
    }

    suspend fun resolveStream(channel: IptvItem): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val pl = sessionRepo.activePlaylist.value
        if (pl != null) {
            iptvRepo.resolveStreamUrl(pl, channel, "live")
        } else {
            Pair(channel.url ?: "", emptyMap())
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

    fun loadLiveTvContent() {
        val pl = sessionRepo.activePlaylist.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Try file cache first
                val plIdStr = pl.id.toString()
                var cached = iptvRepo.loadCachedData(plIdStr, "tv")
                if (cached.isNotEmpty()) {
                    val firstCat = cached.keys.firstOrNull()
                    val channels = cached[firstCat] ?: emptyList()
                    _uiState.update { 
                        it.copy(
                            categories = cached,
                            selectedCategory = firstCat,
                            channels = channels,
                            filteredChannels = channels,
                            isLoading = false
                        ) 
                    }
                }

                // Fetch fresh
                val fresh = iptvRepo.loadLiveCategories(pl)
                val selCat = _uiState.value.selectedCategory ?: fresh.keys.firstOrNull()
                val channels = fresh[selCat] ?: emptyList()

                _uiState.update { 
                    it.copy(
                        categories = fresh,
                        selectedCategory = selCat,
                        channels = channels,
                        filteredChannels = filterChannels(channels, it.searchQuery, it.selectedFilter, it.favoriteIds),
                        isLoading = false
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Error al cargar canales TV") }
            }
        }
    }

    fun selectCategory(category: String) {
        val catChannels = _uiState.value.categories[category] ?: emptyList()
        _uiState.update { state ->
            state.copy(
                selectedCategory = category,
                channels = catChannels,
                filteredChannels = filterChannels(catChannels, state.searchQuery, state.selectedFilter, state.favoriteIds)
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredChannels = filterChannels(state.channels, query, state.selectedFilter, state.favoriteIds)
            )
        }
    }

    fun setSelectedFilter(filter: String) {
        _uiState.update { state ->
            state.copy(
                selectedFilter = filter,
                filteredChannels = filterChannels(state.channels, state.searchQuery, filter, state.favoriteIds)
            )
        }
    }

    fun toggleFavorite(item: IptvItem) {
        viewModelScope.launch {
            val pl = sessionRepo.activePlaylist.value ?: return@launch
            val id = "tv-${pl.id}-${item.name}"
            if (_uiState.value.favoriteIds.contains(id)) {
                iptvRepo.removeFavorite(id)
            } else {
                iptvRepo.addFavorite(id, item.name, "tv", item.url ?: "", item.logo)
            }
        }
    }

    private fun filterChannels(list: List<IptvItem>, query: String, filter: String, favIds: Set<String>): List<IptvItem> {
        return list.filter { ch ->
            val matchesQuery = query.isBlank() || ch.name.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                "FAVORITES" -> favIds.contains(ch.name)
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }
}
