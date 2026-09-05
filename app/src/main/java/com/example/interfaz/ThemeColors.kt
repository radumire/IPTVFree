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
    )
)
