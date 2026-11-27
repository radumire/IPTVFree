package com.example.interfaz.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.Translator
import com.example.parseHexColor
import com.example.interfaz.AppThemeColors
import com.example.interfaz.viewmodel.SettingsViewModel
import com.example.interfaz.THEMES_LIST
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.tvCardFocusable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT
import com.example.interfaz.components.TV_FOCUSED_BORDER
import com.example.interfaz.components.TvQrScannerDialog
import com.example.interfaz.ParentalPinSettingsCard
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    theme: AppThemeColors,
    onFullScreenChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val currentProfile by viewModel.currentProfile.collectAsStateWithLifecycle()
    val settingsObj by viewModel.settings.collectAsStateWithLifecycle()
    val lang = settingsObj.lang

    var activeCategory by remember { mutableStateOf("general") }
    val activeCategoryFocusRequester = remember { FocusRequester() }
    
    var activeManageCategoriesType by remember { mutableStateOf<String?>(null) } // "tv", "movies", "series"
    var showInterfaceSelector by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }

    // Capture back button to return to Home when not in full-screen subpages
    androidx.activity.compose.BackHandler(enabled = activeManageCategoriesType == null && !showInterfaceSelector) {
        onBack()
    }

    LaunchedEffect(activeManageCategoriesType, showInterfaceSelector) {
        val isOverlayOpen = activeManageCategoriesType != null || showInterfaceSelector
        onFullScreenChange(isOverlayOpen)
        if (!isOverlayOpen) {
            delay(120L)
            try {
                activeCategoryFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onFullScreenChange(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ensureCategoriesLoaded()
    }

    val liveChannelsMap by viewModel.liveChannels.collectAsStateWithLifecycle()
    val moviesPrecomputed by viewModel.precomputedMoviesData.collectAsStateWithLifecycle()
    val seriesPrecomputed by viewModel.precomputedSeriesData.collectAsStateWithLifecycle()

    val tvCategories = remember(liveChannelsMap) { liveChannelsMap.keys.sorted() }
    val movieCategories = remember(moviesPrecomputed) {
        val groups = moviesPrecomputed?.groups ?: emptyList()
        groups.filter { it != "Todos" && it != "Favoritos" }
    }
    val seriesCategories = remember(seriesPrecomputed) {
        val groups = seriesPrecomputed?.groups ?: emptyList()
        groups.filter { it != "Todos" && it != "Favoritos" }
    }

    if (showInterfaceSelector) {
        InterfaceSelectorFullScreen(
            selectedLayout = settingsObj.homeLayout,
            onSelectInterface = { selectedLayout ->
                viewModel.updateCurrentProfileField(settings = settingsObj.copy(homeLayout = selectedLayout))
                showInterfaceSelector = false
            },
            onBack = { showInterfaceSelector = false },
            theme = theme,
            lang = lang
        )
        return
    }

    if (activeManageCategoriesType != null) {
        val currentType = activeManageCategoriesType!!
        val title = when (currentType) {
            "tv" -> when (lang) {
                "ro" -> "Gestionează Categorii Canale TV"
                "en" -> "Manage TV Channels Categories"
                else -> "Gestionar Categorías Canales TV"
            }
            "movies" -> when (lang) {
                "ro" -> "Gestionează Categorii Filme VOD"
                "en" -> "Manage VOD Movies Categories"
                else -> "Gestionar Categorías Películas VOD"
            }
            else -> when (lang) {
                "ro" -> "Gestionează Categorii Seriale"
                "en" -> "Manage Series Categories"
                else -> "Gestionar Categorías Series"
            }
        }
        val allCats = when (currentType) {
            "tv" -> tvCategories
            "movies" -> movieCategories
            else -> seriesCategories
        }
        val hiddenCats = when (currentType) {
            "tv" -> settingsObj.hiddenTvCategories
            "movies" -> settingsObj.hiddenMovieCategories
            else -> settingsObj.hiddenSeriesCategories
        }

        ManageCategoriesFullScreen(
            title = title,
            allCategories = allCats,
            hiddenCategories = hiddenCats,
            onSave = { hidden ->
                val updatedSettings = when (currentType) {
                    "tv" -> settingsObj.copy(hiddenTvCategories = hidden)
                    "movies" -> settingsObj.copy(hiddenMovieCategories = hidden)
                    else -> settingsObj.copy(hiddenSeriesCategories = hidden)
                }
                viewModel.updateCurrentProfileField(settings = updatedSettings)
                activeManageCategoriesType = null
            },
            onBack = { activeManageCategoriesType = null },
            theme = theme,
            lang = lang
        )
        return
    }

    // For clear cache feedback
    val coroutineScope = rememberCoroutineScope()
    var showCacheClearedMessage by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(parseHexColor(theme.bg))
    ) {
        // ─── LEFT SIDEBAR (SETTINGS CATEGORIES) ──────────────────────────────────
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(parseHexColor(theme.surface).copy(alpha = 0.5f))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = Translator.translate("system_settings", lang),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
            
            val categories = listOf(
                Triple("general", Translator.translate("general", lang), Icons.Default.Tune),
                Triple("interface", Translator.translate("home_interface", lang), Icons.Default.Dashboard),
                Triple("categories", Translator.translate("categories", lang), Icons.Default.Category),
                Triple("themes", Translator.translate("themes", lang), Icons.Default.ColorLens),
                Triple("subtitles", Translator.translate("subtitles", lang), Icons.Default.Subtitles),
                Triple("sync", if (lang == "es") "Sincronizar TV" else if (lang == "ro") "Sincronizare TV" else "TV Sync", Icons.Default.QrCode),
                Triple("parental", Translator.translate("parental", lang), Icons.Default.Lock),
                Triple("player", Translator.translate("player", lang), Icons.Default.PlayCircle),
                Triple("diagnostic", Translator.translate("diagnostic", lang), Icons.Default.Info),
                Triple("cache", Translator.translate("cache", lang), Icons.Default.DeleteSweep)
            )

            categories.forEachIndexed { index, (catId, catLabel, catIcon) ->
                val isSelected = activeCategory == catId
                var isCatFocused by remember { mutableStateOf(false) }

                val tc = if (isCatFocused) TV_FOCUSED_TEXT
                    else if (isSelected) Color.White
                    else Color.LightGray

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_category_$catId")
                        .tvCardFocusable(
                            shape = RoundedCornerShape(8.dp),
                            scaleFocused = 1.03f,
                            elevationFocused = 6.dp,
                            unfocusedBackgroundColor = if (isSelected) parseHexColor(theme.accent).copy(alpha = 0.60f) else parseHexColor(theme.card).copy(alpha = 0.55f),
                            focusedBackgroundColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                            unfocusedBorderColor = if (isSelected) parseHexColor(theme.accentLight).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f),
                            focusedBorderColor = parseHexColor(theme.accent),
                            focusRequester = if (isSelected) activeCategoryFocusRequester else null,
                            onFocusChange = { isCatFocused = it },
                            onClick = {
                                activeCategory = catId
                                if (catId == "sync") {
                                    showSyncDialog = true
                                } else if (catId == "interface") {
                                    showInterfaceSelector = true
                                }
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = catIcon,
                        contentDescription = catLabel,
                        tint = tc,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = catLabel,
                        color = tc,
                        fontSize = 13.sp,
                        fontWeight = if (isCatFocused || isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Color.White.copy(alpha = 0.1f))
        )

        // ─── RIGHT CONTENT PANE ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            when (activeCategory) {
                "general" -> {
                    SettingsHeader(title = Translator.translate("general_settings", lang), subtitle = Translator.translate("general_desc", lang))
                    
                    SettingsSelectorRow(
                        title = Translator.translate("app_lang", lang),
                        subtitle = Translator.translate("app_lang_desc", lang),
                        selectedValue = settingsObj.lang,
                        options = listOf("es" to "Español", "en" to "English", "ro" to "Română"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(lang = it)) },
                        theme = theme
                    )

                    SettingsSelectorRow(
                        title = Translator.translate("time_format", lang),
                        subtitle = Translator.translate("time_format_desc", lang),
                        selectedValue = settingsObj.timeFormat,
                        options = listOf("24h" to "24 Horas", "12h" to "12 Horas"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(timeFormat = it)) },
                        theme = theme
                    )

                    SettingsToggleRow(
                        title = Translator.translate("auto_update", lang),
                        subtitle = Translator.translate("auto_update_desc", lang),
                        checked = settingsObj.autoUpdate,
                        onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(autoUpdate = it)) },
                        theme = theme
                    )

                    SettingsToggleRow(
                        title = Translator.translate("notifications", lang),
                        subtitle = Translator.translate("notifications_desc", lang),
                        checked = settingsObj.notifications,
                        onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(notifications = it)) },
                        theme = theme
                    )
                }

                "interface" -> {
                    SettingsHeader(
                        title = Translator.translate("interface_selector_title", lang),
                        subtitle = Translator.translate("interface_selector_subtitle", lang, HOME_INTERFACE_OPTIONS.size)
                    )

                    val activeOption = HOME_INTERFACE_OPTIONS.find { it.id == settingsObj.homeLayout } ?: HOME_INTERFACE_OPTIONS.first()
                    val activeInterfaceName = Translator.translate(activeOption.titleKey, lang)
                    val activeInterfaceDesc = Translator.translate(activeOption.descKey, lang)

                    SettingsActionRow(
                        title = activeInterfaceName,
                        subtitle = activeInterfaceDesc,
                        buttonText = Translator.translate("change", lang),
                        onClick = { showInterfaceSelector = true },
                        theme = theme,
                        buttonModifier = Modifier.testTag("settings_interface_button")
                    )
                }

                "sync" -> {
                    LaunchedEffect(Unit) {
                        showSyncDialog = true
                    }

                    SettingsHeader(
                        title = if (lang == "es") "Sincronización TV" else if (lang == "ro") "Sincronizare TV" else "TV Sync",
                        subtitle = if (lang == "es") "Abre la pantalla flotante para ver el Código QR y la dirección IP de sincronización." else "Open the floating screen to view the QR Code and local sync IP address."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface).copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, parseHexColor(theme.accent).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = parseHexColor(theme.accent),
                                modifier = Modifier.size(56.dp)
                            )

                            Text(
                                text = if (lang == "es") "Pantalla Flotante de Sincronización TV" else "TV Sync Floating Window",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = if (lang == "es") "Usa el Código QR para escanear desde tu móvil o ingresa la IP para transferir listas directamente a esta Smart TV." else "Use the QR Code to scan from your phone or enter the IP to transfer lists directly to this Smart TV.",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            TvButton(
                                onClick = { showSyncDialog = true },
                                containerColor = parseHexColor(theme.accent).copy(alpha = 0.15f),
                                unfocusedBorderColor = parseHexColor(theme.accent).copy(alpha = 0.40f),
                                focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                                focusedBorderColor = parseHexColor(theme.accent),
                                shape = RoundedCornerShape(10.dp)
                            ) { isFocused ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.QrCode, contentDescription = null, tint = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                                    Text(
                                        text = if (lang == "es") "ABRIR PANTALLA FLOTANTE DE SINCRONIZACIÓN" else "OPEN FLOATING SYNC SCREEN",
                                        color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                "categories" -> {
                    SettingsHeader(
                        title = Translator.translate("categories_settings", lang),
                        subtitle = Translator.translate("categories_desc", lang)
                    )

                    SettingsActionRow(
                        title = when (lang) {
                            "ro" -> "Categorii Canale TV"
                            "en" -> "TV Channels Categories"
                            else -> "Categorías Canales TV"
                        },
                        subtitle = when (lang) {
                            "ro" -> "Alege ce categorii sau țări de canale TV să fie afișate sau ascunse"
                            "en" -> "Choose which TV channel categories or countries to show or hide"
                            else -> "Elige qué categorías o países de canales de TV mostrar u ocultar"
                        },
                        buttonText = when (lang) {
                            "ro" -> "Gestionează"
                            "en" -> "Manage"
                            else -> "Gestionar"
                        },
                        onClick = { activeManageCategoriesType = "tv" },
                        theme = theme
                    )

                    SettingsActionRow(
                        title = when (lang) {
                            "ro" -> "Categorii Filme VOD"
                            "en" -> "VOD Movies Categories"
                            else -> "Categorías Películas VOD"
                        },
                        subtitle = when (lang) {
                            "ro" -> "Alege ce categorii de filme VOD să fie afișate sau ascunse"
                            "en" -> "Choose which VOD movie categories to show or hide"
                            else -> "Elige qué categorías de películas VOD mostrar u ocultar"
                        },
                        buttonText = when (lang) {
                            "ro" -> "Gestionează"
                            "en" -> "Manage"
                            else -> "Gestionar"
                        },
                        onClick = { activeManageCategoriesType = "movies" },
                        theme = theme
                    )

                    SettingsActionRow(
                        title = when (lang) {
                            "ro" -> "Categorii Seriale"
                            "en" -> "Series Categories"
                            else -> "Categorías Series"
                        },
                        subtitle = when (lang) {
                            "ro" -> "Alege ce categorii de seriale să fie afișate sau ascunse"
                            "en" -> "Choose which series categories to show or hide"
                            else -> "Elige qué categorías de series mostrar u ocultar"
                        },
                        buttonText = when (lang) {
                            "ro" -> "Gestionează"
                            "en" -> "Manage"
                            else -> "Gestionar"
                        },
                        onClick = { activeManageCategoriesType = "series" },
                        theme = theme
                    )
                }

                "player" -> {
                    SettingsHeader(title = Translator.translate("player_settings", lang), subtitle = Translator.translate("player_desc", lang))
                    
                    SettingsSelectorRow(
                        title = Translator.translate("video_engine", lang),
                        subtitle = Translator.translate("video_engine_desc", lang),
                        selectedValue = settingsObj.player,
                        options = listOf("Player integrado" to "Integrado (ExoPlayer)", "VLC Player" to "VLC Player", "Reproductor Externo" to "Externo (Otros)"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(player = it)) },
                        theme = theme
                    )

                    SettingsSelectorRow(
                        title = Translator.translate("video_decoding", lang),
                        subtitle = Translator.translate("video_decoding_desc", lang),
                        selectedValue = settingsObj.decode,
                        options = listOf("Automático" to "Auto", "Hardware (HW+)" to "Hardware (HW+)", "Software (SW)" to "Software (SW)"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(decode = it)) },
                        theme = theme
                    )

                    SettingsSelectorRow(
                        title = Translator.translate("aspect_ratio", lang),
                        subtitle = Translator.translate("aspect_ratio_desc", lang),
                        selectedValue = settingsObj.ratio,
                        options = listOf("Ajustar" to "Ajustar", "Rellenar" to "Rellenar", "16:9" to "16:9", "4:3" to "4:3"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(ratio = it)) },
                        theme = theme
                    )

                    SettingsSelectorRow(
                        title = Translator.translate("stream_quality", lang),
                        subtitle = Translator.translate("stream_quality_desc", lang),
                        selectedValue = settingsObj.quality,
                        options = listOf("Auto" to "Auto", "1080p" to "1080p (FHD)", "720p" to "720p (HD)", "480p" to "480p (SD)"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(quality = it)) },
                        theme = theme
                    )

                    SettingsSelectorRow(
                        title = Translator.translate("connection_timeout", lang),
                        subtitle = Translator.translate("connection_timeout_desc", lang),
                        selectedValue = settingsObj.timeout,
                        options = listOf("5s" to "5 segundos", "10s" to "10 segundos", "15s" to "15 segundos", "30s" to "30 segundos"),
                        onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(timeout = it)) },
                        theme = theme
                    )

                    SettingsToggleRow(
                        title = Translator.translate("skip_silence", lang),
                        subtitle = Translator.translate("skip_silence_desc", lang),
                        checked = settingsObj.skipSilence,
                        onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(skipSilence = it)) },
                        theme = theme
                    )

                    SettingsToggleRow(
                        title = Translator.translate("direct_fullscreen", lang),
                        subtitle = Translator.translate("direct_fullscreen_desc", lang),
                        checked = settingsObj.autoFullscreen,
                        onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(autoFullscreen = it)) },
                        theme = theme
                    )
                }

                "subtitles" -> {
                    SettingsHeader(title = Translator.translate("sub_settings", lang), subtitle = Translator.translate("sub_desc", lang))
                    
                    SettingsToggleRow(
                        title = Translator.translate("enable_subs", lang),
                        subtitle = Translator.translate("enable_subs_desc", lang),
                        checked = settingsObj.subtitles,
                        onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(subtitles = it)) },
                        theme = theme
                    )

                    if (settingsObj.subtitles) {
                        SettingsSelectorRow(
                            title = Translator.translate("pref_lang", lang),
                            subtitle = Translator.translate("pref_lang_desc", lang),
                            selectedValue = settingsObj.subtitleLang,
                            options = listOf("Español" to "Español", "English" to "English", "Français" to "Français", "Deutsch" to "Deutsch"),
                            onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(subtitleLang = it)) },
                            theme = theme
                        )

                        SettingsSelectorRow(
                            title = Translator.translate("font_size", lang),
                            subtitle = Translator.translate("font_size_desc", lang),
                            selectedValue = settingsObj.subtitleSize,
                            options = listOf("Pequeño" to "Pequeño", "Normal" to "Normal", "Grande" to "Grande", "Muy Grande" to "Muy Grande"),
                            onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(subtitleSize = it)) },
                            theme = theme
                        )

                        SettingsSelectorRow(
                            title = Translator.translate("font_color", lang),
                            subtitle = Translator.translate("font_color_desc", lang),
                            selectedValue = settingsObj.subtitleColor,
                            options = listOf("Blanco" to "Blanco", "Amarillo" to "Amarillo", "Verde" to "Verde", "Cyan" to "Celeste / Cyan"),
                            onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(subtitleColor = it)) },
                            theme = theme
                        )

                        SettingsToggleRow(
                            title = Translator.translate("translucent_bg", lang),
                            subtitle = Translator.translate("translucent_bg_desc", lang),
                            checked = settingsObj.subtitleBg,
                            onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(subtitleBg = it)) },
                            theme = theme
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SubtitleApiKeyCard(
                        title = Translator.translate("opensubtitles_key", lang),
                        description = Translator.translate("opensubtitles_desc", lang),
                        currentValue = settingsObj.openSubtitlesKey,
                        onSaveKey = { newKey ->
                            viewModel.updateCurrentProfileField(settings = settingsObj.copy(openSubtitlesKey = newKey))
                            com.example.motor.subtitles.OpenSubtitlesProvider.setGlobalApiKey(newKey.takeIf { it.isNotBlank() })
                        },
                        lang = lang,
                        theme = theme
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SubtitleApiKeyCard(
                        title = Translator.translate("assrt_token", lang),
                        description = Translator.translate("assrt_desc", lang),
                        currentValue = settingsObj.assrtToken,
                        onSaveKey = { newToken ->
                            viewModel.updateCurrentProfileField(settings = settingsObj.copy(assrtToken = newToken))
                            com.example.motor.subtitles.AssrtSubtitleProvider.setGlobalToken(newToken.takeIf { it.isNotBlank() })
                        },
                        lang = lang,
                        theme = theme
                    )
                }

                "parental" -> {
                    SettingsHeader(title = Translator.translate("parental", lang), subtitle = Translator.translate("age_restriction_desc", lang))
                    
                    SettingsToggleRow(
                        title = Translator.translate("age_restriction", lang),
                        subtitle = Translator.translate("age_restriction_desc", lang),
                        checked = settingsObj.parentalEnabled,
                        onCheckedChange = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(parentalEnabled = it)) },
                        theme = theme
                    )

                    if (settingsObj.parentalEnabled) {
                        SettingsSelectorRow(
                            title = Translator.translate("max_rating", lang),
                            subtitle = Translator.translate("max_rating_desc", lang),
                            selectedValue = settingsObj.parentalRating,
                            options = listOf("Apto todos" to "Apto todos", "12+" to "12+ Años", "16+" to "16+ Años", "18+" to "Adultos (18+)"),
                            onSelected = { viewModel.updateCurrentProfileField(settings = settingsObj.copy(parentalRating = it)) },
                            theme = theme
                        )
                    }

                    // Parental PIN management card with Save and Change actions
                    ParentalPinSettingsCard(
                        currentPin = settingsObj.parentalPin,
                        onSavePin = { newPin ->
                            viewModel.updateCurrentProfileField(settings = settingsObj.copy(parentalPin = newPin))
                        },
                        lang = lang,
                        theme = theme
                    )
                }

                "themes" -> {
                    SettingsHeader(title = Translator.translate("themes", lang), subtitle = Translator.translate("themes_desc", lang))
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(THEMES_LIST) { th ->
                            val isSel = currentProfile?.themeId == th.id
                            var isThemeFocused by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isThemeFocused) TV_FOCUSED_BG else parseHexColor(th.surface))
                                    .border(2.dp, if (isThemeFocused) TV_FOCUSED_BORDER else if (isSel) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                                    .tvClickable(
                                        shape = RoundedCornerShape(8.dp),
                                        onFocusChange = { isThemeFocused = it }
                                    ) { viewModel.updateCurrentProfileField(themeId = th.id) }
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(th.emoji, fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    th.name,
                                    color = if (isThemeFocused) TV_FOCUSED_TEXT else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isThemeFocused) FontWeight.ExtraBold else FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                "diagnostic" -> {
                    SettingsHeader(title = Translator.translate("diagnostic", lang), subtitle = Translator.translate("diagnostic_desc", lang))
                    
                    val runtime = Runtime.getRuntime()
                    val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                    val totalMem = runtime.maxMemory() / 1024 / 1024
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            Text(Translator.translate("jvm_mem", lang), color = Color.LightGray, fontSize = 12.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                            ) {
                                Text("$usedMem MB " + Translator.translate("used", lang), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("$totalMem MB " + Translator.translate("total", lang), color = Color.Gray, fontSize = 13.sp)
                            }
                            LinearProgressIndicator(
                                progress = { usedMem.toFloat() / totalMem },
                                color = parseHexColor(theme.accent),
                                trackColor = Color.DarkGray,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            Text(Translator.translate("connection_details", lang), color = Color.LightGray, fontSize = 12.sp)
                            
                            val activePlaylistName by viewModel.activePlaylistName.collectAsStateWithLifecycle()
                            val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
                            
                            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(Translator.translate("network_status", lang) + ":", color = Color.LightGray, fontSize = 13.sp)
                                Text(
                                    text = if (isOnline) Translator.translate("online_status", lang) else Translator.translate("offline_status", lang),
                                    color = if (isOnline) Color.Green else Color.Red,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(Translator.translate("active_playlist", lang) + ":", color = Color.LightGray, fontSize = 13.sp)
                                Text(
                                    text = activePlaylistName.ifEmpty { Translator.translate("none", lang) },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(Translator.translate("backend_tech", lang) + ":", color = Color.LightGray, fontSize = 13.sp)
                                Text("M3U Parser, Xtream Core, Stalker SDK", color = Color.LightGray, fontSize = 13.sp)
                            }
                        }
                    }
                }

                "cache" -> {
                    SettingsHeader(title = Translator.translate("cache", lang), subtitle = Translator.translate("cache_desc", lang))
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                            Text(Translator.translate("reset_db", lang), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(Translator.translate("reset_db_desc", lang), color = Color.LightGray, fontSize = 11.sp)
                            
                            TvButton(
                                onClick = {
                                    viewModel.clearAllCategoryCaches()
                                    coroutineScope.launch {
                                        showCacheClearedMessage = true
                                        delay(3000)
                                        showCacheClearedMessage = false
                                    }
                                },
                                containerColor = Color(0xFFE53935).copy(alpha = 0.20f),
                                unfocusedBorderColor = Color(0xFFE53935).copy(alpha = 0.50f),
                                focusedContainerColor = Color(0xFFE53935).copy(alpha = 0.45f),
                                focusedBorderColor = Color(0xFFFF5252),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = Translator.translate("clear_cache_btn", lang),
                                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                                )
                            }
                            
                            if (showCacheClearedMessage) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Green.copy(alpha = 0.2f))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                                ) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.Green, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(Translator.translate("cache_cleared_success", lang), color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSyncDialog) {
        SyncTvDialog(
            onDismiss = { showSyncDialog = false },
            viewModel = viewModel,
            theme = theme,
            lang = lang
        )
    }
}

