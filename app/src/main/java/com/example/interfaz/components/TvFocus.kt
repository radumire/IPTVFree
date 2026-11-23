package com.example.interfaz.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Global TV Focus CompositionLocals - Match active theme dynamically across the entire app
val LocalTvFocusAccent = staticCompositionLocalOf { Color(0xFF3B82F6) }
val LocalTvFocusAccentLight = staticCompositionLocalOf { Color(0xFF60A5FA) }

val TV_FOCUSED_BORDER = Color(0x01020305) // Sentinel color meaning "illuminate with active theme accent border"
val TV_FOCUSED_TEXT = Color(0xFFFFFFFF) // Crisp bright white text
val TV_FOCUSED_BG = Color(0x01020304) // Sentinel color meaning "illuminate with active theme halo and hover glow"

/**
 * Creates and remembers a FocusRequester that automatically requests focus after the screen renders.
 * Ensures the TV user sees an immediate logical focus point on arrival without touching the screen.
 */
@Composable
fun rememberTvInitialFocusRequester(delayMs: Long = 120L): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(delayMs)
        try {
            requester.requestFocus()
        } catch (_: Exception) {}
    }
    return requester
}

@Composable
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    unfocusedBorderColor: Color = Color.Transparent,
    unfocusedBorderWidth: Dp = 1.dp,
    focusedBorderColor: Color? = null,
    focusedBorderWidth: Dp = 1.5.dp,
    focusedBackgroundColor: Color? = null,
    unfocusedBackgroundColor: Color = Color.Transparent,
    scaleOnFocus: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val themeAccent = LocalTvFocusAccent.current
    val themeAccentLight = LocalTvFocusAccentLight.current

    // Vivo-style focus:
    // Instead of turning into a flat, solid theme block,
    // the element maintains its base container background with an illuminated hover glow:
    val effectiveFocusedBg = if (focusedBackgroundColor == null ||
        focusedBackgroundColor == Color.Unspecified ||
        focusedBackgroundColor == TV_FOCUSED_BG
    ) {
        if (unfocusedBackgroundColor != Color.Transparent) {
            themeAccent.copy(alpha = 0.22f).compositeOver(unfocusedBackgroundColor)
        } else {
            themeAccent.copy(alpha = 0.20f)
        }
    } else {
        focusedBackgroundColor
    }

    val effectiveBorder = when {
        focusedBorderColor == null || focusedBorderColor == TV_FOCUSED_BORDER -> themeAccent
        else -> focusedBorderColor
    }

    val animatedBg by animateColorAsState(
        targetValue = if (isFocused) effectiveFocusedBg else unfocusedBackgroundColor,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusBg"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused && scaleOnFocus) 1.04f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "tvFocusScale"
    )

    val baseModifier = if (focusRequester != null) this.focusRequester(focusRequester) else this

    return baseModifier
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
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
        .then(
            if (isFocused) {
                Modifier
                    .shadow(
                        elevation = 8.dp,
                        shape = shape,
                        spotColor = themeAccent,
                        ambientColor = themeAccent.copy(alpha = 0.45f)
                    )
                    .border(
                        width = focusedBorderWidth,
                        color = effectiveBorder,
                        shape = shape
                    )
                    .then(
                        if (animatedBg != Color.Transparent) {
                            Modifier.background(color = animatedBg, shape = shape)
                        } else Modifier
                    )
            } else {
                Modifier
                    .then(
                        if (unfocusedBorderColor != Color.Transparent) {
                            Modifier.border(
                                width = unfocusedBorderWidth,
                                color = unfocusedBorderColor,
                                shape = shape
                            )
                        } else Modifier
                    )
                    .then(
                        if (unfocusedBackgroundColor != Color.Transparent) {
                            Modifier.background(color = animatedBg, shape = shape)
                        } else Modifier
                    )
            }
        )
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onClick = onClick
                )
            } else {
                Modifier.focusable(enabled = enabled, interactionSource = interactionSource)
            }
        )
}

