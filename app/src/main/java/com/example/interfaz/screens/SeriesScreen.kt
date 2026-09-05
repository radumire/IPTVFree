package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import com.example.Translator
import com.example.parseHexColor
import com.example.motor.db.*
import com.example.motor.config.*
import com.example.motor.repository.MediaDetails
import com.example.interfaz.AppThemeColors
import com.example.interfaz.groupM3uEpisodes
import com.example.interfaz.viewmodel.SeriesViewModel
import com.example.interfaz.components.TmdbPosterImage
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEBUG_SERIES_SCREEN_LOG = false

@Composable
fun SeriesScreen(
    viewModel: SeriesViewModel,
    onPlay: (src: String, title: String, drmScheme: String?, drmLicenseUrl: String?) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors
) {
    val seriesMap by viewModel.series.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val precomputedData by viewModel.precomputedSeriesData.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(300)
        debouncedSearchQuery = searchQuery
    }

    var selectedGroup by remember { mutableStateOf("") }
    val favoritesList by viewModel.favoritesList.collectAsStateWithLifecycle()
    val activePl by viewModel.activePlaylist.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    var selectedSeriesItem by remember { mutableStateOf<IptvItem?>(null) }
    var seasonsList by remember { mutableStateOf<List<EpgChannelData>?>(null) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var selectedSeasonIndex by remember { mutableStateOf(0) }

    // Loading State for when background optimization is processing the massive dataset
    if (precomputedData == null && seriesMap.isNotEmpty()) {
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
                    text = "Optimizando base de datos de series...",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                val totalEp = remember(seriesMap) { seriesMap.values.sumOf { it.size } }
                Text(
                    text = "Procesando $totalEp episodios. Esto tomará solo un momento para que todo funcione de manera instantánea.",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 400.dp)
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

    val favoritesCount = remember(favoritesList) {
        favoritesList.count { it.type == "series" }
    }

    val favSeriesKeys = remember(favoritesList) {
        favoritesList.filter { it.type == "series" }.flatMap { listOfNotNull(it.id, it.url, it.title) }.toSet()
    }

    val categoryCounts = remember(precomputedData, favoritesCount) {
        val counts = precomputedData?.categoryCounts ?: emptyMap()
        counts.toMutableMap().apply {
            this["Favoritos"] = favoritesCount
        }
    }

    LaunchedEffect(groups) {
        if ((selectedGroup.isEmpty() || selectedGroup !in groups) && groups.isNotEmpty()) {
            selectedGroup = groups.first()
        }
    }

    // High performance search and group filtering that runs in <1ms!
    val activeSeriesUnique = remember(selectedGroup, debouncedSearchQuery, precomputedData, favoritesList) {
        val data = precomputedData ?: return@remember emptyList()
        if (debouncedSearchQuery.isNotBlank()) {
            data.allSeriesUnique.filter { it.name.contains(debouncedSearchQuery, ignoreCase = true) }
        } else {
            when (selectedGroup) {
                "Todos" -> data.allSeriesUnique
                "Favoritos" -> {
                    val favIds = favoritesList.filter { it.type == "series" }.map { it.id }.toSet()
                    data.allSeriesUnique.filter { favIds.contains(it.name) || (it.url != null && favIds.contains(it.url)) }
                }
                else -> data.groupToUniqueSeries[selectedGroup] ?: emptyList()
            }
        }
    }

    if (selectedSeriesItem == null) {
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
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                    ) {
                        items(groups.size, key = { idx -> groups[idx] }) { idx ->
                            val group = groups[idx]
                            val isSel = group == selectedGroup && searchQuery.isEmpty()
                            val count = categoryCounts[group] ?: 0
                            var isFocused by remember { mutableStateOf(false) }
                            val bg = if (isFocused) TV_FOCUSED_BG else if (isSel) parseHexColor(theme.accent) else Color.Transparent
                            val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSel) Color.White else Color.LightGray

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) {
                                        searchQuery = "" // Reset search to show group content
                                        selectedGroup = group
                                    }
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
                                val cardBg = if (isFocused) TV_FOCUSED_BG else parseHexColor(theme.surface)
                                val titleColor = if (isFocused) TV_FOCUSED_TEXT else Color.White
                                val subtitleColor = if (isFocused) TV_FOCUSED_TEXT else Color.Gray

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(cardBg)
                                        .tvClickable(shape = RoundedCornerShape(10.dp), onFocusChange = { isFocused = it }) {
                                            val pl = activePl
                                            val activePlType = pl?.type ?: "null (m3u/default)"
                                            val cacheKey = if (pl != null) {
                                                "${pl.id}_${item.seriesId ?: item.name}"
                                            } else {
                                                "m3u_${item.name}"
                                            }

                                            android.util.Log.d("M3U_FLOW_2", """
                                                === M3U_FLOW_2 ===
                                                Nombre de la serie pulsada: ${item.name}
                                                Número de elementos disponibles en la colección de series: ${allSeries.size}
                                                Tipo de playlist: $activePlType
                                                cacheKey: $cacheKey
                                            """.trimIndent())

                                            selectedSeriesItem = item
                                            selectedSeasonIndex = 0
                                            val isRemoteApi = pl != null && (pl.type.lowercase() in listOf("xtream", "m3u", "stalker", "mac"))
                                            val cached = if (isRemoteApi) viewModel.getCachedEpisodes(cacheKey) else null

                                            if (cached != null) {
                                                if (DEBUG_SERIES_SCREEN_LOG) {
                                                    val seasonsBreakdown = cached.joinToString("\n") { season ->
                                                        "Temporada ${season.name} (ID: ${season.id}): ${season.programmes.size} episodios"
                                                    }
                                                    android.util.Log.d("CACHE_DIAGNOSTIC", """
                                                        === CACHE HIT ===
                                                        Serie: ${item.name}
                                                        cacheKey: $cacheKey
                                                        activePl.type: $activePlType
                                                        getCachedEpisodes: DATOS EXISTENTES
                                                        Total temporadas en caché: ${cached.size}
                                                        Episodios por temporada:
                                                        $seasonsBreakdown
                                                        Estado: groupM3uEpisodes() se está OMITIENDO (usando datos de caché).
                                                    """.trimIndent())
                                                }
                                                seasonsList = cached
                                            } else {
                                                if (DEBUG_SERIES_SCREEN_LOG) {
                                                    android.util.Log.d("CACHE_DIAGNOSTIC", """
                                                        === CACHE MISS ===
                                                        Serie: ${item.name}
                                                        cacheKey: $cacheKey
                                                        activePl.type: $activePlType
                                                        getCachedEpisodes: null
                                                        Estado: Se va a ejecutar groupM3uEpisodes() / fetch de episodios.
                                                    """.trimIndent())
                                                }
                                                seasonsList = null
                                                scope.launch {
                                                    isLoadingEpisodes = true
                                                    try {
                                                        val result = viewModel.fetchSeriesEpisodes(item, allSeries, precomputedData?.seriesBaseNameCache)
                                                        seasonsList = result
                                                        viewModel.cacheEpisodes(cacheKey, result)
                                                    } catch (e: Exception) {
                                                        val fallback = withContext(Dispatchers.Default) {
                                                            groupM3uEpisodes(item, allSeries, precomputedData?.seriesBaseNameCache)
                                                        }
                                                        seasonsList = fallback
                                                        viewModel.cacheEpisodes(cacheKey, fallback)
                                                    } finally {
                                                        isLoadingEpisodes = false
                                                    }
                                                }
                                            }
                                        }
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
                                            }
                                        )

                                        val isFav = item.name in favSeriesKeys || (item.url != null && item.url in favSeriesKeys)
                                        TvIconButton(
                                            onClick = {
                                                if (isFav) {
                                                    viewModel.removeFavorite(item.name)
                                                    item.url?.let { viewModel.removeFavorite(it) }
                                                } else {
                                                    viewModel.addFavorite(
                                                        id = item.name,
                                                        title = item.name,
                                                        type = "series",
                                                        url = item.url ?: "",
                                                        logo = item.logo
                                                    )
                                                }
                                            },
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
    } else {
        val series = selectedSeriesItem!!
        val cleanedInfo = remember(series.name) {
            viewModel.cleanTitleAndExtractYear(series.name)
        }
        val seriesTitle = cleanedInfo.title.ifBlank { series.name }

        var seriesDetails by remember(series.name) { mutableStateOf<MediaDetails?>(null) }
        var isDetailsLoading by remember(series.name) { mutableStateOf(true) }
        var showFullSynopsisDialog by remember(series.name) { mutableStateOf(false) }

        LaunchedEffect(series.name, activePl?.id) {
            isDetailsLoading = true
            try {
                seriesDetails = viewModel.fetchXtreamMediaDetails(
                    item = series,
                    activePlaylist = activePl,
                    isSeries = true
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDetailsLoading = false
            }
        }

        val isFav = favoritesList.any { it.id == series.name || it.id == series.seriesId }
        val firstSeasonFocusRequester = remember { FocusRequester() }

        BackHandler(enabled = true) {
            if (showFullSynopsisDialog) {
                showFullSynopsisDialog = false
            } else {
                selectedSeriesItem = null
            }
        }

        LaunchedEffect(seasonsList) {
            if (!seasonsList.isNullOrEmpty()) {
                kotlinx.coroutines.delay(100)
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
                        onClick = { selectedSeriesItem = null },
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
                                    val firstEp = firstSeason?.programmes?.firstOrNull()

                                     if (firstEp != null) {
                                        TvButton(
                                            onClick = {
                                                val finalDrmScheme = selectedSeriesItem?.drmScheme
                                                val finalDrmLicenseUrl = selectedSeriesItem?.drmLicenseUrl
                                                val pl = activePl
                                                if (pl != null && pl.type.lowercase() in listOf("stalker", "mac")) {
                                                    scope.launch {
                                                        isLoadingEpisodes = true
                                                        try {
                                                            val resolved = viewModel.resolveStalkerPlayUrl(firstEp.category, "series")
                                                            onPlay(resolved, "$seriesTitle - ${firstSeason.name} - ${firstEp.title}", finalDrmScheme, finalDrmLicenseUrl)
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        } finally {
                                                            isLoadingEpisodes = false
                                                        }
                                                    }
                                                } else {
                                                    val episodeUrl = viewModel.resolveEpisodeUrl(firstEp.category, pl)
                                                    onPlay(episodeUrl, "$seriesTitle - ${firstSeason.name} - ${firstEp.title}", finalDrmScheme, finalDrmLicenseUrl)
                                                }
                                            },
                                            containerColor = parseHexColor(theme.accent),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.height(32.dp)
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
                                        onClick = {
                                            if (isFav) {
                                                viewModel.removeFavorite(series.name)
                                                series.seriesId?.let { viewModel.removeFavorite(it) }
                                            } else {
                                                viewModel.addFavorite(
                                                    id = series.seriesId ?: series.name,
                                                    title = series.name,
                                                    type = "series",
                                                    url = series.url ?: "",
                                                    logo = series.logo
                                                )
                                            }
                                        },
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
                                        onClick = { selectedSeriesItem = null },
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
                                            val itemModifier = if (idx == 0) Modifier.focusRequester(firstSeasonFocusRequester) else Modifier
                                            var isFocused by remember { mutableStateOf(false) }
                                            val bg = if (isFocused) TV_FOCUSED_BG else if (isSel) parseHexColor(theme.accent) else Color.White.copy(alpha = 0.05f)
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
                                        items(currentSeason.programmes.size) { idx ->
                                            val episode = currentSeason.programmes[idx]
                                            var isFocused by remember { mutableStateOf(false) }
                                            val cardBg = if (isFocused) TV_FOCUSED_BG else Color.White.copy(alpha = 0.05f)
                                            val titleColor = if (isFocused) TV_FOCUSED_TEXT else Color.White
                                            val descColor = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray
                                            val iconColor = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent)

                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) {
                                                        val finalDrmScheme = selectedSeriesItem?.drmScheme
                                                        val finalDrmLicenseUrl = selectedSeriesItem?.drmLicenseUrl
                                                        val pl = activePl
                                                        
                                                        if (pl != null && pl.type.lowercase() in listOf("stalker", "mac")) {
                                                            scope.launch {
                                                                isLoadingEpisodes = true
                                                                try {
                                                                    val resolved = viewModel.resolveStalkerPlayUrl(episode.category, "series")
                                                                    // selectedSeriesItem = null
                                                                    onPlay(resolved, "${seriesTitle} - ${currentSeason.name} - ${episode.title}", finalDrmScheme, finalDrmLicenseUrl)
                                                                } catch (e: Exception) {
                                                                    e.printStackTrace()
                                                                } finally {
                                                                    isLoadingEpisodes = false
                                                                }
                                                            }
                                                        } else {
                                                            val episodeUrl = viewModel.resolveEpisodeUrl(episode.category, pl)
                                                            // selectedSeriesItem = null
                                                            onPlay(episodeUrl, "${seriesTitle} - ${currentSeason.name} - ${episode.title}", finalDrmScheme, finalDrmLicenseUrl)
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
                                                        Text(
                                                            text = episode.title,
                                                            color = titleColor,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
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

