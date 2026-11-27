package com.example.interfaz.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.Translator
import com.example.interfaz.ParentalControl
import com.example.motor.config.JsonUtils
import com.example.motor.config.PlayerSettings
import com.example.motor.db.IptvItem
import com.example.motor.sports.broadcast.SportsTvBroadcastProvider
import com.example.motor.sports.broadcast.TheSportsDbBroadcastResolver
import com.example.motor.sports.matcher.*
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsFilter
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.repository.SportStatus
import com.example.motor.sports.repository.SportsRefreshException
import com.example.motor.sports.repository.SportsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Estado del selector de canales cuando existen múltiples candidatos que superan el umbral SELECTION.
 */
data class ChannelSelectionState(
    val event: SportsEvent,
    val candidates: List<SportsChannelCandidate>,
    val playlistId: Long?
)

data class PendingParentalUnlock(
    val event: SportsEvent,
    val candidate: SportsChannelCandidate,
    val allCandidates: List<SportsChannelCandidate>,
    val playlistId: Long?
)

data class SportsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedSport: SportsSport = SportsSport.FOOTBALL,
    val selectedFilter: SportsFilter = SportsFilter.ALL,
    val liveEvents: List<SportsEvent> = emptyList(),
    val todayEvents: List<SportsEvent> = emptyList(),
    val tomorrowEvents: List<SportsEvent> = emptyList(),
    val weekEvents: List<SportsEvent> = emptyList(),
    val categoryEventCounts: Map<SportsSport, Int> = emptyMap(),
    val errorMessage: String? = null,
    val channelSelectionState: ChannelSelectionState? = null,
    val isMatchingChannel: Boolean = false,
    val isParentalUnlocked: Boolean = false,
    val pendingParentalUnlock: PendingParentalUnlock? = null
) {
    /**
     * Calcula de forma reactiva el número de eventos de un deporte dentro del conjunto
     * disponible en el estado o en el mapa de categorías cargadas.
     * Si la categoría no tiene eventos, retorna 0.
     */
    fun getEventCountForSport(sport: SportsSport): Int {
        if (selectedSport == sport) {
            if (isLoading && liveEvents.isEmpty() && todayEvents.isEmpty() && tomorrowEvents.isEmpty() && weekEvents.isEmpty()) {
                return categoryEventCounts[sport] ?: 0
            }
            return liveEvents.size + todayEvents.size + tomorrowEvents.size + weekEvents.size
        }
        val directCount = (liveEvents + todayEvents + tomorrowEvents + weekEvents).count {
            SportsSport.fromId(it.sport) == sport
        }
        if (directCount > 0) return directCount
        return categoryEventCounts[sport] ?: 0
    }

    val totalEventsCount: Int
        get() = when (selectedFilter) {
            SportsFilter.ALL -> liveEvents.size + todayEvents.size + tomorrowEvents.size + weekEvents.size
            SportsFilter.LIVE -> liveEvents.size
            SportsFilter.TODAY -> todayEvents.size
            SportsFilter.TOMORROW -> tomorrowEvents.size
            SportsFilter.THIS_WEEK -> weekEvents.size
        }

    val isEmpty: Boolean
        get() = !isLoading && totalEventsCount == 0
}

class SportsViewModel(application: Application) : AndroidViewModel(application) {

    private var repository: SportsRepository = SportsRepository.getInstance(application)
    private var channelsProvider: ActiveTvChannelsProvider = DefaultActiveTvChannelsProvider.getInstance(application)
    private var matcher: SportsChannelMatcher = DefaultSportsChannelMatcher()
    private var broadcastResolver: SportsTvBroadcastProvider = TheSportsDbBroadcastResolver.getInstance()
    private val sessionRepo = com.example.motor.repository.PortalSessionRepository.getInstance(application)
    private val iptvRepo = com.example.motor.repository.IptvRepository.getInstance(application, sessionRepo)
    internal var streamResolverOverride: (suspend (IptvItem) -> Pair<String, Map<String, String>>)? = null

    constructor(
        application: Application,
        repository: SportsRepository,
        channelsProvider: ActiveTvChannelsProvider,
        matcher: SportsChannelMatcher,
        broadcastResolver: SportsTvBroadcastProvider
    ) : this(application) {
        this.repository = repository
        this.channelsProvider = channelsProvider
        this.matcher = matcher
        this.broadcastResolver = broadcastResolver
    }

