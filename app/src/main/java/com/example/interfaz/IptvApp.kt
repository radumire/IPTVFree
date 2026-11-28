package com.example.interfaz

import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.LocalTvFocusAccent
import com.example.interfaz.components.LocalTvFocusAccentLight
import com.example.interfaz.components.ProfileAvatarImage
import com.example.interfaz.screens.*
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.interfaz.viewmodel.MoviesViewModel
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.interfaz.viewmodel.PlaylistViewModel
import com.example.interfaz.viewmodel.ProfileViewModel
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.interfaz.viewmodel.SettingsViewModel
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.motor.config.PlayerSettings
import com.example.motor.config.JsonUtils

data class MenuItem(val route: String, val title: String, val icon: ImageVector)

@Composable
fun MainAppTheme(colors: AppThemeColors, content: @Composable () -> Unit) {
    val accent = parseHexColor(colors.accent)
    val accentLight = parseHexColor(colors.accentLight)
    val materialScheme = darkColorScheme(
        primary = accent,
        secondary = accentLight,
        background = parseHexColor(colors.bg),
        surface = parseHexColor(colors.surface),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )

    CompositionLocalProvider(
        LocalTvFocusAccent provides accent,
        LocalTvFocusAccentLight provides accentLight
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            content = content
        )
    }
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
    val sportsViewModel: SportsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val coroutineScope = rememberCoroutineScope()

    val isStalkerDownloading by playlistViewModel.stalkerDownloading.collectAsStateWithLifecycle()
    val stalkerDownloadStats by playlistViewModel.stalkerDownloadStats.collectAsStateWithLifecycle()

    LaunchedEffect(isStalkerDownloading, stalkerDownloadStats) {
        homeViewModel.updateStalkerDownloadState(isStalkerDownloading, stalkerDownloadStats)
    }

    val profileUiState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val currentProfile = profileUiState.currentProfile
    val profilesList = profileUiState.profiles
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf("profiles") }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    
    // Video Player overlays state
    var activePlayerSrc by remember { mutableStateOf<String?>(null) }
    var activePlayerTitle by remember { mutableStateOf("") }
    var activePlayerType by remember { mutableStateOf("live") }
    var activeDrmScheme by remember { mutableStateOf<String?>(null) }
    var activeDrmLicenseUrl by remember { mutableStateOf<String?>(null) }
    var activeStreamHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activePlayerSeriesTitle by remember { mutableStateOf<String?>(null) }
    var activePlayerSeason by remember { mutableStateOf<Int?>(null) }
    var activePlayerEpisode by remember { mutableStateOf<Int?>(null) }
    var activeCategoryName by remember { mutableStateOf("") }
    var activeCategoryChannels by remember { mutableStateOf<List<com.example.motor.db.IptvItem>>(emptyList()) }
    var activeCategoryPlaylistId by remember { mutableStateOf<Long?>(null) }
    var activePlayingChannel by remember { mutableStateOf<com.example.motor.db.IptvItem?>(null) }

    // Navigation Back Stack
    val backStack = remember { mutableStateListOf<String>() }
    val topLevelScreens = remember { setOf("home", "live_tv", "movies", "series", "sports", "favorites", "playlists", "settings", "accounts") }

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
            currentScreen = backStack.removeAt(backStack.lastIndex)
        } else if (currentScreen != "home") {
            currentScreen = "home"
        }
    }

    // Capture system Back button on remote or device:
    // 1. If Exit Confirmation Dialog is open: Back closes the dialog safely.
    // 2. If video player is open: Back closes the player overlay.
    // 3. If backStack is not empty: Back navigates back in stack.
    // 4. If currentScreen != "home" (and not on initial profile selection): Back navigates to "home".
    // 5. If on "home": Back displays the Exit Confirmation Dialog ("¿Seguro que quieres salir?")!
    BackHandler(enabled = true) {
        if (showExitConfirmDialog) {
            showExitConfirmDialog = false
        } else if (activePlayerSrc != null) {
            activePlayerSrc = null
            activeStreamHeaders = emptyMap()
            activeCategoryPlaylistId = null
            activePlayingChannel = null
            if (currentScreen != "live_tv") {
                playerViewModel.stop()
            }
        } else if (backStack.isNotEmpty()) {
            navigateBack()
        } else if (currentScreen != "home" && (currentScreen != "profiles" || currentProfile != null)) {
            navigateTo("home")
        } else {
            showExitConfirmDialog = true
        }
    }

    // Stay on "profiles" until user explicitly picks a profile; if logged out from another screen, return to "profiles"
    LaunchedEffect(currentProfile) {
        if (currentProfile == null && currentScreen != "profiles") {
            activePlayerSrc = null
            activeStreamHeaders = emptyMap()
            activeCategoryPlaylistId = null
            activePlayingChannel = null
            playerViewModel.stop()
            playlistViewModel.cancelConnection()
            backStack.clear()
            currentScreen = "profiles"
        }
    }

    var lastHandledProfileId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(currentProfile?.id) {
        val prof = currentProfile
        if (prof == null) {
            lastHandledProfileId = null
            homeViewModel.setLiveStageSelectedTab(null)
        } else if (prof.id != lastHandledProfileId) {
            lastHandledProfileId = prof.id
            homeViewModel.setLiveStageSelectedTab(null)
            activePlayerSrc = null
            activeStreamHeaders = emptyMap()
            activeCategoryPlaylistId = null
            activePlayingChannel = null
            playerViewModel.stop()
            playlistViewModel.cancelConnection()
            val settingsObj = JsonUtils.settingsFromJson(prof.settingsJson)
            val playlistsList = JsonUtils.playlistsFromJson(prof.playlistsJson)
            val activePl = playlistViewModel.getSafeStartupPlaylist(playlistsList)
            if (activePl != null) {
                playlistViewModel.startupPlaylist(activePl, autoUpdate = settingsObj.autoUpdate)
            } else {
                com.example.motor.repository.MediaCacheRepository.getInstance(
                    context.applicationContext as android.app.Application
                ).setActivePlaylist(-1L)
            }
        }
    }

    var isFullScreenOverlay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // APP SCREEN SWITCHER ALWAYS ACTIVE IN BACKGROUND TO RETAIN STATE
        when (currentScreen) {
            "profiles" -> ProfileSelectorScreen(
                viewModel = profileViewModel,
                onProfileSelected = { p ->
                    activePlayerSrc = null
                    activeStreamHeaders = emptyMap()
                    activeCategoryPlaylistId = null
                    activePlayingChannel = null
                    backStack.clear()
                    profileViewModel.switchProfile(p)
                    currentScreen = "home"
                },
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
                        "home" -> {
                            when (settingsObj.homeLayout) {
                                "modern" -> {
                                    HomeScreen(
                                        viewModel = homeViewModel,
                                        onNavigate = { navigateTo(it) },
                                        onPlay = { src, title, type, dScheme, dLicense, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = type
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            if (type == "series") {
                                                val se = com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)
                                                activePlayerSeriesTitle = com.example.motor.tmdb.TmdbService.cleanSeriesName(title)
                                                activePlayerSeason = se?.season
                                                activePlayerEpisode = se?.episode
                                            } else {
                                                activePlayerSeriesTitle = null
                                                activePlayerSeason = null
                                                activePlayerEpisode = null
                                            }
                                        },
                                        onOpenSeries = { seriesItem ->
                                            seriesViewModel.pendingSelectedSeries.value = seriesItem
                                            navigateTo("series")
                                        },
                                        theme = theme
                                    )
                                }
                                "classic_tiles" -> {
                                    com.example.interfaz.screens.ClassicTvHomeScreen(
                                        viewModel = homeViewModel,
                                        mainViewModel = viewModel,
                                        onNavigate = { navigateTo(it) },
                                        theme = theme
                                    )
                                }
                                "cinema_bar" -> {
                                    CinemaBarHomeScreen(
                                        homeViewModel = homeViewModel,
                                        moviesViewModel = moviesViewModel,
                                        seriesViewModel = seriesViewModel,
                                        sportsViewModel = sportsViewModel,
                                        onNavigate = { navigateTo(it) },
                                        onPlay = { src, title, type, dScheme, dLicense, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = type
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            if (type == "series") {
                                                val se = com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)
                                                activePlayerSeriesTitle = com.example.motor.tmdb.TmdbService.cleanSeriesName(title)
                                                activePlayerSeason = se?.season
                                                activePlayerEpisode = se?.episode
                                            } else {
                                                activePlayerSeriesTitle = null
                                                activePlayerSeason = null
                                                activePlayerEpisode = null
                                            }
                                        },
                                        onPlaySport = { src, title, dScheme, dLicense, matchChannels, matchCat, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = "live"
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            activeCategoryChannels = matchChannels
                                            activeCategoryName = matchCat ?: Translator.translate("sports", settingsObj.lang)
                                            activeCategoryPlaylistId = sportsViewModel.getActivePlaylistId()
                                            activePlayingChannel = sportsViewModel.lastPlayedChannel.value
                                        },
                                        onExitRequest = { showExitConfirmDialog = true },
                                        theme = theme
                                    )
                                }
                                "showcase" -> {
                                    ShowcaseHomeScreen(
                                        homeViewModel = homeViewModel,
                                        liveTvViewModel = liveTvViewModel,
                                        moviesViewModel = moviesViewModel,
                                        seriesViewModel = seriesViewModel,
                                        sportsViewModel = sportsViewModel,
                                        onNavigate = { navigateTo(it) },
                                        onPlayLiveChannel = { src, title, dScheme, dLicense, catName, catChannels, headers, ch ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = "live"
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            activeCategoryName = catName
                                            activeCategoryChannels = catChannels
                                            activeCategoryPlaylistId = liveTvViewModel.getActivePlaylistId()
                                            activePlayingChannel = ch
                                        },
                                        onPlayVod = { src, title, type, dScheme, dLicense, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = type
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            if (type == "series") {
                                                val se = com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)
                                                activePlayerSeriesTitle = com.example.motor.tmdb.TmdbService.cleanSeriesName(title)
                                                activePlayerSeason = se?.season
                                                activePlayerEpisode = se?.episode
                                            } else {
                                                activePlayerSeriesTitle = null
                                                activePlayerSeason = null
                                                activePlayerEpisode = null
                                            }
                                        },
                                        onPlaySport = { src, title, dScheme, dLicense, matchChannels, matchCat, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = "live"
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            activeCategoryChannels = matchChannels
                                            activeCategoryName = matchCat ?: Translator.translate("sports", settingsObj.lang)
                                            activeCategoryPlaylistId = sportsViewModel.getActivePlaylistId()
                                            activePlayingChannel = sportsViewModel.lastPlayedChannel.value
                                        },
                                        onExitRequest = { showExitConfirmDialog = true },
                                        theme = theme
                                    )
                                }
                                "live_stage" -> {
                                    LiveStageHomeScreen(
                                        homeViewModel = homeViewModel,
                                        liveTvViewModel = liveTvViewModel,
                                        moviesViewModel = moviesViewModel,
                                        seriesViewModel = seriesViewModel,
                                        sportsViewModel = sportsViewModel,
                                        settingsViewModel = settingsViewModel,
                                        playerViewModel = playerViewModel,
                                        isFullScreenActive = activePlayerSrc != null,
                                        currentFullscreenChannel = activePlayingChannel,
                                        onNavigate = { navigateTo(it) },
                                        onPlayLiveChannel = { src, title, dScheme, dLicense, catName, catChannels, headers, ch ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = "live"
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            activeCategoryName = catName
                                            activeCategoryChannels = catChannels
                                            activeCategoryPlaylistId = liveTvViewModel.getActivePlaylistId()
                                            activePlayingChannel = ch
                                        },
                                        onPlayVod = { src, title, type, dScheme, dLicense, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = type
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            if (type == "series") {
                                                val se = com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)
                                                activePlayerSeriesTitle = com.example.motor.tmdb.TmdbService.cleanSeriesName(title)
                                                activePlayerSeason = se?.season
                                                activePlayerEpisode = se?.episode
                                            } else {
                                                activePlayerSeriesTitle = null
                                                activePlayerSeason = null
                                                activePlayerEpisode = null
                                            }
                                        },
                                        onPlaySport = { src, title, dScheme, dLicense, matchChannels, matchCat, headers ->
                                            activePlayerSrc = src
                                            activeStreamHeaders = headers
                                            activePlayerTitle = title
                                            activePlayerType = "live"
                                            activeDrmScheme = dScheme
                                            activeDrmLicenseUrl = dLicense
                                            activeCategoryChannels = matchChannels
                                            activeCategoryName = matchCat ?: Translator.translate("sports", settingsObj.lang)
                                            activeCategoryPlaylistId = sportsViewModel.getActivePlaylistId()
                                            activePlayingChannel = sportsViewModel.lastPlayedChannel.value
                                        },
                                        onExitRequest = { showExitConfirmDialog = true },
                                        theme = theme
                                    )
                                }
                                else -> {
                                    com.example.interfaz.screens.VivoTvHomeScreen(
                                        viewModel = homeViewModel,
                                        mainViewModel = viewModel,
                                        playlistViewModel = playlistViewModel,
                                        onNavigate = { navigateTo(it) },
                                        onExitRequest = { showExitConfirmDialog = true },
                                        theme = theme
                                    )
                                }
                            }
                        }
                        "live_tv" -> LiveTVScreen(
                            viewModel = liveTvViewModel,
                            playerViewModel = playerViewModel,
                            onPlay = { src, title, dScheme, dLicense, catName, catChannels ->
                                activePlayerSrc = src
                                activeStreamHeaders = emptyMap()
                                activePlayerTitle = title
                                activePlayerType = "live"
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                                activeCategoryName = catName
                                activeCategoryChannels = catChannels
                                activeCategoryPlaylistId = liveTvViewModel.getActivePlaylistId()
                                activePlayingChannel = catChannels.firstOrNull {
                                    !it.url.isNullOrBlank() && it.url == src
                                } ?: catChannels.firstOrNull {
                                    it.name.equals(title, ignoreCase = true)
                                }
                            },
                            onBack = { navigateTo("home") },
                            theme = theme,
                            isFullScreenActive = activePlayerSrc != null
                        )
                        "movies" -> MoviesScreen(
                            viewModel = moviesViewModel,
                            onPlay = { src, title, dScheme, dLicense ->
                                activePlayerSrc = src
                                activeStreamHeaders = emptyMap()
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
                            onPlay = { src, title, dScheme, dLicense, sTitle, sNum, eNum ->
                                activePlayerSrc = src
                                activeStreamHeaders = emptyMap()
                                activePlayerTitle = title
                                activePlayerType = "series"
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                                activePlayerSeriesTitle = sTitle
                                activePlayerSeason = sNum
                                activePlayerEpisode = eNum
                            },
                            onBack = { navigateTo("home") },
                            theme = theme
                        )
                        "sports" -> SportsScreen(
                            viewModel = sportsViewModel,
                            onPlay = { src, title, dScheme, dLicense, matchChannels, matchCategoryName, headers ->
                                activePlayerSrc = src
                                activeStreamHeaders = headers
                                activePlayerTitle = title
                                activePlayerType = "live"
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                                activeCategoryChannels = matchChannels
                                activeCategoryName = matchCategoryName ?: Translator.translate("sports", settingsObj.lang)
                                activeCategoryPlaylistId = sportsViewModel.getActivePlaylistId()
                                activePlayingChannel = sportsViewModel.lastPlayedChannel.value
                            },
                            onBack = { navigateTo("home") },
                            theme = theme,
                            lang = settingsObj.lang
                        )
                        "favorites" -> com.example.interfaz.screens.FavoritesScreen(
                            viewModel = homeViewModel,
                            onPlay = { src, title, type, dScheme, dLicense, headers, categoryChannels ->
                                activePlayerSrc = src
                                activeStreamHeaders = headers
                                activePlayerTitle = title
                                activePlayerType = type
                                activeDrmScheme = dScheme
                                activeDrmLicenseUrl = dLicense
                                if (type == "live") {
                                    activeCategoryChannels = categoryChannels
                                    activeCategoryName = Translator.translate("favorites", settingsObj.lang)
                                    activeCategoryPlaylistId = liveTvViewModel.getActivePlaylistId()
                                    activePlayingChannel = categoryChannels.firstOrNull {
                                        !it.url.isNullOrBlank() && it.url == src
                                    } ?: categoryChannels.firstOrNull {
                                        it.name.equals(title, ignoreCase = true)
                                    }
                                    activePlayerSeriesTitle = null
                                    activePlayerSeason = null
                                    activePlayerEpisode = null
                                } else {
                                    activeCategoryChannels = emptyList()
                                    activeCategoryName = ""
                                    activeCategoryPlaylistId = null
                                    activePlayingChannel = null
                                    if (type == "series") {
                                        val se = com.example.motor.tmdb.TmdbService.extractSeasonEpisode(title)
                                        activePlayerSeriesTitle = com.example.motor.tmdb.TmdbService.cleanSeriesName(title)
                                        activePlayerSeason = se?.season
                                        activePlayerEpisode = se?.episode
                                    } else {
                                        activePlayerSeriesTitle = null
                                        activePlayerSeason = null
                                        activePlayerEpisode = null
                                    }
                                }
                            },
                            onOpenSeries = { seriesItem ->
                                seriesViewModel.pendingSelectedSeries.value = seriesItem
                                navigateTo("series")
                            },
                            onNavigateCatalog = { route -> navigateTo(route) },
                            onBack = { navigateTo("home") },
                            theme = theme,
                            lang = settingsObj.lang
                        )
                        "playlists" -> PlaylistsScreen(
                            viewModel = playlistViewModel,
                            onBack = { navigateTo("home") },
                            theme = theme
                        )
                        "accounts" -> AccountsScreen(
                            viewModel = profileViewModel,
                            onLogout = {
                                activePlayerSrc = null
                                activeStreamHeaders = emptyMap()
                                activeCategoryPlaylistId = null
                                activePlayingChannel = null
                                playerViewModel.stop()
                                playlistViewModel.cancelConnection()
                                backStack.clear()
                                profileViewModel.logout()
                                currentScreen = "profiles"
                            },
                            onBack = { navigateTo("home") },
                            theme = theme
                        )
                        "settings" -> SettingsScreen(
                            viewModel = settingsViewModel,
                            theme = theme,
                            onFullScreenChange = { isFullScreenOverlay = it },
                            onBack = { navigateTo("home") }
                        )
                    }
                }
            }
        }

        if (activePlayerSrc != null) {
            val pSettings = currentProfile?.let { JsonUtils.settingsFromJson(it.settingsJson) } ?: PlayerSettings()
            val streamHeaders = remember(activePlayerSrc, activeStreamHeaders, currentProfile?.id) {
                if (activeStreamHeaders.isNotEmpty()) activeStreamHeaders else playerViewModel.getActiveStreamHeaders()
            }
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
                        val progress = playerViewModel.getPlaybackProgress(activePlayerSrc!!)
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
                        subtitleEnabledSetting = pSettings.subtitles,
                        initialPositionSec = initialPosSec!!,
                        drmScheme = activeDrmScheme,
                        drmLicenseUrl = activeDrmLicenseUrl,
                        headers = streamHeaders,
                        stopOnDispose = (currentScreen != "live_tv"),
                        onBack = {
                            activePlayerSrc = null
                            activeStreamHeaders = emptyMap()
                            activePlayerSeriesTitle = null
                            activePlayerSeason = null
                            activePlayerEpisode = null
                            activeCategoryName = ""
                            activeCategoryChannels = emptyList()
                            activeCategoryPlaylistId = null
                            activePlayingChannel = null
                            if (currentScreen != "live_tv") {
                                playerViewModel.stop()
                            }
                        },
                        onProgressUpdate = { pos, dur ->
                            activePlayerSrc?.let { src ->
                                playerViewModel.savePlaybackProgress(src, activePlayerTitle, pos, dur, activePlayerType)
                            }
                        },
                        mediaType = activePlayerType,
                        seriesTitle = activePlayerSeriesTitle,
                        season = activePlayerSeason,
                        episode = activePlayerEpisode,
                        categoryChannels = activeCategoryChannels,
                        categoryName = activeCategoryName,
                        currentChannel = activePlayingChannel,
                        onSelectChannel = { channel ->
                            val currentPlaylistId = liveTvViewModel.getActivePlaylistId()
                            if (activeCategoryPlaylistId != null && currentPlaylistId != activeCategoryPlaylistId) {
                                android.widget.Toast.makeText(
                                    context,
                                    Translator.translate("player_zap_playlist_changed", pSettings.lang),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                coroutineScope.launch {
                                    try {
                                        val (resolvedUrl, resolvedHeaders) = liveTvViewModel.resolveStream(channel)
                                        if (liveTvViewModel.getActivePlaylistId() != currentPlaylistId) {
                                            android.widget.Toast.makeText(
                                                context,
                                                Translator.translate("player_zap_playlist_changed", pSettings.lang),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            return@launch
                                        }
                                        if (resolvedUrl.isNotBlank()) {
                                            activePlayerSrc = resolvedUrl
                                            activeStreamHeaders = resolvedHeaders
                                            activePlayerTitle = channel.name
                                            activePlayingChannel = channel
                                            activeDrmScheme = channel.drmScheme
                                            activeDrmLicenseUrl = channel.drmLicenseUrl
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                Translator.translate("player_zap_url_error", pSettings.lang),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            Translator.translate("player_zap_switch_error", pSettings.lang, e.message ?: ""),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        com.example.interfaz.components.OrbitalStreamingLoader(
                            size = 68.dp,
                            accentColor = parseHexColor(theme.accent)
                        )
                    }
                }
            }
        }

        if (showExitConfirmDialog) {
            com.example.interfaz.components.ExitConfirmationDialog(
                onConfirm = {
                    showExitConfirmDialog = false
                    (context as? android.app.Activity)?.finishAffinity()
                },
                onDismiss = {
                    showExitConfirmDialog = false
                },
                theme = theme
            )
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
        MenuItem("sports", Translator.translate("sports", lang), Icons.Default.SportsSoccer),
        MenuItem("favorites", Translator.translate("favorites", lang), Icons.Default.Favorite),
        MenuItem("playlists", Translator.translate("playlists", lang), Icons.Default.CloudDownload),
        MenuItem("settings", Translator.translate("settings", lang), Icons.Default.Settings),
        MenuItem("accounts", Translator.translate("accounts", lang), Icons.Default.People)
    )

    val bottomMenuItems = listOf(
        MenuItem("home", Translator.translate("home", lang), Icons.Default.Home),
        MenuItem("live_tv", Translator.translate("live_tv", lang), Icons.Default.Tv),
        MenuItem("movies", Translator.translate("movies", lang), Icons.Default.Movie),
        MenuItem("series", Translator.translate("series", lang), Icons.Default.VideoLibrary),
        MenuItem("sports", Translator.translate("sports", lang), Icons.Default.SportsSoccer),
        MenuItem("favorites", Translator.translate("favorites", lang), Icons.Default.Favorite),
        MenuItem("playlists", Translator.translate("playlists", lang), Icons.Default.CloudDownload),
        MenuItem("settings", Translator.translate("settings", lang), Icons.Default.Settings)
    )

    val isClassicHome = currentScreen == "home" && settingsObj.homeLayout != "modern"

    if (isPortrait) {
        if (isFullScreenOverlay || isClassicHome) {
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
                    ProfileAvatarImage(
                        avatarKey = currentProfile?.avatar,
                        size = 36.dp,
                        accentColor = parseHexColor(theme.accent),
                        contentDescription = currentProfile?.name ?: "Perfil",
                        modifier = Modifier.tvClickable(shape = CircleShape) { onNavigate("accounts") }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (currentScreen) {
                                "home" -> "Radu IPTV"
                                "live_tv" -> Translator.translate("live_tv", lang)
                                "movies" -> Translator.translate("movies", lang)
                                "series" -> Translator.translate("series", lang)
                                "sports" -> Translator.translate("sports", lang)
                                "favorites" -> Translator.translate("favorites", lang)
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

                    TvIconButton(
                        onClick = { onNavigate("settings") },
                        containerColor = Color.Transparent,
                        shape = CircleShape
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = Translator.translate("settings", lang),
                            tint = if (isFocused) TV_FOCUSED_TEXT else if (currentScreen == "settings") parseHexColor(theme.accent) else Color.White,
                            modifier = Modifier.size(24.dp)
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
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .tvClickable { onNavigate(item.route) }
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.title,
                                color = tint,
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    } else {
        val showSidebar = currentScreen !in listOf("live_tv", "movies", "series", "sports", "favorites") && !isClassicHome && !isFullScreenOverlay
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
                            ProfileAvatarImage(
                                avatarKey = currentProfile?.avatar,
                                size = 44.dp,
                                accentColor = parseHexColor(theme.accent),
                                contentDescription = currentProfile?.name ?: "Perfil"
                            )
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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(menuItems) { item ->
                                val isSelected = currentScreen == item.route
                                var isFocused by remember { mutableStateOf(false) }
                                val bg = if (isFocused) parseHexColor(theme.cardHover)
                                    else if (isSelected) parseHexColor(theme.accent).copy(alpha = 0.65f)
                                    else parseHexColor(theme.card).copy(alpha = 0.6f)
                                val borderCol = if (isFocused) parseHexColor(theme.accent)
                                    else if (isSelected) parseHexColor(theme.accentLight).copy(alpha = 0.8f)
                                    else Color.White.copy(alpha = 0.12f)
                                val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color.White else Color.LightGray

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, borderCol, RoundedCornerShape(8.dp))
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
                                        fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
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
