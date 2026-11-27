package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.example.parseHexColor
import com.example.motor.db.IptvItem
import com.example.motor.repository.IptvRepository
import com.example.interfaz.AppThemeColors
import kotlinx.coroutines.launch
import com.example.interfaz.viewmodel.MoviesViewModel
import com.example.interfaz.components.MovieDetailFullScreen
import com.example.interfaz.components.TmdbPosterImage
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.rememberTvInitialFocusRequester

@Composable
fun MoviesScreen(
    viewModel: MoviesViewModel,
    onPlay: (src: String, title: String, drmScheme: String?, drmLicenseUrl: String?) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors
) {
    val precomputedData by viewModel.precomputedMoviesData.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(300)
        debouncedSearchQuery = searchQuery
    }

    val selectedGroup by viewModel.selectedCategoryFlow.collectAsStateWithLifecycle()
    val favoritesList by viewModel.favoritesList.collectAsStateWithLifecycle()
    val pendingMovie by viewModel.pendingSelectedMovie.collectAsStateWithLifecycle()
    var selectedMovieItem by remember { mutableStateOf<IptvItem?>(null) }
    var openedFromHome by remember { mutableStateOf(false) }
    val initialFocusRequester = rememberTvInitialFocusRequester()

    val scope = rememberCoroutineScope()
    var isResolving by remember { mutableStateOf(false) }

    LaunchedEffect(pendingMovie) {
        pendingMovie?.let {
            selectedMovieItem = it
            openedFromHome = true
            viewModel.pendingSelectedMovie.value = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ensureMoviesLoaded()
    }

    // Capture back button to return to Home when no movie modal is open
    BackHandler(enabled = selectedMovieItem == null) {
        onBack()
    }

    if (selectedMovieItem == null && precomputedData == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(parseHexColor(theme.bg)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = parseHexColor(theme.accent))
                Spacer(modifier = Modifier.height(12.dp))
                Text("Cargando catálogo de películas...", color = Color.White, fontSize = 14.sp)
            }
        }
        return
    }

    val groups = remember(precomputedData, settingsObj.hiddenMovieCategories) {
        val baseGroups = precomputedData?.groups ?: listOf("Todos", "Favoritos")
        baseGroups.filter { it == "Todos" || it == "Favoritos" || it !in settingsObj.hiddenMovieCategories }
    }

    LaunchedEffect(groups) {
        if ((selectedGroup.isEmpty() || selectedGroup !in groups) && groups.isNotEmpty()) {
            viewModel.selectedCategoryFlow.value = groups.first()
        }
    }

    val allMovies = precomputedData?.allMovies ?: emptyList()
    val movieMap = precomputedData?.groupToMovies ?: emptyMap()

    val movieFavorites = remember(favoritesList) {
        favoritesList.filter { IptvRepository.normalizeFavoriteType(it.type) == "movie" }
    }

    val hidden = settingsObj.hiddenMovieCategories
    val visibleMovies = remember(allMovies, hidden) {
        if (hidden.isNotEmpty()) {
            allMovies.filter { it.group !in hidden }
        } else {
            allMovies
        }
    }

    val favoriteMovies = remember(visibleMovies, movieFavorites) {
        visibleMovies.filter { IptvRepository.isItemFavorited(it, "movie", movieFavorites) }
    }

    val movieFavCount = remember(favoriteMovies) {
        favoriteMovies.size
    }

    val activeMovies = remember(selectedGroup, debouncedSearchQuery, visibleMovies, favoriteMovies, movieMap, hidden) {
        if (debouncedSearchQuery.isNotBlank()) {
            visibleMovies.filter { it.name.contains(debouncedSearchQuery, ignoreCase = true) }
        } else {
            when (selectedGroup) {
                "Todos" -> visibleMovies
                "Favoritos" -> favoriteMovies
                else -> {
                    if (selectedGroup in hidden) emptyList() else movieMap[selectedGroup] ?: emptyList()
                }
            }
        }
    }

    LaunchedEffect(debouncedSearchQuery) {
        if (debouncedSearchQuery.isNotBlank()) {
            try {
                viewModel.moviesGridState.scrollToItem(0)
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // TOP BAR with Search and Back Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvIconButton(
                onClick = onBack,
                containerColor = Color.White.copy(alpha = 0.05f),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) { isFocused ->
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Películas",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(16.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(parseHexColor(theme.surface), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Buscar película...",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(parseHexColor(theme.accent)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    TvIconButton(
                        onClick = { searchQuery = "" },
                        containerColor = Color.Transparent,
                        shape = CircleShape,
                        modifier = Modifier.size(32.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Limpiar",
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT COLUMN: Categories
            Column(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight()
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "Categorías",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    items(groups.size, key = { idx -> groups[idx] }) { index ->
                        val group = groups[index]
                        val isSel = group == selectedGroup && searchQuery.isEmpty()
                        val count = when (group) {
                            "Todos" -> visibleMovies.size
                            "Favoritos" -> movieFavCount
                            else -> precomputedData?.categoryCounts?.get(group) ?: movieMap[group]?.size ?: 0
                        }
                        var isFocused by remember { mutableStateOf(false) }
                        val bg = if (isFocused) parseHexColor(theme.cardHover) else if (isSel) parseHexColor(theme.accent).copy(alpha = 0.65f) else parseHexColor(theme.surface)
                        val borderCol = if (isFocused) parseHexColor(theme.accent) else if (isSel) parseHexColor(theme.accentLight).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f)
                        val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSel) Color.White else Color.LightGray

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(8.dp),
                                    unfocusedBackgroundColor = if (isSel) parseHexColor(theme.accent).copy(alpha = 0.60f) else parseHexColor(theme.card).copy(alpha = 0.55f),
                                    focusedBackgroundColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                                    unfocusedBorderColor = if (isSel) parseHexColor(theme.accentLight).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f),
                                    focusedBorderColor = parseHexColor(theme.accent),
                                    focusRequester = if (index == 0) initialFocusRequester else null,
                                    onFocusChange = { isFocused = it },
                                    onClick = {
                                        searchQuery = "" // Reset search to show group content
                                        if (viewModel.selectedCategoryFlow.value != group) {
                                            viewModel.selectedCategoryFlow.value = group
                                            scope.launch {
                                                try {
                                                    viewModel.moviesGridState.scrollToItem(0)
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "$group ($count)",
                                color = tc,
                                fontSize = 12.sp,
                                fontWeight = if (isFocused || isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 4.dp)
            )

            // RIGHT AREA: Posters Grid
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp)
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty()) "Resultados de búsqueda" else "Películas disponibles",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                )
                if (activeMovies.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No se encontraron películas", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyVerticalGrid(
                        state = viewModel.moviesGridState,
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            items = activeMovies,
                            key = { index, it -> "${it.name}_${it.group}_${it.url ?: ""}_$index" }
                        ) { _, item ->
                            val cleanedInfo = remember(item.name) {
                                viewModel.cleanTitleAndExtractYear(item.name)
                            }
                            val displayName = cleanedInfo.title.ifBlank { item.name }
                            var displayYear by remember(item.name) {
                                val cachedYear = viewModel.getCachedYear(item.name, isSeries = false)
                                mutableStateOf(cachedYear ?: item.year ?: cleanedInfo.year)
                            }
                            var isFocused by remember { mutableStateOf(false) }
                            val cardBg = if (isFocused) parseHexColor(theme.cardHover) else parseHexColor(theme.surface)
                            val titleColor = if (isFocused) TV_FOCUSED_TEXT else Color.White
                            val subtitleColor = if (isFocused) TV_FOCUSED_TEXT else Color.Gray

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvCardFocusable(
                                        shape = RoundedCornerShape(10.dp),
                                        unfocusedBackgroundColor = parseHexColor(theme.card).copy(alpha = 0.55f),
                                        focusedBackgroundColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                                        focusedBorderColor = parseHexColor(theme.accent),
                                        onFocusChange = { isFocused = it },
                                        onClick = { selectedMovieItem = item }
                                    )
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                                    TmdbPosterImage(
                                        title = item.name,
                                        isSeries = false,
                                        defaultLogo = item.logo,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(6.dp)),
                                        onYearResolved = { resolvedYear ->
                                            displayYear = resolvedYear
                                        },
                                        fetchPosterAndYear = { title, isSeries ->
                                            viewModel.fetchPosterAndYear(title, isSeries)
                                        }
                                    )

                                    val isFav = remember(item, movieFavorites) {
                                        IptvRepository.isItemFavorited(item, "movie", movieFavorites)
                                    }
                                    TvIconButton(
                                        onClick = { viewModel.toggleFavorite(item) },
                                        containerColor = Color.Black.copy(alpha = 0.6f),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(28.dp)
                                    ) { isFavBtnFocused ->
                                        Icon(
                                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Favorito",
                                            tint = if (isFavBtnFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = displayName,
                                    color = titleColor,
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                if (displayYear != null && displayYear != 0) {
                                    Text(
                                        text = displayYear.toString(),
                                        color = subtitleColor,
                                        fontSize = 11.sp,
                                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedMovieItem != null) {
        val movie = selectedMovieItem!!
            val isFav = remember(movie, movieFavorites) {
                IptvRepository.isItemFavorited(movie, "movie", movieFavorites)
            }
            MovieDetailFullScreen(
                movie = movie,
                isFav = isFav,
                onToggleFavorite = { viewModel.toggleFavorite(movie) },
                onPlay = {
                    scope.launch {
                        isResolving = true
                        try {
                            val finalUrl = viewModel.resolvePlayUrl(movie)
                            onPlay(finalUrl, movie.name, movie.drmScheme, movie.drmLicenseUrl)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            movie.url?.let { onPlay(it, movie.name, movie.drmScheme, movie.drmLicenseUrl) }
                        } finally {
                            isResolving = false
                        }
                    }
                },
                onBack = {
                    selectedMovieItem = null
                    if (openedFromHome) {
                        openedFromHome = false
                        onBack()
                    }
                },
                theme = theme,
                onFetchDetails = { item -> viewModel.fetchMovieDetails(item) },
                onSearchSubtitles = { title -> viewModel.searchMovieSubtitles(title) },
                onGetCachedYear = { title -> viewModel.getCachedYear(title) }
            )
        }
    }

    if (isResolving) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Card(
                colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = parseHexColor(theme.accent))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Obteniendo enlace de reproducción...", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}
