package com.example.interfaz.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.motor.player.TrackOption
import com.example.motor.subtitles.ExternalSubtitle
import com.example.motor.tmdb.TmdbService
import kotlinx.coroutines.delay

@Composable
fun SubtitlePickerDialog(
    visible: Boolean,
    playerViewModel: PlayerViewModel,
    title: String,
    mediaType: String,
    seriesTitle: String?,
    season: Int?,
    episode: Int?,
    src: String,
    langSetting: String,
    headers: Map<String, String>,
    onDismiss: () -> Unit,
    onCloseControls: () -> Unit = {}
) {
    if (!visible) return

    LaunchedEffect(visible) {
        if (visible) {
            playerViewModel.refreshTracks()
            if (mediaType == "movie" || mediaType == "series") {
                val isSeries = (mediaType == "series")
                val effectiveSeason = if (isSeries) (season ?: TmdbService.extractSeasonEpisode(title)?.season ?: 1) else null
                val effectiveEpisode = if (isSeries) (episode ?: TmdbService.extractSeasonEpisode(title)?.episode ?: 1) else null
                val effectiveTitle: String = if (isSeries) {
                    val base = if (!seriesTitle.isNullOrBlank()) seriesTitle else title
                    val cleanSeries = TmdbService.cleanSeriesName(base)
                    if (cleanSeries.isNotBlank()) cleanSeries else base
                } else {
                    val cleanMovie = TmdbService.cleanTitleAndExtractYear(title).title
                    if (cleanMovie.isNotBlank()) cleanMovie else title
                }
                playerViewModel.autoSearchSubtitlesIfNeeded(
                    title = effectiveTitle,
                    isSeries = isSeries,
                    season = effectiveSeason,
                    episode = effectiveEpisode,
                    videoUrl = src,
                    preferredLanguage = langSetting,
                    headers = headers
                )
            }
        }
    }

    val subtitleTracks by playerViewModel.subtitleTracks.collectAsState()
    val externalSubtitles by playerViewModel.externalSubtitles.collectAsState()
    val selectedExternalSubtitle by playerViewModel.selectedExternalSubtitle.collectAsState()
    val downloadingSubtitleId by playerViewModel.downloadingSubtitleId.collectAsState()
    val isSearchingSubtitles by playerViewModel.isSearchingSubtitles.collectAsState()
    val subtitleSearchError by playerViewModel.subtitleSearchError.collectAsState()
    val isSubtitleTranslating by playerViewModel.isTranslatingSubtitle.collectAsState()

    var subForActionDialog by remember { mutableStateOf<ExternalSubtitle?>(null) }

    val initialSearchQuery: String = remember(title, mediaType, seriesTitle) {
        if (mediaType == "series") {
            val base = if (!seriesTitle.isNullOrBlank()) seriesTitle else title
            val cleanSeries = TmdbService.cleanSeriesName(base)
            if (cleanSeries.isNotBlank()) cleanSeries else base
        } else {
            val cleanMovie = TmdbService.cleanTitleAndExtractYear(title).title
            if (cleanMovie.isNotBlank()) cleanMovie else title
        }
    }
    var searchQuery by remember(initialSearchQuery) { mutableStateOf(initialSearchQuery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtítulos", color = Color.White) },
        containerColor = Color(0xFF1E1E28),
        confirmButton = {
            TvButton(
                onClick = onDismiss,
                containerColor = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) { isFocused ->
                Text(
                    "Cerrar",
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Buscar en Internet", color = Color.Gray, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFB07FEE),
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color(0xFFB07FEE),
                            unfocusedLabelColor = Color.Gray
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                val isSeries = mediaType == "series"
                                val queryToUse = searchQuery.trim().ifBlank { initialSearchQuery }
                                val effectiveSeason = if (isSeries) (season ?: TmdbService.extractSeasonEpisode(title)?.season ?: 1) else null
                                val effectiveEpisode = if (isSeries) (episode ?: TmdbService.extractSeasonEpisode(title)?.episode ?: 1) else null
                                playerViewModel.searchSubtitles(
                                    title = queryToUse,
                                    isSeries = isSeries,
                                    season = effectiveSeason,
                                    episode = effectiveEpisode,
                                    videoUrl = src,
                                    preferredLanguage = langSetting,
                                    headers = headers,
                                    mediaType = mediaType
                                )
                            }
                        },
                        enabled = !isSearchingSubtitles,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF9B5FDE))
                    ) {
                        if (isSearchingSubtitles) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.White)
                        }
                    }
                }

                if (subtitleSearchError != null) {
                    Text(subtitleSearchError!!, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }

                if (isSearchingSubtitles) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFFB07FEE), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buscando subtítulos automáticos...", color = Color.Gray, fontSize = 13.sp)
                    }
                }

                // ─── SUBTITLE SYNCHRONIZATION (DELAY / ADVANCE) ───────
                val subOffsetMs by playerViewModel.subtitleOffsetMs.collectAsState()
                val offsetText = when {
                    subOffsetMs == 0L -> "0.0s (Sincronizado)"
                    subOffsetMs > 0L -> "+${subOffsetMs / 1000.0}s"
                    else -> "${subOffsetMs / 1000.0}s"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sincronización de subtítulos",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = offsetText,
                            color = if (subOffsetMs != 0L) Color(0xFFB07FEE) else Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvButton(
                            onClick = { playerViewModel.adjustSubtitleOffset(-1000L) },
                            containerColor = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(28.dp)
                        ) { isFocused ->
                            Text("-1s", fontSize = 11.sp, color = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                        }
                        TvButton(
                            onClick = { playerViewModel.adjustSubtitleOffset(-500L) },
                            containerColor = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(28.dp)
                        ) { isFocused ->
                            Text("-0.5s", fontSize = 11.sp, color = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                        }
                        TvButton(
                            onClick = { playerViewModel.resetSubtitleOffset() },
                            containerColor = if (subOffsetMs != 0L) Color(0xFFB07FEE).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(28.dp)
                        ) { isFocused ->
                            Text("0s", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                        }
                        TvButton(
                            onClick = { playerViewModel.adjustSubtitleOffset(500L) },
                            containerColor = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(28.dp)
                        ) { isFocused ->
                            Text("+0.5s", fontSize = 11.sp, color = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                        }
                        TvButton(
                            onClick = { playerViewModel.adjustSubtitleOffset(1000L) },
                            containerColor = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f).height(28.dp)
                        ) { isFocused ->
                            Text("+1s", fontSize = 11.sp, color = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val noneTrack = subtitleTracks.find { it.isNone } ?: TrackOption(
                    id = "none",
                    name = "Desactivar",
                    type = androidx.media3.common.C.TRACK_TYPE_TEXT,
                    groupIndex = -1,
                    trackIndex = -1,
                    isSelected = subtitleTracks.all { !it.isSelected } && selectedExternalSubtitle == null,
                    isNone = true
                )

                val isNoneSelected = (selectedExternalSubtitle == null) &&
                        (subtitleTracks.find { it.isNone }?.isSelected == true || subtitleTracks.filter { !it.isNone }.all { !it.isSelected })

                val streamTracks = subtitleTracks.filter {
                    !it.isNone &&
                    !it.id.startsWith("os_") && !it.id.startsWith("assrt_") && !it.id.startsWith("subcat_")
                }

                val onlineSubs = externalSubtitles

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1. None / Desactivar
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .tvClickable {
                                    playerViewModel.clearSelectedExternalSubtitle()
                                    playerViewModel.selectSubtitleTrack(noneTrack)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isNoneSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFFB07FEE),
                                    unselectedColor = Color.Gray
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Desactivar",
                                color = if (isNoneSelected) Color(0xFFB07FEE) else Color.White,
                                fontSize = 15.sp,
                                fontWeight = if (isNoneSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    // 2. Stream / List subtitles
                    if (streamTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Subtítulos del vídeo (${streamTracks.size})",
                                color = Color(0xFFB07FEE),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(streamTracks) { track ->
                            val isStreamSelected = selectedExternalSubtitle == null && track.isSelected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .tvClickable {
                                        playerViewModel.clearSelectedExternalSubtitle()
                                        playerViewModel.selectSubtitleTrack(track)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isStreamSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFFB07FEE),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = track.name,
                                    color = if (isStreamSelected) Color(0xFFB07FEE) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (isStreamSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // 3. Online subtitles found
                    if (onlineSubs.isNotEmpty()) {
                        item {
                            Text(
                                text = "Subtítulos encontrados (${onlineSubs.size})",
                                color = Color(0xFFB07FEE),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(onlineSubs) { sub ->
                            val isSubSelected = selectedExternalSubtitle?.id == sub.id
                            val isDownloading = downloadingSubtitleId == sub.id
                            val canTranslate = !sub.label.contains("Traducido") &&
                                    !sub.language.lowercase().startsWith(if (langSetting.lowercase().startsWith("ro")) "ro" else "es")

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .tvClickable {
                                        if (!isDownloading) {
                                            if (canTranslate) {
                                                subForActionDialog = sub
                                            } else {
                                                playerViewModel.selectExternalSubtitle(sub)
                                                onDismiss()
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isDownloading) {
                                    CircularProgressIndicator(
                                        color = Color(0xFFB07FEE),
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    RadioButton(
                                        selected = isSubSelected,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Color(0xFFB07FEE),
                                            unselectedColor = Color.Gray
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sub.label,
                                        color = if (isSubSelected) Color(0xFFB07FEE) else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSubSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = "Fuente: ${sub.source.uppercase()}${if (sub.isTranslated) " · Traducido" else ""}",
                                        color = Color.LightGray.copy(alpha = 0.6f),
                                        fontSize = 11.sp
                                    )
                                }
                                if (canTranslate) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF9B5FDE).copy(alpha = 0.22f),
                                        border = BorderStroke(0.8.dp, Color(0xFFB07FEE).copy(alpha = 0.5f)),
                                        modifier = Modifier.padding(start = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🌐 Traducir",
                                                color = Color(0xFFD4B3FF),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (streamTracks.isEmpty() && onlineSubs.isEmpty()) {
                        item {
                            Text(
                                text = "No hay otros subtítulos disponibles. ¡Busca arriba!",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    )

    // ─── DIÁLOGO DE ACCIÓN DE SUBTÍTULO PARA TV (TRADUCIR / ORIGINAL) ────────────
    if (subForActionDialog != null) {
        val targetSub = subForActionDialog!!
        val focusRequesterFirstBtn = remember { FocusRequester() }

        LaunchedEffect(targetSub.id) {
            delay(120L)
            try {
                focusRequesterFirstBtn.requestFocus()
            } catch (_: Exception) {}
        }

        AlertDialog(
            onDismissRequest = { subForActionDialog = null },
            containerColor = Color(0xFF1E1E28),
            title = {
                Column {
                    Text(
                        text = "Opciones de subtítulo",
                        color = Color.White,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = targetSub.label,
                        color = Color(0xFFB07FEE),
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Traducir a Español (foco por defecto para TV)
                    TvButton(
                        onClick = {
                            val toTranslate = targetSub
                            subForActionDialog = null
                            onDismiss()
                            onCloseControls()
                            playerViewModel.translateSubtitle(toTranslate, "es")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        containerColor = Color(0xFF2E7D32).copy(alpha = 0.60f),
                        focusedContainerColor = Color(0xFF2E7D32),
                        focusedBorderColor = Color(0xFF81C784),
                        shape = RoundedCornerShape(8.dp),
                        focusRequester = focusRequesterFirstBtn
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🇪🇸", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Traducir a Español",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }

                    // 2. Traducir a Rumano
                    TvButton(
                        onClick = {
                            val toTranslate = targetSub
                            subForActionDialog = null
                            onDismiss()
                            onCloseControls()
                            playerViewModel.translateSubtitle(toTranslate, "ro")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        containerColor = Color(0xFF0288D1).copy(alpha = 0.60f),
                        focusedContainerColor = Color(0xFF0288D1),
                        focusedBorderColor = Color(0xFF4FC3F7),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🇷🇴", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Traducir a Rumano",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }

                    // 3. Descargar Original
                    TvButton(
                        onClick = {
                            val toSelect = targetSub
                            subForActionDialog = null
                            onDismiss()
                            playerViewModel.selectExternalSubtitle(toSelect)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        containerColor = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Descargar Original (${targetSub.language.uppercase()})",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TvButton(
                    onClick = { subForActionDialog = null },
                    containerColor = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) { isFocused ->
                    Text(
                        text = "Cancelar",
                        color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        )
    }
}
