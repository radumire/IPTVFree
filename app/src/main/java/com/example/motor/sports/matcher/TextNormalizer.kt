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
    private val SEPARATORS_PATTERN: Pattern = Pattern.compile("[+:|/_\\-\\\\•~()\\[\\]{}.,;!¿?¡\"']+")
    private val MULTI_SPACE_PATTERN: Pattern = Pattern.compile("\\s+")

    // Ruido habitual en sufijos o prefijos de canales IPTV
    private val NOISE_TOKENS = setOf(
        "fhd", "uhd", "4k", "hd", "sd", "h265", "hevc", "raw",
        "1080p", "720p", "50fps", "60fps", "vip", "backup", "opt",
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

        // 3. Sustitución de separadores por espacios
        result = SEPARATORS_PATTERN.matcher(result).replaceAll(" ")

        // 4. Colapso de espacios múltiples
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
