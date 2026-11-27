package com.example.interfaz.screens

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.interfaz.AppThemeColors
import com.example.interfaz.MainViewModel
import com.example.interfaz.components.tvClickable
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.interfaz.viewmodel.PlaylistViewModel
import com.example.parseHexColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Faithful, pixel-perfect replica of the Vivo Player TV interface layout.
 *
 * Characteristics:
 * - Pure landscape default orientation.
 * - Dynamic color adaptation matching the selected theme (Yellow, Red, Blue, Green, Purple, etc.).
 * - Omission of third-party branding logos.
 * - Exact button positions:
 *     - Left column: Live TV (top) + Catch Up (bottom).
 *     - Center column: Movies (spanning full column height).
 *     - Right column: Series (spanning full column height).
 *     - Lower row: Favorites (50%) + Playlists (50%).
 * - Top header: Centered large digital clock + date, Top-right capsule with Search, Profile, Settings, Notifications.
 * - Bottom footer: Current Playlist info on the left, Refresh + Power capsule and Version on the right.
 * - Full D-Pad / TV remote navigation with halo glow and scaling.
 */
@Composable
fun VivoTvHomeScreen(
    viewModel: HomeViewModel,
    mainViewModel: MainViewModel,
    playlistViewModel: PlaylistViewModel,
    onNavigate: (String) -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier,
    onExitRequest: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentProfile by mainViewModel.currentProfile.collectAsStateWithLifecycle()
    val homeUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by playlistViewModel.isBackgroundRefreshing.collectAsStateWithLifecycle()

    var hasNotifiedRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            hasNotifiedRefresh = true
        } else if (hasNotifiedRefresh) {
            hasNotifiedRefresh = false
            Toast.makeText(context, "Lista actualizada correctamente", Toast.LENGTH_SHORT).show()
        }
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // Live clock
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US)
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now)
            delay(1000L)
        }
    }

    // Colors derived directly from the active theme
    val themeBg = remember(theme) { parseHexColor(theme.bg) }
    val themeSurface = remember(theme) { parseHexColor(theme.surface) }
    val themeCard = remember(theme) { parseHexColor(theme.card) }
    val themeCardHover = remember(theme) { parseHexColor(theme.cardHover) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        themeBg,
                        themeSurface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ─── TOP SECTION: EMPTY LOGO AREA | CLOCK | ACTION CAPSULE ─────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Top-Left: Empty spacer where the third-party logo used to be
                Box(modifier = Modifier.width(140.dp))

                // Top-Center: Large clock & Date
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentTime.ifEmpty { "10:36" },
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = currentDate.ifEmpty { "Sunday, Oct 25, 2026" },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Top-Right: Pill Capsule with Search, Profile & Settings actions
                TopActionCapsule(
                    onSearch = { onNavigate("movies") },
                    onProfile = { onNavigate("accounts") },
                    onSettings = { onNavigate("settings") },
                    themeCard = themeCard,
                    accentColor = themeAccent
                )
            }

            // ─── CENTER SECTION: TILES GRID ──────────────────────────────────────
            if (isPortrait) {
                // Adaptive layout for portrait
                VivoPortraitTiles(
                    onNavigate = onNavigate,
                    theme = theme
                )
            } else {
                // Exact replica landscape layout
                VivoLandscapeTiles(
                    onNavigate = onNavigate,
                    theme = theme,
                    modifier = Modifier.weight(1f)
                )
            }

            // ─── BOTTOM SECTION: PLAYLIST DETAILS | REFRESH & POWER CAPSULE ─────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Bottom-Left: Current Playlist Info
                val activeStatePl by playlistViewModel.activePlaylist.collectAsStateWithLifecycle()
                val allPlaylists by playlistViewModel.playlists.collectAsStateWithLifecycle()
                val activePl = activeStatePl
                    ?: allPlaylists.find { it.active }
                    ?: allPlaylists.firstOrNull()
                val activeName = activePl?.name?.ifBlank { "Sin nombre" } ?: "Sin lista activa"
                val activeExpiry = if (activePl == null) "—" else activePl.expires.ifBlank { "Sin caducidad" }

                Column {
                    Text(
                        text = "Current Playlist: $activeName",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Current playlist expires: $activeExpiry",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Bottom-Right: Refresh & Power capsule
                BottomActionCapsule(
                    onRefresh = {
                        val targetPl = playlistViewModel.activePlaylist.value
                            ?: playlistViewModel.playlists.value.find { it.active }
                            ?: playlistViewModel.playlists.value.firstOrNull()
                        if (targetPl != null) {
                            Toast.makeText(context, "Actualizando lista de canales...", Toast.LENGTH_SHORT).show()
                            playlistViewModel.refreshPlaylistInBackground(targetPl)
                        } else {
                            Toast.makeText(context, "No hay ninguna lista conectada para refrescar", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPower = onExitRequest,
                    themeCard = themeCard,
                    accentColor = themeAccent,
                    isRefreshing = isRefreshing
                )
            }
        }
    }
}

/**
 * Top-Right pill capsule containing Search, Account, and Settings.
 */
@Composable
private fun TopActionCapsule(
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    themeCard: Color,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(themeCard.copy(alpha = 0.85f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VivoIconActionButton(icon = Icons.Default.Search, label = "Buscar", onClick = onSearch, accentColor = accentColor)
        VivoIconActionButton(icon = Icons.Default.AccountCircle, label = "Perfil", onClick = onProfile, accentColor = accentColor)
        VivoIconActionButton(icon = Icons.Default.Settings, label = "Ajustes", onClick = onSettings, accentColor = accentColor)
    }
}

/**
 * Single icon button inside a top or bottom capsule with focus scale and halo.
 */
@Composable
private fun VivoIconActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accentColor: Color,
    iconModifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "icon_scale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .size(28.dp)
            .clip(CircleShape)
            .background(if (isFocused) accentColor.copy(alpha = 0.4f) else Color.Transparent)
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = if (isFocused) accentColor else Color.Transparent,
                shape = CircleShape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp).then(iconModifier)
        )
    }
}

