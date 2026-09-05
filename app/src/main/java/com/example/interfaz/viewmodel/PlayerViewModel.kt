package com.example.interfaz.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.interfaz.state.PlayerState
import com.example.motor.db.IptvItem
import com.example.motor.player.PlayerEngine
import com.example.motor.player.PlayerRepository
import com.example.motor.player.SubtitleTranslator
import com.example.motor.player.TrackOption
import com.example.motor.repository.ExternalSubtitle
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.repository.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionRepo = PortalSessionRepository.getInstance(application)
    private val iptvRepo = IptvRepository.getInstance(application, sessionRepo)
    private val tmdbRepo = TmdbRepository.getInstance()

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

    private val _autoSelectTrackLabel = MutableStateFlow<String?>(null)
    val autoSelectTrackLabel: StateFlow<String?> = _autoSelectTrackLabel.asStateFlow()

    // Listener reference to avoid duplicate attachments on shared ExoPlayer
    private var attachedPlayerListener: Player.Listener? = null

    // ─── EXISTING COMPATIBILITY METHODS ───────────────────────────────────────────

    fun getExoPlayer(context: Context): ExoPlayer {
        val player = PlayerRepository.getExoPlayer(context)
        attachPlayer(player)
        return player
    }

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
        subtitles: List<ExternalSubtitle> = emptyList()
    ) {
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
            subtitles = subtitles
        )
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
        viewModelScope.launch {
            iptvRepo.recordPlayback(
                url = state.streamUrl,
                title = state.title,
                position = posSec,
                duration = durSec,
                type = state.mediaType
            )
        }
    }

    // ─── NEW PLAYBACK CONTROL METHODS ─────────────────────────────────────────────

    fun seekTo(positionMs: Long) {
        try {
            val player = PlayerRepository.getExoPlayer(getApplication())
            player.seekTo(positionMs)
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
            val player = PlayerRepository.getExoPlayer(getApplication())
            player.setPlaybackSpeed(speed)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error in setPlaybackSpeed", e)
        }
    }

    fun updateSettings(skipSilenceSetting: Boolean = false, langSetting: String = "es") {
        PlayerRepository.updateSettings(
            skipSilenceSetting = skipSilenceSetting,
            langSetting = langSetting
        )
    }

    fun retryPlayback(context: Context? = null) {
        try {
            val player = PlayerRepository.getExoPlayer(context ?: getApplication())
            _isLoading.value = true
            _errorMsg.value = null
            player.prepare()
            player.play()
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
            val p = player ?: PlayerRepository.getExoPlayer(getApplication())
            PlayerEngine.getTrackOptions(p, trackType)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error getting track options", e)
            emptyList()
        }
    }

    fun selectTrack(track: TrackOption, player: Player? = null) {
        try {
            val p = player ?: PlayerRepository.getExoPlayer(getApplication())
            PlayerEngine.selectTrack(p, track)
            refreshTracks(p)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error selecting track", e)
        }
    }

    fun refreshTracks(player: Player? = null) {
        try {
            val p = player ?: PlayerRepository.getExoPlayer(getApplication())
            val audio = PlayerEngine.getTrackOptions(p, C.TRACK_TYPE_AUDIO)
            val subs = PlayerEngine.getTrackOptions(p, C.TRACK_TYPE_TEXT)
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
            val player = PlayerRepository.getExoPlayer(getApplication())
            PlayerEngine.selectTrack(player, track)
            refreshTracks(player)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error selecting audio track", e)
        }
    }

    fun selectSubtitleTrack(track: TrackOption) {
        try {
            val player = PlayerRepository.getExoPlayer(getApplication())
            PlayerEngine.selectTrack(player, track)
            refreshTracks(player)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error selecting subtitle track", e)
        }
    }

    // ─── SUBTITLES SEARCH & TRANSLATION ───────────────────────────────────────────

    fun searchSubtitles(title: String, isSeries: Boolean = false) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSearchingSubtitles.value = true
            _subtitleSearchError.value = null
            try {
                val subs = tmdbRepo.searchSubtitles(rawTitle = title, isSeries = isSeries)
                _externalSubtitles.value = subs
                if (subs.isEmpty()) {
                    _subtitleSearchError.value = "No se encontraron subtítulos"
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error searching subtitles", e)
                _subtitleSearchError.value = "Error al buscar subtítulos: ${e.message}"
            } finally {
                _isSearchingSubtitles.value = false
            }
        }
    }

    fun addExternalSubtitle(subtitle: ExternalSubtitle) {
        _externalSubtitles.update { it + subtitle }
    }

    fun clearExternalSubtitles() {
        _externalSubtitles.value = emptyList()
    }

    fun translateSubtitle(sub: ExternalSubtitle, targetLang: String = "ro") {
        viewModelScope.launch(Dispatchers.IO) {
            _isTranslatingSubtitle.value = true
            _subtitleNotification.value = "Traduciendo subtítulos..."
            try {
                val localPath = SubtitleTranslator.translateSubtitleUrl(
                    getApplication(), sub.url, targetLang
                )
                _isTranslatingSubtitle.value = false
                if (localPath != null) {
                    val langName = when (targetLang) {
                        "ro" -> "Rumano 🇷🇴"
                        "es" -> "Español 🇪🇸"
                        "en" -> "Inglés 🇬🇧"
                        else -> targetLang.uppercase()
                    }
                    val newLabel = "$langName (Traducido)"
                    val newSub = ExternalSubtitle(
                        id = "${sub.id}_tr_$targetLang",
                        url = localPath,
                        label = newLabel,
                        language = targetLang
                    )
                    addExternalSubtitle(newSub)
                    _autoSelectTrackLabel.value = newLabel
                    _subtitleNotification.value = "✅ Subtítulos en $langName listos"
                } else {
                    _subtitleNotification.value = "❌ No se pudieron traducir los subtítulos"
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error translating subtitle", e)
                _isTranslatingSubtitle.value = false
                _subtitleNotification.value = "Error: ${e.message}"
            }
            delay(3500)
            _subtitleNotification.value = null
        }
    }

    fun clearSubtitleNotification() {
        _subtitleNotification.value = null
    }

    fun clearAutoSelectTrackLabel() {
        _autoSelectTrackLabel.value = null
    }

    // ─── PLAYER LISTENER MANAGEMENT ───────────────────────────────────────────────

    fun attachPlayer(player: ExoPlayer) {
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

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _isLoading.value = true
                    }
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        val dur = player.duration
                        if (dur > 0) {
                            val durSec = dur / 1000L
                            _duration.value = durSec
                            _uiState.update { it.copy(duration = durSec) }
                        }
                        refreshTracks(player)
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
        player.addListener(listener)
    }

    fun detachPlayer(player: ExoPlayer) {
        attachedPlayerListener?.let {
            player.removeListener(it)
            attachedPlayerListener = null
        }
    }
}
