package com.example.interfaz.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.JsonUtils
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.THEMES_LIST
import com.example.interfaz.state.SettingsState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.IptvItem
import com.example.motor.db.UserProfile
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.sync.SyncServerDataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application), SyncServerDataProvider {
    private val TAG = "SettingsViewModel"
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    val currentProfile: StateFlow<UserProfile?> = sessionRepo.currentProfile
    val settings: StateFlow<PlayerSettings> = sessionRepo.settings
    val playlists: StateFlow<List<Playlist>> = sessionRepo.playlists
    val activePlaylist: StateFlow<Playlist?> = sessionRepo.activePlaylist
    val activePlaylistName: StateFlow<String> = sessionRepo.activePlaylist.map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val isOnline: StateFlow<Boolean> = sessionRepo.isOnline

    // Category states synchronized from the app catalog
    private val _liveChannels = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val liveChannels: StateFlow<Map<String, List<IptvItem>>> = _liveChannels.asStateFlow()

    private val _precomputedMoviesData = MutableStateFlow<PrecomputedMoviesData?>(null)
    val precomputedMoviesData: StateFlow<PrecomputedMoviesData?> = _precomputedMoviesData.asStateFlow()

    private val _precomputedSeriesData = MutableStateFlow<PrecomputedSeriesData?>(null)
    val precomputedSeriesData: StateFlow<PrecomputedSeriesData?> = _precomputedSeriesData.asStateFlow()

    private val _uiState = MutableStateFlow(SettingsState())
    val uiState: StateFlow<SettingsState> = _uiState.asStateFlow()

    private var playlistGeneration = 0
    private var lastLoadedPlaylistId: Long? = null

    init {
        viewModelScope.launch {
            combine(
                sessionRepo.profiles,
                sessionRepo.currentProfile,
                sessionRepo.playlists,
                sessionRepo.activePlaylist,
                sessionRepo.settings
            ) { profiles, currentProfile, playlists, activePl, settings ->
                _uiState.update { state ->
                    state.copy(
                        profiles = profiles,
                        currentProfile = currentProfile,
                        playlists = playlists,
                        activePlaylist = activePl,
                        playerSettings = settings,
                        isLoading = false
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            combine(
                sessionRepo.activeThemeId,
                sessionRepo.isOnline
            ) { activeThemeId, isOnline ->
                val activeTheme = THEMES_LIST.find { it.id == activeThemeId } ?: THEMES_LIST[0]
                _uiState.update { state ->
                    state.copy(
                        activeTheme = activeTheme,
                        isOnline = isOnline
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                val activeId = pl?.id
                val isDifferent = activeId != lastLoadedPlaylistId
                if (isDifferent) {
                    categoriesJob?.cancel()
                    _liveChannels.value = emptyMap()
                    _precomputedMoviesData.value = null
                    _precomputedSeriesData.value = null
                    lastLoadedPlaylistId = null
                }
            }
        }
    }

    private var categoriesJob: kotlinx.coroutines.Job? = null

    fun ensureCategoriesLoaded() {
        val pl = sessionRepo.activePlaylist.value ?: return
        if (lastLoadedPlaylistId == pl.id && _precomputedMoviesData.value != null) {
            return
        }
        if (categoriesJob?.isActive == true) return
        categoriesJob = viewModelScope.launch(Dispatchers.IO) {
            loadCategoriesForPlaylist(pl)
        }
    }

    private suspend fun loadCategoriesForPlaylist(pl: Playlist) {
        val tvCats = mediaCacheRepo.getCategoryNames(pl.id, "tv")
        val movieCats = mediaCacheRepo.getCategoryNames(pl.id, "movie")
        val seriesCats = mediaCacheRepo.getCategoryNames(pl.id, "series")

        _liveChannels.value = tvCats.associateWith { emptyList<IptvItem>() }
        _precomputedMoviesData.value = PrecomputedMoviesData(
            groups = movieCats,
            allMovies = emptyList(),
            groupToMovies = emptyMap(),
            categoryCounts = emptyMap(),
            totalMoviesCount = 0
        )
        _precomputedSeriesData.value = PrecomputedSeriesData(
            groups = seriesCats,
            seriesBaseNameCache = emptyMap(),
            categoryCounts = emptyMap(),
            allSeriesUnique = emptyList(),
            groupToUniqueSeries = emptyMap(),
            allSeries = emptyList()
        )
        lastLoadedPlaylistId = pl.id
        Log.d(TAG, "SETTINGS_CATALOG loaded category names without full parsing: tv=${tvCats.size} movieCats=${movieCats.size} seriesCats=${seriesCats.size}")
    }

    fun updateCurrentProfileField(
        name: String? = null,
        avatar: String? = null,
        pin: String? = null,
        themeId: String? = null,
        playlists: List<Playlist>? = null,
        settings: PlayerSettings? = null,
        customThemesMap: Map<String, Map<String, String>>? = null,
        hiddenTvSet: Set<String>? = null,
        hiddenMoviesSet: Set<String>? = null,
        hiddenSeriesSet: Set<String>? = null
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
                    customThemesJson = customThemesMap?.let { JsonUtils.customThemesToJson(it) } ?: prof.customThemesJson,
                    hiddenTvJson = hiddenTvSet?.let { JsonUtils.stringSetToJson(it) } ?: prof.hiddenTvJson,
                    hiddenMoviesJson = hiddenMoviesSet?.let { JsonUtils.stringSetToJson(it) } ?: prof.hiddenMoviesJson,
                    hiddenSeriesJson = hiddenSeriesSet?.let { JsonUtils.stringSetToJson(it) } ?: prof.hiddenSeriesJson
                )
            }
        }
    }

    fun updateSettings(update: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.launch {
            val currentSettings = settings.value
            val newSettings = update(currentSettings)
            updateCurrentProfileField(settings = newSettings)
        }
    }

    fun selectTheme(themeId: String) {
        updateCurrentProfileField(themeId = themeId)
    }

    fun clearAllCategoryCaches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().filesDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("playlist_cache_") && file.name.endsWith(".json")) file.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete file cache", e)
                }
                try {
                    getApplication<Application>().cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear temp cacheDir", e)
                }
                try { mediaCacheRepo.clearAllCaches() } catch (_: Exception) {}
            }
            _liveChannels.value = emptyMap()
            _precomputedMoviesData.value = null
            _precomputedSeriesData.value = null
            _uiState.update { it.copy(message = "Caché limpiada correctamente") }
        }
    }

    // ─── SyncServerDataProvider Implementation ────────────────────────────────
    override fun getPlaylists(): List<Playlist> = playlists.value
    override fun updatePlaylists(playlists: List<Playlist>) {
        updateCurrentProfileField(playlists = playlists)
        ensureCategoriesLoaded()
    }
    override fun connectPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            sessionRepo.setActivePlaylist(playlist.id)
            ensureCategoriesLoaded()
        }
    }
    override fun getApplicationContext(): Context? = getApplication()

    override fun getSubtitleKeys(): Pair<String, String> {
        val s = settings.value
        return Pair(s.openSubtitlesKey, s.assrtToken)
    }

    override fun updateSubtitleKeys(openSubtitlesKey: String?, assrtToken: String?) {
        viewModelScope.launch {
            val current = settings.value
            val newOpenSub = if (!openSubtitlesKey.isNullOrBlank()) openSubtitlesKey.trim() else current.openSubtitlesKey
            val newAssrt = if (!assrtToken.isNullOrBlank()) assrtToken.trim() else current.assrtToken
            val updated = current.copy(
                openSubtitlesKey = newOpenSub,
                assrtToken = newAssrt
            )
            updateCurrentProfileField(settings = updated)
            com.example.motor.subtitles.OpenSubtitlesProvider.setGlobalApiKey(newOpenSub.takeIf { it.isNotBlank() })
            com.example.motor.subtitles.AssrtSubtitleProvider.setGlobalToken(newAssrt.takeIf { it.isNotBlank() })
            Log.i(TAG, "Subtitle keys updated via sync: OpenSubtitles=${newOpenSub.isNotBlank()}, Assrt=${newAssrt.isNotBlank()}")
        }
    }

    override fun syncData(playlists: List<Playlist>, openSubtitlesKey: String?, assrtToken: String?) {
        viewModelScope.launch {
            val current = settings.value
            val newOpenSub = if (!openSubtitlesKey.isNullOrBlank()) openSubtitlesKey.trim() else current.openSubtitlesKey
            val newAssrt = if (!assrtToken.isNullOrBlank()) assrtToken.trim() else current.assrtToken
            val updatedSettings = current.copy(
                openSubtitlesKey = newOpenSub,
                assrtToken = newAssrt
            )
            // Atomic update: both playlists and settings updated simultaneously in the same DB transaction
            updateCurrentProfileField(
                playlists = playlists,
                settings = updatedSettings
            )
            com.example.motor.subtitles.OpenSubtitlesProvider.setGlobalApiKey(newOpenSub.takeIf { it.isNotBlank() })
            com.example.motor.subtitles.AssrtSubtitleProvider.setGlobalToken(newAssrt.takeIf { it.isNotBlank() })
            Log.i(TAG, "syncData atomic update completed: playlists=${playlists.size}, OpenSubtitles=${newOpenSub.isNotBlank()}, Assrt=${newAssrt.isNotBlank()}")
        }
    }
}
