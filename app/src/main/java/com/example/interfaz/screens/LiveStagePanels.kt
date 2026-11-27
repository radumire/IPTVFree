package com.example.interfaz.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.Translator
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.tvCardFocusable
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.repository.IptvRepository
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus

@Composable
internal fun LiveStageTvPanel(
    channels: List<IptvItem>,
    selectedChannel: IptvItem?,
    isChannelLocked: (IptvItem) -> Boolean,
    isResolvingPreview: Boolean,
    hasPreviewStream: Boolean,
    emptyText: String,
    lang: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    onSelectChannel: (IptvItem) -> Unit,
    onOpenFullscreen: () -> Unit,
    videoPreviewSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val selectedIndex = remember(channels, selectedChannel) {
        if (selectedChannel == null) -1 else channels.indexOf(selectedChannel)
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && selectedIndex < channels.size) {
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (selectedIndex !in visibleIndices) {
                listState.animateScrollToItem(selectedIndex)
            }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // LEFT: Full scrollable channel list for the active Spanish/Romanian/First category
        Column(
            modifier = Modifier
                .weight(0.36f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(themeSurface.copy(alpha = 0.72f))
                .border(1.dp, themeAccent.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = themeAccentLight,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = Translator.translate("channel_list", lang),
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (channels.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(themeAccent.copy(alpha = 0.25f))
                            .border(0.8.dp, themeAccentLight.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = channels.size.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (channels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyText,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("livestage_channel_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { idx, ch -> "${idx}_${ch.stalkerCmd ?: ""}_${ch.url ?: ""}_${ch.name}" }
                    ) { idx, ch ->
                        val isActive = selectedChannel == ch
                        val locked = isChannelLocked(ch)
                        var isFocused by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("livestage_channel_item_$idx")
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(9.dp),
                                    scaleFocused = 1.02f,
                                    elevationFocused = 4.dp,
                                    unfocusedBackgroundColor = if (isActive) {
                                        themeAccent.copy(alpha = 0.26f)
                                    } else {
                                        Color.White.copy(alpha = 0.04f)
                                    },
                                    focusedBackgroundColor = TV_FOCUSED_BG,
                                    unfocusedBorderColor = if (isActive) {
                                        themeAccentLight.copy(alpha = 0.85f)
                                    } else {
                                        Color.White.copy(alpha = 0.10f)
                                    },
                                    focusedBorderColor = themeAccentLight,
                                    focusedBorderWidth = 1.8.dp,
                                    onFocusChange = { isFocused = it },
                                    onClick = { onSelectChannel(ch) }
                                )
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${idx + 1}",
                                color = if (isActive || isFocused) themeAccentLight else Color.White.copy(alpha = 0.55f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(24.dp)
                            )

                            if (!ch.logo.isNullOrBlank()) {
                                AsyncImage(
                                    model = ch.logo,
                                    contentDescription = ch.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(themeAccent.copy(alpha = 0.30f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Text(
                                text = ch.name,
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White.copy(alpha = if (isActive) 1f else 0.9f),
                                fontSize = 12.5.sp,
                                fontWeight = if (isActive || isFocused) FontWeight.Bold else FontWeight.Medium,
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
                            } else if (isActive) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = themeAccentLight,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // RIGHT: Live video preview box + active channel footer bar (click/OK opens fullscreen with Channels button)
        var isPreviewFocused by remember { mutableStateOf(false) }
        val activeLocked = selectedChannel != null && isChannelLocked(selectedChannel)

        Column(
            modifier = Modifier
                .weight(0.64f)
                .fillMaxHeight()
                .testTag("livestage_preview_box")
                .tvCardFocusable(
                    shape = RoundedCornerShape(14.dp),
                    scaleFocused = 1.012f,
                    elevationFocused = 8.dp,
                    unfocusedBackgroundColor = themeCard.copy(alpha = 0.82f),
                    focusedBackgroundColor = TV_FOCUSED_BG,
                    unfocusedBorderColor = themeAccent.copy(alpha = 0.42f),
                    focusedBorderColor = themeAccentLight,
                    focusedBorderWidth = 2.dp,
                    onFocusChange = { isPreviewFocused = it },
                    onClick = onOpenFullscreen
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when {
                    selectedChannel == null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = themeAccentLight.copy(alpha = 0.65f),
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = emptyText,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 13.sp
                            )
                        }
                    }
                    activeLocked -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = themeAccentLight,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = selectedChannel.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    hasPreviewStream -> {
                        videoPreviewSlot()
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isResolvingPreview) {
                                CircularProgressIndicator(
                                    color = themeAccentLight,
                                    modifier = Modifier.size(34.dp),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.LiveTv,
                                    contentDescription = null,
                                    tint = themeAccentLight.copy(alpha = 0.7f),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                            Text(
                                text = selectedChannel.name,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Bottom channel caption & fullscreen cue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedChannel?.name ?: Translator.translate("live_tv", lang),
                    color = if (isPreviewFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (selectedChannel != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(themeAccent.copy(alpha = if (isPreviewFocused) 0.45f else 0.25f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = Translator.translate("tap_for_fullscreen", lang),
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = Translator.translate("tap_for_fullscreen", lang),
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveStageSportsRow(
    events: List<SportsEvent>,
    lang: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    onMatchClick: (SportsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val liveLabel = Translator.translate("live_now", lang)
    if (events.isEmpty()) {
        LiveStageEmptyState(
            icon = Icons.Default.SportsSoccer,
            message = Translator.translate("no_sports_today", lang),
            themeAccentLight = themeAccentLight,
            themeSurface = themeSurface,
            modifier = modifier
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val cardWidth = (maxWidth * 0.27f).coerceIn(220.dp, 285.dp)
        val cardHeight = (maxHeight - 8.dp).coerceIn(130.dp, 210.dp)

        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .testTag("livestage_sports_list"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = events,
                key = { idx, ev -> "${ev.id}_$idx" }
            ) { idx, event ->
                val isLive = event.isLive || event.status == SportsEventStatus.LIVE
                val matchTitle = if (event.homeTeam.isNotBlank() && event.awayTeam.isNotBlank()) {
                    "${event.homeTeam} vs ${event.awayTeam}"
                } else {
                    event.league.ifBlank { Translator.translate("sports", lang) }
                }
                val leagueColor = remember(themeAccentLight) {
                    lerp(themeAccentLight, Color.White, 0.35f)
                }
                var isFocused by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .testTag("livestage_sports_item_$idx")
                        .tvCardFocusable(
                            shape = RoundedCornerShape(14.dp),
                            scaleFocused = 1.03f,
                            elevationFocused = 8.dp,
                            unfocusedBackgroundColor = themeSurface.copy(alpha = 0.80f),
                            focusedBackgroundColor = TV_FOCUSED_BG,
                            unfocusedBorderColor = if (isLive) {
                                Color(0xFFEF4444).copy(alpha = 0.65f)
                            } else {
                                Color.White.copy(alpha = 0.14f)
                            },
                            focusedBorderColor = themeAccentLight,
                            focusedBorderWidth = 2.dp,
                            onFocusChange = { isFocused = it },
                            onClick = { onMatchClick(event) }
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = event.league.ifBlank { Translator.translate("sports", lang) },
                            color = leagueColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        if (isLive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFFEF4444))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = liveLabel,
                                    color = Color.White,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        } else {
                            val formattedTime = event.getFormattedTime()
                            if (formattedTime.isNotBlank()) {
                                Text(
                                    text = formattedTime,
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LiveStageCrest(event.homeTeamLogo, event.homeTeam, size = 38.dp)
                        Text(
                            text = if (isLive && event.homeScore != null && event.awayScore != null) {
                                "${event.homeScore} : ${event.awayScore}"
                            } else {
                                "VS"
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        LiveStageCrest(event.awayTeamLogo, event.awayTeam, size = 38.dp)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = matchTitle,
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val broadcasterText = event.broadcaster
                        if (!broadcasterText.isNullOrBlank()) {
                            Text(
                                text = broadcasterText,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 10.sp,
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

@Composable
private fun LiveStageCrest(
    logoUrl: String?,
    teamName: String,
    size: Dp
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
                .padding(4.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(0.8.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = teamName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun LiveStageMediaPosterRow(
    items: List<IptvItem>,
    emptyMessage: String,
    emptyIcon: ImageVector,
    badgeFallback: String,
    testTagPrefix: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    onItemClick: (IptvItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        LiveStageEmptyState(
            icon = emptyIcon,
            message = emptyMessage,
            themeAccentLight = themeAccentLight,
            themeSurface = themeSurface,
            modifier = modifier
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val tileHeight = (maxHeight - 10.dp).coerceIn(130.dp, 260.dp)
        val tileWidth = (tileHeight * 0.68f).coerceIn(96.dp, 176.dp)

        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .testTag("${testTagPrefix}_list"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = items,
                key = { idx, item -> "${idx}_${item.seriesId ?: ""}_${item.url ?: ""}_${item.name}" }
            ) { idx, item ->
                val badgeText = item.year?.toString() ?: item.group?.takeIf { it.isNotBlank() } ?: badgeFallback
                LiveStagePosterTile(
                    title = item.name,
                    logoUrl = item.logo,
                    badge = badgeText,
                    width = tileWidth,
                    height = tileHeight,
                    themeAccent = themeAccent,
                    themeAccentLight = themeAccentLight,
                    themeCard = themeCard,
                    testTag = "${testTagPrefix}_item_$idx",
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

@Composable
internal fun LiveStageFavoritesRow(
    favorites: List<FavoriteItem>,
    lang: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    onFavoriteClick: (FavoriteItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (favorites.isEmpty()) {
        // Explicit user request: when there are no favorites selected, nothing is shown
        Spacer(modifier = modifier)
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val tileHeight = (maxHeight - 10.dp).coerceIn(130.dp, 260.dp)
        val tileWidth = (tileHeight * 0.68f).coerceIn(96.dp, 176.dp)

        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .testTag("livestage_favorites_list"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = favorites,
                key = { idx, fav -> "${idx}_${fav.id}_${fav.url}" }
            ) { idx, fav ->
                val badgeText = when (IptvRepository.normalizeFavoriteType(fav.type)) {
                    "tv" -> Translator.translate("favorites_media_tv", lang)
                    "series" -> Translator.translate("favorites_media_series", lang)
                    else -> Translator.translate("favorites_media_movie", lang)
                }
                LiveStagePosterTile(
                    title = fav.title,
                    logoUrl = fav.logo,
                    badge = badgeText,
                    width = tileWidth,
                    height = tileHeight,
                    themeAccent = themeAccent,
                    themeAccentLight = themeAccentLight,
                    themeCard = themeCard,
                    testTag = "livestage_favorites_item_$idx",
                    onClick = { onFavoriteClick(fav) }
                )
            }
        }
    }
}

@Composable
private fun LiveStagePosterTile(
    title: String,
    logoUrl: String?,
    badge: String,
    width: Dp,
    height: Dp,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    testTag: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .testTag(testTag)
            .tvCardFocusable(
                shape = RoundedCornerShape(12.dp),
                scaleFocused = 1.04f,
                elevationFocused = 8.dp,
                unfocusedBackgroundColor = themeCard.copy(alpha = 0.9f),
                focusedBackgroundColor = TV_FOCUSED_BG,
                unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                focusedBorderColor = themeAccentLight,
                focusedBorderWidth = 2.dp,
                onFocusChange = { isFocused = it },
                onClick = onClick
            )
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(themeAccent.copy(alpha = 0.28f), themeCard.copy(alpha = 0.95f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Top badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = badge,
                color = themeAccentLight,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Bottom caption gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(
                text = title,
                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun LiveStageActivePlaylistPanel(
    activePlaylist: Playlist?,
    isOnline: Boolean,
    liveCount: Int,
    moviesCount: Int,
    seriesCount: Int,
    lang: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeSurface: Color,
    onOpenPlaylists: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .testTag("livestage_connected_playlist_card")
                .tvCardFocusable(
                    shape = RoundedCornerShape(16.dp),
                    scaleFocused = 1.02f,
                    elevationFocused = 8.dp,
                    unfocusedBackgroundColor = themeSurface.copy(alpha = 0.80f),
                    focusedBackgroundColor = TV_FOCUSED_BG,
                    unfocusedBorderColor = themeAccent.copy(alpha = 0.50f),
                    focusedBorderColor = themeAccentLight,
                    focusedBorderWidth = 2.dp,
                    onFocusChange = { isFocused = it },
                    onClick = onOpenPlaylists
                )
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(themeAccent.copy(alpha = 0.25f))
                    .border(1.dp, themeAccentLight.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = Translator.translate("playlists", lang),
                    tint = themeAccentLight,
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = Translator.translate("connected_playlist", lang),
                        color = themeAccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (activePlaylist != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if (isOnline) Color(0xFF10B981).copy(alpha = 0.22f)
                                    else Color(0xFFEF4444).copy(alpha = 0.22f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = Translator.translate(if (isOnline) "online" else "offline", lang),
                                color = if (isOnline) Color(0xFF6EE7B7) else Color(0xFFFCA5A5),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = activePlaylist.type.uppercase(),
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = activePlaylist?.name?.ifBlank { activePlaylist.host }
                        ?: Translator.translate("no_active_playlist", lang),
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (activePlaylist != null) {
                    Text(
                        text = listOf(
                            Translator.translate("channels_count_short", lang, liveCount),
                            Translator.translate("movies_count_short", lang, moviesCount),
                            Translator.translate("series_count_short", lang, seriesCount)
                        ).joinToString("  •  "),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = Translator.translate("no_lists", lang),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(themeAccent.copy(alpha = 0.25f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = Translator.translate("open_all_playlists", lang),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun LiveStageInterfacesRow(
    selectedLayout: String,
    lang: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    onSelectInterface: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeBadge = Translator.translate("interface_active_badge", lang)
    BoxWithConstraints(modifier = modifier) {
        val cardHeight = (maxHeight - 8.dp).coerceIn(135.dp, 215.dp)
        val cardWidth = (cardHeight * 1.38f).coerceIn(200.dp, 280.dp)

        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .testTag("livestage_interfaces_list"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = HOME_INTERFACE_OPTIONS,
                key = { _, opt -> opt.id }
            ) { _, opt ->
                val isSelected = selectedLayout == opt.id
                var isFocused by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .testTag("livestage_interface_item_${opt.id}")
                        .tvCardFocusable(
                            shape = RoundedCornerShape(14.dp),
                            scaleFocused = 1.03f,
                            elevationFocused = 8.dp,
                            unfocusedBackgroundColor = if (isSelected) {
                                themeAccent.copy(alpha = 0.18f)
                            } else {
                                themeSurface.copy(alpha = 0.80f)
                            },
                            focusedBackgroundColor = TV_FOCUSED_BG,
                            unfocusedBorderColor = if (isSelected) themeAccentLight else Color.White.copy(alpha = 0.14f),
                            focusedBorderColor = themeAccentLight,
                            focusedBorderWidth = 2.dp,
                            onFocusChange = { isFocused = it },
                            onClick = { onSelectInterface(opt.id) }
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
                            .padding(7.dp)
                    ) {
                        InterfaceMiniPreview(
                            layoutId = opt.id,
                            themeAccent = themeAccent,
                            themeAccentLight = themeAccentLight,
                            themeCard = themeCard
                        )
                        if (isSelected) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(themeAccent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = activeBadge,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = activeBadge,
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    Text(
                        text = Translator.translate(opt.titleKey, lang),
                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStageEmptyState(
    icon: ImageVector,
    message: String,
    themeAccentLight: Color,
    themeSurface: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(themeSurface.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = themeAccentLight.copy(alpha = 0.65f),
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
