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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.Translator
import com.example.interfaz.AppThemeColors
import com.example.interfaz.ParentalControl
import com.example.interfaz.ParentalPinDialog
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.rememberTvInitialFocusRequester
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.interfaz.viewmodel.MoviesViewModel
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.interfaz.viewmodel.SportsUiState
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.motor.config.PlayerSettings
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.repository.IptvRepository
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.parseHexColor
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Selects the best visible TV category for the Showcase Live TV preview panel,
 * prioritizing Spanish categories when available in the active playlist.
 */
internal fun pickPreferredSpanishTvCategory(
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

    val esPrefixRegex = Regex("^(es|esp)(\\b|[\\s|:\\-_/\\]])")
    val esBracketRegex = Regex("(\\[es\\]|\\|es\\||\\besp\\b|\\bespan[ao]\\b|\\bespanol\\b|\\bspain\\b|\\btdt\\b|\\bcastellano\\b)")
    val secondaryRegex = Regex("(\\bspa\\b|\\bspanish\\b|\\bmovistar\\b|\\blaliga\\b|\\bla liga\\b|\\bdazn es\\b)")
    val tertiaryRegex = Regex("(\\blatino\\b|\\blatinoamerica\\b|\\bmexico\\b|\\bargentina\\b|\\bcolombia\\b)")
    val adultRegex = Regex("(\\badult\\b|\\bxxx\\b|\\b18\\+|\\bporn\\b)")

    val bestEntry = visibleEntries.maxByOrNull { (cat, channels) ->
        val norm = normalizeCat(cat)
        var score = 0
        if (adultRegex.containsMatchIn(norm)) {
            score -= 100
        }
        when {
            esBracketRegex.containsMatchIn(norm) || esPrefixRegex.containsMatchIn(norm) -> score += 300
            secondaryRegex.containsMatchIn(norm) -> score += 200
            tertiaryRegex.containsMatchIn(norm) -> score += 100
        }
        // Slight tie-breaker favoring categories with at least 5 channels
        if (channels.size >= 5) score += 10
        score
    } ?: visibleEntries.first()

    return bestEntry.key to bestEntry.value
}

