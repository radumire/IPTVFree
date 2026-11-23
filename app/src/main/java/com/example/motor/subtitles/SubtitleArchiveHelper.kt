package com.example.motor.subtitles

import android.util.Log
import com.example.motor.parser.BaseEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Centralized downloader and extractor for external subtitles.
 * Handles direct SRT/VTT/ASS files, GZIP compression, and ZIP archives.
 */
object SubtitleArchiveHelper {

    private const val TAG = "SUBTITLE_DOWNLOAD"

    private val httpClient: OkHttpClient by lazy {
        BaseEngine.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Downloads and extracts a subtitle file to a validated local file.
     * Returns the local File if valid, or null if download/extraction/validation failed.
     */
    suspend fun downloadAndResolveSubtitleFile(
        downloadUrl: String,
        headers: Map<String, String> = emptyMap(),
        targetDir: File,
        baseName: String = "sub_${downloadUrl.hashCode()}",
        targetSeason: Int? = null,
        targetEpisode: Int? = null,
        targetLanguage: String? = null,
        client: OkHttpClient = httpClient
    ): File? = withContext(Dispatchers.IO) {
        if (downloadUrl.isBlank()) return@withContext null

        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val reqBuilder = Request.Builder().url(downloadUrl)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SUBTITLE_DOWNLOAD failed with HTTP ${resp.code} for url=$downloadUrl")
                    return@withContext null
                }

                val body = resp.body ?: return@withContext null
                val contentType = resp.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                val contentDisposition = resp.header("Content-Disposition")?.lowercase(Locale.ROOT) ?: ""
                val urlLower = downloadUrl.lowercase(Locale.ROOT)

                val isZip = contentType.contains("zip") || contentDisposition.contains(".zip") || urlLower.contains(".zip")
                val isGzip = contentType.contains("gzip") || urlLower.endsWith(".gz") || resp.header("Content-Encoding")?.contains("gzip") == true

                val inputStream = body.byteStream()

                val resolvedFile: File? = when {
                    isZip -> extractFromZip(inputStream, targetDir, baseName, targetSeason, targetEpisode, targetLanguage)
                    isGzip -> extractFromGzip(inputStream, targetDir, baseName)
                    else -> saveDirectStream(inputStream, targetDir, baseName, urlLower)
                }

                if (resolvedFile != null && isValidSubtitleContent(resolvedFile)) {
                    Log.i(TAG, "SUBTITLE_DOWNLOAD success: size=${resolvedFile.length()} path=${resolvedFile.name}")
                    return@withContext resolvedFile
                } else {
                    Log.w(TAG, "SUBTITLE_DOWNLOAD validation failed for url=$downloadUrl")
                    resolvedFile?.delete()
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SUBTITLE_DOWNLOAD exception for url=$downloadUrl: ${e.message}")
            null
        }
    }

    private fun saveDirectStream(inputStream: InputStream, targetDir: File, baseName: String, url: String): File {
        val ext = when {
            url.contains(".vtt") -> ".vtt"
            url.contains(".ass") -> ".ass"
            else -> ".srt"
        }
        val file = File(targetDir, "$baseName$ext")
        val rawBytes = inputStream.readBytes()
        val utf8Bytes = convertSubtitleBytesToUtf8(rawBytes)
        FileOutputStream(file).use { out ->
            out.write(utf8Bytes)
        }
        return file
    }

    private fun extractFromGzip(inputStream: InputStream, targetDir: File, baseName: String): File {
        val file = File(targetDir, "$baseName.srt")
        val rawBytes = GZIPInputStream(inputStream).use { it.readBytes() }
        val utf8Bytes = convertSubtitleBytesToUtf8(rawBytes)
        FileOutputStream(file).use { out ->
            out.write(utf8Bytes)
        }
        return file
    }

