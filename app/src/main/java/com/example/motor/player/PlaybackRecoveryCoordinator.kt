package com.example.motor.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Interface abstracting player telemetry and control for [PlaybackRecoveryCoordinator].
 * Enables deterministic testing with virtual time and simulated player states.
 */
interface IPlayerPlaybackController {
    val currentPosition: Long
    val duration: Long
    val playbackState: Int
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val isLive: Boolean
    fun prepare()
    fun play()
    fun pause()
    fun executeStreamReset(resetPosition: Boolean, targetPositionMs: Long)
    suspend fun executeStreamResetAsync(resetPosition: Boolean, targetPositionMs: Long) {
        executeStreamReset(resetPosition, targetPositionMs)
    }
}

/**
 * Observable recovery state for the user interface.
 */
sealed class CoordinatorState {
    object Idle : CoordinatorState()
    data class Recovering(val attempt: Int, val reason: String, val delayMs: Long) : CoordinatorState()
    data class PermanentFailure(val message: String) : CoordinatorState()
    object Stable : CoordinatorState()
}

/**
 * Typed recovery reasons for diagnostic tracing and policy enforcement.
 */
sealed class RecoveryReason(val description: String) {
    data class PlayerError(val errorCode: Int, val message: String?, val isTransient: Boolean = true) :
        RecoveryReason("PlayerError(code=$errorCode, transient=$isTransient, msg=$message)")
    object StalledBuffering : RecoveryReason("StalledBuffering(>4s)")
    object StalledPlayback : RecoveryReason("StalledPlayback(frozen position)")
    object UnexpectedIdle : RecoveryReason("UnexpectedIdle(STATE_IDLE while active)")
    object UnexpectedStreamEnd : RecoveryReason("UnexpectedStreamEnd(Premature/Interrupted EOF)")
    object ManualRetry : RecoveryReason("ManualRetry")
}

/**
 * Centralized, production-grade recovery coordinator for IPTV playback in Android / Media3 ExoPlayer.
 *
 * Key guarantees:
 * 1. Single active recovery per session generation with atomic synchronization.
 * 2. Strictly prevents stale background jobs from modifying new sessions upon channel change.
 * 3. Verified progress confirmation: does not reset counter on mere STATE_READY or isPlaying;
 *    requires continuous position advancement across multiple intervals (VOD, Live TV, and audio).
 * 4. Permanent error blocking: stops automatic watchdog retries on 401, 403, 404, 410 until a new
 *    channel is selected or manual retry is initiated, without locking future sessions.
 * 5. Normal Media3 state transition handling: avoids false recovery loops during initial preparation
 *    and clean shutdowns.
 * 6. User pause protection: immediately cancels pending retries and suppresses auto-recovery.
 */
