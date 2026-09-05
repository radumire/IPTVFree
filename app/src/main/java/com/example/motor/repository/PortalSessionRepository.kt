package com.example.motor.repository

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.config.StalkerDevice
import com.example.motor.db.*
import com.example.motor.parser.StalkerParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PortalSessionRepository private constructor(
    private val application: Application
) {
    private val TAG = "PortalSessionRepo"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getDatabase(application)
    private val profileDao = db.userProfileDao()

    // Network connectivity
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Profiles & active session state
    val profiles: StateFlow<List<UserProfile>> = profileDao.getAllProfilesFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _currentProfile = MutableStateFlow<UserProfile?>(null)
    val currentProfile: StateFlow<UserProfile?> = _currentProfile.asStateFlow()

    val playlists: StateFlow<List<Playlist>> = _currentProfile.map { p ->
        p?.let { JsonUtils.playlistsFromJson(it.playlistsJson) } ?: emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activePlaylist: StateFlow<Playlist?> = _currentProfile.map { p ->
        val list = p?.playlistsJson?.let { JsonUtils.playlistsFromJson(it) } ?: emptyList()
        list.find { it.active } ?: list.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val settings: StateFlow<PlayerSettings> = _currentProfile.map { p ->
        p?.let { JsonUtils.settingsFromJson(it.settingsJson) } ?: PlayerSettings()
    }.stateIn(scope, SharingStarted.Eagerly, PlayerSettings())

    val customThemes: StateFlow<Map<String, Map<String, String>>> = _currentProfile.map { p ->
        p?.let { JsonUtils.customThemesFromJson(it.customThemesJson) } ?: emptyMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val activeThemeId: StateFlow<String> = _currentProfile.map { p ->
        p?.themeId ?: "purple"
    }.stateIn(scope, SharingStarted.Eagerly, "purple")

    // MAC Stalker session cache (by playlist ID string)
    private val stalkerSessions = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any>>()

    init {
        // Initialize network monitor
        val connManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _isOnline.value = isNetworkAvailable(connManager)

        val builder = NetworkRequest.Builder()
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        try {
            connManager.registerNetworkCallback(builder.build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                    Log.d(TAG, "Network connection established")
                    scope.launch {
                        reconnectActivePlaylistSilently()
                    }
                }
                override fun onLost(network: Network) {
                    _isOnline.value = false
                    Log.d(TAG, "Network connection lost")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
        }

        // Initialize default profile if needed
        scope.launch {
            val list = profileDao.getAllProfiles()
            if (list.isEmpty()) {
                val defaultProfile = UserProfile(
                    id = 0L,
                    name = "Usuario Principal",
                    avatar = "👤",
                    colorIdx = 0,
                    pin = "",
                    playlistsJson = "[]",
                    settingsJson = JsonUtils.settingsToJson(PlayerSettings()),
                    themeId = "purple",
                    customThemesJson = "{}"
                )
                profileDao.insertProfile(defaultProfile)
                _currentProfile.value = defaultProfile
            } else if (_currentProfile.value == null) {
                _currentProfile.value = list.firstOrNull()
            }
        }
    }

    private fun isNetworkAvailable(connManager: ConnectivityManager): Boolean {
        val activeNet = connManager.activeNetwork ?: return false
        val caps = connManager.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun selectProfile(profile: UserProfile) {
        _currentProfile.value = profile
    }

    suspend fun updateCurrentProfile(update: (UserProfile) -> UserProfile) = withContext(Dispatchers.IO) {
        val current = _currentProfile.value ?: return@withContext
        val updated = update(current)
        profileDao.insertProfile(updated)
        _currentProfile.value = updated
    }

    fun getStalkerSession(playlistIdKey: String): Map<String, Any>? {
        return stalkerSessions[playlistIdKey]
    }

    fun setStalkerSession(playlistIdKey: String, session: Map<String, Any>) {
        stalkerSessions[playlistIdKey] = session
    }

    fun clearStalkerSession(playlistIdKey: String) {
        stalkerSessions.remove(playlistIdKey)
    }

    suspend fun reconnectActivePlaylistSilently() = withContext(Dispatchers.IO) {
        val pl = activePlaylist.value ?: return@withContext
        if ((pl.type == "mac" || pl.type == "stalker") && pl.mac.isNotBlank()) {
            try {
                val host = pl.host
                val mac = pl.mac
                val device = if (pl.stalkerDeviceJson.isNotBlank()) JsonUtils.stalkerDeviceFromJson(pl.stalkerDeviceJson) else StalkerDevice()
                val sessionMap = StalkerParser.performStalkerHandshake(host, mac, device)
                setStalkerSession(pl.id.toString(), sessionMap)
                Log.d(TAG, "Silent re-handshake succeeded for MAC playlist: ${pl.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Silent re-handshake failed: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PortalSessionRepository? = null

        fun getInstance(application: Application): PortalSessionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PortalSessionRepository(application).also { INSTANCE = it }
            }
        }
    }
}
