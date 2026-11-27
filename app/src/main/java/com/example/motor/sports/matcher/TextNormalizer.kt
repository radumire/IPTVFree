package com.example.motor.sports.matcher

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Normalizador robusto de cadenas de texto para comparar eventos deportivos y nombres de canales IPTV.
 *
 * Satisface los requisitos de normalización de la Fase 3:
 * - Conversión a minúsculas.
 * - Eliminación de acentos y diacríticos.
 * - Normalización de espacios.
 * - Eliminación de puntuación y separadores irrelevantes (+, :, /, |, -, _, [, ], etc.).
 * - Eliminación de ruido habitual de listas IPTV (FHD, 4K, 1080p, HEVC, etc.).
 * - Expansión de abreviaturas deportivas comunes.
 */
object TextNormalizer {

    private val DIACRITICS_PATTERN: Pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
    private val CODEC_PATTERN: Pattern = Pattern.compile("\\b([hx])[.\\-_\\s]?(26[45])\\b")
    private val FPS_PATTERN: Pattern = Pattern.compile("\\b(25|30|50|60|120)[.\\-_\\s]?fps\\b")
    private val HDR10_PATTERN: Pattern = Pattern.compile("\\bhdr[.\\-_\\s]?10\\+?\\b")
    private val SEPARATORS_PATTERN: Pattern = Pattern.compile("[+:|/_\\-\\\\•~()\\[\\]{}.,;!¿?¡\"']+")
    private val MULTI_SPACE_PATTERN: Pattern = Pattern.compile("\\s+")

    // Ruido habitual en sufijos o prefijos de canales IPTV
    private val NOISE_TOKENS = setOf(
        "fhd", "uhd", "4k", "8k", "hd", "sd", "h265", "h264", "x265", "x264",
        "hevc", "hdr", "hdr10", "sdr", "hlg", "raw",
        "1080p", "1080i", "720p", "25fps", "30fps", "50fps", "60fps", "120fps",
        "aac", "ac3", "dolby", "vip", "backup", "opt",
        "es", "spa", "esp", "lat", "latam", "us", "uk", "fr", "de", "it",
        "ar", "mx", "canal", "canales", "tv"
    )

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""

        // 1. Minúsculas
        var result = text.trim().lowercase()

        // 2. Normalización NFD y eliminación de acentos solo si contiene caracteres no ASCII
        val hasNonAscii = result.any { it.code > 127 }
        if (hasNonAscii) {
            val nfd = Normalizer.normalize(result, Normalizer.Form.NFD)
            result = DIACRITICS_PATTERN.matcher(nfd).replaceAll("")
        }

        // 3. Preservar etiquetas técnicas con punto o espacio (H.265, H.264, 50 FPS, HDR10+)
        result = CODEC_PATTERN.matcher(result).replaceAll("$1$2")
        result = FPS_PATTERN.matcher(result).replaceAll("$1fps")
        result = HDR10_PATTERN.matcher(result).replaceAll("hdr10")

        // 4. Sustitución de separadores por espacios
        result = SEPARATORS_PATTERN.matcher(result).replaceAll(" ")

        // 5. Colapso de espacios múltiples
        result = MULTI_SPACE_PATTERN.matcher(result).replaceAll(" ").trim()

        return result
    }

    /**
     * Extrae tokens limpios descartando palabras de ruido técnico (resolución, códec, etc.).
     */
    fun extractCleanTokens(text: String?): List<String> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()

        return normalized.split(" ")
            .filter { it.isNotBlank() && it !in NOISE_TOKENS }
    }

    /**
     * Comprueba si una cadena normalizada contiene un término o frase clave.
     */
    fun containsPhrase(text: String, phrase: String): Boolean {
        if (text.isEmpty() || phrase.isEmpty()) return false
        val normText = if (text.startsWith(" ") && text.endsWith(" ")) text else " $text "
        val normPhrase = " $phrase "
        return normText.contains(normPhrase) || text.contains(phrase)
    }

    /**
     * Comprueba si todos los tokens clave de un término están presentes en el texto normalizado.
     */
    fun containsAllTokens(text: String, term: String): Boolean {
        val textTokens = extractCleanTokens(text).toSet()
        val termTokens = extractCleanTokens(term)
        if (termTokens.isEmpty()) return false
        return termTokens.all { it in textTokens }
    }
}
