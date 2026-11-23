package com.example.interfaz

data class AppThemeColors(
    val id: String,
    val name: String,
    val emoji: String,
    val bg: String,
    val surface: String,
    val card: String,
    val cardHover: String,
    val accent: String,
    val accentLight: String,
    val overlay: String,
    val previewGrad: String
)

val THEMES_LIST = listOf(
    AppThemeColors(
        id = "purple", name = "Púrpura", emoji = "🟣",
        bg = "#5B2D8E", surface = "#7B3DBE", card = "#6B2FA8", cardHover = "#7D3DC2",
        accent = "#9B5FDE", accentLight = "#B07FEE", overlay = "rgba(30,0,60,0.85)",
        previewGrad = "linear-gradient(135deg,#5B2D8E,#9B5FDE)"
    ),
    AppThemeColors(
        id = "dark", name = "Oscuro", emoji = "⚫",
        bg = "#0F0F13", surface = "#1E1E28", card = "#18181F", cardHover = "#25252F",
        accent = "#6C63FF", accentLight = "#9C95FF", overlay = "rgba(0,0,0,0.88)",
        previewGrad = "linear-gradient(135deg,#0F0F13,#6C63FF)"
    ),
    AppThemeColors(
        id = "blue", name = "Azul", emoji = "🔵",
        bg = "#0B2D5E", surface = "#1A4A8A", card = "#153F78", cardHover = "#1E528F",
        accent = "#3B82F6", accentLight = "#60A5FA", overlay = "rgba(5,15,40,0.88)",
        previewGrad = "linear-gradient(135deg,#0B2D5E,#3B82F6)"
    ),
    AppThemeColors(
        id = "green", name = "Verde", emoji = "🟢",
        bg = "#0D3B2E", surface = "#1A5C44", card = "#154D38", cardHover = "#1E6048",
        accent = "#10B981", accentLight = "#34D399", overlay = "rgba(5,25,18,0.88)",
        previewGrad = "linear-gradient(135deg,#0D3B2E,#10B981)"
    ),
    AppThemeColors(
        id = "orange", name = "Naranja", emoji = "🟠",
        bg = "#6B2500", surface = "#A83A00", card = "#8C3000", cardHover = "#A03800",
        accent = "#F97316", accentLight = "#FB923C", overlay = "rgba(40,10,0,0.88)",
        previewGrad = "linear-gradient(135deg,#6B2500,#F97316)"
    ),
    AppThemeColors(
        id = "red", name = "Rojo", emoji = "🔴",
        bg = "#5C0A0A", surface = "#8B1A1A", card = "#751414", cardHover = "#8A1C1C",
        accent = "#EF4444", accentLight = "#F87171", overlay = "rgba(40,0,0,0.88)",
        previewGrad = "linear-gradient(135deg,#5C0A0A,#EF4444)"
    ),
    AppThemeColors(
        id = "yellow", name = "Amarillo", emoji = "🟡",
        bg = "#4A3800", surface = "#6E5200", card = "#5C4500", cardHover = "#6A5000",
        accent = "#EAB308", accentLight = "#FACC15", overlay = "rgba(30,22,0,0.88)",
        previewGrad = "linear-gradient(135deg,#4A3800,#EAB308)"
    ),
    AppThemeColors(
        id = "cyan", name = "Cian", emoji = "🩵",
        bg = "#083344", surface = "#0E7490", card = "#155E75", cardHover = "#164E63",
        accent = "#06B6D4", accentLight = "#67E8F9", overlay = "rgba(4,20,30,0.88)",
        previewGrad = "linear-gradient(135deg,#083344,#06B6D4)"
    ),
    AppThemeColors(
        id = "magenta", name = "Magenta", emoji = "🌸",
        bg = "#4A044E", surface = "#701A75", card = "#581C87", cardHover = "#86198F",
        accent = "#D946EF", accentLight = "#F0ABFC", overlay = "rgba(35,2,40,0.88)",
        previewGrad = "linear-gradient(135deg,#4A044E,#D946EF)"
    ),
    AppThemeColors(
        id = "teal", name = "Turquesa", emoji = "🌊",
        bg = "#042F2E", surface = "#115E59", card = "#0F766E", cardHover = "#134E4A",
        accent = "#14B8A6", accentLight = "#5EEAD4", overlay = "rgba(2,25,25,0.88)",
        previewGrad = "linear-gradient(135deg,#042F2E,#14B8A6)"
    ),
    AppThemeColors(
        id = "indigo", name = "Índigo", emoji = "🌌",
        bg = "#1E1B4B", surface = "#312E81", card = "#27276E", cardHover = "#3730A3",
        accent = "#6366F1", accentLight = "#818CF8", overlay = "rgba(15,10,40,0.88)",
        previewGrad = "linear-gradient(135deg,#1E1B4B,#6366F1)"
    ),
    AppThemeColors(
        id = "crimson", name = "Carmesí", emoji = "🍷",
        bg = "#4C0519", surface = "#881337", card = "#70102C", cardHover = "#9F1239",
        accent = "#F43F5E", accentLight = "#FB7185", overlay = "rgba(30,2,10,0.88)",
        previewGrad = "linear-gradient(135deg,#4C0519,#F43F5E)"
    ),
    AppThemeColors(
        id = "emerald", name = "Esmeralda", emoji = "🍃",
        bg = "#064E3B", surface = "#047857", card = "#065F46", cardHover = "#059669",
        accent = "#10B981", accentLight = "#6EE7B7", overlay = "rgba(3,35,25,0.88)",
        previewGrad = "linear-gradient(135deg,#064E3B,#10B981)"
    )
)

data class FailedChannelThemeColors(
    val bg: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
    val title: androidx.compose.ui.graphics.Color,
    val subtitle: androidx.compose.ui.graphics.Color
)

fun AppThemeColors.getFailedChannelColors(): FailedChannelThemeColors {
    val rawHex = accent.removePrefix("#")
    val rgb = if (rawHex.length == 6) {
        rawHex.toLongOrNull(16)?.toInt() ?: 0x3B82F6
    } else {
        0x3B82F6
    }

    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta == 0f -> 0f
        max == r -> (((g - b) / delta) % 6f) * 60f
        max == g -> (((b - r) / delta) + 2f) * 60f
        else -> (((r - g) / delta) + 4f) * 60f
    }.let { if (it < 0f) it + 360f else it }

    fun hsvToColor(h: Float, s: Float, v: Float, alpha: Float = 1f): androidx.compose.ui.graphics.Color {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return androidx.compose.ui.graphics.Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = alpha
        )
    }

    // Darker, desaturated background derived from current theme hue
    val bgColor = hsvToColor(hue, 0.45f, 0.18f)

    // Border: slightly brighter, desaturated accent
    val borderColor = hsvToColor(hue, 0.40f, 0.42f)

    // Text: high-contrast, desaturated pastel tinted with theme hue
    val titleColor = hsvToColor(hue, 0.22f, 0.90f)

    // Subtitle: medium-contrast pastel tinted with theme hue
    val subtitleColor = hsvToColor(hue, 0.20f, 0.70f)

    return FailedChannelThemeColors(
        bg = bgColor,
        border = borderColor,
        title = titleColor,
        subtitle = subtitleColor
    )
}

