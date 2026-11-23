package com.example.interfaz.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.example.interfaz.viewmodel.PlayerViewModel

@Composable
fun AudioTrackDialog(
    visible: Boolean,
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Idioma de Audio", color = Color.White) },
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
            val audioTracks = remember(visible) {
                playerViewModel.getTrackOptions(C.TRACK_TYPE_AUDIO)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Pistas disponibles",
                    color = Color(0xFFB07FEE),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                if (audioTracks.isEmpty()) {
                    Text("No hay otras pistas de audio disponibles", color = Color.LightGray, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(audioTracks) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .tvClickable {
                                        playerViewModel.selectAudioTrack(track)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = track.isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFFB07FEE),
                                        unselectedColor = Color.Gray
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = track.name,
                                    color = if (track.isSelected) Color(0xFFB07FEE) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
