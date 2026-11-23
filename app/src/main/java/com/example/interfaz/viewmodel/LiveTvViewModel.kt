package com.example.interfaz.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.interfaz.state.LiveTvState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.parser.RobustDns
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LiveTvViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LiveTvViewModel"

        fun getChannelCheckKey(channel: IptvItem): String {
            return "${channel.name}::${channel.group}::${channel.stalkerCmd ?: ""}::${channel.url ?: ""}"
        }
    }

    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val mediaCacheRepo = MediaCacheRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LiveTvState())
    val uiState: StateFlow<LiveTvState> = _uiState.asStateFlow()

    val settings: StateFlow<PlayerSettings> = sessionRepo.settings

    private val _liveChannels = MutableStateFlow<Map<String, List<IptvItem>>>(emptyMap())
    val liveChannels: StateFlow<Map<String, List<IptvItem>>> = _liveChannels.asStateFlow()

    val favoritesList: StateFlow<List<FavoriteItem>> = iptvRepo.favoriteItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _failedChannelKeys = MutableStateFlow<Set<String>>(emptySet())
    val failedChannelKeys: StateFlow<Set<String>> = _failedChannelKeys.asStateFlow()

    data class ChannelCheckProgress(
        val total: Int = 0,
        val checked: Int = 0,
        val online: Int = 0,
        val failed: Int = 0,
        val isRunning: Boolean = false,
        val isCompleted: Boolean = false
    )

    private val _channelCheckProgress = MutableStateFlow(ChannelCheckProgress())
    val channelCheckProgress: StateFlow<ChannelCheckProgress> = _channelCheckProgress.asStateFlow()

    private val _isCheckingChannels = MutableStateFlow(false)
    val isCheckingChannels: StateFlow<Boolean> = _isCheckingChannels.asStateFlow()

    private val _isParentalUnlocked = MutableStateFlow(false)
    val isParentalUnlocked: StateFlow<Boolean> = _isParentalUnlocked.asStateFlow()

    fun unlockParentalSession() {
        _isParentalUnlocked.value = true
    }

    fun lockParentalSession() {
        _isParentalUnlocked.value = false
    }

    private var channelCheckJob: Job? = null
    private var tvLoadJob: Job? = null

    private var playlistGeneration = 0
    private var lastLoadedPlaylistId: Long? = null
    private val searchQueryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            iptvRepo.favoriteItems.collect { favs ->
                val activeId = sessionRepo.activePlaylist.value?.id
                val filtered = if (activeId != null) favs.filter { it.playlistId == activeId } else emptyList()
                val favIds = filtered.filter { it.type == "tv" }.flatMap { listOfNotNull(it.id, it.url, it.title) }.toSet()
                _uiState.update { state -> state.copy(favoriteIds = favIds) }
            }
        }
        viewModelScope.launch {
            sessionRepo.activePlaylist.collect { pl ->
                val activeId = pl?.id
                val isDifferent = activeId != lastLoadedPlaylistId
                if (isDifferent) {
                    _uiState.update { it.copy(favoriteIds = emptySet()) }
                }
                if (isDifferent || (pl != null && _liveChannels.value.isEmpty())) {
                    cancelChannelCheck()
                    clearChannelCheckResults()
                    loadLiveTvForPlaylist(pl)
                }
            }
        }
        viewModelScope.launch {
            searchQueryFlow
                .debounce(250L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val state = _uiState.value
                    val filtered = withContext(Dispatchers.Default) {
                        filterChannels(state.channels, query, state.selectedFilter, state.favoriteIds)
                    }
                    _uiState.update { it.copy(filteredChannels = filtered) }
                }
        }
        viewModelScope.launch {
            mediaCacheRepo.cacheUpdateEvents.collect { updatedId ->
                val currentActive = sessionRepo.activePlaylist.value
                if (currentActive != null && currentActive.id == updatedId) {
                    loadLiveTvForPlaylist(currentActive)
                }
            }
        }
    }

    private suspend fun loadLiveTvForPlaylist(pl: com.example.motor.config.Playlist?) {
        val generation = ++playlistGeneration
        val oldId = lastLoadedPlaylistId
        val newId = pl?.id

        tvLoadJob?.cancel()

        if (oldId != newId) {
            cancelChannelCheck()
            clearChannelCheckResults()
            _isParentalUnlocked.value = false
            _liveChannels.value = emptyMap()
        }

        if (pl == null) {
            cancelChannelCheck()
            clearChannelCheckResults()
            lastLoadedPlaylistId = null
            Log.d(TAG, "LIVETV_SWITCH: [Gen $generation] Cleared live channels (active playlist is null)")
            return
        }

        Log.d(TAG, "LIVETV_SWITCH: [Gen $generation] START loading TV cache: oldPlaylistId=$oldId -> newPlaylistId=$newId (${pl.name})")

        tvLoadJob = viewModelScope.launch {
            val tvResult = withContext(Dispatchers.IO) {
                mediaCacheRepo.getCategoryCache(pl.id, "tv")
            }

            if (generation != playlistGeneration) {
                Log.d(TAG, "LIVETV_SWITCH: [Gen $generation] Discarded stale TV cache (current is $playlistGeneration)")
                return@launch
            }

            val cachedData = tvResult.data
            if (!cachedData.isNullOrEmpty()) {
                _liveChannels.value = cachedData
                lastLoadedPlaylistId = pl.id
                Log.d(TAG, "LIVETV_SWITCH: [Gen $generation] Successfully loaded ${cachedData.values.sumOf { it.size }} channels across ${cachedData.size} categories for playlist ${pl.id}")
            } else {
                lastLoadedPlaylistId = pl.id
                Log.d(TAG, "LIVETV_SWITCH: [Gen $generation] No TV cache found for playlist ${pl.id}")
            }
        }
    }

    fun updateLiveChannels(channels: Map<String, List<IptvItem>>) {
        _liveChannels.value = channels
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
        if (query.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    searchQuery = "",
                    filteredChannels = filterChannels(state.channels, "", state.selectedFilter, state.favoriteIds)
                )
            }
            searchQueryFlow.value = ""
        } else {
            _uiState.update { it.copy(searchQuery = query) }
            searchQueryFlow.value = query
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
            val id = item.name
            if (_uiState.value.favoriteIds.contains(id) || (item.url != null && _uiState.value.favoriteIds.contains(item.url))) {
                iptvRepo.removeFavorite(id, pl.id)
                item.url?.let { iptvRepo.removeFavorite(it, pl.id) }
            } else {
                iptvRepo.addFavorite(id, item.name, "tv", item.url ?: "", item.logo, pl.id)
            }
        }
    }

    private fun filterChannels(list: List<IptvItem>, query: String, filter: String, favIds: Set<String>): List<IptvItem> {
        return list.filter { ch ->
            val matchesQuery = query.isBlank() || ch.name.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                "FAVORITES" -> favIds.contains(ch.name) || (ch.url != null && favIds.contains(ch.url))
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    // ─── CHANNEL AVAILABILITY CHECK ──────────────────────────────────────────

    private sealed class StreamCheckResult {
        object Success : StreamCheckResult()
        data class Failed(val reason: String) : StreamCheckResult()
    }

    private val probeHttpClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }

        OkHttpClient.Builder()
            .dns(RobustDns)
            .connectionPool(ConnectionPool(32, 30, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    fun startChannelCheck(hiddenCategories: Collection<String> = emptyList()) {
        cancelChannelCheck()
        clearChannelCheckResults()

        val pl = sessionRepo.activePlaylist.value ?: return
        val plId = pl.id
        val currentChannels = _liveChannels.value
        val hiddenSet = hiddenCategories.toSet()

        val visibleChannels = currentChannels
            .filterKeys { it !in hiddenSet }
            .values
            .flatten()
            .distinctBy { getChannelCheckKey(it) }

        val total = visibleChannels.size
        if (visibleChannels.isEmpty()) {
            _channelCheckProgress.value = ChannelCheckProgress(total = 0, checked = 0, online = 0, failed = 0, isRunning = false, isCompleted = true)
            return
        }

        _isCheckingChannels.value = true
        _channelCheckProgress.value = ChannelCheckProgress(
            total = total,
            checked = 0,
            online = 0,
            failed = 0,
            isRunning = true,
            isCompleted = false
        )

        channelCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val workerCount = 7
            val channelChannel = kotlinx.coroutines.channels.Channel<IptvItem>(capacity = 64)
            val failedSet = ConcurrentHashMap.newKeySet<String>()
            val checkedCount = AtomicInteger(0)
            val onlineCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val timeoutCount = AtomicInteger(0)
            val retryCount = AtomicInteger(0)
            val activeWorkers = AtomicInteger(0)

            val pendingFailedKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val lastUiUpdateTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val startTime = System.currentTimeMillis()

            fun pushUiUpdate(force: Boolean = false) {
                val now = System.currentTimeMillis()
                val last = lastUiUpdateTime.get()
                if (force || (now - last >= 80)) {
                    if (lastUiUpdateTime.compareAndSet(last, now) || force) {
                        if (!pendingFailedKeys.isEmpty()) {
                            val batch = mutableListOf<String>()
                            while (true) {
                                val key = pendingFailedKeys.poll() ?: break
                                batch.add(key)
                            }
                            if (batch.isNotEmpty()) {
                                _failedChannelKeys.update { current -> current + batch }
                            }
                        }
                        _channelCheckProgress.value = ChannelCheckProgress(
                            total = total,
                            checked = checkedCount.get(),
                            online = onlineCount.get(),
                            failed = failedCount.get(),
                            isRunning = true,
                            isCompleted = false
                        )
                    }
                }
            }

            try {
                coroutineScope {
                    // Producer
                    launch {
                        try {
                            for (channel in visibleChannels) {
                                if (!isActive || sessionRepo.activePlaylist.value?.id != plId) break
                                channelChannel.send(channel)
                            }
                        } finally {
                            channelChannel.close()
                        }
                    }

                    // Fixed workers pool
                    val workers = (0 until workerCount).map {
                        launch {
                            for (channel in channelChannel) {
                                if (!isActive || sessionRepo.activePlaylist.value?.id != plId) break
                                activeWorkers.incrementAndGet()
                                try {
                                    val (checkResult, isTimeout, hadRetry) = checkChannelStreamWithStats(channel, pl)
                                    if (isTimeout) timeoutCount.incrementAndGet()
                                    if (hadRetry) retryCount.incrementAndGet()

                                    val currentChecked = checkedCount.incrementAndGet()

                                    if (checkResult is StreamCheckResult.Failed) {
                                        failedCount.incrementAndGet()
                                        val key = getChannelCheckKey(channel)
                                        failedSet.add(key)
                                        pendingFailedKeys.offer(key)
                                    } else {
                                        onlineCount.incrementAndGet()
                                    }

                                    pushUiUpdate(force = (currentChecked == total))
                                } finally {
                                    activeWorkers.decrementAndGet()
                                }
                            }
                        }
                    }

                    workers.forEach { it.join() }
                }

                // Final flush
                val remainingBatch = mutableListOf<String>()
                while (true) {
                    val key = pendingFailedKeys.poll() ?: break
                    remainingBatch.add(key)
                }
                if (remainingBatch.isNotEmpty()) {
                    _failedChannelKeys.update { current -> current + remainingBatch }
                }

                _channelCheckProgress.value = ChannelCheckProgress(
                    total = total,
                    checked = checkedCount.get(),
                    online = onlineCount.get(),
                    failed = failedSet.size,
                    isRunning = false,
                    isCompleted = true
                )
            } catch (e: CancellationException) {
                // Cancelled cleanly
                _channelCheckProgress.update { it.copy(isRunning = false) }
            } catch (_: Exception) {
                _channelCheckProgress.update { it.copy(isRunning = false) }
            } finally {
                _isCheckingChannels.value = false
            }
        }
    }

    fun cancelChannelCheck() {
        channelCheckJob?.cancel()
        channelCheckJob = null
        _isCheckingChannels.value = false
        _channelCheckProgress.update { it.copy(isRunning = false) }
    }

    fun clearChannelCheckResults() {
        _failedChannelKeys.value = emptySet()
        _channelCheckProgress.value = ChannelCheckProgress()
    }

    private data class StreamCheckStats(
        val result: StreamCheckResult,
        val isTimeout: Boolean,
        val hadRetry: Boolean
    )

    private suspend fun checkChannelStreamWithStats(channel: IptvItem, playlist: Playlist): StreamCheckStats = withContext(Dispatchers.IO) {
        val resolved = try {
            iptvRepo.resolveStreamUrl(playlist, channel, "live")
        } catch (e: Exception) {
            return@withContext StreamCheckStats(
                result = StreamCheckResult.Failed(e.javaClass.simpleName),
                isTimeout = false,
                hadRetry = false
            )
        }

        val rawUrl = resolved.first.trim()
        val headers = resolved.second

        if (rawUrl.isBlank()) {
            return@withContext StreamCheckStats(
                result = StreamCheckResult.Failed("Empty stream URL"),
                isTimeout = false,
                hadRetry = false
            )
        }

        val lowerUrl = rawUrl.lowercase()
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return@withContext StreamCheckStats(
                result = StreamCheckResult.Success,
                isTimeout = false,
                hadRetry = false
            )
        }

        // Attempt 1
        val firstAttempt = executeProbe(rawUrl, headers, playlist)
        if (firstAttempt is StreamCheckResult.Success) {
            return@withContext StreamCheckStats(
                result = StreamCheckResult.Success,
                isTimeout = false,
                hadRetry = false
            )
        }

        // Retry 1 quick attempt
        try {
            kotlinx.coroutines.delay(400)
        } catch (_: CancellationException) {
            throw CancellationException()
        }

        val secondAttempt = executeProbe(rawUrl, headers, playlist)
        val isTimeout = (firstAttempt is StreamCheckResult.Failed && firstAttempt.reason.contains("Timeout", ignoreCase = true)) ||
                (secondAttempt is StreamCheckResult.Failed && secondAttempt.reason.contains("Timeout", ignoreCase = true))

        StreamCheckStats(
            result = secondAttempt,
            isTimeout = isTimeout,
            hadRetry = true
        )
    }


    private fun executeProbe(rawUrl: String, headers: Map<String, String>, playlist: Playlist): StreamCheckResult {
        var call: okhttp3.Call? = null
        return try {
            val requestBuilder = Request.Builder().url(rawUrl)

            for ((k, v) in headers) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    requestBuilder.header(k, v)
                }
            }
            val defaultHeaders = iptvRepo.getStreamHeaders(playlist)
            for ((k, v) in defaultHeaders) {
                if (!headers.containsKey(k) && k.isNotBlank() && v.isNotBlank()) {
                    requestBuilder.header(k, v)
                }
            }

            val c = probeHttpClient.newCall(requestBuilder.build())
            call = c
            val response = c.execute()
            response.use { resp ->
                val code = resp.code
                // Redirects are followed by OkHttp automatically.
                // 200..399 indicates valid HTTP response
                if (code !in 200..399) {
                    return StreamCheckResult.Failed("HTTP $code")
                }

                val body = resp.body ?: return StreamCheckResult.Failed("No response body")
                val source = body.source()

                // Read up to 1024 bytes to inspect stream payload without loading the full video
                val buffer = okio.Buffer()
                val readBytes = try {
                    source.read(buffer, 1024)
                } catch (e: Exception) {
                    return StreamCheckResult.Failed("Read error: ${e.javaClass.simpleName}")
                }

                if (readBytes <= 0) {
                    return StreamCheckResult.Failed("Empty stream data")
                }

                val previewBytes = buffer.snapshot().toByteArray()
                val previewStr = try {
                    String(previewBytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }

                // If content looks like an HTML error page (e.g. <html>, <head>, error page returned with 200 OK)
                val trimmedStr = previewStr.trim().lowercase()
                if (trimmedStr.startsWith("<!doctype html") || trimmedStr.startsWith("<html")) {
                    return StreamCheckResult.Failed("HTML error page")
                }

                // If HLS (.m3u8 or #EXTM3U content), verify it's a valid playlist
                if (trimmedStr.startsWith("#extm3u")) {
                    // Valid HLS manifest received
                    return StreamCheckResult.Success
                }

                // For TS/MP4/MPEG or binary streams, receiving bytes from a 200 OK response confirms active data
                StreamCheckResult.Success
            }
        } catch (e: java.net.SocketTimeoutException) {
            call?.cancel()
            StreamCheckResult.Failed("Timeout")
        } catch (e: java.net.ConnectException) {
            call?.cancel()
            StreamCheckResult.Failed("Connection refused")
        } catch (e: java.net.UnknownHostException) {
            call?.cancel()
            StreamCheckResult.Failed("DNS failure")
        } catch (e: javax.net.ssl.SSLException) {
            call?.cancel()
            StreamCheckResult.Failed("SSL error")
        } catch (e: java.lang.IllegalArgumentException) {
            call?.cancel()
            StreamCheckResult.Failed("Invalid URL")
        } catch (e: CancellationException) {
            call?.cancel()
            throw e
        } catch (e: Exception) {
            call?.cancel()
            StreamCheckResult.Failed(e.javaClass.simpleName)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelChannelCheck()
    }
}