    init {
        Log.d(TheSportsDbBroadcastResolver.TAG, "[THESPORTSDB] SportsViewModel initialized")
        viewModelScope.launch {
            var lastProfileId: Long? = sessionRepo.currentProfile.value?.id
            sessionRepo.currentProfile.collect { profile ->
                if (profile?.id != lastProfileId) {
                    lastProfileId = profile?.id
                    unlockedProfileId = null
                    _uiState.update {
                        it.copy(
                            isParentalUnlocked = false,
                            pendingParentalUnlock = null
                        )
                    }
                }
            }
        }
    }

    private val loadedSportCounts = ConcurrentHashMap<SportsSport, Int>()
    private var unlockedProfileId: Long? = null

    private fun buildCategoryCounts(): Map<SportsSport, Int> {
        val repoCounts = repository.getAllCachedEventCounts()
        return SportsSport.entries.associateWith { sport ->
            maxOf(repoCounts[sport] ?: 0, loadedSportCounts[sport] ?: 0)
        }
    }

    private val _uiState = MutableStateFlow(
        SportsUiState(
            categoryEventCounts = buildCategoryCounts(),
            errorMessage = null
        )
    )
    val uiState: StateFlow<SportsUiState> = _uiState.asStateFlow()

    private val _lastPlayedChannel = MutableStateFlow<IptvItem?>(null)
    val lastPlayedChannel: StateFlow<IptvItem?> = _lastPlayedChannel.asStateFlow()

    fun getActivePlaylistId(): Long? = channelsProvider.getActivePlaylistId()

    private var loadJob: Job? = null
    private var livePollingJob: Job? = null
    private var preloadJob: Job? = null
    private var matchJob: Job? = null
    @Volatile
    private var hasPreloadedCategories = false

    fun selectSport(sport: SportsSport) {
        if (_uiState.value.selectedSport == sport) return
        _uiState.update {
            it.copy(
                selectedSport = sport,
                isLoading = true,
                liveEvents = emptyList(),
                todayEvents = emptyList(),
                tomorrowEvents = emptyList(),
                weekEvents = emptyList(),
                errorMessage = null,
                channelSelectionState = null
            )
        }
        loadData(sport = sport, forceRefresh = false)
    }

    fun selectFilter(filter: SportsFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun preloadCategoryCounts() {
        if (hasPreloadedCategories) return
        hasPreloadedCategories = true
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            val sportsToPreload = listOf(
                SportsSport.FORMULA1,
                SportsSport.MOTOS,
                SportsSport.BASKETBALL,
                SportsSport.TENNIS,
                SportsSport.UFC,
                SportsSport.BOXING,
                SportsSport.RUGBY,
                SportsSport.BASEBALL,
                SportsSport.GOLF,
                SportsSport.OTHER
            )
            supervisorScope {
                sportsToPreload.forEach { sport ->
                    launch(Dispatchers.IO) {
                        try {
                            val liveDef = async { repository.getLiveEvents(sport, false).filterCleanEvents() }
                            val todayDef = async { repository.getTodayEvents(sport, false).filterCleanEvents() }
                            val tomorrowDef = async { repository.getTomorrowEvents(sport, false).filterCleanEvents() }
                            val weekDef = async { repository.getWeekEvents(sport, false).filterCleanEvents() }

                            val live = liveDef.await()
                            val liveIds = live.map { it.id }.toSet()
                            val today = todayDef.await().filter { it.id !in liveIds && it.status != SportsEventStatus.LIVE }
                            val tomorrow = tomorrowDef.await().filter { it.id !in liveIds }
                            val excludedIds = liveIds + today.map { it.id } + tomorrow.map { it.id }
                            val week = weekDef.await().filter { it.id !in excludedIds && it.status != SportsEventStatus.LIVE }

                            val count = live.size + today.size + tomorrow.size + week.size
                            loadedSportCounts[sport] = count
                            val updatedCounts = buildCategoryCounts().toMutableMap().apply {
                                put(sport, count)
                            }
                            _uiState.update { state ->
                                state.copy(categoryEventCounts = updatedCounts)
                            }
                        } catch (_: Exception) {
                            // No interrumpir el resto de categorías si una falla
                        }
                    }
                }
            }
        }
    }

