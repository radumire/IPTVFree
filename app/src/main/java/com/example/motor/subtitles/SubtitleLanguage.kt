package com.example.motor.subtitles

import java.util.Locale

/**
 * Unified language model for subtitle processing.
 * Strictly separates variants (e.g. Simplified vs Traditional Chinese vs Bilingual).
 */
object SubtitleLanguage {

    const val LANG_ES = "es"
    const val LANG_ES_419 = "es-419"
    const val LANG_EN = "en"
    const val LANG_RO = "ro"
    const val LANG_ZH_CN = "zh-CN"
    const val LANG_ZH_TW = "zh-TW"
    const val LANG_ZH_BILINGUAL = "zh-bilingual"
    const val LANG_FR = "fr"
    const val LANG_DE = "de"
    const val LANG_IT = "it"
    const val LANG_PT = "pt"
    const val LANG_RU = "ru"

    /**
     * Normalizes a raw language identifier into a canonical code.
     */
    fun normalizeLanguageCode(raw: String?, filenameOrDesc: String? = null): String {
        val r = raw?.trim()?.lowercase(Locale.ROOT) ?: ""
        val f = filenameOrDesc?.trim()?.lowercase(Locale.ROOT) ?: ""
        val combined = "$r $f"

        // 1. Check Bilingual first
        if (combined.contains("双语") || combined.contains("中英") || combined.contains("bilingual") ||
            (combined.contains("chs") && combined.contains("eng")) ||
            (combined.contains("cht") && combined.contains("eng"))
        ) {
            return LANG_ZH_BILINGUAL
        }

        // 2. Check Traditional Chinese
        if (r in listOf("zh-tw", "zh-hk", "cht", "tc", "zh-hant") ||
            combined.contains("繁体") || combined.contains("繁體") || combined.contains("big5") ||
            f.endsWith(".cht.srt") || f.endsWith(".cht.ass") || f.contains(".cht.")
        ) {
            return LANG_ZH_TW
        }

        // 3. Check Simplified Chinese
        if (r in listOf("zh-cn", "zh-sg", "chs", "sc", "zh-hans") ||
            combined.contains("简体") || combined.contains("簡體") || combined.contains("gb2312") ||
            f.endsWith(".chs.srt") || f.endsWith(".chs.ass") || f.contains(".chs.")
        ) {
            return LANG_ZH_CN
        }

        // 4. General Chinese if unspecified
        if (r == "zh" || r == "chi" || r == "zho" || combined.contains("中文") || combined.contains("chinese")) {
            return LANG_ZH_CN
        }

        // 5. Latin American Spanish
        if (r in listOf("es-419", "es-la", "es-mx", "latino", "spanish (latin america)") ||
            combined.contains("es-419") || combined.contains("es_419") || combined.contains("latino") ||
            combined.contains("latin america") || combined.contains("latinoamérica") || combined.contains("latinoamerica")
        ) {
            return LANG_ES_419
        }

        // 6. Spanish
        if (r in listOf("es", "es-es", "spa", "spanish") ||
            combined.contains("spanish") || combined.contains("español") || combined.contains("castellano") ||
            combined.contains("download_es") || combined.contains("-es.") || combined.contains("_es.")
        ) {
            return LANG_ES
        }

        // 7. Romanian
        if (r in listOf("ro", "rum", "ron", "romanian", "romana") ||
            combined.contains("romanian") || combined.contains("română") || combined.contains("romana") || combined.contains("rumano") ||
            combined.contains("download_ro") || combined.contains("-ro.") || combined.contains("_ro.")
        ) {
            return LANG_RO
        }

        // 8. English
        if (r in listOf("en", "eng", "english", "en-us", "en-gb") ||
            combined.contains("english") || combined.contains("inglés") || combined.contains("ingles") ||
            combined.contains("download_en") || combined.contains("-en.") || combined.contains("_en.")
        ) {
            return LANG_EN
        }

        // 9. Other major European languages
        if (r in listOf("fr", "fra", "fre", "french") || combined.contains("french") || combined.contains("français") || combined.contains("francés") || combined.contains("download_fr") || combined.contains("-fr.")) return LANG_FR
        if (r in listOf("de", "deu", "ger", "german") || combined.contains("german") || combined.contains("deutsch") || combined.contains("alemán") || combined.contains("download_de") || combined.contains("-de.")) return LANG_DE
        if (r in listOf("it", "ita", "italian") || combined.contains("italian") || combined.contains("italiano") || combined.contains("download_it") || combined.contains("-it.")) return LANG_IT
        if (r in listOf("pt", "por", "portuguese", "pt-br", "pt-pt") || combined.contains("portuguese") || combined.contains("português") || combined.contains("portugues") || combined.contains("download_pt") || combined.contains("-pt.")) return LANG_PT
        if (r in listOf("ru", "rus", "russian") || combined.contains("russian") || combined.contains("русский") || combined.contains("ruso") || combined.contains("download_ru") || combined.contains("-ru.")) return LANG_RU

        return if (r.isNotBlank()) r else "und"
    }

    fun getDisplayName(code: String): String {
        return when (code) {
            LANG_ES -> "Español"
            LANG_ES_419 -> "Español (Latinoamérica)"
            LANG_RO -> "Română"
            LANG_EN -> "English"
            LANG_ZH_CN -> "Chino Simplificado"
            LANG_ZH_TW -> "Chino Tradicional"
            LANG_ZH_BILINGUAL -> "Chino (Bilingüe)"
            LANG_FR -> "Français"
            LANG_DE -> "Deutsch"
            LANG_IT -> "Italiano"
            LANG_PT -> "Português"
            LANG_RU -> "Русский"
            else -> code.uppercase(Locale.ROOT)
        }
    }

    /**
     * Determines whether candidate language matches the requested preferred language.
     */
    fun matchesPreferred(candidateLang: String, preferredLang: String): Boolean {
        val c = normalizeLanguageCode(candidateLang)
        val p = normalizeLanguageCode(preferredLang)

        if (c == p) return true

        // Bilingual Chinese satisfies either zh-CN, zh-TW or en
        if (c == LANG_ZH_BILINGUAL && (p == LANG_ZH_CN || p == LANG_ZH_TW || p == LANG_EN)) {
            return true
        }

        // Explicitly reject Simplified vs Traditional cross-match
        if ((c == LANG_ZH_CN && p == LANG_ZH_TW) || (c == LANG_ZH_TW && p == LANG_ZH_CN)) {
            return false
        }

        // Allow general Spanish / regional Spanish
        if ((p == LANG_ES || p == LANG_ES_419) && c.startsWith("es")) return true
        if (p == LANG_EN && c.startsWith("en")) return true
        if (p == LANG_RO && c.startsWith("ro")) return true

        return false
    }
}
