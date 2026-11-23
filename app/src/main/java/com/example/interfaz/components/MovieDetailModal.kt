package com.example.interfaz.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import coil.compose.AsyncImage
import com.example.interfaz.AppThemeColors
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.motor.db.IptvItem
import com.example.motor.repository.MediaDetails
import com.example.parseHexColor

@Composable
fun MovieDetailFullScreen(
    movie: IptvItem,
    isFav: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors,
    onFetchDetails: (suspend (IptvItem) -> MediaDetails?)? = null,
    onSearchSubtitles: (suspend (String) -> Unit)? = null,
    onGetCachedYear: ((String) -> Int?)? = null
) {
    var details by remember(movie.name) { mutableStateOf<MediaDetails?>(null) }
    var isLoading by remember(movie.name) { mutableStateOf(true) }
    var showFullSynopsisDialog by remember(movie.name) { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val isTvOrBox = remember(context) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        isLeanback || isTvMode
    }

    LaunchedEffect(movie.name) {
        isLoading = true
        try {
            details = onFetchDetails?.invoke(movie)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
        try {
            onSearchSubtitles?.invoke(movie.name)
        } catch (_: Exception) {}
    }

    val surfaceColor = parseHexColor(theme.surface)
    val accentColor = parseHexColor(theme.accent)
    val playButtonFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try {
            playButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (showFullSynopsisDialog) {
            showFullSynopsisDialog = false
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(parseHexColor(theme.bg))
    ) {
        val backdrop = details?.backdropUrl

        // Backdrop image in background with dark gradient fade
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.4f),
                                parseHexColor(theme.bg).copy(alpha = 0.85f),
                                parseHexColor(theme.bg)
                            )
                        )
                    )
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top Bar with Back Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvIconButton(
                        onClick = onBack,
                        containerColor = Color.White.copy(alpha = 0.1f),
                        shape = CircleShape,
                        modifier = Modifier.size(38.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver al catálogo",
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Películas",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isLandscape) {
                    // Landscape / Android TV Layout: Left Column (Poster + Action buttons) | Right Column (Metadata + Full Synopsis)
                    val leftColWidth = if (isTvOrBox) 150.dp else 140.dp
                    val posterWidth = if (isTvOrBox) 145.dp else 118.dp
                    val playBtnHeight = if (isTvOrBox) 38.dp else 34.dp
                    val favBtnHeight = if (isTvOrBox) 36.dp else 32.dp
                    val btnSpacing = if (isTvOrBox) 8.dp else 6.dp
                    val posterBottomSpacing = if (isTvOrBox) 12.dp else 8.dp

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(if (isTvOrBox) 24.dp else 20.dp)
                    ) {
                        // Left Column: Poster + Actions
                        Column(
                            modifier = Modifier
                                .width(leftColWidth)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val posterModel = details?.posterUrl ?: movie.logo
                            Box(
                                modifier = Modifier
                                    .width(posterWidth)
                                    .aspectRatio(0.68f)
                                    .clip(RoundedCornerShape(if (isTvOrBox) 12.dp else 10.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(if (isTvOrBox) 12.dp else 10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!posterModel.isNullOrBlank()) {
                                    AsyncImage(
                                        model = posterModel,
                                        contentDescription = movie.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(if (isTvOrBox) 40.dp else 30.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(posterBottomSpacing))

                            // Action buttons under poster in landscape
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(btnSpacing)
                            ) {
                                TvButton(
                                    onClick = onPlay,
                                    containerColor = accentColor,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(playBtnHeight)
                                        .focusRequester(playButtonFocusRequester)
                                ) { isFocused ->
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Reproducir",
                                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                                    )
                                }

                                TvButton(
                                    onClick = onToggleFavorite,
                                    containerColor = Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(favBtnHeight)
                                ) { isFocused ->
                                    Icon(
                                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorito",
                                        tint = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color(0xFFFF4B4B) else Color.White),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isFav) "Favorito" else "Favoritos",
                                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Right Column: Title, Badges, and Spacious Synopsis Box
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            val displayTitle = details?.title?.ifBlank { movie.name } ?: movie.name
                            Text(
                                text = displayTitle,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Badges Row: Year, Rating, Genres
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val year = details?.year ?: onGetCachedYear?.invoke(movie.name) ?: movie.year
                                if (year != null && year > 0) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = year.toString(),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                val rating = details?.voteAverage
                                if (rating != null && rating > 0.0) {
                                    Surface(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = String.format(java.util.Locale.US, "%.1f", rating),
                                                color = Color(0xFFF59E0B),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                val genres = details?.genres
                                if (!genres.isNullOrEmpty()) {
                                    Text(
                                        text = "•  " + genres.take(3).joinToString(" • "),
                                        color = Color.LightGray.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Sinopsis",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            // Spacious, scrollable synopsis box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(surfaceColor.copy(alpha = 0.6f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                    .tvClickable(shape = RoundedCornerShape(10.dp)) {
                                        val curOverview = details?.overview?.trim() ?: ""
                                        if (curOverview.isNotBlank() && curOverview != "Sin sinopsis disponible para este contenido." && curOverview != "Sin sinopsis disponible.") {
                                            showFullSynopsisDialog = true
                                        }
                                    }
                                    .padding(14.dp)
                            ) {
                                if (isLoading) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                color = accentColor,
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                text = "Cargando información TMDB...",
                                                color = Color.LightGray,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                } else {
                                    val overview = details?.overview?.ifBlank { "Sin sinopsis disponible para este contenido." }
                                        ?: "Sin sinopsis disponible para este contenido."
                                    Text(
                                        text = overview,
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        modifier = Modifier.verticalScroll(rememberScrollState())
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Portrait Layout: Vertical Scroll with Poster + Details + Action Buttons
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val posterModel = details?.posterUrl ?: movie.logo
                            Box(
                                modifier = Modifier
                                    .width(110.dp)
                                    .aspectRatio(0.68f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!posterModel.isNullOrBlank()) {
                                    AsyncImage(
                                        model = posterModel,
                                        contentDescription = movie.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val displayTitle = details?.title?.ifBlank { movie.name } ?: movie.name
                                Text(
                                    text = displayTitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val year = details?.year ?: onGetCachedYear?.invoke(movie.name) ?: movie.year
                                    if (year != null && year > 0) {
                                        Surface(
                                            color = Color.White.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = year.toString(),
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val rating = details?.voteAverage
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
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = String.format(java.util.Locale.US, "%.1f", rating),
                                                    color = Color(0xFFF59E0B),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                val genres = details?.genres
                                if (!genres.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = genres.take(3).joinToString(" • "),
                                        color = Color.LightGray.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                onClick = onPlay,
                                containerColor = accentColor,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Reproducir",
                                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                                )
                            }

                            TvButton(
                                onClick = onToggleFavorite,
                                containerColor = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(44.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorito",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isFav) "Favorito" else "Añadir",
                                    color = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Sinopsis",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceColor.copy(alpha = 0.6f))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .tvClickable(shape = RoundedCornerShape(8.dp)) {
                                    val curOverview = details?.overview?.trim() ?: ""
                                    if (curOverview.isNotBlank() && curOverview != "Sin sinopsis disponible para este contenido." && curOverview != "Sin sinopsis disponible.") {
                                        showFullSynopsisDialog = true
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            if (isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = accentColor,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else {
                                val overview = details?.overview?.ifBlank { "Sin sinopsis disponible para este contenido." }
                                    ?: "Sin sinopsis disponible para este contenido."
                                Text(
                                    text = overview,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }

                // Modal flotante exclusivo para ver la sinopsis completa
                if (showFullSynopsisDialog) {
                    val fullOverview = details?.overview?.ifBlank { "Sin sinopsis disponible para este contenido." }
                        ?: "Sin sinopsis disponible para este contenido."
                    val displayTitle = details?.title?.ifBlank { movie.name } ?: movie.name
                    Dialog(
                        onDismissRequest = { showFullSynopsisDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
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
                                            text = displayTitle,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Sinopsis completa",
                                            color = accentColor,
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
                                    containerColor = accentColor,
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
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
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

@Composable
fun MovieDetailModal(
    movie: IptvItem,
    isFav: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    theme: AppThemeColors,
    activePlaylist: com.example.motor.config.Playlist? = null,
    onFetchDetails: (suspend (IptvItem) -> MediaDetails?)? = null,
    onSearchSubtitles: (suspend (String) -> Unit)? = null,
    onGetCachedYear: ((String) -> Int?)? = null
) {
    var details by remember { mutableStateOf<MediaDetails?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showFullSynopsisDialog by remember { mutableStateOf(false) }

    LaunchedEffect(movie.name, activePlaylist?.id) {
        isLoading = true
        try {
            details = onFetchDetails?.invoke(movie)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
        try {
            onSearchSubtitles?.invoke(movie.name)
        } catch (_: Exception) {}
    }

    val surfaceColor = parseHexColor(theme.surface)
    val accentColor = parseHexColor(theme.accent)
    val playButtonFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try {
            playButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (showFullSynopsisDialog) {
            showFullSynopsisDialog = false
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp))
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isLandscape = maxWidth > maxHeight

                // Backdrop image at the top with gradient fade
                val backdrop = details?.backdropUrl
                if (!backdrop.isNullOrBlank()) {
                    AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isLandscape) 140.dp else 200.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isLandscape) 140.dp else 200.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.35f),
                                        surfaceColor.copy(alpha = 0.85f),
                                        surfaceColor
                                    )
                                )
                            )
                    )
                }

                // Close Button Top-Right
                TvIconButton(
                    onClick = onDismiss,
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(34.dp)
                ) { isFocused ->
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (isLandscape) {
                    // Landscape / TV Layout: Left Column (Poster + Buttons) | Right Column (Metadata + Full Synopsis)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Left Column: Poster + Actions
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            val posterModel = details?.posterUrl ?: movie.logo
                            Box(
                                modifier = Modifier
                                    .width(135.dp)
                                    .weight(1f, fill = false)
                                    .aspectRatio(0.68f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!posterModel.isNullOrBlank()) {
                                    AsyncImage(
                                        model = posterModel,
                                        contentDescription = movie.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action buttons under poster in landscape
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TvButton(
                                    onClick = {
                                        onDismiss()
                                        onPlay()
                                    },
                                    containerColor = accentColor,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .focusRequester(playButtonFocusRequester)
                                ) { isFocused ->
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Reproducir",
                                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                                    )
                                }

                                TvButton(
                                    onClick = onToggleFavorite,
                                    containerColor = Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                ) { isFocused ->
                                    Icon(
                                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorito",
                                        tint = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isFav) "Favorito" else "Añadir",
                                        color = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                        fontSize = 12.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // Right Column: Title, Badges, and Spacious Synopsis Box
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = 4.dp)
                        ) {
                            val displayTitle = details?.title?.ifBlank { movie.name } ?: movie.name
                            Text(
                                text = displayTitle,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 40.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Badges Row: Year, Rating, Genres
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val year = details?.year ?: onGetCachedYear?.invoke(movie.name) ?: movie.year
                                if (year != null && year > 0) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = year.toString(),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                val rating = details?.voteAverage
                                if (rating != null && rating > 0.0) {
                                    Surface(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = String.format(java.util.Locale.US, "%.1f", rating),
                                                color = Color(0xFFF59E0B),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                val genres = details?.genres
                                if (!genres.isNullOrEmpty()) {
                                    Text(
                                        text = "•  " + genres.take(3).joinToString(" • "),
                                        color = Color.LightGray.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Sinopsis",
                                color = accentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            // Spacious, scrollable synopsis box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.25f))
                                    .tvClickable(shape = RoundedCornerShape(12.dp)) {
                                        val curOverview = details?.overview?.trim() ?: ""
                                        if (curOverview.isNotBlank() && curOverview != "Sin sinopsis disponible para este contenido." && curOverview != "Sin sinopsis disponible.") {
                                            showFullSynopsisDialog = true
                                        }
                                    }
                                    .padding(14.dp)
                            ) {
                                if (isLoading) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                color = accentColor,
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(
                                                text = "Cargando sinopsis...",
                                                color = Color.LightGray,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                } else {
                                    val overview = details?.overview?.ifBlank { "Sin sinopsis disponible para este contenido." }
                                        ?: "Sin sinopsis disponible para este contenido."
                                    Text(
                                        text = overview,
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 13.5.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.verticalScroll(rememberScrollState())
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Portrait Layout: Vertical Scroll with Poster + Details + Action Buttons
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (!backdrop.isNullOrBlank()) 30.dp else 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val posterModel = details?.posterUrl ?: movie.logo
                            Box(
                                modifier = Modifier
                                    .width(105.dp)
                                    .aspectRatio(0.68f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!posterModel.isNullOrBlank()) {
                                    AsyncImage(
                                        model = posterModel,
                                        contentDescription = movie.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val displayTitle = details?.title?.ifBlank { movie.name } ?: movie.name
                                Text(
                                    text = displayTitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val year = details?.year ?: onGetCachedYear?.invoke(movie.name) ?: movie.year
                                    if (year != null && year > 0) {
                                        Surface(
                                            color = Color.White.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = year.toString(),
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    val rating = details?.voteAverage
                                    if (rating != null && rating > 0.0) {
                                        Surface(
                                            color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = Color(0xFFF59E0B),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = String.format(java.util.Locale.US, "%.1f", rating),
                                                    color = Color(0xFFF59E0B),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                val genres = details?.genres
                                if (!genres.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = genres.take(3).joinToString(" • "),
                                        color = Color.LightGray.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Sinopsis",
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 90.dp, max = 180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .tvClickable(shape = RoundedCornerShape(10.dp)) {
                                    val curOverview = details?.overview?.trim() ?: ""
                                    if (curOverview.isNotBlank() && curOverview != "Sin sinopsis disponible para este contenido." && curOverview != "Sin sinopsis disponible.") {
                                        showFullSynopsisDialog = true
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            if (isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = accentColor,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else {
                                val overview = details?.overview?.ifBlank { "Sin sinopsis disponible para este contenido." }
                                    ?: "Sin sinopsis disponible para este contenido."
                                Text(
                                    text = overview,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                onClick = {
                                    onDismiss()
                                    onPlay()
                                },
                                containerColor = accentColor,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Reproducir",
                                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                                )
                            }

                            TvButton(
                                onClick = onToggleFavorite,
                                containerColor = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(46.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorito",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isFav) "Favorito" else "Añadir",
                                    color = if (isFocused) TV_FOCUSED_TEXT else (if (isFav) Color.Red else Color.White),
                                    fontSize = 13.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Modal flotante exclusivo para ver la sinopsis completa
                if (showFullSynopsisDialog) {
                    val fullOverview = details?.overview?.ifBlank { "Sin sinopsis disponible para este contenido." }
                        ?: "Sin sinopsis disponible para este contenido."
                    val displayTitle = details?.title?.ifBlank { movie.name } ?: movie.name
                    Dialog(
                        onDismissRequest = { showFullSynopsisDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
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
                                            text = displayTitle,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Sinopsis completa",
                                            color = accentColor,
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
                                    containerColor = accentColor,
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
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
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