    fun loadData(
        sport: SportsSport = _uiState.value.selectedSport,
        forceRefresh: Boolean = false
    ) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.liveEvents.isEmpty() && it.todayEvents.isEmpty() && it.tomorrowEvents.isEmpty() && it.weekEvents.isEmpty(),
                    isRefreshing = forceRefresh,
                    errorMessage = null
                )
            }

            try {
                val live = withContext(Dispatchers.IO) {
                    repository.getLiveEvents(sport, forceRefresh)
                }.filterCleanEvents()

                val liveIds = live.map { it.id }.toSet()

                val today = withContext(Dispatchers.IO) {
                    repository.getTodayEvents(sport, forceRefresh)
                }.filterCleanEvents().filter { it.id !in liveIds && it.status != SportsEventStatus.LIVE }

                val tomorrow = withContext(Dispatchers.IO) {
                    repository.getTomorrowEvents(sport, forceRefresh)
                }.filterCleanEvents().filter { it.id !in liveIds }

                val initialTotal = live.size + today.size + tomorrow.size + _uiState.value.weekEvents.size
                loadedSportCounts[sport] = initialTotal
                val initialCategoryCounts = buildCategoryCounts().toMutableMap().apply {
                    put(sport, initialTotal)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        liveEvents = live,
                        todayEvents = today,
                        tomorrowEvents = tomorrow,
                        categoryEventCounts = initialCategoryCounts,
                        errorMessage = null
                    )
                }

                val excludedIds = liveIds + today.map { it.id } + tomorrow.map { it.id }
                val week = try {
                    withContext(Dispatchers.IO) {
                        repository.getWeekEvents(sport, forceRefresh)
                    }.filterCleanEvents().filter { it.id !in excludedIds && it.status != SportsEventStatus.LIVE }
                } catch (_: Exception) {
                    _uiState.value.weekEvents
                }

                val sportTotal = live.size + today.size + tomorrow.size + week.size
                loadedSportCounts[sport] = sportTotal
                val updatedCategoryCounts = buildCategoryCounts().toMutableMap().apply {
                    put(sport, sportTotal)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        weekEvents = week,
                        categoryEventCounts = updatedCategoryCounts
                    )
                }
            } catch (e: SportsRefreshException) {
                // Si el refresco falló pero existen datos previos de caché, se conservan y se notifica el fallo
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = Translator.translate("sports_refresh_error", currentLang())
                    )
                }
            } catch (e: Exception) {
                val lang = currentLang()
                _uiState.update {
                    val hasExistingData = it.liveEvents.isNotEmpty() || it.todayEvents.isNotEmpty() || it.tomorrowEvents.isNotEmpty() || it.weekEvents.isNotEmpty()
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (hasExistingData) {
                            Translator.translate("sports_refresh_error", lang)
                        } else {
                            Translator.translate("sports_load_failed", lang)
                        }
                    )
                }
            }
        }
    }

    /**
     * Empareja un evento deportivo con los canales de la playlist activa.
     *
     * Flujo determinista (Fase 3):
     * - AUTO_PLAY: 1 candidato claro con confianza alta -> Reproduce inmediatamente.
     * - SELECTION: Múltiples candidatos válidos -> Despliega el diálogo selector.
     * - NO_MATCH: Ninguna coincidencia suficiente -> Muestra aviso de canal no encontrado.
     */
    private sealed class StreamResolutionResult {
        data class Success(val url: String, val headers: Map<String, String>) : StreamResolutionResult()
        data class Failure(val message: String) : StreamResolutionResult()
    }

    internal fun buildStreamResolutionErrorMessage(channelName: String, error: Throwable?): String {
        val lang = currentLang()
        val detail = error?.message?.trim()?.takeIf { it.isNotBlank() }
        return when (error) {
            is com.example.motor.parser.NetworkError.PortalBlockedMac ->
                Translator.translate(
                    "sports_stream_blocked_mac",
                    lang,
                    channelName,
                    detail ?: Translator.translate("sports_stream_blocked_mac_default", lang)
                )
            is com.example.motor.parser.NetworkError.Auth,
            is com.example.motor.parser.NetworkError.TokenExpired ->
                Translator.translate(
                    "sports_stream_session_expired",
                    lang,
                    channelName,
                    detail ?: Translator.translate("sports_stream_session_expired_default", lang)
                )
            is com.example.motor.parser.NetworkError.Timeout,
            is com.example.motor.parser.NetworkError.ServerDown,
            is com.example.motor.parser.NetworkError.Dns ->
                Translator.translate(
                    "sports_stream_connection_error",
                    lang,
                    channelName,
                    detail ?: Translator.translate("sports_stream_connection_error_default", lang)
                )
            is com.example.motor.parser.NetworkError ->
                Translator.translate(
                    "sports_stream_open_error",
                    lang,
                    channelName,
                    detail ?: Translator.translate("sports_stream_portal_error_default", lang)
                )
            null ->
                Translator.translate("sports_stream_no_link", lang, channelName)
            else ->
                Translator.translate(
                    "sports_stream_open_error",
                    lang,
                    channelName,
                    detail ?: Translator.translate("sports_stream_resolve_error_default", lang)
                )
        }
    }

    private suspend fun resolvePlayableChannelResult(channel: IptvItem): StreamResolutionResult {
        val fallbackDirectUrl = channel.url?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(STALKER_NO_PLAYABLE_CMD, ignoreCase = true)
        }
        val override = streamResolverOverride
        if (override != null) {
            return try {
                val (resolvedUrl, resolvedHeaders) = override(channel)
                if (resolvedUrl.isNotBlank()) {
                    StreamResolutionResult.Success(resolvedUrl, resolvedHeaders)
                } else {
                    StreamResolutionResult.Failure(buildStreamResolutionErrorMessage(channel.name, null))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SportsViewModel", "Error resolving stream for ${channel.name}: ${e.message}")
                if (fallbackDirectUrl != null) {
                    StreamResolutionResult.Success(
                        fallbackDirectUrl,
                        iptvRepo.getStreamHeaders(sessionRepo.activePlaylist.value)
                    )
                } else {
                    StreamResolutionResult.Failure(buildStreamResolutionErrorMessage(channel.name, e))
                }
            }
        }

        val pl = sessionRepo.activePlaylist.value
        return if (pl != null) {
            try {
                val (resolvedUrl, resolvedHeaders) = iptvRepo.resolveStreamUrl(pl, channel, "live")
                if (resolvedUrl.isNotBlank()) {
                    StreamResolutionResult.Success(resolvedUrl, resolvedHeaders)
                } else {
                    StreamResolutionResult.Failure(buildStreamResolutionErrorMessage(channel.name, null))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SportsViewModel", "Error resolving stream for ${channel.name}: ${e.message}")
                if (fallbackDirectUrl != null) {
                    StreamResolutionResult.Success(fallbackDirectUrl, iptvRepo.getStreamHeaders(pl))
                } else {
                    StreamResolutionResult.Failure(buildStreamResolutionErrorMessage(channel.name, e))
                }
            }
        } else if (fallbackDirectUrl != null) {
            StreamResolutionResult.Success(fallbackDirectUrl, iptvRepo.getStreamHeaders(null))
        } else {
            StreamResolutionResult.Failure(
                Translator.translate("sports_no_active_playlist_for_channel", currentLang(), channel.name)
            )
        }
    }

    fun matchAndPlayEvent(
        event: SportsEvent,
        onPlay: (src: String, title: String, dScheme: String?, dLicense: String?, matchChannels: List<IptvItem>, matchCategoryName: String?, headers: Map<String, String>) -> Unit,
        onNotice: (String) -> Unit
    ) {
        if (matchJob?.isActive == true || _uiState.value.isMatchingChannel) {
            return
        }
        _uiState.update { it.copy(isMatchingChannel = true) }
        matchJob = viewModelScope.launch {
            try {
                val currentPlaylistId = channelsProvider.getActivePlaylistId()
                val channels = withContext(Dispatchers.IO) {
                    channelsProvider.getActiveTvChannels()
                }

                if (channels.isEmpty()) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                    onNotice(Translator.translate("sports_no_channels", currentLang()))
                    return@launch
                }

                // Consultar TheSportsDB exclusivamente bajo demanda para el partido seleccionado
                val broadcastInfo = try {
                    withContext(Dispatchers.IO) {
                        broadcastResolver.resolveBroadcastInfo(event)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }

                if (channelsProvider.getActivePlaylistId() != currentPlaylistId) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                    onNotice(Translator.translate("sports_playlist_changed", currentLang()))
                    return@launch
                }

                val enrichedEvent = if (broadcastInfo != null && broadcastInfo.channels.isNotEmpty()) {
                    event.withBroadcasters(broadcastInfo.channels)
                } else {
                    event
                }

                val matchResult = withContext(Dispatchers.Default) {
                    matcher.matchChannel(enrichedEvent, channels, currentPlaylistId)
                }

                if (channelsProvider.getActivePlaylistId() != currentPlaylistId) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                    onNotice(Translator.translate("sports_playlist_changed", currentLang()))
                    return@launch
                }

                when (matchResult.decision) {
                    MatchDecision.AUTO_PLAY -> {
                        val candidate = matchResult.topMatch
                        if (candidate != null) {
                            if (isChannelParentalLocked(candidate.channel)) {
                                ParentalControl.logParental(
                                    enabled = true,
                                    channel = candidate.channel.name,
                                    restricted = true,
                                    pinRequired = true,
                                    pinSuccess = false
                                )
                                _uiState.update {
                                    it.copy(
                                        isMatchingChannel = false,
                                        channelSelectionState = null,
                                        pendingParentalUnlock = PendingParentalUnlock(
                                            event = event,
                                            candidate = candidate,
                                            allCandidates = matchResult.candidates.ifEmpty { listOf(candidate) },
                                            playlistId = currentPlaylistId
                                        )
                                    )
                                }
                                return@launch
                            }
                            val resolution = withContext(Dispatchers.IO) {
                                resolvePlayableChannelResult(candidate.channel)
                            }
                            _uiState.update { it.copy(isMatchingChannel = false) }
                            if (channelsProvider.getActivePlaylistId() != currentPlaylistId) {
                                onNotice(Translator.translate("sports_playlist_changed", currentLang()))
                                return@launch
                            }
                            when (resolution) {
                                is StreamResolutionResult.Success -> {
                                    _uiState.update { it.copy(channelSelectionState = null) }
                                    val matchChannels = filterAllowedMatchChannels(
                                        matchResult.candidates.map { it.channel }.ifEmpty { listOf(candidate.channel) }
                                    )
                                    val matchTitle = "${event.homeTeam} vs ${event.awayTeam}"
                                    _lastPlayedChannel.value = candidate.channel
                                    onPlay(
                                        resolution.url,
                                        candidate.channel.name,
                                        candidate.channel.drmScheme,
                                        candidate.channel.drmLicenseUrl,
                                        matchChannels,
                                        matchTitle,
                                        resolution.headers
                                    )
                                }
                                is StreamResolutionResult.Failure -> {
                                    onNotice(resolution.message)
                                }
                            }
                        } else {
                            _uiState.update { it.copy(isMatchingChannel = false) }
                            onNotice(Translator.translate("sports_channel_not_found", currentLang()))
                        }
                    }
                    MatchDecision.SELECTION -> {
                        _uiState.update {
                            it.copy(
                                isMatchingChannel = false,
                                channelSelectionState = ChannelSelectionState(
                                    event = event,
                                    candidates = matchResult.candidates,
                                    playlistId = currentPlaylistId
                                )
                            )
                        }
                    }
                    MatchDecision.NO_MATCH -> {
                        _uiState.update { it.copy(isMatchingChannel = false) }
                        onNotice(Translator.translate("sports_channel_not_found", currentLang()))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                val lang = currentLang()
                val detail = e.message?.trim()?.takeIf { it.isNotBlank() }
                onNotice(
                    detail?.let { Translator.translate("sports_search_error_detail", lang, it) }
                        ?: Translator.translate("sports_search_error", lang)
                )
            } finally {
                if (_uiState.value.isMatchingChannel) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                }
            }
        }
    }

    /**
     * El usuario selecciona un canal desde el selector de múltiples candidatos.
     */
    fun selectCandidateToPlay(
        candidate: SportsChannelCandidate,
        onPlay: (src: String, title: String, dScheme: String?, dLicense: String?, matchChannels: List<IptvItem>, matchCategoryName: String?, headers: Map<String, String>) -> Unit,
        onNotice: (String) -> Unit
    ) {
        if (matchJob?.isActive == true || _uiState.value.isMatchingChannel) {
            return
        }

        val currentPlaylistId = channelsProvider.getActivePlaylistId()
        val activeSelection = _uiState.value.channelSelectionState

        // Invalidación estricta de playlist si cambió entretanto
        if (activeSelection != null && activeSelection.playlistId != currentPlaylistId) {
            dismissChannelSelection()
            onNotice(Translator.translate("sports_playlist_changed", currentLang()))
            return
        }

        val allCandidates = activeSelection?.candidates ?: listOf(candidate)

        if (isChannelParentalLocked(candidate.channel)) {
            ParentalControl.logParental(
                enabled = true,
                channel = candidate.channel.name,
                restricted = true,
                pinRequired = true,
                pinSuccess = false
            )
            val pendingEvent = activeSelection?.event ?: SportsEvent(
                id = "manual_${candidate.channel.name}",
                sport = _uiState.value.selectedSport.id,
                league = "",
                homeTeam = candidate.channel.name,
                awayTeam = "",
                startTime = 0L,
                status = SportsEventStatus.LIVE
            )
            _uiState.update {
                it.copy(
                    channelSelectionState = null,
                    pendingParentalUnlock = PendingParentalUnlock(
                        event = pendingEvent,
                        candidate = candidate,
                        allCandidates = allCandidates,
                        playlistId = currentPlaylistId
                    )
                )
            }
            return
        }

        val matchChannels = filterAllowedMatchChannels(allCandidates.map { it.channel })
        val matchTitle = activeSelection?.event?.let { "${it.homeTeam} vs ${it.awayTeam}" } ?: candidate.channel.name

        dismissChannelSelection()
        _uiState.update { it.copy(isMatchingChannel = true) }
        matchJob = viewModelScope.launch {
            try {
                val resolution = withContext(Dispatchers.IO) {
                    resolvePlayableChannelResult(candidate.channel)
                }
                _uiState.update { it.copy(isMatchingChannel = false) }
                if (channelsProvider.getActivePlaylistId() != currentPlaylistId) {
                    onNotice(Translator.translate("sports_playlist_changed", currentLang()))
                    return@launch
                }
                when (resolution) {
                    is StreamResolutionResult.Success -> {
                        _lastPlayedChannel.value = candidate.channel
                        onPlay(
                            resolution.url,
                            candidate.channel.name,
                            candidate.channel.drmScheme,
                            candidate.channel.drmLicenseUrl,
                            matchChannels,
                            matchTitle,
                            resolution.headers
                        )
                    }
                    is StreamResolutionResult.Failure -> {
                        onNotice(resolution.message)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                onNotice(buildStreamResolutionErrorMessage(candidate.channel.name, e))
            } finally {
                if (_uiState.value.isMatchingChannel) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                }
            }
        }
    }

    private fun currentPlayerSettings(): PlayerSettings {
        val rawSettings = sessionRepo.currentProfile.value?.settingsJson
        return if (!rawSettings.isNullOrBlank()) {
            JsonUtils.settingsFromJson(rawSettings)
        } else {
            sessionRepo.settings.value
        }
    }

    private fun currentLang(): String = currentPlayerSettings().lang

    fun getExpectedParentalPin(): String = currentPlayerSettings().parentalPin

    fun isParentalUnlockedForCurrentProfile(): Boolean {
        val profileId = sessionRepo.currentProfile.value?.id
        return _uiState.value.isParentalUnlocked && unlockedProfileId == profileId
    }

    fun isChannelParentalLocked(channel: IptvItem): Boolean {
        val settings = currentPlayerSettings()
        return settings.parentalEnabled &&
            !isParentalUnlockedForCurrentProfile() &&
            ParentalControl.isChannelRestricted(channel, settings)
    }

    internal fun filterAllowedMatchChannels(channels: List<IptvItem>): List<IptvItem> {
        val settings = currentPlayerSettings()
        if (!settings.parentalEnabled || isParentalUnlockedForCurrentProfile()) {
            return channels
        }
        return channels.filter { !ParentalControl.isChannelRestricted(it, settings) }
    }

    fun dismissParentalPrompt() {
        _uiState.update { it.copy(pendingParentalUnlock = null) }
    }

    fun confirmParentalUnlockAndPlay(
        onPlay: (src: String, title: String, dScheme: String?, dLicense: String?, matchChannels: List<IptvItem>, matchCategoryName: String?, headers: Map<String, String>) -> Unit,
        onNotice: (String) -> Unit
    ) {
        val pending = _uiState.value.pendingParentalUnlock ?: return
        if (matchJob?.isActive == true || _uiState.value.isMatchingChannel) {
            return
        }

        unlockedProfileId = sessionRepo.currentProfile.value?.id
        _uiState.update {
            it.copy(
                isParentalUnlocked = true,
                pendingParentalUnlock = null
            )
        }

        val currentPlaylistId = channelsProvider.getActivePlaylistId()
        if (pending.playlistId != currentPlaylistId) {
            onNotice(Translator.translate("sports_playlist_changed", currentLang()))
            return
        }

        val matchChannels = pending.allCandidates.map { it.channel }.ifEmpty { listOf(pending.candidate.channel) }
        val matchTitle = if (pending.event.awayTeam.isNotBlank()) {
            "${pending.event.homeTeam} vs ${pending.event.awayTeam}"
        } else {
            pending.candidate.channel.name
        }

        _uiState.update { it.copy(isMatchingChannel = true) }
        matchJob = viewModelScope.launch {
            try {
                val resolution = withContext(Dispatchers.IO) {
                    resolvePlayableChannelResult(pending.candidate.channel)
                }
                _uiState.update { it.copy(isMatchingChannel = false) }
                if (channelsProvider.getActivePlaylistId() != currentPlaylistId) {
                    onNotice(Translator.translate("sports_playlist_changed", currentLang()))
                    return@launch
                }
                when (resolution) {
                    is StreamResolutionResult.Success -> {
                        _lastPlayedChannel.value = pending.candidate.channel
                        onPlay(
                            resolution.url,
                            pending.candidate.channel.name,
                            pending.candidate.channel.drmScheme,
                            pending.candidate.channel.drmLicenseUrl,
                            matchChannels,
                            matchTitle,
                            resolution.headers
                        )
                    }
                    is StreamResolutionResult.Failure -> {
                        onNotice(resolution.message)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                onNotice(buildStreamResolutionErrorMessage(pending.candidate.channel.name, e))
            } finally {
                if (_uiState.value.isMatchingChannel) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                }
            }
        }
    }

    fun dismissChannelSelection() {
        _uiState.update { it.copy(channelSelectionState = null) }
    }

    fun startLivePolling() {
        if (livePollingJob?.isActive == true) return

        livePollingJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000L) // 60 segundos de intervalo seguro para marcadores en vivo
                val currentSport = _uiState.value.selectedSport
                try {
                    val updatedLive = withContext(Dispatchers.IO) {
                        repository.getLiveEvents(currentSport, forceRefresh = true)
                    }.filterCleanEvents()

                    val currentTotal = updatedLive.size +
                        _uiState.value.todayEvents.size +
                        _uiState.value.tomorrowEvents.size +
                        _uiState.value.weekEvents.size
                    loadedSportCounts[currentSport] = currentTotal
                    val updatedCategoryCounts = buildCategoryCounts().toMutableMap().apply {
                        put(currentSport, currentTotal)
                    }

                    _uiState.update {
                        it.copy(
                            liveEvents = updatedLive,
                            categoryEventCounts = updatedCategoryCounts
                        )
                    }
                } catch (_: Exception) {
                    // Si falla el polling periódico de 60s, se conservan los eventos actuales de forma segura
                }
            }
        }
    }

    fun stopLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = null
    }

    /**
     * Garantía estricta de que eventos FINISHED o CANCELLED nunca lleguen al SportsUiState final.
     */
    private fun List<SportsEvent>.filterCleanEvents(): List<SportsEvent> {
        return this.filter {
            it.status != SportsEventStatus.FINISHED && it.status != SportsEventStatus.CANCELLED
        }
    }


    override fun onCleared() {
        super.onCleared()
        stopLivePolling()
        loadJob?.cancel()
        preloadJob?.cancel()
        matchJob?.cancel()
    }
}
