@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.interfaz.components

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.motor.player.TrackOption
import com.example.motor.subtitles.ExternalSubtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
fun anchorSubtitleViewToViewport(playerView: PlayerView) {
    val subView = playerView.subtitleView ?: return
    if (subView.parent !== playerView) {
        (subView.parent as? ViewGroup)?.removeView(subView)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val contentFrame = playerView.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)
        val insertIndex = if (contentFrame != null && contentFrame.parent === playerView) {
            playerView.indexOfChild(contentFrame) + 1
        } else {
            playerView.childCount
        }
        playerView.addView(subView, insertIndex.coerceIn(0, playerView.childCount), lp)
    }
    playerView.clipChildren = true
    playerView.clipToPadding = true
}

@OptIn(UnstableApi::class)
fun applySubtitleStyles(
    playerView: PlayerView,
    subtitleColor: String,
    subtitleBg: Boolean,
    subtitleSize: String
) {
    anchorSubtitleViewToViewport(playerView)
    playerView.subtitleView?.let { subView ->
        val fgColor = when (subtitleColor.lowercase()) {
            "amarillo" -> android.graphics.Color.parseColor("#FFEB3B")
            "verde" -> android.graphics.Color.parseColor("#4CAF50")
            "cyan", "celeste / cyan", "celeste" -> android.graphics.Color.parseColor("#00E5FF")
            else -> android.graphics.Color.WHITE
        }
        val bgColor = if (subtitleBg) {
            android.graphics.Color.argb(180, 0, 0, 0)
        } else {
            android.graphics.Color.TRANSPARENT
        }
        val edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE
        val edgeColor = android.graphics.Color.BLACK
        val captionStyle = CaptionStyleCompat(
            fgColor,
            bgColor,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            edgeColor,
            null
        )
        subView.setStyle(captionStyle)
        subView.setApplyEmbeddedStyles(false)
        subView.setApplyEmbeddedFontSizes(false)

        val sizeFraction = when (subtitleSize) {
            "Pequeño" -> 0.040f
            "Grande" -> 0.065f
            "Muy Grande" -> 0.080f
            else -> 0.0533f
        }
        subView.setFractionalTextSize(sizeFraction)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    src: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    fullscreenDefault: Boolean = false,
    ratioSetting: String = "Ajustar",
    skipSilenceSetting: Boolean = false,
    decodeSetting: String = "Automático",
    langSetting: String = "es",
    subtitleColorSetting: String = "Blanco",
    subtitleBgSetting: Boolean = true,
    subtitleSizeSetting: String = "Normal",
    subtitleEnabledSetting: Boolean = false,
    initialPositionSec: Long = 0L,
    drmScheme: String? = null,
    drmLicenseUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
    onProgressUpdate: (positionSec: Long, durationSec: Long) -> Unit = { _, _ -> },
    mediaType: String = "movie",
    seriesTitle: String? = null,
    season: Int? = null,
    episode: Int? = null,
    categoryChannels: List<com.example.motor.db.IptvItem> = emptyList(),
    categoryName: String? = null,
    onSelectChannel: ((com.example.motor.db.IptvItem) -> Unit)? = null,
    stopOnDispose: Boolean = (mediaType != "live")
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // ─── STATE VARIABLES ─────────────────────────────────────────────────────
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val coordinatorState by com.example.motor.player.PlayerRepository.recoveryCoordinator.coordinatorState.collectAsStateWithLifecycle()
    val isRecoveringPlayback = coordinatorState is com.example.motor.player.CoordinatorState.Recovering
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var currentAspectRatio by remember(ratioSetting) { mutableStateOf(ratioSetting) }
    var showAspectMenu by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitlesDialog by remember { mutableStateOf(false) }
    var showDrmDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }
    var showChannelsOverlay by remember { mutableStateOf(false) }

    var activeDrmScheme by remember(drmScheme) { mutableStateOf(drmScheme) }
    var activeDrmLicenseUrl by remember(drmLicenseUrl) { mutableStateOf(drmLicenseUrl) }

    var multipleAudioAvailable by remember { mutableStateOf(false) }
    var showAudioTip by remember { mutableStateOf(false) }

    val externalSubtitles by playerViewModel.externalSubtitles.collectAsStateWithLifecycle()
    val subtitleTracks by playerViewModel.subtitleTracks.collectAsStateWithLifecycle()
    val isSearchingSubtitles by playerViewModel.isSearchingSubtitles.collectAsStateWithLifecycle()
    val subtitleSearchError by playerViewModel.subtitleSearchError.collectAsStateWithLifecycle()
    val downloadingSubtitleId by playerViewModel.downloadingSubtitleId.collectAsStateWithLifecycle()
    val selectedExternalSubtitle by playerViewModel.selectedExternalSubtitle.collectAsStateWithLifecycle()
    var isFirstLoad by remember(src) { mutableStateOf(true) }
    var hasHandledCompletion by remember(src) { mutableStateOf(false) }

    val activeCues by playerViewModel.activeSubtitleCues.collectAsStateWithLifecycle()
    val subtitleOffsetMs by playerViewModel.subtitleOffsetMs.collectAsStateWithLifecycle()
    val isSubtitleVisible by playerViewModel.isSubtitleVisible.collectAsStateWithLifecycle()
    var currentSubtitleText by remember { mutableStateOf<String?>(null) }
    var selectedSubIdForTranslation by remember { mutableStateOf<String?>(null) }
    val subtitleNotificationMessage by playerViewModel.subtitleNotification.collectAsStateWithLifecycle()
    val isSubtitleTranslating by playerViewModel.isTranslatingSubtitle.collectAsStateWithLifecycle()
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isPlaying, activeCues, subtitleOffsetMs, isSubtitleVisible) {
        if (!isSubtitleVisible || activeCues.isEmpty()) {
            currentSubtitleText = null
            return@LaunchedEffect
        }
        while (true) {
            val currentMs = playerViewModel.getCurrentPositionMs()
            val effectiveMs = currentMs + subtitleOffsetMs
            val matchingCues = activeCues.filter { effectiveMs in it.startMs..it.endMs }
            val newText = if (matchingCues.isEmpty()) {
                null
            } else {
                matchingCues
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("\n")
                    .ifEmpty { null }
            }
            if (currentSubtitleText != newText) {
                currentSubtitleText = newText
            }
            kotlinx.coroutines.delay(80L)
        }
    }

    val playerFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val channelsButtonFocusRequester = remember { FocusRequester() }
    val seekBarFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        playerFocusRequester.requestFocus()
    }

    LaunchedEffect(showControls, showChannelsOverlay, showSpeedMenu, showAspectMenu, lastInteractionTimestamp) {
        if (showControls) {
            if (!showChannelsOverlay && !showSpeedMenu && !showAspectMenu) {
                kotlinx.coroutines.delay(5000L)
                showControls = false
            }
        } else {
            showChannelsOverlay = false
            showSpeedMenu = false
            showAspectMenu = false
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(50)
            try {
                playButtonFocusRequester.requestFocus()
            } catch (_: Exception) {}
        } else {
            try {
                playerFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = true) {
        if (showChannelsOverlay) {
            showChannelsOverlay = false
            try {
                channelsButtonFocusRequester.requestFocus()
            } catch (_: Exception) {}
        } else if (showAudioDialog) {
            showAudioDialog = false
        } else if (showSubtitlesDialog) {
            showSubtitlesDialog = false
        } else if (showDrmDialog) {
            showDrmDialog = false
        } else if (showCastDialog) {
            showCastDialog = false
        } else if (showSpeedMenu) {
            showSpeedMenu = false
        } else if (showControls) {
            showControls = false
        } else {
            onProgressUpdate(currentPosition, duration)
            onBack()
        }
    }

    LaunchedEffect(src, mediaType, title, seriesTitle, season, episode) {
        if (mediaType == "movie" || mediaType == "series") {
            val isSeries = (mediaType == "series")
            val effectiveSeason = if (isSeries) (season ?: com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)?.season ?: 1) else null
            val effectiveEpisode = if (isSeries) (episode ?: com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)?.episode ?: 1) else null
            val effectiveTitle: String = if (isSeries) {
                val base = if (!seriesTitle.isNullOrBlank()) seriesTitle else title
                val cleanSeries = com.example.motor.tmdb.TmdbService.cleanSeriesName(base)
                if (cleanSeries.isNotBlank()) cleanSeries else base
            } else {
                val cleanMovie = com.example.motor.tmdb.TmdbService.cleanTitleAndExtractYear(title).title
                if (cleanMovie.isNotBlank()) cleanMovie else title
            }
            playerViewModel.autoSearchSubtitlesIfNeeded(
                title = effectiveTitle,
                isSeries = isSeries,
                season = effectiveSeason,
                episode = effectiveEpisode,
                videoUrl = src,
                preferredLanguage = langSetting,
                headers = headers
            )
        }
    }

    LaunchedEffect(showSubtitlesDialog) {
        if (showSubtitlesDialog) {
            playerViewModel.refreshTracks()
        }
    }

    LaunchedEffect(multipleAudioAvailable) {
        if (multipleAudioAvailable) {
            showAudioTip = true
            delay(6000L) // Show the tip for 6 seconds
            showAudioTip = false
        }
    }

    LaunchedEffect(skipSilenceSetting, langSetting, subtitleEnabledSetting) {
        playerViewModel.updateSettings(
            skipSilenceSetting = skipSilenceSetting,
            langSetting = langSetting,
            subtitlesEnabled = subtitleEnabledSetting && (playerViewModel.selectedExternalSubtitle.value == null)
        )
    }

    // Keep screen awake while playing in fullscreen or normal mode
    val activity = context as? android.app.Activity
    DisposableEffect(isPlaying) {
        if (isPlaying) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Restore window brightness to system default ONLY when completely exiting the VideoPlayer
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                win.attributes = lp
            }
        }
    }

    // ─── GESTURE CONTROLS (BRIGHTNESS & VOLUME) ───────────────────────────────
    val audioManager = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }
    var playerBoxWidthPx by remember { mutableIntStateOf(0) }
    var playerBoxHeightPx by remember { mutableIntStateOf(0) }

    // Brightness state (0.01f to 1.0f)
    var currentBrightness by remember {
        val initial = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (initial in 0.01f..1.0f) initial else 0.5f)
    }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    var userBrightnessOverridden by remember { mutableStateOf(false) }

    // Volume state (0.0f to 1.0f)
    var currentVolumeFraction by remember {
        val maxV = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
        val curV = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 7
        mutableFloatStateOf((curV.toFloat() / maxOf(1, maxV)).coerceIn(0f, 1f))
    }
    var showVolumeIndicator by remember { mutableStateOf(false) }

    // Gesture tracking variables
    var gestureType by remember { mutableStateOf<Int?>(null) } // 1: Brightness (left), 2: Volume (right)
    var gestureDragStartY by remember { mutableFloatStateOf(0f) }
    var gestureInitialValue by remember { mutableFloatStateOf(0f) }

    // Auto-hide indicators with debounce
    var lastGestureTimestamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastGestureTimestamp) {
        if (lastGestureTimestamp > 0L) {
            delay(1300L)
            showBrightnessIndicator = false
            showVolumeIndicator = false
        }
    }


    // Connect lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    playerViewModel.pause()
                    isPlaying = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Do not auto-play unless user requests it
                    if (userBrightnessOverridden) {
                        activity?.window?.let { win ->
                            val lp = win.attributes
                            lp.screenBrightness = currentBrightness
                            win.attributes = lp
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Set src and DRM changes
    LaunchedEffect(src, activeDrmScheme, activeDrmLicenseUrl) {
        playerViewModel.clearActiveSubtitle()
        errorMsg = null
        val playbackState = playerViewModel.getPlaybackState()
        val isPl = playerViewModel.isPlayerPlaying()
        if (playbackState != Player.STATE_READY && !isPl) {
            isLoading = true
        } else {
            isLoading = false
        }
        val isLive = mediaType == "live" || mediaType == "tv"
        val curMs = playerViewModel.getCurrentPositionMs()
        val startPosMs = if (isLive) {
            0L
        } else if (isFirstLoad) {
            initialPositionSec * 1000
        } else {
            if (curMs > 0) curMs else (currentPosition * 1000)
        }
        isFirstLoad = false

        playerViewModel.playMedia(
            context = context,
            src = src,
            headers = headers,
            drmScheme = activeDrmScheme,
            drmLicenseUrl = activeDrmLicenseUrl,
            initialPosMs = startPosMs,
            subtitlesEnabled = subtitleEnabledSetting,
            isLive = isLive
        )

        if (playerViewModel.getPlaybackState() == Player.STATE_READY || playerViewModel.isPlayerPlaying()) {
            isLoading = false
            val durMs = playerViewModel.getDurationMs()
            if (durMs > 0) {
                duration = durMs / 1000
            }
            val audioTracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            multipleAudioAvailable = audioTracks.size > 1
        }
    }

    // Setup player listener
    DisposableEffect(Unit) {
        playerViewModel.attachPlayer()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isLoading = false
                retryCount = 0
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isLoading = false
                    retryCount = 0
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        isLoading = true
                    }
                    Player.STATE_READY -> {
                        isLoading = false
                        retryCount = 0
                        duration = playerViewModel.getDurationMs() / 1000
                        val audioTracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                        multipleAudioAvailable = audioTracks.size > 1
                    }
                    Player.STATE_ENDED -> {
                        val curSec = maxOf(currentPosition, playerViewModel.getCurrentPositionMs() / 1000)
                        val curDur = maxOf(duration, playerViewModel.getDurationMs() / 1000)
                        val isLiveStream = (mediaType == "live" || mediaType == "tv" || curDur <= 0)
                        val isPremature = isLiveStream || (curDur > 0 && curSec < (curDur - 20) && curSec < (curDur * 0.95f))
                        if (isPremature) {
                            Log.w("VideoPlayer", "Premature STATE_ENDED observed in VideoPlayer (isLive=$isLiveStream, curPos=${curSec}s, dur=${curDur}s). Waiting for coordinator auto-recovery...")
                            isLoading = true
                        } else {
                            isPlaying = false
                            if (curDur > 0) {
                                onProgressUpdate(curDur, curDur)
                            }
                            if (!isLiveStream) {
                                if (!hasHandledCompletion) {
                                    hasHandledCompletion = true
                                    Log.i("VideoPlayer", "Playback completed naturally for $mediaType ($title). Returning to previous screen.")
                                    scope.launch {
                                        kotlinx.coroutines.delay(300L)
                                        onBack()
                                    }
                                }
                            } else {
                                showControls = true
                            }
                        }
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Central coordinator in PlayerRepository handles recovery or permanent failure
                Log.w("VideoPlayer", "Playback error observed in VideoPlayer: ${error.message}. Central coordinator is managing recovery.")
            }
        }
        playerViewModel.addListener(listener)
        // Immediately sync state from existing player
        isPlaying = playerViewModel.isPlayerPlaying()
        if (playerViewModel.getPlaybackState() == Player.STATE_READY || playerViewModel.isPlayerPlaying()) {
            isLoading = false
            duration = playerViewModel.getDurationMs() / 1000
            val audioTracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            multipleAudioAvailable = audioTracks.size > 1
        } else if (playerViewModel.getPlaybackState() == Player.STATE_BUFFERING) {
            isLoading = true
        }

        onDispose {
            playerViewModel.clearActiveSubtitle()
            playerViewModel.removeListener(listener)
            playerViewModel.detachPlayer()
            if (stopOnDispose) {
                playerViewModel.stop()
            }
        }
    }

    // Periodical progress tracking
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = playerViewModel.getCurrentPositionMs() / 1000
            if (duration > 0) {
                onProgressUpdate(currentPosition, duration)
            }
            delay(1000L)
        }
    }

    // Observe centralized recovery state to reflect loading/error states reactively in the UI
    LaunchedEffect(coordinatorState) {
        when (val st = coordinatorState) {
            is com.example.motor.player.CoordinatorState.Recovering -> {
                isLoading = true
                errorMsg = null
            }
            is com.example.motor.player.CoordinatorState.PermanentFailure -> {
                isLoading = false
                errorMsg = st.message
            }
            is com.example.motor.player.CoordinatorState.Stable -> {
                isLoading = false
                errorMsg = null
            }
            is com.example.motor.player.CoordinatorState.Idle -> {}
        }
    }


    val aspectResizeMode = remember(currentAspectRatio) {
        when (currentAspectRatio) {
            "Rellenar" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            "16:9" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val isSelectKey = when (keyEvent.key) {
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.ButtonA,
                    Key.Spacebar -> true
                    else -> when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                        android.view.KeyEvent.KEYCODE_BUTTON_A,
                        android.view.KeyEvent.KEYCODE_SPACE -> true
                        else -> false
                    }
                }

                val isMediaPlayPause = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_HEADSETHOOK -> true
                    else -> false
                }
                val isMediaPlay = keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                val isMediaPause = keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                val isMediaStop = keyCode == android.view.KeyEvent.KEYCODE_MEDIA_STOP
                val isRewind = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
                    else -> false
                }
                val isFastForward = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> true
                    else -> false
                }
                val isDpadUpOrDown = when (keyEvent.key) {
                    Key.DirectionUp, Key.DirectionDown -> true
                    else -> when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_MENU,
                        android.view.KeyEvent.KEYCODE_INFO -> true
                        else -> false
                    }
                }
                val isDpadLeft = keyEvent.key == Key.DirectionLeft || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                val isDpadRight = keyEvent.key == Key.DirectionRight || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                val isBackKey = keyEvent.key == Key.Back ||
                        keyEvent.key == Key.Escape ||
                        keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                        keyCode == android.view.KeyEvent.KEYCODE_ESCAPE

                if (isBackKey) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        if (showChannelsOverlay) {
                            showChannelsOverlay = false
                        } else if (showAudioDialog || showSubtitlesDialog || showDrmDialog || showCastDialog || showSpeedMenu) {
                            showAudioDialog = false
                            showSubtitlesDialog = false
                            showDrmDialog = false
                            showCastDialog = false
                            showSpeedMenu = false
                        } else if (showControls) {
                            showControls = false
                        } else {
                            onProgressUpdate(currentPosition, duration)
                            onBack()
                        }
                    }
                    return@onPreviewKeyEvent true
                }

                if (!showControls) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        val isLive = mediaType == "live" || mediaType == "tv"
                        if (isSelectKey) {
                            if (isLive && categoryChannels.isNotEmpty() && onSelectChannel != null) {
                                showChannelsOverlay = true
                            } else {
                                showControls = true
                            }
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isDpadUpOrDown) {
                            if (isLive && categoryChannels.isNotEmpty() && onSelectChannel != null) {
                                val isUp = keyEvent.key == Key.DirectionUp ||
                                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                                        keyCode == android.view.KeyEvent.KEYCODE_CHANNEL_UP
                                val curIdx = categoryChannels.indexOfFirst {
                                    it.name.equals(title, ignoreCase = true) || (it.url != null && it.url == src)
                                }
                                val nextIdx = if (curIdx >= 0) {
                                    if (isUp) {
                                        if (curIdx > 0) curIdx - 1 else categoryChannels.size - 1
                                    } else {
                                        if (curIdx < categoryChannels.size - 1) curIdx + 1 else 0
                                    }
                                } else 0
                                val targetChannel = categoryChannels[nextIdx]
                                onSelectChannel(targetChannel)
                            } else {
                                showControls = true
                            }
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isDpadLeft && !isLive) {
                            playerViewModel.seekBy(-10000L)
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isDpadRight && !isLive) {
                            playerViewModel.seekBy(10000L)
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isMediaPlayPause) {
                            if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isMediaPlay) {
                            playerViewModel.resume()
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isMediaPause) {
                            playerViewModel.pause()
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isMediaStop) {
                            onProgressUpdate(currentPosition, duration)
                            onBack()
                            return@onPreviewKeyEvent true
                        } else if (isRewind && !isLive) {
                            playerViewModel.seekBy(-10000L)
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isFastForward && !isLive) {
                            playerViewModel.seekBy(10000L)
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        }
                    } else if (keyEvent.type == KeyEventType.KeyUp) {
                        if (isSelectKey || isDpadUpOrDown || isDpadLeft || isDpadRight || isMediaPlayPause || isMediaPlay || isMediaPause || isMediaStop || isRewind || isFastForward) {
                            return@onPreviewKeyEvent true
                        }
                    }
                } else {
                    lastInteractionTimestamp = System.currentTimeMillis()
                }
                false
            }
            .onSizeChanged { size ->
                playerBoxWidthPx = size.width
                playerBoxHeightPx = size.height
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = playerBoxWidthPx.toFloat().coerceAtLeast(1f)
                        val h = playerBoxHeightPx.toFloat().coerceAtLeast(1f)
                        if (offset.x < w * 0.40f) {
                            // Left side: Brightness
                            gestureType = 1
                            gestureDragStartY = offset.y
                            // Fetch current brightness or fallback
                            val cur = activity?.window?.attributes?.screenBrightness ?: -1f
                            gestureInitialValue = if (cur in 0.01f..1.0f) cur else currentBrightness
                            showBrightnessIndicator = true
                            showVolumeIndicator = false
                            lastGestureTimestamp = System.currentTimeMillis()
                        } else if (offset.x > w * 0.60f) {
                            // Right side: Volume
                            gestureType = 2
                            gestureDragStartY = offset.y
                            val maxV = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
                            val curV = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 7
                            val frac = (curV.toFloat() / maxOf(1, maxV)).coerceIn(0f, 1f)
                            gestureInitialValue = frac
                            currentVolumeFraction = frac
                            showVolumeIndicator = true
                            showBrightnessIndicator = false
                            lastGestureTimestamp = System.currentTimeMillis()
                        } else {
                            gestureType = null
                        }
                    },
                    onDrag = { change, _ ->
                        val h = playerBoxHeightPx.toFloat().coerceAtLeast(1f)
                        val deltaY = gestureDragStartY - change.position.y // Moving up is positive delta
                        val deltaFraction = deltaY / (h * 0.70f)

                        when (gestureType) {
                            1 -> {
                                change.consume()
                                val newBrightness = (gestureInitialValue + deltaFraction).coerceIn(0.01f, 1.0f)
                                currentBrightness = newBrightness
                                userBrightnessOverridden = true
                                showBrightnessIndicator = true
                                lastGestureTimestamp = System.currentTimeMillis()
                                activity?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = newBrightness
                                    win.attributes = lp
                                }
                            }
                            2 -> {
                                change.consume()
                                val newVolumeFraction = (gestureInitialValue + deltaFraction).coerceIn(0f, 1f)
                                currentVolumeFraction = newVolumeFraction
                                showVolumeIndicator = true
                                lastGestureTimestamp = System.currentTimeMillis()
                                audioManager?.let { am ->
                                    val maxV = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val targetV = kotlin.math.round(newVolumeFraction * maxV).toInt().coerceIn(0, maxV)
                                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetV, 0)
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        gestureType = null
                        lastGestureTimestamp = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        gestureType = null
                        lastGestureTimestamp = System.currentTimeMillis()
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showChannelsOverlay) {
                    showChannelsOverlay = false
                } else if (showAudioDialog || showSubtitlesDialog || showDrmDialog || showCastDialog || showSpeedMenu) {
                    showAudioDialog = false
                    showSubtitlesDialog = false
                    showDrmDialog = false
                    showCastDialog = false
                    showSpeedMenu = false
                } else {
                    showControls = !showControls
                    if (showControls) {
                        lastInteractionTimestamp = System.currentTimeMillis()
                    }
                }
            }
    ) {
        // Native Player View
        val hasExternalSubtitleActive = selectedExternalSubtitle != null || activeCues.isNotEmpty()
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.player_view_texture, null) as PlayerView).apply {
                    playerViewModel.attachPlayerView(this)
                    this.useController = false
                    this.resizeMode = aspectResizeMode
                    this.keepScreenOn = true
                    this.isFocusable = false
                    this.isFocusableInTouchMode = false
                    this.clipChildren = true
                    this.clipToPadding = true
                    this.subtitleView?.visibility = if (hasExternalSubtitleActive) android.view.View.GONE else android.view.View.VISIBLE
                    applySubtitleStyles(this, subtitleColorSetting, subtitleBgSetting, subtitleSizeSetting)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerViewModel.attachPlayerView(playerView)
                playerView.resizeMode = aspectResizeMode
                playerView.keepScreenOn = true
                playerView.subtitleView?.visibility = if (hasExternalSubtitleActive) android.view.View.GONE else android.view.View.VISIBLE
                applySubtitleStyles(playerView, subtitleColorSetting, subtitleBgSetting, subtitleSizeSetting)
            },
            onRelease = { playerView ->
                playerViewModel.detachPlayerView(playerView)
            },
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )

        // Seamless Compose Subtitle Overlay
        if (!currentSubtitleText.isNullOrBlank() && !showSubtitlesDialog) {
            val subFgColor = when (subtitleColorSetting.lowercase()) {
                "amarillo" -> Color(0xFFFFEB3B)
                "verde" -> Color(0xFF4CAF50)
                "cyan", "celeste / cyan", "celeste" -> Color(0xFF00E5FF)
                else -> Color.White
            }
            val subFontSize = when (subtitleSizeSetting) {
                "Pequeño" -> 16.sp
                "Grande" -> 24.sp
                "Muy Grande" -> 28.sp
                else -> 20.sp
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = if (showControls) 105.dp else 45.dp,
                        start = 32.dp,
                        end = 32.dp
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (subtitleBgSetting) Color.Black.copy(alpha = 0.75f) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = currentSubtitleText!!,
                        color = subFgColor,
                        fontSize = subFontSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black,
                                blurRadius = 4f
                            )
                        )
                    )
                }
            }
        }

        // Orbital streaming loader (Opción 2) with smooth fade to prevent abrupt flicker
        AnimatedVisibility(
            visible = (isLoading || isRecoveringPlayback) && errorMsg == null,
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(durationMillis = 260)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            OrbitalStreamingLoader(
                size = 68.dp,
                accentColor = Color(0xFFB07FEE),
                highlightColor = Color(0xFFEBD9FF),
                secondaryColor = Color.White,
                contentDesc = if (isRecoveringPlayback) "Reconectando canal" else "Cargando reproducción"
            )
        }

        // Multiple Audio Track Tip Pill
        AnimatedVisibility(
            visible = showAudioTip && !isLoading && errorMsg == null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .border(1.dp, Color(0xFFB07FEE).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = Color(0xFFB07FEE),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (langSetting == "es") "Múltiples pistas de audio disponibles" else "Multiple audio tracks available",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Subtitle Translation Status HUD Pill
        AnimatedVisibility(
            visible = subtitleNotificationMessage != null && errorMsg == null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF181824).copy(alpha = 0.94f))
                    .border(1.2.dp, Color(0xFFB07FEE), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSubtitleTranslating) {
                    CircularProgressIndicator(
                        color = Color(0xFFB07FEE),
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = subtitleNotificationMessage ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Error message HUD
        if (errorMsg != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMsg ?: "Error de reproducción",
                    color = Color.Red,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    TvButton(
                        onClick = {
                            errorMsg = null
                            isLoading = true
                            retryCount = 0
                            playerViewModel.retryPlayback(context)
                        },
                        containerColor = Color(0xFF9B5FDE),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Text("Reintentar", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                    }
                    TvButton(
                        onClick = onBack,
                        containerColor = Color.DarkGray,
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Text("Volver", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                    }
                }
            }
        }

        // CUSTOM HUD OVERLAYS
        AnimatedVisibility(
            visible = showControls && errorMsg == null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            // TOP BAR HUD
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        lastInteractionTimestamp = System.currentTimeMillis()
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvPlayerIconButton(
                    onClick = {
                        onProgressUpdate(currentPosition, duration)
                        onBack()
                    },
                    icon = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    focusRequester = backButtonFocusRequester,
                    onRight = {
                        if (categoryChannels.isNotEmpty() && onSelectChannel != null) {
                            try {
                                channelsButtonFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    onDown = {
                        try {
                            if (mediaType == "live" || duration <= 0) {
                                playButtonFocusRequester.requestFocus()
                            } else {
                                seekBarFocusRequester.requestFocus()
                            }
                        } catch (_: Exception) {}
                    },
                    onBack = {
                        showControls = false
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF9B5FDE).copy(alpha = 0.20f))
                        .border(0.8.dp, Color(0xFFB07FEE).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text("MEDIA3", color = Color(0xFFB07FEE), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                if (categoryChannels.isNotEmpty() && onSelectChannel != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    var isChannelsBtnFocused by remember { mutableStateOf(false) }
                    val channelsInteractionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .focusRequester(channelsButtonFocusRequester)
                            .onFocusChanged { isChannelsBtnFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { keyEvent ->
                                val keyCode = keyEvent.nativeKeyEvent.keyCode
                                val isDown = keyEvent.key == Key.DirectionDown || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                val isLeft = keyEvent.key == Key.DirectionLeft || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                val isSelect = keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter ||
                                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                                val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape ||
                                        keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                                        keyCode == android.view.KeyEvent.KEYCODE_ESCAPE

                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        isDown -> {
                                            try {
                                                if (mediaType == "live" || duration <= 0) {
                                                    playButtonFocusRequester.requestFocus()
                                                } else {
                                                    seekBarFocusRequester.requestFocus()
                                                }
                                            } catch (_: Exception) {}
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isLeft -> {
                                            try {
                                                backButtonFocusRequester.requestFocus()
                                            } catch (_: Exception) {}
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isSelect -> {
                                            showChannelsOverlay = !showChannelsOverlay
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isBack -> {
                                            showControls = false
                                            true
                                        }
                                        else -> false
                                    }
                                } else if (keyEvent.type == KeyEventType.KeyUp) {
                                    isDown || isLeft || isSelect || isBack
                                } else {
                                    false
                                }
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isChannelsBtnFocused) Color(0xFF9B5FDE).copy(alpha = 0.55f) else if (showChannelsOverlay) Color(0xFF9B5FDE).copy(alpha = 0.38f) else Color(0xFF9B5FDE).copy(alpha = 0.20f)
                            )
                            .border(
                                width = if (isChannelsBtnFocused) 2.dp else 0.8.dp,
                                color = if (isChannelsBtnFocused) Color(0xFFD4B2FF) else if (showChannelsOverlay) Color(0xFFB07FEE).copy(alpha = 0.80f) else Color(0xFFB07FEE).copy(alpha = 0.35f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(
                                interactionSource = channelsInteractionSource,
                                indication = ripple(bounded = true),
                                onClick = {
                                    showChannelsOverlay = !showChannelsOverlay
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = "Ver canales",
                                tint = if (isChannelsBtnFocused) Color.White else Color(0xFFB07FEE),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CANALES",
                                color = if (isChannelsBtnFocused) Color.White else Color(0xFFB07FEE),
                                fontSize = 11.5.sp,
                                fontWeight = if (isChannelsBtnFocused) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }

        // BOTTOM HUD PANEL
        AnimatedVisibility(
            visible = showControls && errorMsg == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.72f)
                            )
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        lastInteractionTimestamp = System.currentTimeMillis()
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
            ) {
                // Seek Bar Container with TV D-Pad Focus & Visual Feedback
                var isSeekBarFocused by remember { mutableStateOf(false) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .background(
                            if (isSeekBarFocused) Color(0xFFB07FEE).copy(alpha = 0.18f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (isSeekBarFocused) 1.5.dp else 0.dp,
                            color = if (isSeekBarFocused) Color(0xFFD4B2FF) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        color = if (isSeekBarFocused) TV_FOCUSED_TEXT else Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = if (isSeekBarFocused) FontWeight.Bold else FontWeight.Medium
                    )
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { pos ->
                            currentPosition = pos.toLong()
                            playerViewModel.seekTo(currentPosition * 1000L)
                            lastInteractionTimestamp = System.currentTimeMillis()
                        },
                        valueRange = 0f..if (duration > 0) duration.toFloat() else 1f,
                        colors = SliderDefaults.colors(
                            thumbColor = if (isSeekBarFocused) TV_FOCUSED_TEXT else Color.White,
                            activeTrackColor = if (isSeekBarFocused) Color(0xFFC89DF7) else Color(0xFFB07FEE),
                            inactiveTrackColor = Color.White.copy(alpha = 0.20f)
                        ),
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .size(if (isSeekBarFocused) 16.dp else 10.dp)
                                    .background(if (isSeekBarFocused) TV_FOCUSED_TEXT else Color.White, CircleShape)
                                    .border(
                                        width = if (isSeekBarFocused) 2.dp else 0.8.dp,
                                        color = if (isSeekBarFocused) Color(0xFF5E2B97) else Color(0xFFB07FEE),
                                        shape = CircleShape
                                    )
                            )
                        },
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.height(if (isSeekBarFocused) 5.dp else 2.5.dp),
                                thumbTrackGapSize = 0.dp,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = if (isSeekBarFocused) Color(0xFFC89DF7) else Color(0xFFB07FEE),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.20f)
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .height(28.dp)
                            .focusRequester(seekBarFocusRequester)
                            .onFocusChanged { isSeekBarFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { keyEvent ->
                                val keyCode = keyEvent.nativeKeyEvent.keyCode
                                val isLeft = keyEvent.key == Key.DirectionLeft || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                val isRight = keyEvent.key == Key.DirectionRight || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                val isDown = keyEvent.key == Key.DirectionDown || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                val isUp = keyEvent.key == Key.DirectionUp || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                                val isSelect = keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter ||
                                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                                val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape ||
                                        keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                                        keyCode == android.view.KeyEvent.KEYCODE_ESCAPE

                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        isLeft -> {
                                            val step = if (duration > 3600) 15L else 10L
                                            currentPosition = (currentPosition - step).coerceAtLeast(0L)
                                            playerViewModel.seekTo(currentPosition * 1000L)
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isRight -> {
                                            val step = if (duration > 3600) 15L else 10L
                                            currentPosition = (currentPosition + step).coerceAtMost(duration)
                                            playerViewModel.seekTo(currentPosition * 1000L)
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isUp -> {
                                            try {
                                                if (categoryChannels.isNotEmpty() && onSelectChannel != null) {
                                                    channelsButtonFocusRequester.requestFocus()
                                                } else {
                                                    backButtonFocusRequester.requestFocus()
                                                }
                                            } catch (_: Exception) {}
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isDown -> {
                                            try {
                                                playButtonFocusRequester.requestFocus()
                                            } catch (_: Exception) {}
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isSelect -> {
                                            if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                                            lastInteractionTimestamp = System.currentTimeMillis()
                                            true
                                        }
                                        isBack -> {
                                            showControls = false
                                            true
                                        }
                                        else -> false
                                    }
                                } else if (keyEvent.type == KeyEventType.KeyUp) {
                                    isLeft || isRight || isDown || isUp || isSelect || isBack
                                } else {
                                    false
                                }
                            }
                    )
                    Text(
                        text = formatTime(duration),
                        color = if (isSeekBarFocused) TV_FOCUSED_TEXT else Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = if (isSeekBarFocused) FontWeight.Bold else FontWeight.Medium
                    )
                }

                // Callbacks for navigating UP from player control buttons
                val controlsOnUp: () -> Unit = {
                    try {
                        if (categoryChannels.isNotEmpty() && onSelectChannel != null) {
                            if (mediaType == "live" || duration <= 0) {
                                channelsButtonFocusRequester.requestFocus()
                            } else {
                                seekBarFocusRequester.requestFocus()
                            }
                        } else {
                            if (mediaType == "live" || duration <= 0) {
                                backButtonFocusRequester.requestFocus()
                            } else {
                                seekBarFocusRequester.requestFocus()
                            }
                        }
                    } catch (_: Exception) {}
                    lastInteractionTimestamp = System.currentTimeMillis()
                }

                val rightControlsOnUp: () -> Unit = {
                    try {
                        if (categoryChannels.isNotEmpty() && onSelectChannel != null) {
                            channelsButtonFocusRequester.requestFocus()
                        } else {
                            controlsOnUp()
                        }
                    } catch (_: Exception) {}
                    lastInteractionTimestamp = System.currentTimeMillis()
                }

                // Controls row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                ) {
                    // Play/Pause
                    TvPlayerIconButton(
                        onClick = {
                            if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                        },
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        focusRequester = playButtonFocusRequester,
                        onBack = { showControls = false },
                        onUp = controlsOnUp
                    )

                    // Stop button
                    TvPlayerIconButton(
                        onClick = {
                            onProgressUpdate(currentPosition, duration)
                            onBack()
                        },
                        icon = Icons.Default.Stop,
                        contentDescription = "Stop",
                        onBack = { showControls = false },
                        onUp = controlsOnUp
                    )

                    if (mediaType != "live" && mediaType != "tv") {
                        // Rewind 10s
                        TvPlayerIconButton(
                            onClick = {
                                playerViewModel.seekBy(-10000L)
                            },
                            icon = Icons.Default.Replay10,
                            contentDescription = "-10s",
                            onBack = { showControls = false },
                            onUp = controlsOnUp
                        )

                        // Forward 10s
                        TvPlayerIconButton(
                            onClick = {
                                playerViewModel.seekBy(10000L)
                            },
                            icon = Icons.Default.Forward10,
                            contentDescription = "+10s",
                            onBack = { showControls = false },
                            onUp = controlsOnUp
                        )
                    }

                    // Audio Track Language (only if multiple languages are available)
                    if (multipleAudioAvailable) {
                        TvPlayerIconButton(
                            onClick = {
                                showAudioDialog = true
                            },
                            icon = Icons.Default.Language,
                            contentDescription = "Cambiar idioma de audio",
                            onBack = { showControls = false },
                            onUp = rightControlsOnUp
                        )
                    }

                    // Subtitles
                    TvPlayerIconButton(
                        onClick = {
                            showSubtitlesDialog = true
                        },
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = "Subtitle tracks",
                        onBack = { showControls = false },
                        onUp = rightControlsOnUp
                    )

                    // DRM
                    TvPlayerIconButton(
                        onClick = {
                            showDrmDialog = true
                        },
                        icon = Icons.Default.VpnKey,
                        contentDescription = "Configuración DRM (Widevine)",
                        tint = if (!activeDrmLicenseUrl.isNullOrEmpty()) Color(0xFFB07FEE) else Color.White,
                        onBack = { showControls = false },
                        onUp = rightControlsOnUp
                    )

                    // Cast
                    TvPlayerIconButton(
                        onClick = {
                            showCastDialog = true
                        },
                        icon = Icons.Default.Cast,
                        contentDescription = "Transmitir a TV (Cast)",
                        onBack = { showControls = false },
                        onUp = rightControlsOnUp
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // ─── ASPECT RATIO CONTROLLER TRIGGER (GLASS DROPDOWN) ─────────────
                    Box {
                        TvPlayerTextButton(
                            onClick = {
                                showAspectMenu = !showAspectMenu
                                showSpeedMenu = false
                                lastInteractionTimestamp = System.currentTimeMillis()
                            },
                            text = currentAspectRatio,
                            onBack = { showControls = false },
                            onUp = rightControlsOnUp
                        )

                        PlayerGlassDropdownMenu(
                            expanded = showAspectMenu,
                            onDismissRequest = { showAspectMenu = false }
                        ) {
                            listOf("Ajustar", "Rellenar", "16:9", "4:3").forEach { ratio ->
                                val isSelected = currentAspectRatio.equals(ratio, ignoreCase = true)
                                PlayerGlassMenuItem(
                                    text = ratio,
                                    isSelected = isSelected,
                                    onClick = {
                                        currentAspectRatio = ratio
                                        showAspectMenu = false
                                        lastInteractionTimestamp = System.currentTimeMillis()
                                        android.widget.Toast.makeText(
                                            context,
                                            "Aspecto: $ratio",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // ─── SPEED CONTROLLER TRIGGER (GLASS DROPDOWN) ─────────────────────
                    Box {
                        TvPlayerTextButton(
                            onClick = {
                                showSpeedMenu = !showSpeedMenu
                                showAspectMenu = false
                                lastInteractionTimestamp = System.currentTimeMillis()
                            },
                            text = "${playbackSpeed}x",
                            onBack = { showControls = false },
                            onUp = rightControlsOnUp
                        )

                        PlayerGlassDropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false }
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                                val isSelected = (playbackSpeed == s)
                                PlayerGlassMenuItem(
                                    text = "${s}x",
                                    isSelected = isSelected,
                                    onClick = {
                                        playbackSpeed = s
                                        playerViewModel.setPlaybackSpeed(s)
                                        showSpeedMenu = false
                                        lastInteractionTimestamp = System.currentTimeMillis()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ─── GESTURE INDICATORS (BRIGHTNESS: LEFT, VOLUME: RIGHT) ─────────────
        // Brightness indicator (Left side)
        AnimatedVisibility(
            visible = showBrightnessIndicator,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BrightnessMedium,
                    contentDescription = "Brillo",
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(3.dp))
                val brightnessPct = (currentBrightness * 100).toInt().coerceIn(0, 100)
                Text(
                    text = "$brightnessPct%",
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Vertical progress bar (finer and discreet, fills from bottom to top)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(96.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(currentBrightness.coerceIn(0.01f, 1f))
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFFD54F))
                    )
                }
            }
        }

        // Volume indicator (Right side)
        AnimatedVisibility(
            visible = showVolumeIndicator,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                val volPct = (currentVolumeFraction * 100).toInt().coerceIn(0, 100)
                val volIcon = when {
                    volPct == 0 -> Icons.Default.VolumeOff
                    volPct < 50 -> Icons.Default.VolumeDown
                    else -> Icons.Default.VolumeUp
                }
                Icon(
                    imageVector = volIcon,
                    contentDescription = "Volumen",
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "$volPct%",
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Vertical progress bar (finer and discreet, fills from bottom to top)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(96.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(currentVolumeFraction.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF64B5F6))
                    )
                }
            }
        }

        // ─── OVERLAY LATERAL DE CANALES (SEMISTRANSPARENTE SOBRE EL REPRODUCTOR) ───
        if (categoryChannels.isNotEmpty() && onSelectChannel != null) {
            PlayerChannelsOverlay(
                visible = showChannelsOverlay,
                categoryName = categoryName ?: "",
                channels = categoryChannels,
                currentChannelName = title,
                accentColor = Color(0xFFB07FEE),
                onSelectChannel = { channel ->
                    lastInteractionTimestamp = System.currentTimeMillis()
                    onSelectChannel(channel)
                    showChannelsOverlay = false
                },
                onDismiss = {
                    showChannelsOverlay = false
                    lastInteractionTimestamp = System.currentTimeMillis()
                    try {
                        channelsButtonFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
            )
        }
    }

    // ─── AUDIO TRACK SELECTION & BOOST DIALOG ─────────────────────────────
    AudioTrackDialog(
        visible = showAudioDialog,
        playerViewModel = playerViewModel,
        onDismiss = { showAudioDialog = false }
    )

    // ─── SUBTITLES TRACK SELECTION DIALOG ────────────────────────────────────
    SubtitlePickerDialog(
        visible = showSubtitlesDialog,
        playerViewModel = playerViewModel,
        title = title,
        mediaType = mediaType,
        seriesTitle = seriesTitle,
        season = season,
        episode = episode,
        src = src,
        langSetting = langSetting,
        headers = headers,
        onDismiss = { showSubtitlesDialog = false },
        onCloseControls = { showControls = false }
    )

    // ─── DRM CONFIGURATION DIALOG ───────────────────────────────────────
    if (showDrmDialog) {
        var tempLicenseUrl by remember { mutableStateOf(activeDrmLicenseUrl ?: "") }
        
        AlertDialog(
            onDismissRequest = { showDrmDialog = false },
            title = { Text("Configuración DRM Widevine", color = Color.White) },
            containerColor = Color(0xFF1E1E28),
            confirmButton = {
                TvButton(
                    onClick = {
                        activeDrmLicenseUrl = tempLicenseUrl.trim().ifEmpty { null }
                        showDrmDialog = false
                    },
                    containerColor = Color(0xFFB07FEE),
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Text("Aplicar y Reiniciar", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                }
            },
            dismissButton = {
                TvButton(
                    onClick = { showDrmDialog = false },
                    containerColor = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { isFocused ->
                    Text("Cancelar", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Soporte Nativo de DRM Widevine L3/L1. Introduce o modifica la dirección del servidor de licencias para desencriptar el stream protegido.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    OutlinedTextField(
                        value = tempLicenseUrl,
                        onValueChange = { tempLicenseUrl = it },
                        label = { Text("URL de Licencia (Widevine)", color = Color.Gray) },
                        placeholder = { Text("https://lic.example.com/widevine", color = Color.DarkGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFB07FEE),
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color(0xFFB07FEE),
                            unfocusedLabelColor = Color.Gray
                        ),
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (tempLicenseUrl.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TvButton(
                                onClick = { tempLicenseUrl = "" },
                                containerColor = Color(0xFFD32F2F).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) { isFocused ->
                                Text("Limpiar URL", color = if (isFocused) TV_FOCUSED_TEXT else Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        )
    }

    // ─── TRANSMISIÓN A TV / CHROMCAST DIALOG ────────────────────────────────
    if (showCastDialog) {
        val context = LocalContext.current
        val castManager = remember { com.example.cast.TvCastManager.getInstance() }
        val discoveredDevices by castManager.discoveredDevices.collectAsStateWithLifecycle()
        val savedDevices by castManager.savedDevices.collectAsStateWithLifecycle()
        val isCasting by castManager.isCasting.collectAsStateWithLifecycle()
        val currentCastingDevice by castManager.currentCastingDevice.collectAsStateWithLifecycle()
        val isScanning by castManager.isScanning.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var connectingDeviceId by remember { mutableStateOf<String?>(null) }
        var showManualIpInput by remember { mutableStateOf(false) }
        var manualIpText by remember { mutableStateOf("") }
        var rememberThisIp by remember { mutableStateOf(true) }
        var isConnectingDirectIp by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            castManager.startDiscovery(context)
            onDispose {
                castManager.stopDiscovery()
            }
        }

        val castTitle = when (langSetting) {
            "ro" -> "Transmitere Chromecast / TV"
            "en" -> "Cast to Chromecast / TV"
            else -> "Transmitir a Chromecast / TV"
        }
        val closeBtn = when (langSetting) {
            "ro" -> "Închide"
            "en" -> "Close"
            else -> "Cerrar"
        }

        AlertDialog(
            onDismissRequest = {
                castManager.stopDiscovery()
                showCastDialog = false
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cast,
                            contentDescription = "Cast",
                            tint = Color(0xFFB07FEE),
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(castTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                castManager.stopDiscovery()
                                castManager.startDiscovery(context)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Re-escanear",
                                tint = Color(0xFFB07FEE),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (isScanning) {
                            Spacer(modifier = Modifier.width(6.dp))
                            CircularProgressIndicator(
                                color = Color(0xFFB07FEE),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            containerColor = Color(0xFF1E1E28),
            confirmButton = {
                TvButton(
                    onClick = {
                        castManager.stopDiscovery()
                        showCastDialog = false
                    },
                    containerColor = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { isFocused ->
                    Text(closeBtn, color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                }
            },
            text = {
                val allDevices = remember(discoveredDevices, savedDevices) {
                    val list = mutableListOf<com.example.cast.TvCastDevice>()
                    // Add saved devices first
                    savedDevices.forEach { dev ->
                        list.add(dev.copy(isSaved = true))
                    }
                    // Add discovered devices that are not already in list
                    discoveredDevices.forEach { dev ->
                        val alreadyIn = list.any { it.id == dev.id || (!dev.ipAddress.isNullOrBlank() && it.ipAddress == dev.ipAddress) }
                        if (!alreadyIn) {
                            list.add(dev)
                        }
                    }
                    list
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ─── SESIÓN ACTIVA DE TRANSMISIÓN ──────────────────────────
                    if (isCasting && currentCastingDevice != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3B24)),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.CastConnected,
                                        contentDescription = null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (langSetting == "es") "Transmitiendo en tu TV:" else "Casting to:",
                                            color = Color(0xFFA5D6A7),
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = currentCastingDevice?.name ?: "Smart TV",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        castManager.stopCasting()
                                        android.widget.Toast.makeText(
                                            context,
                                            if (langSetting == "es") "Transmisión detenida" else "Casting stopped",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (langSetting == "es") "Detener" else "Stop",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // ─── ADAPTIVE LAYOUT (2 COLUMNS IN LANDSCAPE, 1 COLUMN IN PORTRAIT) ───
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val isLandscapeWide = maxWidth > 460.dp

                        if (isLandscapeWide) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // ─── COLUMNA IZQUIERDA: LISTA DE DISPOSITIVOS DETECTADOS ───
                                Column(
                                    modifier = Modifier
                                        .weight(1.15f)
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (langSetting == "es")
                                            "📺 Dispositivos en red local (${allDevices.size}):"
                                        else if (langSetting == "ro")
                                            "📺 Dispozitive în rețea (${allDevices.size}):"
                                        else
                                            "📺 Devices on local network (${allDevices.size}):",
                                        color = Color(0xFFE0E0E0),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )

                                    if (allDevices.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(130.dp)
                                                .background(Color(0xFF282835), RoundedCornerShape(10.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(12.dp)
                                            ) {
                                                if (isScanning) {
                                                    CircularProgressIndicator(
                                                        color = Color(0xFFB07FEE),
                                                        strokeWidth = 2.5.dp,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = if (langSetting == "es")
                                                            "Buscando Chromecast, Android TV y Smart TV..."
                                                        else if (langSetting == "ro")
                                                            "Căutare Chromecast, Android TV și Smart TV..."
                                                        else
                                                            "Scanning for Chromecast, Android TV & Smart TV...",
                                                        color = Color.LightGray,
                                                        fontSize = 11.sp,
                                                        textAlign = TextAlign.Center
                                                    )
                                                } else {
                                                    Text(
                                                        text = if (langSetting == "es")
                                                            "No se detectaron dispositivos automáticamente.\nPuedes conectar directamente por IP a la derecha."
                                                        else if (langSetting == "ro")
                                                            "Nu s-au detectat dispozitive automat.\nPuteți conecta direct prin IP în dreapta."
                                                        else
                                                            "No devices found automatically.\nYou can connect directly via IP on the right.",
                                                        color = Color.LightGray,
                                                        fontSize = 11.sp,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        allDevices.forEach { dev ->
                                            val isConnectingThis = connectingDeviceId == dev.id
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .tvClickable(
                                                        enabled = connectingDeviceId == null && !isConnectingDirectIp,
                                                        shape = RoundedCornerShape(10.dp)
                                                    ) {
                                                        connectingDeviceId = dev.id
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            if (langSetting == "es") "Conectando con ${dev.name}..." else "Connecting to ${dev.name}...",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                        scope.launch {
                                                            val ok = castManager.castToDevice(context, dev, src, title, headers, mediaType)
                                                            connectingDeviceId = null
                                                            if (ok) {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    if (langSetting == "es") "Transmitiendo a ${dev.name}" else "Streaming to ${dev.name}",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                                showCastDialog = false
                                                            } else {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    if (langSetting == "es") "No se pudo conectar a ${dev.name}. Verifica que la TV esté encendida."
                                                                    else "Could not connect to ${dev.name}. Check TV status.",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        }
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isConnectingThis) Color(0xFF38384A) else if (dev.isSaved) Color(0xFF2E2344) else Color(0xFF282835)
                                                ),
                                                shape = RoundedCornerShape(10.dp),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (dev.isSaved) Color(0xFFFFD54F).copy(alpha = 0.6f) else Color(0xFFB07FEE).copy(alpha = 0.3f)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = when (dev.type) {
                                                            com.example.cast.TvCastType.CHROMECAST -> Icons.Default.CastConnected
                                                            else -> Icons.Default.Tv
                                                        },
                                                        contentDescription = null,
                                                        tint = if (dev.type == com.example.cast.TvCastType.DLNA_TV) Color(0xFF00E676) else if (dev.isSaved) Color(0xFFFFD54F) else Color(0xFFB07FEE),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(dev.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                            if (dev.isSaved) {
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("⭐", fontSize = 10.sp)
                                                            }
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = if (dev.type == com.example.cast.TvCastType.DLNA_TV) Color(0xFF1B5E20) else Color(0xFF0D47A1),
                                                                modifier = Modifier.padding(top = 2.dp, end = 6.dp)
                                                            ) {
                                                                Text(
                                                                    text = if (dev.type == com.example.cast.TvCastType.DLNA_TV) "DLNA TV" else "Cast",
                                                                    color = Color.White,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                            Text(
                                                                text = dev.ipAddress ?: "Wi-Fi",
                                                                color = Color.LightGray,
                                                                fontSize = 10.sp
                                                            )
                                                        }
                                                    }
                                                    if (isConnectingThis) {
                                                        CircularProgressIndicator(
                                                            color = Color(0xFF4CAF50),
                                                            strokeWidth = 2.dp,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    } else {
                                                        // Explicit DLNA button if device has IP
                                                        if (!dev.ipAddress.isNullOrBlank() && dev.type != com.example.cast.TvCastType.DLNA_TV) {
                                                            Button(
                                                                onClick = {
                                                                    connectingDeviceId = dev.id
                                                                    scope.launch {
                                                                        val ok = castManager.castToDevice(context, dev, src, title, headers, mediaType, forceDlna = true)
                                                                        connectingDeviceId = null
                                                                        if (ok) {
                                                                            android.widget.Toast.makeText(context, "Transmitiendo vía DLNA a ${dev.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                                            showCastDialog = false
                                                                        } else {
                                                                            android.widget.Toast.makeText(context, "No se detectó receptor DLNA en ${dev.name}. Toca la tarjeta para conectar por Google Cast.", android.widget.Toast.LENGTH_LONG).show()
                                                                        }
                                                                    }
                                                                },
                                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF).copy(alpha = 0.8f)),
                                                                shape = RoundedCornerShape(6.dp),
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                                modifier = Modifier.padding(end = 6.dp)
                                                            ) {
                                                                Text("DLNA", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = "Transmitir",
                                                            tint = if (dev.type == com.example.cast.TvCastType.DLNA_TV) Color(0xFF00E676) else Color(0xFF4CAF50),
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // ─── COLUMNA DERECHA: CONECTAR POR IP DIRECTA Y VLC ───
                                Column(
                                    modifier = Modifier
                                        .weight(0.95f)
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF22283A)),
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.4f))
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(imageVector = Icons.Default.Router, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (langSetting == "es") "Conectar por IP Directa" else "Direct IP Connection",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            OutlinedTextField(
                                                value = manualIpText,
                                                onValueChange = { manualIpText = it.trim() },
                                                modifier = Modifier.fillMaxWidth(),
                                                placeholder = { Text("Ej. 192.168.1.150", fontSize = 11.sp, color = Color.Gray) },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White,
                                                    focusedBorderColor = Color(0xFF64B5F6),
                                                    unfocusedBorderColor = Color.Gray
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = rememberThisIp,
                                                    onCheckedChange = { rememberThisIp = it },
                                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF64B5F6))
                                                )
                                                Text(
                                                    text = if (langSetting == "es") "Recordar TV" else "Remember TV",
                                                    color = Color.White,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                // Botón DLNA nativo (Shield, Smart TV)
                                                Button(
                                                    onClick = {
                                                        if (manualIpText.isNotBlank()) {
                                                            isConnectingDirectIp = true
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                if (langSetting == "es") "Conectando por DLNA a $manualIpText..." else "Connecting via DLNA to $manualIpText...",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                            scope.launch {
                                                                val ok = castManager.castToDirectIp(
                                                                    context = context,
                                                                    ipAddress = manualIpText,
                                                                    videoUrl = src,
                                                                    title = title,
                                                                    headers = headers,
                                                                    saveDevice = rememberThisIp,
                                                                    mediaType = mediaType,
                                                                    preferDlna = true
                                                                )
                                                                isConnectingDirectIp = false
                                                                if (ok) {
                                                                    android.widget.Toast.makeText(
                                                                        context,
                                                                        if (langSetting == "es") "Transmitiendo vía DLNA a $manualIpText" else "Streaming via DLNA to $manualIpText",
                                                                        android.widget.Toast.LENGTH_LONG
                                                                    ).show()
                                                                    showCastDialog = false
                                                                } else {
                                                                    android.widget.Toast.makeText(
                                                                        context,
                                                                        if (langSetting == "es") "No se detectó receptor DLNA en $manualIpText. Si es Android TV o Nvidia Shield, pulsa 'Transmitir por Chromecast'."
                                                                        else "No DLNA service found on $manualIpText. If it's an Android TV, use 'Stream via Chromecast'.",
                                                                        android.widget.Toast.LENGTH_LONG
                                                                    ).show()
                                                                }
                                                            }
                                                        }
                                                    },
                                                    enabled = manualIpText.isNotBlank() && !isConnectingDirectIp,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(vertical = 6.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (langSetting == "es") "Transmitir por DLNA" else "Stream via DLNA",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                // Botón Google Cast
                                                Button(
                                                    onClick = {
                                                        if (manualIpText.isNotBlank()) {
                                                            isConnectingDirectIp = true
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                if (langSetting == "es") "Conectando por Cast a $manualIpText..." else "Connecting via Cast to $manualIpText...",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                            scope.launch {
                                                                val ok = castManager.castToDirectIp(
                                                                    context = context,
                                                                    ipAddress = manualIpText,
                                                                    videoUrl = src,
                                                                    title = title,
                                                                    headers = headers,
                                                                    saveDevice = rememberThisIp,
                                                                    mediaType = mediaType,
                                                                    preferDlna = false
                                                                )
                                                                isConnectingDirectIp = false
                                                                if (ok) {
                                                                    android.widget.Toast.makeText(
                                                                        context,
                                                                        if (langSetting == "es") "Transmitiendo por Cast a $manualIpText" else "Streaming to $manualIpText",
                                                                        android.widget.Toast.LENGTH_LONG
                                                                    ).show()
                                                                    showCastDialog = false
                                                                } else {
                                                                    android.widget.Toast.makeText(
                                                                        context,
                                                                        if (langSetting == "es") "No se pudo conectar por Cast a $manualIpText."
                                                                        else "Could not connect to $manualIpText.",
                                                                        android.widget.Toast.LENGTH_LONG
                                                                    ).show()
                                                                }
                                                            }
                                                        }
                                                    },
                                                    enabled = manualIpText.isNotBlank() && !isConnectingDirectIp,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(vertical = 6.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (langSetting == "es") "Transmitir por Chromecast" else "Stream via Chromecast",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // ─── MODO VERTICAL / PANTALLA ESTRECHA ───
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (langSetting == "es")
                                        "Dispositivos detectados en red local:"
                                    else
                                        "Detected devices on local network:",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )

                                if (allDevices.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(80.dp)
                                            .background(Color(0xFF282835), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isScanning) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(color = Color(0xFFB07FEE), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Buscando TV en la red...", color = Color.LightGray, fontSize = 11.sp)
                                            }
                                        } else {
                                            Text("No se detectaron dispositivos automáticamente.", color = Color.LightGray, fontSize = 11.sp)
                                        }
                                    }
                                } else {
                                    allDevices.forEach { dev ->
                                        val isConnectingThis = connectingDeviceId == dev.id
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .tvClickable(shape = RoundedCornerShape(10.dp)) {
                                                    connectingDeviceId = dev.id
                                                    scope.launch {
                                                        val ok = castManager.castToDevice(context, dev, src, title, headers, mediaType)
                                                        connectingDeviceId = null
                                                        if (ok) showCastDialog = false
                                                    }
                                                },
                                            colors = CardDefaults.cardColors(containerColor = if (dev.isSaved) Color(0xFF2E2344) else Color(0xFF282835)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (dev.type == com.example.cast.TvCastType.CHROMECAST) Icons.Default.CastConnected else Icons.Default.Tv,
                                                    contentDescription = null,
                                                    tint = if (dev.type == com.example.cast.TvCastType.DLNA_TV) Color(0xFF00E676) else Color(0xFFB07FEE),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(dev.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = if (dev.type == com.example.cast.TvCastType.DLNA_TV) Color(0xFF1B5E20) else Color(0xFF0D47A1),
                                                            modifier = Modifier.padding(top = 2.dp, end = 6.dp)
                                                        ) {
                                                            Text(
                                                                text = if (dev.type == com.example.cast.TvCastType.DLNA_TV) "DLNA" else "Cast",
                                                                color = Color.White,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                        Text(dev.ipAddress ?: "Wi-Fi", color = Color.LightGray, fontSize = 10.sp)
                                                    }
                                                }
                                                if (isConnectingThis) {
                                                    CircularProgressIndicator(color = Color(0xFF4CAF50), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                                } else {
                                                    if (!dev.ipAddress.isNullOrBlank() && dev.type != com.example.cast.TvCastType.DLNA_TV) {
                                                        Button(
                                                            onClick = {
                                                                connectingDeviceId = dev.id
                                                                scope.launch {
                                                                    val ok = castManager.castToDevice(context, dev, src, title, headers, mediaType, forceDlna = true)
                                                                    connectingDeviceId = null
                                                                    if (ok) {
                                                                        android.widget.Toast.makeText(context, "Transmitiendo vía DLNA a ${dev.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                                        showCastDialog = false
                                                                    } else {
                                                                        android.widget.Toast.makeText(context, "No se detectó receptor DLNA en ${dev.name}. Toca la tarjeta para conectar por Google Cast.", android.widget.Toast.LENGTH_LONG).show()
                                                                    }
                                                                }
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF).copy(alpha = 0.8f)),
                                                            shape = RoundedCornerShape(6.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                            modifier = Modifier.padding(end = 6.dp)
                                                        ) {
                                                            Text("DLNA", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                // Manual IP card in portrait
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF22283A)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Conectar por IP Directa", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = manualIpText,
                                            onValueChange = { manualIpText = it.trim() },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("IP de tu TV (ej. 192.168.1.150)", fontSize = 11.sp) },
                                            singleLine = true
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(
                                                onClick = {
                                                    if (manualIpText.isNotBlank()) {
                                                        isConnectingDirectIp = true
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            if (langSetting == "es") "Conectando por DLNA a $manualIpText..." else "Connecting via DLNA to $manualIpText...",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                        scope.launch {
                                                            val ok = castManager.castToDirectIp(context, manualIpText, src, title, headers, rememberThisIp, mediaType, preferDlna = true)
                                                            isConnectingDirectIp = false
                                                            if (ok) {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    if (langSetting == "es") "Transmitiendo vía DLNA a $manualIpText" else "Streaming via DLNA to $manualIpText",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                                showCastDialog = false
                                                            } else {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    if (langSetting == "es") "No se detectó receptor DLNA en $manualIpText. Si es Android TV o Nvidia Shield, pulsa 'Transmitir por Chromecast'."
                                                                    else "No DLNA service found on $manualIpText. If it's an Android TV, use 'Stream via Chromecast'.",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Icon(imageVector = Icons.Default.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Transmitir por DLNA", fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = {
                                                    if (manualIpText.isNotBlank()) {
                                                        isConnectingDirectIp = true
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            if (langSetting == "es") "Conectando por Cast a $manualIpText..." else "Connecting via Cast to $manualIpText...",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                        scope.launch {
                                                            val ok = castManager.castToDirectIp(context, manualIpText, src, title, headers, rememberThisIp, mediaType, preferDlna = false)
                                                            isConnectingDirectIp = false
                                                            if (ok) {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    if (langSetting == "es") "Transmitiendo por Cast a $manualIpText" else "Streaming to $manualIpText",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                                showCastDialog = false
                                                            } else {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    if (langSetting == "es") "No se pudo conectar por Cast a $manualIpText. Comprueba la IP y que la TV esté encendida."
                                                                    else "Could not connect to $manualIpText. Check TV power and IP.",
                                                                    android.widget.Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Icon(imageVector = Icons.Default.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Transmitir por Chromecast", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}


private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

@Composable
private fun TvPlayerIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    focusRequester: FocusRequester? = null,
    onBack: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val mod = modifier
        .size(36.dp)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { isFocused = it.isFocused }
        .onKeyEvent { keyEvent ->
            val keyCode = keyEvent.nativeKeyEvent.keyCode
            val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape ||
                    keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                    keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
            val isUp = keyEvent.key == Key.DirectionUp || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
            val isDown = keyEvent.key == Key.DirectionDown || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
            val isLeft = keyEvent.key == Key.DirectionLeft || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
            val isRight = keyEvent.key == Key.DirectionRight || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT

            if (isBack) {
                if (keyEvent.type == KeyEventType.KeyUp) {
                    onBack?.invoke()
                }
                true
            } else if (isUp && onUp != null) {
                if (keyEvent.type == KeyEventType.KeyDown) {
                    onUp.invoke()
                }
                true
            } else if (isDown && onDown != null) {
                if (keyEvent.type == KeyEventType.KeyDown) {
                    onDown.invoke()
                }
                true
            } else if (isLeft && onLeft != null) {
                if (keyEvent.type == KeyEventType.KeyDown) {
                    onLeft.invoke()
                }
                true
            } else if (isRight && onRight != null) {
                if (keyEvent.type == KeyEventType.KeyDown) {
                    onRight.invoke()
                }
                true
            } else {
                false
            }
        }
        .background(
            if (isFocused) Color(0xFF9B5FDE).copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.25f),
            CircleShape
        )
        .border(
            width = if (isFocused) 2.dp else 0.8.dp,
            color = if (isFocused) Color(0xFFD4B2FF) else Color.White.copy(alpha = 0.15f),
            shape = CircleShape
        )
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true, radius = 18.dp),
            onClick = onClick
        )
    Box(
        modifier = mod,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) TV_FOCUSED_TEXT else tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun TvPlayerTextButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(28.dp)
            .padding(horizontal = 2.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { keyEvent ->
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape ||
                        keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                        keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                val isUp = keyEvent.key == Key.DirectionUp || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                if (isBack) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        onBack?.invoke()
                    }
                    true
                } else if (isUp && onUp != null) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        onUp.invoke()
                    }
                    true
                } else {
                    false
                }
            }
            .background(
                if (isFocused) Color(0xFF9B5FDE).copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.25f),
                RoundedCornerShape(14.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 0.8.dp,
                color = if (isFocused) Color(0xFFD4B2FF) else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium
        )
    }
}

/**
 * Dropdown menu styled with the same translucent gradient, border, and button styling as PlayerChannelsOverlay.
 */
@Composable
private fun PlayerGlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF160E28).copy(alpha = 0.88f),
                        Color(0xFF120A20).copy(alpha = 0.92f)
                    )
                )
            )
            .border(
                width = 0.8.dp,
                color = Color(0xFFB07FEE).copy(alpha = 0.40f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(vertical = 6.dp, horizontal = 6.dp)
    ) {
        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun PlayerGlassMenuItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bg = when {
        isFocused -> Color(0xFF9B5FDE).copy(alpha = 0.45f)
        isSelected -> Color(0xFF9B5FDE).copy(alpha = 0.25f)
        else -> Color(0xFF9B5FDE).copy(alpha = 0.08f)
    }

    val border = when {
        isFocused -> Color(0xFFD4B2FF)
        isSelected -> Color(0xFFB07FEE).copy(alpha = 0.65f)
        else -> Color(0xFFB07FEE).copy(alpha = 0.18f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (isFocused) 1.5.dp else 0.8.dp,
                color = border,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                color = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color(0xFFD4B2FF) else Color.White,
                fontSize = 12.sp,
                fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Medium
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Seleccionado",
                    tint = Color(0xFFB07FEE),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

