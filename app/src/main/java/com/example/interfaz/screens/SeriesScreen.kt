package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import com.example.parseHexColor
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesEpisode
import com.example.motor.db.SeriesSeason
import com.example.motor.repository.IptvRepository
import com.example.motor.repository.MediaDetails
import com.example.interfaz.AppThemeColors
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.interfaz.viewmodel.EpisodeWatchStatus
import com.example.interfaz.components.TmdbPosterImage
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.rememberTvInitialFocusRequester
import kotlinx.coroutines.launch

@Composable
fun SeriesScreen(
    viewModel: SeriesViewModel,
    onPlay: (src: String, title: String, drmScheme: String?, drmLicenseUrl: String?, seriesTitle: String?, season: Int?, episode: Int?) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors
) {
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val precomputedData by viewModel.precomputedSeriesData.collectAsStateWithLifecycle()
    val historyList by viewModel.playbackHistory.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(300)
        debouncedSearchQuery = searchQuery
    }

    val selectedGroup by viewModel.selectedCategoryFlow.collectAsStateWithLifecycle()
    val favoritesList by viewModel.favoritesList.collectAsStateWithLifecycle()
    val initialFocusRequester = rememberTvInitialFocusRequester()

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val pendingSeries by viewModel.pendingSelectedSeries.collectAsStateWithLifecycle()
    var selectedSeriesItem by remember { mutableStateOf<IptvItem?>(null) }
    var openedFromHome by remember { mutableStateOf(false) }
    var seasonsList by remember { mutableStateOf<List<SeriesSeason>?>(null) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var selectedSeasonIndex by remember { mutableStateOf(0) }

    LaunchedEffect(pendingSeries) {
        val item = pendingSeries ?: return@LaunchedEffect
        selectedSeriesItem = item
        openedFromHome = true
        selectedSeasonIndex = 0
        viewModel.pendingSelectedSeries.value = null

        val cached = viewModel.getCachedEpisodesForSeries(item)
        if (cached != null) {
            seasonsList = cached
            isLoadingEpisodes = false
        } else {
            seasonsList = null
            isLoadingEpisodes = true
            scope.launch {
                try {
                    val loaded = viewModel.loadEpisodesForSeries(item)
                    if (selectedSeriesItem == item) {
                        seasonsList = loaded
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (selectedSeriesItem == item) {
                        seasonsList = emptyList()
                    }
                } finally {
                    if (selectedSeriesItem == item) {
                        isLoadingEpisodes = false
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ensureSeriesLoaded()
    }

    // Capture back button to return to Home when no series is selected
    BackHandler(enabled = selectedSeriesItem == null) {
        onBack()
    }

    // Loading State for when background optimization is processing the massive dataset and user is viewing catalog
    if (selectedSeriesItem == null && precomputedData == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(parseHexColor(theme.bg)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                CircularProgressIndicator(
                    color = parseHexColor(theme.accent),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Cargando catálogo de series...",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    val groups = remember(precomputedData, settingsObj.hiddenSeriesCategories) {
        val baseGroups = precomputedData?.groups ?: emptyList()
        baseGroups.filter { it == "Todos" || it == "Favoritos" || it !in settingsObj.hiddenSeriesCategories }
    }
    val allSeries = precomputedData?.allSeries ?: emptyList()

    val seriesFavorites = remember(favoritesList) {
        favoritesList.filter { IptvRepository.normalizeFavoriteType(it.type) == "series" }
    }

    val hiddenSeries = settingsObj.hiddenSeriesCategories
    val visibleSeries = remember(precomputedData, hiddenSeries) {
        val data = precomputedData ?: return@remember emptyList()
        if (hiddenSeries.isNotEmpty()) {
            data.allSeriesUnique.filter { it.group !in hiddenSeries }
        } else {
            data.allSeriesUnique
        }
    }

    val favoriteSeries = remember(visibleSeries, seriesFavorites) {
        visibleSeries.filter { IptvRepository.isItemFavorited(it, "series", seriesFavorites) }
    }

    val favoritesCount = remember(favoriteSeries) {
        favoriteSeries.size
    }

    val categoryCounts = remember(precomputedData, favoritesCount, visibleSeries.size) {
        val counts = precomputedData?.categoryCounts ?: emptyMap()
        counts.toMutableMap().apply {
            this["Todos"] = visibleSeries.size
            this["Favoritos"] = favoritesCount
        }
    }

    LaunchedEffect(groups) {
        if ((selectedGroup.isEmpty() || selectedGroup !in groups) && groups.isNotEmpty()) {
            viewModel.selectedCategoryFlow.value = groups.first()
        }
    }

    // High performance search and group filtering that runs in <1ms!
    val activeSeriesUnique = remember(selectedGroup, debouncedSearchQuery, precomputedData, visibleSeries, favoriteSeries, hiddenSeries) {
        val data = precomputedData ?: return@remember emptyList()
        if (debouncedSearchQuery.isNotBlank()) {
            visibleSeries.filter { it.name.contains(debouncedSearchQuery, ignoreCase = true) }
        } else {
            when (selectedGroup) {
                "Todos" -> visibleSeries
                "Favoritos" -> favoriteSeries
                else -> {
                    if (selectedGroup in hiddenSeries) emptyList() else data.groupToUniqueSeries[selectedGroup] ?: emptyList()
                }
            }
        }
    }

    LaunchedEffect(debouncedSearchQuery) {
        if (debouncedSearchQuery.isNotBlank()) {
            try {
                viewModel.seriesGridState.scrollToItem(0)
            } catch (_: Exception) {}
        }
    }

    if (selectedSeriesItem == null) {
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
                    text = "Series",
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
                                text = "Buscar serie...",
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
                        items(groups.size, key = { idx -> groups[idx] }) { idx ->
                            val group = groups[idx]
                            val isSel = group == selectedGroup && searchQuery.isEmpty()
                            val count = categoryCounts[group] ?: 0
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
                                    focusRequester = if (idx == 0) initialFocusRequester else null,
                                    onFocusChange = { isFocused = it },
                                        onClick = {
                                            searchQuery = "" // Reset search to show group content
                                            if (viewModel.selectedCategoryFlow.value != group) {
                                                viewModel.selectedCategoryFlow.value = group
                                                scope.launch {
                                                    try {
                                                        viewModel.seriesGridState.scrollToItem(0)
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
                        text = if (searchQuery.isNotEmpty()) "Resultados de búsqueda" else "Series disponibles",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                    )
                    if (activeSeriesUnique.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No se encontraron series", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyVerticalGrid(
                            state = viewModel.seriesGridState,
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                count = activeSeriesUnique.size,
                                key = { index ->
                                    val item = activeSeriesUnique[index]
                                    "${item.seriesId ?: ""}_${item.name}_${item.group}_${item.url ?: ""}_$index"
                                }
                            ) { index ->
                                val item = activeSeriesUnique[index]
                                val cleanedInfo = remember(item.name) {
                                    viewModel.cleanTitleAndExtractYear(item.name)
                                }
                                val displayName = cleanedInfo.title.ifBlank { item.name }
                                var displayYear by remember(item.name) {
                                    val cachedYear = viewModel.getCachedYear(item.name, isSeries = true)
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
                                            onClick = {
                                                selectedSeriesItem = item
                                                selectedSeasonIndex = 0
                                                val cached = viewModel.getCachedEpisodesForSeries(item)
                                                if (cached != null) {
                                                    seasonsList = cached
                                                } else {
                                                    seasonsList = null
                                                    scope.launch {
                                                        isLoadingEpisodes = true
                                                        try {
                                                            seasonsList = viewModel.loadEpisodesForSeries(item)
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                            seasonsList = emptyList()
                                                        } finally {
                                                            isLoadingEpisodes = false
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                                        TmdbPosterImage(
                                            title = item.name,
                                            isSeries = true,
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

                                        val isFav = remember(item, seriesFavorites) {
                                            IptvRepository.isItemFavorited(item, "series", seriesFavorites)
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
    }

    } else {
        val series = selectedSeriesItem!!
        val cleanedInfo = remember(series.name) {
            viewModel.cleanTitleAndExtractYear(series.name)
        }
        val seriesTitle = cleanedInfo.title.ifBlank { series.name }

        var seriesDetails by remember(series.name) { mutableStateOf<MediaDetails?>(null) }
        var isDetailsLoading by remember(series.name) { mutableStateOf(true) }
        var showFullSynopsisDialog by remember(series.name) { mutableStateOf(false) }

        LaunchedEffect(series.name) {
            isDetailsLoading = true
            try {
                seriesDetails = viewModel.fetchSeriesDetails(series)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDetailsLoading = false
            }
        }

        val isFav = remember(series, seriesFavorites) {
            IptvRepository.isItemFavorited(series, "series", seriesFavorites)
        }
        val firstSeasonFocusRequester = remember { FocusRequester() }
        val selectedSeasonFocusRequester = remember { FocusRequester() }
        val firstEpisodeFocusRequester = remember { FocusRequester() }
        val playButtonFocusRequester = remember { FocusRequester() }

        val closeSeriesDetail: () -> Unit = {
            if (showFullSynopsisDialog) {
                showFullSynopsisDialog = false
            } else {
                selectedSeriesItem = null
                if (openedFromHome) {
                    openedFromHome = false
                    onBack()
                }
            }
        }

        BackHandler(enabled = true) {
            closeSeriesDetail()
        }

        LaunchedEffect(seasonsList) {
            if (!seasonsList.isNullOrEmpty()) {
                kotlinx.coroutines.delay(120)
                try {
                    firstSeasonFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(parseHexColor(theme.bg))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Series Overview Header (Horizontal Layout: Back button -> Poster -> Info & Badges -> Synopsis -> Action Buttons)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Back button
                    TvIconButton(
                        onClick = closeSeriesDetail,
                        containerColor = Color.White.copy(alpha = 0.08f),
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(end = 10.dp, top = 2.dp)
                            .size(36.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver al catálogo",
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                            // Poster
                            val posterModel = seriesDetails?.posterUrl ?: series.logo
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .aspectRatio(0.68f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!posterModel.isNullOrBlank()) {
                                    AsyncImage(
                                        model = posterModel,
                                        contentDescription = seriesTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            // Metadata Column (Title, Badges, Action Buttons)
                            Column(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = seriesDetails?.title ?: seriesTitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // Badges
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                                ) {
                                    val displayYear = seriesDetails?.year ?: viewModel.getCachedYear(series.name, isSeries = true) ?: series.year ?: cleanedInfo.year
                                    if (displayYear != null && displayYear != 0) {
                                        Surface(
                                            color = Color.White.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = displayYear.toString(),
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val rating = seriesDetails?.voteAverage
                                    if (rating != null && rating > 0.0) {
                                        Surface(
                                            color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = Color(0xFFF59E0B),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = String.format(java.util.Locale.US, "%.1f", rating),
                                                    color = Color(0xFFF59E0B),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                val genres = seriesDetails?.genres
                                if (!genres.isNullOrEmpty()) {
                                    Text(
                                        text = genres.take(2).joinToString(" • "),
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }

                                // Action Buttons (Play first episode + Fav)
                                Row(
                                    modifier = Modifier.padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val firstSeason = seasonsList?.firstOrNull()
                                    val firstEp = firstSeason?.episodes?.firstOrNull()

                                     if (firstEp != null) {
                                        TvButton(
                                            onClick = {
                                                val finalDrmScheme = selectedSeriesItem?.drmScheme
                                                val finalDrmLicenseUrl = selectedSeriesItem?.drmLicenseUrl
                                                scope.launch {
                                                    isLoadingEpisodes = true
                                                    try {
                                                        val resolved = viewModel.resolveEpisodePlayUrl(firstEp)
                                                        if (resolved.isNotBlank()) {
                                                            val sNum = firstEp.seasonNum.toIntOrNull() ?: com.example.motor.tmdb.TmdbService.extractSeasonEpisode(firstSeason.name)?.season ?: 1
                                                            val eNum = firstEp.episodeNum.toIntOrNull() ?: com.example.motor.tmdb.TmdbService.extractSeasonEpisode(firstEp.title)?.episode ?: 1
                                                            onPlay(resolved, "$seriesTitle - ${firstSeason.name} - ${firstEp.title}", finalDrmScheme, finalDrmLicenseUrl, seriesTitle, sNum, eNum)
                                                        } else {
                                                            android.widget.Toast.makeText(context, "No se pudo obtener la URL del episodio", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        android.widget.Toast.makeText(context, "Error al reproducir el episodio", android.widget.Toast.LENGTH_SHORT).show()
                                                    } finally {
                                                        isLoadingEpisodes = false
                                                    }
                                                }
                                            },
                                            focusRequester = playButtonFocusRequester,
                                            containerColor = parseHexColor(theme.accent),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .onKeyEvent { keyEvent ->
                                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                                        val keyCode = keyEvent.nativeKeyEvent.keyCode
                                                        if (keyEvent.key == Key.DirectionDown || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                                            try {
                                                                firstSeasonFocusRequester.requestFocus()
                                                                return@onKeyEvent true
                                                            } catch (_: Exception) {}
                                                        }
                                                    }
                                                    false
                                                }
                                        ) { isFocused ->
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reproducir", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    TvIconButton(
                                        onClick = { viewModel.toggleFavorite(series) },
                                        containerColor = Color.White.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.size(32.dp)
                                    ) { isFavBtnFocused ->
                                        Icon(
                                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Favorito",
                                            tint = if (isFavBtnFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Synopsis Box (Placed directly to the right side of the title and metadata)
                            Column(
                                modifier = Modifier
                                    .weight(1.4f)
                                    .padding(start = 8.dp, end = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Sinopsis",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TvIconButton(
                                        onClick = closeSeriesDetail,
                                        containerColor = Color.White.copy(alpha = 0.1f),
                                        shape = CircleShape,
                                        modifier = Modifier.size(28.dp)
                                    ) { isFocused ->
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cerrar",
                                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                val synopsis = seriesDetails?.overview?.ifBlank { "Cargando sinopsis..." }
                                    ?: (if (isDetailsLoading) "Cargando sinopsis..." else "Sin sinopsis disponible.")
                                var isSynopsisFocused by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSynopsisFocused) TV_FOCUSED_BG else Color.White.copy(alpha = 0.04f))
                                        .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isSynopsisFocused = it }) {
                                            if (synopsis != "Cargando sinopsis..." && synopsis != "Sin sinopsis disponible.") {
                                                showFullSynopsisDialog = true
                                            }
                                        }
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = synopsis,
                                            color = if (isSynopsisFocused) TV_FOCUSED_TEXT else Color(0xFFD1D5DB),
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (synopsis.length > 120 && synopsis != "Cargando sinopsis..." && synopsis != "Sin sinopsis disponible.") {
                                            Text(
                                                text = "Ver más...",
                                                color = if (isSynopsisFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.1f))
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (isLoadingEpisodes) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = parseHexColor(theme.accent))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Cargando temporadas...", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        } else if (seasonsList.isNullOrEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No se encontraron episodios o temporadas.", color = Color.Gray, fontSize = 14.sp)
                            }
                        } else {
                            val seasons = seasonsList!!
                            val seasonIndex = if (selectedSeasonIndex < seasons.size) selectedSeasonIndex else 0
                            val currentSeason = seasons[seasonIndex]

                            Row(modifier = Modifier.fillMaxSize()) {
                                // Left Column: Seasons list
                                Column(
                                    modifier = Modifier
                                        .width(180.dp)
                                        .fillMaxHeight()
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = "Temporadas",
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(seasons.size) { idx ->
                                            val season = seasons[idx]
                                            val isSel = idx == seasonIndex
                                            val isFirst = idx == 0
                                            val itemModifier = when {
                                                isSel -> Modifier.focusRequester(selectedSeasonFocusRequester)
                                                isFirst -> Modifier.focusRequester(firstSeasonFocusRequester)
                                                else -> Modifier
                                            }
                                            var isFocused by remember { mutableStateOf(false) }
                                            val bg = if (isFocused) parseHexColor(theme.cardHover) else if (isSel) parseHexColor(theme.accent).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f)
                                            val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSel) Color.White else Color.LightGray

                                            Box(
                                                modifier = itemModifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(bg)
                                                    .onFocusChanged {
                                                        if (it.isFocused) {
                                                            selectedSeasonIndex = idx
                                                        }
                                                    }
                                                    .onKeyEvent { keyEvent ->
                                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                                            val keyCode = keyEvent.nativeKeyEvent.keyCode
                                                            if (keyEvent.key == Key.DirectionRight || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                                                                try {
                                                                    firstEpisodeFocusRequester.requestFocus()
                                                                    return@onKeyEvent true
                                                                } catch (_: Exception) {}
                                                            } else if (isFirst && (keyEvent.key == Key.DirectionUp || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
                                                                try {
                                                                    playButtonFocusRequester.requestFocus()
                                                                    return@onKeyEvent true
                                                                } catch (_: Exception) {}
                                                            }
                                                        }
                                                        false
                                                    }
                                                    .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) { selectedSeasonIndex = idx }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                            ) {
                                                Text(
                                                    text = season.name,
                                                    color = tc,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isFocused || isSel) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                    }
                                }

                                // Vertical divider
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.1f))
                                )

                                // Right Column: Episodes of the selected season
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(start = 16.dp)
                                ) {
                                    Text(
                                        text = "Episodios de la ${currentSeason.name}",
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                     LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                     ) {
                                        items(currentSeason.episodes.size) { idx ->
                                            val episode = currentSeason.episodes[idx]
                                            val watchState = remember(episode, currentSeason.name, seriesTitle, historyList) {
                                                viewModel.getEpisodeWatchState(episode, currentSeason.name, seriesTitle, historyList)
                                            }
                                            var isFocused by remember { mutableStateOf(false) }
                                            val cardBg = if (isFocused) TV_FOCUSED_BG else Color.White.copy(alpha = 0.05f)
                                            val titleColor = if (isFocused) TV_FOCUSED_TEXT else Color.White
                                            val descColor = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray
                                            val iconColor = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent)

                                            val isFirst = idx == 0
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = (if (isFirst) Modifier.focusRequester(firstEpisodeFocusRequester) else Modifier)
                                                    .fillMaxWidth()
                                                    .onKeyEvent { keyEvent ->
                                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                                            val keyCode = keyEvent.nativeKeyEvent.keyCode
                                                            if (keyEvent.key == Key.DirectionLeft || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                                                try {
                                                                    selectedSeasonFocusRequester.requestFocus()
                                                                    return@onKeyEvent true
                                                                } catch (_: Exception) {}
                                                            } else if (isFirst && (keyEvent.key == Key.DirectionUp || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
                                                                try {
                                                                    playButtonFocusRequester.requestFocus()
                                                                    return@onKeyEvent true
                                                                } catch (_: Exception) {}
                                                            }
                                                        }
                                                        false
                                                    }
                                                    .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) {
                                                        val finalDrmScheme = selectedSeriesItem?.drmScheme
                                                        val finalDrmLicenseUrl = selectedSeriesItem?.drmLicenseUrl
                                                        scope.launch {
                                                            isLoadingEpisodes = true
                                                            try {
                                                                val resolved = viewModel.resolveEpisodePlayUrl(episode)
                                                                if (resolved.isNotBlank()) {
                                                                    val sNum = episode.seasonNum.toIntOrNull() ?: com.example.motor.tmdb.TmdbService.extractSeasonEpisode(currentSeason.name)?.season ?: (selectedSeasonIndex + 1)
                                                                    val eNum = episode.episodeNum.toIntOrNull() ?: com.example.motor.tmdb.TmdbService.extractSeasonEpisode(episode.title)?.episode ?: 1
                                                                    onPlay(resolved, "${seriesTitle} - ${currentSeason.name} - ${episode.title}", finalDrmScheme, finalDrmLicenseUrl, seriesTitle, sNum, eNum)
                                                                } else {
                                                                    android.widget.Toast.makeText(context, "No se pudo obtener la URL del episodio", android.widget.Toast.LENGTH_SHORT).show()
                                                                }
                                                            } catch (e: Exception) {
                                                                e.printStackTrace()
                                                                android.widget.Toast.makeText(context, "Error al reproducir el episodio", android.widget.Toast.LENGTH_SHORT).show()
                                                            } finally {
                                                                isLoadingEpisodes = false
                                                            }
                                                        }
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Reproducir",
                                                        tint = iconColor,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = episode.title,
                                                                color = titleColor,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.weight(1f, fill = false)
                                                            )
                                                            if (watchState.status == EpisodeWatchStatus.WATCHED) {
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    modifier = Modifier
                                                                        .background(
                                                                            color = if (isFocused) Color.White.copy(alpha = 0.2f) else parseHexColor(theme.accent).copy(alpha = 0.18f),
                                                                            shape = RoundedCornerShape(4.dp)
                                                                        )
                                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Check,
                                                                        contentDescription = "Visto",
                                                                        tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                                                        modifier = Modifier.size(12.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text(
                                                                        text = "VISTO",
                                                                        color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        if (episode.desc.isNotEmpty()) {
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                            Text(
                                                                text = episode.desc,
                                                                color = descColor,
                                                                fontSize = 12.sp,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        if (watchState.status == EpisodeWatchStatus.IN_PROGRESS) {
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                LinearProgressIndicator(
                                                                    progress = { watchState.progressPct.coerceIn(0f, 1f) },
                                                                    modifier = Modifier
                                                                        .weight(1f)
                                                                        .height(4.dp)
                                                                        .clip(RoundedCornerShape(2.dp)),
                                                                    color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                                                    trackColor = Color.White.copy(alpha = 0.15f)
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Text(
                                                                    text = "${(watchState.progressPct * 100).toInt()}%",
                                                                    color = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold
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
                        }
                    }
                }

                // Modal flotante exclusivo para ver la sinopsis completa
                if (showFullSynopsisDialog) {
                    val fullOverview = seriesDetails?.overview?.ifBlank { "Sin sinopsis disponible." } ?: "Sin sinopsis disponible."
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showFullSynopsisDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .fillMaxHeight(0.80f)
                                .clip(RoundedCornerShape(20.dp))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp)
                            ) {
                                // Header: Title and Close button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = seriesTitle,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Sinopsis completa",
                                            color = parseHexColor(theme.accent),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }

                                    TvIconButton(
                                        onClick = { showFullSynopsisDialog = false },
                                        containerColor = Color.White.copy(alpha = 0.1f),
                                        shape = CircleShape,
                                        modifier = Modifier.size(32.dp)
                                    ) { isFocused ->
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cerrar",
                                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Scrollable Full Synopsis Content
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.25f))
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = fullOverview,
                                        color = Color(0xFFE5E7EB),
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                        modifier = Modifier.verticalScroll(rememberScrollState())
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Bottom Button: Volver
                                TvButton(
                                    onClick = { showFullSynopsisDialog = false },
                                    containerColor = parseHexColor(theme.accent),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) { isFocused ->
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Volver",
                                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Volver",
                                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