@Composable
fun Modifier.tvClickable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    unfocusedBorderColor: Color = Color.Transparent,
    unfocusedBorderWidth: Dp = 1.dp,
    focusedBorderColor: Color? = null,
    focusedBorderWidth: Dp = 1.5.dp,
    focusedBackgroundColor: Color? = null,
    unfocusedBackgroundColor: Color = Color.Transparent,
    scaleOnFocus: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    return this.tvFocusable(
        enabled = enabled,
        shape = shape,
        unfocusedBorderColor = unfocusedBorderColor,
        unfocusedBorderWidth = unfocusedBorderWidth,
        focusedBorderColor = focusedBorderColor,
        focusedBorderWidth = focusedBorderWidth,
        focusedBackgroundColor = focusedBackgroundColor,
        unfocusedBackgroundColor = unfocusedBackgroundColor,
        scaleOnFocus = scaleOnFocus,
        focusRequester = focusRequester,
        onFocusChange = onFocusChange,
        onClick = onClick
    )
}

/**
 * Standard Vivo-style card focus for media cards, channels, categories, and list items.
 *
 * Provides:
 * - Animated micro-scaling (1.04f).
 * - Halo glow / shadow with active theme accent illumination.
 * - Dynamic 1.5.dp border matching active theme accent when focused (and subtle 1.dp border when unfocused).
 * - Background illumination using theme accent hover glow.
 * - Hardware D-Pad key handling (Center/Enter).
 */
@Composable
fun Modifier.tvCardFocusable(
    shape: Shape = RoundedCornerShape(10.dp),
    scaleFocused: Float = 1.04f,
    elevationFocused: Dp = 6.dp,
    unfocusedBorderColor: Color = Color.White.copy(alpha = 0.12f),
    focusedBorderColor: Color? = null,
    focusedBorderWidth: Dp = 1.5.dp,
    unfocusedBackgroundColor: Color = Color.Transparent,
    focusedBackgroundColor: Color? = null,
    scaleOnFocus: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val themeAccent = LocalTvFocusAccent.current
    val effectiveBorderColor = when {
        isFocused -> focusedBorderColor ?: themeAccent
        else -> unfocusedBorderColor
    }
    val effectiveBorderWidth = if (isFocused) focusedBorderWidth else 1.dp

    val targetScale = if (isFocused && scaleOnFocus) scaleFocused else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 150),
        label = "tvCardScale"
    )

    val effectiveFocusedBg = if (focusedBackgroundColor == null ||
        focusedBackgroundColor == Color.Unspecified ||
        focusedBackgroundColor == TV_FOCUSED_BG
    ) {
        if (unfocusedBackgroundColor != Color.Transparent) {
            themeAccent.copy(alpha = 0.22f).compositeOver(unfocusedBackgroundColor)
        } else {
            themeAccent.copy(alpha = 0.20f)
        }
    } else {
        focusedBackgroundColor
    }

    val animatedBg by animateColorAsState(
        targetValue = if (isFocused) effectiveFocusedBg else unfocusedBackgroundColor,
        animationSpec = tween(durationMillis = 150),
        label = "tvCardBg"
    )

    val interactionSource = remember { MutableInteractionSource() }

    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (isFocused) {
                Modifier.shadow(
                    elevation = elevationFocused,
                    shape = shape,
                    spotColor = themeAccent,
                    ambientColor = themeAccent.copy(alpha = 0.40f)
                )
            } else Modifier
        )
        .border(
            width = effectiveBorderWidth,
            color = effectiveBorderColor,
            shape = shape
        )
        .then(
            if (animatedBg != Color.Transparent) {
                Modifier.background(color = animatedBg, shape = shape)
            } else Modifier
        )
        .clip(shape)
        .onFocusChanged {
            isFocused = it.isFocused
            onFocusChange?.invoke(it.isFocused)
        }
        .then(
            if (onClick != null) {
                Modifier
                    .onKeyEvent { keyEvent ->
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

                        if (isConfirm && keyEvent.type == KeyEventType.KeyUp) {
                            onClick()
                            true
                        } else {
                            false
                        }
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true),
                        onClick = onClick
                    )
            } else {
                Modifier.focusable(interactionSource = interactionSource)
            }
        )
}

