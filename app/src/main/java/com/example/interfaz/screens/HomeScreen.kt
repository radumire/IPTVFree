package com.example.interfaz.screens

import kotlinx.coroutines.launch
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.Translator
import com.example.parseHexColor
import com.example.motor.db.*
import com.example.motor.config.*
import com.example.motor.repository.IptvRepository
import com.example.interfaz.AppThemeColors
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.state.HomeState
import com.example.interfaz.components.MovieDetailModal
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.rememberTvInitialFocusRequester
import androidx.compose.ui.focus.FocusRequester

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigate: (String) -> Unit,
    onPlay: (src: String, title: String, type: String, drmScheme: String?, drmLicenseUrl: String?, headers: Map<String, String>) -> Unit,
    onOpenSeries: (IptvItem) -> Unit = {},
    theme: AppThemeColors
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    var selectedMovieItem by remember { mutableStateOf<IptvItem?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialFocusRequester = rememberTvInitialFocusRequester()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)
        ) {
            // Live Real-Time Background Download Counter Panel (Stalker/MAC)
            if (uiState.isStalkerDownloading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(parseHexColor(theme.accent).copy(alpha = 0.18f))
                            .border(1.dp, parseHexColor(theme.accent).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(parseHexColor(theme.accent).copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = parseHexColor(theme.accent),
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Descarga Asíncrona en Segundo Plano",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "En Vivo",
                                    color = parseHexColor(theme.accent),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (uiState.stalkerDownloadStats.isNotEmpty()) uiState.stalkerDownloadStats else "Sincronizando catálogo Stalker/MAC en vivo...",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Canales de TV en directo y contenido disponible habilitados para ver mientras descarga.",
                                color = Color.LightGray.copy(alpha = 0.7f),
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }
            }

            // Quick overview metrics
            item {
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max)
                ) {
                    MetricCard(title = "Canales de TV", count = uiState.totalLiveCount, icon = Icons.Default.Tv, onClick = { onNavigate("live_tv") }, theme = theme, modifier = Modifier.weight(1f).fillMaxHeight(), focusRequester = initialFocusRequester)
                    MetricCard(title = "Películas VOD", count = uiState.totalMoviesCount, icon = Icons.Default.Movie, onClick = { onNavigate("movies") }, theme = theme, modifier = Modifier.weight(1f).fillMaxHeight())
                    MetricCard(title = "Series", count = uiState.totalSeriesCount, icon = Icons.Default.VideoLibrary, onClick = { onNavigate("series") }, theme = theme, modifier = Modifier.weight(1f).fillMaxHeight())
                    MetricCard(title = "Deportes", countText = "Agenda", icon = Icons.Default.SportsSoccer, onClick = { onNavigate("sports") }, theme = theme, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }

            // Películas Más Nuevas (VOD) replacing Panel de Control
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Películas Más Nuevas",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    val newestMovies = uiState.newestMovies
                    if (newestMovies.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(
                                newestMovies,
                                key = { index, movie -> homeNewestMovieKey(index, movie) }
                            ) { _, movie ->
                                var isFocused by remember { mutableStateOf(false) }
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .tvCardFocusable(
                                            shape = RoundedCornerShape(8.dp),
                                            scaleFocused = 1.05f,
                                            elevationFocused = 4.dp,
                                            onFocusChange = { isFocused = it },
                                            onClick = { selectedMovieItem = movie }
                                        )
                                        .background(if (isFocused) TV_FOCUSED_BG else parseHexColor(theme.surface))
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(0.7f)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isFocused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (movie.logo != null) {
                                            AsyncImage(
                                                model = movie.logo,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = null,
                                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val cleanedInfo = remember(movie.name) {
                                        viewModel.cleanTitleAndExtractYear(movie.name)
                                    }
                                    val displayName = cleanedInfo.title.ifEmpty { movie.name ?: "" }
                                    var displayYear by remember(movie.name) {
                                        val cachedYear = viewModel.getCachedYear(movie.name, isSeries = false)
                                        mutableStateOf(cachedYear ?: movie.year ?: cleanedInfo.year)
                                    }

                                    LaunchedEffect(movie.name) {
                                        val mName = movie.name
                                        if (!mName.isNullOrBlank()) {
                                            val (_, realYear) = viewModel.fetchPosterAndYear(mName, isSeries = false)
                                            if (realYear != null && realYear > 1900) {
                                                displayYear = realYear
                                            }
                                        }
                                    }
                                    Text(
                                        text = displayName,
                                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (displayYear != null && displayYear != 0) {
                                        Text(
                                            text = displayYear.toString(),
                                            color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                            fontSize = 9.sp,
                                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(parseHexColor(theme.surface))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Carga una lista con películas para ver los últimos estrenos",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Continue watching history
            val continueWatching = uiState.continueWatching
            if (continueWatching.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Seguir Viendo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            TvButton(
                                onClick = { viewModel.clearAllPlaybackHistory() },
                                containerColor = parseHexColor(theme.card).copy(alpha = 0.60f),
                                focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Limpiar todo",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Borrar todo",
                                    color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        }
                        LazyRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(
                                continueWatching.take(10),
                                key = { index, hist -> homeContinueWatchingKey(index, hist) }
                            ) { _, hist ->
                                var isFocused by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .width(190.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .tvClickable(
                                            focusedBackgroundColor = TV_FOCUSED_BG,
                                            unfocusedBackgroundColor = parseHexColor(theme.card).copy(alpha = 0.60f),
                                            onFocusChange = { isFocused = it },
                                            onClick = {
                                                if (viewModel.canPlayHistoryItem(hist)) {
                                                    onPlay(hist.src, hist.title, hist.type, null, null, emptyMap())
                                                }
                                            }
                                        )
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                hist.title,
                                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TvIconButton(
                                                onClick = { viewModel.deletePlaybackHistory(hist.src) },
                                                modifier = Modifier.size(22.dp)
                                            ) { isCloseBtnFocused ->
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Eliminar de seguir viendo",
                                                    tint = if (isCloseBtnFocused) TV_FOCUSED_TEXT else (if (isFocused) Color.White else Color.LightGray),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text("Visto al ${(hist.pct * 100).toInt()}%", color = if (isFocused) TV_FOCUSED_TEXT.copy(alpha = 0.8f) else Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { hist.pct },
                                            color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                            trackColor = if (isFocused) Color.Black.copy(alpha = 0.2f) else Color.DarkGray,
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Favorites quick access
            val favsList = uiState.favorites
            if (favsList.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = Translator.translate("recent_favorites", settingsObj.lang),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TvButton(
                                onClick = { onNavigate("favorites") },
                                containerColor = parseHexColor(theme.card).copy(alpha = 0.65f),
                                focusedContainerColor = TV_FOCUSED_BG,
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) { isViewAllFocused ->
                                Text(
                                    text = "${Translator.translate("view_all", settingsObj.lang)} (${favsList.size})",
                                    color = if (isViewAllFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accentLight),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        LazyRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(
                                favsList.take(6),
                                key = { index, fav -> homeFavoriteKey(index, fav) }
                            ) { _, fav ->
                                var isFocused by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .tvClickable(
                                            focusedBackgroundColor = TV_FOCUSED_BG,
                                            unfocusedBackgroundColor = parseHexColor(theme.card).copy(alpha = 0.60f),
                                            onFocusChange = { isFocused = it },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.setResolvingMovie(true)
                                                    try {
                                                        when (val res = viewModel.resolveFavorite(fav)) {
                                                            is HomeViewModel.FavoriteResolution.PlayStream -> {
                                                                onPlay(res.url, res.title, res.type, res.drmScheme, res.drmLicenseUrl, res.headers)
                                                            }
                                                            is HomeViewModel.FavoriteResolution.OpenSeries -> {
                                                                onOpenSeries(res.seriesItem)
                                                            }
                                                            is HomeViewModel.FavoriteResolution.Error -> {
                                                                android.widget.Toast.makeText(context, res.message, android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    } finally {
                                                        viewModel.setResolvingMovie(false)
                                                    }
                                                }
                                            }
                                        )
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (fav.logo != null) {
                                            AsyncImage(
                                                model = fav.logo,
                                                contentDescription = null,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(4.dp))
                                            )
                                        } else {
                                            Icon(imageVector = Icons.Default.Tv, contentDescription = null, tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray, modifier = Modifier.size(36.dp))
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            fav.title,
                                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedMovieItem?.let { movie ->
        val isFav = remember(movie, uiState.favorites) {
            IptvRepository.isItemFavorited(movie, "movie", uiState.favorites)
        }
        MovieDetailModal(
            movie = movie,
            isFav = isFav,
            onToggleFavorite = { viewModel.toggleFavoriteItem(movie, "movie") },
            onPlay = {
                if (!movie.url.isNullOrEmpty()) {
                    onPlay(movie.url, movie.name, "movie", movie.drmScheme, movie.drmLicenseUrl, emptyMap())
                } else if (!movie.stalkerCmd.isNullOrEmpty()) {
                    scope.launch {
                        viewModel.setResolvingMovie(true)
                        try {
                            val resolved = viewModel.resolveStalkerPlayUrl(movie.stalkerCmd, "vod")
                            onPlay(resolved, movie.name, "movie", movie.drmScheme, movie.drmLicenseUrl, emptyMap())
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            viewModel.setResolvingMovie(false)
                        }
                    }
                }
            },
            onDismiss = { selectedMovieItem = null },
            theme = theme,
            activePlaylist = uiState.activePlaylist,
            onFetchDetails = { item -> viewModel.fetchXtreamMediaDetails(item, uiState.activePlaylist) },
            onSearchSubtitles = { title -> viewModel.searchMovieSubtitles(title) },
            onGetCachedYear = { title -> viewModel.getCachedYear(title) }
        )
    }

    if (uiState.isResolvingMovie) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = parseHexColor(theme.accent))
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    count: Int? = null,
    countText: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = parseHexColor(theme.surface)
        ),
        modifier = modifier
            .fillMaxHeight()
            .tvCardFocusable(
                shape = CardDefaults.shape,
                scaleFocused = 1.04f,
                elevationFocused = 6.dp,
                focusRequester = focusRequester,
                onFocusChange = { isFocused = it },
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isFocused) Color.White else Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = countText ?: (count?.toString() ?: "•"),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) Color.White else parseHexColor(theme.accent),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

internal fun homeNewestMovieKey(index: Int, movie: IptvItem): String =
    "${movie.name}_${movie.stalkerCmd ?: ""}_${movie.url ?: ""}_$index"

internal fun homeContinueWatchingKey(index: Int, hist: PlaybackHistory): String =
    "${hist.playlistId}_${hist.src}_${hist.title}_$index"

internal fun homeFavoriteKey(index: Int, fav: FavoriteItem): String =
    "${fav.playlistId}_${fav.id}_${fav.type}_$index"
