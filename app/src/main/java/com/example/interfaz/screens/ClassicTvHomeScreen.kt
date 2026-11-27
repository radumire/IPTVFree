package com.example.interfaz.screens

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.interfaz.state.HomeState
import com.example.interfaz.viewmodel.HomeViewModel
import com.example.parseHexColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Modern 3D IPTV Launcher Home Screen.
 *
 * Symmetrical architecture inspired by classic Android TV launchers (XCIPTV / Smarters),
 * featuring 7 distinct 3D glossy tiles with animated halo glow on TV remote (D-Pad) focus,
 * live real-time digital clock, playlist summary, and responsive landscape layout.
 */
@Composable
fun ClassicTvHomeScreen(
    viewModel: HomeViewModel,
    mainViewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentProfile by mainViewModel.currentProfile.collectAsStateWithLifecycle()
    val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val context = LocalContext.current
    val isTvOrBox = remember(context) {
        val pm = context.packageManager
        val isLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        isLeanback || isTvMode
    }
    // Mobile landscape devices have limited height (< 500dp) compared to widescreen TVs (540dp+)
    val isMobile = !isTvOrBox || configuration.screenHeightDp < 500

    // Live real-time clock
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val locale = Locale.getDefault()
        val timeFormat = SimpleDateFormat("HH:mm:ss", locale)
        val datePattern = if (locale.language == "es") "EEEE, d 'de' MMMM" else "EEEE, d MMMM"
        val dateFormat = SimpleDateFormat(datePattern, locale)
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            delay(1000L)
        }
    }

    // Colors derived directly from the active theme (matching Vivo style)
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
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ─── TOP STATUS & BRANDING BAR ───────────────────────────────────────
            TopStatusHeader(
                profileName = currentProfile?.name ?: "Perfil Activo",
                profileAvatar = currentProfile?.avatar ?: com.example.interfaz.components.DEFAULT_PROFILE_AVATAR,
                isOnline = isOnline,
                currentTime = currentTime,
                currentDate = currentDate,
                onProfileClick = { onNavigate("accounts") },
                themeCard = themeCard,
                themeAccent = themeAccent
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ─── MAIN TILES SECTION ──────────────────────────────────────────────
            if (isPortrait) {
                // Adaptive scrollable layout for portrait mobile screens
                PortraitLayout(
                    uiState = uiState,
                    onNavigate = onNavigate,
                    currentProfileName = currentProfile?.name,
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    themeSurface = themeSurface
                )
            } else {
                // Primary widescreen landscape TV launcher layout
                LandscapeSymmetricLayout(
                    uiState = uiState,
                    onNavigate = onNavigate,
                    currentProfileName = currentProfile?.name,
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    themeSurface = themeSurface,
                    isMobile = isMobile
                )
            }
        }
    }
}

/**
 * Top bar displaying current profile, active playlist, live clock and network status.
 */
