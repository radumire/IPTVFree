package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.Translator
import com.example.interfaz.AppThemeColors
import com.example.interfaz.ParentalPinDialog
import com.example.interfaz.components.*
import com.example.interfaz.viewmodel.SportsUiState
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsFilter
import com.example.motor.sports.model.SportsSport
import com.example.parseHexColor
import kotlinx.coroutines.delay

import com.example.motor.sports.matcher.SportsChannelCandidate
import com.example.motor.sports.matcher.dedupIdentityKey
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SportsScreen(
    viewModel: SportsViewModel,
    onPlay: (src: String, title: String, dScheme: String?, dLicense: String?, matchChannels: List<com.example.motor.db.IptvItem>, matchCategoryName: String?, headers: Map<String, String>) -> Unit = { _, _, _, _, _, _, _ -> },
    onBack: () -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier,
    lang: String = "es"
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var linkedChannelNotice by remember { mutableStateOf<String?>(null) }
    val initialFocusRequester = rememberTvInitialFocusRequester(delayMs = 120L)

    // Trigger sports data loading only when entering SportsScreen
    LaunchedEffect(Unit) {
        viewModel.loadData(forceRefresh = false)
        viewModel.preloadCategoryCounts()
        viewModel.startLivePolling()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopLivePolling()
        }
    }

    // Capture back button to return to Home
    BackHandler {
        onBack()
    }

    // Auto dismiss temporary channel notice after 3.2 seconds
    LaunchedEffect(linkedChannelNotice) {
        if (linkedChannelNotice != null) {
            delay(3200L)
            linkedChannelNotice = null
        }
    }

    val themeBg = remember(theme.bg) { parseHexColor(theme.bg) }
    val themeSurface = remember(theme.surface) { parseHexColor(theme.surface) }
    val themeCard = remember(theme.card) { parseHexColor(theme.card) }
    val themeAccent = remember(theme.accent) { parseHexColor(theme.accent) }
    val themeAccentLight = remember(theme.accentLight) { parseHexColor(theme.accentLight) }

    // Gradient background matching theme colors
    val backgroundBrush = remember(themeBg, themeSurface) {
        Brush.verticalGradient(
            colors = listOf(
                themeBg,
                themeSurface.copy(alpha = 0.85f),
                themeBg.copy(alpha = 0.98f)
            )
        )
    }

    CompositionLocalProvider(
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
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                // ─── 1. CABECERA PRINCIPAL (Deportes, Volver, Contador Live, Refresco, API Key) ───
                val currentSportLiveCount = if (uiState.liveEvents.any { SportsSport.fromId(it.sport) != uiState.selectedSport }) {
                    uiState.liveEvents.count { SportsSport.fromId(it.sport) == uiState.selectedSport }
                } else {
                    uiState.liveEvents.size
                }
                val currentSportTodayCount = if (uiState.todayEvents.any { SportsSport.fromId(it.sport) != uiState.selectedSport }) {
                    uiState.todayEvents.count { SportsSport.fromId(it.sport) == uiState.selectedSport }
                } else {
                    uiState.todayEvents.size
                }
                val currentSportTomorrowCount = if (uiState.tomorrowEvents.any { SportsSport.fromId(it.sport) != uiState.selectedSport }) {
                    uiState.tomorrowEvents.count { SportsSport.fromId(it.sport) == uiState.selectedSport }
                } else {
                    uiState.tomorrowEvents.size
                }
                val currentSportWeekCount = if (uiState.weekEvents.any { SportsSport.fromId(it.sport) != uiState.selectedSport }) {
                    uiState.weekEvents.count { SportsSport.fromId(it.sport) == uiState.selectedSport }
                } else {
                    uiState.weekEvents.size
                }

                SportsHeader(
                    onBack = onBack,
                    onRefresh = { viewModel.loadData(forceRefresh = true) },
                    isRefreshing = uiState.isRefreshing,
                    liveCount = currentSportLiveCount,
                    theme = theme,
                    focusRequester = initialFocusRequester,
                    lang = lang
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ─── 2. CATEGORÍAS DEPORTIVAS (Horizontal) ───────────────────────────
                SportsCategoriesRow(
                    selectedSport = uiState.selectedSport,
                    onSportSelected = { viewModel.selectSport(it) },
                    getSportCount = { uiState.getEventCountForSport(it) },
                    theme = theme,
                    lang = lang
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ─── 3. FILTROS (Todos, En Directo, Hoy, Mañana, Próximamente esta semana) ──
                SportsFiltersRow(
                    selectedFilter = uiState.selectedFilter,
                    onFilterSelected = { viewModel.selectFilter(it) },
                    liveCount = currentSportLiveCount,
                    todayCount = currentSportTodayCount,
                    tomorrowCount = currentSportTomorrowCount,
                    weekCount = currentSportWeekCount,
                    theme = theme,
                    lang = lang
                )

                Spacer(modifier = Modifier.height(6.dp))

                // ─── 4. CONTENIDO Y EVENTOS ──────────────────────────────────────────
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = themeAccent,
                                strokeWidth = 3.5.dp,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = Translator.translate("loading_sports", lang),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (uiState.errorMessage != null && uiState.isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(themeSurface.copy(alpha = 0.85f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                                .padding(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = uiState.errorMessage ?: Translator.translate("sports_load_error", lang),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            TvButton(
                                onClick = { viewModel.loadData(forceRefresh = true) },
                                containerColor = themeAccent
                            ) {
                                Text(Translator.translate("retry", lang), color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (uiState.isEmpty) {
                    val emptyTitle = when (uiState.selectedFilter) {
                        SportsFilter.LIVE -> Translator.translate("sports_empty_live", lang)
                        SportsFilter.TODAY -> Translator.translate("sports_empty_today", lang)
                        SportsFilter.TOMORROW -> Translator.translate("sports_empty_tomorrow", lang)
                        SportsFilter.THIS_WEEK -> Translator.translate("sports_empty_week", lang)
                        SportsFilter.ALL -> Translator.translate("sports_empty_all", lang)
                    }
                    val sportName = getSportLabel(uiState.selectedSport, lang)
                    val emptySubtitle = when (uiState.selectedFilter) {
                        SportsFilter.LIVE -> Translator.translate("sports_empty_sub_live", lang, sportName)
                        SportsFilter.TODAY -> Translator.translate("sports_empty_sub_today", lang)
                        SportsFilter.TOMORROW -> Translator.translate("sports_empty_sub_tomorrow", lang)
                        SportsFilter.THIS_WEEK -> Translator.translate("sports_empty_sub_week", lang)
                        SportsFilter.ALL -> Translator.translate("sports_empty_sub_all", lang, sportName)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(themeSurface.copy(alpha = 0.65f))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                                .padding(32.dp)
                        ) {
                            Text(
                                text = getSportEmoji(uiState.selectedSport),
                                fontSize = 44.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = emptyTitle,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = emptySubtitle,
                                color = Color.LightGray.copy(alpha = 0.75f),
                                fontSize = 12.5.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TvButton(
                                onClick = { viewModel.loadData(forceRefresh = true) },
                                containerColor = themeAccent.copy(alpha = 0.9f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(Translator.translate("refresh_schedule", lang), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Contenido organizado en secciones visuales de tarjetas
                    SportsSectionsList(
                        uiState = uiState,
                        onEventAction = { event ->
                            viewModel.matchAndPlayEvent(
                                event = event,
                                onPlay = onPlay,
                                onNotice = { linkedChannelNotice = it }
                            )
                        },
                        theme = theme,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        lang = lang
                    )
                }
            }

            // ─── SELECTOR DE CANALES CUANDO HAY MÚLTIPLES CANDIDATOS (FASE 3) ──
            val selectionState = uiState.channelSelectionState
            if (selectionState != null) {
                SportsChannelSelectionDialog(
                    selectionState = selectionState,
                    onSelect = { candidate ->
                        viewModel.selectCandidateToPlay(
                            candidate = candidate,
                            onPlay = onPlay,
                            onNotice = { linkedChannelNotice = it }
                        )
                    },
                    onDismiss = { viewModel.dismissChannelSelection() },
                    theme = theme,
                    lang = lang,
                    isLockedChannel = { viewModel.isChannelParentalLocked(it) }
                )
            }

            // ─── CONTROL PARENTAL PIN PARA CANALES RESTRINGIDOS ────────────────
            val pendingParental = uiState.pendingParentalUnlock
            if (pendingParental != null) {
                ParentalPinDialog(
                    channelName = pendingParental.candidate.channel.name,
                    expectedPin = viewModel.getExpectedParentalPin(),
                    onDismiss = { viewModel.dismissParentalPrompt() },
                    onSuccess = {
                        viewModel.confirmParentalUnlockAndPlay(
                            onPlay = onPlay,
                            onNotice = { linkedChannelNotice = it }
                        )
                    },
                    theme = theme,
                    lang = lang
                )
            }

            // ─── INDICADOR MODAL MIENTRAS BUSCA / RESUELVE ENLACE DEL CANAL ───
            if (uiState.isMatchingChannel) {
                Dialog(
                    onDismissRequest = {},
                    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = themeSurface.copy(alpha = 0.95f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, themeAccent.copy(alpha = 0.4f)),
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .padding(24.dp)
                            .testTag("sports_matching_loader_dialog")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = themeAccent,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = Translator.translate("sports_searching_channel", lang),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ─── AVISO TEMPORAL FLOTANTE ──────────────────────────────────────
            AnimatedVisibility(
                visible = linkedChannelNotice != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = themeSurface.copy(alpha = 0.95f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, themeAccentLight),
                    shadowElevation = 10.dp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = themeAccentLight,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = linkedChannelNotice ?: "",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DIÁLOGO SELECTOR DE CANALES (MANDO TV / D-PAD COMPATIBLE)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun SportsChannelSelectionDialog(
    selectionState: com.example.interfaz.viewmodel.ChannelSelectionState,
    onSelect: (SportsChannelCandidate) -> Unit,
    onDismiss: () -> Unit,
    theme: AppThemeColors,
    lang: String = "es",
    isLockedChannel: (com.example.motor.db.IptvItem) -> Boolean = { false }
) {
    val themeBg = parseHexColor(theme.bg)
    val themeSurface = parseHexColor(theme.surface)
    val themeAccent = parseHexColor(theme.accent)
    val themeAccentLight = parseHexColor(theme.accentLight)
    val initialFocus = rememberTvInitialFocusRequester(delayMs = 80L)

    BackHandler {
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 480.dp, max = 620.dp)
                    .padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                color = themeSurface,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, themeAccentLight.copy(alpha = 0.5f)),
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Cabecera del diálogo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = themeAccentLight,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = Translator.translate("select_channel", lang),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${selectionState.event.homeTeam} vs ${selectionState.event.awayTeam} • ${selectionState.event.league}",
                                    color = Color.LightGray.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        TvIconButton(
                            onClick = onDismiss,
                            containerColor = Color.White.copy(alpha = 0.1f),
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) { isFocused ->
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = Translator.translate("close", lang),
                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = Translator.translate("channels_found_for_event", lang, selectionState.candidates.size),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Lista de canales candidatos
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = selectionState.candidates,
                            key = { index, candidate -> "${candidate.channel.dedupIdentityKey()}_$index" }
                        ) { index, candidate ->
                            var isFocused by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (isFocused) 2.dp else 1.dp,
                                        color = if (isFocused) TV_FOCUSED_BORDER else Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .tvClickable(
                                        shape = RoundedCornerShape(10.dp),
                                        focusedBackgroundColor = TV_FOCUSED_BG,
                                        unfocusedBackgroundColor = Color.White.copy(alpha = 0.05f),
                                        focusRequester = if (index == 0) initialFocus else null,
                                        onFocusChange = { isFocused = it },
                                        onClick = { onSelect(candidate) }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (!candidate.channel.logo.isNullOrBlank()) {
                                            AsyncImage(
                                                model = candidate.channel.logo,
                                                contentDescription = candidate.channel.name,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.1f)),
                                                contentScale = ContentScale.Fit
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = null,
                                                tint = if (isFocused) TV_FOCUSED_TEXT else themeAccentLight,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                        }

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = candidate.channel.name,
                                                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (isLockedChannel(candidate.channel)) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Lock,
                                                        contentDescription = "Parental lock",
                                                        tint = if (isFocused) TV_FOCUSED_TEXT else Color(0xFFF59E0B),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (candidate.channel.group.isNotBlank()) {
                                                    Text(
                                                        text = candidate.channel.group,
                                                        color = if (isFocused) TV_FOCUSED_TEXT.copy(alpha = 0.8f) else Color.LightGray.copy(alpha = 0.7f),
                                                        fontSize = 11.sp,
                                                        maxLines = 1
                                                    )
                                                }
                                                if (candidate.confidence == com.example.motor.sports.matcher.MatchConfidence.HIGH) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "• ${Translator.translate("recommended", lang)}",
                                                        color = if (isFocused) TV_FOCUSED_TEXT else Color(0xFF10B981),
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isFocused) Color.White.copy(alpha = 0.25f) else themeAccent)
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = Translator.translate("play", lang),
                                                color = Color.White,
                                                fontSize = 11.5.sp,
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

// ─────────────────────────────────────────────────────────────────────────────
// 1. CABECERA LIMPIA Y ELEGANTE
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SportsHeader(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    liveCount: Int,
    theme: AppThemeColors,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    lang: String = "es"
) {
    val themeAccent = parseHexColor(theme.accent)
    val themeSurface = parseHexColor(theme.surface)
    val themeAccentLight = parseHexColor(theme.accentLight)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Bloque Izquierdo: Volver + Título Deportes
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvIconButton(
                onClick = onBack,
                containerColor = themeSurface.copy(alpha = 0.75f),
                focusRequester = focusRequester,
                shape = CircleShape,
                modifier = Modifier.size(42.dp)
            ) { isFocused ->
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Translator.translate("back", lang),
                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Translator.translate("sports", lang),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(themeAccent)
                    )
                }
                Text(
                    text = Translator.translate("sports_subtitle", lang),
                    color = Color.LightGray.copy(alpha = 0.75f),
                    fontSize = 11.5.sp
                )
            }
        }

        // Bloque Derecho: Badge En Directo + Botón Refrescar
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (liveCount > 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = Translator.translate("live_count_badge", lang, liveCount),
                            color = Color(0xFFFF6B6B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Botón de refresco manual
            TvIconButton(
                onClick = onRefresh,
                containerColor = themeSurface.copy(alpha = 0.75f),
                shape = CircleShape,
                modifier = Modifier.size(42.dp)
            ) { isFocused ->
                if (isRefreshing) {
                    CircularProgressIndicator(
                        color = if (isFocused) TV_FOCUSED_TEXT else themeAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = Translator.translate("refresh", lang),
                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. CATEGORÍAS DEPORTIVAS (Horizontal)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SportsCategoriesRow(
    selectedSport: SportsSport,
    onSportSelected: (SportsSport) -> Unit,
    getSportCount: (SportsSport) -> Int,
    theme: AppThemeColors,
    lang: String = "es"
) {
    val themeAccent = parseHexColor(theme.accent)
    val themeAccentLight = parseHexColor(theme.accentLight)
    val themeSurface = parseHexColor(theme.surface)

    val displayedSports = remember {
        listOf(
            SportsSport.FOOTBALL,
            SportsSport.FORMULA1,
            SportsSport.MOTOS,
            SportsSport.BASKETBALL,
            SportsSport.BOXING,
            SportsSport.TENNIS,
            SportsSport.UFC,
            SportsSport.RUGBY,
            SportsSport.BASEBALL,
            SportsSport.GOLF,
            SportsSport.OTHER
        )
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(displayedSports, key = { it.id }) { sport ->
            val isSelected = sport == selectedSport
            var isFocused by remember { mutableStateOf(false) }
            val count = getSportCount(sport)

            val unselectedBg = themeSurface.copy(alpha = 0.75f)
            val selectedBg = themeAccent.copy(alpha = 0.95f)
            val borderColor = if (isFocused) {
                TV_FOCUSED_BORDER
            } else if (isSelected) {
                themeAccentLight
            } else {
                Color.White.copy(alpha = 0.12f)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .tvClickable(
                        shape = RoundedCornerShape(12.dp),
                        focusedBackgroundColor = TV_FOCUSED_BG,
                        unfocusedBackgroundColor = if (isSelected) selectedBg else unselectedBg,
                        onFocusChange = { isFocused = it },
                        onClick = { onSportSelected(sport) }
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = getSportIcon(sport)
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                    } else {
                        Text(
                            text = sport.emoji,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                    }

                    Text(
                        text = "${getSportLabel(sport, lang)} ($count)",
                        color = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color.White else Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected || isFocused) FontWeight.ExtraBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. FILTROS (TODOS, LIVE, HOY, MAÑANA, PRÓXIMAMENTE ESTA SEMANA)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SportsFiltersRow(
    selectedFilter: SportsFilter,
    onFilterSelected: (SportsFilter) -> Unit,
    liveCount: Int,
    todayCount: Int,
    tomorrowCount: Int,
    weekCount: Int,
    theme: AppThemeColors,
    lang: String = "es"
) {
    val themeAccent = parseHexColor(theme.accent)
    val themeAccentLight = parseHexColor(theme.accentLight)
    val themeSurface = parseHexColor(theme.surface)
    val filters = SportsFilter.entries

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(filters, key = { it.id }) { filter ->
            val isSelected = filter == selectedFilter
            var isFocused by remember { mutableStateOf(false) }

            val count = when (filter) {
                SportsFilter.ALL -> liveCount + todayCount + tomorrowCount + weekCount
                SportsFilter.LIVE -> liveCount
                SportsFilter.TODAY -> todayCount
                SportsFilter.TOMORROW -> tomorrowCount
                SportsFilter.THIS_WEEK -> weekCount
            }

            val bg = if (isSelected) themeAccent.copy(alpha = 0.9f) else themeSurface.copy(alpha = 0.55f)
            val borderCol = if (isFocused) {
                TV_FOCUSED_BORDER
            } else if (isSelected) {
                themeAccentLight.copy(alpha = 0.8f)
            } else {
                Color.White.copy(alpha = 0.08f)
            }

            Box(
                modifier = Modifier
                    .tvCardFocusable(
                        shape = RoundedCornerShape(22.dp),
                        scaleFocused = 1.05f,
                        elevationFocused = 4.dp,
                        unfocusedBorderColor = borderCol,
                        onFocusChange = { isFocused = it },
                        onClick = { onFilterSelected(filter) }
                    )
                    .background(bg)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (filter == SportsFilter.LIVE) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                    }

                    Text(
                        text = when (filter) {
                            SportsFilter.ALL -> Translator.translate("all", lang)
                            SportsFilter.LIVE -> Translator.translate("live", lang)
                            SportsFilter.TODAY -> Translator.translate("today", lang)
                            SportsFilter.TOMORROW -> Translator.translate("tomorrow", lang)
                            SportsFilter.THIS_WEEK -> Translator.translate("this_week", lang)
                        },
                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                    )

                    if (count > 0) {
                        Spacer(modifier = Modifier.width(7.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 7.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = count.toString(),
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. SECCIONES: EN DIRECTO / PRÓXIMOS - HOY / MAÑANA / PRÓXIMAMENTE ESTA SEMANA
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SportsSectionsList(
    uiState: SportsUiState,
    onEventAction: (SportsEvent) -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier,
    lang: String = "es"
) {
    val showLive = (uiState.selectedFilter == SportsFilter.ALL || uiState.selectedFilter == SportsFilter.LIVE) && uiState.liveEvents.isNotEmpty()
    val showToday = (uiState.selectedFilter == SportsFilter.ALL || uiState.selectedFilter == SportsFilter.TODAY) && uiState.todayEvents.isNotEmpty()
    val showTomorrow = (uiState.selectedFilter == SportsFilter.ALL || uiState.selectedFilter == SportsFilter.TOMORROW) && uiState.tomorrowEvents.isNotEmpty()
    val showWeek = (uiState.selectedFilter == SportsFilter.ALL || uiState.selectedFilter == SportsFilter.THIS_WEEK) && uiState.weekEvents.isNotEmpty()

    val livePairs = remember(uiState.liveEvents) { uiState.liveEvents.chunked(2) }
    val todayPairs = remember(uiState.todayEvents) { uiState.todayEvents.chunked(2) }
    val tomorrowPairs = remember(uiState.tomorrowEvents) { uiState.tomorrowEvents.chunked(2) }
    val weekPairs = remember(uiState.weekEvents) { uiState.weekEvents.chunked(2) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        // SECCIÓN 1: 🔴 EN DIRECTO
        if (showLive) {
            item(key = "header_live") {
                SectionHeader(
                    title = Translator.translate("live_now", lang),
                    icon = Icons.Default.FiberManualRecord,
                    iconTint = Color(0xFFEF4444),
                    count = uiState.liveEvents.size,
                    theme = theme
                )
            }

            itemsIndexed(
                items = livePairs,
                key = { index, pair -> "live_${pair[0].id}_${pair.getOrNull(1)?.id ?: "single"}_$index" }
            ) { _, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SportsEventCard(
                        event = pair[0],
                        onAction = { onEventAction(pair[0]) },
                        theme = theme,
                        modifier = Modifier.weight(1f),
                        lang = lang
                    )
                    if (pair.size > 1) {
                        SportsEventCard(
                            event = pair[1],
                            onAction = { onEventAction(pair[1]) },
                            theme = theme,
                            modifier = Modifier.weight(1f),
                            lang = lang
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // SECCIÓN 2: ⏱️ PRÓXIMOS - HOY
        if (showToday) {
            item(key = "header_today") {
                SectionHeader(
                    title = Translator.translate("upcoming_today", lang),
                    icon = Icons.Default.Schedule,
                    iconTint = parseHexColor(theme.accentLight),
                    count = uiState.todayEvents.size,
                    theme = theme
                )
            }

            itemsIndexed(
                items = todayPairs,
                key = { index, pair -> "today_${pair[0].id}_${pair.getOrNull(1)?.id ?: "single"}_$index" }
            ) { _, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SportsEventCard(
                        event = pair[0],
                        onAction = { onEventAction(pair[0]) },
                        theme = theme,
                        modifier = Modifier.weight(1f),
                        lang = lang
                    )
                    if (pair.size > 1) {
                        SportsEventCard(
                            event = pair[1],
                            onAction = { onEventAction(pair[1]) },
                            theme = theme,
                            modifier = Modifier.weight(1f),
                            lang = lang
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // SECCIÓN 3: 📅 MAÑANA
        if (showTomorrow) {
            item(key = "header_tomorrow") {
                SectionHeader(
                    title = Translator.translate("tomorrow_events", lang),
                    icon = Icons.Default.CalendarToday,
                    iconTint = parseHexColor(theme.accentLight),
                    count = uiState.tomorrowEvents.size,
                    theme = theme
                )
            }

            itemsIndexed(
                items = tomorrowPairs,
                key = { index, pair -> "tomorrow_${pair[0].id}_${pair.getOrNull(1)?.id ?: "single"}_$index" }
            ) { _, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SportsEventCard(
                        event = pair[0],
                        onAction = { onEventAction(pair[0]) },
                        theme = theme,
                        modifier = Modifier.weight(1f),
                        lang = lang
                    )
                    if (pair.size > 1) {
                        SportsEventCard(
                            event = pair[1],
                            onAction = { onEventAction(pair[1]) },
                            theme = theme,
                            modifier = Modifier.weight(1f),
                            lang = lang
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // SECCIÓN 4: 🗓️ PRÓXIMAMENTE ESTA SEMANA
        if (showWeek) {
            item(key = "header_week") {
                SectionHeader(
                    title = Translator.translate("upcoming_week", lang),
                    icon = Icons.Default.DateRange,
                    iconTint = parseHexColor(theme.accentLight),
                    count = uiState.weekEvents.size,
                    theme = theme
                )
            }

            itemsIndexed(
                items = weekPairs,
                key = { index, pair -> "week_${pair[0].id}_${pair.getOrNull(1)?.id ?: "single"}_$index" }
            ) { _, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SportsEventCard(
                        event = pair[0],
                        onAction = { onEventAction(pair[0]) },
                        theme = theme,
                        modifier = Modifier.weight(1f),
                        lang = lang
                    )
                    if (pair.size > 1) {
                        SportsEventCard(
                            event = pair[1],
                            onAction = { onEventAction(pair[1]) },
                            theme = theme,
                            modifier = Modifier.weight(1f),
                            lang = lang
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    count: Int,
    theme: AppThemeColors
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(parseHexColor(theme.accent).copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = count.toString(),
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. TARJETA DE EVENTO (SportsEventCard)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SportsEventCard(
    event: SportsEvent,
    onAction: () -> Unit,
    theme: AppThemeColors,
    modifier: Modifier = Modifier,
    lang: String = "es"
) {
    var isFocused by remember { mutableStateOf(false) }

    val themeCard = parseHexColor(theme.card)
    val themeAccent = parseHexColor(theme.accent)
    val themeAccentLight = parseHexColor(theme.accentLight)

    val isLive = event.isLive || event.status == SportsEventStatus.LIVE
    val idleBorder = if (isLive) Color(0xFFEF4444).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.10f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .tvCardFocusable(
                shape = RoundedCornerShape(10.dp),
                scaleFocused = 1.03f,
                elevationFocused = 4.dp,
                unfocusedBackgroundColor = themeCard.copy(alpha = 0.65f),
                focusedBackgroundColor = themeAccent.copy(alpha = 0.30f),
                unfocusedBorderColor = idleBorder,
                focusedBorderColor = themeAccent,
                focusedBorderWidth = 1.5.dp,
                onFocusChange = { isFocused = it },
                onClick = onAction
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Fila 1: Competición + País + Emisora (Izquierda) • Estado/Hora (Derecha) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Competición + Logo + País + Emisora
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (!event.leagueLogo.isNullOrBlank()) {
                        AsyncImage(
                            model = event.leagueLogo,
                            contentDescription = event.league,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    } else {
                        Text(
                            text = getSportEmoji(SportsSport.fromId(event.sport)),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }

                    Text(
                        text = event.league.uppercase(),
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!event.country.isNullOrBlank()) {
                        Text(
                            text = " • ${event.country}",
                            color = Color.LightGray.copy(alpha = 0.65f),
                            fontSize = 9.5.sp,
                            maxLines = 1
                        )
                    }

                    if (!event.broadcaster.isNullOrBlank()) {
                        Text(
                            text = " • ${event.broadcaster}",
                            color = themeAccentLight,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Estado o Hora
                if (isLive) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (event.elapsedMinutes != null) "${event.elapsedMinutes}'" else event.statusText.ifEmpty { Translator.translate("live_caps", lang) },
                                color = Color(0xFFFF6B6B),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = themeAccentLight,
                                modifier = Modifier.size(9.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = event.getFormattedDateTime(),
                                color = themeAccentLight,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // ── Fila 2: Enfrentamiento Equipos (Centro) + Botón Ver (Derecha) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Enfrentamiento de equipos + marcador/hora central
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Equipo Local
                    TeamBlock(
                        name = event.homeTeam,
                        logoUrl = event.homeTeamLogo,
                        isHome = true,
                        modifier = Modifier.weight(1f)
                    )

                    // Marcador Central / VS
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isLive) Color(0xFFEF4444).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)
                            )
                            .border(
                                0.8.dp,
                                if (isLive) Color(0xFFEF4444).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLive && event.homeScore != null && event.awayScore != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${event.homeScore} : ${event.awayScore}",
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = Translator.translate("live_short", lang),
                                    color = Color(0xFFEF4444),
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = event.getFormattedTime(),
                                    color = themeAccentLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "VS",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Equipo Visitante
                    TeamBlock(
                        name = event.awayTeam,
                        logoUrl = event.awayTeamLogo,
                        isHome = false,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Botón "Ver" integrado a la derecha
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isFocused) themeAccent else themeAccent.copy(alpha = 0.85f))
                        .border(
                            0.8.dp,
                            if (isFocused) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.25f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = Translator.translate("watch", lang),
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = Translator.translate("watch", lang),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamBlock(
    name: String,
    logoUrl: String?,
    isHome: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isHome) Arrangement.End else Arrangement.Start
    ) {
        if (!isHome) {
            TeamLogoOrAvatar(logoUrl = logoUrl, name = name)
            Spacer(modifier = Modifier.width(5.dp))
        }

        Text(
            text = name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isHome) TextAlign.End else TextAlign.Start,
            modifier = Modifier.weight(1f, fill = false)
        )

        if (isHome) {
            Spacer(modifier = Modifier.width(5.dp))
            TeamLogoOrAvatar(logoUrl = logoUrl, name = name)
        }
    }
}

@Composable
private fun TeamLogoOrAvatar(
    logoUrl: String?,
    name: String
) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = name,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.toString()?.uppercase() ?: "•",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS PARA ICONOS Y LABELS DEPORTIVOS
// ─────────────────────────────────────────────────────────────────────────────
private fun getSportIcon(sport: SportsSport): ImageVector? {
    return when (sport) {
        SportsSport.FOOTBALL -> Icons.Default.SportsSoccer
        SportsSport.FORMULA1 -> Icons.Default.SportsMotorsports
        SportsSport.MOTOS -> Icons.Default.TwoWheeler
        SportsSport.BASKETBALL -> Icons.Default.SportsBasketball
        SportsSport.BOXING -> Icons.Default.SportsMma
        SportsSport.UFC -> Icons.Default.SportsMma
        SportsSport.TENNIS -> Icons.Default.SportsTennis
        SportsSport.GOLF -> Icons.Default.SportsGolf
        SportsSport.RUGBY -> Icons.Default.SportsRugby
        SportsSport.BASEBALL -> Icons.Default.SportsBaseball
        SportsSport.OTHER -> Icons.Default.Sports
    }
}

private fun getSportLabel(sport: SportsSport, lang: String = "es"): String {
    return when (sport) {
        SportsSport.FOOTBALL -> Translator.translate("football", lang)
        SportsSport.FORMULA1 -> Translator.translate("formula1", lang)
        SportsSport.MOTOS -> Translator.translate("motos", lang)
        SportsSport.BASKETBALL -> Translator.translate("basketball", lang)
        SportsSport.BOXING -> Translator.translate("boxing", lang)
        SportsSport.UFC -> Translator.translate("ufc", lang)
        SportsSport.TENNIS -> Translator.translate("tennis", lang)
        SportsSport.GOLF -> Translator.translate("golf", lang)
        SportsSport.RUGBY -> Translator.translate("rugby", lang)
        SportsSport.BASEBALL -> Translator.translate("baseball", lang)
        SportsSport.OTHER -> Translator.translate("other_sports", lang)
    }
}

private fun getSportEmoji(sport: SportsSport): String {
    return when (sport) {
        SportsSport.FOOTBALL -> "⚽"
        SportsSport.FORMULA1 -> "🏎"
        SportsSport.MOTOS -> "🏍"
        SportsSport.BASKETBALL -> "🏀"
        SportsSport.BOXING -> "🥊"
        SportsSport.UFC -> "🥋"
        SportsSport.TENNIS -> "🎾"
        SportsSport.GOLF -> "⛳"
        SportsSport.RUGBY -> "🏉"
        SportsSport.BASEBALL -> "⚾"
        SportsSport.OTHER -> "•••"
    }
}
