package com.example.interfaz

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.Translator
import com.example.parseHexColor
import com.example.interfaz.components.VideoPlayer
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.screens.*
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.interfaz.viewmodel.MoviesViewModel
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.interfaz.viewmodel.PlaylistViewModel
import com.example.interfaz.viewmodel.ProfileViewModel
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.interfaz.viewmodel.SettingsViewModel
import com.example.motor.config.PlayerSettings

data class MenuItem(val route: String, val title: String, val icon: ImageVector)

@Composable
fun MainAppTheme(colors: AppThemeColors, content: @Composable () -> Unit) {
    val materialScheme = darkColorScheme(
        primary = parseHexColor(colors.accent),
        secondary = parseHexColor(colors.accentLight),
        background = parseHexColor(colors.bg),
        surface = parseHexColor(colors.surface),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )

    MaterialTheme(
        colorScheme = materialScheme,
        content = content
    )
}

// ─── CORE MAIN APPLICATION CONTAINER ──────────────────────────────────────────

@Composable
fun AppContent(viewModel: MainViewModel, theme: AppThemeColors) {
    val liveTvViewModel: LiveTvViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val moviesViewModel: MoviesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val seriesViewModel: SeriesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val playerViewModel: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val homeViewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val settingsViewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val playlistViewModel: PlaylistViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val profileViewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    val liveChannelsMap by viewModel.liveChannels.collectAsStateWithLifecycle()
    val epgDataMap by viewModel.epgData.collectAsStateWithLifecycle()
    val moviesMap by viewModel.movies.collectAsStateWithLifecycle()
    val precomputedMoviesData by viewModel.precomputedMoviesData.collectAsStateWithLifecycle()
    val seriesMap by viewModel.series.collectAsStateWithLifecycle()
    val precomputedSeriesData by viewModel.precomputedSeriesData.collectAsStateWithLifecycle()
    val isStalkerDownloading by viewModel.stalkerDownloading.collectAsStateWithLifecycle()
    val stalkerDownloadStats by viewModel.stalkerDownloadStats.collectAsStateWithLifecycle()

    LaunchedEffect(liveChannelsMap) {
        liveTvViewModel.updateLiveChannels(liveChannelsMap)
        homeViewModel.updateChannels(liveChannelsMap)
        settingsViewModel.updateLiveChannels(liveChannelsMap)
    }
    LaunchedEffect(epgDataMap) {
        liveTvViewModel.updateEpgData(epgDataMap)
    }
    LaunchedEffect(moviesMap) {
        moviesViewModel.updateMovies(moviesMap)
    }
    LaunchedEffect(precomputedMoviesData) {
        moviesViewModel.updatePrecomputedData(precomputedMoviesData)
        homeViewModel.updateMoviesData(precomputedMoviesData)
        settingsViewModel.updateMoviesData(precomputedMoviesData)
    }
    LaunchedEffect(seriesMap) {
        seriesViewModel.updateSeries(seriesMap)
    }
    LaunchedEffect(precomputedSeriesData) {
        seriesViewModel.updatePrecomputedData(precomputedSeriesData)
        homeViewModel.updateSeriesData(precomputedSeriesData)
        settingsViewModel.updateSeriesData(precomputedSeriesData)
    }
    LaunchedEffect(isStalkerDownloading, stalkerDownloadStats) {
        homeViewModel.updateStalkerDownloadState(isStalkerDownloading, stalkerDownloadStats)
    }

    val currentProfile by viewModel.currentProfile.collectAsStateWithLifecycle()
    val profilesList by viewModel.profiles.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf("profiles") }
    
    // Video Player overlays state
    var activePlayerSrc by remember { mutableStateOf<String?>(null) }
    var activePlayerTitle by remember { mutableStateOf("") }
    var activePlayerType by remember { mutableStateOf("live") }
    var activeDrmScheme by remember { mutableStateOf<String?>(null) }
    var activeDrmLicenseUrl by remember { mutableStateOf<String?>(null) }

    // Navigation Back Stack
    val backStack = remember { mutableStateListOf<String>() }
    val topLevelScreens = remember { setOf("home", "live_tv", "movies", "series", "playlists", "settings", "accounts") }

    fun navigateTo(screen: String) {
        if (currentScreen != screen) {
            if (screen in topLevelScreens) {
                backStack.clear()
                if (screen != "home") {
                    backStack.add("home")
                }
            } else {
                backStack.add(currentScreen)
            }
            currentScreen = screen
        }
    }

    fun navigateBack() {
        if (backStack.isNotEmpty()) {
            currentScreen = backStack.removeLast()
        }
    }

    // Capture system Back button
    BackHandler {
        if (activePlayerSrc != null) {
            activePlayerSrc = null
        } else if (backStack.isNotEmpty()) {
            navigateBack()
        }
    }

    // Keep screens in sync when profile loads
    LaunchedEffect(currentProfile) {
        if (currentProfile == null) {
            currentScreen = "profiles"
        } else if (currentScreen == "profiles") {
            currentScreen = "home"
        }
    }

    var isFullScreenOverlay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // APP SCREEN SWITCHER ALWAYS ACTIVE IN BACKGROUND TO RETAIN STATE
        when (currentScreen) {
            "profiles" -> ProfileSelectorScreen(
                profiles = profilesList,
                onProfileSelected = { p -> 
                    viewModel.switchProfile(p)
                    currentScreen = "home"
                },
                onAddProfile = { name, avatar, pin -> viewModel.addProfile(name, avatar, pin) },
                onDeleteProfile = { id -> viewModel.deleteProfile(id) },
                onEditProfile = { id, name, avatar, pin -> viewModel.updateProfile(id, name, avatar, pin) },
                theme = theme
            )
            else -> {
                // Main app shell with sidebar and top bar
                AppShell(
                    currentScreen = currentScreen,
                    onNavigate = { navigateTo(it) },
                    onBack = { navigateBack() },
                    viewModel = viewModel,
                    theme = theme,
                    isFullScreenOverlay = isFullScreenOverlay
                ) {
                    when (currentScreen) {
                        "home" -> HomeScreen(
                            viewModel = homeViewModel,
                            onNavigate = { navigateTo(it) },
                            onPlay = { src, title, type, dScheme, dLicense ->
                                activePlayerSrc = src
                                activePlayerTitle = title
                                activePlayerType = type
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                            },
                            theme = theme
                        )
                        "live_tv" -> LiveTVScreen(
                            viewModel = liveTvViewModel,
                            playerViewModel = playerViewModel,
                            mainViewModel = viewModel,
                            onPlay = { src, title, dScheme, dLicense ->
                                activePlayerSrc = src
                                activePlayerTitle = title
                                activePlayerType = "live"
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                            },
                            onBack = { navigateTo("home") },
                            theme = theme,
                            isFullScreenActive = activePlayerSrc != null
                        )
                        "movies" -> MoviesScreen(
                            viewModel = moviesViewModel,
                            onPlay = { src, title, dScheme, dLicense ->
                                activePlayerSrc = src
                                activePlayerTitle = title
                                activePlayerType = "movie"
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                            },
                            onBack = { navigateTo("home") },
                            theme = theme
                        )
                        "series" -> SeriesScreen(
                            viewModel = seriesViewModel,
                            onPlay = { src, title, dScheme, dLicense ->
                                activePlayerSrc = src
                                activePlayerTitle = title
                                activePlayerType = "series"
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                            },
                            onBack = { navigateTo("home") },
                            theme = theme
                        )
                        "playlists" -> PlaylistsScreen(
                            viewModel = playlistViewModel,
                            theme = theme
                        )
                        "accounts" -> AccountsScreen(
                            viewModel = profileViewModel,
                            onLogout = { currentScreen = "profiles" },
                            theme = theme
                        )
                        "settings" -> SettingsScreen(
                            viewModel = settingsViewModel,
                            theme = theme,
                            onFullScreenChange = { isFullScreenOverlay = it }
                        )
                    }
                }
            }
        }

        if (activePlayerSrc != null) {
            val pSettings = currentProfile?.let { JsonUtils.settingsFromJson(it.settingsJson) } ?: PlayerSettings()
            val streamHeaders = viewModel.getActiveStreamHeaders()
            val context = LocalContext.current

            if (pSettings.player != "Player integrado") {
                LaunchedEffect(activePlayerSrc) {
                    val url = activePlayerSrc!!
                    val vlcOptions = ArrayList<String>()
                    streamHeaders.forEach { (key, value) ->
                        if (key.equals("User-Agent", ignoreCase = true)) {
                            vlcOptions.add(":http-user-agent=$value")
                        } else if (key.equals("Referer", ignoreCase = true)) {
                            vlcOptions.add(":http-referrer=$value")
                        } else {
                            vlcOptions.add(":http-header-$key=$value")
                        }
                    }

                    if (pSettings.player == "VLC Player") {
                        var success = false
                        val pm = context.packageManager
                        
                        val hasStable = try { pm.getPackageInfo("org.videolan.vlc", 0); true } catch (e: Exception) { false }
                        val hasBeta = try { pm.getPackageInfo("org.videolan.vlc.beta", 0); true } catch (e: Exception) { false }
                        
                        val pkgList = mutableListOf<String>()
                        if (hasStable) pkgList.add("org.videolan.vlc")
                        if (hasBeta) pkgList.add("org.videolan.vlc.beta")
                        if (pkgList.isEmpty()) {
                            pkgList.add("org.videolan.vlc")
                            pkgList.add("org.videolan.vlc.beta")
                        }

                        for (pkg in pkgList) {
                            if (success) break
                            
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setComponent(android.content.ComponentName(pkg, "org.videolan.vlc.gui.video.VideoPlayerActivity"))
                                    setDataAndType(android.net.Uri.parse(url), "video/*")
                                    if (vlcOptions.isNotEmpty()) {
                                        putStringArrayListExtra("options", vlcOptions)
                                    }
                                    if (streamHeaders.isNotEmpty()) {
                                        val headersArray = streamHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()
                                        putExtra("headers", headersArray)
                                        val headersString = streamHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                        putExtra("http-headers", headersString)
                                    }
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                android.widget.Toast.makeText(context, "Abriendo con VLC...", android.widget.Toast.LENGTH_SHORT).show()
                                success = true
                            } catch (e: Exception) {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setPackage(pkg)
                                        setDataAndType(android.net.Uri.parse(url), "video/*")
                                        if (vlcOptions.isNotEmpty()) {
                                            putStringArrayListExtra("options", vlcOptions)
                                        }
                                        if (streamHeaders.isNotEmpty()) {
                                            val headersArray = streamHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()
                                            putExtra("headers", headersArray)
                                            val headersString = streamHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                            putExtra("http-headers", headersString)
                                        }
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    android.widget.Toast.makeText(context, "Abriendo con VLC ($pkg)...", android.widget.Toast.LENGTH_SHORT).show()
                                    success = true
                                } catch (e2: Exception) {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setPackage(pkg)
                                            setData(android.net.Uri.parse(url))
                                            if (vlcOptions.isNotEmpty()) {
                                                putStringArrayListExtra("options", vlcOptions)
                                            }
                                            if (streamHeaders.isNotEmpty()) {
                                                val headersArray = streamHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()
                                                putExtra("headers", headersArray)
                                                val headersString = streamHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                                putExtra("http-headers", headersString)
                                            }
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                        android.widget.Toast.makeText(context, "Abriendo con VLC ($pkg)...", android.widget.Toast.LENGTH_SHORT).show()
                                        success = true
                                    } catch (e3: Exception) {
                                    }
                                }
                            }
                        }

                        if (!success) {
                            try {
                                val genericIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(android.net.Uri.parse(url), "video/*")
                                    if (streamHeaders.isNotEmpty()) {
                                        val headersArray = streamHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()
                                        putExtra("headers", headersArray)
                                        val headersString = streamHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                        putExtra("http-headers", headersString)
                                    }
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                android.widget.Toast.makeText(context, "VLC no se pudo abrir, intentando con reproductor del sistema...", android.widget.Toast.LENGTH_LONG).show()
                                context.startActivity(genericIntent)
                            } catch (ex: Exception) {
                                android.widget.Toast.makeText(context, "No se encontró ningún reproductor compatible", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(url), "video/*")
                                if (streamHeaders.isNotEmpty()) {
                                    val headersArray = streamHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()
                                    putExtra("headers", headersArray)
                                    val headersString = streamHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                    putExtra("http-headers", headersString)
                                }
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            android.widget.Toast.makeText(context, "Abriendo con reproductor externo...", android.widget.Toast.LENGTH_SHORT).show()
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Error al abrir reproductor externo: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    activePlayerSrc = null
                }
            } else {
                var initialPosSec by remember(activePlayerSrc) { mutableStateOf<Long?>(null) }
                LaunchedEffect(activePlayerSrc) {
                    if (activePlayerSrc != null) {
                        val progress = viewModel.getPlaybackProgress(activePlayerSrc!!)
                        initialPosSec = progress?.position ?: 0L
                    }
                }

                if (initialPosSec != null) {
                    VideoPlayer(
                        src = activePlayerSrc!!,
                        title = activePlayerTitle,
                        playerViewModel = playerViewModel,
                        ratioSetting = pSettings.ratio,
                        skipSilenceSetting = pSettings.skipSilence,
                        decodeSetting = pSettings.decode,
                        langSetting = pSettings.lang,
                        subtitleColorSetting = pSettings.subtitleColor,
                        subtitleBgSetting = pSettings.subtitleBg,
                        subtitleSizeSetting = pSettings.subtitleSize,
                        initialPositionSec = initialPosSec!!,
                        drmScheme = activeDrmScheme,
                        drmLicenseUrl = activeDrmLicenseUrl,
                        headers = streamHeaders,
                        onBack = { activePlayerSrc = null },
                        onProgressUpdate = { pos, dur ->
                            activePlayerSrc?.let { src ->
                                viewModel.savePlaybackProgress(src, activePlayerTitle, pos, dur, activePlayerType)
                            }
                        },
                        mediaType = activePlayerType
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = parseHexColor(theme.accent))
                    }
                }
            }
        }
    }
}