@Composable
fun ShowcaseHomeScreen(
    homeViewModel: HomeViewModel,
    liveTvViewModel: LiveTvViewModel,
    moviesViewModel: MoviesViewModel,
    seriesViewModel: SeriesViewModel,
    sportsViewModel: SportsViewModel,
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

    val scope = rememberCoroutineScope()
    val lang = settings.lang
    var isParentalUnlocked by remember(activePlaylist?.id) { mutableStateOf(false) }
    var pendingPinChannel by remember { mutableStateOf<Triple<IptvItem, String, List<IptvItem>>?>(null) }
    var isResolvingChannel by remember { mutableStateOf(false) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activePlaylist?.id) {
        seriesViewModel.ensureSeriesLoaded()
        moviesViewModel.ensureMoviesLoaded()
        sportsViewModel.loadData()
    }

    LaunchedEffect(noticeMessage) {
        if (noticeMessage != null) {
            delay(4000L)
            noticeMessage = null
        }
    }

    fun launchLiveChannel(channel: IptvItem, categoryName: String, categoryChannels: List<IptvItem>) {
        if (isResolvingChannel) return
        val startPlId = liveTvViewModel.getActivePlaylistId()
        scope.launch {
            isResolvingChannel = true
            try {
                val (resolvedUrl, resolvedHeaders) = liveTvViewModel.resolveStream(channel)
                if (liveTvViewModel.getActivePlaylistId() != startPlId) {
                    noticeMessage = Translator.translate("player_zap_playlist_changed", lang)
                    return@launch
                }
                if (resolvedUrl.isNotBlank()) {
                    onPlayLiveChannel(
                        resolvedUrl,
                        channel.name,
                        channel.drmScheme,
                        channel.drmLicenseUrl,
                        categoryName,
                        categoryChannels,
                        resolvedHeaders,
                        channel
                    )
                } else {
                    noticeMessage = Translator.translate("player_zap_url_error", lang)
                }
            } catch (e: Exception) {
                noticeMessage = Translator.translate("player_zap_switch_error", lang, e.message ?: "")
            } finally {
                isResolvingChannel = false
            }
        }
    }

    ShowcaseHomeContent(
        settings = settings,
        liveChannels = liveChannels,
        newestMovies = newestMovies,
        precomputedMoviesData = precomputedMoviesData,
        precomputedSeriesData = precomputedSeriesData,
        sportsUiState = sportsUiState,
        favorites = homeUiState.favorites,
        isChannelLocked = { ch ->
            settings.parentalEnabled &&
                ParentalControl.isChannelRestricted(ch, settings) &&
                !isParentalUnlocked
        },
        onChannelClick = { ch, catName, catChannels ->
            val locked = settings.parentalEnabled &&
                ParentalControl.isChannelRestricted(ch, settings) &&
                !isParentalUnlocked
            if (locked) {
                pendingPinChannel = Triple(ch, catName, catChannels)
            } else {
                launchLiveChannel(ch, catName, catChannels)
            }
        },
        onMovieClick = { movie ->
            moviesViewModel.pendingSelectedMovie.value = movie
            onNavigate("movies")
        },
        onSeriesClick = { series ->
            seriesViewModel.pendingSelectedSeries.value = series
            onNavigate("series")
        },
        onMatchClick = { event ->
            sportsViewModel.matchAndPlayEvent(
                event = event,
                onPlay = onPlaySport,
                onNotice = { noticeMessage = it }
            )
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
                            is HomeViewModel.FavoriteResolution.OpenSeries -> {
                                seriesViewModel.pendingSelectedSeries.value = res.seriesItem
                                onNavigate("series")
                            }
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
        onNavigate = onNavigate,
        onExitRequest = onExitRequest,
        theme = theme
    )

    // Parental PIN prompt when clicking a locked channel in Showcase Live TV panel
    val pinTarget = pendingPinChannel
    if (pinTarget != null) {
        ParentalPinDialog(
            channelName = pinTarget.first.name,
            expectedPin = settings.parentalPin,
            onDismiss = { pendingPinChannel = null },
            onSuccess = {
                isParentalUnlocked = true
                val (ch, catName, catChannels) = pinTarget
                pendingPinChannel = null
                launchLiveChannel(ch, catName, catChannels)
            },
            theme = theme,
            lang = lang
        )
    }

    // Sports channel picker dialog (shows channels broadcasting the selected match)
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

    if (sportsUiState.isMatchingChannel || homeUiState.isResolvingMovie || isResolvingChannel) {
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
                        text = if (sportsUiState.isMatchingChannel) {
                            Translator.translate("sports_searching_channel", lang)
                        } else {
                            Translator.translate("loading_channels", lang)
                        },
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
            .padding(bottom = 24.dp)
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
internal fun ShowcaseHomeContent(
    settings: PlayerSettings,
    liveChannels: Map<String, List<IptvItem>>,
    newestMovies: List<IptvItem>,
    precomputedMoviesData: PrecomputedMoviesData?,
    precomputedSeriesData: PrecomputedSeriesData?,
    sportsUiState: SportsUiState,
    favorites: List<FavoriteItem>,
    isChannelLocked: (IptvItem) -> Boolean = { false },
    onChannelClick: ((channel: IptvItem, categoryName: String, categoryChannels: List<IptvItem>) -> Unit)? = null,
    onMovieClick: ((IptvItem) -> Unit)? = null,
    onSeriesClick: ((IptvItem) -> Unit)? = null,
    onMatchClick: ((SportsEvent) -> Unit)? = null,
    onFavoriteClick: ((FavoriteItem) -> Unit)? = null,
    onNavigate: (String) -> Unit,
    onExitRequest: () -> Unit,
    theme: AppThemeColors
) {
    val lang = settings.lang
    val initialFocusRequester = rememberTvInitialFocusRequester()

    BackHandler(enabled = true) {
        onExitRequest()
    }

    // Live clock and date (without app name, respecting user timeFormat and language)
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

    val themeBg = remember(theme) { parseHexColor(theme.bg) }
    val themeSurface = remember(theme) { parseHexColor(theme.surface) }
    val themeCard = remember(theme) { parseHexColor(theme.card) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }
    val themeAccentLight = remember(theme) { parseHexColor(theme.accentLight) }

    // 1. Live TV category preview (preferring Spanish)
    val tvCategoryPreview = remember(liveChannels, settings.hiddenTvCategories) {
        pickPreferredSpanishTvCategory(liveChannels, settings.hiddenTvCategories)
    }

    // 2. Movies preview posters
    val hiddenMoviesSet = remember(settings.hiddenMovieCategories) {
        settings.hiddenMovieCategories.map { it.trim().lowercase() }.toSet()
    }
    val previewMovies = remember(newestMovies, precomputedMoviesData, hiddenMoviesSet, settings.hiddenMovieCategories) {
        val data = precomputedMoviesData
        val pool = if (hiddenMoviesSet.isEmpty()) {
            if (newestMovies.isNotEmpty()) newestMovies else data?.allMovies.orEmpty()
        } else {
            val validNewest = newestMovies.filter { movie ->
                val g = movie.group?.trim().orEmpty()
                g.isNotEmpty() && g !in settings.hiddenMovieCategories && g.lowercase() !in hiddenMoviesSet
            }
            val allowedFromCatalog = data?.groupToMovies
                ?.filterKeys { cat -> cat !in settings.hiddenMovieCategories && cat.trim().lowercase() !in hiddenMoviesSet }
                ?.values
                ?.flatten()
                .orEmpty()
            (validNewest + allowedFromCatalog)
                .distinctBy { it.name.trim().lowercase() }
        }
        pool.sortedByDescending { !it.logo.isNullOrBlank() }.take(18)
    }

    // 3. Series preview posters
    val hiddenSeriesSet = remember(settings.hiddenSeriesCategories) {
        settings.hiddenSeriesCategories.map { it.trim().lowercase() }.toSet()
    }
    val previewSeries = remember(precomputedSeriesData, hiddenSeriesSet, settings.hiddenSeriesCategories) {
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
        pool.sortedByDescending { !it.logo.isNullOrBlank() }.take(18)
    }

    // 4. Today's/Live Sports matches preview
    val previewSports = remember(sportsUiState.liveEvents, sportsUiState.todayEvents) {
        (sportsUiState.liveEvents + sportsUiState.todayEvents)
            .distinctBy { it.id }
    }

    // 5. Favorites preview: movies or series saved in favorites for the active playlist (empty if none)
    val previewVodFavorites = remember(favorites) {
        favorites
            .filter { fav ->
                val normalized = IptvRepository.normalizeFavoriteType(fav.type)
                normalized == "movie" || normalized == "series"
            }
            .sortedByDescending { !it.logo.isNullOrBlank() }
            .take(24)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(themeBg, themeSurface.copy(alpha = 0.92f), themeBg)
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            themeAccent.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.22f, size.height * 0.12f),
                        radius = size.maxDimension * 0.55f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            themeAccentLight.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.78f),
                        radius = size.maxDimension * 0.5f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── TOP BAR: CLOCK & DATE (LEFT) | LISTAS / PERFILES / AJUSTES (CENTER) ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Only Clock and Date (NO app name)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = currentTime.ifBlank { "--:--" },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = currentDate,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Center: Text Action Buttons (Listas, Perfiles, Ajustes) using standard TvButton
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShowcaseTopButton(
                        label = Translator.translate("playlists", lang),
                        icon = Icons.Default.Bookmark,
                        themeAccent = themeAccent,
                        testTag = "showcase_btn_playlists",
                        onClick = { onNavigate("playlists") }
                    )
                    ShowcaseTopButton(
                        label = Translator.translate("accounts", lang),
                        icon = Icons.Default.People,
                        themeAccent = themeAccent,
                        testTag = "showcase_btn_accounts",
                        onClick = { onNavigate("accounts") }
                    )
                    ShowcaseTopButton(
                        label = Translator.translate("settings", lang),
                        icon = Icons.Default.Settings,
                        themeAccent = themeAccent,
                        testTag = "showcase_btn_settings",
                        onClick = { onNavigate("settings") }
                    )
                }

                // Right: Balanced empty spacer (top-right icons intentionally removed)
                Spacer(modifier = Modifier.weight(1f))
            }

            // ─── MAIN LANDSCAPE GRID: LIVE TV (LEFT) + FILMS/SERIES/SPORTS/FAVORITES (RIGHT) ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // LEFT PANEL: CANALES TV (Spanish category prioritized, vertical scroll, no category badge)
                val tvCatName = tvCategoryPreview?.first ?: Translator.translate("live_tv", lang)
                val tvCatChannels = tvCategoryPreview?.second.orEmpty()
                ShowcaseLiveTvPanel(
                    title = Translator.translate("live_tv", lang),
                    channels = tvCatChannels,
                    isChannelLocked = isChannelLocked,
                    emptyText = Translator.translate("no_live_channels_preview", lang),
                    themeAccent = themeAccent,
                    themeAccentLight = themeAccentLight,
                    themeCard = themeCard,
                    themeSurface = themeSurface,
                    focusRequester = initialFocusRequester,
                    onHeaderClick = { onNavigate("live_tv") },
                    onChannelClick = { ch ->
                        if (onChannelClick != null) {
                            onChannelClick(ch, tvCatName, tvCatChannels)
                        } else {
                            onNavigate("live_tv")
                        }
                    },
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                )

                // RIGHT 2x2 GRID: TOP (PELÍCULAS + SERIES) | BOTTOM (DEPORTES + FAVORITOS)
                Column(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // TOP ROW: PELÍCULAS + SERIES
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.55f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ShowcaseMediaPanel(
                            title = Translator.translate("movies", lang),
                            icon = Icons.Default.Movie,
                            items = previewMovies,
                            themeAccent = themeAccent,
                            themeAccentLight = themeAccentLight,
                            themeCard = themeCard,
                            themeSurface = themeSurface,
                            testTag = "showcase_card_movies",
                            onHeaderClick = { onNavigate("movies") },
                            onItemClick = { movie ->
                                if (onMovieClick != null) onMovieClick(movie) else onNavigate("movies")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        ShowcaseMediaPanel(
                            title = Translator.translate("series", lang),
                            icon = Icons.Default.Subscriptions,
                            items = previewSeries,
                            themeAccent = themeAccent,
                            themeAccentLight = themeAccentLight,
                            themeCard = themeCard,
                            themeSurface = themeSurface,
                            testTag = "showcase_card_series",
                            onHeaderClick = { onNavigate("series") },
                            onItemClick = { series ->
                                if (onSeriesClick != null) onSeriesClick(series) else onNavigate("series")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }

                    // BOTTOM ROW: DEPORTES + FAVORITOS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.45f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ShowcaseSportsPanel(
                            title = Translator.translate("sports", lang),
                            events = previewSports,
                            liveBadgeLabel = Translator.translate("live_now", lang),
                            todayBadgeLabel = Translator.translate("today", lang),
                            emptyText = Translator.translate("no_sports_today", lang),
                            themeAccent = themeAccent,
                            themeAccentLight = themeAccentLight,
                            themeCard = themeCard,
                            themeSurface = themeSurface,
                            onHeaderClick = { onNavigate("sports") },
                            onMatchClick = { event ->
                                if (onMatchClick != null) onMatchClick(event) else onNavigate("sports")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )

                        ShowcaseFavoritesPanel(
                            title = Translator.translate("favorites", lang),
                            favorites = previewVodFavorites,
                            themeAccent = themeAccent,
                            themeAccentLight = themeAccentLight,
                            themeCard = themeCard,
                            themeSurface = themeSurface,
                            onHeaderClick = { onNavigate("favorites") },
                            onFavoriteClick = { fav ->
                                if (onFavoriteClick != null) onFavoriteClick(fav) else onNavigate("favorites")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseTopButton(
    label: String,
    icon: ImageVector,
    themeAccent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        containerColor = Color.White.copy(alpha = 0.08f),
        unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
        focusedContainerColor = TV_FOCUSED_BG,
        focusedBorderColor = TV_FOCUSED_BORDER,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) { isFocused ->
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isFocused) TV_FOCUSED_TEXT else themeAccent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = if (isFocused) TV_FOCUSED_TEXT else Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ShowcaseLiveTvPanel(
    title: String,
    channels: List<IptvItem>,
    isChannelLocked: (IptvItem) -> Boolean,
    emptyText: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    focusRequester: FocusRequester,
    onHeaderClick: () -> Unit,
    onChannelClick: (IptvItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var isHeaderFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(themeSurface.copy(alpha = 0.72f))
            .border(1.dp, themeAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Live TV Hero Banner (focusable header, NO category text overlay)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.36f)
                .testTag("showcase_card_live_tv")
                .tvCardFocusable(
                    shape = RoundedCornerShape(12.dp),
                    scaleFocused = 1.025f,
                    elevationFocused = 8.dp,
                    unfocusedBackgroundColor = Color.Transparent,
                    focusedBackgroundColor = TV_FOCUSED_BG,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                    focusedBorderColor = themeAccentLight,
                    focusedBorderWidth = 2.dp,
                    focusRequester = focusRequester,
                    onFocusChange = { isHeaderFocused = it },
                    onClick = onHeaderClick
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            themeAccent.copy(alpha = if (isHeaderFocused) 0.55f else 0.38f),
                            themeCard.copy(alpha = 0.92f)
                        )
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }

        // Bottom Channel List (vertical scroll with D-Pad & touch)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.64f)
        ) {
            if (channels.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyText,
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("showcase_live_tv_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { idx, channel -> "${idx}_${channel.url ?: channel.stalkerCmd ?: channel.name}" }
                    ) { idx, channel ->
                        val isFirst = idx == 0
                        val locked = isChannelLocked(channel)
                        var isItemFocused by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("showcase_live_tv_item_$idx")
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFocused = 1.02f,
                                    elevationFocused = 4.dp,
                                    unfocusedBackgroundColor = if (isFirst) {
                                        themeCard.copy(alpha = 0.72f)
                                    } else {
                                        Color.White.copy(alpha = 0.05f)
                                    },
                                    focusedBackgroundColor = TV_FOCUSED_BG,
                                    unfocusedBorderColor = if (isFirst) {
                                        themeAccentLight.copy(alpha = 0.45f)
                                    } else {
                                        Color.White.copy(alpha = 0.08f)
                                    },
                                    focusedBorderColor = themeAccentLight,
                                    focusedBorderWidth = 1.5.dp,
                                    onFocusChange = { isItemFocused = it },
                                    onClick = { onChannelClick(channel) }
                                )
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!channel.logo.isNullOrBlank()) {
                                AsyncImage(
                                    model = channel.logo,
                                    contentDescription = channel.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = themeAccentLight,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = channel.name,
                                color = if (isItemFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = if (isFirst || isItemFocused) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (locked) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = themeAccentLight,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = if (isItemFocused) 0.95f else 0.65f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseMediaPanel(
    title: String,
    icon: ImageVector,
    items: List<IptvItem>,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    testTag: String,
    onHeaderClick: () -> Unit,
    onItemClick: (IptvItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var isHeaderFocused by remember { mutableStateOf(false) }
    val stripItems = items.take(3)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(themeSurface.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top hero header (focusable button opening Movies / Series)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.42f)
                .testTag(testTag)
                .tvCardFocusable(
                    shape = RoundedCornerShape(10.dp),
                    scaleFocused = 1.02f,
                    elevationFocused = 6.dp,
                    unfocusedBackgroundColor = Color.Transparent,
                    focusedBackgroundColor = TV_FOCUSED_BG,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                    focusedBorderColor = themeAccentLight,
                    focusedBorderWidth = 2.dp,
                    onFocusChange = { isHeaderFocused = it },
                    onClick = onHeaderClick
                )
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            themeCard.copy(alpha = 0.95f),
                            themeAccent.copy(alpha = if (isHeaderFocused) 0.42f else 0.24f)
                        )
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = themeAccentLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        color = if (isHeaderFocused) TV_FOCUSED_TEXT else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (stripItems.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((-10).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        stripItems.forEachIndexed { idx, item ->
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(34.dp)
                                    .graphicsLayer {
                                        rotationZ = (idx - 1) * 4f
                                    }
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(themeSurface)
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(5.dp))
                            ) {
                                if (!item.logo.isNullOrBlank()) {
                                    AsyncImage(
                                        model = item.logo,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Poster Strip (horizontal LazyRow with D-Pad & touch scroll)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.58f)
        ) {
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.04f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                val tileWidth = ((maxWidth - 16.dp) / 3f).coerceAtLeast(72.dp)
                LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("${testTag}_list"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { idx, mediaItem -> "${idx}_${mediaItem.seriesId ?: mediaItem.url ?: mediaItem.name}" }
                    ) { idx, mediaItem ->
                        Box(
                            modifier = Modifier
                                .width(tileWidth)
                                .fillMaxHeight()
                                .testTag("${testTag}_item_$idx")
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFocused = 1.04f,
                                    elevationFocused = 6.dp,
                                    unfocusedBackgroundColor = Color.Transparent,
                                    focusedBackgroundColor = TV_FOCUSED_BG,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                                    focusedBorderColor = themeAccentLight,
                                    focusedBorderWidth = 2.dp,
                                    onClick = { onItemClick(mediaItem) }
                                )
                                .padding(2.dp)
                        ) {
                            ShowcasePosterTile(
                                title = mediaItem.name,
                                logoUrl = mediaItem.logo,
                                fallbackIcon = icon,
                                themeCard = themeCard,
                                themeSurface = themeSurface,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseSportsPanel(
    title: String,
    events: List<SportsEvent>,
    liveBadgeLabel: String,
    todayBadgeLabel: String,
    emptyText: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    onHeaderClick: () -> Unit,
    onMatchClick: (SportsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var isHeaderFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(themeSurface.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Focusable compact header opening Sports screen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("showcase_card_sports")
                .tvCardFocusable(
                    shape = RoundedCornerShape(10.dp),
                    scaleFocused = 1.02f,
                    elevationFocused = 4.dp,
                    unfocusedBackgroundColor = Color.White.copy(alpha = 0.04f),
                    focusedBackgroundColor = TV_FOCUSED_BG,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                    focusedBorderColor = themeAccentLight,
                    focusedBorderWidth = 1.5.dp,
                    onFocusChange = { isHeaderFocused = it },
                    onClick = onHeaderClick
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SportsSoccer,
                    contentDescription = title,
                    tint = themeAccentLight,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    color = if (isHeaderFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            if (events.isNotEmpty()) {
                val hasLive = events.any { it.isLive || it.status == SportsEventStatus.LIVE }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (hasLive) Color(0xFFEF4444) else themeAccent.copy(alpha = 0.85f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (hasLive) liveBadgeLabel else todayBadgeLabel.uppercase(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // Horizontal LazyRow of compact Match Cards (D-Pad & touch scroll)
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SportsSoccer,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.30f),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = emptyText,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val cardWidth = if (events.size <= 1) {
                    (maxWidth - 4.dp).coerceAtLeast(140.dp)
                } else {
                    ((maxWidth - 12.dp) / 2f).coerceAtLeast(136.dp)
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("showcase_sports_list"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    itemsIndexed(
                        items = events,
                        key = { _, event -> event.id }
                    ) { idx, event ->
                        Box(
                            modifier = Modifier
                                .width(cardWidth)
                                .fillMaxHeight()
                                .testTag("showcase_sports_item_$idx")
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    scaleFocused = 1.03f,
                                    elevationFocused = 6.dp,
                                    unfocusedBackgroundColor = Color.Transparent,
                                    focusedBackgroundColor = TV_FOCUSED_BG,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = themeAccentLight,
                                    focusedBorderWidth = 2.dp,
                                    onClick = { onMatchClick(event) }
                                )
                                .padding(2.dp)
                        ) {
                            ShowcaseCompactMatchCard(
                                event = event,
                                liveLabel = liveBadgeLabel,
                                themeAccentLight = themeAccentLight,
                                themeCard = themeCard,
                                themeSurface = themeSurface,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseCompactMatchCard(
    event: SportsEvent,
    liveLabel: String,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    modifier: Modifier = Modifier
) {
    val isLive = event.isLive || event.status == SportsEventStatus.LIVE
    val matchTitle = if (event.homeTeam.isNotBlank() && event.awayTeam.isNotBlank()) {
        "${event.homeTeam} vs ${event.awayTeam}"
    } else {
        event.league.ifBlank { "Sports" }
    }
    val leagueLabelColor = remember(themeAccentLight) {
        lerp(themeAccentLight, Color.White, 0.35f)
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(themeSurface.copy(alpha = 0.85f), themeCard.copy(alpha = 0.95f))
                )
            )
            .border(
                width = 1.dp,
                color = if (isLive) Color(0xFFEF4444).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        val isCompactHeight = maxHeight < 90.dp
        val cardPadH = if (isCompactHeight) 8.dp else 10.dp
        val cardPadV = if (isCompactHeight) 4.dp else 8.dp
        val crestSize = if (isCompactHeight) 22.dp else 30.dp
        val leagueFontSize = if (isCompactHeight) 8.5.sp else 9.5.sp
        val vsFontSize = if (isCompactHeight) 9.5.sp else 10.5.sp
        val titleFontSize = if (isCompactHeight) 10.sp else 11.sp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = cardPadH, vertical = cardPadV),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // League & Time/Live badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = event.league.ifBlank { "Deportes" },
                    color = leagueLabelColor,
                    fontSize = leagueFontSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF4444))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = liveLabel,
                            color = Color.White,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else {
                    val formatted = event.getFormattedTime()
                    if (formatted.isNotBlank()) {
                        Text(
                            text = formatted,
                            color = Color.LightGray,
                            fontSize = leagueFontSize,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Team crests & score/VS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 6.dp else 8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth()
            ) {
                ShowcaseTeamCrest(logoUrl = event.homeTeamLogo, teamName = event.homeTeam, size = crestSize)
                Text(
                    text = if (isLive && event.homeScore != null && event.awayScore != null) {
                        "${event.homeScore} : ${event.awayScore}"
                    } else {
                        "VS"
                    },
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = vsFontSize,
                    fontWeight = FontWeight.ExtraBold
                )
                ShowcaseTeamCrest(logoUrl = event.awayTeamLogo, teamName = event.awayTeam, size = crestSize)
            }

            // Match title
            Text(
                text = matchTitle,
                color = Color.White,
                fontSize = titleFontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ShowcaseTeamCrest(
    logoUrl: String?,
    teamName: String,
    size: Dp = 30.dp
) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = teamName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(if (size < 26.dp) 2.dp else 3.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = teamName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•",
                color = Color.White,
                fontSize = if (size < 26.dp) 10.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ShowcaseFavoritesPanel(
    title: String,
    favorites: List<FavoriteItem>,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    onHeaderClick: () -> Unit,
    onFavoriteClick: (FavoriteItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var isHeaderFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(themeSurface.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Focusable header row opening Favorites screen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("showcase_card_favorites")
                .tvCardFocusable(
                    shape = RoundedCornerShape(10.dp),
                    scaleFocused = 1.02f,
                    elevationFocused = 4.dp,
                    unfocusedBackgroundColor = Color.White.copy(alpha = 0.04f),
                    focusedBackgroundColor = TV_FOCUSED_BG,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                    focusedBorderColor = themeAccentLight,
                    focusedBorderWidth = 1.5.dp,
                    onFocusChange = { isHeaderFocused = it },
                    onClick = onHeaderClick
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = title,
                    tint = themeAccentLight,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    color = if (isHeaderFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // Favorites posters (horizontal LazyRow): show ONLY if user has movie/series favorites; otherwise render nothing inside
        if (favorites.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val tileWidth = ((maxWidth - 16.dp) / 3f).coerceAtLeast(72.dp)
                LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("showcase_favorites_list"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    itemsIndexed(
                        items = favorites,
                        key = { idx, fav -> "${idx}_${fav.id}_${fav.playlistId}" }
                    ) { idx, fav ->
                        val normalizedType = IptvRepository.normalizeFavoriteType(fav.type)
                        val fallbackIcon = if (normalizedType == "series") Icons.Default.Subscriptions else Icons.Default.Movie
                        Box(
                            modifier = Modifier
                                .width(tileWidth)
                                .fillMaxHeight()
                                .testTag("showcase_favorites_item_$idx")
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFocused = 1.04f,
                                    elevationFocused = 6.dp,
                                    unfocusedBackgroundColor = Color.Transparent,
                                    focusedBackgroundColor = TV_FOCUSED_BG,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                                    focusedBorderColor = themeAccentLight,
                                    focusedBorderWidth = 2.dp,
                                    onClick = { onFavoriteClick(fav) }
                                )
                                .padding(2.dp)
                        ) {
                            ShowcasePosterTile(
                                title = fav.title,
                                logoUrl = fav.logo,
                                fallbackIcon = fallbackIcon,
                                themeCard = themeCard,
                                themeSurface = themeSurface,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShowcasePosterTile(
    title: String,
    logoUrl: String?,
    fallbackIcon: ImageVector,
    themeCard: Color,
    themeSurface: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(themeCard, themeSurface)
                )
            )
            .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = title,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 9.5.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Subtle bottom gradient + title overlay so covers remain identifiable
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
                .padding(horizontal = 5.dp, vertical = 4.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
