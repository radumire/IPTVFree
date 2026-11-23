package com.example.motor.subtitles

import android.util.Log
import com.example.motor.parser.BaseEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MovieHashResult(
    val hash: String,
    val fileSize: Long
)

/**
 * OpenSubtitles MovieHash implementation.
 *
 * Computes 64-bit checksum using the file size and the first and last 64 KiB
 * chunks of a media file via HTTP Range requests without downloading the entire video.
 */
object MovieHash {

    private const val TAG = "SUBTITLE_HASH"
    const val HASH_CHUNK_SIZE = 65536L // 64 KiB

    private val httpClient: OkHttpClient by lazy {
        BaseEngine.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Determines whether the given URL is a seekable video file suitable for MovieHash.
     */
    fun isSeekableMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }
        if (lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("hls.mp4") ||
            lower.contains(".mpd") || lower.contains("/dash/") || lower.contains("output=ts") ||
            lower.contains("live/") || lower.contains("/live.ts")
        ) {
            return false
        }
        return true
    }

    /**
     * Calculates OpenSubtitles MovieHash via HTTP Range requests.
     * Guaranteed to NEVER download full video files if Range is not supported.
     */
    suspend fun calculateMovieHash(
        videoUrl: String?,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = httpClient
    ): MovieHashResult? = withContext(Dispatchers.IO) {
        if (videoUrl.isNullOrBlank()) {
            Log.d(TAG, "SUBTITLE_HASH skipped: video URL is empty")
            return@withContext null
        }

        if (!isSeekableMediaUrl(videoUrl)) {
            Log.i(TAG, "SUBTITLE_HASH skipped: non-seekable streaming format (HLS/DASH or live)")
            return@withContext null
        }

        try {
            // ── Step 1: Request first 64 KiB ──────────────────────────────────────────
            val headReqBuilder = Request.Builder()
                .url(videoUrl)
                .header("Range", "bytes=0-${HASH_CHUNK_SIZE - 1}")
                .header("Accept-Encoding", "identity")

            headers.forEach { (k, v) -> headReqBuilder.header(k, v) }

            val headResp = client.newCall(headReqBuilder.build()).execute()
            headResp.use { response ->
                val code = response.code
                if (code != 206) {
                    Log.w(
                        TAG,
                        "SUBTITLE_HASH skipped: server does not support HTTP Range (HTTP $code). Aborting to avoid full download."
                    )
                    return@withContext null
                }

                val body = response.body
                if (body == null) {
                    Log.w(TAG, "SUBTITLE_HASH skipped: empty response body for head range")
                    return@withContext null
                }

                val headBytes = body.bytes()
                if (headBytes.size < HASH_CHUNK_SIZE) {
                    Log.w(TAG, "SUBTITLE_HASH skipped: head range returned ${headBytes.size} bytes, expected $HASH_CHUNK_SIZE")
                    return@withContext null
                }

                val contentRange = response.header("Content-Range")
                val totalSize = parseTotalSizeFromContentRange(contentRange)
                if (totalSize == null || totalSize <= HASH_CHUNK_SIZE * 2) {
                    Log.w(
                        TAG,
                        "SUBTITLE_HASH skipped: total file size could not be determined or is too small (size=$totalSize, Content-Range=\"$contentRange\")"
                    )
                    return@withContext null
                }

                // ── Step 2: Request last 64 KiB ───────────────────────────────────────
                val tailStart = totalSize - HASH_CHUNK_SIZE
                val tailEnd = totalSize - 1
                val tailReqBuilder = Request.Builder()
                    .url(videoUrl)
                    .header("Range", "bytes=$tailStart-$tailEnd")
                    .header("Accept-Encoding", "identity")

                headers.forEach { (k, v) -> tailReqBuilder.header(k, v) }

                val tailResp = client.newCall(tailReqBuilder.build()).execute()
                tailResp.use { tailResponse ->
                    if (tailResponse.code != 206) {
                        Log.w(TAG, "SUBTITLE_HASH skipped: tail Range request failed (HTTP ${tailResponse.code})")
                        return@withContext null
                    }
                    val tailBody = tailResponse.body ?: return@withContext null
                    val tailBytes = tailBody.bytes()
                    if (tailBytes.size < HASH_CHUNK_SIZE) {
                        Log.w(TAG, "SUBTITLE_HASH skipped: tail bytes size ${tailBytes.size} < $HASH_CHUNK_SIZE")
                        return@withContext null
                    }

                    val hashStr = computeHash(headBytes, tailBytes, totalSize)
                    Log.i(TAG, "SUBTITLE_HASH calculated: hash=$hashStr, size=$totalSize, url=$videoUrl")
                    return@withContext MovieHashResult(hash = hashStr, fileSize = totalSize)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SUBTITLE_HASH failed for url=$videoUrl: ${e.message}")
            null
        }
    }

    /**
     * Extracts total size from a Content-Range header, e.g. "bytes 0-65535/104857600".
     */
    fun parseTotalSizeFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex >= contentRange.length - 1) return null
        val sizeStr = contentRange.substring(slashIndex + 1).trim()
        return sizeStr.toLongOrNull()
    }

    /**
     * Computes the 64-bit integer sum hash over the head, tail and total size.
     */
    fun computeHash(headBytes: ByteArray, tailBytes: ByteArray, totalSize: Long): String {
        var hash = totalSize

        val headBuffer = ByteBuffer.wrap(headBytes, 0, HASH_CHUNK_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        while (headBuffer.remaining() >= 8) {
            hash += headBuffer.long
        }

        val tailBuffer = ByteBuffer.wrap(tailBytes, 0, HASH_CHUNK_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        while (tailBuffer.remaining() >= 8) {
            hash += tailBuffer.long
        }

        return String.format(Locale.US, "%016x", hash)
    }

    /**
     * Computes MovieHash from a local file (useful for testing and local playback).
     */
    fun computeFromFile(file: File): MovieHashResult? {
        if (!file.exists() || !file.canRead()) return null
        val totalSize = file.length()
        if (totalSize < HASH_CHUNK_SIZE * 2) return null

        try {
            val head = ByteArray(HASH_CHUNK_SIZE.toInt())
            val tail = ByteArray(HASH_CHUNK_SIZE.toInt())

            FileInputStream(file).use { fis ->
                var read = 0
                while (read < head.size) {
                    val r = fis.read(head, read, head.size - read)
                    if (r <= 0) break
                    read += r
                }
            }

            file.inputStream().use { fis ->
                fis.channel.position(totalSize - HASH_CHUNK_SIZE)
                var read = 0
                while (read < tail.size) {
                    val r = fis.read(tail, read, tail.size - read)
                    if (r <= 0) break
                    read += r
                }
            }

            val hash = computeHash(head, tail, totalSize)
            return MovieHashResult(hash, totalSize)
        } catch (_: Exception) {
            return null
        }
    }
}
