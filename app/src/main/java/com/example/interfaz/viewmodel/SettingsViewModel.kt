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
import com.example.motor.db.AppDatabase
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
    private val appDatabase = AppDatabase.getDatabase(application)
    private val epgDao = appDatabase.epgCacheDao()

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
    }

    fun updateLiveChannels(channels: Map<String, List<IptvItem>>) {
        _liveChannels.value = channels
    }

    fun updateMoviesData(data: PrecomputedMoviesData?) {
        _precomputedMoviesData.value = data
    }

    fun updateSeriesData(data: PrecomputedSeriesData?) {
        _precomputedSeriesData.value = data
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
            val current = currentProfile.value ?: return@launch
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

    fun clearAllCategoryAndEpgCaches() {
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
                try { epgDao.clearAll() } catch (_: Exception) {}
            }
            _uiState.update { it.copy(message = "Caché limpiada correctamente") }
        }
    }

    // ─── SyncServerDataProvider Implementation ────────────────────────────────
    override fun getPlaylists(): List<Playlist> = playlists.value
    override fun updatePlaylists(playlists: List<Playlist>) {
        updateCurrentProfileField(playlists = playlists)
    }
    override fun connectPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val updated = playlists.value.map { it.copy(active = it.id == playlist.id) }
            updateCurrentProfileField(playlists = updated)
        }
    }
    override fun getApplicationContext(): Context? = getApplication()
}
