package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.example.interfaz.getFailedChannelColors
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.rememberTvInitialFocusRequester
import androidx.compose.ui.focus.FocusRequester
import com.example.interfaz.components.applySubtitleStyles
import com.example.interfaz.ParentalControl
import com.example.interfaz.ParentalPinDialog

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
                headers = headers,
                isLive = true
            )
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        key(src) {
            AndroidView(
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.player_view_texture, null) as androidx.media3.ui.PlayerView).apply {
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        applySubtitleStyles(this, subtitleColor, subtitleBg, subtitleSize)
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        if (!isFullScreenActive) {
                            playerViewModel.attachPlayerView(this)
                        }
                    }
                },
                update = { playerView ->
                    if (isFullScreenActive) {
                        playerViewModel.detachPlayerView(playerView)
                    } else {
                        playerViewModel.attachPlayerView(playerView)
                    }
                    playerView.onResume()
                    playerView.keepScreenOn = true
                    applySubtitleStyles(playerView, subtitleColor, subtitleBg, subtitleSize)
                },
                onRelease = { playerView ->
                    playerViewModel.detachPlayerView(playerView)
                },
                modifier = Modifier.fillMaxSize()
            )
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
    onPlay: (
        src: String,
        title: String,
        drmScheme: String?,
        drmLicenseUrl: String?,
        categoryName: String,
        categoryChannels: List<IptvItem>
    ) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors,
    isFullScreenActive: Boolean = false
) {
    val liveChannelsMap by viewModel.liveChannels.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val failedChannelKeys by viewModel.failedChannelKeys.collectAsStateWithLifecycle()
    val checkProgress by viewModel.channelCheckProgress.collectAsStateWithLifecycle()
    var showProgressModal by remember { mutableStateOf(false) }

    val failedColors = remember(theme) { theme.getFailedChannelColors() }
    val isParentalUnlocked by viewModel.isParentalUnlocked.collectAsStateWithLifecycle()
    var showPinDialogForChannel by remember { mutableStateOf<IptvItem?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelChannelCheck()
            viewModel.lockParentalSession()
        }
    }

    // Capture back button to return to Home
    BackHandler {
        onBack()
    }

    val favorites by viewModel.favoritesList.collectAsStateWithLifecycle()
    val tvFavorites = remember(favorites) { favorites.filter { it.type == "tv" } }
    val favoriteTvIds = remember(tvFavorites) {
        tvFavorites.flatMap { listOfNotNull(it.id, it.title, it.url) }.toSet()
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

    val activeChannels = remember(selectedGroup, debouncedSearchQuery, liveChannelsMap, favoriteChannels, settingsObj.hiddenTvCategories) {
        val hidden = settingsObj.hiddenTvCategories
        if (debouncedSearchQuery.isNotBlank()) {
            val query = debouncedSearchQuery
            val filtered = ArrayList<IptvItem>()
            for ((groupName, list) in liveChannelsMap) {
                if (groupName in hidden) continue
                for (item in list) {
                    if (item.name.contains(query, ignoreCase = true) && item.group !in hidden) {
                        filtered.add(item)
                    }
                }
            }
            filtered
        } else if (selectedGroup == "★ Favoritos") {
            favoriteChannels.filter { it.group !in hidden }
        } else {
            if (selectedGroup in hidden) emptyList() else liveChannelsMap[selectedGroup] ?: emptyList()
        }
    }

    var selectedChannel by remember { mutableStateOf<IptvItem?>(null) }

    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var resolvedHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isResolvingUrl by remember { mutableStateOf(false) }

    val streamCache = remember { mutableMapOf<String, Pair<String, Map<String, String>>>() }

    // Resolve URL for selectedChannel with caching and background pre-loading of adjacent channels
    LaunchedEffect(selectedChannel, isParentalUnlocked) {
        resolvedUrl = null
        resolvedHeaders = emptyMap()
        isResolvingUrl = false
        val channel = selectedChannel ?: return@LaunchedEffect

        val isRestricted = ParentalControl.isChannelRestricted(channel, settingsObj)
        val isLocked = settingsObj.parentalEnabled && isRestricted && !isParentalUnlocked

        ParentalControl.logParental(
            enabled = settingsObj.parentalEnabled,
            channel = channel.name,
            restricted = isRestricted,
            pinRequired = isLocked,
            pinSuccess = !isLocked
        )

        if (isLocked) {
            return@LaunchedEffect
        }

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
            ).filter { !settingsObj.parentalEnabled || !ParentalControl.isChannelRestricted(it, settingsObj) || isParentalUnlocked }
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

    val initialFocusRequester = rememberTvInitialFocusRequester()

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

            Spacer(modifier = Modifier.width(10.dp))

            TvButton(
                onClick = {
                    showProgressModal = true
                    viewModel.startChannelCheck(settingsObj.hiddenTvCategories)
                },
                modifier = Modifier.height(48.dp),
                containerColor = parseHexColor(theme.card).copy(alpha = 0.60f),
                focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) { isFocused ->
                Text(
                    text = "SOLO ONLINE",
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
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
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    // Virtual Favorites Category first
                    item {
                        val isSel = selectedGroup == "★ Favoritos" && searchQuery.isEmpty()
                        val count = favoriteChannels.size
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
                                    focusRequester = initialFocusRequester,
                                    onFocusChange = { isFocused = it },
                                    onClick = {
                                        searchQuery = ""
                                        selectedGroup = "★ Favoritos"
                                    }
                                )
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
                                    onFocusChange = { isFocused = it },
                                    onClick = {
                                        searchQuery = "" // Reset search to show group channels
                                        selectedGroup = group
                                    }
                                )
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
                            val channelKey = remember(channel) { LiveTvViewModel.getChannelCheckKey(channel) }
                            val isFailed = failedChannelKeys.contains(channelKey)

                            var isFocused by remember { mutableStateOf(false) }
                            val bg = when {
                                isFocused -> parseHexColor(theme.cardHover)
                                isFailed -> failedColors.bg
                                isSel -> parseHexColor(theme.accent).copy(alpha = 0.35f)
                                else -> parseHexColor(theme.surface)
                            }
                            val borderColor = when {
                                isFocused -> parseHexColor(theme.accent)
                                isFailed -> failedColors.border
                                isSel -> parseHexColor(theme.accentLight).copy(alpha = 0.8f)
                                else -> Color.White.copy(alpha = 0.12f)
                            }
                            val titleColor = when {
                                isFocused -> TV_FOCUSED_TEXT
                                isFailed -> failedColors.title
                                isSel -> parseHexColor(theme.accentLight)
                                else -> Color.White
                            }
                            val subtitleColor = when {
                                isFocused -> TV_FOCUSED_TEXT
                                isFailed -> failedColors.subtitle
                                else -> Color.LightGray
                            }

                            val favId = channel.tvgId?.ifBlank { channel.name } ?: channel.name
                            val isChannelFav = favoriteTvIds.contains(favId) || favoriteTvIds.contains(channel.name) || (channel.url != null && favoriteTvIds.contains(channel.url))
                            val idleBg = when {
                                isFailed -> failedColors.bg
                                isSel -> parseHexColor(theme.accent).copy(alpha = 0.35f)
                                else -> parseHexColor(theme.card).copy(alpha = 0.55f)
                            }
                            val idleBorder = when {
                                isFailed -> failedColors.border
                                isSel -> parseHexColor(theme.accentLight).copy(alpha = 0.8f)
                                else -> Color.White.copy(alpha = 0.12f)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvCardFocusable(
                                        shape = RoundedCornerShape(8.dp),
                                        unfocusedBackgroundColor = idleBg,
                                        focusedBackgroundColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                                        unfocusedBorderColor = idleBorder,
                                        focusedBorderColor = parseHexColor(theme.accent),
                                        onFocusChange = { isFocused = it },
                                        onClick = {
                                            if (settingsObj.parentalEnabled && ParentalControl.isChannelRestricted(channel, settingsObj) && !isParentalUnlocked) {
                                                selectedChannel = channel
                                                showPinDialogForChannel = channel
                                            } else {
                                                selectedChannel = channel
                                            }
                                        }
                                    )
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = channel.name,
                                            color = titleColor,
                                            fontSize = 12.sp,
                                            fontWeight = if (isFocused || isSel) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (settingsObj.parentalEnabled && ParentalControl.isChannelRestricted(channel, settingsObj) && !isParentalUnlocked) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Protegido",
                                                tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = channel.group.ifBlank { "General" },
                                        color = subtitleColor,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Favorite Star Button in Channel Card (Bottom/Right)
                                TvIconButton(
                                    onClick = {
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
                                    containerColor = Color.Transparent,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(32.dp)
                                ) { isFavBtnFocused ->
                                    Icon(
                                        imageVector = if (isChannelFav) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isChannelFav) "Quitar de favoritos" else "Añadir a favoritos",
                                        tint = if (isFavBtnFocused) TV_FOCUSED_TEXT else (if (isChannelFav) Color(0xFFFFD700) else Color.White.copy(alpha = 0.35f)),
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

                    val isChannelLocked = settingsObj.parentalEnabled &&
                            ParentalControl.isChannelRestricted(channel, settingsObj) &&
                            !isParentalUnlocked

                    // 16:9 Video Player Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.77f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        if (isChannelLocked) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Bloqueado",
                                        tint = parseHexColor(theme.accent),
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Canal Protegido por Control Parental",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Pulsa OK para introducir el PIN",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            // Clickable overlay on locked preview
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .tvClickable(
                                        shape = RoundedCornerShape(12.dp),
                                        focusedBorderColor = null,
                                        focusedBorderWidth = 2.5.dp,
                                        focusedBackgroundColor = Color.Transparent,
                                        unfocusedBackgroundColor = Color.Transparent,
                                        scaleOnFocus = false
                                    ) {
                                        showPinDialogForChannel = channel
                                    }
                            )
                        } else if (resolvedUrl != null) {
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
                                        focusedBorderColor = null,
                                        focusedBorderWidth = 2.5.dp,
                                        focusedBackgroundColor = Color.Transparent,
                                        unfocusedBackgroundColor = Color.Transparent,
                                        scaleOnFocus = false
                                    ) {
                                        resolvedUrl?.let { url ->
                                            val catChannels = if (selectedGroup == "★ Favoritos") {
                                                favoriteChannels
                                            } else {
                                                liveChannelsMap[selectedGroup] ?: emptyList()
                                            }
                                            onPlay(
                                                url,
                                                channel.name,
                                                channel.drmScheme,
                                                channel.drmLicenseUrl,
                                                selectedGroup.ifBlank { "Canales" },
                                                catChannels
                                            )
                                        }
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

                    // Channel Information
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

                        Text(
                            text = "Categoría: ${channel.group.ifBlank { "General" }}",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // Fullscreen Play Button
                        TvButton(
                            onClick = {
                                resolvedUrl?.let { url ->
                                    val catChannels = if (selectedGroup == "★ Favoritos") {
                                        favoriteChannels
                                    } else {
                                        liveChannelsMap[selectedGroup] ?: emptyList()
                                    }
                                    onPlay(
                                        url,
                                        channel.name,
                                        channel.drmScheme,
                                        channel.drmLicenseUrl,
                                        selectedGroup.ifBlank { "Canales" },
                                        catChannels
                                    )
                                }
                            },
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

    if (showProgressModal) {
        ChannelCheckProgressModal(
            progress = checkProgress,
            theme = theme,
            onStop = {
                viewModel.cancelChannelCheck()
            },
            onDismiss = {
                showProgressModal = false
            }
        )
    }

    if (showPinDialogForChannel != null) {
        val ch = showPinDialogForChannel!!
        ParentalPinDialog(
            channelName = ch.name,
            expectedPin = settingsObj.parentalPin,
            onDismiss = { showPinDialogForChannel = null },
            onSuccess = {
                viewModel.unlockParentalSession()
                selectedChannel = ch
                showPinDialogForChannel = null
            },
            theme = theme
        )
    }
}

@Composable
private fun ChannelCheckProgressModal(
    progress: LiveTvViewModel.ChannelCheckProgress,
    theme: AppThemeColors,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = rememberTvInitialFocusRequester(100L)
    val accentColor = parseHexColor(theme.accent)
    val accentLightColor = parseHexColor(theme.accentLight)

    Dialog(
        onDismissRequest = {
            if (!progress.isRunning) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(min = 440.dp, max = 560.dp)
                    .padding(24.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = parseHexColor(theme.card)
                ),
                border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Status Icon
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (progress.isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = accentLightColor,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = accentLightColor,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Title
                    Text(
                        text = if (progress.isRunning) "Verificando canales..." else "Comprobación completada",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Real-time counter text: "Comprobados X / Y · Z online"
                    val counterText = if (progress.total > 0) {
                        "Comprobados ${progress.checked} / ${progress.total} · ${progress.online} online"
                    } else {
                        "Iniciando comprobación..."
                    }
                    Text(
                        text = counterText,
                        color = Color(0xFFD1D5DB),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    if (progress.failed > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${progress.failed} no disponibles",
                            color = Color(0xFFFCA5A5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Progress Bar
                    val progressFraction = if (progress.total > 0) {
                        (progress.checked.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor)
                        )
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    // Action Button (PARAR during check, CERRAR when completed)
                    if (progress.isRunning) {
                        TvButton(
                            onClick = {
                                onStop()
                                onDismiss()
                            },
                            focusRequester = initialFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(46.dp),
                            containerColor = Color(0xFF374151),
                            contentColor = Color.White,
                            shape = RoundedCornerShape(23.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                        ) { isFocused ->
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PARAR",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        TvButton(
                            onClick = {
                                onDismiss()
                            },
                            focusRequester = initialFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(46.dp),
                            containerColor = accentColor,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(23.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                        ) { isFocused ->
                            Text(
                                text = "CERRAR",
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