    private fun extractFromZip(
        inputStream: InputStream,
        targetDir: File,
        baseName: String,
        targetSeason: Int?,
        targetEpisode: Int?,
        targetLanguage: String? = null
    ): File? {
        val zipBytes = inputStream.readBytes()
        var bestEntryFile: File? = null
        var bestPriority = -1

        val targetCanonical = targetLanguage?.let { SubtitleLanguage.normalizeLanguageCode(it) }

        // Try extracting with UTF-8 first, fallback to GBK (common for Chinese subtitle zips)
        val charsetsToTry = listOf(Charsets.UTF_8, java.nio.charset.Charset.forName("GBK"))
        for (charset in charsetsToTry) {
            try {
                ZipInputStream(java.io.ByteArrayInputStream(zipBytes), charset).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory && (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".vtt"))) {
                            val ext = if (name.endsWith(".ass")) ".ass" else if (name.endsWith(".vtt")) ".vtt" else ".srt"
                            val entryBytes = zipIn.readBytes()
                            val utf8Bytes = convertSubtitleBytesToUtf8(entryBytes)

                            val tempOut = File(targetDir, "${baseName}_entry_${name.hashCode()}$ext")
                            FileOutputStream(tempOut).use { out ->
                                out.write(utf8Bytes)
                            }

                            // Score this entry based on matching episode
                            var priority = 10
                            val se = SubtitleMatcher.extractSeasonEpisode(name)
                            if (targetSeason != null && se?.first == targetSeason) priority += 50
                            if (targetEpisode != null && se?.second == targetEpisode) priority += 100
                            if (targetEpisode != null && SubtitleMatcher.isEpisodeInMultiEpisodeRange(name, targetEpisode)) priority += 80

                            // Score entry based on target language
                            if (targetCanonical != null) {
                                val detectedLang = SubtitleLanguage.normalizeLanguageCode("", name)
                                if (detectedLang == targetCanonical) {
                                    priority += 150
                                } else {
                                    // Check if name contains explicit conflicting language markers
                                    val conflictingLanguages = listOf(
                                        "ar" to listOf("arabic", "arab", ".ar.", "-ar.", "_ar."),
                                        "ru" to listOf("russian", "русский", ".ru.", "-ru.", "_ru."),
                                        "el" to listOf("greek", ".el.", "-el.", "_el."),
                                        "fr" to listOf("french", "français", ".fr.", "-fr.", "_fr."),
                                        "de" to listOf("german", "deutsch", ".de.", "-de.", "_de."),
                                        "it" to listOf("italian", "italiano", ".it.", "-it.", "_it."),
                                        "pt" to listOf("portuguese", ".pt.", "-pt.", "_pt."),
                                        "tr" to listOf("turkish", ".tr.", "-tr.", "_tr.")
                                    )
                                    for ((cLang, markers) in conflictingLanguages) {
                                        if (cLang != targetCanonical && markers.any { name.contains(it) }) {
                                            priority -= 120
                                            break
                                        }
                                    }
                                }
                            }

                            if (priority > bestPriority) {
                                bestEntryFile?.delete()
                                bestEntryFile = tempOut
                                bestPriority = priority
                            } else {
                                tempOut.delete()
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
                if (bestEntryFile != null) break
            } catch (_: Exception) {}
        }
        return bestEntryFile
    }

    /**
     * Converts subtitle raw bytes to valid UTF-8 from GBK / GB2312 / Big5 / UTF-16 / ISO-8859-1.
     * Prevents Media3 ExoPlayer from rendering blank screens or mojibake.
     */
    fun convertSubtitleBytesToUtf8(rawBytes: ByteArray): ByteArray {
        if (rawBytes.isEmpty()) return rawBytes

        // Strip UTF-8 BOM
        if (rawBytes.size >= 3 && rawBytes[0] == 0xEF.toByte() && rawBytes[1] == 0xBB.toByte() && rawBytes[2] == 0xBF.toByte()) {
            return rawBytes.copyOfRange(3, rawBytes.size)
        }
        // Handle UTF-16LE BOM
        if (rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xFE.toByte()) {
            return try {
                String(rawBytes, 2, rawBytes.size - 2, Charsets.UTF_16LE).toByteArray(Charsets.UTF_8)
            } catch (_: Exception) { rawBytes }
        }
        // Handle UTF-16BE BOM
        if (rawBytes.size >= 2 && rawBytes[0] == 0xFE.toByte() && rawBytes[1] == 0xFF.toByte()) {
            return try {
                String(rawBytes, 2, rawBytes.size - 2, Charsets.UTF_16BE).toByteArray(Charsets.UTF_8)
            } catch (_: Exception) { rawBytes }
        }

        // Test if rawBytes are already strictly valid UTF-8
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        try {
            utf8Decoder.decode(java.nio.ByteBuffer.wrap(rawBytes))
            return rawBytes
        } catch (_: java.nio.charset.CharacterCodingException) {
            // Not valid UTF-8, proceed with auto-conversion
        }

        // Try GB18030 / GBK (covers Simplified and Traditional Chinese)
        try {
            val gbkCharset = java.nio.charset.Charset.forName("GB18030")
            val gbkDecoder = gbkCharset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val charBuf = gbkDecoder.decode(java.nio.ByteBuffer.wrap(rawBytes))
            return charBuf.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {}

        // Try Big5 (Traditional Chinese)
        try {
            val big5Charset = java.nio.charset.Charset.forName("Big5")
            val big5Decoder = big5Charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val charBuf = big5Decoder.decode(java.nio.ByteBuffer.wrap(rawBytes))
            return charBuf.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {}

        // Fallback to ISO-8859-1 conversion to UTF-8
        return try {
            val text = String(rawBytes, java.nio.charset.Charset.forName("ISO-8859-1"))
            text.toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            rawBytes
        }
    }

    /**
     * Checks if the file contains legitimate subtitle text patterns.
     */
    fun isValidSubtitleContent(file: File): Boolean {
        if (!file.exists() || file.length() < 10) return false
        try {
            // Read up to first 4 KiB
            val preview = file.inputStream().use { stream ->
                val buf = ByteArray(4096)
                val read = stream.read(buf)
                if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
            }

            if (preview.isBlank()) return false

            // Reject if it's an HTML error page or JSON or binary
            val lower = preview.trim().lowercase(Locale.ROOT)
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
                lower.startsWith("{\"status\"") || lower.startsWith("{\"error\"") ||
                preview.startsWith("Rar!") || preview.startsWith("PK")
            ) {
                return false
            }

            // Must contain standard subtitle indicators
            return preview.contains("-->") || // SRT / VTT timestamps: 00:00:01,000 --> 00:00:04,000
                    preview.contains("WEBVTT") ||
                    preview.contains("[Script Info]") || // ASS/SSA
                    preview.contains("Dialogue:") ||
                    preview.contains("Format:") ||
                    Regex("""\d{1,2}:\d{2}:\d{2}""").containsMatchIn(preview)
        } catch (_: Exception) {
            return false
        }
    }
}