class PlaybackRecoveryCoordinator(
    private val controller: IPlayerPlaybackController,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val backoffPolicy: (Int) -> Long = { attempt ->
        val delays = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L)
        val baseDelay = if (attempt - 1 in delays.indices) delays[attempt - 1] else 15000L
        val jitter = ((baseDelay * 0.15) * (Math.random() * 2 - 1)).toLong()
        (baseDelay + jitter).coerceAtLeast(500L)
    },
    private val bufferingStallThresholdSec: Int = 4,
    private val frozenPositionThresholdSec: Int = 4,
    private val stableResetThresholdSec: Int = 3
) : AutoCloseable {

    companion object {
        const val TAG = "PLAYER_RECONNECT"

        fun sanitizeUrl(url: String?): String {
            if (url.isNullOrBlank()) return "<null>"
            return try {
                val uri = Uri.parse(url)
                val scheme = uri.scheme ?: "http"
                val host = uri.host ?: "unknown"
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val lastSegment = uri.lastPathSegment ?: ""
                "$scheme://$host$port/.../$lastSegment"
            } catch (_: Exception) {
                url.take(25) + "..."
            }
        }

        fun isErrorTransient(error: PlaybackException): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    val code = cause.responseCode
                    if (code == 400 || code == 401 || code == 403 || code == 404 || code == 410) {
                        return false
                    }
                    return true
                }
                cause = cause.cause
            }

            val msg = error.message?.lowercase() ?: ""
            if (msg.contains("response code: 401") ||
                msg.contains("response code: 403") ||
                msg.contains("response code: 404") ||
                msg.contains("response code: 410") ||
                msg.contains("http 401") ||
                msg.contains("http 403") ||
                msg.contains("http 404") ||
                msg.contains("http 410")
            ) {
                return false
            }

            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_TIMEOUT -> true
                else -> true
            }
        }
    }

    private val sessionGeneration = AtomicLong(0L)
    @Volatile private var currentSrc: String? = null
    @Volatile private var isLive: Boolean = true

    private val isUserPaused = AtomicBoolean(false)
    private val isRecovering = AtomicBoolean(false)
    private val isPermanentlyFailed = AtomicBoolean(false)
    private val isPreparing = AtomicBoolean(false)

    private val _coordinatorState = MutableStateFlow<CoordinatorState>(CoordinatorState.Idle)
    val coordinatorState: StateFlow<CoordinatorState> = _coordinatorState.asStateFlow()

    @Volatile private var attemptCount = 0
    private var activeRecoveryJob: Job? = null
    private var watchdogJob: Job? = null

    // Telemetry tracking for stable progress detection
    @Volatile private var renderedFirstFrame = false
    @Volatile private var consecutiveStableSeconds = 0
    @Volatile private var lastKnownPositionMs = 0L

    val currentAttemptCount: Int get() = attemptCount
    val isCurrentlyRecovering: Boolean get() = isRecovering.get()
    val isSessionPermanentlyFailed: Boolean get() = isPermanentlyFailed.get()
    val currentSessionId: Long get() = sessionGeneration.get()

    /**
     * Called whenever a new stream begins playback or when recovering an interrupted stream.
     * When isRecoveryReload is true, preserves the current recovery attempt without invalidating the active session.
     */
    fun onSessionStarted(src: String, isLive: Boolean, isRecoveryReload: Boolean = false) {
        currentSrc = src
        this.isLive = isLive
        isUserPaused.set(false)
        isPermanentlyFailed.set(false)
        isPreparing.set(true)
        renderedFirstFrame = false
        consecutiveStableSeconds = 0

        if (!isRecoveryReload) {
            val newGen = sessionGeneration.incrementAndGet()
            attemptCount = 0
            isRecovering.set(false)
            lastKnownPositionMs = 0L

            activeRecoveryJob?.cancel()
            activeRecoveryJob = null

            _coordinatorState.value = CoordinatorState.Idle
            Log.i(TAG, "Playback session started: src=${sanitizeUrl(src)}, isLive=$isLive, generation=$newGen")

            startWatchdog()
        } else {
            Log.i(TAG, "Playback recovery reload for session ${sessionGeneration.get()}: src=${sanitizeUrl(src)}, isLive=$isLive, attempt=$attemptCount")
        }
    }

    /**
     * Called when playback is stopped or released.
     */
    fun onSessionEnded() {
        val oldGen = sessionGeneration.incrementAndGet()
        currentSrc = null
        attemptCount = 0
        isRecovering.set(false)
        isPermanentlyFailed.set(false)
        isPreparing.set(false)
        renderedFirstFrame = false
        consecutiveStableSeconds = 0
        lastKnownPositionMs = 0L

        activeRecoveryJob?.cancel()
        activeRecoveryJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        _coordinatorState.value = CoordinatorState.Idle
        Log.i(TAG, "Playback session ended for generation $oldGen. Watchdogs and recovery jobs cancelled.")
    }

    /**
     * User pressed pause in the UI. Suppresses any automatic recovery.
     */
    fun onUserPause() {
        isUserPaused.set(true)
        Log.i(TAG, "User paused playback (session=${sessionGeneration.get()}). Auto-recovery suppressed.")
        activeRecoveryJob?.cancel()
        activeRecoveryJob = null
        isRecovering.set(false)
        _coordinatorState.value = CoordinatorState.Idle
    }

    /**
     * User resumed playback in the UI.
     */
    fun onUserPlay() {
        isUserPaused.set(false)
        Log.i(TAG, "User resumed playback (session=${sessionGeneration.get()}).")
    }

    /**
     * Forwarded error callback from ExoPlayer.
     */
    fun onPlayerError(error: PlaybackException) {
        val isTransient = isErrorTransient(error)
        val msgLower = error.message?.lowercase() ?: ""
        val isAuthTokenExpiry = msgLower.contains("401") || msgLower.contains("403")
        val hadSustainedPlayback = renderedFirstFrame || lastKnownPositionMs > 5000L
        val allowTokenRefreshRecovery = !isTransient && isAuthTokenExpiry && hadSustainedPlayback && attemptCount == 0
        Log.w(TAG, "Player error detected: code=${error.errorCode}, transient=$isTransient, refreshToken=$allowTokenRefreshRecovery, msg=${error.message}")
        if (isTransient || allowTokenRefreshRecovery) {
            requestRecovery(RecoveryReason.PlayerError(error.errorCode, error.message, isTransient = isTransient))
        } else {
            isPermanentlyFailed.set(true)
            activeRecoveryJob?.cancel()
            activeRecoveryJob = null
            isRecovering.set(false)

            val msg = when {
                error.message?.contains("401") == true -> "Error 401: No autorizado (credenciales o token expirado)"
                error.message?.contains("403") == true -> "Error 403: Acceso denegado al stream"
                error.message?.contains("404") == true -> "Error 404: Canal o contenido no encontrado"
                error.message?.contains("410") == true -> "Error 410: Stream eliminado"
                else -> error.message ?: "Error permanente de reproducción"
            }
            Log.e(TAG, "Permanent playback error detected: code=${error.errorCode}. Automatic recovery suppressed for session ${sessionGeneration.get()}.")
            _coordinatorState.value = CoordinatorState.PermanentFailure(msg)
        }
    }

    fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_IDLE -> {
                // Ignore normal STATE_IDLE transitions during initial preparation, user pause, or permanent failure
                if (!isUserPaused.get() && currentSrc != null && !isPreparing.get() && !isPermanentlyFailed.get() && !isRecovering.get()) {
                    Log.w(TAG, "Unexpected STATE_IDLE detected while session is active and playWhenReady is true.")
                    requestRecovery(RecoveryReason.UnexpectedIdle)
                }
            }
            Player.STATE_BUFFERING -> {
                isPreparing.set(false)
            }
            Player.STATE_READY -> {
                isPreparing.set(false)
                val cur = controller.currentPosition
                if (cur > 0L) {
                    lastKnownPositionMs = cur
                }
                // Do NOT reset attemptCount on mere STATE_READY. Await verified continuous position advancement.
            }
            Player.STATE_ENDED -> {
                isPreparing.set(false)
                val curPos = if (lastKnownPositionMs > 0L) lastKnownPositionMs else controller.currentPosition
                val dur = controller.duration
                val isPremature = isLive || (dur <= 0L) || (curPos < dur - 15000L)
                Log.d(TAG, "Playback reached STATE_ENDED. isLive=$isLive, curPos=$curPos, dur=$dur, isPremature=$isPremature")
                if (isPremature && !isUserPaused.get() && currentSrc != null && !isPermanentlyFailed.get()) {
                    Log.w(TAG, "Premature stream termination (EOF drop) detected. Requesting recovery...")
                    requestRecovery(RecoveryReason.UnexpectedStreamEnd)
                }
            }
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            consecutiveStableSeconds = 0
        }
    }

    fun onRenderedFirstFrame() {
        renderedFirstFrame = true
        Log.d(TAG, "First video frame rendered for session=${sessionGeneration.get()}")
    }

    /**
     * Centralized entry point to request a playback recovery.
     * Guaranteed thread-safe, deduplicated, and strictly respects session validity and permanent failure rules.
     */
    fun requestRecovery(reason: RecoveryReason): Boolean {
        val targetSrc = currentSrc
        if (targetSrc == null) {
            Log.d(TAG, "No active playback session. Ignoring recovery request: ${reason.description}")
            return false
        }

        if (reason is RecoveryReason.ManualRetry) {
            // Explicit manual retry from user unblocks permanent failure
            isPermanentlyFailed.set(false)
        }

        if (isPermanentlyFailed.get()) {
            Log.d(TAG, "Session has permanently failed. Suppressing auto-recovery for: ${reason.description}")
            return false
        }

        if (isUserPaused.get()) {
            Log.d(TAG, "Playback is paused by user. Suppressing auto-recovery for: ${reason.description}")
            return false
        }

        val targetGeneration = sessionGeneration.get()

        // Atomically ensure only one recovery is active for this session
        if (!isRecovering.compareAndSet(false, true)) {
            Log.d(TAG, "Recovery already in progress for session $targetGeneration (attempt $attemptCount). Ignoring duplicate request: ${reason.description}")
            return false
        }

        attemptCount++
        consecutiveStableSeconds = 0
        renderedFirstFrame = false

        val delayMs = backoffPolicy(attemptCount)
        Log.i(TAG, "Starting recovery attempt $attemptCount for session $targetGeneration. Backoff delay: ${delayMs}ms, reason: ${reason.description}")
        _coordinatorState.value = CoordinatorState.Recovering(attemptCount, reason.description, delayMs)

        activeRecoveryJob?.cancel()
        activeRecoveryJob = coroutineScope.launch {
            try {
                if (delayMs > 0) {
                    delay(delayMs)
                }

                // Verify session remains valid after delay
                if (sessionGeneration.get() != targetGeneration || currentSrc != targetSrc) {
                    Log.i(TAG, "Recovery aborted after backoff delay: session invalidated (current=${sessionGeneration.get()}, target=$targetGeneration)")
                    return@launch
                }
                if (isUserPaused.get()) {
                    Log.i(TAG, "Recovery aborted after backoff delay: user paused playback")
                    return@launch
                }

                val savedPos = if (lastKnownPositionMs > 0L) lastKnownPositionMs else controller.currentPosition
                val targetPos = if (isLive) 0L else savedPos
                Log.i(TAG, "Executing stream reset for session $targetGeneration (attempt $attemptCount, isLive=$isLive, targetPos=$targetPos)...")
                controller.executeStreamResetAsync(resetPosition = isLive, targetPositionMs = targetPos)
                Log.i(TAG, "Stream reset executed for session $targetGeneration. Awaiting playback stabilization...")

                // Post-execution grace period
                delay(1500L)
            } catch (e: CancellationException) {
                Log.i(TAG, "Recovery job cancelled for session $targetGeneration")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error executing recovery for session $targetGeneration", e)
            } finally {
                // Only reset isRecovering if this job still matches the current session generation
                if (sessionGeneration.get() == targetGeneration) {
                    isRecovering.set(false)
                }
            }
        }
        return true
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        val targetGeneration = sessionGeneration.get()
        watchdogJob = coroutineScope.launch {
            var lastTrackedPos = -1L
            var samePosSeconds = 0
            var bufferingSeconds = 0

            // Initial grace period for player connection, 302 redirects, and initial chunk buffering
            delay(2000L)
            isPreparing.set(false)

            while (isActive && sessionGeneration.get() == targetGeneration) {
                delay(1000L)

                // If session permanently failed, user paused, or recovery is active, suspend watchdog checks
                if (isPermanentlyFailed.get() || isUserPaused.get() || isRecovering.get()) {
                    bufferingSeconds = 0
                    samePosSeconds = 0
                    consecutiveStableSeconds = 0
                    continue
                }

                val state = controller.playbackState
                val isPwr = controller.playWhenReady
                val curPos = controller.currentPosition
                val isPlaying = controller.isPlaying

                if (curPos > 0L) {
                    lastKnownPositionMs = curPos
                }

                if (state == Player.STATE_BUFFERING || state == Player.STATE_READY) {
                    isPreparing.set(false)
                }

                // 1. Detect unexpected STATE_IDLE while player is intended to be playing (not initial preparation)
                if (state == Player.STATE_IDLE && isPwr && currentSrc != null && !isPreparing.get()) {
                    Log.w(TAG, "Watchdog: Unexpected STATE_IDLE detected while active. Triggering recovery...")
                    requestRecovery(RecoveryReason.UnexpectedIdle)
                    continue
                }

                // 2. Detect genuinely prolonged buffering (>= bufferingStallThresholdSec after grace period)
                if (state == Player.STATE_BUFFERING && isPwr) {
                    bufferingSeconds++
                    consecutiveStableSeconds = 0
                    if (bufferingSeconds >= bufferingStallThresholdSec) {
                        Log.w(TAG, "Watchdog: Prolonged buffering detected (${bufferingSeconds}s). Triggering recovery...")
                        bufferingSeconds = 0
                        requestRecovery(RecoveryReason.StalledBuffering)
                        continue
                    }
                } else {
                    bufferingSeconds = 0
                }

                // 3. Telemetry and verified continuous progress detection when STATE_READY and playWhenReady
                if (state == Player.STATE_READY && isPwr) {
                    if (lastTrackedPos == -1L) {
                        lastTrackedPos = curPos
                        continue
                    }

                    val positionAdvanced = (curPos > lastTrackedPos)

                    if (positionAdvanced) {
                        // Real progress verified across consecutive ticks (VOD, Live TV, and audio streams)
                        samePosSeconds = 0
                        lastTrackedPos = curPos
                        consecutiveStableSeconds++

                        if (consecutiveStableSeconds >= stableResetThresholdSec) {
                            if (attemptCount > 0) {
                                Log.i(TAG, "Playback progress verified ($consecutiveStableSeconds consecutive seconds of advancing position). Recovery stable for session $targetGeneration after $attemptCount attempts. Resetting retry counter.")
                                attemptCount = 0
                                _coordinatorState.value = CoordinatorState.Stable
                            }
                        }
                    } else {
                        consecutiveStableSeconds = 0
                        samePosSeconds++
                        if (samePosSeconds >= frozenPositionThresholdSec) {
                            Log.w(TAG, "Watchdog: Playback stall detected (frozen at ${curPos}ms for ${samePosSeconds}s, isPlaying=$isPlaying). Triggering recovery...")
                            samePosSeconds = 0
                            requestRecovery(RecoveryReason.StalledPlayback)
                            continue
                        }
                    }
                } else {
                    consecutiveStableSeconds = 0
                    samePosSeconds = 0
                    lastTrackedPos = curPos
                }
            }
        }
    }

    override fun close() {
        release()
    }

    fun release() {
        onSessionEnded()
    }
}