@Composable
private fun TopStatusHeader(
    profileName: String,
    profileAvatar: String,
    isOnline: Boolean,
    currentTime: String,
    currentDate: String,
    onProfileClick: () -> Unit,
    themeCard: Color,
    themeAccent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(themeCard.copy(alpha = 0.85f))
            .border(1.dp, themeAccent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Profile badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .tvClickable { onProfileClick() }
                .padding(4.dp)
        ) {
            com.example.interfaz.components.ProfileAvatarImage(
                avatarKey = profileAvatar,
                size = 34.dp,
                accentColor = themeAccent,
                contentDescription = profileName
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = profileName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color(0xFF00E676) else Color(0xFFFF1744))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isOnline) "En línea" else "Sin conexión",
                        color = if (isOnline) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Center: App title badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(themeAccent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "IPTV PLAYER",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Right: Live clock & date
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = currentTime.ifEmpty { "--:--:--" },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = currentDate,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

/**
 * Primary Widescreen Landscape TV Launcher Layout.
 *
 * Highly symmetric design:
 * - Left column (35% width): 4 Media buttons in a vertical column (Live TV, Películas, Series, Deportes).
 * - Right column (65% width):
 *     - Upper card (~68% height): IPTV Player hero display with counters.
 *     - Lower row (~32% height): 3 Management buttons (Listas, Ajustes, Perfiles), perfectly balanced.
 */
@Composable
private fun LandscapeSymmetricLayout(
    uiState: HomeState,
    onNavigate: (String) -> Unit,
    currentProfileName: String?,
    themeCard: Color,
    themeCardHover: Color,
    themeAccent: Color,
    themeSurface: Color,
    isMobile: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ─── LEFT COLUMN: 4 MEDIA CATEGORIES ─────────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. LIVE TV
            ClassicTvLauncherButton(
                title = "LIVE TV",
                icon = Icons.Default.Tv,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = if (uiState.totalLiveCount > 0) "${uiState.totalLiveCount}" else "EN VIVO",
                testTag = "btn_live_tv",
                iconOnLeft = isMobile,
                onClick = { onNavigate("live_tv") },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // 2. PELÍCULAS
            ClassicTvLauncherButton(
                title = "PELÍCULAS",
                icon = Icons.Default.Movie,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = if (uiState.totalMoviesCount > 0) "${uiState.totalMoviesCount}" else "VOD",
                testTag = "btn_movies",
                iconOnLeft = isMobile,
                onClick = { onNavigate("movies") },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // 3. SERIES
            ClassicTvLauncherButton(
                title = "SERIES",
                icon = Icons.Default.VideoLibrary,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = if (uiState.totalSeriesCount > 0) "${uiState.totalSeriesCount}" else "SERIES",
                testTag = "btn_series",
                iconOnLeft = isMobile,
                onClick = { onNavigate("series") },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // 4. DEPORTES
            ClassicTvLauncherButton(
                title = "DEPORTES",
                icon = Icons.Default.SportsSoccer,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = "EVENTOS",
                testTag = "btn_sports",
                iconOnLeft = isMobile,
                onClick = { onNavigate("sports") },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        // ─── RIGHT COLUMN: HERO BANNER + 3 UTILITY BUTTONS ────────────────────
        Column(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Upper: IPTV PLAYER Hero Card
            HeroPlayerCard(
                uiState = uiState,
                currentProfileName = currentProfileName,
                onHeroClick = { onNavigate("live_tv") },
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                themeSurface = themeSurface,
                modifier = Modifier
                    .weight(0.68f)
                    .fillMaxWidth()
            )

            // Lower: Row of 4 symmetric utility buttons (Favoritos, Listas, Ajustes, Perfiles)
            Row(
                modifier = Modifier
                    .weight(0.32f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 5. FAVORITOS
                ClassicTvLauncherButton(
                    title = "FAVORITOS",
                    icon = Icons.Default.Favorite,
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    badgeText = if (uiState.favorites.isNotEmpty()) "${uiState.favorites.size}" else null,
                    testTag = "btn_favorites",
                    onClick = { onNavigate("favorites") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // 6. LISTAS
                ClassicTvLauncherButton(
                    title = "LISTAS",
                    icon = Icons.Default.CloudDownload,
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    testTag = "btn_playlists",
                    onClick = { onNavigate("playlists") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // 7. AJUSTES
                ClassicTvLauncherButton(
                    title = "AJUSTES",
                    icon = Icons.Default.Settings,
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    testTag = "btn_settings",
                    onClick = { onNavigate("settings") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // 8. PERFILES
                ClassicTvLauncherButton(
                    title = "PERFILES",
                    icon = Icons.Default.People,
                    themeCard = themeCard,
                    themeCardHover = themeCardHover,
                    themeAccent = themeAccent,
                    testTag = "btn_profiles",
                    onClick = { onNavigate("accounts") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/**
 * Adaptive vertical layout for portrait mobile devices.
 */
@Composable
private fun PortraitLayout(
    uiState: HomeState,
    onNavigate: (String) -> Unit,
    currentProfileName: String?,
    themeCard: Color,
    themeCardHover: Color,
    themeAccent: Color,
    themeSurface: Color
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero card on top
        HeroPlayerCard(
            uiState = uiState,
            currentProfileName = currentProfileName,
            onHeroClick = { onNavigate("live_tv") },
            themeCard = themeCard,
            themeCardHover = themeCardHover,
            themeAccent = themeAccent,
            themeSurface = themeSurface,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )

        // 2x2 grid for the primary 4 media categories
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClassicTvLauncherButton(
                title = "LIVE TV",
                icon = Icons.Default.Tv,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = "${uiState.totalLiveCount}",
                testTag = "btn_live_tv",
                onClick = { onNavigate("live_tv") },
                modifier = Modifier
                    .weight(1f)
                    .height(95.dp)
            )
            ClassicTvLauncherButton(
                title = "PELÍCULAS",
                icon = Icons.Default.Movie,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = "${uiState.totalMoviesCount}",
                testTag = "btn_movies",
                onClick = { onNavigate("movies") },
                modifier = Modifier
                    .weight(1f)
                    .height(95.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClassicTvLauncherButton(
                title = "SERIES",
                icon = Icons.Default.VideoLibrary,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = "${uiState.totalSeriesCount}",
                testTag = "btn_series",
                onClick = { onNavigate("series") },
                modifier = Modifier
                    .weight(1f)
                    .height(95.dp)
            )
            ClassicTvLauncherButton(
                title = "DEPORTES",
                icon = Icons.Default.SportsSoccer,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                testTag = "btn_sports",
                onClick = { onNavigate("sports") },
                modifier = Modifier
                    .weight(1f)
                    .height(95.dp)
            )
        }

        // 2x2 grid for utility buttons (Favoritos, Listas, Ajustes, Perfiles)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClassicTvLauncherButton(
                title = "FAVORITOS",
                icon = Icons.Default.Favorite,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                badgeText = if (uiState.favorites.isNotEmpty()) "${uiState.favorites.size}" else null,
                testTag = "btn_favorites",
                onClick = { onNavigate("favorites") },
                modifier = Modifier
                    .weight(1f)
                    .height(82.dp)
            )
            ClassicTvLauncherButton(
                title = "LISTAS",
                icon = Icons.Default.CloudDownload,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                testTag = "btn_playlists",
                onClick = { onNavigate("playlists") },
                modifier = Modifier
                    .weight(1f)
                    .height(82.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClassicTvLauncherButton(
                title = "AJUSTES",
                icon = Icons.Default.Settings,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                testTag = "btn_settings",
                onClick = { onNavigate("settings") },
                modifier = Modifier
                    .weight(1f)
                    .height(82.dp)
            )
            ClassicTvLauncherButton(
                title = "PERFILES",
                icon = Icons.Default.People,
                themeCard = themeCard,
                themeCardHover = themeCardHover,
                themeAccent = themeAccent,
                testTag = "btn_profiles",
                onClick = { onNavigate("accounts") },
                modifier = Modifier
                    .weight(1f)
                    .height(82.dp)
            )
        }
    }
}

/**
 * Standard app launcher button matching application theme and button design:
 * - Theme card background with themeCardHover on focus.
 * - Fine 1.dp border (1.5.dp themeAccent on focus).
 * - Clean elevation, icon badge, and crisp typography.
 */
@Composable
fun ClassicTvLauncherButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    themeCard: Color,
    themeCardHover: Color,
    themeAccent: Color,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    testTag: String = "classic_tv_btn",
    iconOnLeft: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "button_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 10.dp else 2.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = themeAccent,
                ambientColor = themeAccent.copy(alpha = 0.35f)
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
            .testTag(testTag)
            .padding(
                horizontal = if (iconOnLeft) 10.dp else 12.dp,
                vertical = if (iconOnLeft) 4.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        if (iconOnLeft) {
            // Horizontal layout for mobile landscape screens:
            // Icon aligned to the left edge, text centered in the remaining space
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon stuck to the left
                Box(
                    modifier = Modifier
                        .size(if (isFocused) 32.dp else 28.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) themeAccent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(if (isFocused) 18.dp else 16.dp)
                    )
                }

                // Text centered in the remaining space
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = if (isFocused) 13.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!badgeText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.25f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Standard vertical layout (TV or utility buttons)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isFocused) 36.dp else 32.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) themeAccent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = if (isFocused) 13.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!badgeText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Centerpiece Hero Card with Play icon and live content statistics styled with active theme.
 */
@Composable
private fun HeroPlayerCard(
    uiState: HomeState,
    currentProfileName: String?,
    onHeroClick: () -> Unit,
    themeCard: Color,
    themeCardHover: Color,
    themeAccent: Color,
    themeSurface: Color,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "hero_scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 12.dp else 3.dp,
                shape = RoundedCornerShape(14.dp),
                spotColor = themeAccent,
                ambientColor = themeAccent.copy(alpha = 0.3f)
            )
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) themeCardHover else themeCard)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) themeAccent else themeAccent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onHeroClick() }
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Play Icon circle matching theme accent
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(themeAccent.copy(alpha = if (isFocused) 0.35f else 0.2f))
                    .border(1.5.dp, themeAccent.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = "IPTV PLAYER",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Text(
                text = uiState.activePlaylistName.takeIf { it.isNotBlank() }?.let { "Lista activa: $it" }
                    ?: currentProfileName?.takeIf { it.isNotBlank() }?.let { "Perfil: $it" }
                    ?: "Transmisión en Alta Definición",
                color = themeAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Live statistics pills using theme
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatPill(label = "Canales", count = uiState.totalLiveCount, accent = themeAccent)
                StatPill(label = "Películas", count = uiState.totalMoviesCount, accent = themeAccent)
                StatPill(label = "Series", count = uiState.totalSeriesCount, accent = themeAccent)
            }
        }
    }
}

@Composable
private fun StatPill(label: String, count: Int, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (count > 0) "$count $label" else label,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
