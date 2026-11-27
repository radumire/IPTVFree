package com.example.interfaz.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.motor.db.IptvItem
import com.example.motor.sports.matcher.dedupIdentityKey

/**
 * Overlay semitransparente lateral que muestra los canales de la categoría seleccionada
 * sobre el reproductor en pantalla completa, permitiendo cambiar de canal directamente sin salir.
 */
@Composable
fun PlayerChannelsOverlay(
    visible: Boolean,
    categoryName: String,
    channels: List<IptvItem>,
    currentChannelName: String?,
    onSelectChannel: (IptvItem) -> Unit,
    onDismiss: () -> Unit,
    currentChannel: IptvItem? = null,
    currentChannelUrl: String? = null,
    accentColor: Color = Color(0xFFB07FEE),
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it },
        modifier = modifier
    ) {
        val listState = rememberLazyListState()
        val initialFocusRequester = remember { FocusRequester() }

        val currentChannelIndex = remember(channels, currentChannel, currentChannelUrl, currentChannelName) {
            if (currentChannel != null) {
                val targetKey = currentChannel.dedupIdentityKey()
                val targetGroup = currentChannel.group.trim()
                val exactWithGroup = channels.indexOfFirst {
                    it.dedupIdentityKey() == targetKey && it.group.trim().equals(targetGroup, ignoreCase = true)
                }
                if (exactWithGroup >= 0) {
                    exactWithGroup
                } else {
                    val exactKey = channels.indexOfFirst { it.dedupIdentityKey() == targetKey }
                    if (exactKey >= 0) {
                        exactKey
                    } else {
                        val targetCmd = currentChannel.stalkerCmd?.trim().orEmpty()
                        val byCmd = if (targetCmd.isNotEmpty()) {
                            channels.indexOfFirst { it.stalkerCmd?.trim().orEmpty() == targetCmd }
                        } else -1
                        if (byCmd >= 0) byCmd else {
                            val targetUrl = currentChannelUrl?.trim().takeIf { !it.isNullOrBlank() }
                                ?: currentChannel.url?.trim().takeIf { !it.isNullOrBlank() }
                            val byUrl = if (targetUrl != null) {
                                channels.indexOfFirst { it.url?.trim()?.equals(targetUrl, ignoreCase = true) == true }
                            } else -1
                            if (byUrl >= 0) byUrl else channels.indexOfFirst {
                                it.name.equals(currentChannelName, ignoreCase = true)
                            }
                        }
                    }
                }
            } else {
                val targetUrl = currentChannelUrl?.trim().takeIf { !it.isNullOrBlank() }
                val byUrl = if (targetUrl != null) {
                    channels.indexOfFirst { it.url?.trim()?.equals(targetUrl, ignoreCase = true) == true }
                } else -1
                if (byUrl >= 0) byUrl else channels.indexOfFirst {
                    it.name.equals(currentChannelName, ignoreCase = true)
                }
            }
        }

        val targetInitialIndex = currentChannelIndex.coerceAtLeast(0)

        // Hacer auto-scroll y solicitar foco al canal actual cuando se abra la lista
        LaunchedEffect(visible, targetInitialIndex) {
            if (visible && channels.isNotEmpty()) {
                listState.scrollToItem(targetInitialIndex)
                kotlinx.coroutines.delay(120L)
                try {
                    initialFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }

        Box(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .padding(top = 16.dp, bottom = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF160E28).copy(alpha = 0.40f),
                            Color(0xFF120A20).copy(alpha = 0.45f)
                        )
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = Color(0xFFB07FEE).copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Evita que clics dentro del panel lo cierren accidentalmente
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // ─── CABECERA DEL PANEL (CANALES + NOMBRE CATEGORÍA) ───
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF9B5FDE).copy(alpha = 0.20f))
                                .border(0.8.dp, Color(0xFFB07FEE).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CANALES",
                                color = Color(0xFFB07FEE),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = categoryName.ifBlank { "Lista de Canales" },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF9B5FDE).copy(alpha = 0.20f))
                            .border(0.8.dp, Color(0xFFB07FEE).copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar canales",
                            tint = Color(0xFFB07FEE),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Contador de canales disponibles en la categoría
                Text(
                    text = "${channels.size} canales disponibles",
                    color = Color.LightGray.copy(alpha = 0.70f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ─── LISTA SCROLLABLE DE CANALES ───
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { index, item -> "${item.dedupIdentityKey()}_${item.group}_$index" }
                    ) { index, channel ->
                        val isCurrentPlaying = index == currentChannelIndex
                        var isItemFocused by remember { mutableStateOf(false) }

                        val itemBg = if (isItemFocused) {
                            Color(0xFF9B5FDE).copy(alpha = 0.40f)
                        } else if (isCurrentPlaying) {
                            Color(0xFF9B5FDE).copy(alpha = 0.25f)
                        } else {
                            Color(0xFF9B5FDE).copy(alpha = 0.08f)
                        }

                        val itemBorderColor = if (isItemFocused) {
                            Color(0xFFD4B2FF)
                        } else if (isCurrentPlaying) {
                            Color(0xFFB07FEE).copy(alpha = 0.65f)
                        } else {
                            Color(0xFFB07FEE).copy(alpha = 0.18f)
                        }

                        val requester = if (index == targetInitialIndex) {
                            initialFocusRequester
                        } else {
                            null
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("player_overlay_channel_$index")
                                .then(
                                    if (requester != null) Modifier.focusRequester(requester) else Modifier
                                )
                                .tvCardFocusable(
                                    shape = RoundedCornerShape(10.dp),
                                    scaleFocused = 1.02f,
                                    elevationFocused = 4.dp,
                                    unfocusedBorderColor = itemBorderColor,
                                    onFocusChange = { isItemFocused = it },
                                    onClick = { onSelectChannel(channel) }
                                )
                                .background(itemBg)
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Logo del canal
                                    if (!channel.logo.isNullOrBlank()) {
                                        AsyncImage(
                                            model = channel.logo,
                                            contentDescription = channel.name,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White.copy(alpha = 0.12f)),
                                            contentScale = ContentScale.Fit
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF9B5FDE).copy(alpha = 0.20f))
                                                .border(0.8.dp, Color(0xFFB07FEE).copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.LiveTv,
                                                contentDescription = null,
                                                tint = Color(0xFFB07FEE),
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Nombre del canal
                                    Text(
                                        text = channel.name,
                                        color = if (isItemFocused) TV_FOCUSED_TEXT else Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isCurrentPlaying || isItemFocused) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Indicador de canal en reproducción actual (punto lila)
                                if (isCurrentPlaying) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFB07FEE))
                                            .testTag("player_overlay_active_dot_$index")
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
