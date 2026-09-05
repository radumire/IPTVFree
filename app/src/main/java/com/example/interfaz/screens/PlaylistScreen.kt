package com.example.interfaz.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.Translator
import com.example.parseHexColor
import com.example.motor.db.*
import com.example.motor.config.*
import com.example.interfaz.AppThemeColors
import com.example.interfaz.viewmodel.PlaylistViewModel
import com.example.interfaz.components.tvClickable
import com.example.interfaz.components.TvButton
import com.example.interfaz.components.TvIconButton
import com.example.interfaz.components.TV_FOCUSED_BG
import com.example.interfaz.components.TV_FOCUSED_TEXT

@Composable
fun PlaylistsScreen(
    viewModel: PlaylistViewModel,
    theme: AppThemeColors
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlistsList = uiState.playlists
    val connStatus = uiState.connectStatus
    val connLog = uiState.connectLog
    val connError = uiState.connectErrorMsg

    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var editingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var showSyncTvDialog by remember { mutableStateOf(false) }

    // Duplicate detection states
    var showDuplicateWarningDialog by remember { mutableStateOf(false) }
    var pendingDuplicatePl by remember { mutableStateOf<Playlist?>(null) }
    var detectedDuplicateExisting by remember { mutableStateOf<Playlist?>(null) }
    var isEditDuplicate by remember { mutableStateOf(false) }

    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Gestión de Listas IPTV", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            
            TvButton(
                onClick = { showSyncTvDialog = true },
                containerColor = parseHexColor(theme.surface),
                modifier = Modifier.padding(end = 8.dp)
            ) { isFocused ->
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isFocused) TV_FOCUSED_TEXT else parseHexColor(theme.accent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Sincronizar TV",
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                )
            }

            TvButton(
                onClick = { showAddPlaylistDialog = true },
                containerColor = parseHexColor(theme.accent)
            ) { isFocused ->
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Añadir Lista",
                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                )
            }
        }

        if (playlistsList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No tienes ninguna lista agregada", color = Color.Gray, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                items(playlistsList) { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(parseHexColor(theme.surface))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (pl.active) Icons.Default.CheckCircle else Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = if (pl.active) Color.Green else Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Tipo: ${pl.type.uppercase()} · ${if (pl.expires.isNotEmpty()) "Expira: ${pl.expires}" else "Sin caducidad"}",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                        Row(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                onClick = { viewModel.connectPlaylist(pl) },
                                containerColor = if (pl.active) Color(0xFF3B3B48) else parseHexColor(theme.accent),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) { isFocused ->
                                Text(
                                    text = if (pl.active) "Reconectar" else "Conectar",
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                                    color = if (isFocused) TV_FOCUSED_TEXT else Color.White
                                )
                            }

                            TvIconButton(
                                onClick = {
                                    editingPlaylist = pl
                                    showEditPlaylistDialog = true
                                },
                                containerColor = Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(36.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Editar",
                                    tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            TvIconButton(
                                onClick = {
                                    playlistToDelete = pl
                                    showDeleteConfirmDialog = true
                                },
                                containerColor = Color.Red.copy(alpha = 0.15f),
                                focusedContainerColor = Color(0xFFFF5252),
                                focusedContentColor = Color.White,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(36.dp)
                            ) { isFocused ->
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Eliminar",
                                    tint = if (isFocused) Color.White else Color(0xFFFF6B6B),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Connection Log Dialog
        if (connStatus != null) {
            val isConnecting = connStatus == "connecting"
            val isError = connStatus == "error"
            val isSuccess = connStatus == "ok"

            LaunchedEffect(connStatus) {
                if (connStatus == "connecting") {
                    kotlinx.coroutines.delay(30_000L)
                    if (connStatus == "connecting") {
                        viewModel.dismissConnectDialog()
                    }
                }
            }

            val stage1Status = remember(connLog, connStatus) {
                when {
                    isError && (connLog.contains("Iniciando") || connLog.contains("Conectando") || connLog.isEmpty()) -> "error"
                    isSuccess -> "completed"
                    connLog.contains("Descargando") || connLog.contains("analizando") || connLog.contains("elementos") || connLog.contains("¡Conectado!") -> "completed"
                    isConnecting -> "active"
                    else -> "pending"
                }
            }

            val stage2Status = remember(connLog, connStatus) {
                when {
                    isError && (connLog.contains("Descargando") || connLog.contains("analizando") || connLog.contains("M3U")) -> "error"
                    isSuccess -> "completed"
                    connLog.contains("¡Conectado!") || connLog.contains("elementos") -> "completed"
                    connLog.contains("Descargando") || connLog.contains("analizando") || connLog.contains("M3U") -> "active"
                    else -> "pending"
                }
            }

            val stage3Status = remember(connLog, connStatus) {
                when {
                    isError && (stage2Status == "completed") -> "error"
                    isSuccess -> "completed"
                    connLog.contains("¡Conectado!") || connLog.contains("elementos") -> "active"
                    else -> "pending"
                }
            }

            AlertDialog(
                onDismissRequest = { viewModel.dismissConnectDialog() },
                containerColor = parseHexColor(theme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 440.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                confirmButton = {
                    if (isConnecting) {
                        TvButton(
                            onClick = { viewModel.dismissConnectDialog() },
                            containerColor = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        ) { isFocused ->
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Ocultar y descargar en segundo plano",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        TvButton(
                            onClick = { viewModel.dismissConnectDialog() },
                            containerColor = if (isError) Color(0xFFD32F2F) else parseHexColor(theme.accent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        ) { isFocused ->
                            Text(
                                text = if (isError) "Cerrar" else "Aceptar y Continuar",
                                color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top Illustration / Animation Circle
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    when {
                                        isSuccess -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                        isError -> Color(0xFFC62828).copy(alpha = 0.15f)
                                        else -> parseHexColor(theme.accent).copy(alpha = 0.15f)
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isSuccess -> Color(0xFF4CAF50).copy(alpha = 0.4f)
                                        isError -> Color(0xFFEF5350).copy(alpha = 0.4f)
                                        else -> parseHexColor(theme.accent).copy(alpha = 0.4f)
                                    },
                                    shape = androidx.compose.foundation.shape.CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isSuccess -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Éxito",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                isError -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Error",
                                        tint = Color(0xFFEF5350),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                else -> {
                                    // Spinning glowing loader
                                    CircularProgressIndicator(
                                        color = parseHexColor(theme.accent),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Conectando",
                                        tint = parseHexColor(theme.accent),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Text Header
                        Text(
                            text = when {
                                isSuccess -> "¡Lista Configurada!"
                                isError -> "Error de Sincronización"
                                else -> "Sincronizando Lista"
                            },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = when {
                                isSuccess -> "Todo listo. Se han cargado tus canales y contenido multimedia."
                                isError -> "No pudimos conectar con el servidor. Verifica los datos."
                                else -> "Por favor espera mientras descargamos e indexamos tu contenido."
                            },
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Step Cards Block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                                .padding(vertical = 6.dp, horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Step 1: Server Authentication
                            StepRow(
                                title = "1. Conexión con Servidor",
                                desc = "Autenticando credenciales y validando token",
                                status = stage1Status,
                                theme = theme
                            )

                            // Step 2: Content Parsing
                            StepRow(
                                title = "2. Sincronización de Contenido",
                                desc = "Descargando lista de canales, películas y series",
                                status = stage2Status,
                                theme = theme
                            )

                            // Step 3: Local Caching & Indexing
                            StepRow(
                                title = "3. Indexación y Optimización",
                                desc = "Guardando en caché de alta velocidad",
                                status = stage3Status,
                                theme = theme
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Sleek real-time detailed status log / Console output
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                .padding(vertical = 5.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isError) Color(0xFFEF5350) else parseHexColor(theme.accent).copy(alpha = 0.8f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (connLog.isNotEmpty()) connLog else "Esperando respuesta del servidor...",
                                color = if (isError) Color(0xFFEF5350) else Color.LightGray,
                                fontSize = 9.5.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            )
        }

        if (showSyncTvDialog) {
            SyncTvDialog(
                onDismiss = { showSyncTvDialog = false },
                viewModel = viewModel,
                theme = theme,
                lang = "es"
            )
        }

        // Add Playlist Form Dialog
        if (showAddPlaylistDialog) {
            var plName by remember { mutableStateOf("") }
            var plType by remember { mutableStateOf("m3u") } // m3u, xtream, stalker
            var plUrl by remember { mutableStateOf("") }
            var plHost by remember { mutableStateOf("") }
            var plUser by remember { mutableStateOf("") }
            var plPass by remember { mutableStateOf("") }
            var plPassVisible by remember { mutableStateOf(false) }
            var plPort by remember { mutableStateOf("80") }
            var plMac by remember { mutableStateOf("") }
            var plMacTfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }

            AlertDialog(
                onDismissRequest = { showAddPlaylistDialog = false },
                containerColor = parseHexColor(theme.surface),
                shape = RoundedCornerShape(16.dp),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 680.dp),
                text = {
                    Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
                    ) {
                        // Custom Tab Switcher - strictly M3U, Xtream, Stalker
                        Row(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            listOf("m3u" to "M3U", "xtream" to "Xtream", "stalker" to "Stalker").forEach { (typeKey, label) ->
                                val isSelected = plType == typeKey
                                var isFocused by remember { mutableStateOf(false) }
                                val bg = if (isFocused) TV_FOCUSED_BG else if (isSelected) parseHexColor(theme.accent) else Color.Transparent
                                val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color.White else Color.LightGray

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(bg)
                                        .tvClickable(shape = RoundedCornerShape(6.dp), onFocusChange = { isFocused = it }) { plType = typeKey }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = tc,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Conditional content based on type
                        if (plType == "m3u") {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                TextField(
                                    value = plName,
                                    onValueChange = { plName = it },
                                    label = { Text("Nombre de la lista") },
                                    placeholder = { Text("Ej. Mi Lista Premium") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = parseHexColor(theme.bg),
                                        unfocusedContainerColor = parseHexColor(theme.bg),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = parseHexColor(theme.accent),
                                        unfocusedLabelColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                TextField(
                                    value = plUrl,
                                    onValueChange = { plUrl = it },
                                    label = { Text("URL de la lista M3U") },
                                    placeholder = { Text("http://example.com/playlist.m3u") },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val clipText = clipboardManager?.getText()?.text ?: ""
                                            if (clipText.isNotEmpty()) plUrl = clipText
                                        }) {
                                            Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                        }
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = parseHexColor(theme.bg),
                                        unfocusedContainerColor = parseHexColor(theme.bg),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = parseHexColor(theme.accent),
                                        unfocusedLabelColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Soporta formatos estándar de listas M3U y M3U8.",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        } else if (plType == "xtream") {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = plName,
                                        onValueChange = { plName = it },
                                        label = { Text("Nombre de la lista") },
                                        placeholder = { Text("Ej. Mi Servidor Xtream") },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    val handleHostInput: (String) -> Unit = { input ->
                                        val clean = input.trim()
                                        if (clean.contains("username=", ignoreCase = true) && clean.contains("password=", ignoreCase = true)) {
                                            try {
                                                val uri = java.net.URI(clean)
                                                val query = uri.query ?: ""
                                                val queryParams = query.split("&").associate {
                                                    val parts = it.split("=", limit = 2)
                                                    if (parts.size == 2) parts[0].lowercase() to parts[1] else "" to ""
                                                }
                                                val u = queryParams["username"] ?: queryParams["user"]
                                                val p = queryParams["password"] ?: queryParams["pass"]
                                                if (!u.isNullOrEmpty()) plUser = u
                                                if (!p.isNullOrEmpty()) plPass = p

                                                val scheme = if (clean.startsWith("https://", ignoreCase = true)) "https" else "http"
                                                val hostStr = uri.host ?: ""
                                                val portVal = if (uri.port != -1) ":${uri.port}" else ""
                                                if (hostStr.isNotEmpty()) {
                                                    plHost = "$scheme://$hostStr$portVal"
                                                } else {
                                                    plHost = clean
                                                }
                                            } catch (e: Exception) {
                                                plHost = clean
                                            }
                                        } else {
                                            plHost = clean
                                        }
                                    }

                                    TextField(
                                        value = plHost,
                                        onValueChange = { handleHostInput(it) },
                                        label = { Text("URL del Servidor / Host") },
                                        placeholder = { Text("http://servidor-iptv.com:8080") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) handleHostInput(clipText)
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = plUser,
                                        onValueChange = { plUser = it },
                                        label = { Text("Usuario") },
                                        placeholder = { Text("Tu usuario") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) plUser = clipText
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextField(
                                        value = plPass,
                                        onValueChange = { plPass = it },
                                        label = { Text("Contraseña") },
                                        placeholder = { Text("Tu clave") },
                                        visualTransformation = if (plPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            Row {
                                                IconButton(onClick = { plPassVisible = !plPassVisible }) {
                                                    Icon(
                                                        imageVector = if (plPassVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                        contentDescription = if (plPassVisible) "Ocultar" else "Mostrar",
                                                        tint = Color.LightGray
                                                    )
                                                }
                                                IconButton(onClick = {
                                                    val clipText = clipboardManager?.getText()?.text ?: ""
                                                    if (clipText.isNotEmpty()) plPass = clipText
                                                }) {
                                                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                                }
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        } else {
                            // Stalker Portal
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                TextField(
                                    value = plName,
                                    onValueChange = { plName = it },
                                    label = { Text("Nombre de la lista") },
                                    placeholder = { Text("Ej. Mi Lista Stalker") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = parseHexColor(theme.bg),
                                        unfocusedContainerColor = parseHexColor(theme.bg),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = parseHexColor(theme.accent),
                                        unfocusedLabelColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = plHost,
                                        onValueChange = { input ->
                                            val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2})\\b")
                                            val match = macRegex.find(input)
                                            if (match != null) {
                                                val formatted = formatMacAddress(match.value)
                                                plMac = formatted
                                                plMacTfv = androidx.compose.ui.text.input.TextFieldValue(text = formatted, selection = androidx.compose.ui.text.TextRange(formatted.length))
                                                plHost = input.replace(match.value, "").replace(",", "").trim()
                                            } else {
                                                plHost = input
                                            }
                                        },
                                        label = { Text("URL del Portal Stalker") },
                                        placeholder = { Text("http://stalkerportal.live/c/") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) {
                                                    val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2})\\b")
                                                    val match = macRegex.find(clipText)
                                                    if (match != null) {
                                                        val formatted = formatMacAddress(match.value)
                                                        plMac = formatted
                                                        plMacTfv = androidx.compose.ui.text.input.TextFieldValue(text = formatted, selection = androidx.compose.ui.text.TextRange(formatted.length))
                                                        plHost = clipText.replace(match.value, "").replace(",", "").trim()
                                                    } else {
                                                        plHost = clipText
                                                    }
                                                }
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextField(
                                        value = plMacTfv,
                                        onValueChange = { tfv ->
                                            val formatted = formatMacAddress(tfv.text)
                                            plMac = formatted
                                            plMacTfv = androidx.compose.ui.text.input.TextFieldValue(
                                                text = formatted,
                                                selection = androidx.compose.ui.text.TextRange(formatted.length)
                                            )
                                        },
                                        label = { Text("Dirección MAC") },
                                        placeholder = { Text("00:1A:79:XX:XX:XX") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) {
                                                    val formatted = formatMacAddress(clipText)
                                                    plMac = formatted
                                                    plMacTfv = androidx.compose.ui.text.input.TextFieldValue(text = formatted, selection = androidx.compose.ui.text.TextRange(formatted.length))
                                                }
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TvButton(
                        onClick = {
                            val finalName = plName.trim().ifEmpty { "Lista ${plType.uppercase()} ${playlistsList.size + 1}" }
                            val currentPlaylists = playlistsList.toMutableList()
                            val listId = System.currentTimeMillis()
                            val deviceJson = JsonUtils.stalkerDeviceToJson(StalkerDevice())

                            var finalHost = plHost.trim()
                            var finalPort = plPort.trim()
                            var finalUser = plUser.trim()
                            var finalPass = plPass.trim()

                            if (plType == "m3u" && plUrl.isNotBlank()) {
                                val creds = com.example.motor.parser.XtreamParser.parseXtreamCredentialsDirect(plUrl.trim())
                                if (creds != null) {
                                    finalHost = creds.host
                                    finalPort = creds.port
                                    finalUser = creds.user
                                    finalPass = creds.pass
                                }
                            }

                            val isFirst = currentPlaylists.isEmpty() || currentPlaylists.none { it.active }
                            val newPl = Playlist(
                                id = listId,
                                type = plType,
                                name = finalName,
                                url = plUrl.trim(),
                                host = finalHost,
                                user = finalUser,
                                pass = finalPass,
                                port = finalPort,
                                mac = plMac.trim(),
                                stalkerDeviceJson = deviceJson,
                                active = isFirst
                            )
                            val duplicate = findDuplicatePlaylist(newPl, playlistsList)
                            if (duplicate != null) {
                                pendingDuplicatePl = newPl
                                detectedDuplicateExisting = duplicate
                                isEditDuplicate = false
                                showDuplicateWarningDialog = true
                            } else {
                                currentPlaylists.add(newPl)
                                if (isFirst) {
                                    viewModel.connectPlaylist(newPl)
                                }
                                viewModel.updateCurrentProfileField(playlists = currentPlaylists)
                                showAddPlaylistDialog = false
                            }
                        },
                        containerColor = parseHexColor(theme.accent),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Añadir Lista",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TvButton(
                        onClick = { showAddPlaylistDialog = false },
                        containerColor = Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Text(
                            text = "Cancelar",
                            color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray,
                            fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal
                        )
                    }
                }
            )
        }

        // Edit Playlist Dialog
        if (showEditPlaylistDialog && editingPlaylist != null) {
            var editName by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.name) }
            var editType by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.type) }
            var editUrl by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.url) }
            var editHost by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.host) }
            var editUser by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.user) }
            var editPass by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.pass) }
            var editPassVisible by remember { mutableStateOf(false) }
            var editPort by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.port) }
            var editMac by remember(editingPlaylist) { mutableStateOf(editingPlaylist!!.mac) }
            var editMacTfv by remember(editingPlaylist) {
                val formatted = formatMacAddress(editingPlaylist!!.mac)
                mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(formatted, androidx.compose.ui.text.TextRange(formatted.length)))
            }

            AlertDialog(
                onDismissRequest = { showEditPlaylistDialog = false; editingPlaylist = null },
                containerColor = parseHexColor(theme.surface),
                shape = RoundedCornerShape(16.dp),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 680.dp),
                text = {
                    Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
                    ) {
                        // Type selector - strictly M3U, Xtream, Stalker
                        Row(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            listOf("m3u" to "M3U", "xtream" to "Xtream", "stalker" to "Stalker").forEach { (typeKey, label) ->
                                val isSelected = editType == typeKey
                                var isFocused by remember { mutableStateOf(false) }
                                val bg = if (isFocused) TV_FOCUSED_BG else if (isSelected) parseHexColor(theme.accent) else Color.Transparent
                                val tc = if (isFocused) TV_FOCUSED_TEXT else if (isSelected) Color.White else Color.LightGray

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(bg)
                                        .tvClickable(shape = RoundedCornerShape(6.dp), onFocusChange = { isFocused = it }) { editType = typeKey }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = tc,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (editType == "m3u") {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                TextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    label = { Text("Nombre de la lista") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = parseHexColor(theme.bg),
                                        unfocusedContainerColor = parseHexColor(theme.bg),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = parseHexColor(theme.accent),
                                        unfocusedLabelColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TextField(
                                    value = editUrl,
                                    onValueChange = { editUrl = it },
                                    label = { Text("URL de la lista M3U") },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val clipText = clipboardManager?.getText()?.text ?: ""
                                            if (clipText.isNotEmpty()) editUrl = clipText
                                        }) {
                                            Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                        }
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = parseHexColor(theme.bg),
                                        unfocusedContainerColor = parseHexColor(theme.bg),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = parseHexColor(theme.accent),
                                        unfocusedLabelColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else if (editType == "xtream") {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = editName,
                                        onValueChange = { editName = it },
                                        label = { Text("Nombre de la lista") },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextField(
                                        value = editHost,
                                        onValueChange = { editHost = it },
                                        label = { Text("URL del Servidor / Host") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) editHost = clipText
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = editUser,
                                        onValueChange = { editUser = it },
                                        label = { Text("Usuario") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) editUser = clipText
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextField(
                                        value = editPass,
                                        onValueChange = { editPass = it },
                                        label = { Text("Contraseña") },
                                        visualTransformation = if (editPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            Row {
                                                IconButton(onClick = { editPassVisible = !editPassVisible }) {
                                                    Icon(
                                                        imageVector = if (editPassVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                        contentDescription = if (editPassVisible) "Ocultar" else "Mostrar",
                                                        tint = Color.LightGray
                                                    )
                                                }
                                                IconButton(onClick = {
                                                    val clipText = clipboardManager?.getText()?.text ?: ""
                                                    if (clipText.isNotEmpty()) editPass = clipText
                                                }) {
                                                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                                }
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                TextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    label = { Text("Nombre de la lista") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = parseHexColor(theme.bg),
                                        unfocusedContainerColor = parseHexColor(theme.bg),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedLabelColor = parseHexColor(theme.accent),
                                        unfocusedLabelColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = editHost,
                                        onValueChange = { input ->
                                            val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2})\\b")
                                            val match = macRegex.find(input)
                                            if (match != null) {
                                                editMac = match.value.replace("-", ":").uppercase()
                                                editHost = input.replace(match.value, "").replace(",", "").trim()
                                            } else {
                                                editHost = input
                                            }
                                        },
                                        label = { Text("URL del Portal Stalker") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) {
                                                    val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2}[:-][0-9A-F]{2})\\b")
                                                    val match = macRegex.find(clipText)
                                                    if (match != null) {
                                                        editMac = match.value.replace("-", ":").uppercase()
                                                        editHost = clipText.replace(match.value, "").replace(",", "").trim()
                                                    } else {
                                                        editHost = clipText
                                                    }
                                                }
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextField(
                                        value = editMacTfv,
                                        onValueChange = { tfv ->
                                            val formatted = formatMacAddress(tfv.text)
                                            editMac = formatted
                                            editMacTfv = androidx.compose.ui.text.input.TextFieldValue(
                                                text = formatted,
                                                selection = androidx.compose.ui.text.TextRange(formatted.length)
                                            )
                                        },
                                        label = { Text("Dirección MAC") },
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                val clipText = clipboardManager?.getText()?.text ?: ""
                                                if (clipText.isNotEmpty()) {
                                                    val formatted = formatMacAddress(clipText)
                                                    editMac = formatted
                                                    editMacTfv = androidx.compose.ui.text.input.TextFieldValue(text = formatted, selection = androidx.compose.ui.text.TextRange(formatted.length))
                                                }
                                            }) {
                                                Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Pegar", tint = Color.LightGray)
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = parseHexColor(theme.bg),
                                            unfocusedContainerColor = parseHexColor(theme.bg),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedLabelColor = parseHexColor(theme.accent),
                                            unfocusedLabelColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TvButton(
                        onClick = {
                            val finalName = editName.trim().ifEmpty { "Lista ${editType.uppercase()} ${playlistsList.size}" }
                            
                            var finalHost = editHost.trim()
                            var finalPort = editPort.trim()
                            var finalUser = editUser.trim()
                            var finalPass = editPass.trim()

                            if (editType == "m3u" && editUrl.isNotBlank()) {
                                val creds = com.example.motor.parser.XtreamParser.parseXtreamCredentialsDirect(editUrl.trim())
                                if (creds != null) {
                                    finalHost = creds.host
                                    finalPort = creds.port
                                    finalUser = creds.user
                                    finalPass = creds.pass
                                }
                            }

                            val editedPl = editingPlaylist!!.copy(
                                type = editType,
                                name = finalName,
                                url = editUrl.trim(),
                                host = finalHost,
                                user = finalUser,
                                pass = finalPass,
                                port = finalPort,
                                mac = editMac.trim()
                            )
                            val duplicate = findDuplicatePlaylist(editedPl, playlistsList, excludeId = editingPlaylist!!.id)
                            if (duplicate != null) {
                                pendingDuplicatePl = editedPl
                                detectedDuplicateExisting = duplicate
                                isEditDuplicate = true
                                showDuplicateWarningDialog = true
                            } else {
                                val updatedPlaylists = playlistsList.map { pl ->
                                    if (pl.id == editingPlaylist!!.id) editedPl else pl
                                }
                                viewModel.updateCurrentProfileField(playlists = updatedPlaylists)
                                showEditPlaylistDialog = false
                                editingPlaylist = null
                            }
                        },
                        containerColor = parseHexColor(theme.accent),
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Guardar Cambios", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TvButton(
                        onClick = { showEditPlaylistDialog = false; editingPlaylist = null },
                        containerColor = Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Text("Cancelar", color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal)
                    }
                }
            )
        }

        // Duplicate Warning Alert Dialog
        if (showDuplicateWarningDialog && detectedDuplicateExisting != null && pendingDuplicatePl != null) {
            AlertDialog(
                onDismissRequest = {
                    showDuplicateWarningDialog = false
                    pendingDuplicatePl = null
                    detectedDuplicateExisting = null
                },
                containerColor = parseHexColor(theme.surface),
                shape = RoundedCornerShape(16.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Aviso lista duplicada",
                            tint = Color(0xFFFFA726),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Lista ya agregada",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Esta lista ya se encuentra registrada en la aplicación:",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = parseHexColor(theme.accent),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = detectedDuplicateExisting!!.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = "Tipo: ${detectedDuplicateExisting!!.type.uppercase()}",
                                    color = parseHexColor(theme.accent),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                if (detectedDuplicateExisting!!.type.equals("m3u", ignoreCase = true)) {
                                    val u = detectedDuplicateExisting!!.url.ifBlank { detectedDuplicateExisting!!.host }
                                    if (u.isNotBlank()) {
                                        Text(
                                            text = "URL: $u",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            maxLines = 2
                                        )
                                    }
                                } else if (detectedDuplicateExisting!!.type.equals("stalker", ignoreCase = true)) {
                                    Text(
                                        text = "Portal: ${detectedDuplicateExisting!!.host}\nMAC: ${detectedDuplicateExisting!!.mac}",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                } else {
                                    Text(
                                        text = "Servidor: ${detectedDuplicateExisting!!.host}\nUsuario: ${detectedDuplicateExisting!!.user}",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Text(
                            text = "¿Deseas cancelarla para no añadirla duplicada o deseas añadirla igualmente?",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                confirmButton = {
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvButton(
                            onClick = {
                                // Force add / save
                                val currentPlaylists = playlistsList.toMutableList()
                                val targetPl = pendingDuplicatePl!!
                                if (isEditDuplicate && editingPlaylist != null) {
                                    val updatedPlaylists = playlistsList.map { pl ->
                                        if (pl.id == editingPlaylist!!.id) targetPl else pl
                                    }
                                    viewModel.updateCurrentProfileField(playlists = updatedPlaylists)
                                    showEditPlaylistDialog = false
                                    editingPlaylist = null
                                } else {
                                    val isFirst = currentPlaylists.isEmpty() || currentPlaylists.none { it.active }
                                    val finalPl = targetPl.copy(active = isFirst)
                                    currentPlaylists.add(finalPl)
                                    if (isFirst) {
                                        viewModel.connectPlaylist(finalPl)
                                    }
                                    viewModel.updateCurrentProfileField(playlists = currentPlaylists)
                                    showAddPlaylistDialog = false
                                }
                                showDuplicateWarningDialog = false
                                pendingDuplicatePl = null
                                detectedDuplicateExisting = null
                            },
                            shape = RoundedCornerShape(8.dp),
                            containerColor = Color.Transparent
                        ) { isFocused ->
                            Text(if (isEditDuplicate) "Guardar de todos modos" else "Añadir de todos modos", fontSize = 11.sp, color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal)
                        }

                        TvButton(
                            onClick = {
                                // Cancel / Do not add
                                showDuplicateWarningDialog = false
                                if (!isEditDuplicate) {
                                    showAddPlaylistDialog = false
                                }
                                pendingDuplicatePl = null
                                detectedDuplicateExisting = null
                            },
                            containerColor = parseHexColor(theme.accent),
                            shape = RoundedCornerShape(8.dp)
                        ) { isFocused ->
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isFocused) TV_FOCUSED_TEXT else Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("No añadir (Cancelar)", color = if (isFocused) TV_FOCUSED_TEXT else Color.White, fontSize = 12.sp, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Bold)
                        }
                    }
                }
            )
        }

        // Delete Confirmation Dialog
        if (showDeleteConfirmDialog && playlistToDelete != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false; playlistToDelete = null },
                containerColor = parseHexColor(theme.surface),
                shape = RoundedCornerShape(16.dp),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("¿Eliminar Lista?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        "¿Estás seguro de que deseas eliminar la lista \"${playlistToDelete!!.name}\"? Esta acción no se puede deshacer.",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TvButton(
                        onClick = {
                            val idToDelete = playlistToDelete?.id
                            if (idToDelete != null) {
                                viewModel.deletePlaylist(idToDelete)
                            }
                            showDeleteConfirmDialog = false
                            playlistToDelete = null
                        },
                        containerColor = Color.Red,
                        focusedContainerColor = Color(0xFFFF5252),
                        focusedContentColor = Color.White,
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TvButton(
                        onClick = { showDeleteConfirmDialog = false; playlistToDelete = null },
                        containerColor = Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) { isFocused ->
                        Text("Cancelar", color = if (isFocused) TV_FOCUSED_TEXT else Color.LightGray, fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal)
                    }
                }
            )
        }
    }
}

@Composable
private fun StepRow(
    title: String,
    desc: String,
    status: String, // pending, active, completed, error
    theme: AppThemeColors
) {
    val alpha = if (status == "pending") 0.4f else 1.0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Step icon / Status indicator
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    when (status) {
                        "completed" -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                        "error" -> Color(0xFFC62828).copy(alpha = 0.15f)
                        "active" -> parseHexColor(theme.accent).copy(alpha = 0.15f)
                        else -> Color.White.copy(alpha = 0.05f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (status) {
                "completed" -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completado",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(13.dp)
                    )
                }
                "error" -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Error",
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(13.dp)
                    )
                }
                "active" -> {
                    CircularProgressIndicator(
                        color = parseHexColor(theme.accent),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(13.dp)
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.Gray)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = when (status) {
                    "completed" -> Color.LightGray
                    "error" -> Color(0xFFEF5350)
                    "active" -> Color.White
                    else -> Color.Gray
                },
                fontSize = 12.sp,
                fontWeight = if (status == "active") FontWeight.Bold else FontWeight.Medium
            )
            Text(
                text = desc,
                color = if (status == "active") Color.LightGray else Color.Gray,
                fontSize = 9.5.sp
            )
        }
    }
}

