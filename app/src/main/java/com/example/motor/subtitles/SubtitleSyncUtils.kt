package com.example.motor.subtitles

import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Utility to shift subtitle timestamps in SRT and VTT files for precise synchronization.
 */
object SubtitleSyncUtils {
    private const val TAG = "SubtitleSyncUtils"

    /**
     * Shifts all timestamps in the provided SRT/VTT file by [offsetMs].
     * Returns a new file with the shifted timings.
     */
    fun createShiftedSubtitleFile(sourceFile: File, offsetMs: Long): File {
        if (!sourceFile.exists() || offsetMs == 0L) return sourceFile

        val isVtt = sourceFile.extension.equals("vtt", ignoreCase = true)
        val ext = if (isVtt) "vtt" else "srt"
        val baseName = sourceFile.nameWithoutExtension.substringBefore("_sync_")
        val outFile = File(sourceFile.parentFile, "${baseName}_sync_${offsetMs}.$ext")

        if (outFile.exists() && outFile.length() > 50) {
            return outFile
        }

        try {
            val lines = sourceFile.readLines(Charsets.UTF_8)
            val shiftedLines = ArrayList<String>(lines.size)

            for (line in lines) {
                if (line.contains("-->")) {
                    val shiftedLine = shiftTimeline(line, offsetMs, isVtt)
                    shiftedLines.add(shiftedLine)
                } else {
                    shiftedLines.add(line)
                }
            }

            outFile.writeText(shiftedLines.joinToString("\n"), Charsets.UTF_8)
            Log.d(TAG, "Created shifted subtitle file: ${outFile.name} (offset=${offsetMs}ms)")
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create shifted subtitle file", e)
            return sourceFile
        }
    }

    private fun shiftTimeline(timeline: String, offsetMs: Long, isVtt: Boolean): String {
        val parts = timeline.split("-->")
        if (parts.size != 2) return timeline

        val startPart = parts[0].trim()
        val rest = parts[1].trim()

        // In VTT there may be cue settings after the end timestamp (e.g. "00:01:23.000 line:0%")
        val endPart = rest.substringBefore(" ").trim()
        val settings = if (rest.contains(" ")) rest.substring(rest.indexOf(" ")) else ""

        val newStartMs = (parseTimestampToMs(startPart) + offsetMs).coerceAtLeast(0L)
        val newEndMs = (parseTimestampToMs(endPart) + offsetMs).coerceAtLeast(newStartMs + 100L)

        val formattedStart = formatMsToTimestamp(newStartMs, isVtt)
        val formattedEnd = formatMsToTimestamp(newEndMs, isVtt)

        return "$formattedStart --> $formattedEnd$settings"
    }

    fun parseTimestampToMs(timeStr: String): Long {
        val clean = timeStr.trim().replace(',', '.')
        val parts = clean.split(":")
        try {
            return when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secParts = parts[2].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + millis
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    (minutes * 60000L) + (seconds * 1000L) + millis
                }
                else -> 0L
            }
        } catch (_: Exception) {
            return 0L
        }
    }

    fun formatMsToTimestamp(ms: Long, isVtt: Boolean): String {
        val totalSec = ms / 1000L
        val millis = ms % 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        val delim = if (isVtt) '.' else ','

        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, delim, millis)
    }

    data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    fun parseSubtitleFile(file: File): List<SubtitleCue> {
        if (!file.exists() || file.length() < 10) return emptyList()
        val isVtt = file.extension.equals("vtt", ignoreCase = true)
        return try {
            val rawBytes = file.readBytes()
            val text = decodeSubtitleBytes(rawBytes)
            parseSubtitleContent(text, isVtt)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading subtitle file for parsing", e)
            emptyList()
        }
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // Check for BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // Try UTF-8 first
        try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {}

        // Fallback charsets (GBK / GB2312 for Chinese subs, Windows-1252, ISO-8859-1)
        val charsets = listOf("GBK", "GB2312", "GB18030", "Big5", "windows-1252", "ISO-8859-1")
        for (cName in charsets) {
            try {
                val cs = java.nio.charset.Charset.forName(cName)
                val decoder = cs.newDecoder()
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun parseSubtitleContent(content: String, isVtt: Boolean = false): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = content.lines()

        // Detect if this is an ASS / SSA file
        val isAss = content.contains("[Events]", ignoreCase = true) ||
                content.lines().any { it.trim().startsWith("Dialogue:", ignoreCase = true) }

        if (isAss) {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                    try {
                        val afterColon = trimmed.substringAfter(":").trim()
                        val parts = afterColon.split(",", limit = 10)
                        if (parts.size >= 10) {
                            val startMs = parseTimestampToMs(parts[1].trim())
                            val endMs = parseTimestampToMs(parts[2].trim())
                            val rawText = parts[9]
                            val cleanText = rawText
                                .replace(Regex("\\{[^}]*\\}"), "") // strip override tags like {\pos(100,200)}
                                .replace("\\N", "\n")
                                .replace("\\n", "\n")
                                .trim()
                            if (cleanText.isNotEmpty() && endMs > startMs) {
                                cues.add(SubtitleCue(startMs, endMs, cleanText))
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            if (cues.isNotEmpty()) {
                return cues.sortedBy { it.startMs }
            }
        }

        // Standard SRT / VTT parsing
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val parts = line.split("-->")
                if (parts.size == 2) {
                    val startPart = parts[0].trim()
                    val rest = parts[1].trim()
                    val endPart = rest.substringBefore(" ").trim()
                    val startMs = parseTimestampToMs(startPart)
                    val endMs = parseTimestampToMs(endPart)

                    val textBuilder = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].trim().isNotEmpty() && !lines[i].contains("-->")) {
                        val tLine = lines[i].trim()
                        val cleanText = tLine
                            .replace(Regex("<[^>]+>"), "")
                            .replace(Regex("\\{[^}]+\\}"), "")
                            .trim()
                        if (cleanText.isNotEmpty() && !cleanText.matches(Regex("^\\d+$"))) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(cleanText)
                        }
                        i++
                    }
                    val text = textBuilder.toString().trim()
                    if (text.isNotEmpty() && endMs > startMs) {
                        cues.add(SubtitleCue(startMs, endMs, text))
                    }
                    continue
                }
            }
            i++
        }
        return cues.sortedBy { it.startMs }
    }
}
