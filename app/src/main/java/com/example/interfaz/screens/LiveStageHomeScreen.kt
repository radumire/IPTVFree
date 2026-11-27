package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.Translator
import com.example.interfaz.AppThemeColors
import com.example.interfaz.ParentalControl
import com.example.interfaz.ParentalPinDialog
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.interfaz.viewmodel.MoviesViewModel
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.interfaz.viewmodel.SettingsViewModel
import com.example.interfaz.viewmodel.SportsUiState
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.repository.IptvRepository
import com.example.motor.sports.model.SportsEvent
import com.example.parseHexColor
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class LiveStageTab(
    val titleKey: String,
    val route: String,
    val icon: ImageVector,
    val testTag: String
) {
    TV("tab_tv_short", "live_tv", Icons.Default.LiveTv, "livestage_tab_tv"),
    SPORTS("sports", "sports", Icons.Default.SportsSoccer, "livestage_tab_sports"),
    FAVORITES("favorites", "favorites", Icons.Default.Favorite, "livestage_tab_favorites"),
    MOVIES("movies", "movies", Icons.Default.Movie, "livestage_tab_movies"),
    SERIES("series", "series", Icons.Default.Subscriptions, "livestage_tab_series"),
    PLAYLISTS("playlists", "playlists", Icons.Default.Bookmark, "livestage_tab_playlists"),
    SETTINGS("settings", "settings", Icons.Default.Settings, "livestage_tab_settings")
}

/**
 * Picks the visible Live TV category to load in Interface 6:
 * 1) First/best visible Spanish category
 * 2) Otherwise first/best visible Romanian category
 * 3) Otherwise the first visible non-empty category in the active playlist
 */
internal fun pickPreferredSpanishOrRomanianTvCategory(
    liveChannels: Map<String, List<IptvItem>>,
    hiddenTvCategories: List<String>
): Pair<String, List<IptvItem>>? {
    if (liveChannels.isEmpty()) return null
    val hiddenLower = hiddenTvCategories.map { it.trim().lowercase() }.toSet()
    val visibleEntries = liveChannels.entries.filter { (cat, channels) ->
        channels.isNotEmpty() &&
            cat !in hiddenTvCategories &&
            cat.trim().lowercase() !in hiddenLower
    }
    if (visibleEntries.isEmpty()) return null

    fun normalizeCat(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return decomposed.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").lowercase().trim()
    }

    val adultRegex = Regex("(\\badult\\b|\\bxxx\\b|\\b18\\+|\\bporn\\b)")

    val esPrefixRegex = Regex("^(es|esp)(\\b|[\\s|:\\-_/\\]])")
    val esPrimaryRegex = Regex("(\\[es\\]|\\|es\\||\\besp\\b|\\bespan[ao]\\b|\\bespanol\\b|\\bspain\\b|\\btdt\\b|\\bcastellano\\b)")
    val esSecondaryRegex = Regex("(\\bspa\\b|\\bspanish\\b|\\bmovistar\\b|\\blaliga\\b|\\bla liga\\b|\\bdazn es\\b)")
    val esTertiaryRegex = Regex("(\\blatino\\b|\\blatinoamerica\\b|\\bmexico\\b|\\bargentina\\b|\\bcolombia\\b)")

    val spanishCandidate = visibleEntries.mapNotNull { entry ->
        val norm = normalizeCat(entry.key)
        if (adultRegex.containsMatchIn(norm)) return@mapNotNull null
        val langScore = when {
            esPrimaryRegex.containsMatchIn(norm) || esPrefixRegex.containsMatchIn(norm) -> 300
            esSecondaryRegex.containsMatchIn(norm) -> 200
            esTertiaryRegex.containsMatchIn(norm) -> 100
            else -> 0
        }
        if (langScore > 0) entry to (langScore + if (entry.value.size >= 5) 10 else 0) else null
    }.maxByOrNull { it.second }?.first

    if (spanishCandidate != null) {
        return spanishCandidate.key to spanishCandidate.value
    }

    val roPrefixRegex = Regex("^(ro|rom|rou)(\\b|[\\s|:\\-_/\\]])")
    val roPrimaryRegex = Regex("(\\[ro\\]|\\|ro\\||\\brom\\b|\\bromania\\b|\\bromana\\b|\\broman[ae]\\b|\\bdigi\\b|\\bprotv\\b|\\bantena\\b|\\bkanald\\b|\\bprima tv\\b|\\btvr\\b)")

    val romanianCandidate = visibleEntries.mapNotNull { entry ->
        val norm = normalizeCat(entry.key)
        if (adultRegex.containsMatchIn(norm)) return@mapNotNull null
        val langScore = when {
            roPrimaryRegex.containsMatchIn(norm) || roPrefixRegex.containsMatchIn(norm) -> 300
            else -> 0
        }
        if (langScore > 0) entry to (langScore + if (entry.value.size >= 5) 10 else 0) else null
    }.maxByOrNull { it.second }?.first

    if (romanianCandidate != null) {
        return romanianCandidate.key to romanianCandidate.value
    }

    val firstVisible = visibleEntries.first()
    return firstVisible.key to firstVisible.value
}

