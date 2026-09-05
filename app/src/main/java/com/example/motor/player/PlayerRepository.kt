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
import androidx.media3.extractor.DefaultExtractorsFactory
import java.util.UUID
import com.example.motor.parser.BaseEngine
import com.example.motor.tmdb.TmdbService

@OptIn(UnstableApi::class)
object PlayerRepository {
    private const val TAG = "PlayerRepository"

    private var exoPlayerInstance: ExoPlayer? = null

    @Volatile
    private var currentSrc: String? = null
    @Volatile
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentDrmScheme: String? = null
    private var currentDrmLicenseUrl: String? = null

    fun getExoPlayer(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        return exoPlayerInstance ?: synchronized(this) {
            exoPlayerInstance ?: createExoPlayer(appContext).also { exoPlayerInstance = it }
        }
    }

    private val streamingClient = BaseEngine.client.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // Indefinite read timeout for continuous live streams
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun createExoPlayer(context: Context): ExoPlayer {
        val httpDataSourceFactory = DataSource.Factory {
            val headers = currentHeaders
            val userAgent = headers["User-Agent"]
                ?: "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
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

        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(httpDataSourceFactory)

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ) = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)
                .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                .build()
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setMediaCodecSelector(androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT)
        }

        try {
            val isFfmpegAvailable = androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()
            val ffmpegVersion = androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion()
            Log.i(TAG, "FFmpeg extension initialized successfully: available=$isFfmpegAvailable, version=$ffmpegVersion")
        } catch (t: Throwable) {
            Log.w(TAG, "FFmpeg check: ${t.message}")
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2000, // minBufferMs
                12000, // maxBufferMs
                800,  // bufferForPlaybackMs
                1500  // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                playWhenReady = true
                volume = 1.0f
            }
    }

    fun playMedia(
        context: Context,
        src: String,
        headers: Map<String, String> = emptyMap(),
        drmScheme: String? = null,
        drmLicenseUrl: String? = null,
        initialPosMs: Long = 0L,
        forceReload: Boolean = false,
        subtitles: List<TmdbService.ExternalSubtitle> = emptyList()
    ) {
        val player = getExoPlayer(context)

        // If the same source is already active and not ended, and not forcing reload or adding new subtitles
        if (!forceReload && currentSrc == src && subtitles.isEmpty() && player.playbackState != Player.STATE_ENDED) {
            Log.d(TAG, "Media $src is already active in shared ExoPlayer. Resuming seamless playback.")
            if (!player.isPlaying && player.playWhenReady) {
                player.play()
            } else if (!player.playWhenReady) {
                player.playWhenReady = true
                player.play()
            }
            return
        }

        Log.d(TAG, "Loading new media item in shared ExoPlayer: $src")
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

        if (subtitles.isNotEmpty()) {
            val subConfigs = subtitles.map { sub ->
                val uriString = if (sub.url.startsWith("/") || sub.url.startsWith("file://")) {
                    if (sub.url.startsWith("file://")) sub.url else "file://${sub.url}"
                } else {
                    sub.url
                }
                val subMime = if (sub.url.contains("vtt", ignoreCase = true)) {
                    MimeTypes.TEXT_VTT
                } else {
                    MimeTypes.APPLICATION_SUBRIP
                }
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(uriString))
                    .setMimeType(subMime)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(subConfigs)
        }

        val targetPos = if (initialPosMs > 0) initialPosMs else if (player.currentPosition > 0) player.currentPosition else 0L

        val mediaItem = mediaItemBuilder.build()
        player.setMediaItem(mediaItem)
        if (targetPos > 0) {
            player.seekTo(targetPos)
        }
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    fun updateSettings(
        skipSilenceSetting: Boolean = false,
        langSetting: String = "es"
    ) {
        val player = exoPlayerInstance ?: return
        player.setSkipSilenceEnabled(skipSilenceSetting)
        val preferredLang = when (langSetting) {
            "ro" -> "ron"
            "en" -> "eng"
            else -> "spa"
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(preferredLang)
            .setPreferredTextLanguage(preferredLang)
            .build()
    }

    fun pause() {
        exoPlayerInstance?.pause()
    }

    fun resume() {
        exoPlayerInstance?.play()
    }

    fun stop() {
        exoPlayerInstance?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        currentSrc = null
    }

    fun release() {
        exoPlayerInstance?.release()
        exoPlayerInstance = null
        currentSrc = null
    }

    fun getCurrentSrc(): String? = currentSrc
}
