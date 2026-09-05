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
        viewModelScope.launch {
            sessionRepo.selectProfile(profile)
        }
    }

    fun addProfile(name: String, avatar: String, pin: String) {
        viewModelScope.launch {
            val all = profileDao.getAllProfiles()
            val newId = System.currentTimeMillis()
            val newProfile = UserProfile(
                id = newId,
                name = name,
                avatar = avatar,
                colorIdx = all.size,
                pin = pin,
                playlistsJson = "[]",
                settingsJson = JsonUtils.settingsToJson(PlayerSettings())
            )
            profileDao.insertProfile(newProfile)
            sessionRepo.selectProfile(newProfile)
        }
    }

    fun updateProfile(id: Long, name: String, avatar: String, pin: String) {
        viewModelScope.launch {
            val profile = profileDao.getProfileById(id) ?: return@launch
            val updated = profile.copy(name = name, avatar = avatar, pin = pin)
            profileDao.insertProfile(updated)
            if (currentProfile.value?.id == id) {
                sessionRepo.selectProfile(updated)
            }
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            profileDao.deleteProfileById(id)
            val current = currentProfile.value
            if (current?.id == id) {
                val remaining = profileDao.getAllProfiles()
                if (remaining.isNotEmpty()) {
                    sessionRepo.selectProfile(remaining.first())
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
