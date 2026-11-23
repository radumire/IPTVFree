package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.Translator
import com.example.interfaz.AppThemeColors
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.tvClickable
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.viewmodel.MoviesViewModel
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.motor.db.IptvItem
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.parseHexColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.motor.sports.matcher.SportsChannelCandidate
import androidx.compose.foundation.lazy.itemsIndexed

private enum class CinemaTab(
    val key: String,
    val titleKey: String,
    val fallbackTitle: String,
    val icon: ImageVector,
    val isDirectNavigation: Boolean
) {
    LIVE_TV("live_tv", "live_tv", "TV en vivo", Icons.Default.Tv, true),
    MOVIES("movies", "movies", "Películas", Icons.Default.Movie, false),
    SERIES("series", "series", "Series", Icons.Default.Subscriptions, false),
    SPORTS("sports", "sports", "Deportes", Icons.Default.SportsSoccer, false),
    FAVORITES("favorites", "favorites", "Favoritos", Icons.Default.Favorite, false),
    PLAYLIST("playlists", "playlists", "Playlist", Icons.Default.Bookmark, true),
    PROFILES("accounts", "profiles", "Perfiles", Icons.Default.People, true),
    SETTINGS("settings", "settings", "Ajustes", Icons.Default.Settings, true)
}

@Composable
fun CinemaBarHomeScreen(
    homeViewModel: HomeViewModel,
    moviesViewModel: MoviesViewModel,
    seriesViewModel: SeriesViewModel,
    sportsViewModel: SportsViewModel,
    onNavigate: (String) -> Unit,
    onPlay: (src: String, title: String, type: String, drmScheme: String?, drmLicenseUrl: String?, headers: Map<String, String>) -> Unit,
    onPlaySport: (src: String, title: String, drmScheme: String?, drmLicenseUrl: String?, matchChannels: List<IptvItem>, catName: String?, headers: Map<String, String>) -> Unit,
    onExitRequest: () -> Unit,
    theme: AppThemeColors
) {
    val savedTabKey by homeViewModel.cinemaBarSelectedTab.collectAsStateWithLifecycle()
    val selectedTab = remember(savedTabKey) {
        CinemaTab.values().find { it.key == savedTabKey } ?: CinemaTab.SERIES
    }
    var sportsNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val settings by homeViewModel.settings.collectAsStateWithLifecycle()
    val activePlaylist by homeViewModel.activePlaylist.collectAsStateWithLifecycle()
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val precomputedSeriesData by seriesViewModel.precomputedSeriesData.collectAsStateWithLifecycle()
    val precomputedMoviesData by moviesViewModel.precomputedMoviesData.collectAsStateWithLifecycle()
    val sportsUiState by sportsViewModel.uiState.collectAsStateWithLifecycle()
    val favoritesList by homeViewModel.favoritesList.collectAsStateWithLifecycle()
    val newestMovies by homeViewModel.newestMovies.collectAsStateWithLifecycle()

    val lang = settings.lang

    LaunchedEffect(sportsNotice) {
        if (sportsNotice != null) {
            delay(3500L)
            sportsNotice = null
        }
    }

    BackHandler(enabled = true) {
        if (sportsUiState.channelSelectionState != null) {
            sportsViewModel.dismissChannelSelection()
        } else {
            onExitRequest()
        }
    }

    LaunchedEffect(Unit) {
        seriesViewModel.ensureSeriesLoaded()
        moviesViewModel.ensureMoviesLoaded()
        sportsViewModel.loadData()
    }

    val hiddenSeriesSet = remember(settings.hiddenSeriesCategories) {
        settings.hiddenSeriesCategories.map { it.trim().lowercase() }.toSet()
    }
    val hiddenMoviesSet = remember(settings.hiddenMovieCategories) {
        settings.hiddenMovieCategories.map { it.trim().lowercase() }.toSet()
    }

    // Featured series list: take unique series only from user-selected/visible categories (excluding hidden categories)
    val featuredSeriesList = remember(precomputedSeriesData, hiddenSeriesSet, settings.hiddenSeriesCategories) {
        val data = precomputedSeriesData ?: return@remember emptyList()
        if (hiddenSeriesSet.isEmpty()) {
            data.allSeriesUnique.take(25)
        } else {
            // Collect series belonging to allowed/visible categories
            val allowedCategorySeries = data.groupToUniqueSeries
                .filterKeys { cat -> cat !in settings.hiddenSeriesCategories && cat.trim().lowercase() !in hiddenSeriesSet }
                .values
                .flatten()

            val filtered = allowedCategorySeries.filter { item ->
                val g = item.group?.trim() ?: ""
                g.isEmpty() || (g !in settings.hiddenSeriesCategories && g.lowercase() !in hiddenSeriesSet)
            }.distinctBy { if (!it.seriesId.isNullOrBlank()) it.seriesId else it.name.trim().lowercase() }

            if (filtered.isNotEmpty()) {
                filtered.take(25)
            } else {
                data.allSeriesUnique.filter { item ->
                    val g = item.group?.trim() ?: ""
                    g.isNotEmpty() && g !in settings.hiddenSeriesCategories && g.lowercase() !in hiddenSeriesSet
                }.take(25)
            }
        }
    }

    // Featured movies list: take newest/top movies only from user-selected/visible categories (excluding hidden categories)
    val featuredMoviesList = remember(newestMovies, precomputedMoviesData, hiddenMoviesSet, settings.hiddenMovieCategories) {
        val data = precomputedMoviesData
        if (hiddenMoviesSet.isEmpty()) {
            if (newestMovies.isNotEmpty()) newestMovies.take(25)
            else data?.allMovies?.take(25) ?: emptyList()
        } else {
            // 1. Filter newest preview movies that belong to visible/selected categories
            val validNewest = newestMovies.filter { movie ->
                val g = movie.group?.trim() ?: ""
                g.isNotEmpty() && g !in settings.hiddenMovieCategories && g.lowercase() !in hiddenMoviesSet
            }

            // 2. Filter full movies catalog for allowed categories
            val allowedCategoryMovies = data?.groupToMovies
                ?.filterKeys { cat -> cat !in settings.hiddenMovieCategories && cat.trim().lowercase() !in hiddenMoviesSet }
                ?.values
                ?.flatten()
                ?.filter { movie ->
                    val g = movie.group?.trim() ?: ""
                    g.isEmpty() || (g !in settings.hiddenMovieCategories && g.lowercase() !in hiddenMoviesSet)
                }
                ?: emptyList()

            if (validNewest.size >= 15) {
                validNewest.take(25)
            } else {
                // Combine valid newest with allowed category movies up to 25 items without duplicates
                val seen = mutableSetOf<String>()
                val combined = mutableListOf<IptvItem>()
                for (m in validNewest) {
                    if (seen.add(m.name.trim().lowercase())) {
                        combined.add(m)
                    }
                }
                for (m in allowedCategoryMovies) {
                    if (combined.size >= 25) break
                    if (seen.add(m.name.trim().lowercase())) {
                        combined.add(m)
                    }
                }
                if (combined.isNotEmpty()) {
                    combined
                } else {
                    data?.allMovies?.filter { movie ->
                        val g = movie.group?.trim() ?: ""
                        g.isNotEmpty() && g !in settings.hiddenMovieCategories && g.lowercase() !in hiddenMoviesSet
                    }?.take(25) ?: emptyList()
                }
            }
        }
    }

    // Featured sports events (live + today)
    val featuredSportsList = remember(sportsUiState.liveEvents, sportsUiState.todayEvents) {
        (sportsUiState.liveEvents + sportsUiState.todayEvents).distinctBy { it.id }.take(25)
    }

    // Featured favorites list
    val featuredFavoritesList = remember(favoritesList) {
        favoritesList.take(25)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(parseHexColor(theme.bg))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ─── 1. TOP NAVIGATION BAR ──────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CinemaTab.values().forEach { tab ->
                    // Vertical divider between Playlist and Perfiles
                    if (tab == CinemaTab.PROFILES) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .width(1.dp)
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.22f))
                        )
                    }

                    val isSelected = (selectedTab == tab)
                    var isFocused by remember { mutableStateOf(false) }

                    val borderAlpha by animateFloatAsState(
                        targetValue = if (isFocused) 1.0f else if (isSelected) 0.85f else 0.0f,
                        label = "tab_border"
                    )
                    val bgAlpha by animateFloatAsState(
                        targetValue = if (isFocused) 0.35f else if (isSelected) 0.20f else 0.0f,
                        label = "tab_bg"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isFocused) 1.05f else 1.0f,
                        label = "tab_scale"
                    )

                    Box(
                        modifier = Modifier
                            .scale(scale)
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(parseHexColor(theme.accent).copy(alpha = bgAlpha))
                            .border(
                                width = if (isFocused) 1.5.dp else 1.2.dp,
                                color = if (borderAlpha > 0f) parseHexColor(theme.accent).copy(alpha = borderAlpha) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .tvClickable(
                                shape = RoundedCornerShape(12.dp),
                                onFocusChange = { isFocused = it }
                            ) {
                                if (tab.isDirectNavigation) {
                                    onNavigate(tab.key)
                                } else {
                                    homeViewModel.setCinemaBarSelectedTab(tab.key)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.fallbackTitle,
                                tint = if (isFocused || isSelected) Color.White else Color.White.copy(alpha = 0.70f),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = Translator.translate(tab.titleKey, lang).ifBlank { tab.fallbackTitle },
                                color = if (isFocused || isSelected) Color.White else Color.White.copy(alpha = 0.70f),
                                fontSize = 12.sp,
                                fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ─── 2. SUB-HEADER WITH ACTION PILLS ────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pill "Destacado"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(parseHexColor(theme.accent).copy(alpha = 0.85f))
                        .border(1.dp, parseHexColor(theme.accent), RoundedCornerShape(50))
                        .padding(horizontal = 24.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (lang == "ro") "Recomandate" else if (lang == "en") "Featured" else "Destacado",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Pill "Ver todas las películas / series / deportes"
                val allActionTitle = when (selectedTab) {
                    CinemaTab.SERIES -> if (lang == "ro") "Vezi toate serialele" else if (lang == "en") "View all series" else "Ver todas las series"
                    CinemaTab.MOVIES -> if (lang == "ro") "Vezi toate filmele" else if (lang == "en") "View all movies" else "Ver todas las películas"
                    CinemaTab.SPORTS -> if (lang == "ro") "Vezi toate sporturile" else if (lang == "en") "View all sports" else "Ver todos los deportes"
                    CinemaTab.FAVORITES -> if (lang == "ro") "Vezi toate favoritele" else if (lang == "en") "View all favorites" else "Ver todos los favoritos"
                    else -> "Ver todo"
                }

                TvButton(
                    onClick = {
                        when (selectedTab) {
                            CinemaTab.SERIES -> onNavigate("series")
                            CinemaTab.MOVIES -> onNavigate("movies")
                            CinemaTab.SPORTS -> onNavigate("sports")
                            CinemaTab.FAVORITES -> onNavigate("movies")
                            else -> {}
                        }
                    },
                    containerColor = Color(0x22FFFFFF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                    focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                    focusedBorderColor = parseHexColor(theme.accent),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp)
                ) { isFocused ->
                    Text(
                        text = allActionTitle,
                        color = Color.White,
                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ─── 3. FEATURED CARDS CAROUSEL (DESTACADOS) ───────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                when (selectedTab) {
                    CinemaTab.SERIES -> {
                        if (featuredSeriesList.isEmpty()) {
                            CinemaEmptyPlaceholder(
                                message = if (lang == "es") "Cargando o no hay series disponibles en esta lista" else "No series found in current playlist",
                                icon = Icons.Default.Tv,
                                theme = theme
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(
                                    items = featuredSeriesList,
                                    key = { index, seriesItem -> "${seriesItem.name}_${seriesItem.url ?: index}_$index" }
                                ) { _, seriesItem ->
                                    CinemaCard(
                                        title = seriesItem.name,
                                        logo = seriesItem.logo,
                                        badge = seriesItem.group ?: "SERIE",
                                        theme = theme,
                                        onClick = {
                                            seriesViewModel.pendingSelectedSeries.value = seriesItem
                                            onNavigate("series")
                                        }
                                    )
                                }
                            }
                        }
                    }

                    CinemaTab.MOVIES -> {
                        if (featuredMoviesList.isEmpty()) {
                            CinemaEmptyPlaceholder(
                                message = if (lang == "es") "Cargando o no hay películas disponibles en esta lista" else "No movies found in current playlist",
                                icon = Icons.Default.Movie,
                                theme = theme
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(
                                    items = featuredMoviesList,
                                    key = { index, movieItem -> "${movieItem.name}_${movieItem.url ?: index}_$index" }
                                ) { _, movieItem ->
                                    CinemaCard(
                                        title = movieItem.name,
                                        logo = movieItem.logo,
                                        badge = movieItem.year?.toString() ?: (movieItem.group ?: "PELÍCULA"),
                                        theme = theme,
                                        onClick = {
                                            moviesViewModel.pendingSelectedMovie.value = movieItem
                                            onNavigate("movies")
                                        }
                                    )
                                }
                            }
                        }
                    }

                    CinemaTab.SPORTS -> {
                        if (featuredSportsList.isEmpty()) {
                            CinemaEmptyPlaceholder(
                                message = if (lang == "es") "No hay partidos o eventos deportivos destacados hoy" else "No live or featured sports events today",
                                icon = Icons.Default.SportsSoccer,
                                theme = theme
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(
                                    items = featuredSportsList,
                                    key = { index, event -> "${event.id}_$index" }
                                ) { _, event ->
                                    CinemaSportsCard(
                                        event = event,
                                        theme = theme,
                                        onClick = {
                                            sportsViewModel.matchAndPlayEvent(
                                                event = event,
                                                onPlay = onPlaySport,
                                                onNotice = { sportsNotice = it }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    CinemaTab.FAVORITES -> {
                        if (featuredFavoritesList.isEmpty()) {
                            CinemaEmptyPlaceholder(
                                message = if (lang == "es") "No tienes favoritos guardados todavía" else "No favorites added yet",
                                icon = Icons.Default.FavoriteBorder,
                                theme = theme
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(
                                    items = featuredFavoritesList,
                                    key = { index, fav -> "${fav.id}_${fav.url}_$index" }
                                ) { _, fav ->
                                    CinemaCard(
                                        title = fav.title,
                                        logo = fav.logo,
                                        badge = fav.type.uppercase(),
                                        theme = theme,
                                        onClick = {
                                            scope.launch {
                                                homeViewModel.setResolvingMovie(true)
                                                try {
                                                    when (val res = homeViewModel.resolveFavorite(fav)) {
                                                        is HomeViewModel.FavoriteResolution.PlayStream -> {
                                                            onPlay(res.url, res.title, res.type, res.drmScheme, res.drmLicenseUrl, res.headers)
                                                        }
                                                        is HomeViewModel.FavoriteResolution.OpenSeries -> {
                                                            seriesViewModel.pendingSelectedSeries.value = res.seriesItem
                                                            onNavigate("series")
                                                        }
                                                        is HomeViewModel.FavoriteResolution.Error -> {
                                                            sportsNotice = res.message
                                                        }
                                                    }
                                                } finally {
                                                    homeViewModel.setResolvingMovie(false)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    else -> {}
                }
            }

            // Aviso temporal flotante para deportes ("Canal no encontrado...", etc.)
            AnimatedVisibility(
                visible = sportsNotice != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = parseHexColor(theme.surface).copy(alpha = 0.95f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, parseHexColor(theme.accentLight)),
                    shadowElevation = 10.dp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = parseHexColor(theme.accentLight),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = sportsNotice ?: "",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Selector de canales cuando hay múltiples candidatos coincidentes para el partido
    val selectionState = sportsUiState.channelSelectionState
    if (selectionState != null) {
        SportsChannelSelectionDialog(
            selectionState = selectionState,
            onSelect = { candidate ->
                sportsViewModel.selectCandidateToPlay(
                    candidate = candidate,
                    onPlay = onPlaySport,
                    onNotice = { sportsNotice = it }
                )
            },
            onDismiss = { sportsViewModel.dismissChannelSelection() },
            theme = theme
        )
    }

    // Indicador modal mientras busca/enlaza canales para el evento deportivo
    if (sportsUiState.isMatchingChannel) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = parseHexColor(theme.surface).copy(alpha = 0.95f),
                border = androidx.compose.foundation.BorderStroke(1.dp, parseHexColor(theme.accent).copy(alpha = 0.4f)),
                shadowElevation = 12.dp,
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = parseHexColor(theme.accent),
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (lang == "es") "Buscando canal para el partido..." else "Searching broadcast channel...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTES AUXILIARES: TARJETA DE CONTENIDO DESTACADO
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CinemaCard(
    title: String,
    logo: String?,
    badge: String,
    theme: AppThemeColors,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(128.dp)
            .tvCardFocusable(
                shape = RoundedCornerShape(10.dp),
                scaleFocused = 1.05f,
                elevationFocused = 8.dp,
                unfocusedBackgroundColor = parseHexColor(theme.card).copy(alpha = 0.55f),
                focusedBackgroundColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedBorderColor = parseHexColor(theme.accent),
                focusedBorderWidth = 1.5.dp,
                onFocusChange = { isFocused = it },
                onClick = onClick
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Carátula vertical en miniatura (proporción rectangular vertical 0.7f idéntica a series y películas)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    parseHexColor(theme.card),
                                    parseHexColor(theme.surface)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.30f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Etiqueta superior (año / categoría / grupo)
            if (badge.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge.uppercase(),
                        color = Color.White,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Título limpio debajo de la carátula sin tapar la imagen
        Text(
            text = title,
            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
            fontSize = 11.5.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        // Subtítulo con año o categoría
        if (badge.isNotBlank()) {
            Text(
                text = badge,
                color = if (isFocused) TV_FOCUSED_TEXT.copy(alpha = 0.85f) else Color.LightGray.copy(alpha = 0.7f),
                fontSize = 9.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


@Composable
private fun CinemaSportsCard(
    event: SportsEvent,
    theme: AppThemeColors,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isLive = event.isLive || event.status == SportsEventStatus.LIVE
    val eventTitle = if (event.homeTeam.isNotBlank() && event.awayTeam.isNotBlank()) {
        "${event.homeTeam} vs ${event.awayTeam}"
    } else {
        event.league.ifBlank { "Evento Deportivo" }
    }

    Box(
        modifier = Modifier
            .width(220.dp)
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .tvCardFocusable(
                shape = RoundedCornerShape(12.dp),
                scaleFocused = 1.05f,
                elevationFocused = 8.dp,
                unfocusedBackgroundColor = parseHexColor(theme.card).copy(alpha = 0.65f),
                focusedBackgroundColor = parseHexColor(theme.accent).copy(alpha = 0.30f),
                unfocusedBorderColor = if (isLive) Color(0xFFEF4444).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f),
                focusedBorderColor = parseHexColor(theme.accent),
                focusedBorderWidth = 1.5.dp,
                onFocusChange = { isFocused = it },
                onClick = onClick
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        parseHexColor(theme.surface).copy(alpha = 0.85f),
                        parseHexColor(theme.card).copy(alpha = 0.95f)
                    )
                )
            )
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: League + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = event.league.ifBlank { "Deportes" },
                    color = parseHexColor(theme.accent),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF4444))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("EN DIRECTO", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                    }
                } else {
                    val formatted = event.getFormattedTime()
                    if (formatted.isNotBlank()) {
                        Text(formatted, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Teams / Event Info
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CinemaTeamCrest(
                        logoUrl = event.homeTeamLogo,
                        teamName = event.homeTeam
                    )
                    Text(
                        text = if (isLive && event.homeScore != null && event.awayScore != null) {
                            "${event.homeScore} : ${event.awayScore}"
                        } else {
                            "VS"
                        },
                        color = if (isLive && event.homeScore != null && event.awayScore != null) {
                            Color.White
                        } else {
                            Color.LightGray.copy(alpha = 0.75f)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    CinemaTeamCrest(
                        logoUrl = event.awayTeamLogo,
                        teamName = event.awayTeam
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = eventTitle,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Footer: Botón "Ver partido"
            val channelCount = event.broadcastChannels.size
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isFocused) parseHexColor(theme.accent) else Color.White.copy(alpha = 0.12f))
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ver partido",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (channelCount > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "($channelCount)",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaTeamCrest(
    logoUrl: String?,
    teamName: String
) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = teamName,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(4.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = teamName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CinemaEmptyPlaceholder(
    message: String,
    icon: ImageVector,
    theme: AppThemeColors
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(parseHexColor(theme.surface).copy(alpha = 0.5f))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.LightGray.copy(alpha = 0.4f),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = Color.LightGray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
