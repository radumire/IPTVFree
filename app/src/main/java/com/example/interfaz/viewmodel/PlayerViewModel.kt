package com.example.interfaz.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.PlayerView
import com.example.interfaz.state.PlayerState
import com.example.motor.db.AppDatabase
import com.example.motor.db.IptvItem
import com.example.motor.db.PlaybackHistory
import com.example.motor.player.PlayerRepository
import com.example.motor.player.TrackOption
import com.example.motor.subtitles.ExternalSubtitle
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.SubtitleRepository
import com.example.motor.repository.TmdbRepository
import com.example.motor.subtitles.SubtitleSearchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()
    private val subtitleRepo = SubtitleRepository.getInstance().apply {
        init(application.cacheDir)
    }
    private val historyDao = AppDatabase.getDatabase(application).playbackHistoryDao()

    // ─── EXISTING UI STATE ────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(PlayerState())
    val uiState: StateFlow<PlayerState> = _uiState.asStateFlow()

    // ─── PLAYBACK STATES ──────────────────────────────────────────────────────────
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMsg = MutableStateFlow<String?>(null)
    val errorMsg: StateFlow<String?> = _errorMsg.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // ─── TRACKS & SUBTITLES STATES ────────────────────────────────────────────────
    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

    private val _externalSubtitles = MutableStateFlow<List<ExternalSubtitle>>(emptyList())
    val externalSubtitles: StateFlow<List<ExternalSubtitle>> = _externalSubtitles.asStateFlow()

    private val _isSearchingSubtitles = MutableStateFlow(false)
    val isSearchingSubtitles: StateFlow<Boolean> = _isSearchingSubtitles.asStateFlow()

    private val _subtitleSearchError = MutableStateFlow<String?>(null)
    val subtitleSearchError: StateFlow<String?> = _subtitleSearchError.asStateFlow()

    private val _isTranslatingSubtitle = MutableStateFlow(false)
    val isTranslatingSubtitle: StateFlow<Boolean> = _isTranslatingSubtitle.asStateFlow()

    private val _subtitleNotification = MutableStateFlow<String?>(null)
    val subtitleNotification: StateFlow<String?> = _subtitleNotification.asStateFlow()

    private val _downloadingSubtitleId = MutableStateFlow<String?>(null)
    val downloadingSubtitleId: StateFlow<String?> = _downloadingSubtitleId.asStateFlow()

    private val _selectedExternalSubtitle = MutableStateFlow<ExternalSubtitle?>(null)
    val selectedExternalSubtitle: StateFlow<ExternalSubtitle?> = _selectedExternalSubtitle.asStateFlow()

    private val _subtitleOffsetMs = MutableStateFlow(0L)
    val subtitleOffsetMs: StateFlow<Long> = _subtitleOffsetMs.asStateFlow()

    private val _activeSubtitleCues = MutableStateFlow<List<com.example.motor.subtitles.SubtitleSyncUtils.SubtitleCue>>(emptyList())
    val activeSubtitleCues: StateFlow<List<com.example.motor.subtitles.SubtitleSyncUtils.SubtitleCue>> = _activeSubtitleCues.asStateFlow()

    private val _isSubtitleVisible = MutableStateFlow(true)
    val isSubtitleVisible: StateFlow<Boolean> = _isSubtitleVisible.asStateFlow()

    // Listener reference to avoid duplicate attachments on shared player
    private var attachedPlayerListener: Player.Listener? = null

    init {
        setupPlayerListener()
    }

    override fun onCleared() {
        super.onCleared()
        attachedPlayerListener?.let {
            PlayerRepository.removeListener(it)
            attachedPlayerListener = null
        }
    }

    fun attachPlayerView(playerView: PlayerView) {
        PlayerRepository.attachPlayerView(playerView)
    }

    fun detachPlayerView(playerView: PlayerView) {
        PlayerRepository.detachPlayerView(playerView)
    }

    fun addListener(listener: Player.Listener) {
        PlayerRepository.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        PlayerRepository.removeListener(listener)
    }

    fun getCurrentPositionMs(): Long = PlayerRepository.getCurrentPosition()

    fun getDurationMs(): Long = PlayerRepository.getDuration()

    fun getPlaybackState(): Int = PlayerRepository.getPlaybackState()

    fun isPlayerPlaying(): Boolean = PlayerRepository.isPlaying()

    fun isPlayWhenReady(): Boolean = PlayerRepository.isPlayWhenReady()

    fun seekBy(offsetMs: Long) {
        try {
            PlayerRepository.seekBy(offsetMs)
            val posSec = PlayerRepository.getCurrentPosition() / 1000L
            _currentPosition.value = posSec
            _uiState.update { it.copy(currentPosition = posSec) }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error in seekBy", e)
        }
    }

    // ─── EXISTING COMPATIBILITY METHODS ───────────────────────────────────────────

    fun getActiveStreamHeaders(): Map<String, String> =
        iptvRepo.getStreamHeaders()

    fun playMedia(context: Context, src: String, headers: Map<String, String> = emptyMap()) {
        _uiState.update {
            it.copy(
                streamUrl = src,
                headers = headers,
                error = null
            )
        }
        PlayerRepository.playMedia(context, src, headers)
    }

    fun playMedia(
        context: Context,
        src: String,
        headers: Map<String, String> = emptyMap(),
        drmScheme: String? = null,
        drmLicenseUrl: String? = null,
        initialPosMs: Long = 0L,
        subtitlesEnabled: Boolean = false,
        isLive: Boolean = false
    ) {
        clearActiveSubtitle()
        resetSubtitleOffset()
        _uiState.update {
            it.copy(
                streamUrl = src,
                headers = headers,
                error = null
            )
        }
        PlayerRepository.playMedia(
            context = context,
            src = src,
            headers = headers,
            drmScheme = drmScheme,
            drmLicenseUrl = drmLicenseUrl,
            initialPosMs = initialPosMs,
            subtitlesEnabled = subtitlesEnabled,
            isLive = isLive
        )
    }

    fun setSubtitleOffset(offsetMs: Long) {
        _subtitleOffsetMs.value = offsetMs
    }

    fun adjustSubtitleOffset(deltaMs: Long) {
        val newOffset = (_subtitleOffsetMs.value + deltaMs).coerceIn(-10000L, 10000L)
        setSubtitleOffset(newOffset)
    }

    fun resetSubtitleOffset() {
        setSubtitleOffset(0L)
    }

    fun pause() {
        PlayerRepository.pause()
        _isPlaying.value = false
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        PlayerRepository.resume()
        _isPlaying.value = true
        _uiState.update { it.copy(isPlaying = true) }
    }

    fun stop() {
        PlayerRepository.stop()
        _isPlaying.value = false
        _isLoading.value = false
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun prepareStream(item: IptvItem, mediaType: String = "live") {
        clearExternalSubtitles()
        val pl = sessionRepo.activePlaylist.value ?: return
        viewModelScope.launch {
            try {
                val (resolvedUrl, headers) = iptvRepo.resolveStreamUrl(pl, item, mediaType)
                _uiState.update { 
                    it.copy(
                        title = item.name,
                        streamUrl = resolvedUrl,
                        headers = headers,
                        mediaType = mediaType,
                        error = null
                    ) 
                }
            } catch (e: Exception) {
                val err = e.message ?: "Error resolviendo enlace de reproducción"
                _errorMsg.value = err
                _uiState.update { it.copy(error = err) }
            }
        }
    }

    fun recordProgress(positionMs: Long, durationMs: Long) {
        val state = _uiState.value
        if (state.streamUrl.isBlank() || state.title.isBlank()) return
        val posSec = positionMs / 1000L
        val durSec = durationMs / 1000L
        val activePlId = sessionRepo.activePlaylist.value?.id ?: 0L
        viewModelScope.launch {
            iptvRepo.recordPlayback(
                url = state.streamUrl,
                title = state.title,
                position = posSec,
                duration = durSec,
                type = state.mediaType,
                playlistId = activePlId
            )
        }
    }

    // ─── NEW PLAYBACK CONTROL METHODS ─────────────────────────────────────────────

    fun seekTo(positionMs: Long) {
        try {
            PlayerRepository.seekTo(positionMs)
            val posSec = positionMs / 1000L
            _currentPosition.value = posSec
            _uiState.update { it.copy(currentPosition = posSec) }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error in seekTo", e)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        try {
            PlayerRepository.setPlaybackSpeed(speed)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error in setPlaybackSpeed", e)
        }
    }

    fun updateSettings(
        skipSilenceSetting: Boolean = false,
        langSetting: String = "es",
        subtitlesEnabled: Boolean = false
    ) {
        PlayerRepository.updateSettings(
            skipSilenceSetting = skipSilenceSetting,
            langSetting = langSetting,
            subtitlesEnabled = subtitlesEnabled
        )
    }

    fun retryPlayback(context: Context? = null) {
        try {
            _isLoading.value = true
            _errorMsg.value = null
            PlayerRepository.retryPlayback(context ?: getApplication())
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error in retryPlayback", e)
            _errorMsg.value = "Error al reintentar reproducción: ${e.message}"
        }
    }

    fun clearError() {
        _errorMsg.value = null
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: String?) {
        _errorMsg.value = message
        _uiState.update { it.copy(error = message) }
    }

    fun updatePlaybackPosition(positionSec: Long, durationSec: Long? = null) {
        _currentPosition.value = positionSec
        _uiState.update {
            it.copy(
                currentPosition = positionSec,
                duration = durationSec ?: it.duration
            )
        }
    }

    fun updatePlaybackState(isPlaying: Boolean, isLoading: Boolean) {
        _isPlaying.value = isPlaying
        _isLoading.value = isLoading
        _uiState.update { it.copy(isPlaying = isPlaying) }
    }

    // ─── TRACK SELECTION & MANAGEMENT ─────────────────────────────────────────────

    fun getTrackOptions(trackType: Int, player: Player? = null): List<TrackOption> {
        return try {
            PlayerRepository.getTrackOptions(trackType)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error getting track options", e)
            emptyList()
        }
    }

    fun selectTrack(track: TrackOption, player: Player? = null) {
        try {
            PlayerRepository.selectTrack(track)
            refreshTracks()
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error selecting track", e)
        }
    }

    fun refreshTracks(player: Player? = null) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                refreshTracks(player)
            }
            return
        }
        try {
            val audio = PlayerRepository.getTrackOptions(C.TRACK_TYPE_AUDIO)
            val subs = PlayerRepository.getTrackOptions(C.TRACK_TYPE_TEXT)
            _audioTracks.value = audio
            _subtitleTracks.value = subs
            _uiState.update {
                it.copy(
                    audioTracks = audio.map { track -> track.name },
                    subtitleTracks = subs.map { track -> track.name }
                )
            }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error refreshing tracks", e)
        }
    }

    fun selectAudioTrack(track: TrackOption) {
        try {
            PlayerRepository.selectTrack(track)
            refreshTracks()
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error selecting audio track", e)
        }
    }

    fun selectSubtitleTrack(track: TrackOption) {
        try {
            cancelPendingExternalSubtitleWork()
            _selectedExternalSubtitle.value = null
            _activeSubtitleCues.value = emptyList()
            if (track.isNone || track.groupIndex >= 0) {
                PlayerRepository.selectTrack(track)
                refreshTracks()
            } else {
                val subs = PlayerRepository.getTrackOptions(C.TRACK_TYPE_TEXT)
                val match = subs.find {
                    !it.isNone && (it.id == track.id || it.formatId == track.id || it.externalSubtitleId == track.id)
                } ?: subs.find {
                    !it.isNone && it.name.equals(track.name, ignoreCase = true)
                }
                if (match != null && match.groupIndex >= 0) {
                    PlayerRepository.selectTrack(match)
                    refreshTracks()
                } else {
                    Log.w("SUBTITLE_TRACKS", "SUBTITLE_TRACK_NOT_FOUND id=${track.id}")
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error selecting subtitle track", e)
        }
    }

    // ─── SUBTITLES SEARCH & TRANSLATION ───────────────────────────────────────────

    private var searchSubtitlesJob: kotlinx.coroutines.Job? = null
    private val currentSearchRequestId = java.util.concurrent.atomic.AtomicLong(0)
    private var selectSubtitleJob: kotlinx.coroutines.Job? = null
    private var translateSubtitleJob: kotlinx.coroutines.Job? = null
    private val currentExternalRequestId = java.util.concurrent.atomic.AtomicLong(0)

    private fun cancelPendingExternalSubtitleWork() {
        selectSubtitleJob?.cancel()
        selectSubtitleJob = null
        translateSubtitleJob?.cancel()
        translateSubtitleJob = null
        currentExternalRequestId.incrementAndGet()
        _downloadingSubtitleId.value = null
        _isTranslatingSubtitle.value = false
    }
    private var lastAutoSearchedKey: String? = null

    fun autoSearchSubtitlesIfNeeded(
        title: String,
        isSeries: Boolean = false,
        season: Int? = null,
        episode: Int? = null,
        videoUrl: String? = null,
        preferredLanguage: String = "es",
        headers: Map<String, String> = emptyMap(),
        mediaType: String? = null
    ) {
        if (title.isBlank()) return
        if (mediaType.equals("live", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)) {
            Log.i("SUBTITLE_SEARCH", "autoSearchSubtitlesIfNeeded skipped: Live TV is explicitly excluded.")
            return
        }
        val key = if (isSeries) "series_${title.trim().lowercase(java.util.Locale.ROOT)}_${season ?: 1}_${episode ?: 1}" else "movie_${title.trim().lowercase(java.util.Locale.ROOT)}"
        if (key == lastAutoSearchedKey && (_externalSubtitles.value.isNotEmpty() || _isSearchingSubtitles.value)) {
            return
        }
        lastAutoSearchedKey = key
        searchSubtitles(
            title = title,
            isSeries = isSeries,
            season = season,
            episode = episode,
            videoUrl = videoUrl,
            preferredLanguage = preferredLanguage,
            headers = headers,
            isAuto = true,
            mediaType = mediaType
        )
    }

    fun searchSubtitles(
        title: String,
        isSeries: Boolean = false,
        season: Int? = null,
        episode: Int? = null,
        videoUrl: String? = null,
        preferredLanguage: String = "es",
        headers: Map<String, String> = emptyMap(),
        isAuto: Boolean = false,
        mediaType: String? = null
    ) {
        if (title.isBlank()) return
        val resolvedMediaType = mediaType ?: if (isSeries) "series" else "movie"
        if (resolvedMediaType.equals("live", ignoreCase = true) || resolvedMediaType.equals("tv", ignoreCase = true)) {
            Log.i("SUBTITLE_SEARCH", "searchSubtitles skipped: Live TV content is explicitly excluded.")
            _externalSubtitles.value = emptyList()
            return
        }
        if (isAuto) {
            Log.i("SUBTITLE_AUTO_SEARCH", "title=\"$title\", mediaType=$resolvedMediaType, isSeries=$isSeries, season=$season, episode=$episode, videoUrl=$videoUrl")
        } else {
            Log.i("SUBTITLE_MANUAL_SEARCH", "query=\"$title\", mediaType=$resolvedMediaType, isSeries=$isSeries, season=$season, episode=$episode, videoUrl=$videoUrl")
        }
        searchSubtitlesJob?.cancel()
        val requestId = currentSearchRequestId.incrementAndGet()
        _subtitleSearchError.value = null

        searchSubtitlesJob = viewModelScope.launch(Dispatchers.IO) {
            _isSearchingSubtitles.value = true
            try {
                val request = SubtitleSearchRequest(
                    videoUrl = videoUrl,
                    title = title,
                    mediaType = resolvedMediaType,
                    isSeries = isSeries,
                    season = season,
                    episode = episode,
                    preferredLanguage = preferredLanguage,
                    headers = headers
                )
                val subs = subtitleRepo.searchSubtitles(request)
                if (requestId != currentSearchRequestId.get()) {
                    Log.d("PlayerViewModel", "Dropping stale subtitle results for reqId=$requestId")
                    return@launch
                }
                if (subs.isEmpty()) {
                    Log.i("SUBTITLE_STATE_UPDATE", "count=0")
                    _externalSubtitles.value = emptyList()
                    _subtitleSearchError.value = "No se encontraron subtítulos adecuados"
                } else {
                    _subtitleSearchError.value = null
                    Log.i("SUBTITLE_STATE_UPDATE", "count=${subs.size}")
                    _externalSubtitles.value = subs
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (requestId == currentSearchRequestId.get()) {
                    Log.e("PlayerViewModel", "Error searching subtitles", e)
                    _subtitleSearchError.value = "Error al buscar subtítulos: ${e.message}"
                }
            } finally {
                if (requestId == currentSearchRequestId.get()) {
                    _isSearchingSubtitles.value = false
                }
            }
        }
    }

    fun addExternalSubtitle(subtitle: ExternalSubtitle) {
        _externalSubtitles.update { current ->
            current.filter { it.id != subtitle.id && it.label != subtitle.label } + subtitle
        }
    }

    fun selectExternalSubtitle(sub: ExternalSubtitle) {
        cancelPendingExternalSubtitleWork()
        val requestId = currentExternalRequestId.get()
        selectSubtitleJob = viewModelScope.launch(Dispatchers.IO) {
            _downloadingSubtitleId.value = sub.id
            _subtitleNotification.value = "Descargando subtítulo... ⏳"
            try {
                val isLocal = sub.url.startsWith("/") || sub.url.startsWith("file://")
                val resolvedSub = if (isLocal) {
                    sub
                } else {
                    com.example.motor.subtitles.SubtitleResolver.resolveSingleSubtitle(sub) ?: sub
                }

                if (requestId != currentExternalRequestId.get()) return@launch

                val hasLocalFile = resolvedSub.url.startsWith("/") || resolvedSub.url.startsWith("file://")
                if (!hasLocalFile) {
                    _subtitleNotification.value = "❌ No se pudo descargar el subtítulo"
                    return@launch
                }

                val localFilePath = if (resolvedSub.url.startsWith("file://")) {
                    resolvedSub.url.removePrefix("file://")
                } else {
                    resolvedSub.url
                }
                val localFile = java.io.File(localFilePath)
                if (!localFile.exists() || localFile.length() < 10) {
                    _subtitleNotification.value = "❌ No se pudo descargar el subtítulo"
                    return@launch
                }

                val parsedCues = com.example.motor.subtitles.SubtitleSyncUtils.parseSubtitleFile(localFile)

                if (requestId != currentExternalRequestId.get()) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (requestId != currentExternalRequestId.get()) return@withContext
                    PlayerRepository.disableTextTracks()
                    if (parsedCues.isNotEmpty()) {
                        _selectedExternalSubtitle.value = resolvedSub
                        _activeSubtitleCues.value = parsedCues
                        _isSubtitleVisible.value = true
                        _externalSubtitles.update { current ->
                            current.map { if (it.id == sub.id) resolvedSub else it }
                        }
                        refreshTracks()
                        _subtitleNotification.value = "✅ Subtítulo activado: ${sub.label}"
                    } else {
                        _selectedExternalSubtitle.value = null
                        _activeSubtitleCues.value = emptyList()
                        _subtitleNotification.value = "❌ El subtítulo no contiene líneas válidas"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error downloading/selecting external subtitle", e)
                if (requestId == currentExternalRequestId.get()) {
                    withContext(Dispatchers.Main.immediate) {
                        _selectedExternalSubtitle.value = null
                        _activeSubtitleCues.value = emptyList()
                        _subtitleNotification.value = "❌ Error al descargar subtítulo"
                    }
                }
            } finally {
                if (requestId == currentExternalRequestId.get()) {
                    _downloadingSubtitleId.value = null
                }
            }
            if (requestId == currentExternalRequestId.get()) {
                launch {
                    delay(3500L)
                    if (requestId == currentExternalRequestId.get() &&
                        (_subtitleNotification.value?.startsWith("✅") == true || _subtitleNotification.value?.startsWith("❌") == true)
                    ) {
                        _subtitleNotification.value = null
                    }
                }
            }
        }
    }

    fun clearActiveSubtitle() {
        cancelPendingExternalSubtitleWork()
        _selectedExternalSubtitle.value = null
        _activeSubtitleCues.value = emptyList()
    }

    fun clearSelectedExternalSubtitle() {
        clearActiveSubtitle()
    }

    fun clearExternalSubtitles() {
        searchSubtitlesJob?.cancel()
        currentSearchRequestId.incrementAndGet()
        lastAutoSearchedKey = null
        cancelPendingExternalSubtitleWork()
        _externalSubtitles.value = emptyList()
        _subtitleTracks.value = emptyList()
        _subtitleSearchError.value = null
        _isSearchingSubtitles.value = false
        _selectedExternalSubtitle.value = null
        _activeSubtitleCues.value = emptyList()
    }

    fun translateSubtitle(sub: ExternalSubtitle, targetLang: String = "ro") {
        cancelPendingExternalSubtitleWork()
        val requestId = currentExternalRequestId.get()
        translateSubtitleJob = viewModelScope.launch(Dispatchers.IO) {
            _isTranslatingSubtitle.value = true
            _downloadingSubtitleId.value = sub.id
            val langName = when (targetLang) {
                "ro" -> "Rumano"
                "es" -> "Español"
                "en" -> "Inglés"
                else -> targetLang.uppercase()
            }
            _subtitleNotification.value = "Preparando subtítulo para traducir... ⏳"

            val autoHideJob = launch {
                delay(5000L)
                if (requestId == currentExternalRequestId.get() &&
                    (_subtitleNotification.value?.startsWith("Traduciendo") == true ||
                    _subtitleNotification.value?.startsWith("Preparando") == true)
                ) {
                    _subtitleNotification.value = null
                }
            }

            try {
                // Ensure base subtitle is downloaded to local file first
                val isLocal = sub.url.startsWith("/") || sub.url.startsWith("file://")
                val baseSub = if (isLocal) {
                    sub
                } else {
                    com.example.motor.subtitles.SubtitleResolver.resolveSingleSubtitle(sub) ?: sub
                }

                if (requestId != currentExternalRequestId.get()) return@launch

                _subtitleNotification.value = "Traduciendo subtítulos a $langName... ⏳"
                val newLabel = "$langName · Traducido"
                val subId = "${sub.id}_tr_$targetLang"

                val localPath = PlayerRepository.translateSubtitleUrl(
                    context = getApplication(),
                    subUrl = baseSub.url,
                    targetLanguage = targetLang,
                    onProgressiveReady = { partialPath ->
                        if (requestId != currentExternalRequestId.get()) return@translateSubtitleUrl
                        val partialFile = java.io.File(if (partialPath.startsWith("file://")) partialPath.removePrefix("file://") else partialPath)
                        val parsedPartial = com.example.motor.subtitles.SubtitleSyncUtils.parseSubtitleFile(partialFile)
                        if (parsedPartial.isEmpty()) return@translateSubtitleUrl
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            if (requestId != currentExternalRequestId.get()) return@launch
                            try {
                                val initialSub = ExternalSubtitle(
                                    id = subId,
                                    url = partialPath,
                                    label = newLabel,
                                    language = targetLang,
                                    source = sub.source,
                                    isTranslated = true,
                                    displayTitle = sub.displayTitle,
                                    season = sub.season,
                                    episode = sub.episode
                                )
                                PlayerRepository.disableTextTracks()
                                addExternalSubtitle(initialSub)
                                _selectedExternalSubtitle.value = initialSub
                                _activeSubtitleCues.value = parsedPartial
                                _isSubtitleVisible.value = true
                                refreshTracks()
                            } catch (e: Exception) {
                                Log.e("PlayerViewModel", "Error attaching progressive subtitle", e)
                            }
                        }
                    }
                )

                autoHideJob.cancel()
                if (requestId != currentExternalRequestId.get()) return@launch
                _isTranslatingSubtitle.value = false
                _downloadingSubtitleId.value = null

                if (localPath != null) {
                    val finalSub = ExternalSubtitle(
                        id = subId,
                        url = localPath,
                        label = newLabel,
                        language = targetLang,
                        source = sub.source,
                        isTranslated = true,
                        displayTitle = sub.displayTitle,
                        season = sub.season,
                        episode = sub.episode
                    )
                    val tFile = java.io.File(if (localPath.startsWith("file://")) localPath.removePrefix("file://") else localPath)
                    val parsedTranslated = com.example.motor.subtitles.SubtitleSyncUtils.parseSubtitleFile(tFile)
                    withContext(Dispatchers.Main.immediate) {
                        if (requestId != currentExternalRequestId.get()) return@withContext
                        PlayerRepository.disableTextTracks()
                        addExternalSubtitle(finalSub)
                        _selectedExternalSubtitle.value = finalSub
                        _activeSubtitleCues.value = parsedTranslated
                        _isSubtitleVisible.value = true
                        refreshTracks()
                        _subtitleNotification.value = "✅ Subtítulos en $langName listos"
                    }
                } else {
                    _subtitleNotification.value = "❌ No se pudieron traducir los subtítulos"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                autoHideJob.cancel()
                throw e
            } catch (e: Exception) {
                autoHideJob.cancel()
                if (requestId == currentExternalRequestId.get()) {
                    _isTranslatingSubtitle.value = false
                    _downloadingSubtitleId.value = null
                    Log.e("PlayerViewModel", "Error translating subtitle", e)
                    _subtitleNotification.value = "❌ Error al traducir subtítulos"
                }
            }
            if (requestId == currentExternalRequestId.get()) {
                delay(4000L)
                if (requestId == currentExternalRequestId.get()) {
                    _subtitleNotification.value = null
                }
            }
        }
    }

    fun clearSubtitleNotification() {
        _subtitleNotification.value = null
    }

    // ─── PLAYER LISTENER MANAGEMENT ───────────────────────────────────────────────

    fun attachPlayer() {
        setupPlayerListener()
    }

    fun detachPlayer() {
        attachedPlayerListener?.let {
            PlayerRepository.removeListener(it)
            attachedPlayerListener = null
        }
    }

    private fun setupPlayerListener() {
        if (attachedPlayerListener != null) return
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                _isLoading.value = false
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                _uiState.update { it.copy(isPlaying = playing) }
                if (playing) {
                    _isLoading.value = false
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _isLoading.value = true
                    }
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        val dur = PlayerRepository.getDuration()
                        if (dur > 0) {
                            val durSec = dur / 1000L
                            _duration.value = durSec
                            _uiState.update { it.copy(duration = durSec) }
                        }
                        refreshTracks()
                    }
                    Player.STATE_ENDED -> {
                        _isPlaying.value = false
                        _uiState.update { it.copy(isPlaying = false) }
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isLoading.value = false
                _errorMsg.value = error.message
                _uiState.update { it.copy(error = error.message) }
            }
        }
        attachedPlayerListener = listener
        PlayerRepository.addListener(listener)
    }

    // ─── PLAYBACK HISTORY & PROGRESS ──────────────────────────────────────────────
    fun savePlaybackProgress(
        src: String,
        title: String,
        positionSec: Long,
        durationSec: Long,
        type: String,
        playlistId: Long? = null
    ) {
        if (src.isEmpty() || title.isEmpty() || durationSec < 60) return
        val pct = positionSec.toFloat() / durationSec
        val activePlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: 0L
        viewModelScope.launch {
            if (pct < 0.01f) return@launch
            val entry = PlaybackHistory(src, title, positionSec, durationSec, pct, type, System.currentTimeMillis(), activePlId)
            historyDao.insert(entry)
        }
    }

    suspend fun getPlaybackProgress(src: String, playlistId: Long? = null): PlaybackHistory? {
        val activePlId = playlistId ?: sessionRepo.activePlaylist.value?.id ?: return null
        val entry = historyDao.getBySrcAndPlaylist(src, activePlId) ?: return null
        if (entry.pct > 0.95f) return null
        return entry
    }
}
