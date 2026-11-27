package com.example.interfaz.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.Translator
import com.example.interfaz.AppThemeColors
import com.example.interfaz.ParentalControl
import com.example.interfaz.ParentalPinDialog
import com.example.interfaz.components.LocalTvFocusAccent
import com.example.interfaz.components.LocalTvFocusAccentLight
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.rememberTvInitialFocusRequester
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.repository.IptvRepository
import com.example.parseHexColor
import kotlinx.coroutines.launch

private data class FavoriteFilterOption(
    val key: String,
    val label: String,
    val count: Int
)

@Composable
fun FavoritesScreen(
    viewModel: HomeViewModel,
    onPlay: (
        src: String,
        title: String,
        type: String,
        drmScheme: String?,
        drmLicenseUrl: String?,
        headers: Map<String, String>,
        categoryChannels: List<IptvItem>
    ) -> Unit,
    onOpenSeries: (IptvItem) -> Unit,
    onNavigateCatalog: (String) -> Unit = {},
    onBack: () -> Unit,
    theme: AppThemeColors,
    lang: String = "es",
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val favorites = uiState.favorites

    var selectedFilter by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var isParentalUnlocked by remember { mutableStateOf(false) }
    var pendingParentalFav by remember { mutableStateOf<FavoriteItem?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val initialFocusRequester = rememberTvInitialFocusRequester(delayMs = 120L)

    BackHandler {
        onBack()
    }

    val themeBg = remember(theme.bg) { parseHexColor(theme.bg) }
    val themeSurface = remember(theme.surface) { parseHexColor(theme.surface) }
    val themeCard = remember(theme.card) { parseHexColor(theme.card) }
    val themeAccent = remember(theme.accent) { parseHexColor(theme.accent) }
    val themeAccentLight = remember(theme.accentLight) { parseHexColor(theme.accentLight) }

    val backgroundBrush = remember(themeBg, themeSurface) {
        Brush.verticalGradient(
            colors = listOf(
                themeBg,
                themeSurface.copy(alpha = 0.88f),
                themeBg.copy(alpha = 0.98f)
            )
        )
    }

    val tvCount = remember(favorites) {
        favorites.count { IptvRepository.normalizeFavoriteType(it.type) == "tv" }
    }
    val movieCount = remember(favorites) {
        favorites.count { IptvRepository.normalizeFavoriteType(it.type) == "movie" }
    }
    val seriesCount = remember(favorites) {
        favorites.count { IptvRepository.normalizeFavoriteType(it.type) == "series" }
    }

    val filterOptions = remember(favorites.size, tvCount, movieCount, seriesCount, lang) {
        listOf(
            FavoriteFilterOption("all", Translator.translate("filter_all", lang), favorites.size),
            FavoriteFilterOption("tv", Translator.translate("filter_tv", lang), tvCount),
            FavoriteFilterOption("movie", Translator.translate("filter_movies", lang), movieCount),
            FavoriteFilterOption("series", Translator.translate("filter_series", lang), seriesCount)
        )
    }

    val filteredFavorites = remember(favorites, selectedFilter, searchQuery) {
        val cleanQuery = searchQuery.trim()
        favorites.filter { fav ->
            val normType = IptvRepository.normalizeFavoriteType(fav.type)
            val matchesType = selectedFilter == "all" || normType == selectedFilter
            val matchesQuery = cleanQuery.isEmpty() ||
                fav.title.contains(cleanQuery, ignoreCase = true) ||
                (fav.categoryGroup?.contains(cleanQuery, ignoreCase = true) == true)
            matchesType && matchesQuery
        }
    }

    fun launchFavorite(fav: FavoriteItem, bypassParentalCheck: Boolean = false) {
        val fallbackItem = IptvRepository.toFallbackIptvItem(fav)
        val isRestricted = ParentalControl.isChannelRestricted(fallbackItem, settingsObj)
        if (!bypassParentalCheck && settingsObj.parentalEnabled && isRestricted && !isParentalUnlocked) {
            pendingParentalFav = fav
            return
        }

        scope.launch {
            viewModel.setResolvingMovie(true)
            try {
                when (val res = viewModel.resolveFavorite(fav)) {
                    is HomeViewModel.FavoriteResolution.PlayStream -> {
                        val peers = if (res.type == "live") {
                            viewModel.getFavoriteTvChannelPeers(favorites)
                                .filter { peer ->
                                    !settingsObj.parentalEnabled ||
                                        isParentalUnlocked ||
                                        !ParentalControl.isChannelRestricted(peer, settingsObj)
                                }
                        } else {
                            emptyList()
                        }
                        onPlay(
                            res.url,
                            res.title,
                            res.type,
                            res.drmScheme,
                            res.drmLicenseUrl,
                            res.headers,
                            peers
                        )
                    }
                    is HomeViewModel.FavoriteResolution.OpenSeries -> {
                        onOpenSeries(res.seriesItem)
                    }
                    is HomeViewModel.FavoriteResolution.Error -> {
                        Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                viewModel.setResolvingMovie(false)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalTvFocusAccent provides themeAccent,
        LocalTvFocusAccentLight provides themeAccentLight
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header: Back Button + Title + Count Badge + Search Input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvIconButton(
                        onClick = onBack,
                        containerColor = Color.White.copy(alpha = 0.08f),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("favorites_back_button")
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = Translator.translate("back", lang),
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = themeAccentLight,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = Translator.translate("favorites", lang),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        color = themeAccent.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, themeAccent.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "${favorites.size}",
                            color = themeAccentLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .background(themeSurface, RoundedCornerShape(22.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = Translator.translate("search_favorites", lang),
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                cursorBrush = SolidColor(themeAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("favorites_search_input")
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            TvIconButton(
                                onClick = { searchQuery = "" },
                                containerColor = Color.Transparent,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("favorites_clear_search_button")
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = Translator.translate("close", lang),
                                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Filter Chips Row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    items(filterOptions, key = { it.key }) { option ->
                        val isSelected = selectedFilter == option.key
                        TvButton(
                            onClick = { selectedFilter = option.key },
                            focusRequester = if (option.key == "all") initialFocusRequester else null,
                            containerColor = if (isSelected) themeAccent else themeCard.copy(alpha = 0.65f),
                            focusedContainerColor = TV_FOCUSED_BG,
                            unfocusedBorderColor = if (isSelected) themeAccentLight else Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("favorites_filter_${option.key}")
                        ) { isFocused ->
                            Text(
                                text = "${option.label} (${option.count})",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Content Grid or Empty State
                if (filteredFavorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .widthIn(max = 460.dp)
                                .padding(16.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = themeCard.copy(alpha = 0.68f)
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(themeAccent.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = themeAccentLight,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                                Text(
                                    text = if (favorites.isEmpty()) {
                                        Translator.translate("favorites_empty_title", lang)
                                    } else {
                                        Translator.translate("favorites_filtered_empty", lang)
                                    },
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = Translator.translate("favorites_empty_desc", lang),
                                    color = Color.LightGray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                if (favorites.isEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TvButton(
                                            onClick = { onNavigateCatalog("live_tv") },
                                            containerColor = themeAccent,
                                            focusedContainerColor = TV_FOCUSED_BG,
                                            shape = RoundedCornerShape(22.dp),
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("favorites_explore_tv_button")
                                        ) { isFocused ->
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = null,
                                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = Translator.translate("live_tv", lang),
                                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        TvButton(
                                            onClick = { onNavigateCatalog("movies") },
                                            containerColor = themeSurface,
                                            focusedContainerColor = TV_FOCUSED_BG,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(22.dp),
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("favorites_explore_movies_button")
                                        ) { isFocused ->
                                            Icon(
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = null,
                                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = Translator.translate("movies", lang),
                                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        itemsIndexed(
                            items = filteredFavorites,
                            key = { index, fav -> "${fav.playlistId}_${fav.id}_$index" }
                        ) { index, fav ->
                            FavoriteGridCard(
                                fav = fav,
                                isParentalRestricted = settingsObj.parentalEnabled &&
                                    !isParentalUnlocked &&
                                    ParentalControl.isChannelRestricted(
                                        IptvRepository.toFallbackIptvItem(fav),
                                        settingsObj
                                    ),
                                index = index,
                                themeCard = themeCard,
                                themeAccent = themeAccent,
                                themeAccentLight = themeAccentLight,
                                lang = lang,
                                onPlayClick = { launchFavorite(fav) },
                                onRemoveClick = { viewModel.removeFavoriteById(fav.id) }
                            )
                        }
                    }
                }
            }

            if (uiState.isResolvingMovie) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = themeAccent)
                }
            }

            pendingParentalFav?.let { restrictedFav ->
                ParentalPinDialog(
                    channelName = restrictedFav.title,
                    expectedPin = settingsObj.parentalPin,
                    onDismiss = { pendingParentalFav = null },
                    onSuccess = {
                        isParentalUnlocked = true
                        val favToOpen = restrictedFav
                        pendingParentalFav = null
                        launchFavorite(favToOpen, bypassParentalCheck = true)
                    },
                    theme = theme
                )
            }
        }
    }
}

@Composable
private fun FavoriteGridCard(
    fav: FavoriteItem,
    isParentalRestricted: Boolean,
    index: Int,
    themeCard: Color,
    themeAccent: Color,
    themeAccentLight: Color,
    lang: String,
    onPlayClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val normType = remember(fav.type) { IptvRepository.normalizeFavoriteType(fav.type) }
    val typeBadgeLabel = remember(normType, lang) {
        when (normType) {
            "tv" -> Translator.translate("favorites_media_tv", lang)
            "series" -> Translator.translate("favorites_media_series", lang)
            else -> Translator.translate("favorites_media_movie", lang)
        }
    }
    val typeIcon = remember(normType) {
        when (normType) {
            "tv" -> Icons.Default.Tv
            "series" -> Icons.Default.VideoLibrary
            else -> Icons.Default.Movie
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvCardFocusable(
                shape = RoundedCornerShape(12.dp),
                unfocusedBackgroundColor = themeCard.copy(alpha = 0.62f),
                focusedBackgroundColor = TV_FOCUSED_BG,
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedBorderColor = themeAccent,
                onFocusChange = { isFocused = it },
                onClick = onPlayClick
            )
            .testTag("favorite_card_$index")
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.45f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center
        ) {
            if (!fav.logo.isNullOrBlank()) {
                AsyncImage(
                    model = fav.logo,
                    contentDescription = fav.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            } else {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.Gray,
                    modifier = Modifier.size(38.dp)
                )
            }

            // Type badge on top-left
            Surface(
                color = themeAccent.copy(alpha = 0.82f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                Text(
                    text = typeBadgeLabel,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Parental lock icon or play hint on bottom-left
            if (isParentalRestricted) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = themeAccentLight,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Remove favorite button (48dp minimum interactive touch target)
            TvIconButton(
                onClick = onRemoveClick,
                containerColor = Color.Black.copy(alpha = 0.68f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .minimumInteractiveComponentSize()
                    .size(34.dp)
                    .testTag("favorite_remove_button_$index")
            ) { isRemoveFocused ->
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = Translator.translate("remove_favorite", lang),
                    tint = if (isRemoveFocused) TV_FOCUSED_TEXT else Color(0xFFFF4D6D),
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = fav.title,
            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val categoryLabel = fav.categoryGroup?.takeIf { it.isNotBlank() }
        if (categoryLabel != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = categoryLabel,
                color = if (isFocused) TV_FOCUSED_TEXT.copy(alpha = 0.85f) else themeAccentLight,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
