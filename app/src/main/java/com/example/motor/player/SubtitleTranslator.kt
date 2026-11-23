package com.example.motor.player

import android.content.Context
import android.util.Log
import com.example.motor.parser.BaseEngine
import com.example.motor.tmdb.TmdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SubtitleTranslator {
    private const val TAG = "SubtitleTranslator"

    private const val ANDROID_TRANSLATE_UA = "AndroidTranslate/5.3.0.RC02.130475354-53000263 5.1 phone TRANSLATE_OPM5_TEST_1"

    private val BROWSER_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Referer" to "https://www.subtitlecat.com/"
    )

    private val LINGVA_INSTANCES = listOf(
        "https://lingva.ml/api/v1",
        "https://translate.lingva.ml/api/v1",
        "https://lingva.thedaviddelta.com/api/v1"
    )

    suspend fun translateSubtitleUrl(
        context: Context,
        subUrl: String,
        targetLanguage: String, // "es" or "ro"
        onProgressiveReady: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting subtitle translation for: $subUrl -> $targetLanguage")
            val originalContent = downloadSubtitle(subUrl)
            if (originalContent.isNullOrBlank()) {
                Log.e(TAG, "Failed to download original subtitle content from: $subUrl")
                return@withContext null
            }

            Log.d(TAG, "Original subtitle fetched (${originalContent.length} chars). Translating dialogue...")

            val isVtt = originalContent.trimStart().startsWith("WEBVTT", ignoreCase = true) ||
                    subUrl.contains(".vtt", ignoreCase = true)
            val ext = if (isVtt) "vtt" else "srt"
            val cacheDir = context.cacheDir
            val filename = "translated_${System.currentTimeMillis()}_${targetLanguage}.$ext"
            val file = File(cacheDir, filename)

            val translatedContent = translateSubtitleContent(
                content = originalContent,
                targetLang = targetLanguage,
                onInitialBatchDone = { partialContent ->
                    try {
                        file.writeText(partialContent, Charsets.UTF_8)
                        Log.d(TAG, "Progressive subtitle saved to: ${file.absolutePath}")
                        onProgressiveReady?.invoke(file.absolutePath)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write progressive subtitle", e)
                    }
                }
            )

            if (translatedContent.isNullOrBlank()) {
                Log.e(TAG, "Translation produced empty content")
                return@withContext null
            }

            // Save the complete translated file locally in the cache directory
            file.writeText(translatedContent, Charsets.UTF_8)
            Log.d(TAG, "Complete translated subtitle saved to: ${file.absolutePath}")
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
                val directLink = com.example.motor.subtitles.SubtitleResolver.extractDirectSubtitleLinkFromHtml(response, currentUrl)
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

    private suspend fun translateSubtitleContent(
        content: String,
        targetLang: String,
        onInitialBatchDone: ((String) -> Unit)? = null
    ): String? = coroutineScope {
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
            return@coroutineScope content
        }

        Log.d(TAG, "Found ${textsToTranslate.size} dialogue lines to translate to $targetLang")

        // Group into batches of ~35 lines or ~1800 chars
        val batchLimitChars = 1800
        val batchLimitLines = 35

        data class Batch(val text: String, val indices: List<Int>)
        val batches = mutableListOf<Batch>()

        var curText = StringBuilder()
        val curIndices = mutableListOf<Int>()

        for (i in textsToTranslate.indices) {
            val text = textsToTranslate[i]
            val indexInLines = textIndices[i]

            if ((curIndices.size >= batchLimitLines || curText.length + text.length + 1 > batchLimitChars) && curIndices.isNotEmpty()) {
                batches.add(Batch(curText.toString(), curIndices.toList()))
                curText = StringBuilder()
                curIndices.clear()
            }

            if (curText.isNotEmpty()) {
                curText.append("\n")
            }
            curText.append(text)
            curIndices.add(indexInLines)
        }

        if (curIndices.isNotEmpty()) {
            batches.add(Batch(curText.toString(), curIndices.toList()))
        }

        Log.d(TAG, "Split dialogue into ${batches.size} batches for parallel translation")

        // Concurrency limiter to avoid network flooding
        val semaphore = Semaphore(4)
        var initialReported = false

        // Translate in parallel
        val deferreds = batches.mapIndexed { batchIdx, batch ->
            async(Dispatchers.IO) {
                val translatedSegments = semaphore.withPermit {
                    translateBatchLines(batch.text, batch.indices.size, targetLang)
                }

                synchronized(translatedLines) {
                    applyTranslatedSegments(translatedSegments, batch.indices, translatedLines)
                }

                // If batch 0 completes, optionally trigger progressive callback
                if (batchIdx == 0 && !initialReported) {
                    initialReported = true
                    try {
                        val snapshot = synchronized(translatedLines) { translatedLines.joinToString("\n") }
                        onInitialBatchDone?.invoke(snapshot)
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial batch callback error: ${e.message}")
                    }
                }
            }
        }

        deferreds.awaitAll()

        return@coroutineScope translatedLines.joinToString("\n")
    }

    private fun applyTranslatedSegments(
        segments: List<String>?,
        indices: List<Int>,
        translatedLines: MutableList<String>
    ) {
        if (segments == null || segments.isEmpty()) return
        for (j in indices.indices) {
            val lineIdx = indices[j]
            if (j < segments.size) {
                val cleaned = cleanHtmlTags(segments[j].trim())
                if (cleaned.isNotBlank()) {
                    translatedLines[lineIdx] = cleaned
                }
            }
        }
    }

    private suspend fun translateBatchLines(
        batchText: String,
        expectedLineCount: Int,
        targetLang: String
    ): List<String>? = withContext(Dispatchers.IO) {
        if (batchText.isBlank()) return@withContext null

        // 1. Official Google Translate Android Client (Fast POST, handles newlines & paragraphs)
        val gAndroid = translateChunkViaGoogleAndroid(batchText, targetLang)
        if (gAndroid != null && gAndroid.isNotBlank()) {
            val lines = gAndroid.split(Regex("\\r?\\n")).map { it.trim() }
            if (lines.isNotEmpty()) {
                return@withContext lines
            }
        }

        // 2. Google Translate Chrome Extension Client
        val gChrome = translateChunkViaChromeEx(batchText, targetLang)
        if (gChrome != null && gChrome.isNotBlank()) {
            val lines = gChrome.split(Regex("\\r?\\n")).map { it.trim() }
            if (lines.isNotEmpty()) {
                return@withContext lines
            }
        }

        // 3. Fallback Lingva / MyMemory
        val lingva = translateTextWithLingvaOrFallbacks(batchText, targetLang)
        if (lingva != null && lingva.isNotBlank()) {
            val lines = lingva.split(Regex("\\r?\\n")).map { it.trim() }
            if (lines.isNotEmpty()) {
                return@withContext lines
            }
        }

        null
    }

    private fun translateChunkViaGoogleAndroid(text: String, targetLang: String): String? {
        return try {
            val url = "https://translate.google.com/translate_a/single?client=at&sl=auto&tl=$targetLang&dt=t"
            val body = okhttp3.FormBody.Builder(Charsets.UTF_8)
                .add("q", text)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", ANDROID_TRANSLATE_UA)
                .header("Accept", "*/*")
                .build()
            val okClient = BaseEngine.client.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
            okClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string().orEmpty()
                    parseGoogleTranslateResponse(responseStr)
                } else {
                    Log.w(TAG, "Google Translate Android HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Translate Android error: ${e.message}")
            null
        }
    }

    private fun translateChunkViaChromeEx(text: String, targetLang: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$targetLang&q=$encoded"
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val okClient = BaseEngine.client.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            okClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val resp = response.body?.string().orEmpty()
                    val arr = JSONArray(resp)
                    val first = arr.optJSONArray(0)
                    first?.optString(0, "")
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateTextWithLingvaOrFallbacks(text: String, targetLang: String): String? {
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            return null
        }

        // Try Lingva public instances
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

        // Fallback to MyMemory Free Translation API
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
            Log.e(TAG, "Failed to parse Google Translate response", e)
        }
        return result.toString()
    }

    private fun cleanHtmlTags(str: String): String {
        return str
            .replace("< i >", "<i>", ignoreCase = true)
            .replace("< / i >", "</i>", ignoreCase = true)
            .replace("< /i >", "</i>", ignoreCase = true)
            .replace("< /i>", "</i>", ignoreCase = true)
            .replace("< b >", "<b>", ignoreCase = true)
            .replace("< / b >", "</b>", ignoreCase = true)
            .replace("< /b >", "</b>", ignoreCase = true)
            .replace("< /b>", "</b>", ignoreCase = true)
            .replace("< u >", "<u>", ignoreCase = true)
            .replace("< / u >", "</u>", ignoreCase = true)
            .replace("< /u >", "</u>", ignoreCase = true)
            .replace("< /u>", "</u>", ignoreCase = true)
            .replace("< font color = ", "<font color=", ignoreCase = true)
            .replace("< / font >", "</font>", ignoreCase = true)
            .replace("< /font>", "</font>", ignoreCase = true)
    }
}
