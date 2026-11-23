package com.example.interfaz.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.motor.db.IptvItem
import com.example.motor.sports.broadcast.SportsTvBroadcastProvider
import com.example.motor.sports.broadcast.TheSportsDbBroadcastResolver
import com.example.motor.sports.matcher.*
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsFilter
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.remote.ApiKeyValidationResult
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
 * Modos de la interfaz de configuración de API Key.
 */
enum class ApiKeyDialogMode {
    FORM,           // Introducir / cambiar clave
    INFO,           // Ver clave enmascarada y opciones [CAMBIAR] / [ELIMINAR]
    CONFIRM_DELETE  // Confirmar eliminación
}

/**
 * Estado del selector de canales cuando existen múltiples candidatos que superan el umbral SELECTION.
 */
data class ChannelSelectionState(
    val event: SportsEvent,
    val candidates: List<SportsChannelCandidate>,
    val playlistId: Long?
)

data class SportsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isApiKeyConfigured: Boolean = false,
    val hasUserApiKey: Boolean = false,
    val maskedApiKey: String? = null,
    val showApiKeyConfigDialog: Boolean = false,
    val apiKeyDialogMode: ApiKeyDialogMode = ApiKeyDialogMode.FORM,
    val isCheckingApiKey: Boolean = false,
    val apiKeyFeedbackMessage: String? = null,
    val apiKeyFeedbackSuccess: Boolean = false,
    val selectedSport: SportsSport = SportsSport.FOOTBALL,
    val selectedFilter: SportsFilter = SportsFilter.ALL,
    val liveEvents: List<SportsEvent> = emptyList(),
    val todayEvents: List<SportsEvent> = emptyList(),
    val tomorrowEvents: List<SportsEvent> = emptyList(),
    val weekEvents: List<SportsEvent> = emptyList(),
    val categoryEventCounts: Map<SportsSport, Int> = emptyMap(),
    val errorMessage: String? = null,
    val channelSelectionState: ChannelSelectionState? = null,
    val isMatchingChannel: Boolean = false
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
    }

    private val loadedSportCounts = ConcurrentHashMap<SportsSport, Int>()

    private fun buildCategoryCounts(): Map<SportsSport, Int> {
        val repoCounts = repository.getAllCachedEventCounts()
        return SportsSport.entries.associateWith { sport ->
            maxOf(repoCounts[sport] ?: 0, loadedSportCounts[sport] ?: 0)
        }
    }

    private val _uiState = MutableStateFlow(
        SportsUiState(
            isApiKeyConfigured = true,
            hasUserApiKey = false,
            maskedApiKey = null,
            categoryEventCounts = buildCategoryCounts(),
            errorMessage = null
        )
    )
    val uiState: StateFlow<SportsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var livePollingJob: Job? = null
    private var preloadJob: Job? = null
    @Volatile
    private var hasPreloadedCategories = false

    fun openApiKeyConfig() {
        val hasKey = repository.hasUserApiKey()
        _uiState.update {
            it.copy(
                showApiKeyConfigDialog = true,
                apiKeyDialogMode = if (hasKey) ApiKeyDialogMode.INFO else ApiKeyDialogMode.FORM,
                apiKeyFeedbackMessage = null,
                isCheckingApiKey = false
            )
        }
    }

    fun openApiKeyForm() {
        _uiState.update {
            it.copy(
                apiKeyDialogMode = ApiKeyDialogMode.FORM,
                apiKeyFeedbackMessage = null,
                isCheckingApiKey = false
            )
        }
    }

    fun promptDeleteApiKey() {
        _uiState.update {
            it.copy(
                apiKeyDialogMode = ApiKeyDialogMode.CONFIRM_DELETE,
                apiKeyFeedbackMessage = null,
                isCheckingApiKey = false
            )
        }
    }

    fun dismissApiKeyConfig() {
        _uiState.update {
            it.copy(
                showApiKeyConfigDialog = false,
                apiKeyFeedbackMessage = null,
                isCheckingApiKey = false
            )
        }
    }

    fun deleteUserApiKey() {
        repository.removeUserApiKey()
        loadedSportCounts.clear()
        val isConfigured = repository.isApiKeyConfigured()
        _uiState.update {
            it.copy(
                hasUserApiKey = false,
                maskedApiKey = null,
                isApiKeyConfigured = isConfigured,
                showApiKeyConfigDialog = false,
                apiKeyFeedbackMessage = null,
                isCheckingApiKey = false,
                liveEvents = emptyList(),
                todayEvents = emptyList(),
                tomorrowEvents = emptyList(),
                weekEvents = emptyList(),
                categoryEventCounts = emptyMap(),
                errorMessage = if (!isConfigured) "Para mostrar eventos deportivos necesitas una API key de API-Sports." else null
            )
        }
        hasPreloadedCategories = false
        loadData(forceRefresh = true)
    }

    fun saveAndCheckApiKey(apiKey: String) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            _uiState.update {
                it.copy(
                    apiKeyFeedbackMessage = "La API key no es válida.",
                    apiKeyFeedbackSuccess = false,
                    isCheckingApiKey = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingApiKey = true,
                    apiKeyFeedbackMessage = null
                )
            }

            val result = repository.saveAndValidateUserApiKey(cleanKey)
            when (result) {
                is ApiKeyValidationResult.Valid -> {
                    val masked = repository.getMaskedUserApiKey()
                    _uiState.update {
                        it.copy(
                            isCheckingApiKey = false,
                            apiKeyFeedbackMessage = "✓ API configurada correctamente",
                            apiKeyFeedbackSuccess = true,
                            hasUserApiKey = true,
                            maskedApiKey = masked,
                            isApiKeyConfigured = true,
                            errorMessage = null
                        )
                    }
                    delay(800)
                    _uiState.update { it.copy(showApiKeyConfigDialog = false) }
                    loadData(forceRefresh = true)
                }
                is ApiKeyValidationResult.InvalidKey -> {
                    _uiState.update {
                        it.copy(
                            isCheckingApiKey = false,
                            apiKeyFeedbackMessage = "La API key no es válida.",
                            apiKeyFeedbackSuccess = false
                        )
                    }
                }
                is ApiKeyValidationResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isCheckingApiKey = false,
                            apiKeyFeedbackMessage = "No se pudo comprobar la API key. Comprueba tu conexión.",
                            apiKeyFeedbackSuccess = false
                        )
                    }
                }
            }
        }
    }

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
        if (!repository.isApiKeyConfigured() || hasPreloadedCategories) return
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
                SportsSport.GOLF
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
                    errorMessage = null,
                    isApiKeyConfigured = true
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
                        errorMessage = "No se pudieron actualizar los eventos."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    val hasExistingData = it.liveEvents.isNotEmpty() || it.todayEvents.isNotEmpty() || it.tomorrowEvents.isNotEmpty() || it.weekEvents.isNotEmpty()
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (hasExistingData) {
                            "No se pudieron actualizar los eventos."
                        } else {
                            "No se pudieron cargar los eventos deportivos."
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
     * - NO_MATCH: Ninguna coincidencia suficiente -> Muestra "Canal no encontrado en la lista activa."
     */
    private suspend fun resolvePlayableChannel(channel: IptvItem): Pair<String, Map<String, String>> {
        val pl = sessionRepo.activePlaylist.value
        return if (pl != null) {
            try {
                iptvRepo.resolveStreamUrl(pl, channel, "live")
            } catch (e: Exception) {
                Log.w("SportsViewModel", "Error resolving stream for ${channel.name}: ${e.message}")
                Pair(channel.url ?: "", iptvRepo.getStreamHeaders(pl))
            }
        } else {
            Pair(channel.url ?: "", iptvRepo.getStreamHeaders(null))
        }
    }

    fun matchAndPlayEvent(
        event: SportsEvent,
        onPlay: (src: String, title: String, dScheme: String?, dLicense: String?, matchChannels: List<IptvItem>, matchCategoryName: String?, headers: Map<String, String>) -> Unit,
        onNotice: (String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMatchingChannel = true) }
            try {
                val currentPlaylistId = channelsProvider.getActivePlaylistId()
                val channels = withContext(Dispatchers.IO) {
                    channelsProvider.getActiveTvChannels()
                }

                if (channels.isEmpty()) {
                    _uiState.update { it.copy(isMatchingChannel = false) }
                    onNotice("No hay canales disponibles en la lista activa.")
                    return@launch
                }

                // Consultar TheSportsDB exclusivamente bajo demanda para el partido seleccionado
                val broadcastInfo = try {
                    withContext(Dispatchers.IO) {
                        broadcastResolver.resolveBroadcastInfo(event)
                    }
                } catch (e: Exception) {
                    null
                }

                val enrichedEvent = if (broadcastInfo != null && broadcastInfo.channels.isNotEmpty()) {
                    event.withBroadcasters(broadcastInfo.channels)
                } else {
                    event
                }

                val matchResult = withContext(Dispatchers.Default) {
                    matcher.matchChannel(enrichedEvent, channels, currentPlaylistId)
                }

                when (matchResult.decision) {
                    MatchDecision.AUTO_PLAY -> {
                        val candidate = matchResult.topMatch
                        if (candidate != null) {
                            val (resolvedUrl, resolvedHeaders) = withContext(Dispatchers.IO) {
                                resolvePlayableChannel(candidate.channel)
                            }
                            _uiState.update { it.copy(isMatchingChannel = false) }
                            if (resolvedUrl.isNotBlank()) {
                                _uiState.update { it.copy(channelSelectionState = null) }
                                val matchChannels = matchResult.candidates.map { it.channel }.ifEmpty { listOf(candidate.channel) }
                                val matchTitle = "${event.homeTeam} vs ${event.awayTeam}"
                                onPlay(
                                    resolvedUrl,
                                    candidate.channel.name,
                                    candidate.channel.drmScheme,
                                    candidate.channel.drmLicenseUrl,
                                    matchChannels,
                                    matchTitle,
                                    resolvedHeaders
                                )
                            } else {
                                onNotice("Canal no encontrado en la lista activa.")
                            }
                        } else {
                            _uiState.update { it.copy(isMatchingChannel = false) }
                            onNotice("Canal no encontrado en la lista activa.")
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
                        onNotice("Canal no encontrado en la lista activa.")
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                onNotice("Canal no encontrado en la lista activa.")
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
        val currentPlaylistId = channelsProvider.getActivePlaylistId()
        val activeSelection = _uiState.value.channelSelectionState

        // Invalidación estricta de playlist si cambió entretanto
        if (activeSelection != null && activeSelection.playlistId != currentPlaylistId) {
            dismissChannelSelection()
            onNotice("La lista activa ha cambiado. Vuelve a pulsar Ver.")
            return
        }

        val allCandidates = activeSelection?.candidates ?: listOf(candidate)
        val matchChannels = allCandidates.map { it.channel }
        val matchTitle = activeSelection?.event?.let { "${it.homeTeam} vs ${it.awayTeam}" } ?: candidate.channel.name

        dismissChannelSelection()
        viewModelScope.launch {
            _uiState.update { it.copy(isMatchingChannel = true) }
            try {
                val (resolvedUrl, resolvedHeaders) = withContext(Dispatchers.IO) {
                    resolvePlayableChannel(candidate.channel)
                }
                _uiState.update { it.copy(isMatchingChannel = false) }
                if (resolvedUrl.isNotBlank()) {
                    onPlay(
                        resolvedUrl,
                        candidate.channel.name,
                        candidate.channel.drmScheme,
                        candidate.channel.drmLicenseUrl,
                        matchChannels,
                        matchTitle,
                        resolvedHeaders
                    )
                } else {
                    onNotice("Canal no reproducible.")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isMatchingChannel = false) }
                onNotice("Canal no reproducible.")
            }
        }
    }

    fun dismissChannelSelection() {
        _uiState.update { it.copy(channelSelectionState = null) }
    }

    fun startLivePolling() {
        if (!repository.isApiKeyConfigured()) return
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
    }
}