// ─── SETTINGS HELPER COMPOSABLES ──────────────────────────────────────────────

@Composable
fun SettingsHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = Color.LightGray,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    theme: AppThemeColors
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            var isFocused by remember { mutableStateOf(false) }
            val accentColor = parseHexColor(theme.accent)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = if (isFocused) 2.5.dp else 0.dp,
                        color = if (isFocused) TV_FOCUSED_BORDER else Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .background(
                        color = if (isFocused) accentColor.copy(alpha = 0.22f) else Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentColor,
                        checkedTrackColor = accentColor.copy(alpha = 0.4f)
                    )
                )
            }
        }
    }
}

@Composable
fun SettingsSelectorRow(
    title: String,
    subtitle: String,
    selectedValue: String,
    options: List<Pair<String, String>>, // Value to Label
    onSelected: (String) -> Unit,
    theme: AppThemeColors
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                options.forEach { (value, label) ->
                    val isSel = selectedValue == value
                    val unselBg = Color.White.copy(alpha = 0.08f)
                    val bg = if (isSel) parseHexColor(theme.accent) else unselBg
                    var isFocused by remember { mutableStateOf(false) }
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = 1.dp,
                                color = if (isSel) parseHexColor(theme.accent) else Color.White.copy(alpha = 0.20f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .background(if (isFocused) TV_FOCUSED_BG else bg)
                            .tvClickable(
                                shape = RoundedCornerShape(6.dp),
                                onFocusChange = { isFocused = it }
                            ) { onSelected(value) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isFocused || isSel) FontWeight.ExtraBold else FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String,
    buttonText: String,
    onClick: () -> Unit,
    theme: AppThemeColors,
    buttonModifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            TvButton(
                onClick = onClick,
                modifier = buttonModifier.border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.20f),
                    shape = RoundedCornerShape(8.dp)
                ),
                containerColor = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            ) { isFocused ->
                Text(
                    text = buttonText,
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ManageCategoriesFullScreen(
    title: String,
    allCategories: List<String>,
    hiddenCategories: List<String>,
    onSave: (List<String>) -> Unit,
    onBack: () -> Unit,
    theme: AppThemeColors,
    lang: String
) {
    var visibleCategoriesState by remember(allCategories, hiddenCategories) {
        mutableStateOf<Set<String>>(allCategories.filter { it !in hiddenCategories }.toSet())
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(parseHexColor(theme.bg))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Top Bar with Back Button and Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp)
                ) { isFocused ->
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${visibleCategoriesState.size} / ${allCategories.size} " + (if (lang == "es") "categorías visibles" else if (lang == "ro") "categorii vizibile" else "visible categories"),
                        color = parseHexColor(theme.accent),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Main Content Area (2 columns: Left for Action Buttons, Right for Categories List)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Left Column: Action Buttons (Mostrar/Ocultar & Cancelar/Guardar)
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvButton(
                        onClick = {
                            visibleCategoriesState = allCategories.toSet()
                        },
                        containerColor = parseHexColor(theme.accent).copy(alpha = 0.15f),
                        unfocusedBorderColor = parseHexColor(theme.accent).copy(alpha = 0.45f),
                        focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                        focusedBorderColor = parseHexColor(theme.accent),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (lang == "es") "Mostrar Todas" else if (lang == "ro") "Arată Toate" else "Show All",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                        )
                    }

                    TvButton(
                        onClick = {
                            visibleCategoriesState = emptySet()
                        },
                        containerColor = Color.White.copy(alpha = 0.08f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                        focusedBorderColor = parseHexColor(theme.accent),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (lang == "es") "Ocultar Todas" else if (lang == "ro") "Ascunde Toate" else "Hide All",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Bottom action buttons in left column: Cancelar / Guardar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvButton(
                            onClick = onBack,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            containerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                            focusedBorderColor = parseHexColor(theme.accent),
                            shape = RoundedCornerShape(8.dp)
                        ) { isFocused ->
                            Text(
                                text = if (lang == "es") "Cancelar" else if (lang == "ro") "Anulează" else "Cancel",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                            )
                        }

                        TvButton(
                            onClick = {
                                val hidden = allCategories.filter { it !in visibleCategoriesState }
                                onSave(hidden)
                            },
                            containerColor = parseHexColor(theme.accent).copy(alpha = 0.25f),
                            unfocusedBorderColor = parseHexColor(theme.accent).copy(alpha = 0.50f),
                            focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.40f),
                            focusedBorderColor = parseHexColor(theme.accent),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(44.dp)
                        ) { isFocused ->
                            Text(
                                text = if (lang == "es") "Guardar" else if (lang == "ro") "Salvează" else "Save",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                            )
                        }
                    }
                }

                // Right Column: Full Categories List with Checkboxes
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface).copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    if (allCategories.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (lang == "es") "No se encontraron categorías" else if (lang == "ro") "Nu s-au găsit categorii" else "No categories found",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(allCategories) { category ->
                                val isVisible = category in visibleCategoriesState
                                var isFocused by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isFocused) TV_FOCUSED_BG else if (isVisible) Color.White.copy(alpha = 0.04f) else Color.Transparent)
                                        .tvClickable(
                                            shape = RoundedCornerShape(8.dp),
                                            onFocusChange = { isFocused = it }
                                        ) {
                                            visibleCategoriesState = if (isVisible) {
                                                visibleCategoriesState - category
                                            } else {
                                                visibleCategoriesState + category
                                            }
                                        }
                                        .padding(vertical = 8.dp, horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isVisible,
                                        onCheckedChange = { checked ->
                                            visibleCategoriesState = if (checked == true) {
                                                visibleCategoriesState + category
                                            } else {
                                                visibleCategoriesState - category
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = if (isFocused) Color.Black else parseHexColor(theme.accent),
                                            checkmarkColor = if (isFocused) TV_FOCUSED_BG else Color.White
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = category,
                                        color = if (isFocused) TV_FOCUSED_TEXT else if (isVisible) Color.White else Color.Gray,
                                        fontSize = 13.sp,
                                        fontWeight = if (isFocused) FontWeight.ExtraBold else if (isVisible) FontWeight.Medium else FontWeight.Normal,
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
    }
}

fun generateQrBitmap(content: String, sizePx: Int = 360): androidx.compose.ui.graphics.ImageBitmap? {
    if (content.isBlank()) return null
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(
            content,
            com.google.zxing.BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) black else white
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        bmp.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}

@Composable
fun TvSyncPanel(
    viewModel: com.example.motor.sync.SyncServerDataProvider,
    theme: AppThemeColors,
    lang: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isServerActive by remember { mutableStateOf(true) }
    var activePort by remember { mutableStateOf(com.example.motor.sync.LocalSyncServer.serverPort) }
    var activeIp by remember { mutableStateOf(com.example.motor.sync.LocalSyncServer.getLocalIpAddress(context) ?: "") }

    var targetIpAddress by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sendSuccess by remember { mutableStateOf(false) }
    var sendSuccessMessage by remember { mutableStateOf<String?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScannerDialog = true
        } else {
            sendError = if (lang == "es") "Permiso de cámara requerido para escanear el QR" else "Camera permission required to scan QR"
        }
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    // Auto-start Receiver Mode automatically on launch
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Always ensure the active viewModel is set as provider
            val ok = com.example.motor.sync.LocalSyncServer.start(viewModel)
            isServerActive = ok
            activePort = com.example.motor.sync.LocalSyncServer.serverPort

            val physicalWifiIp = com.example.motor.sync.LocalSyncServer.getPhysicalWifiIp(context)
            val ip = com.example.motor.sync.LocalSyncServer.getLocalIpAddress(context) ?: (physicalWifiIp ?: "")
            val finalIp = if (ip.isNotEmpty()) ip else "192.168.1.100"
            activeIp = finalIp

            val isVpn = com.example.motor.sync.LocalSyncServer.isVpnActive(context)
            val physicalWifiFound = !physicalWifiIp.isNullOrBlank()
            val qrUrl = "http://$finalIp:$activePort?type=iptv_tv_sync&version=2"

            android.util.Log.i("SYNC_DIAG", "VPN activa: $isVpn")
            android.util.Log.i("SYNC_DIAG", "Wi-Fi física encontrada: $physicalWifiFound")
            android.util.Log.i("SYNC_DIAG", "IP física Wi-Fi: ${physicalWifiIp ?: "ninguna"}")
            android.util.Log.i("SYNC_DIAG", "IP utilizada por QR: $finalIp")
            android.util.Log.i("SYNC_DIAG", "puerto LocalSyncServer: $activePort")
            android.util.Log.i("SYNC_DIAG", "URL completa del QR: $qrUrl")
            android.util.Log.i("SYNC_DIAG", "servidor LocalSyncServer iniciado: ${com.example.motor.sync.LocalSyncServer.isRunning}")
        }
    }

    val displayIp = if (activeIp.isNotEmpty()) activeIp else "192.168.1.100"
    val qrDisplayUrl = remember(displayIp, activePort) {
        "http://$displayIp:$activePort"
    }
    val candidateList = remember(activeIp) {
        com.example.motor.sync.LocalSyncServer.getAllLocalIpAddresses(context)
    }
    val candidateParams = remember(candidateList) {
        candidateList.joinToString(",")
    }
    val qrPayload = remember(displayIp, activePort, candidateParams) {
        if (candidateParams.isNotEmpty()) {
            "http://$displayIp:$activePort?type=iptv_tv_sync&version=2&candidates=$candidateParams"
        } else {
            "http://$displayIp:$activePort?type=iptv_tv_sync&version=2"
        }
    }
    val qrBitmap = remember(qrPayload) {
        generateQrBitmap(qrPayload, 380)
    }

    if (showQrScannerDialog) {
        TvQrScannerDialog(
            lang = lang,
            accentColor = parseHexColor(theme.accent),
            onDismiss = { showQrScannerDialog = false },
            onQrScanned = { scannedQr ->
                showQrScannerDialog = false
                isSending = true
                sendError = null
                sendSuccess = false
                sendSuccessMessage = null
                val parsed = com.example.motor.sync.LocalSyncServer.parseQrSyncTarget(scannedQr)
                if (parsed != null) {
                    targetIpAddress = "${parsed.host}:${parsed.port}"
                }
                val (osKey, assrt) = viewModel.getSubtitleKeys()
                com.example.motor.sync.LocalSyncServer.syncPlaylistsWithTv(
                    qrOrAddress = scannedQr,
                    playlists = viewModel.getPlaylists(),
                    openSubtitlesKey = osKey.ifBlank { null },
                    assrtToken = assrt.ifBlank { null },
                    context = context,
                    onSuccess = { msg ->
                        isSending = false
                        sendSuccess = true
                        sendSuccessMessage = msg
                    },
                    onError = { err ->
                        isSending = false
                        sendError = err
                    }
                )
            }
        )
    }

    @Composable
    fun LeftQrCard(modifier: Modifier = Modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface).copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp),
            modifier = modifier.border(1.dp, parseHexColor(theme.accent).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = parseHexColor(theme.accent),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = if (lang == "es") "Código QR TV (Receptor)" else "TV QR Code (Receiver)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isServerActive) "● " + (if (lang == "es") "Servidor Activo" else "Server Active") else "○ " + (if (lang == "es") "Inactivo" else "Inactive"),
                                color = if (isServerActive) Color.Green else Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Refresh IP button
                    Box(
                        modifier = Modifier
                            .tvClickable(shape = RoundedCornerShape(6.dp)) {
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ip = com.example.motor.sync.LocalSyncServer.getLocalIpAddress(context) ?: ""
                                    if (ip.isNotEmpty()) activeIp = ip
                                    if (!com.example.motor.sync.LocalSyncServer.isRunning) {
                                        com.example.motor.sync.LocalSyncServer.start(viewModel)
                                        isServerActive = true
                                    }
                                }
                            }
                            .padding(4.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Actualizar", tint = parseHexColor(theme.accent), modifier = Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Left Side QR Code directly visible
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // QR Code Display
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "Código QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = "Código QR",
                                    tint = Color.DarkGray,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = if (lang == "es") "Usa la URL local" else "Use local URL",
                                    color = Color.DarkGray,
                                    fontSize = 9.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    // Steps and URL
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (lang == "es") "IP DE SINCRONIZACIÓN:" else "SYNC IP ADDRESS:",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        SelectionContainer {
                            Text(
                                text = qrDisplayUrl,
                                color = parseHexColor(theme.accent),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (lang == "es") "1. Escanea el código QR con el móvil." else "1. Scan the QR code with phone.",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (lang == "es") "2. O ingresa la IP en el navegador." else "2. Or open IP in phone browser.",
                            color = Color.LightGray,
                            fontSize = 9.sp
                        )
                        Text(
                            text = if (lang == "es") "3. Envía tus listas M3U/Xtream a la TV." else "3. Send your M3U/Xtream playlists to TV.",
                            color = Color.LightGray,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun RightSenderCard(modifier: Modifier = Modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface).copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp),
            modifier = modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MobileFriendly,
                        contentDescription = null,
                        tint = parseHexColor(theme.accent),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (lang == "es") "Emisor (Móvil -> TV)" else "Sender (Mobile -> TV)",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quick Scan QR Button
                TvButton(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            showQrScannerDialog = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !isSending,
                    containerColor = parseHexColor(theme.accent).copy(alpha = 0.15f),
                    unfocusedBorderColor = parseHexColor(theme.accent).copy(alpha = 0.50f),
                    focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                    focusedBorderColor = parseHexColor(theme.accent),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp),
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = if (isFocused) Color.White else parseHexColor(theme.accent),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (lang == "es") "ESCANEAR QR DE LA TV" else "SCAN TV QR CODE",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (lang == "es") "O introduce la IP manualmente:" else "Or enter TV IP manually:",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )

                OutlinedTextField(
                    value = targetIpAddress,
                    onValueChange = { targetIpAddress = it },
                    placeholder = { Text("Ej: 192.168.1.150:9999", color = Color.DarkGray, fontSize = 10.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = parseHexColor(theme.accent),
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                if (sendSuccess) {
                    Text(
                        text = sendSuccessMessage ?: (if (lang == "es") "¡Enviado con éxito a la TV!" else "Sent successfully to TV!"),
                        color = Color.Green,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (sendError != null) {
                    Text("Error: $sendError", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                TvButton(
                    onClick = {
                        if (targetIpAddress.isBlank()) {
                            sendError = if (lang == "es") "Introduce la IP de destino o escanea el QR" else "Enter target IP or scan QR"
                            return@TvButton
                        }
                        isSending = true
                        sendError = null
                        sendSuccess = false
                        sendSuccessMessage = null
                        val playlistsToSend = viewModel.getPlaylists()
                        val (osKey, assrt) = viewModel.getSubtitleKeys()
                        com.example.motor.sync.LocalSyncServer.syncPlaylistsWithTv(
                            qrOrAddress = targetIpAddress,
                            playlists = playlistsToSend,
                            openSubtitlesKey = osKey.ifBlank { null },
                            assrtToken = assrt.ifBlank { null },
                            context = context,
                            onSuccess = { msg ->
                                isSending = false
                                sendSuccess = true
                                sendSuccessMessage = msg
                            },
                            onError = { err ->
                                isSending = false
                                sendError = err
                            }
                        )
                    },
                    enabled = !isSending,
                    containerColor = parseHexColor(theme.accent).copy(alpha = 0.25f),
                    unfocusedBorderColor = parseHexColor(theme.accent).copy(alpha = 0.60f),
                    focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.45f),
                    focusedBorderColor = parseHexColor(theme.accent),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    shape = RoundedCornerShape(8.dp)
                ) { isFocused ->
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSending) (if (lang == "es") "Enviando..." else "Sending...") else (if (lang == "es") "ENVIAR A LA TV" else "SEND TO TV"),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Always render side-by-side in dialog/panel for compact fixed screen fit
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LeftQrCard(modifier = Modifier.weight(1.1f))
        RightSenderCard(modifier = Modifier.weight(0.9f))
    }
}

@Composable
fun SyncTvDialog(
    onDismiss: () -> Unit,
    viewModel: com.example.motor.sync.SyncServerDataProvider,
    theme: AppThemeColors,
    lang: String
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth(0.95f)
                .padding(8.dp)
                .border(1.5.dp, parseHexColor(theme.accent).copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.bg))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (lang == "es") "Sincronización TV y Código QR" else if (lang == "ro") "Sincronizare TV și Cod QR" else "TV Sync & QR Code",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    TvIconButton(
                        onClick = onDismiss,
                        containerColor = Color.Transparent,
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

                TvSyncPanel(
                    viewModel = viewModel,
                    theme = theme,
                    lang = lang
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TvButton(
                        onClick = onDismiss,
                        containerColor = Color.White.copy(alpha = 0.10f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                        focusedBorderColor = parseHexColor(theme.accent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Text(
                            text = if (lang == "es") "Cerrar" else if (lang == "ro") "Închide" else "Close",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleApiKeyCard(
    title: String,
    description: String,
    currentValue: String,
    onSaveKey: (String) -> Unit,
    lang: String,
    theme: AppThemeColors
) {
    var keyInput by remember(currentValue) { mutableStateOf(currentValue) }
    var keyVisible by remember { mutableStateOf(false) }
    var hasSavedFeedback by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = parseHexColor(theme.surface)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = parseHexColor(theme.accent),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = description,
                color = Color.LightGray,
                fontSize = 12.sp
            )

            OutlinedTextField(
                value = keyInput,
                onValueChange = {
                    keyInput = it
                    hasSavedFeedback = false
                },
                label = { Text(title, color = Color.Gray) },
                placeholder = { Text("Introduce tu clave...", color = Color.DarkGray) },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (keyVisible) "Ocultar" else "Mostrar",
                            tint = Color.Gray
                        )
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = parseHexColor(theme.accent),
                    unfocusedBorderColor = Color.DarkGray,
                    cursorColor = parseHexColor(theme.accent)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvButton(
                    onClick = {
                        onSaveKey(keyInput.trim())
                        hasSavedFeedback = true
                    },
                    containerColor = parseHexColor(theme.accent).copy(alpha = 0.20f),
                    unfocusedBorderColor = parseHexColor(theme.accent).copy(alpha = 0.50f),
                    focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.38f),
                    focusedBorderColor = parseHexColor(theme.accent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) { isFocused ->
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = Translator.translate("save_key", lang),
                        color = Color.White,
                        fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (keyInput.isNotBlank()) {
                    TvButton(
                        onClick = {
                            keyInput = ""
                            onSaveKey("")
                            hasSavedFeedback = false
                        },
                        containerColor = Color.White.copy(alpha = 0.08f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                        focusedBorderColor = parseHexColor(theme.accent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null,
                            tint = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = Translator.translate("clear_key", lang),
                            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }

                TvIconButton(
                    onClick = { keyVisible = !keyVisible },
                    containerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedContainerColor = parseHexColor(theme.accent).copy(alpha = 0.35f),
                    focusedBorderColor = parseHexColor(theme.accent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(38.dp)
                ) { isFocused ->
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Ocultar" else "Mostrar",
                        tint = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (hasSavedFeedback) {
                    Text("✅ Guardado", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
