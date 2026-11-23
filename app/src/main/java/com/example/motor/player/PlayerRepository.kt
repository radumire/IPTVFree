package com.example.motor.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.example.motor.parser.BaseEngine
import com.example.motor.subtitles.ExternalSubtitle

@OptIn(UnstableApi::class)
object PlayerRepository {
    private const val TAG = "PlayerRepository"

    private var exoPlayerInstance: ExoPlayer? = null
    private val registeredListeners = mutableSetOf<Player.Listener>()

    @Volatile
    private var currentSrc: String? = null
    @Volatile
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentDrmScheme: String? = null
    private var currentDrmLicenseUrl: String? = null
    @Volatile
    private var appContext: Context? = null

    private val adaptiveLoadControl = AdaptiveLoadControl()

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null
    @Volatile
    private var streamReResolverSrc: String? = null
    @Volatile
    private var streamReResolver: (suspend () -> Pair<String, Map<String, String>>)? = null

    fun registerStreamReResolver(
        resolvedUrl: String,
        resolver: suspend () -> Pair<String, Map<String, String>>
    ) {
        if (resolvedUrl.isNotBlank()) {
            streamReResolverSrc = resolvedUrl
            streamReResolver = resolver
        }
    }

    private fun startPortalKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = repoScope.launch {
            while (isActive) {
                delay(90_000L)
                val shouldPing = withContext(Dispatchers.Main.immediate) {
                    val p = exoPlayerInstance
                    p != null && currentSrc != null && (p.isPlaying || p.playWhenReady)
                }
                if (shouldPing) {
                    try {
                        com.example.motor.repository.PortalSessionRepository.getInstanceOrNull()
                            ?.keepActivePlaylistSessionAlive()
                    } catch (e: Exception) {
                        Log.w(TAG, "Portal keep-alive ping warning: ${e.message}")
                    }
                }
            }
        }
    }

    private val playbackController = object : IPlayerPlaybackController {
        override val currentPosition: Long get() = exoPlayerInstance?.currentPosition ?: 0L
        override val duration: Long get() = exoPlayerInstance?.duration ?: 0L
        override val playbackState: Int get() = exoPlayerInstance?.playbackState ?: Player.STATE_IDLE
        override val isPlaying: Boolean get() = exoPlayerInstance?.isPlaying ?: false
        override val playWhenReady: Boolean get() = exoPlayerInstance?.playWhenReady ?: false
        override val isLive: Boolean get() {
            val p = exoPlayerInstance ?: return true
            return p.isCurrentMediaItemLive || p.isCurrentMediaItemDynamic || p.duration <= 0
        }
        override fun prepare() {
            exoPlayerInstance?.prepare()
        }
        override fun play() {
            exoPlayerInstance?.play()
        }
        override fun pause() {
            exoPlayerInstance?.pause()
        }
        override fun executeStreamReset(resetPosition: Boolean, targetPositionMs: Long) {
            executeResetInternal(
                resetPosition = resetPosition,
                targetPositionMs = targetPositionMs,
                resolvedUrlOverride = null,
                resolvedHeadersOverride = null
            )
        }

        override suspend fun executeStreamResetAsync(resetPosition: Boolean, targetPositionMs: Long) {
            val snapshotUrl = currentSrc
            val resolver = streamReResolver
            var freshUrl: String? = null
            var freshHeaders: Map<String, String>? = null
            if (!snapshotUrl.isNullOrEmpty() && resolver != null && streamReResolverSrc == snapshotUrl) {
                val refreshed = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(6000L) {
                        try {
                            com.example.motor.repository.PortalSessionRepository.getInstanceOrNull()
                                ?.keepActivePlaylistSessionAlive()
                            resolver.invoke()
                        } catch (e: Exception) {
                            Log.w("PLAYER_RECONNECT", "Fresh URL resolution failed during recovery, keeping previous URL: ${e.message}")
                            null
                        }
                    }
                }
                if (refreshed != null && refreshed.first.isNotBlank()) {
                    freshUrl = refreshed.first
                    freshHeaders = refreshed.second
                    if (freshUrl != snapshotUrl) {
                        streamReResolverSrc = freshUrl
                        Log.i("PLAYER_RECONNECT", "Re-resolved fresh stream token URL for recovery reset")
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (currentSrc != snapshotUrl) return@withContext
                executeResetInternal(
                    resetPosition = resetPosition,
                    targetPositionMs = targetPositionMs,
                    resolvedUrlOverride = freshUrl,
                    resolvedHeadersOverride = freshHeaders
                )
            }
        }

        private fun executeResetInternal(
            resetPosition: Boolean,
            targetPositionMs: Long,
            resolvedUrlOverride: String?,
            resolvedHeadersOverride: Map<String, String>?
        ) {
            val player = exoPlayerInstance ?: return
            val url = resolvedUrlOverride ?: currentSrc
            val headersToUse = resolvedHeadersOverride?.takeIf { it.isNotEmpty() } ?: currentHeaders
            Log.i("PLAYER_RECONNECT", "PlayerRepository executing stream reset: isLive=$resetPosition, targetPos=$targetPositionMs, curUrl=${url != null}")
            if (!url.isNullOrEmpty() && appContext != null) {
                // Stop the player completely to release blocked network sockets, clear corrupt buffers,
                // and flush decoders for both Live TV and VOD (Movies & Series).
                try {
                    player.stop()
                } catch (e: Exception) {
                    Log.w("PLAYER_RECONNECT", "Error stopping player during stream reset", e)
                }
                playMedia(
                    context = appContext!!,
                    src = url,
                    headers = headersToUse,
                    drmScheme = currentDrmScheme,
                    drmLicenseUrl = currentDrmLicenseUrl,
                    initialPosMs = if (resetPosition) 0L else targetPositionMs,
                    forceReload = true,
                    isLive = resetPosition,
                    isAutoRecovery = true
                )
            } else {
                player.prepare()
                player.playWhenReady = true
                player.play()
            }
        }
    }

    val recoveryCoordinator: PlaybackRecoveryCoordinator by lazy {
        PlaybackRecoveryCoordinator(
            controller = playbackController,
            bufferingStallThresholdSec = 10,
            frozenPositionThresholdSec = 8,
            stableResetThresholdSec = 15
        )
    }

    fun getExoPlayer(context: Context): ExoPlayer {
        val appCtx = context.applicationContext
        appContext = appCtx
        return exoPlayerInstance ?: synchronized(this) {
            exoPlayerInstance ?: createExoPlayer(appCtx).also { player ->
                exoPlayerInstance = player
                synchronized(registeredListeners) {
                    registeredListeners.forEach { listener ->
                        player.addListener(listener)
                    }
                }
            }
        }
    }

    // Dedicated OkHttpClient isolated from catalog/TMDB/EPG dispatcher and 10-minute idle pool
    private val streamingClient = BaseEngine.client.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        })
        .connectionPool(okhttp3.ConnectionPool(8, 90, java.util.concurrent.TimeUnit.SECONDS))
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun createExoPlayer(context: Context): ExoPlayer {
        val httpDataSourceFactory = DataSource.Factory {
            val headers = currentHeaders
            val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: "IPTVSmartersPro/3.1.5 (Linux; Android 11)"
            val factory = OkHttpDataSource.Factory(streamingClient)
                .setUserAgent(userAgent)
            if (headers.isNotEmpty()) {
                factory.setDefaultRequestProperties(headers)
            }
            factory.createDataSource()
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(1 or 8 or 16 or 64) // FLAG_ALLOW_NON_IDR_KEYFRAMES, FLAG_DETECT_ACCESS_UNITS, FLAG_IGNORE_SPLICE_INFO_STREAM, FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            .setTsExtractorTimestampSearchBytes(2000 * 188)
            .setConstantBitrateSeekingEnabled(true)

        val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(5)

        val upstreamDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(upstreamDataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setMediaCodecSelector(androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(adaptiveLoadControl)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setUsePlatformDiagnostics(false)
            .build().apply {
                playWhenReady = true
                volume = 1.0f
            }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                recoveryCoordinator.onPlayerError(error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                recoveryCoordinator.onPlaybackStateChanged(state)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                recoveryCoordinator.onIsPlayingChanged(isPlaying)
            }
            override fun onRenderedFirstFrame() {
                recoveryCoordinator.onRenderedFirstFrame()
            }
        })

        return player
    }

    fun playMedia(
        context: Context,
        src: String,
        headers: Map<String, String> = emptyMap(),
        drmScheme: String? = null,
        drmLicenseUrl: String? = null,
        initialPosMs: Long = 0L,
        forceReload: Boolean = false,
        subtitlesEnabled: Boolean = false,
        isLive: Boolean? = null,
        isAutoRecovery: Boolean = false
    ) {
        val player = getExoPlayer(context)
        val isSameSource = (currentSrc == src)

        // If the same source is already active and not ended, and not forcing reload
        if (!forceReload && isSameSource && player.playbackState != Player.STATE_ENDED) {
            if (!player.isPlaying && player.playWhenReady) {
                player.play()
            } else if (!player.playWhenReady) {
                player.playWhenReady = true
                player.play()
            }
            return
        }

        currentSrc = src
        currentHeaders = headers
        currentDrmScheme = drmScheme
        currentDrmLicenseUrl = drmLicenseUrl

        val mediaItemBuilder = MediaItem.Builder().setUri(src)

        // Set explicit mimeType only for known manifests or explicit .ts extension
        val mimeType = when {
            src.contains(".m3u8", ignoreCase = true) || src.contains("m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            src.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            src.contains(".ism", ignoreCase = true) -> MimeTypes.APPLICATION_SS
            src.endsWith(".ts", ignoreCase = true) || src.contains(".ts?", ignoreCase = true) || src.contains(".ts/", ignoreCase = true) || src.contains("/udp/", ignoreCase = true) || src.contains("/rtp/", ignoreCase = true) || src.contains("/ch/", ignoreCase = true) || src.contains("ch/", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
            src.contains(".mp4", ignoreCase = true) || (src.contains("/series/", ignoreCase = true) && src.endsWith(".mp4", ignoreCase = true)) -> MimeTypes.VIDEO_MP4
            src.contains(".mkv", ignoreCase = true) -> MimeTypes.VIDEO_MATROSKA
            src.contains(".avi", ignoreCase = true) -> MimeTypes.VIDEO_AVI
            src.contains(".flv", ignoreCase = true) -> MimeTypes.VIDEO_FLV
            else -> null
        }
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        if (!drmLicenseUrl.isNullOrBlank()) {

            val schemeUuid = when {
                drmScheme.equals("widevine", ignoreCase = true) -> C.WIDEVINE_UUID
                drmScheme.equals("playready", ignoreCase = true) -> C.PLAYREADY_UUID
                drmScheme.equals("clearkey", ignoreCase = true) -> C.CLEARKEY_UUID
                !drmScheme.isNullOrBlank() -> try {
                    UUID.fromString(drmScheme)
                } catch (e: Exception) {
                    null
                }
                else -> null
            }

            if (schemeUuid != null) {
                val drmConfig = MediaItem.DrmConfiguration.Builder(schemeUuid)
                    .setLicenseUri(android.net.Uri.parse(drmLicenseUrl))
                    .build()

                mediaItemBuilder.setDrmConfiguration(drmConfig)
            }
        }

        val trackParamsBuilder = player.trackSelectionParameters.buildUpon()
        if (!subtitlesEnabled) {
            trackParamsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = trackParamsBuilder.build()

        val isVodPathOrExt =
            src.contains("/series/", ignoreCase = true) ||
            src.contains("/movie/", ignoreCase = true) ||
            src.contains("/vod/", ignoreCase = true) ||
            src.contains(".mp4", ignoreCase = true) ||
            src.contains(".mkv", ignoreCase = true) ||
            src.contains(".avi", ignoreCase = true) ||
            src.contains(".mov", ignoreCase = true) ||
            src.contains(".flv", ignoreCase = true) ||
            src.contains(".webm", ignoreCase = true)

        val inferredLiveFromUrl = !isVodPathOrExt && (
            src.contains(".m3u8", ignoreCase = true) ||
            src.endsWith(".ts", ignoreCase = true) ||
            src.contains(".ts?", ignoreCase = true) ||
            src.contains(".ts/", ignoreCase = true) ||
            src.contains("/udp/", ignoreCase = true) ||
            src.contains("/rtp/", ignoreCase = true) ||
            src.contains("/ch/", ignoreCase = true) ||
            src.contains("/live/", ignoreCase = true)
        )

        val isLiveStream = isLive ?: inferredLiveFromUrl

        adaptiveLoadControl.isLiveStream = isLiveStream

        val targetPos = if (isLiveStream) {
            0L // Live streams must connect directly to the live edge, never seeking back into dead/frozen buffers
        } else if (initialPosMs > 0) {
            initialPosMs
        } else if (forceReload && isSameSource && player.currentPosition > 0) {
            player.currentPosition
        } else {
            0L
        }

        val mediaItem = mediaItemBuilder.build()
        player.setMediaItem(mediaItem)
        if (isLiveStream) {
            player.seekToDefaultPosition()
        } else if (targetPos > 0) {
            player.seekTo(targetPos)
        }
        player.prepare()
        player.playWhenReady = true
        player.play()

        recoveryCoordinator.onSessionStarted(src, isLiveStream, isRecoveryReload = isAutoRecovery)
        if (!isAutoRecovery) {
            startPortalKeepAliveLoop()
        }
    }

    fun updateSettings(
        skipSilenceSetting: Boolean = false,
        langSetting: String = "es",
        subtitlesEnabled: Boolean = false
    ) {
        val player = exoPlayerInstance ?: return
        player.setSkipSilenceEnabled(skipSilenceSetting)
        val preferredLang = when (langSetting) {
            "ro" -> "ron"
            "en" -> "eng"
            else -> "spa"
        }
        val builder = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(preferredLang)

        if (subtitlesEnabled) {
            builder.setPreferredTextLanguage(preferredLang)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = builder.build()
    }

    fun pause() {
        recoveryCoordinator.onUserPause()
        exoPlayerInstance?.pause()
    }

    fun resume() {
        recoveryCoordinator.onUserPlay()
        exoPlayerInstance?.play()
    }

    fun stop() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        recoveryCoordinator.onSessionEnded()
        exoPlayerInstance?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        currentSrc = null
        streamReResolverSrc = null
        streamReResolver = null
    }

    fun release() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        recoveryCoordinator.release()
        exoPlayerInstance?.release()
        exoPlayerInstance = null
        currentSrc = null
        streamReResolverSrc = null
        streamReResolver = null
    }

    fun disableTextTracks() {
        val runDisable = {
            exoPlayerInstance?.let { player ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runDisable()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { runDisable() }
        }
    }

    fun getCurrentSrc(): String? = currentSrc

    fun addListener(listener: Player.Listener) {
        synchronized(registeredListeners) {
            registeredListeners.add(listener)
            exoPlayerInstance?.addListener(listener)
        }
    }

    fun removeListener(listener: Player.Listener) {
        synchronized(registeredListeners) {
            registeredListeners.remove(listener)
            exoPlayerInstance?.removeListener(listener)
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayerInstance?.seekTo(positionMs)
    }

    fun seekBy(offsetMs: Long) {
        exoPlayerInstance?.let { player ->
            if (player.isCurrentMediaItemLive || (adaptiveLoadControl.isLiveStream && player.duration <= 0L)) {
                return
            }
            val cur = player.currentPosition
            val dur = player.duration
            val maxPos = if (dur > 0) dur else Long.MAX_VALUE
            val target = (cur + offsetMs).coerceIn(0L, maxPos)
            player.seekTo(target)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayerInstance?.setPlaybackSpeed(speed)
    }

    fun isPlayWhenReady(): Boolean {
        return exoPlayerInstance?.playWhenReady ?: false
    }

    fun recoverStalledPlayback(context: Context? = null) {
        val ctx = context ?: appContext
        if (ctx != null) appContext = ctx
        recoveryCoordinator.requestRecovery(RecoveryReason.ManualRetry)
    }

    fun retryPlayback(context: Context? = null) {
        recoverStalledPlayback(context)
    }

    fun getCurrentPosition(): Long {
        return exoPlayerInstance?.currentPosition ?: 0L
    }

    fun getDuration(): Long {
        val dur = exoPlayerInstance?.duration ?: 0L
        return if (dur > 0) dur else 0L
    }

    fun getPlaybackState(): Int {
        return exoPlayerInstance?.playbackState ?: Player.STATE_IDLE
    }

    fun isPlaying(): Boolean {
        return exoPlayerInstance?.isPlaying ?: false
    }

    fun getTrackOptions(trackType: Int): List<TrackOption> {
        val player = exoPlayerInstance ?: return emptyList()
        return PlayerEngine.getTrackOptions(player, trackType)
    }

    fun selectTrack(track: TrackOption) {
        val player = exoPlayerInstance ?: return
        PlayerEngine.selectTrack(player, track)
    }

    suspend fun translateSubtitleUrl(
        context: Context,
        subUrl: String,
        targetLanguage: String,
        onProgressiveReady: ((String) -> Unit)? = null
    ): String? {
        return SubtitleTranslator.translateSubtitleUrl(context, subUrl, targetLanguage, onProgressiveReady)
    }

    fun attachPlayerView(playerView: PlayerView) {
        val player = getExoPlayer(playerView.context)
        if (playerView.player != player) {
            playerView.player = player
        }
    }

    fun detachPlayerView(playerView: PlayerView) {
        if (playerView.player == exoPlayerInstance) {
            playerView.player = null
        }
    }
}
