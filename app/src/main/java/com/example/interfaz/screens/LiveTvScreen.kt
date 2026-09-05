package com.example.interfaz.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.example.Translator
import com.example.parseHexColor
import com.example.motor.db.*
import com.example.motor.config.*
import com.example.interfaz.AppThemeColors
import com.example.interfaz.MainViewModel
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.applySubtitleStyles

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LivePreviewPlayer(
    playerViewModel: PlayerViewModel,
    src: String,
    headers: Map<String, String> = emptyMap(),
    isFullScreenActive: Boolean = false,
    subtitleColor: String = "Blanco",
    subtitleBg: Boolean = true,
    subtitleSize: String = "Normal",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val exoPlayer = remember { playerViewModel.getExoPlayer(context) }

    DisposableEffect(currentLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    playerViewModel.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    playerViewModel.resume()
                }
                else -> {}
            }
        }
        currentLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            currentLifecycleOwner.lifecycle.removeObserver(observer)
            playerViewModel.stop()
        }
    }

    LaunchedEffect(src, headers, isFullScreenActive) {
        if (src.isNotEmpty() && !isFullScreenActive) {
            playerViewModel.playMedia(
                context = context,
                src = src,
                headers = headers
            )
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!isFullScreenActive) {
            key(isFullScreenActive) {
                AndroidView(
                    factory = { ctx ->
                        (android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.player_view_texture, null) as androidx.media3.ui.PlayerView).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            keepScreenOn = true
                            applySubtitleStyles(this, subtitleColor, subtitleBg, subtitleSize)
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { playerView ->
                        if (playerView.player != exoPlayer) {
                            playerView.player = exoPlayer
                        }
                        playerView.onResume()
                        playerView.keepScreenOn = true
                        applySubtitleStyles(playerView, subtitleColor, subtitleBg, subtitleSize)
                    },
                    onRelease = { playerView ->
                        playerView.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun getCountryAbbrAndCleanName(groupName: String): Pair<String, String> {
    val cleanGroup = groupName.trim()
    val regex = Regex("^(?i)([a-z]{2,4}|latino|castellano|canales|esp|spa|usa|uk|fra|ger|ita|por)\\s*[|:\\-➔➔➔]\\s*(.*)$")
    val match = regex.find(cleanGroup)
    if (match != null) {
        val countryRaw = match.groupValues[1].uppercase()
        val cleanedCat = match.groupValues[2].trim()
        val abbr = when (countryRaw) {
            "ES", "ESP", "SPA", "CASTELLANO" -> "ES"
            "LAT", "LATINO", "LATIN" -> "LAT"
            "US", "USA", "UK", "ENG" -> "US"
            "FR", "FRA" -> "FR"
            "DE", "GER" -> "DE"
            "IT", "ITA" -> "IT"
            "PT", "POR" -> "PT"
            else -> countryRaw
        }
        return Pair(abbr, cleanedCat)
    }
    
    val spainRegex = Regex("(?i)\\b(españa|spain|esp|es)\\b")
    if (spainRegex.containsMatchIn(cleanGroup)) {
        return Pair("ES", cleanGroup)
    }
    val latRegex = Regex("(?i)\\b(latino|latam|lat)\\b")
    if (latRegex.containsMatchIn(cleanGroup)) {
        return Pair("LAT", cleanGroup)
    }
    
    return Pair("Otros", cleanGroup)
}

@Composable
fun LiveTVScreen(
    viewModel: LiveTvViewModel,
    playerViewModel: PlayerViewModel,
    mainViewModel: MainViewModel? = null,
    onPlay: (src: String, title: String, drmScheme: String?, drmLicenseUrl: String?) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors,
    isFullScreenActive: Boolean = false
) {
    val liveChannelsMap by viewModel.liveChannels.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val epgData by viewModel.epgData.collectAsStateWithLifecycle()
    val epgLoaded by viewModel.epgLoaded.collectAsStateWithLifecycle()

    val favorites by viewModel.favoritesList.collectAsStateWithLifecycle()
    val tvFavorites = remember(favorites) { favorites.filter { it.type == "tv" } }
    val favoriteTvIds = remember(tvFavorites) {
        tvFavorites.flatMap { listOfNotNull(it.id, it.title) }.toSet()
    }
    val favoriteChannels = remember(tvFavorites) {
        tvFavorites.map { fav ->
            IptvItem(
                name = fav.title,
                group = "Favoritos",
                url = fav.url,
                logo = fav.logo,
                tvgId = fav.id
            )
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(300)
        debouncedSearchQuery = searchQuery
    }

    var selectedGroup by remember { mutableStateOf("") }
    val groups = remember(liveChannelsMap, settingsObj.hiddenTvCategories) {
        liveChannelsMap.keys
            .filter { it !in settingsObj.hiddenTvCategories }
            .sortedWith(compareBy({ 
                val (abbr, _) = getCountryAbbrAndCleanName(it)
                if (abbr == "Otros") "ZZZ" else abbr
            }, {
                val (_, cleanName) = getCountryAbbrAndCleanName(it)
                cleanName
            }))
    }

    LaunchedEffect(groups) {
        if ((selectedGroup.isEmpty() || selectedGroup !in groups) && groups.isNotEmpty()) {
            selectedGroup = groups.first()
        }
    }

    val activeChannels = remember(selectedGroup, debouncedSearchQuery, liveChannelsMap, favoriteChannels) {
        if (debouncedSearchQuery.isNotBlank()) {
            val query = debouncedSearchQuery
            val filtered = ArrayList<IptvItem>()
            for (list in liveChannelsMap.values) {
                for (item in list) {
                    if (item.name.contains(query, ignoreCase = true)) {
                        filtered.add(item)
                    }
                }
            }
            filtered
        } else if (selectedGroup == "★ Favoritos") {
            favoriteChannels
        } else {
            liveChannelsMap[selectedGroup] ?: emptyList()
        }
    }

    var selectedChannel by remember { mutableStateOf<IptvItem?>(null) }

    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var resolvedHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isResolvingUrl by remember { mutableStateOf(false) }

    val streamCache = remember { mutableMapOf<String, Pair<String, Map<String, String>>>() }

    // Resolve URL for selectedChannel with caching and background pre-loading of adjacent channels
    LaunchedEffect(selectedChannel) {
        resolvedUrl = null
        resolvedHeaders = emptyMap()
        isResolvingUrl = false
        val channel = selectedChannel ?: return@LaunchedEffect
        val cacheKey = channel.stalkerCmd ?: channel.url ?: channel.name

        if (streamCache.containsKey(cacheKey)) {
            val cached = streamCache[cacheKey]!!
            resolvedUrl = cached.first
            resolvedHeaders = cached.second
        } else {
            isResolvingUrl = true
            try {
                val (url, headers) = viewModel.resolveStream(channel)
                streamCache[cacheKey] = Pair(url, headers)
                resolvedUrl = url
                resolvedHeaders = headers
            } catch (e: Exception) {
                e.printStackTrace()
                val fallback = channel.url ?: ""
                val fallbackPair = Pair(fallback, emptyMap<String, String>())
                streamCache[cacheKey] = fallbackPair
                resolvedUrl = fallback
                resolvedHeaders = emptyMap()
            } finally {
                isResolvingUrl = false
            }
        }

        // Smart Channel Preloading: Pre-resolve the next adjacent channels in background
        val currIdx = activeChannels.indexOf(channel)
        if (currIdx != -1) {
            val nextChannels = listOfNotNull(
                activeChannels.getOrNull(currIdx + 1),
                activeChannels.getOrNull(currIdx + 2)
            )
            withContext(Dispatchers.IO) {
                for (nextCh in nextChannels) {
                    val nextKey = nextCh.stalkerCmd ?: nextCh.url ?: nextCh.name
                    if (!streamCache.containsKey(nextKey)) {
                        try {
                            val res = viewModel.resolveStream(nextCh)
                            streamCache[nextKey] = res
                        } catch (e: Exception) {
                            // ignore preload errors silently
                        }
                    }
                }
            }
        }
    }

    // Auto-select first channel of active list if selectedChannel is not in the active list
    LaunchedEffect(activeChannels) {
        if (activeChannels.isNotEmpty()) {
            if (selectedChannel == null || !activeChannels.contains(selectedChannel)) {
                selectedChannel = activeChannels.first()
            }
        } else {
            selectedChannel = null
        }
    }

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
                text = "Televisión",
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
                            text = "Buscar canal...",
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
            // COLUMN 1: Categories
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
                    // Virtual Favorites Category first
                    item {
                        val isSel = selectedGroup == "★ Favoritos" && searchQuery.isEmpty()
                        val count = favoriteChannels.size
                        var isFocused by remember { mutableStateOf(false) }
                        val bg = if (isFocused) TV_FOCUSED_BG else if (isSel) parseHexColor(theme.accent) else Color.Transparent
                        val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSel) Color.White else Color.LightGray

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) {
                                    searchQuery = ""
                                    selectedGroup = "★ Favoritos"
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = if (isFocused) TV_FOCUSED_TEXT else (if (isSel) Color.White else parseHexColor(theme.accentLight)),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Favoritos ($count)",
                                    color = tc,
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused || isSel) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    items(groups, key = { it }) { group ->
                        val isSel = group == selectedGroup && searchQuery.isEmpty()
                        val count = liveChannelsMap[group]?.size ?: 0
                        val (abbr, cleanCatName) = getCountryAbbrAndCleanName(group)
                        val displayName = if (abbr != "Otros" && abbr.isNotEmpty()) {
                            "[$abbr] $cleanCatName"
                        } else {
                            cleanCatName
                        }
                        var isFocused by remember { mutableStateOf(false) }
                        val bg = if (isFocused) TV_FOCUSED_BG else if (isSel) parseHexColor(theme.accent) else Color.Transparent
                        val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSel) Color.White else Color.LightGray
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) {
                                    searchQuery = "" // Reset search to show group channels
                                    selectedGroup = group
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "$displayName ($count)",
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

            // COLUMN 2: Channels
            Column(
                modifier = Modifier
                    .width(210.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty()) "Resultados de búsqueda" else if (selectedGroup == "★ Favoritos") "Favoritos" else "Canales",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                )
                if (activeChannels.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val emptyText = if (selectedGroup == "★ Favoritos") {
                            "No tienes favoritos todavía.\nUsa la estrella para añadir."
                        } else {
                            "No se encontraron canales"
                        }
                        Text(emptyText, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                    ) {
                        items(activeChannels, key = { it.url ?: (it.name + "_" + it.group) }) { channel ->
                            val isSel = channel == selectedChannel
                            val epgItem = findEpgForChannel(epgData, channel)
                            val currentProg = epgItem?.programmes?.find { p ->
                                val now = System.currentTimeMillis()
                                p.start <= now && p.stop >= now
                            }
                            var isFocused by remember { mutableStateOf(false) }
                            val bg = if (isFocused) TV_FOCUSED_BG
                                else if (isSel) parseHexColor(theme.accent).copy(alpha = 0.2f)
                                else parseHexColor(theme.surface)
                            val titleColor = if (isFocused) TV_FOCUSED_TEXT
                                else if (isSel) parseHexColor(theme.accentLight)
                                else Color.White
                            val subtitleColor = if (isFocused) TV_FOCUSED_TEXT
                                else Color.LightGray

                            val favId = channel.tvgId?.ifBlank { channel.name } ?: channel.name
                            val isChannelFav = favoriteTvIds.contains(favId) || favoriteTvIds.contains(channel.name)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .tvClickable(shape = RoundedCornerShape(8.dp), onFocusChange = { isFocused = it }) { selectedChannel = channel }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.05f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (channel.logo != null) {
                                        AsyncImage(
                                            model = channel.logo,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Tv,
                                            contentDescription = null,
                                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = channel.name,
                                        color = titleColor,
                                        fontSize = 12.sp,
                                        fontWeight = if (isFocused || isSel) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (currentProg != null) {
                                        Text(
                                            text = currentProg.title,
                                            color = subtitleColor,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        Text(
                                            text = "Sin información EPG",
                                            color = if (isFocused) TV_FOCUSED_TEXT else Color.Gray,
                                            fontSize = 9.sp
                                        )
                                    }
                                }

                                // Favorite Star Button in Channel Card (Bottom/Right)
                                Box(
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (isChannelFav) {
                                                viewModel.removeFavorite(favId)
                                                if (channel.tvgId != null && channel.tvgId != channel.name) {
                                                    viewModel.removeFavorite(channel.name)
                                                }
                                            } else {
                                                viewModel.addFavorite(
                                                    id = favId,
                                                    title = channel.name,
                                                    type = "tv",
                                                    url = channel.url ?: "",
                                                    logo = channel.logo
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isChannelFav) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isChannelFav) "Quitar de favoritos" else "Añadir a favoritos",
                                        tint = if (isChannelFav) Color(0xFFFFD700) else if (isFocused) TV_FOCUSED_TEXT.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
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

            // COLUMN 3: Live Preview Player & Info Panel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Reproductor Preview",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                )

                if (selectedChannel != null) {
                    val channel = selectedChannel!!
                    val epgItem = findEpgForChannel(epgData, channel)
                    val currentProg = epgItem?.programmes?.find { p ->
                        val now = System.currentTimeMillis()
                        p.start <= now && p.stop >= now
                    }

                    // 16:9 Video Player Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.77f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        if (resolvedUrl != null) {
                            LivePreviewPlayer(
                                playerViewModel = playerViewModel,
                                src = resolvedUrl!!,
                                headers = resolvedHeaders,
                                isFullScreenActive = isFullScreenActive,
                                subtitleColor = settingsObj.subtitleColor,
                                subtitleBg = settingsObj.subtitleBg,
                                subtitleSize = settingsObj.subtitleSize,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Clickable overlay to launch fullscreen on tap / remote OK
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .tvClickable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedBorderColor = Color.White,
                                        focusedBorderWidth = 3.dp,
                                        focusedBackgroundColor = Color.Transparent,
                                        unfocusedBackgroundColor = Color.Transparent
                                    ) {
                                        resolvedUrl?.let { onPlay(it, channel.name, channel.drmScheme, channel.drmLicenseUrl) }
                                    }
                            )
                        } else if (isResolvingUrl) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = parseHexColor(theme.accent))
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Canal no disponible", color = Color.Gray)
                            }
                        }
                    }

                    // Channel Information & EPG details
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(parseHexColor(theme.surface))
                            .padding(14.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.05f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (channel.logo != null) {
                                        AsyncImage(
                                            model = channel.logo,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(imageVector = Icons.Default.Tv, contentDescription = null, tint = Color.Gray)
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = channel.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = channel.group,
                                        color = parseHexColor(theme.accentLight),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            
                            var isFav by remember(channel.tvgId, channel.name) { mutableStateOf(false) }
                            LaunchedEffect(channel.tvgId, channel.name, favoriteTvIds) {
                                val id = channel.tvgId ?: channel.name
                                isFav = favoriteTvIds.contains(id) || favoriteTvIds.contains(channel.name) || viewModel.isFavorite(id)
                            }
                            
                            TvIconButton(
                                onClick = {
                                    val id = channel.tvgId ?: channel.name
                                    if (isFav) {
                                        viewModel.removeFavorite(id)
                                        if (channel.tvgId != null && channel.tvgId != channel.name) {
                                            viewModel.removeFavorite(channel.name)
                                        }
                                        isFav = false
                                    } else {
                                        viewModel.addFavorite(
                                            id = id,
                                            title = channel.name,
                                            type = "tv",
                                            url = channel.url ?: "",
                                            logo = channel.logo
                                        )
                                        isFav = true
                                    }
                                },
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "Favorito",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) parseHexColor(theme.accentLight) else Color.Gray),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.05f))
                        )

                        if (currentProg != null) {
                            Text(
                                text = "En Directo:",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentProg.title,
                                color = parseHexColor(theme.accentLight),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentProg.desc.isNotEmpty()) {
                                Text(
                                    text = currentProg.desc,
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = "No hay información de programación (EPG) disponible para este canal actualmente.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Fullscreen Play Button
                        TvButton(
                            onClick = { resolvedUrl?.let { onPlay(it, channel.name, channel.drmScheme, channel.drmLicenseUrl) } },
                            enabled = resolvedUrl != null,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = parseHexColor(theme.accent),
                            shape = RoundedCornerShape(8.dp)
                        ) { isFocused ->
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Ver a Pantalla Completa",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(parseHexColor(theme.surface)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = Icons.Default.Tv, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Selecciona un canal para reproducir", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun findEpgForChannel(epgData: Map<String, com.example.motor.db.EpgChannelData>, channel: IptvItem): com.example.motor.db.EpgChannelData? {
    if (epgData.isEmpty()) return null
    val byId = channel.tvgId?.takeIf { it.isNotBlank() }?.let { epgData[it] }
    if (byId != null) return byId
    val byName = epgData[channel.name] ?: epgData.values.find { it.name.equals(channel.name, ignoreCase = true) }
    if (byName != null) return byName
    val normChannelName = channel.name.lowercase(java.util.Locale.ROOT)
        .replace(Regex("(hd|fhd|4k|uhd|raw|1080p|720p|es:|sp:|uk:|us:|fr:|de:|it:|ro:|vip|plus)"), "")
        .replace(Regex("[^a-z0-9]"), "")
    if (normChannelName.isBlank()) return null
    return epgData.values.find { ch ->
        val normEpgName = ch.name.lowercase(java.util.Locale.ROOT)
            .replace(Regex("(hd|fhd|4k|uhd|raw|1080p|720p|es:|sp:|uk:|us:|fr:|de:|it:|ro:|vip|plus)"), "")
            .replace(Regex("[^a-z0-9]"), "")
        val normEpgId = ch.id.lowercase(java.util.Locale.ROOT)
            .replace(Regex("(hd|fhd|4k|uhd|raw|1080p|720p|es:|sp:|uk:|us:|fr:|de:|it:|ro:|vip|plus)"), "")
            .replace(Regex("[^a-z0-9]"), "")
        normEpgName == normChannelName || normEpgId == normChannelName
    }
}
