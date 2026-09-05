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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.motor.player.TrackOption
import com.example.motor.repository.ExternalSubtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
fun applySubtitleStyles(
    playerView: PlayerView,
    subtitleColor: String,
    subtitleBg: Boolean,
    subtitleSize: String
) {
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
    initialPositionSec: Long = 0L,
    drmScheme: String? = null,
    drmLicenseUrl: String? = null,
    headers: Map<String, String> = emptyMap(),
    onProgressUpdate: (positionSec: Long, durationSec: Long) -> Unit = { _, _ -> },
    mediaType: String = "movie"
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
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitlesDialog by remember { mutableStateOf(false) }
    var showDrmDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }

    var activeDrmScheme by remember(drmScheme) { mutableStateOf(drmScheme) }
    var activeDrmLicenseUrl by remember(drmLicenseUrl) { mutableStateOf(drmLicenseUrl) }

    var multipleAudioAvailable by remember { mutableStateOf(false) }
    var showAudioTip by remember { mutableStateOf(false) }

    val externalSubtitles by playerViewModel.externalSubtitles.collectAsStateWithLifecycle()
    val isSearchingSubtitles by playerViewModel.isSearchingSubtitles.collectAsStateWithLifecycle()
    val subtitleSearchError by playerViewModel.subtitleSearchError.collectAsStateWithLifecycle()
    var isFirstLoad by remember(src) { mutableStateOf(true) }

    val autoSelectTrackLabel by playerViewModel.autoSelectTrackLabel.collectAsStateWithLifecycle()
    var selectedSubForTranslationOptions by remember { mutableStateOf<TrackOption?>(null) }
    val subtitleNotificationMessage by playerViewModel.subtitleNotification.collectAsStateWithLifecycle()
    val isSubtitleTranslating by playerViewModel.isTranslatingSubtitle.collectAsStateWithLifecycle()
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val playerFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        playerFocusRequester.requestFocus()
    }

    LaunchedEffect(showControls, lastInteractionTimestamp) {
        if (showControls) {
            kotlinx.coroutines.delay(5000L)
            showControls = false
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
        if (showAudioDialog) {
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

    LaunchedEffect(src, mediaType, title) {
        if (mediaType == "movie" || mediaType == "series") {
            playerViewModel.searchSubtitles(
                title = title,
                isSeries = (mediaType == "series")
            )
        }
    }

    LaunchedEffect(multipleAudioAvailable) {
        if (multipleAudioAvailable) {
            showAudioTip = true
            delay(6000L) // Show the tip for 6 seconds
            showAudioTip = false
        }
    }

    val exoPlayer = remember { playerViewModel.getExoPlayer(context) }

    LaunchedEffect(skipSilenceSetting, langSetting) {
        playerViewModel.updateSettings(
            skipSilenceSetting = skipSilenceSetting,
            langSetting = langSetting
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

    LaunchedEffect(exoPlayer.currentTracks, autoSelectTrackLabel) {
        val labelToSelect = autoSelectTrackLabel
        if (labelToSelect != null) {
            val tracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_TEXT)
            val matchingTrack = tracks.find { it.name == labelToSelect }
            if (matchingTrack != null) {
                playerViewModel.selectSubtitleTrack(matchingTrack)
                playerViewModel.clearAutoSelectTrackLabel()
            }
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
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Set src and DRM changes (and external subtitles)
    LaunchedEffect(src, activeDrmScheme, activeDrmLicenseUrl, externalSubtitles) {
        errorMsg = null
        val exo = playerViewModel.getExoPlayer(context)
        if (exo.playbackState != Player.STATE_READY && !exo.isPlaying) {
            isLoading = true
        } else {
            isLoading = false
        }
        retryCount = 0
        
        val startPosMs = if (isFirstLoad) {
            initialPositionSec * 1000
        } else {
            if (exo.currentPosition > 0) exo.currentPosition else (currentPosition * 1000)
        }
        isFirstLoad = false

        playerViewModel.playMedia(
            context = context,
            src = src,
            headers = headers,
            drmScheme = activeDrmScheme,
            drmLicenseUrl = activeDrmLicenseUrl,
            initialPosMs = startPosMs,
            subtitles = externalSubtitles
        )

        if (exo.playbackState == Player.STATE_READY || exo.isPlaying) {
            isLoading = false
            if (exo.duration > 0) {
                duration = exo.duration / 1000
            }
            val audioTracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            multipleAudioAvailable = audioTracks.size > 1
        }
    }

    // Setup player listener
    DisposableEffect(exoPlayer) {
        playerViewModel.attachPlayer(exoPlayer)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isLoading = false
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isLoading = false
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        isLoading = true
                    }
                    Player.STATE_READY -> {
                        isLoading = false
                        duration = exoPlayer.duration / 1000
                        val audioTracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                        multipleAudioAvailable = audioTracks.size > 1
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        showControls = true
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isLoading = false
                Log.e("VideoPlayer", "Playback error: ${error.message}", error)
                if (retryCount < 3) {
                    scope.launch {
                        _connectRetryBackoff(retryCount)
                        retryCount++
                        errorMsg = null
                        isLoading = true
                        playerViewModel.retryPlayback(context)
                    }
                } else {
                    errorMsg = "No se puede reproducir este stream"
                }
            }
        }
        exoPlayer.addListener(listener)
        // Immediately sync state from existing player
        isPlaying = exoPlayer.isPlaying
        if (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.isPlaying) {
            isLoading = false
            duration = exoPlayer.duration / 1000
            val audioTracks = playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            multipleAudioAvailable = audioTracks.size > 1
        } else if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
            isLoading = true
        }

        onDispose {
            exoPlayer.removeListener(listener)
            playerViewModel.detachPlayer(exoPlayer)
            if (mediaType != "live") {
                playerViewModel.stop()
            }
        }
    }

    // Periodical progress tracking
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition / 1000
            if (duration > 0) {
                onProgressUpdate(currentPosition, duration)
            }
            delay(1000L)
        }
    }

    // Hide controls timer
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000L)
            showControls = false
        }
    }

    BackHandler(enabled = true) {
        if (showAudioDialog || showSubtitlesDialog || showDrmDialog || showCastDialog || showSpeedMenu) {
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

    val aspectResizeMode = remember(ratioSetting) {
        when (ratioSetting) {
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
                        if (showAudioDialog || showSubtitlesDialog || showDrmDialog || showCastDialog || showSpeedMenu) {
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
                        if (isSelectKey || isDpadUpOrDown) {
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isDpadLeft) {
                            playerViewModel.seekTo(maxOf(0, exoPlayer.currentPosition - 10000))
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isDpadRight) {
                            playerViewModel.seekTo(minOf(exoPlayer.duration, exoPlayer.currentPosition + 10000))
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
                        } else if (isRewind) {
                            playerViewModel.seekTo(maxOf(0, exoPlayer.currentPosition - 10000))
                            showControls = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            return@onPreviewKeyEvent true
                        } else if (isFastForward) {
                            playerViewModel.seekTo(minOf(exoPlayer.duration, exoPlayer.currentPosition + 10000))
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showAudioDialog || showSubtitlesDialog || showDrmDialog || showCastDialog || showSpeedMenu) {
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
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.player_view_texture, null) as PlayerView).apply {
                    this.player = exoPlayer
                    this.useController = false
                    this.resizeMode = aspectResizeMode
                    this.keepScreenOn = true
                    this.isFocusable = false
                    this.isFocusableInTouchMode = false
                    applySubtitleStyles(this, subtitleColorSetting, subtitleBgSetting, subtitleSizeSetting)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
                playerView.resizeMode = aspectResizeMode
                playerView.keepScreenOn = true
                applySubtitleStyles(playerView, subtitleColorSetting, subtitleBgSetting, subtitleSizeSetting)
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading spinner
        if (isLoading) {
            CircularProgressIndicator(
                color = Color(0xFFB07FEE),
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center)
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
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        lastInteractionTimestamp = System.currentTimeMillis()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvPlayerIconButton(
                    onClick = {
                        onProgressUpdate(currentPosition, duration)
                        onBack()
                    },
                    icon = Icons.Default.ArrowBack,
                    contentDescription = "Back"
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
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF9B5FDE).copy(alpha = 0.4f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text("MEDIA3", color = Color(0xFFB07FEE), fontSize = 11.sp)
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
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        lastInteractionTimestamp = System.currentTimeMillis()
                    }
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                // Seek Bar Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { pos ->
                            currentPosition = pos.toLong()
                            playerViewModel.seekTo(currentPosition * 1000)
                        },
                        valueRange = 0f..if (duration > 0) duration.toFloat() else 1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFFB07FEE),
                            inactiveTrackColor = Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                    Text(
                        text = formatTime(duration),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                // Controls row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Play/Pause
                    TvPlayerIconButton(
                        onClick = {
                            if (isPlaying) playerViewModel.pause() else playerViewModel.resume()
                        },
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        focusRequester = playButtonFocusRequester,
                        onBack = { showControls = false }
                    )

                    // Stop button
                    TvPlayerIconButton(
                        onClick = {
                            onProgressUpdate(currentPosition, duration)
                            onBack()
                        },
                        icon = Icons.Default.Stop,
                        contentDescription = "Stop",
                        onBack = { showControls = false }
                    )

                    // Rewind 10s
                    TvPlayerIconButton(
                        onClick = {
                            playerViewModel.seekTo(maxOf(0, exoPlayer.currentPosition - 10000))
                        },
                        icon = Icons.Default.Replay10,
                        contentDescription = "-10s",
                        onBack = { showControls = false }
                    )

                    // Forward 10s
                    TvPlayerIconButton(
                        onClick = {
                            playerViewModel.seekTo(minOf(exoPlayer.duration, exoPlayer.currentPosition + 10000))
                        },
                        icon = Icons.Default.Forward10,
                        contentDescription = "+10s",
                        onBack = { showControls = false }
                    )

                    // Audio Track Language
                    TvPlayerIconButton(
                        onClick = {
                            showAudioDialog = true
                        },
                        icon = Icons.Default.Language,
                        contentDescription = "Audio track language",
                        onBack = { showControls = false }
                    )

                    // Subtitles
                    TvPlayerIconButton(
                        onClick = {
                            showSubtitlesDialog = true
                        },
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = "Subtitle tracks",
                        onBack = { showControls = false }
                    )

                    // DRM
                    TvPlayerIconButton(
                        onClick = {
                            showDrmDialog = true
                        },
                        icon = Icons.Default.VpnKey,
                        contentDescription = "Configuración DRM (Widevine)",
                        tint = if (!activeDrmLicenseUrl.isNullOrEmpty()) Color(0xFFB07FEE) else Color.White,
                        onBack = { showControls = false }
                    )

                    // Cast
                    TvPlayerIconButton(
                        onClick = {
                            showCastDialog = true
                        },
                        icon = Icons.Default.Cast,
                        contentDescription = "Transmitir a TV (Cast)",
                        onBack = { showControls = false }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Speed controller trigger
                    Box {
                        TvPlayerTextButton(
                            onClick = { showSpeedMenu = !showSpeedMenu },
                            text = "${playbackSpeed}x",
                            onBack = { showControls = false }
                        )

                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            modifier = Modifier.background(Color(0xFF1E1E28))
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("${s}x", color = Color.White) },
                                    onClick = {
                                        playbackSpeed = s
                                        playerViewModel.setPlaybackSpeed(s)
                                        showSpeedMenu = false
                                    },
                                    modifier = Modifier.tvClickable {
                                        playbackSpeed = s
                                        playerViewModel.setPlaybackSpeed(s)
                                        showSpeedMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── AUDIO TRACK SELECTION DIALOG ───────────────────────────────────────
    if (showAudioDialog) {
        AlertDialog(
            onDismissRequest = { showAudioDialog = false },
            title = { Text("Idioma de Audio", color = Color.White) },
            containerColor = Color(0xFF1E1E28),
            confirmButton = {
                TvButton(
                    onClick = { showAudioDialog = false },
                    containerColor = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Text("Cerrar", color = if (isFocused) TV_FOCUSED_TEXT else Color(0xFFB07FEE), fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                }
            },
            text = {
                val audioTracks = remember(exoPlayer.currentTracks) {
                    playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                }
                if (audioTracks.isEmpty()) {
                    Text("No hay pistas de audio disponibles", color = Color.LightGray)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(audioTracks) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .tvClickable {
                                        playerViewModel.selectAudioTrack(track)
                                        showAudioDialog = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = track.isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFFB07FEE),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = track.name,
                                    color = if (track.isSelected) Color(0xFFB07FEE) else Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    // ─── SUBTITLES TRACK SELECTION DIALOG ────────────────────────────────────
    if (showSubtitlesDialog) {
        var searchQuery by remember { mutableStateOf(title) }

        AlertDialog(
            onDismissRequest = { showSubtitlesDialog = false },
            title = { Text("Subtítulos", color = Color.White) },
            containerColor = Color(0xFF1E1E28),
            confirmButton = {
                TvButton(
                    onClick = { showSubtitlesDialog = false },
                    containerColor = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Text("Cerrar", color = if (isFocused) TV_FOCUSED_TEXT else Color(0xFFB07FEE), fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Search bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Buscar en Internet", color = Color.Gray, fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFB07FEE),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color(0xFFB07FEE),
                                unfocusedLabelColor = Color.Gray
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (searchQuery.isNotBlank()) {
                                    val isSeries = mediaType == "series"
                                    val queryToUse = searchQuery.ifBlank { title }
                                    playerViewModel.searchSubtitles(
                                        title = queryToUse,
                                        isSeries = isSeries
                                    )
                                }
                            },
                            enabled = !isSearchingSubtitles,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF9B5FDE))
                        ) {
                            if (isSearchingSubtitles) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
                            }
                        }
                    }

                    if (subtitleSearchError != null) {
                        Text(subtitleSearchError!!, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    if (isSearchingSubtitles) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFFB07FEE), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando subtítulos automáticos...", color = Color.Gray, fontSize = 13.sp)
                        }
                    }

                    val subtitleTracks = remember(exoPlayer.currentTracks, externalSubtitles) {
                        playerViewModel.getTrackOptions(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    }

                    if (subtitleTracks.isEmpty() || (subtitleTracks.size == 1 && subtitleTracks.first().isNone)) {
                        Text("No hay subtítulos disponibles. ¡Busca arriba!", color = Color.LightGray, modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 250.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(subtitleTracks) { track ->
                                val matchingSub = externalSubtitles.find { it.label == track.name } ?: run {
                                    val trackNameLower = track.name.lowercase()
                                    externalSubtitles.find { ext ->
                                        val extLabelLower = ext.label.lowercase()
                                        ((trackNameLower.contains("alemán") || trackNameLower.contains("aleman") || trackNameLower.contains("german")) && (extLabelLower.contains("alemán") || extLabelLower.contains("aleman") || extLabelLower.contains("german") || ext.language.trim().lowercase() in listOf("de", "ger", "deu"))) ||
                                        ((trackNameLower.contains("francés") || trackNameLower.contains("frances") || trackNameLower.contains("french")) && (extLabelLower.contains("francés") || extLabelLower.contains("frances") || extLabelLower.contains("french") || ext.language.trim().lowercase() in listOf("fr", "fre", "fra"))) ||
                                        ((trackNameLower.contains("inglés") || trackNameLower.contains("ingles") || trackNameLower.contains("english")) && (extLabelLower.contains("inglés") || extLabelLower.contains("ingles") || extLabelLower.contains("english") || ext.language.trim().lowercase() in listOf("en", "eng"))) ||
                                        ((trackNameLower.contains("italiano") || trackNameLower.contains("italian")) && (extLabelLower.contains("italiano") || extLabelLower.contains("italian") || ext.language.trim().lowercase() in listOf("it", "ita"))) ||
                                        ((trackNameLower.contains("portugués") || trackNameLower.contains("portugues") || trackNameLower.contains("portuguese")) && (extLabelLower.contains("portugués") || extLabelLower.contains("portugues") || extLabelLower.contains("portuguese") || ext.language.trim().lowercase() in listOf("pt", "por"))) ||
                                        ((trackNameLower.contains("ruso") || trackNameLower.contains("russian")) && (extLabelLower.contains("ruso") || extLabelLower.contains("russian") || ext.language.trim().lowercase() in listOf("ru", "rus")))
                                    }
                                }
                                val canTranslate = matchingSub != null && 
                                        !track.name.contains("Traducido") && 
                                        !track.name.lowercase().contains("español") && 
                                        !track.name.lowercase().contains("rumano")

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (selectedSubForTranslationOptions == track) Color.White.copy(alpha = 0.05f) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .tvClickable {
                                                if (canTranslate) {
                                                    selectedSubForTranslationOptions = if (selectedSubForTranslationOptions == track) null else track
                                                } else {
                                                    playerViewModel.selectSubtitleTrack(track)
                                                    showSubtitlesDialog = false
                                                }
                                            }
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = track.isSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = Color(0xFFB07FEE),
                                                unselectedColor = Color.Gray
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = track.name,
                                                color = if (track.isSelected) Color(0xFFB07FEE) else Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (canTranslate) {
                                                Text(
                                                    text = "Toca para traducir a Español o Rumano",
                                                    color = Color.LightGray.copy(alpha = 0.6f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        if (canTranslate) {
                                            Icon(
                                                imageVector = if (selectedSubForTranslationOptions == track) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                contentDescription = "Traducir",
                                                tint = Color.LightGray
                                            )
                                        }
                                    }

                                    if (canTranslate && selectedSubForTranslationOptions == track) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isSubtitleTranslating) {
                                                CircularProgressIndicator(
                                                    color = Color(0xFFB07FEE),
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Traduciendo...", color = Color.LightGray, fontSize = 12.sp)
                                            } else {
                                                Button(
                                                    onClick = {
                                                        val sub = matchingSub ?: return@Button
                                                        showSubtitlesDialog = false
                                                        showControls = false
                                                        selectedSubForTranslationOptions = null
                                                        playerViewModel.translateSubtitle(sub, "es")
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text("🇪🇸 Español", fontSize = 11.sp, color = Color.White)
                                                }

                                                Button(
                                                    onClick = {
                                                        val sub = matchingSub ?: return@Button
                                                        showSubtitlesDialog = false
                                                        showControls = false
                                                        selectedSubForTranslationOptions = null
                                                        playerViewModel.translateSubtitle(sub, "ro")
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text("🇷🇴 Rumano", fontSize = 11.sp, color = Color.White)
                                                }

                                                TextButton(
                                                    onClick = {
                                                        playerViewModel.selectSubtitleTrack(track)
                                                        selectedSubForTranslationOptions = null
                                                        showSubtitlesDialog = false
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text("Original", fontSize = 11.sp, color = Color.LightGray)
                                                }
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
                    containerColor = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Text("Cancelar", color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal)
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
                            TextButton(onClick = { tempLicenseUrl = "" }) {
                                Text("Limpiar URL", color = Color.Red)
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
        val isScanning by castManager.isScanning.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var connectingDeviceId by remember { mutableStateOf<String?>(null) }

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
                    if (isScanning) {
                        CircularProgressIndicator(
                            color = Color(0xFFB07FEE),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
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
                    containerColor = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Text(closeBtn, color = if (isFocused) TV_FOCUSED_TEXT else Color(0xFFB07FEE), fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ─── OPCIÓN 2: VLC PLAYER (COMPATIBILIDAD VPN Y CHROMECAST) ───
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val vlcOpened = castManager.launchInVlcPlayer(context, src, title, headers)
                                if (vlcOpened) {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (langSetting == "es") "Abriendo transmisión en VLC..."
                                        else if (langSetting == "ro") "Se deschide transmisia în VLC..."
                                        else "Opening stream in VLC...",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    showCastDialog = false
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (langSetting == "es") "No se pudo iniciar VLC. Instala VLC desde Play Store."
                                        else if (langSetting == "ro") "Nu s-a putut porni VLC. Instalați VLC din Play Store."
                                        else "Could not launch VLC. Please install VLC from Play Store.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF33261D)),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFFF9800).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "VLC",
                                    tint = Color(0xFFFF9800),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (langSetting == "es") "Transmitir / Reproducir con VLC Player"
                                    else if (langSetting == "ro") "Transmite / Redă cu VLC Player"
                                    else "Stream / Play with VLC Player",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (langSetting == "es")
                                        "Opción VPN: Envía a Chromecast o reproduce desde VLC directamente"
                                    else if (langSetting == "ro")
                                        "Opțiune VPN: Trimite la Chromecast sau redă din VLC direct"
                                    else
                                        "VPN Option: Cast to Chromecast or play directly via VLC",
                                    color = Color(0xFFFFCC80),
                                    fontSize = 11.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Abrir VLC",
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (langSetting == "es")
                            "Dispositivos detectados en red local (Chromecast, Android TV, TV Box):"
                        else if (langSetting == "ro")
                            "Dispozitive detectate în rețeaua locală (Chromecast, Android TV, TV Box):"
                        else
                            "Detected devices on local network (Chromecast, Android TV, TV Box):",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(Color(0xFF282835), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFFB07FEE),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (langSetting == "es")
                                        "Buscando en Wi-Fi... Si la VPN bloquea el escaneo, usa el botón de VLC arriba."
                                    else if (langSetting == "ro")
                                        "Căutare pe Wi-Fi... Dacă VPN-ul blochează scanarea, folosește opțiunea VLC de mai sus."
                                    else
                                        "Searching on Wi-Fi... If VPN blocks discovery, use the VLC option above.",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(discoveredDevices, key = { it.id }) { dev ->
                                val isConnectingThis = connectingDeviceId == dev.id
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = connectingDeviceId == null) {
                                            connectingDeviceId = dev.id
                                            android.widget.Toast.makeText(
                                                context,
                                                if (langSetting == "es") "Conectando con ${dev.name}..." else "Connecting to ${dev.name}...",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            scope.launch {
                                                val ok = castManager.castToDevice(context, dev, src, title, headers)
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
                                                        if (langSetting == "es") "No se pudo conectar directamente con ${dev.name}. Intenta la opción de VLC arriba."
                                                        else if (langSetting == "ro") "Nu s-a putut conecta direct la ${dev.name}. Încercați opțiunea VLC de mai sus."
                                                        else "Could not connect to ${dev.name}. Try the VLC option above.",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isConnectingThis) Color(0xFF38384A) else Color(0xFF282835)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (dev.type) {
                                                com.example.cast.TvCastType.CHROMECAST -> Icons.Default.CastConnected
                                                else -> Icons.Default.Tv
                                            },
                                            contentDescription = null,
                                            tint = Color(0xFFB07FEE),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dev.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = when (dev.type) {
                                                    com.example.cast.TvCastType.CHROMECAST -> "Chromecast / Google TV / Android TV (${dev.ipAddress ?: "Wi-Fi"})"
                                                    com.example.cast.TvCastType.DLNA_TV -> "Smart TV / Android Box (${dev.ipAddress ?: "Wi-Fi"})"
                                                    com.example.cast.TvCastType.SYSTEM_CAST -> "Sistema"
                                                },
                                                color = Color.LightGray,
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (isConnectingThis) {
                                            CircularProgressIndicator(
                                                color = Color(0xFF4CAF50),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Transmitir",
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(22.dp)
                                            )
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

private suspend fun _connectRetryBackoff(attempt: Int) {
    val delays = listOf(1000L, 2500L, 5000L)
    val delayMs = if (attempt < delays.size) delays[attempt] else 5000L
    delay(delayMs)
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
    onBack: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val mod = modifier
        .size(44.dp)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { isFocused = it.isFocused }
        .onKeyEvent { keyEvent ->
            val keyCode = keyEvent.nativeKeyEvent.keyCode
            val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape ||
                    keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                    keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
            if (isBack) {
                if (keyEvent.type == KeyEventType.KeyUp) {
                    onBack?.invoke()
                }
                true
            } else {
                false
            }
        }
        .background(
            if (isFocused) TV_FOCUSED_BG else Color.White.copy(alpha = 0.08f),
            CircleShape
        )
        .border(
            width = if (isFocused) 2.5.dp else 0.dp,
            color = if (isFocused) TV_FOCUSED_BORDER else Color.Transparent,
            shape = CircleShape
        )
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true, radius = 22.dp),
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
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun TvPlayerTextButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(36.dp)
            .padding(horizontal = 4.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { keyEvent ->
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape ||
                        keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                        keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                if (isBack) {
                    if (keyEvent.type == KeyEventType.KeyUp) {
                        onBack?.invoke()
                    }
                    true
                } else {
                    false
                }
            }
            .background(
                if (isFocused) TV_FOCUSED_BG else Color.DarkGray,
                RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 0.dp,
                color = if (isFocused) TV_FOCUSED_BORDER else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontSize = 12.sp, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
    }
}