/**
 * Bottom-Right pill capsule containing Refresh and Power buttons.
 */
@Composable
private fun BottomActionCapsule(
    onRefresh: () -> Unit,
    onPower: () -> Unit,
    themeCard: Color,
    accentColor: Color,
    isRefreshing: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(themeCard.copy(alpha = 0.85f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VivoIconActionButton(
            icon = Icons.Default.Refresh,
            label = if (isRefreshing) "Actualizando..." else "Refrescar",
            onClick = onRefresh,
            accentColor = accentColor,
            iconModifier = if (isRefreshing) Modifier.graphicsLayer { rotationZ = rotationAngle } else Modifier
        )
        VivoIconActionButton(
            icon = Icons.Default.PowerSettingsNew,
            label = "Apagar",
            onClick = onPower,
            accentColor = accentColor
        )
    }
}

/**
 * Landscape tiles grid matching the exact positions of the user screenshot:
 * - 3 equal columns:
 *   - Column 1: Live TV (top) + Catch Up (bottom)
 *   - Column 2: Movies (spans full combined height)
 *   - Column 3: Series (spans full combined height)
 * - Row below:
 *   - Favorites (50%) + Playlists (50%)
 *
 * Sized and optimized for mobile phone landscape so icons and text never clip.
 */
@Composable
private fun VivoLandscapeTiles(
    onNavigate: (String) -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier
) {
    val themeCard = remember(theme) { parseHexColor(theme.card) }
    val themeCardHover = remember(theme) { parseHexColor(theme.cardHover) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            // Upper Grid: 3 Columns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.70f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Column 1: Live TV (top) + Catch Up (bottom)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VivoTile(
                        title = "Live TV",
                        icon = Icons.Default.Tv,
                        onClick = { onNavigate("live_tv") },
                        themeCard = themeCard,
                        themeCardHover = themeCardHover,
                        themeAccent = themeAccent,
                        iconSize = 28.dp,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.68f)
                    )

                    VivoHorizontalTile(
                        title = "Deportes",
                        icon = Icons.Default.SportsSoccer,
                        onClick = { onNavigate("sports") },
                        themeCard = themeCard,
                        themeCardHover = themeCardHover,
                        themeAccent = themeAccent,
                        iconSize = 18.dp,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.32f)
                    )
                }

                // Column 2: Movies (tall)
                VivoTile(
                    title = "Movies",
                    icon = Icons.Default.Movie,
                    onClick = { onNavigate("movies") },
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    iconSize = 34.dp,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // Column 3: Series (tall)
                VivoTile(
                    title = "Series",
                    icon = Icons.Default.VideoLibrary,
                    onClick = { onNavigate("series") },
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    iconSize = 34.dp,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lower Row: Favorites (50%) + Playlists (50%)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.24f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VivoHorizontalTile(
                    title = "Favorites",
                    icon = Icons.Default.BookmarkBorder,
                    onClick = { onNavigate("favorites") },
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    iconSize = 20.dp,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                VivoHorizontalTile(
                    title = "Playlists",
                    icon = Icons.Default.FormatListBulleted,
                    onClick = { onNavigate("playlists") },
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    iconSize = 20.dp,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/**
 * Standard vertical tile (Live TV, Movies, Series) styled to match the app cards:
 * - Exact theme card color and hover/focus highlight.
 * - Fine 1.dp border (1.5.dp on focus).
 * - Compact icon and text so labels are always 100% visible on mobile landscape.
 */
@Composable
private fun VivoTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    themeCard: Color,
    themeCardHover: Color,
    themeAccent: Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "tile_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 8.dp else 2.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = themeAccent
            )
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) themeCardHover else themeCard)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) themeAccent else themeAccent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Compact horizontal tile (Catch Up, Favorites, Playlists) styled to match the app cards:
 * - Exact theme card color and hover/focus highlight.
 * - Fine 1.dp border (1.5.dp on focus).
 * - Sized so icon and text fit comfortably without truncation.
 */
