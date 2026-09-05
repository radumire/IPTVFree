package com.example.interfaz.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Global TV Focus Colors - Maximum contrast and visibility on TV screens
val TV_FOCUSED_BG = Color(0xFFFFD54F) // High-contrast Golden Yellow
val TV_FOCUSED_TEXT = Color(0xFF000000) // Intense pure black for crisp readability
val TV_FOCUSED_BORDER = Color(0xFFFFFFFF) // Crisp white border

@Composable
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    focusedBorderColor: Color = TV_FOCUSED_BORDER,
    focusedBorderWidth: Dp = 2.5.dp,
    focusedBackgroundColor: Color = TV_FOCUSED_BG,
    unfocusedBackgroundColor: Color = Color.Transparent,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    return this
        .onFocusChanged {
            isFocused = it.isFocused
            onFocusChange?.invoke(it.isFocused)
        }
        .then(
            if (onClick != null) {
                Modifier.onKeyEvent { keyEvent ->
                    if (!enabled) return@onKeyEvent false
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    val isConfirm = keyEvent.key == Key.DirectionCenter ||
                            keyEvent.key == Key.Enter ||
                            keyEvent.key == Key.NumPadEnter ||
                            keyEvent.key == Key.Spacebar ||
                            keyEvent.key == Key.ButtonA ||
                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A

                    if (isConfirm) {
                        if (keyEvent.type == KeyEventType.KeyUp) {
                            onClick()
                        }
                        true
                    } else {
                        false
                    }
                }
            } else Modifier
        )
        .border(
            width = if (isFocused) focusedBorderWidth else 0.dp,
            color = if (isFocused) focusedBorderColor else Color.Transparent,
            shape = shape
        )
        .background(
            color = if (isFocused) focusedBackgroundColor else unfocusedBackgroundColor,
            shape = shape
        )
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onClick = onClick
                )
            } else Modifier
        )
}

@Composable
fun Modifier.tvClickable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    focusedBorderColor: Color = TV_FOCUSED_BORDER,
    focusedBorderWidth: Dp = 2.5.dp,
    focusedBackgroundColor: Color = TV_FOCUSED_BG,
    unfocusedBackgroundColor: Color = Color.Transparent,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    return this.tvFocusable(
        enabled = enabled,
        shape = shape,
        focusedBorderColor = focusedBorderColor,
        focusedBorderWidth = focusedBorderWidth,
        focusedBackgroundColor = focusedBackgroundColor,
        unfocusedBackgroundColor = unfocusedBackgroundColor,
        onFocusChange = onFocusChange,
        onClick = onClick
    )
}

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    containerColor: Color = Color(0xFF2E2E3E),
    contentColor: Color = Color.White,
    focusedContainerColor: Color = TV_FOCUSED_BG,
    focusedContentColor: Color = TV_FOCUSED_TEXT,
    focusedBorderColor: Color = TV_FOCUSED_BORDER,
    focusedBorderWidth: Dp = 2.5.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val currentBg = if (isFocused) focusedContainerColor else containerColor
    val currentContentColor = if (isFocused) focusedContentColor else contentColor

    Box(
        modifier = modifier
            .clip(shape)
            .tvClickable(
                enabled = enabled,
                shape = shape,
                focusedBackgroundColor = focusedContainerColor,
                unfocusedBackgroundColor = containerColor,
                focusedBorderColor = focusedBorderColor,
                focusedBorderWidth = focusedBorderWidth,
                onFocusChange = { isFocused = it },
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides currentContentColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                content(isFocused)
            }
        }
    }
}

@Composable
fun TvIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    containerColor: Color = Color.Transparent,
    contentColor: Color = Color.White,
    focusedContainerColor: Color = TV_FOCUSED_BG,
    focusedContentColor: Color = TV_FOCUSED_TEXT,
    focusedBorderColor: Color = TV_FOCUSED_BORDER,
    focusedBorderWidth: Dp = 2.5.dp,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val currentBg = if (isFocused) focusedContainerColor else containerColor
    val currentContentColor = if (isFocused) focusedContentColor else contentColor

    Box(
        modifier = modifier
            .clip(shape)
            .tvClickable(
                enabled = enabled,
                shape = shape,
                focusedBackgroundColor = focusedContainerColor,
                unfocusedBackgroundColor = containerColor,
                focusedBorderColor = focusedBorderColor,
                focusedBorderWidth = focusedBorderWidth,
                onFocusChange = { isFocused = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides currentContentColor) {
            content(isFocused)
        }
    }
}
