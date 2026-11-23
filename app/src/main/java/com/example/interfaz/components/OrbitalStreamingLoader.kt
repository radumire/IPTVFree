package com.example.interfaz.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Indicador de carga orbital doble (Opción 2):
 * Dos anillos concéntricos contrarrotatorios con estela degradada en tonos lila y blanco,
 * halo oscuro suave para contraste sobre cualquier escena y punto central pulsante.
 */
@Composable
fun OrbitalStreamingLoader(
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    accentColor: Color = Color(0xFFB07FEE),
    highlightColor: Color = Color(0xFFEBD9FF),
    secondaryColor: Color = Color.White,
    contentDesc: String = "Cargando vídeo"
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbital_streaming_loader")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_orbital_rotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_orbital_rotation"
    )

    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    Box(
        modifier = modifier
            .size(size)
            .testTag("player_orbital_loader")
            .semantics { contentDescription = contentDesc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasCenter = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f

            // Ambient dark halo so rings remain sharp over bright frames
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.55f),
                        accentColor.copy(alpha = 0.14f * corePulse),
                        Color.Transparent
                    ),
                    center = canvasCenter,
                    radius = maxRadius
                ),
                center = canvasCenter,
                radius = maxRadius
            )

            val outerStrokePx = 3.6.dp.toPx()
            val outerRadius = maxRadius - outerStrokePx * 1.4f
            val outerTopLeft = Offset(canvasCenter.x - outerRadius, canvasCenter.y - outerRadius)
            val outerArcSize = Size(outerRadius * 2f, outerRadius * 2f)

            // Subtle orbital track for outer ring
            drawCircle(
                color = accentColor.copy(alpha = 0.14f),
                radius = outerRadius,
                center = canvasCenter,
                style = Stroke(width = outerStrokePx * 0.7f)
            )

            // Outer ring: lilac gradient comet tail rotating clockwise
            rotate(degrees = outerRotation, pivot = canvasCenter) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.12f to Color.Transparent,
                            0.45f to accentColor.copy(alpha = 0.38f),
                            0.78f to accentColor,
                            0.94f to highlightColor,
                            0.99f to Color.Transparent
                        ),
                        center = canvasCenter
                    ),
                    startAngle = 30f,
                    sweepAngle = 305f,
                    useCenter = false,
                    topLeft = outerTopLeft,
                    size = outerArcSize,
                    style = Stroke(width = outerStrokePx, cap = StrokeCap.Round)
                )

                // Soft glowing leading node at the tip (~335 deg)
                val tipRad = (335.0 * PI / 180.0)
                val tipCenter = Offset(
                    x = canvasCenter.x + (outerRadius * cos(tipRad)).toFloat(),
                    y = canvasCenter.y + (outerRadius * sin(tipRad)).toFloat()
                )
                drawCircle(
                    color = highlightColor.copy(alpha = 0.9f),
                    radius = outerStrokePx * 0.75f,
                    center = tipCenter
                )
            }

            // Inner ring: pure white counter-rotating orbital arc
            val innerStrokePx = 2.6.dp.toPx()
            val innerRadius = outerRadius * 0.65f
            val innerTopLeft = Offset(canvasCenter.x - innerRadius, canvasCenter.y - innerRadius)
            val innerArcSize = Size(innerRadius * 2f, innerRadius * 2f)

            drawCircle(
                color = secondaryColor.copy(alpha = 0.10f),
                radius = innerRadius,
                center = canvasCenter,
                style = Stroke(width = innerStrokePx * 0.65f)
            )

            rotate(degrees = innerRotation, pivot = canvasCenter) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.11f to secondaryColor,
                            0.18f to secondaryColor.copy(alpha = 0.92f),
                            0.52f to secondaryColor.copy(alpha = 0.35f),
                            0.84f to Color.Transparent,
                            1.00f to Color.Transparent
                        ),
                        center = canvasCenter
                    ),
                    startAngle = 45f,
                    sweepAngle = 275f,
                    useCenter = false,
                    topLeft = innerTopLeft,
                    size = innerArcSize,
                    style = Stroke(width = innerStrokePx, cap = StrokeCap.Round)
                )
            }

            // Subtle breathing center core
            drawCircle(
                color = accentColor.copy(alpha = 0.35f * corePulse),
                radius = 5.5.dp.toPx(),
                center = canvasCenter
            )
            drawCircle(
                color = secondaryColor.copy(alpha = 0.75f * corePulse),
                radius = 2.4.dp.toPx(),
                center = canvasCenter
            )
        }
    }
}