// =========================================================================================
// REGLA DE DISEÑO PERMANENTE DE LA APLICACIÓN:
// "TODO NUEVO CONTROL INTERACTIVO QUE REPRESENTE UNA ACCIÓN DE USUARIO DEBE UTILIZAR EL
//  COMPONENTE DE BOTÓN ESTÁNDAR DE LA APLICACIÓN (TvButton) Y RESPETAR SU ESTILO VISUAL."
//
// 1. Usar TvButton para todas las acciones primarias, secundarias y de diálogo.
// 2. Respetar el estilo de foco Android TV, bordes luminosos y escala visual.
// 3. Respetar colores semánticos con fondo SIEMPRE VISIBLE:
//    - Primario (Aceptar, Añadir, Guardar, Confirmar): parseHexColor(theme.accent)
//    - Secundario (Cancelar, Cerrar, Volver): TV_BUTTON_SECONDARY_BG
//    - Destructivo (Eliminar, Borrar): TV_BUTTON_DANGER_BG
// 4. NO usar Text + clickable como sustituto de botones de acción.
// 5. NO usar TvButton con containerColor = Color.Transparent para acciones normales de usuario.
// =========================================================================================
val TV_BUTTON_SECONDARY_BG = Color(0x24FFFFFF) // Fondo translúcido visible (~14% alpha) para botones secundarios
val TV_BUTTON_DANGER_BG = Color(0xFFD32F2F)    // Rojo para acciones destructivas

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    containerColor: Color = Color(0x22FFFFFF),
    unfocusedBorderColor: Color = Color.White.copy(alpha = 0.18f),
    unfocusedBorderWidth: Dp = 1.dp,
    contentColor: Color = Color.White,
    focusedContainerColor: Color = TV_FOCUSED_BG,
    focusedContentColor: Color = TV_FOCUSED_TEXT,
    focusedBorderColor: Color = TV_FOCUSED_BORDER,
    focusedBorderWidth: Dp = 1.5.dp,
    focusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val themeAccent = LocalTvFocusAccent.current
    val effectiveFocusedBg = if (focusedContainerColor == TV_FOCUSED_BG) {
        if (containerColor != Color.Transparent) {
            themeAccent.copy(alpha = 0.22f).compositeOver(containerColor)
        } else {
            themeAccent.copy(alpha = 0.20f)
        }
    } else {
        focusedContainerColor
    }
    val currentContentColor = if (isFocused) focusedContentColor else contentColor

    Box(
        modifier = modifier
            .clip(shape)
            .tvClickable(
                enabled = enabled,
                shape = shape,
                unfocusedBorderColor = unfocusedBorderColor,
                unfocusedBorderWidth = unfocusedBorderWidth,
                focusedBackgroundColor = effectiveFocusedBg,
                unfocusedBackgroundColor = containerColor,
                focusedBorderColor = focusedBorderColor,
                focusedBorderWidth = focusedBorderWidth,
                focusRequester = focusRequester,
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
    containerColor: Color = Color(0x22FFFFFF),
    unfocusedBorderColor: Color = Color.White.copy(alpha = 0.18f),
    unfocusedBorderWidth: Dp = 1.dp,
    contentColor: Color = Color.White,
    focusedContainerColor: Color = TV_FOCUSED_BG,
    focusedContentColor: Color = TV_FOCUSED_TEXT,
    focusedBorderColor: Color = TV_FOCUSED_BORDER,
    focusedBorderWidth: Dp = 1.5.dp,
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val themeAccent = LocalTvFocusAccent.current
    val effectiveFocusedBg = if (focusedContainerColor == TV_FOCUSED_BG) {
        if (containerColor != Color.Transparent) {
            themeAccent.copy(alpha = 0.22f).compositeOver(containerColor)
        } else {
            themeAccent.copy(alpha = 0.20f)
        }
    } else {
        focusedContainerColor
    }
    val currentContentColor = if (isFocused) focusedContentColor else contentColor

    Box(
        modifier = modifier
            .clip(shape)
            .tvClickable(
                enabled = enabled,
                shape = shape,
                unfocusedBorderColor = unfocusedBorderColor,
                unfocusedBorderWidth = unfocusedBorderWidth,
                focusedBackgroundColor = effectiveFocusedBg,
                unfocusedBackgroundColor = containerColor,
                focusedBorderColor = focusedBorderColor,
                focusedBorderWidth = focusedBorderWidth,
                focusRequester = focusRequester,
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
