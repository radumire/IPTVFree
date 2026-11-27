package com.example.interfaz.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.Translator
import com.example.interfaz.AppThemeColors
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.rememberTvInitialFocusRequester
import com.example.interfaz.components.tvCardFocusable
import com.example.parseHexColor

internal data class HomeInterfaceOption(
    val id: String,
    val titleKey: String,
    val descKey: String
)

internal val HOME_INTERFACE_OPTIONS = listOf(
    HomeInterfaceOption("vivo_glass", "interface_vivo", "interface_vivo_desc"),
    HomeInterfaceOption("classic_tiles", "interface_classic", "interface_classic_desc"),
    HomeInterfaceOption("modern", "interface_modern", "interface_modern_desc"),
    HomeInterfaceOption("cinema_bar", "interface_cinema", "interface_cinema_desc"),
    HomeInterfaceOption("showcase", "interface_showcase", "interface_showcase_desc"),
    HomeInterfaceOption("live_stage", "interface_live_stage", "interface_live_stage_desc")
)

internal fun getInterfaceTitleKey(layoutId: String): String {
    return HOME_INTERFACE_OPTIONS.find { it.id == layoutId }?.titleKey ?: "interface_vivo"
}

@Composable
fun InterfaceSelectorFullScreen(
    selectedLayout: String,
    onSelectInterface: (String) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors,
    lang: String
) {
    val initialFocusRequester = rememberTvInitialFocusRequester()
    val effectiveSelected = remember(selectedLayout) {
        if (HOME_INTERFACE_OPTIONS.any { it.id == selectedLayout }) selectedLayout else "vivo_glass"
    }

    val themeBg = remember(theme) { parseHexColor(theme.bg) }
    val themeSurface = remember(theme) { parseHexColor(theme.surface) }
    val themeCard = remember(theme) { parseHexColor(theme.card) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }
    val themeAccentLight = remember(theme) { parseHexColor(theme.accentLight) }

    BackHandler(enabled = true) {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(themeBg)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header with Back Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvIconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("interface_selector_back")
            ) { isFocused ->
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Translator.translate("back", lang),
                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Translator.translate("interface_selector_title", lang),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = Translator.translate("interface_selector_subtitle", lang, HOME_INTERFACE_OPTIONS.size),
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 5 Interface Cards arranged in 2 landscape rows (3 on top, 2 on bottom)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val firstRow = HOME_INTERFACE_OPTIONS.take(3)
            val secondRow = HOME_INTERFACE_OPTIONS.drop(3)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                firstRow.forEach { option ->
                    InterfaceOptionCard(
                        option = option,
                        isSelected = effectiveSelected == option.id,
                        activeBadgeText = Translator.translate("interface_active_badge", lang),
                        title = Translator.translate(option.titleKey, lang),
                        description = Translator.translate(option.descKey, lang),
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        focusRequester = if (effectiveSelected == option.id) initialFocusRequester else null,
                        onClick = { onSelectInterface(option.id) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                secondRow.forEach { option ->
                    InterfaceOptionCard(
                        option = option,
                        isSelected = effectiveSelected == option.id,
                        activeBadgeText = Translator.translate("interface_active_badge", lang),
                        title = Translator.translate(option.titleKey, lang),
                        description = Translator.translate(option.descKey, lang),
                        themeAccent = themeAccent,
                        themeAccentLight = themeAccentLight,
                        themeCard = themeCard,
                        themeSurface = themeSurface,
                        focusRequester = if (effectiveSelected == option.id) initialFocusRequester else null,
                        onClick = { onSelectInterface(option.id) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun InterfaceOptionCard(
    option: HomeInterfaceOption,
    isSelected: Boolean,
    activeBadgeText: String,
    title: String,
    description: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color,
    themeSurface: Color,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .testTag("interface_option_${option.id}")
            .tvCardFocusable(
                shape = RoundedCornerShape(14.dp),
                scaleFocused = 1.03f,
                elevationFocused = 8.dp,
                unfocusedBackgroundColor = if (isSelected) themeAccent.copy(alpha = 0.16f) else themeSurface.copy(alpha = 0.78f),
                focusedBackgroundColor = TV_FOCUSED_BG,
                unfocusedBorderColor = if (isSelected) themeAccentLight else Color.White.copy(alpha = 0.14f),
                focusedBorderColor = themeAccent,
                focusedBorderWidth = 2.dp,
                focusRequester = focusRequester,
                onFocusChange = { isFocused = it },
                onClick = onClick
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Miniature wireframe preview of the layout
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .border(0.8.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            InterfaceMiniPreview(
                layoutId = option.id,
                themeAccent = themeAccent,
                themeAccentLight = themeAccentLight,
                themeCard = themeCard
            )

            if (isSelected) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(6.dp))
                        .background(themeAccent)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = activeBadgeText,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = activeBadgeText,
                        color = Color.White,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isFocused || isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                color = Color.LightGray.copy(alpha = 0.85f),
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun InterfaceMiniPreview(
    layoutId: String,
    themeAccent: Color,
    themeAccentLight: Color,
    themeCard: Color
) {
    val blockColor = themeCard.copy(alpha = 0.9f)
    val accentBlock = themeAccent.copy(alpha = 0.45f)
    val softStroke = Color.White.copy(alpha = 0.16f)

    when (layoutId) {
        "vivo_glass" -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(8.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentBlock)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp)).background(accentBlock).border(0.6.dp, themeAccentLight, RoundedCornerShape(4.dp)))
                        Box(Modifier.fillMaxWidth().weight(0.6f).clip(RoundedCornerShape(4.dp)).background(blockColor))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(blockColor))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(blockColor))
                }
            }
        }
        "classic_tiles" -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.width(56.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(accentBlock))
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(accentBlock))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(blockColor))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(blockColor))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(blockColor))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor))
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor))
                }
            }
        }
        "modern" -> {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier.width(30.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(4) { idx ->
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(2.dp)).background(if (idx == 0) themeAccent else softStroke))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(accentBlock))
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) {
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor))
                        }
                    }
                }
            }
        }
        "cinema_bar" -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(6) { idx ->
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (idx == 2) accentBlock else blockColor))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    repeat(4) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
        "showcase" -> {
            // 5th Interface: Showcase (top clock + 3 tabs, left tall Live TV + right 2x2 grid)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(34.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(softStroke))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) {
                            Box(Modifier.width(20.dp).height(7.dp).clip(RoundedCornerShape(3.dp)).background(accentBlock))
                        }
                    }
                    Spacer(Modifier.width(26.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.36f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(accentBlock)
                            .border(0.8.dp, themeAccentLight, RoundedCornerShape(5.dp))
                    )
                    Column(
                        modifier = Modifier.weight(0.64f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().weight(0.55f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                        }
                        Row(Modifier.fillMaxWidth().weight(0.45f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(blockColor).border(0.6.dp, softStroke, RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
        else -> {
            // 6th Interface: Live Stage (top 7-tab bar, left channel list, right live video preview, bottom-right clock)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    repeat(7) { idx ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (idx == 0) accentBlock else blockColor)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.36f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(blockColor)
                            .padding(3.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(5) { rowIdx ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (rowIdx == 0) accentBlock else softStroke)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(0.64f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(accentBlock)
                            .border(0.8.dp, themeAccentLight, RoundedCornerShape(5.dp))
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(Modifier.width(34.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(softStroke))
                    Box(Modifier.width(34.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(softStroke))
                }
            }
        }
    }
}
