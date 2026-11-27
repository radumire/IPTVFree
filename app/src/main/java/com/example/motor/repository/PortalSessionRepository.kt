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
import com.example.motor.source.StalkerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PortalSessionRepository private constructor(
    private val application: Application
) {
    private val TAG = "PortalSessionRepo"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getDatabase(application)
    private val profileDao = db.userProfileDao()
    private val profileUpdateMutex = kotlinx.coroutines.sync.Mutex()

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
    // MAC Stalker learned compatibility state (by playlist ID string, backed by SharedPreferences)
    private val stalkerCompatStates = java.util.concurrent.ConcurrentHashMap<String, com.example.motor.parser.PortalCompatibilityState>()
    private val compatPrefs by lazy {
        application.getSharedPreferences("stalker_compat_states", Context.MODE_PRIVATE)
    }

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

        // Initialize default profile if needed, WITHOUT auto-selecting so ProfileSelector stays visible
        scope.launch {
            val list = profileDao.getAllProfiles()
            if (list.isEmpty()) {
                val defaultProfile = UserProfile(
                    id = 1L,
                    name = "Usuario Principal",
                    avatar = "avatar_plumber",
                    colorIdx = 0,
                    pin = "",
                    playlistsJson = "[]",
                    settingsJson = JsonUtils.settingsToJson(PlayerSettings()),
                    themeId = "purple",
                    customThemesJson = "{}"
                )
                profileDao.insertProfile(defaultProfile)
            }
        }

        // Keep subtitle provider credentials synchronized with current profile settings
        scope.launch {
            settings.collect { s ->
                com.example.motor.subtitles.OpenSubtitlesProvider.setGlobalApiKey(s.openSubtitlesKey.takeIf { it.isNotBlank() })
                com.example.motor.subtitles.AssrtSubtitleProvider.setGlobalToken(s.assrtToken.takeIf { it.isNotBlank() })
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

    fun logoutProfile() {
        _currentProfile.value = null
    }

    fun setActivePlaylist(playlistId: Long): Playlist? {
        val current = _currentProfile.value ?: return null
        val plList = JsonUtils.playlistsFromJson(current.playlistsJson)
        val target = plList.find { it.id == playlistId } ?: return null
        val updatedList = plList.map { p ->
            p.copy(active = p.id == playlistId)
        }
        val optimisticProfile = current.copy(playlistsJson = JsonUtils.playlistsToJson(updatedList))
        _currentProfile.value = optimisticProfile
        val targetProfileId = current.id
        scope.launch(Dispatchers.IO) {
            profileUpdateMutex.withLock {
                try {
                    val latest = profileDao.getProfileById(targetProfileId)
                        ?: _currentProfile.value?.takeIf { it.id == targetProfileId }
                        ?: return@withLock
                    val latestList = JsonUtils.playlistsFromJson(latest.playlistsJson)
                    if (latestList.none { it.id == playlistId }) {
                        // Playlist was concurrently deleted; preserve deletion cleanly
                        if (_currentProfile.value?.id == targetProfileId) {
                            _currentProfile.value = latest
                        }
                        return@withLock
                    }
                    val lockedList = latestList.map { p -> p.copy(active = p.id == playlistId) }
                    val updatedProfile = latest.copy(playlistsJson = JsonUtils.playlistsToJson(lockedList))
                    profileDao.insertProfile(updatedProfile)
                    if (_currentProfile.value?.id == targetProfileId) {
                        _currentProfile.value = updatedProfile
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving updated profile to DB", e)
                }
            }
        }
        return target.copy(active = true)
    }

    suspend fun updateCurrentProfile(update: (UserProfile) -> UserProfile) = withContext(Dispatchers.IO) {
        profileUpdateMutex.withLock {
            val activeSnapshot = _currentProfile.value ?: return@withLock
            val current = profileDao.getProfileById(activeSnapshot.id) ?: activeSnapshot
            val updated = update(current)
            profileDao.insertProfile(updated)
            if (_currentProfile.value?.id == updated.id) {
                _currentProfile.value = updated
            }
        }
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

    fun getStalkerCompatState(playlistIdKey: String): com.example.motor.parser.PortalCompatibilityState? {
        stalkerCompatStates[playlistIdKey]?.let { return it }
        val rawJson = try {
            compatPrefs.getString(playlistIdKey, null)
        } catch (e: Throwable) {
            Log.w(TAG, "Error reading persisted Stalker compat state for $playlistIdKey", e)
            null
        } ?: return null

        val parsed = JsonUtils.stalkerCompatStateFromJson(rawJson)
        if (parsed != null) {
            stalkerCompatStates[playlistIdKey] = parsed
        } else {
            // Corrupt or incompatible JSON entry: clean up cleanly
            try {
                compatPrefs.edit().remove(playlistIdKey).apply()
            } catch (_: Throwable) {}
        }
        return parsed
    }

    fun setStalkerCompatState(playlistIdKey: String, state: com.example.motor.parser.PortalCompatibilityState) {
        stalkerCompatStates[playlistIdKey] = state
        try {
            val json = JsonUtils.stalkerCompatStateToJson(state)
            compatPrefs.edit().putString(playlistIdKey, json).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Error persisting Stalker compat state for $playlistIdKey", e)
        }
    }

    fun clearStalkerCompatState(playlistIdKey: String) {
        stalkerCompatStates.remove(playlistIdKey)
        try {
            compatPrefs.edit().remove(playlistIdKey).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "Error removing persisted Stalker compat state for $playlistIdKey", e)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun clearInMemoryCompatCacheForTests() {
        stalkerCompatStates.clear()
    }

    suspend fun reconnectActivePlaylistSilently() = withContext(Dispatchers.IO) {
        val pl = activePlaylist.value ?: return@withContext
        if ((pl.type == "mac" || pl.type == "stalker") && pl.mac.isNotBlank()) {
            try {
                val host = pl.host
                val mac = pl.mac
                val device = if (pl.stalkerDeviceJson.isNotBlank()) JsonUtils.stalkerDeviceFromJson(pl.stalkerDeviceJson) else StalkerDevice()
                val stalkerSource = StalkerSource(this@PortalSessionRepository)
                val sessionMap = stalkerSource.performHandshake(host, mac, device, pl.id)
                setStalkerSession(pl.id.toString(), sessionMap)
                Log.d(TAG, "Silent re-handshake succeeded for MAC playlist: ${pl.name}")
            } catch (e: Throwable) {
                Log.w(TAG, "Silent re-handshake failed: ${e.message}")
            }
        }
    }

    suspend fun keepActivePlaylistSessionAlive(): Boolean = withContext(Dispatchers.IO) {
        val pl = activePlaylist.value ?: return@withContext false
        if ((pl.type == "mac" || pl.type == "stalker") && pl.mac.isNotBlank()) {
            val session = getStalkerSession(pl.id.toString())
            if (session == null) {
                reconnectActivePlaylistSilently()
                return@withContext getStalkerSession(pl.id.toString()) != null
            }
            val alive = com.example.motor.parser.StalkerParser.sendStalkerWatchdogPing(session)
            if (!alive) {
                Log.i(TAG, "Stalker watchdog reported expired token for ${pl.name}; refreshing session silently...")
                reconnectActivePlaylistSilently()
                return@withContext getStalkerSession(pl.id.toString()) != null
            }
            return@withContext true
        }
        true
    }

    companion object {
        @Volatile
        private var INSTANCE: PortalSessionRepository? = null

        fun getInstance(application: Application): PortalSessionRepository {
            val existing = INSTANCE
            if (existing != null && existing.application === application) {
                return existing
            }
            return synchronized(this) {
                val locked = INSTANCE
                if (locked != null && locked.application === application) {
                    locked
                } else {
                    PortalSessionRepository(application).also { INSTANCE = it }
                }
            }
        }

        fun getInstanceOrNull(): PortalSessionRepository? {
            return INSTANCE
        }
    }
}
