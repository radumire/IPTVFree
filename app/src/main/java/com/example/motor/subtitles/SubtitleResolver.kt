package com.example.motor.subtitles

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Resolves external subtitle candidates into playable local files (file://)
 * or verified direct GET URLs for Media3/ExoPlayer.
 *
 * Specifically prevents passing raw POST endpoints like:
 * https://api.opensubtitles.com/api/v1/download?file_id=...
 * to Media3.
 */
object SubtitleResolver {

    private const val TAG = "SUBTITLE_RESOLVER"

    @Volatile
    private var baseCacheDir: File? = null

    fun init(cacheDir: File) {
        baseCacheDir = if (cacheDir.name == "subtitles") {
            cacheDir.apply { if (!exists()) mkdirs() }
        } else {
            File(cacheDir, "subtitles").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    fun getSubtitlesDir(): File {
        val dir = baseCacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "app_subtitles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun resolveSingleSubtitle(
        sub: ExternalSubtitle,
        openSubtitlesApiKey: String? = OpenSubtitlesProvider.resolveDefaultApiKey()
    ): ExternalSubtitle? = resolveSubtitle(sub, openSubtitlesApiKey)

    /**
     * Resolves a single ExternalSubtitle candidate into a playable file:// URL or direct GET link.
     * Returns the updated ExternalSubtitle, or null if resolution/download failed.
     */
    suspend fun resolveSubtitle(
        sub: ExternalSubtitle,
        openSubtitlesApiKey: String? = OpenSubtitlesProvider.resolveDefaultApiKey()
    ): ExternalSubtitle? = withContext(Dispatchers.IO) {
        val targetDir = getSubtitlesDir()

        // 1. If already a valid local file
        if (sub.url.startsWith("file://") || (sub.url.startsWith("/") && File(sub.url).exists())) {
            val localPath = if (sub.url.startsWith("file://")) sub.url else "file://${sub.url}"
            return@withContext sub.copy(url = localPath)
        }

        // 2. OpenSubtitles provider resolution
        if (sub.source.equals("OpenSubtitles", ignoreCase = true) || sub.url.contains("opensubtitles.com", ignoreCase = true) || sub.fileId != null) {
            val fileId = sub.fileId ?: extractFileId(sub.url)
            if (fileId != null && fileId > 0) {
                // Check if already cached locally
                val cachedFile = File(targetDir, "os_${fileId}.srt").takeIf { it.exists() && it.length() > 0 }
                    ?: File(targetDir, "os_${fileId}.vtt").takeIf { it.exists() && it.length() > 0 }
                    ?: targetDir.parentFile?.let { parent ->
                        File(parent, "os_${fileId}.srt").takeIf { it.exists() && it.length() > 0 }
                            ?: File(parent, "os_${fileId}.vtt").takeIf { it.exists() && it.length() > 0 }
                    }

                if (cachedFile != null) {
                    Log.i(TAG, "OpenSubtitles file_id=$fileId found in local cache: ${cachedFile.name}")
                    return@withContext sub.copy(url = "file://${cachedFile.absolutePath}", fileId = fileId)
                }

                // Resolve real CDN download link via POST /api/v1/download
                val apiKey = openSubtitlesApiKey?.takeIf { it.isNotBlank() } ?: OpenSubtitlesProvider.resolveDefaultApiKey()
                if (apiKey.isNullOrBlank()) {
                    Log.w(TAG, "Cannot resolve OpenSubtitles file_id=$fileId: Api-Key is missing")
                    return@withContext null
                }

                val directCdnLink = OpenSubtitlesProvider.resolveDownloadLink(fileId, apiKey)
                if (directCdnLink.isNullOrBlank()) {
                    Log.w(TAG, "OpenSubtitles /api/v1/download did not return a link for file_id=$fileId")
                    return@withContext null
                }

                // Download CDN link to local cache file
                val downloadedFile = SubtitleArchiveHelper.downloadAndResolveSubtitleFile(
                    downloadUrl = directCdnLink,
                    targetDir = targetDir,
                    baseName = "os_${fileId}",
                    targetSeason = sub.season,
                    targetEpisode = sub.episode,
                    targetLanguage = sub.language
                )

                if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 50) {
                    Log.i(TAG, "OpenSubtitles successfully downloaded file_id=$fileId to ${downloadedFile.name}")
                    return@withContext sub.copy(url = "file://${downloadedFile.absolutePath}", fileId = fileId)
                } else {
                    Log.w(TAG, "OpenSubtitles download failed for file_id=$fileId: file incomplete or download error")
                    return@withContext null
                }
            } else if (sub.url.contains("/api/v1/download")) {
                // Raw unresolvable download endpoint without valid fileId: reject
                Log.w(TAG, "Rejecting unresolvable OpenSubtitles endpoint URL: ${sub.url}")
                return@withContext null
            }
        }

        // 3. ASSRT provider resolution (archives / detail pages)
        if (sub.source.equals("ASSRT", ignoreCase = true)) {
            val safeId = sub.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val cachedFile = File(targetDir, "${safeId}.srt").takeIf { it.exists() && it.length() > 50 }
                ?: File(targetDir, "${safeId}.ass").takeIf { it.exists() && it.length() > 50 }

            if (cachedFile != null) {
                return@withContext sub.copy(url = "file://${cachedFile.absolutePath}")
            }

            if (sub.url.startsWith("http://", ignoreCase = true) || sub.url.startsWith("https://", ignoreCase = true)) {
                val assrtToken = AssrtSubtitleProvider.resolveDefaultToken()?.trim()
                val downloadHeaders = mutableMapOf<String, String>()
                if (!assrtToken.isNullOrBlank()) {
                    downloadHeaders["Authorization"] = "Bearer $assrtToken"
                }
                val downloadUrlWithToken = if (!assrtToken.isNullOrBlank() && !sub.url.contains("token=")) {
                    if (sub.url.contains("?")) "${sub.url}&token=$assrtToken" else "${sub.url}?token=$assrtToken"
                } else {
                    sub.url
                }

                val downloaded = SubtitleArchiveHelper.downloadAndResolveSubtitleFile(
                    downloadUrl = downloadUrlWithToken,
                    headers = downloadHeaders,
                    targetDir = targetDir,
                    baseName = safeId,
                    targetSeason = sub.season,
                    targetEpisode = sub.episode,
                    targetLanguage = sub.language
                )
                if (downloaded != null && downloaded.exists()) {
                    return@withContext sub.copy(url = "file://${downloaded.absolutePath}")
                }
            }
        }

        // 4. SubtitleCat & other direct GET providers
        if (sub.url.startsWith("http://", ignoreCase = true) || sub.url.startsWith("https://", ignoreCase = true)) {
            var finalUrl = sub.url
            val isDirectSub = finalUrl.endsWith(".srt", ignoreCase = true) ||
                              finalUrl.endsWith(".vtt", ignoreCase = true) ||
                              finalUrl.endsWith(".ass", ignoreCase = true) ||
                              finalUrl.endsWith(".sub", ignoreCase = true)

            // CRITICAL: Only attempt HTML resolution if the URL is an HTML/PHP page, NEVER when it's already a direct subtitle file!
            if (!isDirectSub && (sub.url.endsWith(".html", ignoreCase = true) || sub.url.endsWith(".php", ignoreCase = true) || sub.url.contains("/sub/"))) {
                try {
                    val req = okhttp3.Request.Builder().url(sub.url).header("User-Agent", "Mozilla/5.0").build()
                    val resp = com.example.motor.parser.BaseEngine.client.newCall(req).execute()
                    val html = resp.use { if (it.isSuccessful) it.body?.string() ?: "" else "" }
                    val direct = extractDirectSubtitleLinkFromHtml(html, sub.url, sub.language)
                    if (!direct.isNullOrBlank()) {
                        finalUrl = direct
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SubtitleCat HTML resolution failed for ${sub.url}: ${e.message}")
                }
            }

            // Download directly to local cache
            val isTargetSub = finalUrl.endsWith(".srt", ignoreCase = true) ||
                              finalUrl.endsWith(".vtt", ignoreCase = true) ||
                              finalUrl.endsWith(".ass", ignoreCase = true) ||
                              finalUrl.endsWith(".sub", ignoreCase = true)
            if (isTargetSub) {
                val safeId = "subcat_${sub.id.hashCode()}"
                val downloaded = SubtitleArchiveHelper.downloadAndResolveSubtitleFile(
                    downloadUrl = finalUrl,
                    targetDir = targetDir,
                    baseName = safeId,
                    targetSeason = sub.season,
                    targetEpisode = sub.episode,
                    targetLanguage = sub.language
                )
                if (downloaded != null && downloaded.exists()) {
                    return@withContext sub.copy(url = "file://${downloaded.absolutePath}")
                }
            }

            return@withContext sub.copy(url = finalUrl)
        }

        null
    }

    /**
     * Resolves all candidates in parallel, filtering out those that cannot be resolved.
     */
    suspend fun resolveSubtitles(
        subs: List<ExternalSubtitle>,
        openSubtitlesApiKey: String? = OpenSubtitlesProvider.resolveDefaultApiKey()
    ): List<ExternalSubtitle> = coroutineScope {
        subs.map { sub ->
            async(Dispatchers.IO) {
                resolveSubtitle(sub, openSubtitlesApiKey)
            }
        }.awaitAll().filterNotNull()
    }

    private fun extractFileId(url: String): Long? {
        val match = Regex("""(?:file_id=|/files?/|/download/?)(\d+)""").find(url)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    fun extractDirectSubtitleLinkFromHtml(
        html: String,
        baseUrl: String,
        targetLanguage: String? = null
    ): String? {
        val targetLangCode = targetLanguage?.let { SubtitleLanguage.normalizeLanguageCode(it).lowercase(Locale.ROOT) }

        // 1. Language-targeted match if language was specified
        if (!targetLangCode.isNullOrBlank()) {
            // Check for id="download_${targetLangCode}" (e.g. id="download_en" or id="download_es")
            val idPattern = Regex("""<a\s+[^>]*id=["']download_${Regex.escape(targetLangCode)}["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
            if (idPattern != null) {
                val href = idPattern.groupValues[1].trim()
                if (href.isNotBlank()) return normalizeUrl(href, baseUrl)
            }

            // Check for href matching "-${targetLangCode}.srt" or "_${targetLangCode}.srt"
            val filePattern = Regex("""<a\s+[^>]*href=["']([^"']*[_\-]${Regex.escape(targetLangCode)}\.(?:srt|vtt|ass|sub)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(html)
            if (filePattern != null) {
                val href = filePattern.groupValues[1].trim()
                if (href.isNotBlank()) return normalizeUrl(href, baseUrl)
            }
        }

        // 2. Check for explicit srt / vtt download links
        val srtMatch = Regex("""<a\s+[^>]*href=["']([^"']*\.(?:srt|vtt)[^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
        if (srtMatch != null) {
            val href = srtMatch.groupValues[1].trim()
            if (href.isNotBlank() && !href.contains("translate", true)) return normalizeUrl(href, baseUrl)
        }

        // 3. Check for download buttons with id="download_..." or id="download"
        val downloadBtnMatch = Regex("""<a\s+[^>]*id=["']download(?:_[a-zA-Z0-9_-]+)?["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*id=["']download(?:_[a-zA-Z0-9_-]+)?["']""", RegexOption.IGNORE_CASE).find(html)
        if (downloadBtnMatch != null) {
            val href = downloadBtnMatch.groupValues[1].trim()
            if (href.isNotBlank() && !href.contains("translate", true)) return normalizeUrl(href, baseUrl)
        }

        // 4. Check for links with /download/ or download.php
        val downloadPathMatch = Regex("""<a\s+[^>]*href=["']([^"']*(?:/download/|download\.php\?[^"']*))["']""", RegexOption.IGNORE_CASE).find(html)
        if (downloadPathMatch != null) {
            val href = downloadPathMatch.groupValues[1].trim()
            if (href.isNotBlank() && !href.contains("translate", true)) return normalizeUrl(href, baseUrl)
        }

        // 5. Check for download attribute
        val downloadAttrMatch = Regex("""<a\s+[^>]*download(?:\s*=\s*["'][^"']*["'])?\s+[^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*download""", RegexOption.IGNORE_CASE).find(html)
        if (downloadAttrMatch != null) {
            val href = downloadAttrMatch.groupValues[1].trim()
            if (href.isNotBlank() && !href.contains("translate", true)) return normalizeUrl(href, baseUrl)
        }

        return null
    }

    fun normalizeUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> {
                val origin = try {
                    val u = java.net.URI(baseUrl)
                    "${u.scheme}://${u.host}${if (u.port != -1 && u.port != 80 && u.port != 443) ":${u.port}" else ""}"
                } catch (_: Exception) {
                    "https://www.subtitlecat.com"
                }
                "$origin$trimmed"
            }
            else -> {
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                "$base$trimmed"
            }
        }
    }
}