// ─── STYLISH NAVIGATION APPSHELL ──────────────────────────────────────────────

@Composable
fun AppShell(
    currentScreen: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel,
    theme: AppThemeColors,
    isFullScreenOverlay: Boolean = false,
    content: @Composable () -> Unit
) {
    val currentProfile by viewModel.currentProfile.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val lang = settingsObj.lang
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val menuItems = listOf(
        MenuItem("home", Translator.translate("home", lang), Icons.Default.Home),
        MenuItem("live_tv", Translator.translate("live_tv", lang), Icons.Default.Tv),
        MenuItem("movies", Translator.translate("movies", lang), Icons.Default.Movie),
        MenuItem("series", Translator.translate("series", lang), Icons.Default.VideoLibrary),
        MenuItem("playlists", Translator.translate("playlists", lang), Icons.Default.CloudDownload),
        MenuItem("settings", Translator.translate("settings", lang), Icons.Default.Settings),
        MenuItem("accounts", Translator.translate("accounts", lang), Icons.Default.People)
    )

    val bottomMenuItems = listOf(
        MenuItem("home", Translator.translate("home", lang), Icons.Default.Home),
        MenuItem("live_tv", Translator.translate("live_tv", lang), Icons.Default.Tv),
        MenuItem("movies", Translator.translate("movies", lang), Icons.Default.Movie),
        MenuItem("series", Translator.translate("series", lang), Icons.Default.VideoLibrary),
        MenuItem("playlists", Translator.translate("playlists", lang), Icons.Default.CloudDownload),
        MenuItem("settings", Translator.translate("settings", lang), Icons.Default.Settings)
    )

    if (isPortrait) {
        if (isFullScreenOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(parseHexColor(theme.bg))
            ) {
                content()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(parseHexColor(theme.bg))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(parseHexColor(theme.surface))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(theme.accent).copy(alpha = 0.2f))
                            .clickable { onNavigate("accounts") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentProfile?.avatar ?: "👤",
                            fontSize = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (currentScreen) {
                                "home" -> "Radu IPTV"
                                "live_tv" -> Translator.translate("live_tv", lang)
                                "movies" -> Translator.translate("movies", lang)
                                "series" -> Translator.translate("series", lang)
                                "playlists" -> Translator.translate("playlists", lang)
                                "accounts" -> Translator.translate("accounts", lang)
                                "settings" -> Translator.translate("settings", lang)
                                else -> "IPTV"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color.Green else Color.Red)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOnline) Translator.translate("online", lang) else Translator.translate("offline", lang),
                                color = if (isOnline) Color.LightGray else Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = { onNavigate("settings") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = Translator.translate("settings", lang),
                            tint = if (currentScreen == "settings") parseHexColor(theme.accent) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { onNavigate("profiles") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = Translator.translate("exit", lang),
                            tint = Color.Red,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    content()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(parseHexColor(theme.surface))
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    bottomMenuItems.forEach { item ->
                        val isSelected = currentScreen == item.route
                        val tint = if (isSelected) parseHexColor(theme.accent) else Color.Gray

                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .tvClickable { onNavigate(item.route) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = tint,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.title,
                                color = tint,
                                fontSize = 9.sp,
                                maxLines = 1,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    } else {
        val showSidebar = currentScreen !in listOf("live_tv", "movies", "series") && !isFullScreenOverlay
        val context = LocalContext.current
        val isTvOrBox = remember(context) {
            val pm = context.packageManager
            val isLeanback = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            isLeanback || isTvMode
        }
        var isSidebarVisible by remember(isTvOrBox) { mutableStateOf(true) }

        Row(modifier = Modifier.fillMaxSize().background(parseHexColor(theme.bg))) {
            if (showSidebar) {
                AnimatedVisibility(
                    visible = isSidebarVisible,
                    enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .background(parseHexColor(theme.surface))
                            .padding(vertical = 16.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .tvClickable { onNavigate("accounts") }
                                .padding(bottom = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(theme.accent).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = currentProfile?.avatar ?: "👤",
                                    fontSize = 20.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = currentProfile?.name ?: "Usuario",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isOnline) Color.Green else Color.Red)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isOnline) Translator.translate("online", lang) else Translator.translate("offline", lang),
                                        color = if (isOnline) Color.LightGray else Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(menuItems) { item ->
                                val isSelected = currentScreen == item.route
                                var isFocused by remember { mutableStateOf(false) }
                                val bg = if (isFocused) TV_FOCUSED_BG else if (isSelected) parseHexColor(theme.accent) else Color.Transparent
                                val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color.White else Color.LightGray

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bg)
                                        .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) { onNavigate(item.route) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        tint = tc,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = item.title,
                                        color = tc,
                                        fontSize = 13.sp,
                                        fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        var isExitFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isExitFocused) TV_FOCUSED_BG else Color.Transparent)
                                .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isExitFocused = it }) { onNavigate("profiles") }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = Translator.translate("exit", lang),
                                tint = if (isExitFocused) TV_FOCUSED_TEXT else Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = Translator.translate("exit", lang),
                                color = if (isExitFocused) TV_FOCUSED_TEXT else Color.Red,
                                fontSize = 13.sp,
                                fontWeight = if (isExitFocused) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                content()

                if (showSidebar && !isSidebarVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 8.dp, start = 8.dp)
                    ) {
                        TvIconButton(
                            onClick = { isSidebarVisible = true },
                            containerColor = parseHexColor(theme.accent).copy(alpha = 0.85f),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) { isFocused ->
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Mostrar menú",
                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
