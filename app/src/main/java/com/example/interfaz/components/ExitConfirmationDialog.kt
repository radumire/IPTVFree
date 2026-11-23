package com.example.interfaz.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.interfaz.AppThemeColors
import com.example.parseHexColor

/**
 * TV and Mobile friendly Exit Confirmation Dialog.
 *
 * Ensures TV users never exit accidentally:
 * - 'Cancelar' button receives initial focus by default.
 * - 'Aceptar' button cleanly closes the application.
 * - Remote Back button also dismisses the dialog safely.
 */
@Composable
fun ExitConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    theme: AppThemeColors
) {
    val themeSurface = remember(theme) { parseHexColor(theme.surface) }
    val themeAccent = remember(theme) { parseHexColor(theme.accent) }
    val themeCard = remember(theme) { parseHexColor(theme.card) }

    // Focus Requester on CANCEL button by default to prevent accidental exit on TV
    val cancelFocusRequester = rememberTvInitialFocusRequester(delayMs = 100L)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.80f))
                .testTag("exit_confirmation_dialog"),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = themeSurface,
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 460.dp)
                    .fillMaxWidth(0.85f)
                    .border(
                        width = 1.5.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                themeAccent.copy(alpha = 0.6f),
                                themeAccent.copy(alpha = 0.15f)
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Power / Exit Icon in soft circular accent container
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(themeAccent.copy(alpha = 0.18f))
                            .border(1.dp, themeAccent.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Salir",
                            tint = themeAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Main Title
                    Text(
                        text = "¿Seguro que quieres salir?",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    // Subtitle / Description
                    Text(
                        text = "Se cerrará la aplicación IPTV y volverás al menú de tu televisor.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Buttons Row: Cancelar (focused first) and Aceptar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // CANCELAR BUTTON (Primary default focus)
                        TvButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("exit_dialog_cancel_button"),
                            shape = RoundedCornerShape(12.dp),
                            focusRequester = cancelFocusRequester,
                            containerColor = themeCard.copy(alpha = 0.85f),
                            focusedContainerColor = TV_FOCUSED_BG,
                            focusedBorderColor = themeAccent
                        ) { isFocused ->
                            Text(
                                text = "Cancelar",
                                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.9f),
                                fontSize = 15.sp,
                                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold
                            )
                        }

                        // ACEPTAR BUTTON
                        TvButton(
                            onClick = onConfirm,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("exit_dialog_confirm_button"),
                            shape = RoundedCornerShape(12.dp),
                            containerColor = Color(0x33EF4444),
                            focusedContainerColor = Color(0xCCEF4444),
                            focusedBorderColor = Color(0xFFEF4444)
                        ) { isFocused ->
                            Text(
                                text = "Aceptar",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
