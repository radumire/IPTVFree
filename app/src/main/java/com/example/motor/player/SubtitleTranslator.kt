package com.example.motor.player

import android.content.Context
import android.util.Log
import com.example.motor.parser.BaseEngine
import com.example.motor.tmdb.TmdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

object SubtitleTranslator {
    private const val TAG = "SubtitleTranslator"

    private val LINGVA_INSTANCES = listOf(
        "https://lingva.ml/api/v1",
        "https://translate.lingva.ml/api/v1",
        "https://lingva.thedaviddelta.com/api/v1",
        "https://lingva.lunar.icu/api/v1",
        "https://lingva.garudalinux.org/api/v1",
        "https://lingva.pussthecat.org/api/v1"
    )

    private val BROWSER_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Referer" to "https://www.subtitlecat.com/"
    )

    suspend fun translateSubtitleUrl(
        context: Context,
        subUrl: String,
        targetLanguage: String // "es" or "ro"
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting subtitle translation for: $subUrl -> $targetLanguage")
            val originalContent = downloadSubtitle(subUrl)
            if (originalContent.isNullOrBlank()) {
                Log.e(TAG, "Failed to download original subtitle content from: $subUrl")
                return@withContext null
            }

            Log.d(TAG, "Original subtitle fetched (${originalContent.length} chars). Translating dialogue...")
            val translatedContent = translateSubtitleContent(originalContent, targetLanguage)
            if (translatedContent.isNullOrBlank()) {
                Log.e(TAG, "Translation produced empty content")
                return@withContext null
            }

            // Save the translated file locally in the cache directory
            val cacheDir = context.cacheDir
            val filename = "translated_${System.currentTimeMillis()}_${targetLanguage}.srt"
            val file = File(cacheDir, filename)
            file.writeText(translatedContent, Charsets.UTF_8)

            Log.d(TAG, "Translated subtitle saved locally to: ${file.absolutePath}")
            return@withContext file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error during subtitle translation", e)
            null
        }
    }

    private suspend fun downloadSubtitle(urlOrPath: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!urlOrPath.startsWith("http://", ignoreCase = true) &&
                !urlOrPath.startsWith("https://", ignoreCase = true)
            ) {
                // Local file path
                val localPath = if (urlOrPath.startsWith("file://")) urlOrPath.removePrefix("file://") else urlOrPath
                val localFile = File(localPath)
                if (localFile.exists()) {
                    return@withContext localFile.readText(Charsets.UTF_8)
                }
                return@withContext null
            }

            var currentUrl = urlOrPath
            var attempts = 0
            while (attempts < 3) {
                attempts++
                val response = BaseEngine.executeGetRequest(currentUrl, BROWSER_HEADERS)
                if (response.isBlank()) return@withContext null

                val isHtml = response.contains("<!DOCTYPE", ignoreCase = true) ||
                        response.contains("<html", ignoreCase = true) ||
                        response.contains("<body", ignoreCase = true)

                if (!isHtml) {
                    val hasTimestamps = response.contains("-->") || response.trimStart().startsWith("WEBVTT", ignoreCase = true)
                    if (hasTimestamps) {
                        return@withContext response
                    }
                }

                // If it's HTML, resolve direct link from page (e.g. SubtitleCat download links)
                val directLink = TmdbService.extractDirectSubtitleLinkFromHtml(response, currentUrl)
                if (directLink != null && directLink != currentUrl) {
                    Log.d(TAG, "Resolved direct subtitle link from HTML: $directLink")
                    currentUrl = directLink
                } else {
                    // Try to use it anyway if it contains timeline tags
                    if (response.contains("-->")) return@withContext response
                    break
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading subtitle from $urlOrPath", e)
            null
        }
    }

    private suspend fun translateSubtitleContent(content: String, targetLang: String): String? {
        val lines = content.split(Regex("\\r?\\n"))
        val translatedLines = lines.toMutableList()

        val textIndices = mutableListOf<Int>()
        val textsToTranslate = mutableListOf<String>()

        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex].trim()
            if (line.isEmpty() || line.all { it.isDigit() } || line.startsWith("WEBVTT", ignoreCase = true)) {
                lineIndex++
                continue
            }

            // Check if it is a timeline (contains "-->")
            if (line.contains("-->")) {
                lineIndex++
                while (lineIndex < lines.size) {
                    val textLine = lines[lineIndex].trim()
                    if (textLine.isEmpty() || textLine.all { it.isDigit() } || textLine.contains("-->")) {
                        break
                    }

                    // This is a dialogue line!
                    textIndices.add(lineIndex)
                    textsToTranslate.add(lines[lineIndex])
                    lineIndex++
                }
            } else {
                lineIndex++
            }
        }

        if (textsToTranslate.isEmpty()) {
            return content
        }

        Log.d(TAG, "Found ${textsToTranslate.size} dialogue lines to translate to $targetLang")

        // Batch translate in chunks of 20 lines or ~700 characters
        val batchLimit = 700
        var currentBatchText = StringBuilder()
        val currentBatchIndices = mutableListOf<Int>()

        for (i in textsToTranslate.indices) {
            val text = textsToTranslate[i]
            val indexInLines = textIndices[i]

            if (currentBatchText.length + text.length + 1 > batchLimit && currentBatchText.isNotEmpty()) {
                translateAndApplyBatch(currentBatchText.toString(), currentBatchIndices, translatedLines, targetLang)
                currentBatchText = StringBuilder()
                currentBatchIndices.clear()
            }

            if (currentBatchText.isNotEmpty()) {
                currentBatchText.append("\n")
            }
            currentBatchText.append(text)
            currentBatchIndices.add(indexInLines)
        }

        if (currentBatchText.isNotEmpty()) {
            translateAndApplyBatch(currentBatchText.toString(), currentBatchIndices, translatedLines, targetLang)
        }

        return translatedLines.joinToString("\n")
    }

    private suspend fun translateAndApplyBatch(
        batchText: String,
        indices: List<Int>,
        translatedLines: MutableList<String>,
        targetLang: String
    ) {
        if (batchText.isBlank() || indices.isEmpty()) return

        try {
            val translatedBatch = translateTextWithLingvaOrFallbacks(batchText, targetLang)
            if (translatedBatch.isNullOrBlank()) {
                Log.w(TAG, "Batch translation returned empty, retaining original lines")
                return
            }

            val translatedSegments = translatedBatch.split(Regex("\\r?\\n"))
            for (j in indices.indices) {
                if (j < translatedSegments.size) {
                    val originalIndex = indices[j]
                    var segment = translatedSegments[j].trim()

                    // Fix distorted HTML tags
                    segment = segment
                        .replace("< i >", "<i>")
                        .replace("< / i >", "</i>")
                        .replace("< b >", "<b>")
                        .replace("< / b >", "</b>")
                        .replace("< u >", "<u>")
                        .replace("< / u >", "</u>")
                        .replace("< font color = ", "<font color=")
                        .replace("< / font >", "</font>")
                        .replace("< /font>", "</font>")

                    if (segment.isNotEmpty()) {
                        translatedLines[originalIndex] = segment
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply translated batch", e)
        }
    }

    private suspend fun translateTextWithLingvaOrFallbacks(text: String, targetLang: String): String? {
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            return null
        }

        // 1. Try Lingva public instances
        for (instance in LINGVA_INSTANCES) {
            try {
                val url = "$instance/auto/$targetLang/$encodedText"
                val response = BaseEngine.executeGetRequest(url, BROWSER_HEADERS)
                if (response.isNotBlank() && response.startsWith("{")) {
                    val json = JSONObject(response)
                    val translation = json.optString("translation", "")
                    if (translation.isNotBlank()) {
                        return translation
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Lingva instance $instance failed: ${e.message}")
            }
        }

        // 2. Fallback to Google Translate GTX endpoint (Free / SubStudio compatible)
        try {
            val gtxUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            val response = BaseEngine.executeGetRequest(gtxUrl, BROWSER_HEADERS)
            val parsed = parseGoogleTranslateResponse(response)
            if (parsed.isNotBlank()) {
                return parsed
            }
        } catch (e: Exception) {
            Log.d(TAG, "Google GTX fallback failed: ${e.message}")
        }

        // 3. Fallback to MyMemory Free Translation API
        try {
            val myMemoryUrl = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=autodetect|$targetLang"
            val response = BaseEngine.executeGetRequest(myMemoryUrl, BROWSER_HEADERS)
            if (response.isNotBlank() && response.startsWith("{")) {
                val json = JSONObject(response)
                val responseData = json.optJSONObject("responseData")
                val translatedText = responseData?.optString("translatedText", "")
                if (!translatedText.isNullOrBlank() && !translatedText.equals("null", ignoreCase = true)) {
                    return translatedText
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MyMemory fallback failed: ${e.message}")
        }

        return null
    }

    private fun parseGoogleTranslateResponse(jsonStr: String): String {
        val result = StringBuilder()
        try {
            val mainArray = JSONArray(jsonStr)
            if (mainArray.length() > 0) {
                val firstNested = mainArray.optJSONArray(0)
                if (firstNested != null) {
                    for (i in 0 until firstNested.length()) {
                        val translationGroup = firstNested.optJSONArray(i)
                        if (translationGroup != null && translationGroup.length() > 0) {
                            val translatedChunk = translationGroup.optString(0, "")
                            result.append(translatedChunk)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Google Translate GTX response", e)
        }
        return result.toString()
    }
}