@Composable
fun LiveStageHomeScreen(
    homeViewModel: HomeViewModel,
    liveTvViewModel: LiveTvViewModel,
    moviesViewModel: MoviesViewModel,
    seriesViewModel: SeriesViewModel,
    sportsViewModel: SportsViewModel,
    settingsViewModel: SettingsViewModel,
    playerViewModel: PlayerViewModel,
    isFullScreenActive: Boolean,
    currentFullscreenChannel: IptvItem?,
    onNavigate: (String) -> Unit,
    onPlayLiveChannel: (
        src: String,
        title: String,
        drmScheme: String?,
        drmLicenseUrl: String?,
        categoryName: String,
        categoryChannels: List<IptvItem>,
        headers: Map<String, String>,
        channel: IptvItem
    ) -> Unit,
    onPlayVod: (
        src: String,
        title: String,
        type: String,
        drmScheme: String?,
        drmLicenseUrl: String?,
        headers: Map<String, String>
    ) -> Unit,
    onPlaySport: (
        src: String,
        title: String,
        drmScheme: String?,
        drmLicenseUrl: String?,
        matchChannels: List<IptvItem>,
        matchCategoryName: String?,
        headers: Map<String, String>
    ) -> Unit,
    onExitRequest: () -> Unit,
    theme: AppThemeColors
) {
    val settings by homeViewModel.settings.collectAsStateWithLifecycle()
    val activePlaylist by homeViewModel.activePlaylist.collectAsStateWithLifecycle()
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val liveChannels by homeViewModel.liveChannels.collectAsStateWithLifecycle()
    val newestMovies by homeViewModel.newestMovies.collectAsStateWithLifecycle()
    val precomputedMoviesData by moviesViewModel.precomputedMoviesData.collectAsStateWithLifecycle()
    val precomputedSeriesData by seriesViewModel.precomputedSeriesData.collectAsStateWithLifecycle()
    val sportsUiState by sportsViewModel.uiState.collectAsStateWithLifecycle()
    val isOnline by homeViewModel.isOnline.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val lang = settings.lang

    var selectedPreviewChannel by remember { mutableStateOf<IptvItem?>(null) }
    var previewStreamUrl by remember { mutableStateOf("") }
    var previewStreamHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isResolvingPreview by remember { mutableStateOf(false) }
    var isParentalUnlocked by remember(activePlaylist?.id) { mutableStateOf(false) }
    var showPinDialogForChannel by remember { mutableStateOf<IptvItem?>(null) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activePlaylist?.id) {
        selectedPreviewChannel = null
        previewStreamUrl = ""
        previewStreamHeaders = emptyMap()
        moviesViewModel.ensureMoviesLoaded()
        seriesViewModel.ensureSeriesLoaded()
        sportsViewModel.loadData()
    }

    LaunchedEffect(noticeMessage) {
        if (noticeMessage != null) {
            delay(4000L)
            noticeMessage = null
        }
    }

    val pickedTvCategory = remember(liveChannels, settings.hiddenTvCategories) {
        pickPreferredSpanishOrRomanianTvCategory(liveChannels, settings.hiddenTvCategories)
    }
    val categoryName = pickedTvCategory?.first ?: Translator.translate("live_tv", lang)
    val categoryChannels = pickedTvCategory?.second.orEmpty()

    // Ensure first channel starts automatically when category channels load
    LaunchedEffect(categoryChannels, activePlaylist?.id) {
        if (categoryChannels.isEmpty()) {
            selectedPreviewChannel = null
            previewStreamUrl = ""
            previewStreamHeaders = emptyMap()
        } else if (selectedPreviewChannel == null || selectedPreviewChannel !in categoryChannels) {
            selectedPreviewChannel = categoryChannels.first()
        }
    }

    // Sync active channel if user zapped while inside full-screen VideoPlayer
    LaunchedEffect(isFullScreenActive, currentFullscreenChannel) {
        if (!isFullScreenActive && currentFullscreenChannel != null && currentFullscreenChannel in categoryChannels) {
            selectedPreviewChannel = currentFullscreenChannel
        }
    }

    // Resolve stream URL whenever selectedPreviewChannel changes
    LaunchedEffect(selectedPreviewChannel, activePlaylist?.id, isParentalUnlocked) {
        val ch = selectedPreviewChannel
        if (ch == null) {
            previewStreamUrl = ""
            previewStreamHeaders = emptyMap()
            isResolvingPreview = false
            return@LaunchedEffect
        }
        val locked = settings.parentalEnabled &&
            ParentalControl.isChannelRestricted(ch, settings) &&
            !isParentalUnlocked
        if (locked) {
            previewStreamUrl = ""
            previewStreamHeaders = emptyMap()
            isResolvingPreview = false
            return@LaunchedEffect
        }

        val startPlId = liveTvViewModel.getActivePlaylistId()
        previewStreamUrl = ""
        previewStreamHeaders = emptyMap()
        isResolvingPreview = true
        try {
            val (resolvedUrl, resolvedHeaders) = liveTvViewModel.resolveStream(ch)
            if (liveTvViewModel.getActivePlaylistId() == startPlId && selectedPreviewChannel == ch) {
                previewStreamUrl = resolvedUrl
                previewStreamHeaders = resolvedHeaders
            }
        } catch (_: Exception) {
            if (selectedPreviewChannel == ch) {
                previewStreamUrl = ch.url ?: ""
                previewStreamHeaders = emptyMap()
            }
        } finally {
            if (selectedPreviewChannel == ch) {
                isResolvingPreview = false
            }
        }
    }

    LiveStageHomeContent(
        settings = settings,
        activePlaylist = activePlaylist,
        isOnline = isOnline,
        totalLiveCount = homeUiState.totalLiveCount,
        totalMoviesCount = homeUiState.totalMoviesCount,
        totalSeriesCount = homeUiState.totalSeriesCount,
        categoryChannels = categoryChannels,
        selectedPreviewChannel = selectedPreviewChannel,
        isChannelLocked = { ch ->
            settings.parentalEnabled &&
                ParentalControl.isChannelRestricted(ch, settings) &&
                !isParentalUnlocked
        },
        isResolvingPreview = isResolvingPreview,
        hasPreviewStream = previewStreamUrl.isNotBlank(),
        newestMovies = newestMovies,
        precomputedMoviesData = precomputedMoviesData,
        precomputedSeriesData = precomputedSeriesData,
        sportsUiState = sportsUiState,
        favorites = homeUiState.favorites,
        onSelectPreviewChannel = { ch ->
            val locked = settings.parentalEnabled &&
                ParentalControl.isChannelRestricted(ch, settings) &&
                !isParentalUnlocked
            selectedPreviewChannel = ch
            if (locked) {
                showPinDialogForChannel = ch
            }
        },
        onOpenFullscreenPreview = {
            val ch = selectedPreviewChannel ?: return@LiveStageHomeContent
            val locked = settings.parentalEnabled &&
                ParentalControl.isChannelRestricted(ch, settings) &&
                !isParentalUnlocked
            if (locked) {
                showPinDialogForChannel = ch
            } else if (previewStreamUrl.isNotBlank()) {
                onPlayLiveChannel(
                    previewStreamUrl,
                    ch.name,
                    ch.drmScheme,
                    ch.drmLicenseUrl,
                    categoryName,
                    categoryChannels,
                    previewStreamHeaders,
                    ch
                )
            } else {
                scope.launch {
                    val (resolvedUrl, resolvedHeaders) = liveTvViewModel.resolveStream(ch)
                    if (resolvedUrl.isNotBlank()) {
                        onPlayLiveChannel(
                            resolvedUrl,
                            ch.name,
                            ch.drmScheme,
                            ch.drmLicenseUrl,
                            categoryName,
                            categoryChannels,
                            resolvedHeaders,
                            ch
                        )
                    } else {
                        noticeMessage = Translator.translate("player_zap_url_error", lang)
                    }
                }
            }
        },
        onMatchClick = { event ->
            sportsViewModel.matchAndPlayEvent(
                event = event,
                onPlay = onPlaySport,
                onNotice = { noticeMessage = it }
            )
        },
        onMovieClick = { movie ->
            moviesViewModel.pendingSelectedMovie.value = movie
            onNavigate("movies")
        },
        onSeriesClick = { series ->
            seriesViewModel.pendingSelectedSeries.value = series
            onNavigate("series")
        },
        onFavoriteClick = { fav ->
            val normType = IptvRepository.normalizeFavoriteType(fav.type)
            if (normType == "movie") {
                val matchedMovie = precomputedMoviesData?.allMovies?.firstOrNull {
                    IptvRepository.matchesFavorite(it, "movie", fav)
                } ?: IptvRepository.toFallbackIptvItem(fav)
                moviesViewModel.pendingSelectedMovie.value = matchedMovie
                onNavigate("movies")
            } else {
                scope.launch {
                    homeViewModel.setResolvingMovie(true)
                    try {
                        when (val res = homeViewModel.resolveFavorite(fav)) {
                            is HomeViewModel.FavoriteResolution.PlayStream -> {
                                if (res.type == "live") {
                                    val favChannels = homeViewModel.getFavoriteTvChannelPeers(homeUiState.favorites)
                                    val matchedCh = favChannels.firstOrNull {
                                        IptvRepository.matchesFavorite(it, "tv", fav)
                                    } ?: IptvRepository.toFallbackIptvItem(fav).copy(url = res.url)
                                    onPlayLiveChannel(
                                        res.url,
                                        res.title,
                                        res.drmScheme,
                                        res.drmLicenseUrl,
                                        Translator.translate("favorites", lang),
                                        favChannels,
                                        res.headers,
                                        matchedCh
                                    )
                                } else {
                                    onPlayVod(res.url, res.title, res.type, res.drmScheme, res.drmLicenseUrl, res.headers)
                                }
                            }
                            is HomeViewModel.FavoriteResolution.OpenSeries -> {
                                seriesViewModel.pendingSelectedSeries.value = res.seriesItem
                                onNavigate("series")
                            }
                            is HomeViewModel.FavoriteResolution.Error -> {
                                noticeMessage = res.message
                            }
                        }
                    } finally {
                        homeViewModel.setResolvingMovie(false)
                    }
                }
            }
        },
        onSelectInterface = { layoutId ->
            settingsViewModel.updateCurrentProfileField(settings = settings.copy(homeLayout = layoutId))
        },
        onNavigate = onNavigate,
        onExitRequest = onExitRequest,
        theme = theme,
        videoPreviewSlot = {
            LivePreviewPlayer(
                playerViewModel = playerViewModel,
                src = previewStreamUrl,
                headers = previewStreamHeaders,
                isFullScreenActive = isFullScreenActive,
                subtitleColor = settings.subtitleColor,
                subtitleBg = settings.subtitleBg,
                subtitleSize = settings.subtitleSize,
                modifier = Modifier.fillMaxSize()
            )
        }
    )

    // Parental PIN unlock prompt for restricted preview/channel
    val pendingPinChannel = showPinDialogForChannel
    if (pendingPinChannel != null) {
        ParentalPinDialog(
            channelName = pendingPinChannel.name,
            expectedPin = settings.parentalPin,
            onDismiss = { showPinDialogForChannel = null },
            onSuccess = {
                isParentalUnlocked = true
                showPinDialogForChannel = null
            },
            theme = theme,
            lang = lang
        )
    }

    // Sports multi-channel selector dialog (same flow as Interface 4)
    val selectionState = sportsUiState.channelSelectionState
    if (selectionState != null) {
        SportsChannelSelectionDialog(
            selectionState = selectionState,
            onSelect = { candidate ->
                sportsViewModel.selectCandidateToPlay(
                    candidate = candidate,
                    onPlay = onPlaySport,
                    onNotice = { noticeMessage = it }
                )
            },
            onDismiss = { sportsViewModel.dismissChannelSelection() },
            theme = theme,
            lang = lang,
            isLockedChannel = { sportsViewModel.isChannelParentalLocked(it) }
        )
    }

    val pendingSportsParental = sportsUiState.pendingParentalUnlock
    if (pendingSportsParental != null) {
        ParentalPinDialog(
            channelName = pendingSportsParental.candidate.channel.name,
            expectedPin = sportsViewModel.getExpectedParentalPin(),
            onDismiss = { sportsViewModel.dismissParentalPrompt() },
            onSuccess = {
                sportsViewModel.confirmParentalUnlockAndPlay(
                    onPlay = onPlaySport,
                    onNotice = { noticeMessage = it }
                )
            },
            theme = theme,
            lang = lang
        )
    }

    if (sportsUiState.isMatchingChannel || homeUiState.isResolvingMovie) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = parseHexColor(theme.surface),
                border = BorderStroke(1.5.dp, parseHexColor(theme.accent))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(
                        color = parseHexColor(theme.accentLight),
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = Translator.translate("sports_searching_channel", lang),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = noticeMessage != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.BottomCenter)
            .padding(bottom = 52.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = parseHexColor(theme.surface).copy(alpha = 0.96f),
            border = BorderStroke(1.5.dp, parseHexColor(theme.accentLight)),
            shadowElevation = 10.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = parseHexColor(theme.accentLight),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = noticeMessage.orEmpty(),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun LiveStageHomeContent(
    settings: PlayerSettings,
    activePlaylist: Playlist?,
    isOnline: Boolean,
    totalLiveCount: Int,
    totalMoviesCount: Int,
    totalSeriesCount: Int,
    categoryChannels: List<IptvItem>,
    selectedPreviewChannel: IptvItem?,
    isChannelLocked: (IptvItem) -> Boolean,
    isResolvingPreview: Boolean,
    hasPreviewStream: Boolean,
    newestMovies: List<IptvItem>,
    precomputedMoviesData: PrecomputedMoviesData?,
    precomputedSeriesData: PrecomputedSeriesData?,
    sportsUiState: SportsUiState,
    favorites: List<FavoriteItem>,
    onSelectPreviewChannel: (IptvItem) -> Unit,
    onOpenFullscreenPreview: () -> Unit,
    onMatchClick: (SportsEvent) -> Unit,
    onMovieClick: (IptvItem) -> Unit,
    onSeriesClick: (IptvItem) -> Unit,
    onFavoriteClick: (FavoriteItem) -> Unit,
    onSelectInterface: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onExitRequest: () -> Unit,
    theme: AppThemeColors,
    videoPreviewSlot: @Composable () -> Unit = {}
) {
    val lang = settings.lang
    val themeBg = remember(theme) { parseHexColor(theme.bg) }
    val themeSurface = remember(theme) { parseHexColor(theme.surface) }
    val themeCard = remember(theme) { parseHexColor(theme.card) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }
    val themeAccentLight = remember(theme) { parseHexColor(theme.accentLight) }

    // Startup requirement: NO tab visually highlighted at launch, while TV view is shown below
    var highlightedTab by remember { mutableStateOf<LiveStageTab?>(null) }
    var activeContentTab by remember { mutableStateOf(LiveStageTab.TV) }
    var focusedTab by remember { mutableStateOf<LiveStageTab?>(null) }
    var isProfilesFocused by remember { mutableStateOf(false) }
    var consumeInitialConfirmUp by remember { mutableStateOf(false) }

    val startupSentinelFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember {
        LiveStageTab.entries.associateWith { FocusRequester() }
    }

    LaunchedEffect(Unit) {
        delay(120L)
        if (highlightedTab == null && focusedTab == null && !isProfilesFocused) {
            try {
                startupSentinelFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = true) {
        onExitRequest()
    }

    // Bottom-right clock & date (replacing the Telegram badge from screenshot)
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    LaunchedEffect(settings.timeFormat, lang) {
        val locale = when (lang.lowercase()) {
            "ro" -> Locale("ro", "RO")
            "en" -> Locale.US
            else -> Locale("es", "ES")
        }
        val timePattern = if (settings.timeFormat == "12h") "hh:mm a" else "HH:mm"
        val datePattern = if (lang.lowercase() == "en") "MMM dd, yyyy" else "dd MMM yyyy"
        val timeFormat = SimpleDateFormat(timePattern, locale)
        val dateFormat = SimpleDateFormat(datePattern, locale)
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
            delay(1000L)
        }
    }

    val hiddenMoviesSet = remember(settings.hiddenMovieCategories) {
        settings.hiddenMovieCategories.map { it.trim().lowercase() }.toSet()
    }
    val featuredMovies = remember(newestMovies, precomputedMoviesData, hiddenMoviesSet, settings.hiddenMovieCategories) {
        val data = precomputedMoviesData
        val pool = if (hiddenMoviesSet.isEmpty()) {
            if (newestMovies.isNotEmpty()) newestMovies else data?.allMovies.orEmpty()
        } else {
            val validNewest = newestMovies.filter { m ->
                val g = m.group?.trim().orEmpty()
                g.isNotEmpty() && g !in settings.hiddenMovieCategories && g.lowercase() !in hiddenMoviesSet
            }
            val allowed = data?.groupToMovies
                ?.filterKeys { cat -> cat !in settings.hiddenMovieCategories && cat.trim().lowercase() !in hiddenMoviesSet }
                ?.values
                ?.flatten()
                .orEmpty()
            (validNewest + allowed).distinctBy { it.name.trim().lowercase() }
        }
        pool.sortedByDescending { !it.logo.isNullOrBlank() }.take(24)
    }

    val hiddenSeriesSet = remember(settings.hiddenSeriesCategories) {
        settings.hiddenSeriesCategories.map { it.trim().lowercase() }.toSet()
    }
    val featuredSeries = remember(precomputedSeriesData, hiddenSeriesSet, settings.hiddenSeriesCategories) {
        val data = precomputedSeriesData ?: return@remember emptyList()
        val pool = if (hiddenSeriesSet.isEmpty()) {
            data.allSeriesUnique
        } else {
            data.groupToUniqueSeries
                .filterKeys { cat -> cat !in settings.hiddenSeriesCategories && cat.trim().lowercase() !in hiddenSeriesSet }
                .values
                .flatten()
                .distinctBy { if (!it.seriesId.isNullOrBlank()) it.seriesId else it.name.trim().lowercase() }
        }
        pool.sortedByDescending { !it.logo.isNullOrBlank() }.take(24)
    }

    val featuredSports = remember(sportsUiState.liveEvents, sportsUiState.todayEvents) {
        (sportsUiState.liveEvents + sportsUiState.todayEvents).distinctBy { it.id }
    }

    fun isRemoteNavigationKey(key: Key): Boolean {
        return key == Key.DirectionUp ||
            key == Key.DirectionDown ||
            key == Key.DirectionLeft ||
            key == Key.DirectionRight ||
            key == Key.DirectionCenter ||
            key == Key.Enter ||
            key == Key.NumPadEnter ||
            key == Key.Spacebar ||
            key == Key.ButtonA ||
            key == Key.Tab
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(themeBg, themeSurface.copy(alpha = 0.94f), themeBg)
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(themeAccent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.85f),
                        radius = size.maxDimension * 0.55f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(themeAccentLight.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.25f),
                        radius = size.maxDimension * 0.50f
                    )
                )
            }
            .onPreviewKeyEvent { event ->
                if (highlightedTab == null && focusedTab == null && !isProfilesFocused && isRemoteNavigationKey(event.key)) {
                    if (event.type == KeyEventType.KeyDown) {
                        val isConfirm = event.key == Key.DirectionCenter ||
                            event.key == Key.Enter ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.Spacebar ||
                            event.key == Key.ButtonA
                        if (isConfirm) {
                            consumeInitialConfirmUp = true
                        }
                        try {
                            tabFocusRequesters[LiveStageTab.TV]?.requestFocus()
                        } catch (_: Exception) {}
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyUp) {
                        consumeInitialConfirmUp = false
                        return@onPreviewKeyEvent true
                    }
                } else if (consumeInitialConfirmUp && event.type == KeyEventType.KeyUp) {
                    val isConfirm = event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.Spacebar ||
                        event.key == Key.ButtonA
                    if (isConfirm) {
                        consumeInitialConfirmUp = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
    ) {
        // Hidden zero-size startup focus sentinel so none of the 7 top tabs looks selected on startup
        Box(
            modifier = Modifier
                .size(1.dp)
                .testTag("livestage_startup_sentinel")
                .focusRequester(startupSentinelFocusRequester)
                .focusable()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ─── TOP NAVIGATION BAR (7 TABS, NO HEADER ABOVE) ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveStageTab.entries.forEach { tab ->
                    val isTabHighlighted = highlightedTab == tab
                    val isTabFocused = focusedTab == tab
                    val label = Translator.translate(tab.titleKey, lang)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag(tab.testTag)
                            .tvCardFocusable(
                                shape = RoundedCornerShape(10.dp),
                                scaleFocused = 1.03f,
                                elevationFocused = 6.dp,
                                unfocusedBackgroundColor = if (isTabHighlighted) {
                                    themeAccent.copy(alpha = 0.24f)
                                } else {
                                    Color.White.copy(alpha = 0.05f)
                                },
                                focusedBackgroundColor = TV_FOCUSED_BG,
                                unfocusedBorderColor = if (isTabHighlighted) {
                                    themeAccentLight
                                } else {
                                    Color.White.copy(alpha = 0.12f)
                                },
                                focusedBorderColor = themeAccentLight,
                                focusedBorderWidth = 1.8.dp,
                                focusRequester = tabFocusRequesters[tab],
                                onFocusChange = { focused ->
                                    if (focused) {
                                        focusedTab = tab
                                        highlightedTab = tab
                                        activeContentTab = tab
                                    } else if (focusedTab == tab) {
                                        focusedTab = null
                                    }
                                },
                                onClick = {
                                    if (highlightedTab != tab) {
                                        highlightedTab = tab
                                        activeContentTab = tab
                                    } else {
                                        onNavigate(tab.route)
                                    }
                                }
                            )
                            .padding(horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = label,
                                tint = when {
                                    isTabFocused -> TV_FOCUSED_TEXT
                                    isTabHighlighted -> themeAccentLight
                                    else -> Color.White.copy(alpha = 0.72f)
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = label,
                                color = when {
                                    isTabFocused -> TV_FOCUSED_TEXT
                                    isTabHighlighted -> Color.White
                                    else -> Color.White.copy(alpha = 0.80f)
                                },
                                fontSize = 12.5.sp,
                                fontWeight = if (isTabHighlighted || isTabFocused) FontWeight.ExtraBold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isTabHighlighted) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(2.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(themeAccentLight)
                            )
                        }
                    }
                }
            }

            // ─── MAIN CONTENT STAGE ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeContentTab) {
                    LiveStageTab.TV -> LiveStageTvPanel(
                        channels = categoryChannels,
                        selectedChannel = selectedPreviewChannel,
                        isChannelLocked = isChannelLocked,
                        isResolvingPreview = isResolvingPreview,
                        hasPreviewStream = hasPreviewStream,
                        emptyText = Translator.translate("no_live_channels_preview", lang),
                        lang = lang,
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        onSelectChannel = onSelectPreviewChannel,
                        onOpenFullscreen = onOpenFullscreenPreview,
                        videoPreviewSlot = videoPreviewSlot,
                        modifier = Modifier.fillMaxSize()
                    )

                    LiveStageTab.SPORTS -> LiveStageSportsRow(
                        events = featuredSports,
                        lang = lang,
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        onMatchClick = onMatchClick,
                        modifier = Modifier.fillMaxSize()
                    )

                    LiveStageTab.FAVORITES -> LiveStageFavoritesRow(
                        favorites = favorites,
                        lang = lang,
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        onFavoriteClick = onFavoriteClick,
                        modifier = Modifier.fillMaxSize()
                    )

                    LiveStageTab.MOVIES -> LiveStageMediaPosterRow(
                        items = featuredMovies,
                        emptyMessage = Translator.translate("no_movies_featured", lang),
                        emptyIcon = Icons.Default.Movie,
                        badgeFallback = Translator.translate("favorites_media_movie", lang),
                        testTagPrefix = "livestage_movies",
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        onItemClick = onMovieClick,
                        modifier = Modifier.fillMaxSize()
                    )

                    LiveStageTab.SERIES -> LiveStageMediaPosterRow(
                        items = featuredSeries,
                        emptyMessage = Translator.translate("no_series_featured", lang),
                        emptyIcon = Icons.Default.Subscriptions,
                        badgeFallback = Translator.translate("favorites_media_series", lang),
                        testTagPrefix = "livestage_series",
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        onItemClick = onSeriesClick,
                        modifier = Modifier.fillMaxSize()
                    )

                    LiveStageTab.PLAYLISTS -> LiveStageActivePlaylistPanel(
                        activePlaylist = activePlaylist,
                        isOnline = isOnline,
                        liveCount = totalLiveCount,
                        moviesCount = totalMoviesCount,
                        seriesCount = totalSeriesCount,
                        lang = lang,
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeSurface = themeSurface,
                        onOpenPlaylists = { onNavigate("playlists") },
                        modifier = Modifier.fillMaxSize()
                    )

                    LiveStageTab.SETTINGS -> LiveStageInterfacesRow(
                        selectedLayout = settings.homeLayout,
                        lang = lang,
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        onSelectInterface = onSelectInterface,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ─── BOTTOM FOOTER: PROFILES (LEFT) & CLOCK/DATE (RIGHT) WITH IDENTICAL SIZE ─
            val profilesLabel = Translator.translate("accounts", lang)
            LiveStageSymmetricFooter(
                modifier = Modifier.fillMaxWidth(),
                leftContent = {
                    Box(
                        modifier = Modifier
                            .testTag("livestage_btn_profiles")
                            .tvCardFocusable(
                                shape = RoundedCornerShape(8.dp),
                                scaleFocused = 1.03f,
                                elevationFocused = 6.dp,
                                unfocusedBackgroundColor = themeSurface.copy(alpha = 0.72f),
                                focusedBackgroundColor = TV_FOCUSED_BG,
                                unfocusedBorderColor = themeAccent.copy(alpha = 0.40f),
                                focusedBorderColor = themeAccentLight,
                                focusedBorderWidth = 1.8.dp,
                                onFocusChange = { isProfilesFocused = it },
                                onClick = { onNavigate("accounts") }
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.People,
                                contentDescription = profilesLabel,
                                tint = if (isProfilesFocused) TV_FOCUSED_TEXT else themeAccentLight,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = profilesLabel,
                                color = if (isProfilesFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = if (isProfilesFocused) FontWeight.ExtraBold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                rightContent = {
                    Box(
                        modifier = Modifier
                            .testTag("livestage_bottom_clock")
                            .clip(RoundedCornerShape(8.dp))
                            .background(themeSurface.copy(alpha = 0.72f))
                            .border(1.dp, themeAccent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = currentTime.ifBlank { "--:--" },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                            if (currentDate.isNotBlank()) {
                                Text(
                                    text = currentDate,
                                    color = themeAccentLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun LiveStageSymmetricFooter(
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(
        content = {
            leftContent()
            rightContent()
        },
        modifier = modifier
    ) { measurables, constraints ->
        val leftMeasurable = measurables[0]
        val rightMeasurable = measurables[1]
        val gapPx = 16.dp.roundToPx()
        val minPillHPx = 28.dp.roundToPx()

        val maxSideW = ((constraints.maxWidth - gapPx) / 2).coerceAtLeast(0)
        val leftIntrinsicW = leftMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val rightIntrinsicW = rightMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val sharedW = maxOf(leftIntrinsicW, rightIntrinsicW).coerceIn(0, maxSideW)

        val leftIntrinsicH = leftMeasurable.maxIntrinsicHeight(sharedW)
        val rightIntrinsicH = rightMeasurable.maxIntrinsicHeight(sharedW)
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val sharedH = maxOf(leftIntrinsicH, rightIntrinsicH, minPillHPx).coerceIn(0, maxH)

        val pillConstraints = Constraints.fixed(sharedW, sharedH)
        val leftPlaceable = leftMeasurable.measure(pillConstraints)
        val rightPlaceable = rightMeasurable.measure(pillConstraints)

        val layoutW = constraints.maxWidth
        layout(layoutW, sharedH) {
            leftPlaceable.placeRelative(0, 0)
            rightPlaceable.placeRelative(layoutW - sharedW, 0)
        }
    }
}
