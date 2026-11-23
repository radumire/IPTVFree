package com.example.interfaz.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.JsonUtils
import com.example.interfaz.state.ProfileState
import com.example.motor.config.PlayerSettings
import com.example.motor.db.AppDatabase
import com.example.motor.db.UserProfile
import com.example.motor.repository.PortalSessionRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val profileDao = AppDatabase.getDatabase(application).userProfileDao()

    val profiles: StateFlow<List<UserProfile>> = sessionRepo.profiles
    val currentProfile: StateFlow<UserProfile?> = sessionRepo.currentProfile
    val isOnline: StateFlow<Boolean> = sessionRepo.isOnline

    private val _uiState = MutableStateFlow(ProfileState())
    val uiState: StateFlow<ProfileState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sessionRepo.profiles,
                sessionRepo.currentProfile,
                sessionRepo.isOnline
            ) { profList, curProf, online ->
                ProfileState(
                    profiles = profList,
                    currentProfile = curProf,
                    isOnline = online,
                    isLoading = false,
                    error = null
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    // ─── PROFILE OPERATIONS ───────────────────────────────────────────────────

    fun switchProfile(profile: UserProfile) {
        sessionRepo.selectProfile(profile)
        viewModelScope.launch {
            val fresh = profileDao.getProfileById(profile.id)
            if (fresh != null && sessionRepo.currentProfile.value?.id == profile.id && fresh != profile) {
                sessionRepo.selectProfile(fresh)
            }
        }
    }

    fun logout() {
        com.example.motor.repository.MediaCacheRepository.getInstance(getApplication()).setActivePlaylist(-1L)
        sessionRepo.logoutProfile()
    }

    fun addProfile(name: String, avatar: String, pin: String = "", autoSelect: Boolean = false) {
        viewModelScope.launch {
            val all = profileDao.getAllProfiles()
            val newId = maxOf(System.currentTimeMillis(), (all.maxOfOrNull { it.id } ?: 1L) + 1L)
            val newProfile = UserProfile(
                id = newId,
                name = name,
                avatar = avatar,
                colorIdx = all.size,
                pin = pin,
                playlistsJson = "[]",
                settingsJson = JsonUtils.settingsToJson(PlayerSettings()),
                themeId = "purple",
                customThemesJson = "{}"
            )
            profileDao.insertProfile(newProfile)
            if (autoSelect) {
                sessionRepo.selectProfile(newProfile)
            }
        }
    }

    fun updateProfile(id: Long, name: String, avatar: String, pin: String? = null) {
        viewModelScope.launch {
            val profile = profileDao.getProfileById(id) ?: return@launch
            val updated = profile.copy(name = name, avatar = avatar, pin = pin ?: profile.pin)
            profileDao.insertProfile(updated)
            if (currentProfile.value?.id == id) {
                sessionRepo.selectProfile(updated)
            }
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            val all = profileDao.getAllProfiles()
            val mainId = all.firstOrNull()?.id
            if (mainId == id) return@launch
            val target = all.find { it.id == id }
            profileDao.deleteProfileById(id)

            if (target != null) {
                val app = getApplication<Application>()
                val deletedPlaylistIds = com.example.motor.config.JsonUtils
                    .playlistsFromJson(target.playlistsJson)
                    .map { it.id }
                    .toSet()
                val remainingPlaylistIds = all
                    .filter { it.id != id }
                    .flatMap { com.example.motor.config.JsonUtils.playlistsFromJson(it.playlistsJson) }
                    .map { it.id }
                    .toSet()
                val orphanPlaylistIds = deletedPlaylistIds - remainingPlaylistIds

                if (orphanPlaylistIds.isNotEmpty()) {
                    val db = AppDatabase.getDatabase(app)
                    val mediaCacheRepo = com.example.motor.repository.MediaCacheRepository.getInstance(app)
                    val iptvRepo = com.example.motor.repository.IptvRepository.getInstance(app, sessionRepo)
                    for (plId in orphanPlaylistIds) {
                        try {
                            mediaCacheRepo.clearCategoryCache(plId)
                            iptvRepo.clearFileCache(plId.toString())
                            db.playbackHistoryDao().clearForPlaylist(plId)
                            db.favoriteItemDao().clearForPlaylist(plId)
                            com.example.motor.repository.PlaylistConnectionGuard.clearAllForPlaylist(app, plId)
                            sessionRepo.clearStalkerSession(plId.toString())
                            sessionRepo.clearStalkerCompatState(plId.toString())
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            val current = currentProfile.value
            if (current?.id == id) {
                val remaining = profileDao.getAllProfiles()
                if (remaining.isNotEmpty()) {
                    sessionRepo.selectProfile(remaining.first())
                } else {
                    sessionRepo.logoutProfile()
                }
            }
        }
    }

    fun saveProfiles(list: List<UserProfile>) {
        viewModelScope.launch {
            list.forEach { p -> profileDao.insertProfile(p) }
        }
    }
}
