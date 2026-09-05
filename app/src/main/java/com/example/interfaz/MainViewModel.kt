package com.example.interfaz

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.motor.config.*
import com.example.motor.db.*
import com.example.motor.epg.*
import com.example.motor.parser.*
import com.example.motor.repository.*
import com.squareup.moshi.Types
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source

class MainViewModel(application: Application) : AndroidViewModel(application), com.example.motor.sync.SyncServerDataProvider {
    private val TAG = "MainViewModel"
    private val db = AppDatabase.getDatabase(application)

    // Repositories
    val portalSessionRepo = PortalSessionRepository.getInstance(application)
    val iptvRepo = IptvRepository.getInstance(application, portalSessionRepo)
    val epgRepo = EpgRepository.getInstance(application)
    val tmdbRepo = TmdbRepository.getInstance()
    val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    // DAOs
    private val profileDao = db.userProfileDao()
    private val categoryDao = db.categoryCacheDao()
    private val epgDao = db.epgCacheDao()
    private val historyDao = db.playbackHistoryDao()
    private val favoriteDao = db.favoriteItemDao()

    // ─── CONNECTIVITY MONITOR ────────────────────────────────────────────────
    val isOnline: StateFlow<Boolean> = portalSessionRepo.isOnline

    // ─── USER PROFILES & GLOBAL SESSION STATES ──────────────────────────────
    val profiles: StateFlow<List<UserProfile>> = portalSessionRepo.profiles
    val activeTheme: StateFlow<AppThemeColors> = portalSessionRepo.activeThemeId.map { tid ->
        THEMES_LIST.find { it.id == tid } ?: THEMES_LIST[0]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, THEMES_LIST[0])
    val currentProfile: StateFlow<UserProfile?> = portalSessionRepo.currentProfile

    val playlists: StateFlow<List<Playlist>> = portalSessionRepo.playlists
    val settings: StateFlow<PlayerSettings> = portalSessionRepo.settings
    val customThemes: StateFlow<Map<String, Map<String, String>>> = portalSessionRepo.customThemes

