package com.example.interfaz.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

data class AvatarOption(
    val id: String,
    val label: String,
    @DrawableRes val drawableRes: Int
)

const val DEFAULT_PROFILE_AVATAR = "avatar_plumber"

val PROFILE_AVATARS = listOf(
    AvatarOption(
        id = "avatar_plumber",
        label = "Héroe Retro",
        drawableRes = R.drawable.img_avatar_plumber_hero
    ),
    AvatarOption(
        id = "avatar_night",
        label = "Centinela",
        drawableRes = R.drawable.img_avatar_night_hero
    ),
    AvatarOption(
        id = "avatar_beast",
        label = "Camaleón",
        drawableRes = R.drawable.img_avatar_green_hero
    ),
    AvatarOption(
        id = "avatar_ninja",
        label = "Ciber Ninja",
        drawableRes = R.drawable.img_avatar_cyber_ninja
    ),
    AvatarOption(
        id = "avatar_space",
        label = "Piloto Estelar",
        drawableRes = R.drawable.img_avatar_space_hero
    ),
    AvatarOption(
        id = "avatar_retro_heroine",
        label = "Heroína Retro",
        drawableRes = R.drawable.img_avatar_retro_heroine
    ),
    AvatarOption(
        id = "avatar_night_heroine",
        label = "Sombra Felina",
        drawableRes = R.drawable.img_avatar_night_heroine
    ),
    AvatarOption(
        id = "avatar_cosmos_heroine",
        label = "Chica Cosmos",
        drawableRes = R.drawable.img_avatar_cosmos_heroine
    ),
    AvatarOption(
        id = "avatar_cyber_kunoichi",
        label = "Ciber Kunoichi",
        drawableRes = R.drawable.img_avatar_cyber_kunoichi
    ),
    AvatarOption(
        id = "avatar_space_heroine",
        label = "Piloto Galáctica",
        drawableRes = R.drawable.img_avatar_space_heroine
    )
)

@DrawableRes
fun resolveAvatarDrawable(avatarKey: String?): Int {
    if (avatarKey.isNullOrBlank()) return R.drawable.img_avatar_plumber_hero
    PROFILE_AVATARS.find { it.id == avatarKey }?.let { return it.drawableRes }
    return when (avatarKey) {
        "👤" -> R.drawable.img_avatar_plumber_hero
        "🤖" -> R.drawable.img_avatar_cyber_ninja
        "🦊" -> R.drawable.img_avatar_green_hero
        "🐻", "🐼" -> R.drawable.img_avatar_night_hero
        "🐱", "🐶" -> R.drawable.img_avatar_space_hero
        else -> R.drawable.img_avatar_plumber_hero
    }
}

fun resolveAvatarLabel(avatarKey: String?): String {
    if (avatarKey.isNullOrBlank()) return PROFILE_AVATARS.first().label
    PROFILE_AVATARS.find { it.id == avatarKey }?.let { return it.label }
    return when (avatarKey) {
        "🤖" -> "Ciber Ninja"
        "🦊" -> "Camaleón"
        "🐻", "🐼" -> "Centinela"
        "🐱", "🐶" -> "Piloto Estelar"
        else -> "Héroe Retro"
    }
}

@Composable
fun ProfileAvatarImage(
    avatarKey: String?,
    size: Dp,
    accentColor: Color,
    isFocused: Boolean = false,
    isSelected: Boolean = false,
    showAnimatedRing: Boolean = false,
    dimOverlay: Boolean = false,
    contentDescription: String = "Avatar de perfil",
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = when {
            isFocused -> 1.07f
            isSelected -> 1.04f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "avatarScale"
    )

    val ringAlpha = if (showAnimatedRing || isFocused) {
        val infiniteTransition = rememberInfiniteTransition(label = "avatarPulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "avatarPulseAlpha"
        )
        pulse
    } else if (isSelected) {
        0.9f
    } else {
        0.35f
    }

    val borderWidth = if (isFocused || isSelected) 2.5.dp else 1.5.dp
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            if (isFocused) Color.White else accentColor.copy(alpha = ringAlpha),
            accentColor.copy(alpha = ringAlpha)
        )
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(accentColor.copy(alpha = 0.18f))
            .border(width = borderWidth, brush = borderBrush, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = resolveAvatarDrawable(avatarKey)),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.08f)
                .alpha(if (dimOverlay) 0.55f else 1f)
        )
    }
}
