package com.example.interfaz

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.motor.config.*
import com.example.motor.db.UserProfile
import com.example.motor.repository.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Repositories
    private val portalSessionRepo = PortalSessionRepository.getInstance(application)

    // ─── CONNECTIVITY MONITOR ────────────────────────────────────────────────
    val isOnline: StateFlow<Boolean> = portalSessionRepo.isOnline

    // ─── USER PROFILES & GLOBAL SESSION STATES ──────────────────────────────
    val currentProfile: StateFlow<UserProfile?> = portalSessionRepo.currentProfile
    val activeTheme: StateFlow<AppThemeColors> = combine(
        portalSessionRepo.currentProfile,
        portalSessionRepo.profiles,
        portalSessionRepo.activeThemeId
    ) { curProfile, allProfiles, activeTid ->
        val effectiveThemeId = if (curProfile != null) {
            activeTid
        } else {
            allProfiles.firstOrNull()?.themeId ?: "purple"
        }
        THEMES_LIST.find { it.id == effectiveThemeId } ?: THEMES_LIST[0]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, THEMES_LIST[0])
    val settings: StateFlow<PlayerSettings> = portalSessionRepo.settings
}