    val hiddenTV: StateFlow<Set<String>> = currentProfile.map { p ->
        p?.let { JsonUtils.stringSetFromJson(it.hiddenTvJson) } ?: emptySet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val hiddenMovies: StateFlow<Set<String>> = currentProfile.map { p ->
        p?.let { JsonUtils.stringSetFromJson(it.hiddenMoviesJson) } ?: emptySet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val hiddenSeries: StateFlow<Set<String>> = currentProfile.map { p ->
        p?.let { JsonUtils.stringSetFromJson(it.hiddenSeriesJson) } ?: emptySet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // ─── ACTIVE CATALOG & CONTENT STATES ─────────────────────────────────────
    private val _liveChannels = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val liveChannels: StateFlow<Map<String, List<IptvItem>>> = _liveChannels.asStateFlow()

    private val _movies = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val movies: StateFlow<Map<String, List<IptvItem>>> = _movies.asStateFlow()

    private val _series = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val series: StateFlow<Map<String, List<IptvItem>>> = _series.asStateFlow()

    val precomputedSeriesData: StateFlow<PrecomputedSeriesData?> = _series.map { seriesMap ->
        if (seriesMap.isEmpty()) null else computeSeriesData(seriesMap)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val precomputedMoviesData: StateFlow<PrecomputedMoviesData?> = _movies.map { moviesMap ->
        if (moviesMap.isEmpty()) null else computeMoviesData(moviesMap)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isContentHydrating = MutableStateFlow(false)
    val isContentHydrating: StateFlow<Boolean> = _isContentHydrating.asStateFlow()

    val activePlaylist: StateFlow<Playlist?> = portalSessionRepo.activePlaylist
    private val _activePlaylistName = MutableStateFlow("")
    val activePlaylistName: StateFlow<String> = _activePlaylistName.asStateFlow()

    private val _stalkerSession = MutableStateFlow<Map<String, Any>?>(null)
    val stalkerSession: StateFlow<Map<String, Any>?> = _stalkerSession.asStateFlow()

    private val _stalkerDownloading = MutableStateFlow(false)
    val stalkerDownloading: StateFlow<Boolean> = _stalkerDownloading.asStateFlow()

    private val _stalkerDownloadStats = MutableStateFlow("")
    val stalkerDownloadStats: StateFlow<String> = _stalkerDownloadStats.asStateFlow()

    private var backgroundSyncJob: Job? = null
    private var playlistConnectionJob: Job? = null

    // ─── EPG GUIDE STATES ────────────────────────────────────────────────────
    private val _epgData = MutableStateFlow<Map<String, EpgChannelData>>(emptyMap())
    val epgData: StateFlow<Map<String, EpgChannelData>> = _epgData.asStateFlow()

    private val _epgLoaded = MutableStateFlow(false)
    val epgLoaded: StateFlow<Boolean> = _epgLoaded.asStateFlow()

    private val _epgSourceLabel = MutableStateFlow("")
    val epgSourceLabel: StateFlow<String> = _epgSourceLabel.asStateFlow()

    private val _epgLoading = MutableStateFlow(false)
    val epgLoading: StateFlow<Boolean> = _epgLoading.asStateFlow()

    private val _epgError = MutableStateFlow<String?>(null)
    val epgError: StateFlow<String?> = _epgError.asStateFlow()

    // ─── RECOVERY / FAVORITES & HISTORY ──────────────────────────────────────
    val playbackHistory: StateFlow<List<PlaybackHistory>> = historyDao.getAllHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoritesList: StateFlow<List<FavoriteItem>> = favoriteDao.getAllFavoritesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── CONNECTION DIALOG LOGGING ───────────────────────────────────────────
    private val _connectStatus = MutableStateFlow<String?>(null)
    val connectStatus: StateFlow<String?> = _connectStatus.asStateFlow()

    private val _connectErrorMsg = MutableStateFlow<String?>(null)
    val connectErrorMsg: StateFlow<String?> = _connectErrorMsg.asStateFlow()

    private val _connectLog = MutableStateFlow("")
    val connectLog: StateFlow<String> = _connectLog.asStateFlow()

    private val _connectingPlaylistId = MutableStateFlow<Long?>(null)
    val connectingPlaylistId: StateFlow<Long?> = _connectingPlaylistId.asStateFlow()

    private var playlistGeneration = 0
    private var lastHydratedPlaylistId: Long? = null

    init {
        viewModelScope.launch {
            portalSessionRepo.activePlaylist.collect { activePl ->
                val activeId = activePl?.id
                val contentEmpty = _liveChannels.value.isEmpty() && _movies.value.isEmpty() && _series.value.isEmpty()
                val isDifferent = activeId != lastHydratedPlaylistId
                if (isDifferent || (activePl != null && contentEmpty && !_isContentHydrating.value)) {
                    Log.d(TAG, "PLAYLIST_SWITCH: activePlaylist flow emitted id=$activeId (lastHydrated=$lastHydratedPlaylistId, contentEmpty=$contentEmpty, hydrating=${_isContentHydrating.value})")
                    hydrateContentForPlaylist(activePl)
                }
            }
        }
    }

    // ─── EPISODES CACHE DELEGATION ───────────────────────────────────────────
    fun getCachedEpisodes(key: String) = iptvRepo.getCachedEpisodes(key)
    fun cacheEpisodes(key: String, episodes: List<EpgChannelData>) = iptvRepo.cacheEpisodes(key, episodes)
    fun clearEpisodesCache() = iptvRepo.clearEpisodesCache()

    fun cancelOngoingJobs() {
        playlistConnectionJob?.cancel()
        backgroundSyncJob?.cancel()
        playlistConnectionJob = null
        backgroundSyncJob = null
        _stalkerDownloading.value = false
        _stalkerDownloadStats.value = ""
    }

    // ─── PROFILE MANAGEMENT ──────────────────────────────────────────────────
    fun switchProfile(profile: UserProfile) {
        viewModelScope.launch {
            portalSessionRepo.selectProfile(profile)
            clearActiveContentInMemory()
            val playlistsList = JsonUtils.playlistsFromJson(profile.playlistsJson)
            val activePl = playlistsList.find { it.active } ?: playlistsList.firstOrNull()
            hydrateContentForPlaylist(activePl)
            
            val settingsObj = JsonUtils.settingsFromJson(profile.settingsJson)
            if (settingsObj.autoUpdate) {
                activePl?.let { connectPlaylist(it, silent = true) }
            }
        }
    }

    fun saveProfiles(list: List<UserProfile>) {
        viewModelScope.launch {
            list.forEach { p -> profileDao.insertProfile(p) }
        }
    }

    fun addProfile(name: String, avatar: String, pin: String) {
        viewModelScope.launch {
            val all = profileDao.getAllProfiles()
            val newId = System.currentTimeMillis()
            val newProfile = UserProfile(
                id = newId, name = name, avatar = avatar, colorIdx = all.size, pin = pin,
                playlistsJson = "[]", settingsJson = JsonUtils.settingsToJson(PlayerSettings())
            )
            profileDao.insertProfile(newProfile)
            portalSessionRepo.selectProfile(newProfile)
            clearActiveContentInMemory()
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            profileDao.deleteProfileById(id)
            val current = currentProfile.value
            if (current?.id == id) {
                val remaining = profileDao.getAllProfiles()
                if (remaining.isNotEmpty()) switchProfile(remaining.first())
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val current = currentProfile.value ?: return@launch
            val playlists = JsonUtils.playlistsFromJson(current.playlistsJson)
            val deleted = playlists.find { it.id == playlistId } ?: return@launch

            // If the playlist being deleted is currently connecting or was active, cancel all downloads immediately
            if (_connectingPlaylistId.value == playlistId || deleted.active) {
                cancelOngoingJobs()
                dismissConnectDialog()
            }

            // If it was active, clear in-memory content immediately
            if (deleted.active) {
                clearActiveContentInMemory()
            }

            // Remove disk cache files
            withContext(Dispatchers.IO) {
                try {
                    listOf("tv", "movie", "series").forEach { type ->
                        val file = getPlaylistCacheFile(playlistId, type)
                        if (file.exists()) file.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting cache files for ID $playlistId", e)
                }
            }

            // Remove from profile and update
            val remaining = playlists.filter { it.id != playlistId }
            val updatedList = if (deleted.active && remaining.isNotEmpty()) {
                val first = remaining.first().copy(active = true)
                listOf(first) + remaining.drop(1)
            } else {
                remaining
            }

            portalSessionRepo.updateCurrentProfile { prof ->
                prof.copy(playlistsJson = JsonUtils.playlistsToJson(updatedList))
            }

            // Hydrate newly active playlist if any
            val newActive = updatedList.find { it.active }
            if (newActive != null && deleted.active) {
                hydrateContentForPlaylist(newActive)
            }
        }
    }

    fun updateProfile(id: Long, name: String, avatar: String, pin: String) {
        viewModelScope.launch {
            val profile = profileDao.getProfileById(id) ?: return@launch
            val updated = profile.copy(name = name, avatar = avatar, pin = pin)
            profileDao.insertProfile(updated)
            if (currentProfile.value?.id == id) {
                portalSessionRepo.selectProfile(updated)
            }
        }
    }

    fun updateCurrentProfileField(
        name: String? = null, avatar: String? = null, pin: String? = null,
        themeId: String? = null, playlists: List<Playlist>? = null,
        settings: PlayerSettings? = null, customThemesMap: Map<String, Map<String, String>>? = null,
        hiddenTvSet: Set<String>? = null, hiddenMoviesSet: Set<String>? = null,
        hiddenSeriesSet: Set<String>? = null
    ) {
        viewModelScope.launch {
            val current = currentProfile.value ?: return@launch
            val oldPlaylists = JsonUtils.playlistsFromJson(current.playlistsJson)
            portalSessionRepo.updateCurrentProfile { prof ->
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

    // ─── CONTENT HYDRATION & FILE CACHE ──────────────────────────────────────
    private fun clearActiveContentInMemory() {
        _liveChannels.value = emptyMap()
        _movies.value = emptyMap()
        _series.value = emptyMap()
        _activePlaylistName.value = ""
        _stalkerSession.value = null
        _epgData.value = emptyMap()
        _epgLoaded.value = false
        _epgSourceLabel.value = ""
        clearEpisodesCache()
    }

    private fun getPlaylistCacheFile(playlistId: Long, type: String): File {
        return File(getApplication<Application>().filesDir, "playlist_cache_${playlistId}_${type}.json")
    }

    private suspend fun savePlaylistCacheToFile(playlistId: Long, type: String, data: Map<String, List<IptvItem>>) {
        withContext(Dispatchers.IO) {
            try {
                val file = getPlaylistCacheFile(playlistId, type)
                file.sink().buffer().use { moshiAdapter().toJson(it, data) }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving cache file for ID $playlistId type $type", e)
            }
        }
    }

    private suspend fun loadPlaylistCacheFromFile(playlistId: Long, type: String): Map<String, List<IptvItem>>? {
        return withContext(Dispatchers.IO) {
            try {
                val file = getPlaylistCacheFile(playlistId, type)
                if (file.exists()) {
                    file.source().buffer().use { moshiAdapter().fromJson(it) }
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cache file for ID $playlistId type $type", e)
                null
            }
        }
    }

    private suspend fun hydrateContentForPlaylist(activePl: Playlist?) {
        val generation = ++playlistGeneration
        val oldId = lastHydratedPlaylistId
        val newId = activePl?.id
        Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] START hydration: oldPlaylistId=$oldId -> newPlaylistId=$newId (${activePl?.name})")

        _isContentHydrating.value = true
        cancelOngoingJobs()
        clearActiveContentInMemory()
        Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Cleared in-memory active content")

        if (activePl == null) {
            lastHydratedPlaylistId = null
            _isContentHydrating.value = false
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Finished (active playlist is null)")
            return
        }

        _activePlaylistName.value = activePl.name

        // 1. Carga INSTANTÁNEA desde Room Database
        val tvResult = mediaCacheRepo.getCategoryCache(activePl.id, "tv")
        if (generation != playlistGeneration) {
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Discarded after tv cache read (current is $playlistGeneration)")
            return
        }

        val movieResult = mediaCacheRepo.getCategoryCache(activePl.id, "movie")
        if (generation != playlistGeneration) {
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Discarded after movie cache read (current is $playlistGeneration)")
            return
        }

        val seriesResult = mediaCacheRepo.getCategoryCache(activePl.id, "series")
        if (generation != playlistGeneration) {
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Discarded after series cache read (current is $playlistGeneration)")
            return
        }

        var tvData = tvResult.data
        var movieData = movieResult.data
        var seriesData = seriesResult.data

        if (tvData == null && movieData == null && seriesData == null) {
            tvData = loadPlaylistCacheFromFile(activePl.id, "tv")
            movieData = loadPlaylistCacheFromFile(activePl.id, "movie")
            seriesData = loadPlaylistCacheFromFile(activePl.id, "series")
        }

        if (generation != playlistGeneration) {
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Discarded after disk cache read (current is $playlistGeneration)")
            return
        }

        val hasCache = !tvData.isNullOrEmpty() || !movieData.isNullOrEmpty() || !seriesData.isNullOrEmpty()

        if (hasCache) {
            _liveChannels.value = tvData ?: emptyMap()
            _movies.value = movieData ?: emptyMap()
            _series.value = seriesData ?: emptyMap()
            lastHydratedPlaylistId = activePl.id

            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Published cached content for id=${activePl.id}: " +
                    "tv=${tvData?.values?.sumOf { it.size } ?: 0}, " +
                    "movies=${movieData?.values?.sumOf { it.size } ?: 0}, " +
                    "series=${seriesData?.values?.sumOf { it.size } ?: 0}")

            val isStalkerPl = activePl.type.lowercase() in listOf("stalker", "mac")
            if (isStalkerPl && _stalkerSession.value == null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val dev = JsonUtils.stalkerDeviceFromJson(activePl.stalkerDeviceJson)
                        val session = StalkerParser.performStalkerHandshake(activePl.host, activePl.mac, dev)
                        if (generation == playlistGeneration) {
                            _stalkerSession.value = session
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Light Stalker handshake failed: ${e.message}")
                    }
                }
            }
        }

        // 2. Lógica de control de tiempo (TTL): Si no hay caché o si ha caducado el tiempo de categorías con caché, consulta en segundo plano
        val tvExpired = (tvResult.updatedAt > 0L && tvResult.isExpired)
        val movieExpired = (movieResult.updatedAt > 0L && movieResult.isExpired)
        val seriesExpired = (seriesResult.updatedAt > 0L && seriesResult.isExpired)
        val isExpired = !hasCache || tvExpired || movieExpired || seriesExpired

        if (!hasCache || isExpired) {
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Cache missing or expired (hasCache=$hasCache, tvExp=$tvExpired, movExp=$movieExpired, serExp=$seriesExpired). Triggering connectPlaylist.")
            connectPlaylist(activePl, silent = hasCache, generation = generation)
        } else {
            _isContentHydrating.value = false
            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $generation] Hydration complete from fresh cache")
        }
    }

    // ─── PLAYLIST CONNECTION METHOD ──────────────────────────────────────────
    fun connectPlaylist(pl: Playlist, silent: Boolean = false, generation: Int = ++playlistGeneration) {
        val currentGen = generation
        Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] connectPlaylist started for id=${pl.id} name=${pl.name} (silent=$silent)")

        if (_connectingPlaylistId.value == pl.id && playlistConnectionJob?.isActive == true) {
            if (!silent) {
                _connectStatus.value = "connecting"
                if (_connectLog.value.isEmpty()) _connectLog.value = "Conectando al servidor..."
                _connectErrorMsg.value = null
            }
            return
        }

        _connectingPlaylistId.value = pl.id
        if (!silent) {
            _connectStatus.value = "connecting"
            _connectLog.value = "Conectando al servidor..."
            _connectErrorMsg.value = null
        }
        cancelOngoingJobs()

        playlistConnectionJob = viewModelScope.launch {
            try {
                if (currentGen != playlistGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Cancelled before connection work (current=$playlistGeneration)")
                    return@launch
                }

                if (!silent) _connectLog.value = "Conectando al servidor..."

                var finalCatalog: Map<String, List<IptvItem>>? = null
                var expiryDate: String = pl.expires
                var finalPlaylist = pl

                val isStalkerPl = pl.type.lowercase() in listOf("stalker", "mac")
                if (isStalkerPl) {
                    if (!silent) _connectLog.value = "Conectando al portal Stalker..."
                    val dev = JsonUtils.stalkerDeviceFromJson(pl.stalkerDeviceJson)
                    _stalkerDownloading.value = true
                    _stalkerDownloadStats.value = "Conectando al portal Stalker..."
                    val catalog = withContext(Dispatchers.IO) {
                        StalkerParser.fetchStalkerCatalog(
                            host = pl.host,
                            mac = pl.mac,
                            device = dev,
                            onProgress = { msg ->
                                if (currentGen == playlistGeneration) {
                                    if (!silent) _connectLog.value = msg
                                    _stalkerDownloadStats.value = msg
                                }
                            },
                            onPartialUpdate = { type, items ->
                                if (currentGen == playlistGeneration) {
                                    val grouped = items.groupBy { it.group }
                                    when (type) {
                                        "tv" -> {
                                            _liveChannels.value = grouped
                                            _activePlaylistName.value = pl.name
                                            lastHydratedPlaylistId = pl.id
                                            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Stalker partial update TV: ${items.size} channels published")
                                            viewModelScope.launch(Dispatchers.IO) {
                                                if (currentGen != playlistGeneration) return@launch
                                                try {
                                                    savePlaylistCacheToFile(pl.id, "tv", grouped)
                                                    mediaCacheRepo.saveCategoryCache(pl.id, "tv", grouped)
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Error guardando caché de canales TV", e)
                                                }
                                            }
                                        }
                                        "movie" -> {
                                            _movies.value = grouped
                                            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Stalker partial update Movie: ${items.size} movies published")
                                            val sCount = _series.value.values.sumOf { it.size }
                                            _stalkerDownloadStats.value = "Catálogo: ${items.size} películas · $sCount series"
                                        }
                                        "series" -> {
                                            _series.value = grouped
                                            Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Stalker partial update Series: ${items.size} series published")
                                            val mCount = _movies.value.values.sumOf { it.size }
                                            _stalkerDownloadStats.value = "Catálogo: $mCount películas · ${items.size} series"
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Discarding Stalker partial update $type (current=$playlistGeneration)")
                                }
                            },
                            onSessionReady = { session ->
                                if (currentGen == playlistGeneration) {
                                    _stalkerSession.value = session
                                }
                            }
                        )
                    }
                    _stalkerDownloading.value = false
                    _stalkerDownloadStats.value = ""
                    val tv = catalog["tv"] as? List<IptvItem> ?: emptyList()
                    val movie = catalog["movie"] as? List<IptvItem> ?: emptyList()
                    val series = catalog["series"] as? List<IptvItem> ?: emptyList()
                    finalCatalog = mapOf("tv" to tv, "movie" to movie, "series" to series)
                    if (currentGen == playlistGeneration) {
                        _stalkerSession.value = catalog["stalkerSession"] as? Map<String, Any>
                    }
                } else {
                    if (!silent) _connectLog.value = "Conectando..."
                    var host = pl.host
                    var port = pl.port
                    var user = pl.user
                    var pass = pl.pass

                    if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
                        val inputUrl = pl.url.ifEmpty { pl.host }
                        if (!silent) _connectLog.value = "Analizando lista..."
                        val creds = withContext(Dispatchers.IO) {
                            XtreamParser.resolveXtreamCredentials(inputUrl)
                        }
                        if (creds != null) {
                            host = creds.host
                            port = creds.port
                            user = creds.user
                            pass = creds.pass
                            finalPlaylist = pl.copy(
                                host = host,
                                port = port,
                                user = user,
                                pass = pass
                            )
                        }
                    }

                    if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                        throw NetworkError.NotFound("No se pudieron extraer las credenciales del enlace proporcionado")
                    }

                    val catalog = withContext(Dispatchers.IO) {
                        XtreamParser.fetchXtreamCatalog(host, port, user, pass) { msg ->
                            if (currentGen == playlistGeneration && !silent) {
                                _connectLog.value = msg
                            }
                        }
                    }
                    finalCatalog = catalog
                    val userInfo = authDataToExpiry(catalog)
                    if (!userInfo.isNullOrEmpty()) expiryDate = userInfo
                }

                if (currentGen != playlistGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Discarding fetched catalog for id=${pl.id} (current=$playlistGeneration)")
                    return@launch
                }

                val tvItems = finalCatalog["tv"] ?: emptyList()
                val movieItems = finalCatalog["movie"] ?: emptyList()
                val seriesItems = finalCatalog["series"] ?: emptyList()
                val tvMap = tvItems.groupBy { it.group }
                val movieMap = movieItems.groupBy { it.group }
                val seriesMap = seriesItems.groupBy { it.group }
                val total = tvItems.size + movieItems.size + seriesItems.size

                if (total == 0) throw NetworkError.NotFound("La lista está vacía")

                if (currentGen != playlistGeneration) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Discarding state assignment (current=$playlistGeneration)")
                    return@launch
                }

                _liveChannels.value = tvMap
                _movies.value = movieMap
                _series.value = seriesMap
                _activePlaylistName.value = pl.name
                lastHydratedPlaylistId = pl.id
                _isContentHydrating.value = false

                Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Successfully published catalogs for id=${pl.id}: " +
                        "tv=${tvItems.size}, movies=${movieItems.size}, series=${seriesItems.size}")

                val currentLists = playlists.value.map { p ->
                    if (p.id == pl.id) {
                        finalPlaylist.copy(active = true, expires = if (expiryDate.isNotEmpty()) expiryDate else p.expires)
                    } else {
                        p.copy(active = false)
                    }
                }
                updateCurrentProfileField(playlists = currentLists)

                if (!silent) {
                    _connectStatus.value = "ok"
                    _connectLog.value = "¡Conectado! $total elementos cargados."
                }

                // Guardar en caché de archivos y Room Database de manera asíncrona en segundo plano sin bloquear la UI
                viewModelScope.launch(Dispatchers.IO) {
                    if (currentGen != playlistGeneration) return@launch
                    try {
                        savePlaylistCacheToFile(pl.id, "tv", tvMap)
                        savePlaylistCacheToFile(pl.id, "movie", movieMap)
                        savePlaylistCacheToFile(pl.id, "series", seriesMap)

                        mediaCacheRepo.saveCategoryCache(pl.id, "tv", tvMap)
                        mediaCacheRepo.saveCategoryCache(pl.id, "movie", movieMap)
                        mediaCacheRepo.saveCategoryCache(pl.id, "series", seriesMap)
                        Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Cache saved successfully")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error guardando caché en segundo plano", e)
                    }
                }
            } catch (e: Throwable) {
                _stalkerDownloading.value = false
                _stalkerDownloadStats.value = ""
                _isContentHydrating.value = false
                if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Connection cancelled for id=${pl.id}")
                    throw e
                }
                Log.e(TAG, "PLAYLIST_SWITCH: [Gen $currentGen] Error connecting playlist id=${pl.id}", e)
                val netErr = if (e is NetworkError) e else NetworkError.Unknown(e.message ?: "Error desconocido")
                if (!silent && currentGen == playlistGeneration) {
                    _connectStatus.value = "error"
                    _connectErrorMsg.value = netErr.message
                    _connectLog.value = "✗ " + netErr.message
                }
            }
        }
    }

    fun dismissConnectDialog() {
        _connectingPlaylistId.value = null
        _connectStatus.value = null
        _connectErrorMsg.value = null
        _connectLog.value = ""
    }

    fun getActivePlaylist(): Playlist? {
        portalSessionRepo.activePlaylist.value?.let { return it }
        val prof = currentProfile.value ?: return null
        val list = JsonUtils.playlistsFromJson(prof.playlistsJson)
        return list.find { it.active } ?: list.firstOrNull()
    }

    suspend fun fetchStalkerSeriesEpisodes(seriesId: String): List<EpgChannelData> {
        var session = _stalkerSession.value
        if (session == null) {
            val activePl = getActivePlaylist()
            if (activePl != null && activePl.type.lowercase() in listOf("stalker", "mac")) {
                try {
                    val dev = JsonUtils.stalkerDeviceFromJson(activePl.stalkerDeviceJson)
                    session = withContext(Dispatchers.IO) { StalkerParser.performStalkerHandshake(activePl.host, activePl.mac, dev) }
                    _stalkerSession.value = session
                } catch (e: Exception) {
                    Log.e(TAG, "Handshake error in fetchStalkerSeriesEpisodes", e)
                }
            }
        }
        if (session == null) return emptyList()
        val portalUrl = session["portalUrl"] as? String ?: return emptyList()
        val mac = session["mac"] as? String ?: return emptyList()
        val token = session["token"] as? String ?: ""
        val device = session["device"] as? StalkerDevice ?: return emptyList()
        return withContext(Dispatchers.IO) {
            StalkerParser.fetchStalkerSeriesEpisodes(portalUrl, mac, seriesId, token, device)
        }
    }

    suspend fun resolveStalkerPlayUrl(cmd: String, type: String? = null): String {
        var session = _stalkerSession.value
        if (session == null) {
            val activePl = getActivePlaylist()
            if (activePl != null && activePl.type.lowercase() in listOf("stalker", "mac")) {
                val dev = JsonUtils.stalkerDeviceFromJson(activePl.stalkerDeviceJson)
                session = withContext(Dispatchers.IO) { StalkerParser.performStalkerHandshake(activePl.host, activePl.mac, dev) }
                _stalkerSession.value = session
            }
        }
        if (session == null) throw NetworkError.Auth("No hay sesión de portal Stalker activa")
        return withContext(Dispatchers.IO) { StalkerParser.resolveStalkerPlayLink(session!!, cmd, type) }
    }

    fun getActiveStreamHeaders(): Map<String, String> {
        val session = _stalkerSession.value
        val activePl = getActivePlaylist()

        if (session == null && activePl != null && activePl.type != "stalker" && activePl.type != "mac") {
            return mapOf("User-Agent" to "VLC/3.0.18 LibVLC/3.0.18")
        }

        val macClean = if (session != null) (session["mac"] as? String ?: "").trim().uppercase()
        else activePl?.mac?.trim()?.uppercase() ?: ""

        val device = if (session != null) session["device"] as? StalkerDevice
        else if (activePl != null && activePl.type.lowercase() in listOf("stalker", "mac")) {
            try { JsonUtils.stalkerDeviceFromJson(activePl.stalkerDeviceJson) } catch (e: Exception) { null }
        } else null

        val token = session?.get("token") as? String ?: ""

        val portalUrl = session?.get("portalUrl") as? String ?: activePl?.host ?: ""

        val userAgent = if (!device?.userAgent.isNullOrBlank()) device!!.userAgent else "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        val xUserAgent = if (!device?.xUserAgent.isNullOrBlank()) device!!.xUserAgent else "Model: MAG254; Link: Ethernet"

        val cookieVal = if (macClean.isNotEmpty()) {
            if (token.isNotEmpty()) "mac=$macClean; stb_lang=en; timezone=Europe/Madrid; token=$token;"
            else "mac=$macClean; stb_lang=en; timezone=Europe/Madrid;"
        } else ""

        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "X-User-Agent" to xUserAgent,
            "Accept" to "*/*"
        )
        if (cookieVal.isNotEmpty()) headers["Cookie"] = cookieVal
        if (portalUrl.isNotEmpty()) {
            val portalBase = portalUrl.substringBefore("/c/").substringBefore("/portal").substringBefore("/server").removeSuffix("/")
            headers["Referer"] = "$portalBase/c/"
        }
        return headers
    }

    suspend fun refreshStalkerSession(): Map<String, Any>? {
        val activePl = getActivePlaylist() ?: return null
        if (activePl.type.lowercase() !in listOf("stalker", "mac")) return null
        return withContext(Dispatchers.IO) {
            try {
                val dev = JsonUtils.stalkerDeviceFromJson(activePl.stalkerDeviceJson)
                val newSession = StalkerParser.performStalkerHandshake(activePl.host, activePl.mac, dev)
                _stalkerSession.value = newSession
                newSession
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing Stalker session: ${e.message}", e)
                null
            }
        }
    }

    // ─── EPG LOADER ──────────────────────────────────────────────────────────
    fun loadEPG(url: String, label: String) {
        _epgLoading.value = true
        _epgError.value = null
        _epgSourceLabel.value = label

        viewModelScope.launch {
            try {
                val cachedRows = withContext(Dispatchers.IO) { epgDao.getAllForSource(label) }
                val freshRows = cachedRows.filter { System.currentTimeMillis() - it.updatedAt < 6 * 3600 * 1000 }
                if (freshRows.isNotEmpty()) {
                    val map = HashMap<String, EpgChannelData>()
                    freshRows.forEach { r ->
                        val key = r.channelKey.substringAfter("::")
                        val data = JsonUtils.epgChannelDataFromJson(r.channelDataJson)
                        if (data != null) map[key] = data
                    }
                    _epgData.value = map
                    _epgLoaded.value = true
                    _epgLoading.value = false
                    return@launch
                }

                val parsed = withContext(Dispatchers.IO) { EpgEngine.fetchAndParseEPG(url) }
                withContext(Dispatchers.IO) {
                    epgDao.deleteForSource(label)
                    parsed.forEach { (channelId, data) ->
                        val row = EpgCache("$label::$channelId", label, JsonUtils.epgChannelDataToJson(data), System.currentTimeMillis())
                        epgDao.insert(row)
                    }
                }

                _epgData.value = parsed
                _epgLoaded.value = true
                _epgLoading.value = false
            } catch (e: Exception) {
                _epgError.value = e.message ?: "No se pudo cargar la guía"
                _epgLoading.value = false
            }
        }
    }

    fun workWithoutEPG() {
        _epgError.value = null
        _epgLoading.value = false
        val channelNames = liveChannels.value.values.flatten().map { it.name }
        _epgData.value = epgRepo.buildMockEPG(channelNames)
        _epgLoaded.value = true
        _epgSourceLabel.value = "Demo EPG"
    }

    // ─── PLAYBACK HISTORY & FAVORITES ────────────────────────────────────────
    fun savePlaybackProgress(src: String, title: String, positionSec: Long, durationSec: Long, type: String) {
        if (src.isEmpty() || title.isEmpty() || durationSec < 60) return
        val pct = positionSec.toFloat() / durationSec
        viewModelScope.launch {
            if (pct > 0.95f) {
                historyDao.deleteBySrc(src)
                return@launch
            }
            if (pct < 0.01f) return@launch
            val entry = PlaybackHistory(src, title, positionSec, durationSec, pct, type, System.currentTimeMillis())
            historyDao.insert(entry)
        }
    }

    suspend fun getPlaybackProgress(src: String): PlaybackHistory? = historyDao.getBySrc(src)
    fun deletePlaybackHistory(src: String) { viewModelScope.launch { historyDao.deleteBySrc(src) } }
    fun clearAllPlaybackHistory() { viewModelScope.launch { historyDao.clearAll() } }

    fun addFavorite(id: String, title: String, type: String, url: String, logo: String?) {
        viewModelScope.launch {
            val item = FavoriteItem(id, title, type, url, logo, System.currentTimeMillis())
            favoriteDao.insert(item)
        }
    }

    fun removeFavorite(id: String) { viewModelScope.launch { favoriteDao.deleteById(id) } }
    suspend fun isFavorite(id: String): Boolean = favoriteDao.getById(id) != null
    fun clearAllFavorites() { viewModelScope.launch { favoriteDao.clearAll() } }

    // ─── CACHE CLEANUP & MEMORY PURGE ─────────────────────────────────────────
    suspend fun clearAllCachesAndFreeMemory() = withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.Main) { clearActiveContentInMemory() }
    }

    fun clearAllCategoryAndEpgCaches() {
        viewModelScope.launch { clearAllCachesAndFreeMemory() }
    }

    private fun moshiAdapter() = JsonUtils.moshi.adapter<Map<String, List<IptvItem>>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Types.newParameterizedType(List::class.java, IptvItem::class.java))
    )

    private fun authDataToExpiry(catalog: Map<String, Any?>?): String? {
        val info = catalog?.get("accountInfo") as? Map<*, *> ?: return null
        val exp = info["exp_date"]?.toString() ?: return null
        return try {
            val date = Date(exp.toLong() * 1000)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        } catch (e: Exception) { null }
    }

    // ─── SyncServerDataProvider Implementation ────────────────────────────────
    override fun getPlaylists(): List<Playlist> = playlists.value
    override fun updatePlaylists(playlists: List<Playlist>) {
        updateCurrentProfileField(playlists = playlists)
    }
    override fun connectPlaylist(playlist: Playlist) {
        connectPlaylist(playlist, silent = false)
    }
    override fun getApplicationContext(): Context? = getApplication()

    // ─── TMDB DELEGATION ────────────────────────────────────────────────────
    fun cleanTitleAndExtractYear(rawTitle: String) = tmdbRepo.cleanTitleAndExtractYear(rawTitle)
    fun getCachedYear(title: String, isSeries: Boolean) = tmdbRepo.getCachedYear(title, isSeries)
    suspend fun fetchPosterAndYear(title: String, isSeries: Boolean) = tmdbRepo.fetchPosterAndYear(title, isSeries)
    suspend fun fetchXtreamMediaDetails(item: IptvItem, activePlaylist: Playlist?, isSeries: Boolean) =
        tmdbRepo.fetchXtreamMediaDetails(item, activePlaylist, isSeries)
    suspend fun fetchMediaDetails(title: String, isSeries: Boolean, defaultLogo: String? = null) =
        tmdbRepo.fetchMediaDetails(title, isSeries, defaultLogo)
    suspend fun searchSubtitles(rawTitle: String, isSeries: Boolean, season: Int? = null, episode: Int? = null) =
        tmdbRepo.searchSubtitles(rawTitle, isSeries, season, episode)
}