@Composable
private fun VivoHorizontalTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    themeCard: Color,
    themeCardHover: Color,
    themeAccent: Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "h_tile_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 8.dp else 2.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = themeAccent
            )
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) themeCardHover else themeCard)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) themeAccent else themeAccent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Adaptive vertical tiles grid for portrait mobile display.
 */
@Composable
private fun VivoPortraitTiles(
    onNavigate: (String) -> Unit,
    theme: AppThemeColors
) {
    val themeCard = remember(theme) { parseHexColor(theme.card) }
    val themeCardHover = remember(theme) { parseHexColor(theme.cardHover) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VivoTile(
                title = "Live TV",
                icon = Icons.Default.Tv,
                onClick = { onNavigate("live_tv") },
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                iconSize = 30.dp,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
            )
            VivoTile(
                title = "Movies",
                icon = Icons.Default.Movie,
                onClick = { onNavigate("movies") },
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                iconSize = 30.dp,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp)
            )
        }

        VivoTile(
            title = "Series",
            icon = Icons.Default.VideoLibrary,
            onClick = { onNavigate("series") },
            themeCard = themeCard,
            themeCardHover = themeCardHover,
            themeAccent = themeAccent,
            iconSize = 30.dp,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VivoHorizontalTile(
                title = "Deportes",
                icon = Icons.Default.SportsSoccer,
                onClick = { onNavigate("sports") },
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                iconSize = 18.dp,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            )
            VivoHorizontalTile(
                title = "Favorites",
                icon = Icons.Default.BookmarkBorder,
                onClick = { onNavigate("favorites") },
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                iconSize = 18.dp,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            )
        }

        VivoHorizontalTile(
            title = "Playlists",
            icon = Icons.Default.FormatListBulleted,
            onClick = { onNavigate("playlists") },
            themeCard = themeCard,
            themeCardHover = themeCardHover,
            themeAccent = themeAccent,
            iconSize = 18.dp,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )
    }
}

