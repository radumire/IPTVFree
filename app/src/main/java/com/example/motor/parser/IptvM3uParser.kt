package com.example.motor.parser

import android.util.Log
import com.example.motor.db.IptvItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Parser de listas M3U/M3U8/M3U+ de alto rendimiento con arquitectura desacoplada:
 * M3uAttributeParser -> MediaClassifier -> SeriesGrouper.
 * Todos los submódulos están encapsulados para evitar conflictos de redeclaración en el paquete.
 */
object IptvM3uParser {

    private const val TAG = "IptvM3uParser"

    object M3uAttributeParser {
        fun extractName(line: String): String {
            if (!line.startsWith("#EXTINF:", ignoreCase = true)) {
                return line.substringAfter(',').trim()
            }
            val content = line.substringAfter(':')
            var inQuotes = false
            var delimiterIndex = -1
            for (i in content.indices) {
                val c = content[i]
                if (c == '"') {
                    inQuotes = !inQuotes
                } else if (c == ',' && !inQuotes) {
                    delimiterIndex = i
                    break
                }
            }
            return if (delimiterIndex != -1) {
                content.substring(delimiterIndex + 1).trim()
            } else {
                line.substringAfterLast(',').trim()
            }
        }

        fun extractAttribute(line: String, attrName: String): String? {
            val pattern = Regex("""$attrName="([^"]*)"""", RegexOption.IGNORE_CASE)
            val match = pattern.find(line)
            if (match != null) {
                return match.groupValues[1]
            }
            val unquotedPattern = Regex("""$attrName=([^,\s]+)""", RegexOption.IGNORE_CASE)
            return unquotedPattern.find(line)?.groupValues?.get(1)
        }

        fun parseExtInfLine(line: String): IptvItem {
            val name = extractName(line)
            val group = extractAttribute(line, "group-title") ?: "General"
            val logo = extractAttribute(line, "tvg-logo") ?: extractAttribute(line, "logo")
            val tvgId = extractAttribute(line, "tvg-id")
            val tvgName = extractAttribute(line, "tvg-name")
            val isRadio = extractAttribute(line, "radio")?.equals("true", ignoreCase = true) == true

            return IptvItem(
                name = name,
                group = group,
                logo = logo,
                tvgId = tvgId,
                tvgName = tvgName,
                radio = isRadio
            )
        }
    }

    object MediaClassifier {
        enum class MediaType {
            LIVE,
            MOVIE,
            SERIES,
            RADIO
        }

        private val seriesPatterns = listOf(
            Regex("""(?i)\bS\d+\s*E\d+\b"""),
            Regex("""(?i)\b\d+x\d+\b"""),
            Regex("""(?i)\bTemporada\s*\d+\s*(?:Cap[ií]tulo|Episodio)\s*\d+\b"""),
            Regex("""الموسم\s*\d+\s*الحلقة\s*\d+""")
        )

        fun classify(title: String, group: String, url: String, isRadio: Boolean = false): MediaType {
            if (isRadio || url.endsWith(".mp3", ignoreCase = true) || url.endsWith(".aac", ignoreCase = true) || group.contains("radio", ignoreCase = true)) {
                return MediaType.RADIO
            }

            // Check if title clearly has an episode numbering pattern
            val hasEpisodeNumbering = seriesPatterns.any { it.containsMatchIn(title) }
            if (hasEpisodeNumbering) {
                return MediaType.SERIES
            }

            val urlLower = url.lowercase()
            val groupLower = group.lowercase()

            // If URL explicitly points to series API
            if (urlLower.contains("/series/")) {
                return MediaType.SERIES
            }

            // If URL explicitly points to movie/vod or title has movie pattern
            if (urlLower.contains("/movie/") || urlLower.contains("/vod/")) {
                return MediaType.MOVIE
            }

            // Group-based movie check (when not a live channel)
            val isMovieGroup = groupLower.contains("película") || groupLower.contains("pelicula") ||
                    groupLower.contains("cine") || (groupLower.contains("vod") && !groupLower.contains("series"))

            if (isMovieGroup && (urlLower.endsWith(".mp4") || urlLower.endsWith(".mkv") || urlLower.endsWith(".avi") || Regex("""\(\d{4}\)""").containsMatchIn(title))) {
                return MediaType.MOVIE
            }

            // Year in title with video extension
            if (Regex("""\(\d{4}\)""").containsMatchIn(title) && (urlLower.endsWith(".mp4") || urlLower.endsWith(".mkv") || urlLower.endsWith(".avi"))) {
                return MediaType.MOVIE
            }

            return MediaType.LIVE
        }
    }

    object SeriesEpisodeParser {
        data class EpisodeMeta(
            val seriesName: String,
            val seasonNumber: Int,
            val episodeNumber: Int
        )

        private val sEPattern = Regex("""(?i)^(.+?)\s+S0*(\d+)\s*E0*(\d+)(.*)$""")
        private val xPattern = Regex("""(?i)^(.+?)\s+0*(\d+)x0*(\d+)(.*)$""")
        private val tempCapPattern = Regex("""(?i)^(.+?)\s*[-:]?\s*Temporada\s*0*(\d+)\s*(?:Cap[ií]tulo|Episodio)\s*0*(\d+)(.*)$""")
        private val arabicPattern = Regex("""^(.+?)\s+الموسم\s*0*(\d+)\s+الحلقة\s*0*(\d+)(.*)$""")

        fun parseEpisode(rawTitle: String, group: String = "", url: String = ""): EpisodeMeta {
            sEPattern.find(rawTitle)?.let { match ->
                val name = match.groupValues[1].trim().removeSuffix("-").trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                val episode = match.groupValues[3].toIntOrNull() ?: 1
                return EpisodeMeta(name, season, episode)
            }

            xPattern.find(rawTitle)?.let { match ->
                val name = match.groupValues[1].trim().removeSuffix("-").trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                val episode = match.groupValues[3].toIntOrNull() ?: 1
                return EpisodeMeta(name, season, episode)
            }

            tempCapPattern.find(rawTitle)?.let { match ->
                val name = match.groupValues[1].trim().removeSuffix("-").trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                val episode = match.groupValues[3].toIntOrNull() ?: 1
                return EpisodeMeta(name, season, episode)
            }

            arabicPattern.find(rawTitle)?.let { match ->
                val name = match.groupValues[1].trim().removeSuffix("-").trim()
                val season = match.groupValues[2].toIntOrNull() ?: 1
                val episode = match.groupValues[3].toIntOrNull() ?: 1
                return EpisodeMeta(name, season, episode)
            }

            return EpisodeMeta(rawTitle.trim(), 1, 1)
        }
    }

    object SeriesGrouper {
        data class SeriesHierarchy(
            val seriesTitle: String,
            val seasons: List<SeasonHierarchy>
        )

        data class SeasonHierarchy(
            val seasonNumber: Int,
            val episodes: List<IptvItem>
        )

        fun groupSeriesHierarchy(episodes: List<IptvItem>): List<SeriesHierarchy> {
            val seriesMap = LinkedHashMap<String, MutableMap<Int, MutableList<IptvItem>>>()

            for (ep in episodes) {
                val meta = SeriesEpisodeParser.parseEpisode(ep.name, ep.group, ep.url ?: "")
                val title = meta.seriesName
                val seasonsMap = seriesMap.getOrPut(title) { LinkedHashMap() }
                val epList = seasonsMap.getOrPut(meta.seasonNumber) { ArrayList() }
                epList.add(ep)
            }

            return seriesMap.map { (title, seasonsMap) ->
                val seasonList = seasonsMap.map { (seasonNum, epList) ->
                    SeasonHierarchy(seasonNum, epList)
                }.sortedBy { it.seasonNumber }
                SeriesHierarchy(title, seasonList)
            }
        }
    }

    data class ParsedM3u(
        val live: List<IptvItem> = emptyList(),
        val movies: List<IptvItem> = emptyList(),
        val series: List<IptvItem> = emptyList(),
        val radio: List<IptvItem> = emptyList()
    )

    fun parseFromString(content: String): ParsedM3u {
        return parseReader(BufferedReader(StringReader(content)))
    }

    fun parseStream(inputStream: InputStream): ParsedM3u {
        return parseReader(BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)))
    }

    private fun parseReader(reader: BufferedReader): ParsedM3u {
        val liveList = mutableListOf<IptvItem>()
        val movieList = mutableListOf<IptvItem>()
        val seriesList = mutableListOf<IptvItem>()
        val radioList = mutableListOf<IptvItem>()

        var currentItem: IptvItem? = null
        var line: String? = reader.readLine()

        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                currentItem = M3uAttributeParser.parseExtInfLine(trimmed)
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                if (currentItem != null) {
                    val fullItem = currentItem.copy(url = trimmed)
                    val isRadioAttr = currentItem.radio ||
                            currentItem.name.contains("radio", ignoreCase = true) ||
                            currentItem.group.contains("radio", ignoreCase = true)
                    when (MediaClassifier.classify(fullItem.name, fullItem.group, trimmed, isRadioAttr)) {
                        MediaClassifier.MediaType.LIVE -> liveList.add(fullItem)
                        MediaClassifier.MediaType.MOVIE -> movieList.add(fullItem)
                        MediaClassifier.MediaType.SERIES -> seriesList.add(fullItem)
                        MediaClassifier.MediaType.RADIO -> radioList.add(fullItem)
                    }
                    currentItem = null
                }
            }
            line = reader.readLine()
        }

        return ParsedM3u(liveList, movieList, seriesList, radioList)
    }

    /**
     * Descarga y parsea una lista M3U/M3U8 remota o local por URL.
     */
    suspend fun downloadAndParse(
        url: String,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((String) -> Unit)? = null
    ): ParsedM3u = withContext(Dispatchers.IO) {
        onProgress?.invoke("Conectando con la lista M3U...")
        val requestBuilder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val okClient = BaseEngine.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            okClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error ${response.code} downloading M3U: $url")
                    return@withContext ParsedM3u()
                }
                val stream = response.body?.byteStream() ?: return@withContext ParsedM3u()
                onProgress?.invoke("Descargando y parseando contenido...")
                stream.use { parseStream(it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading and parsing M3U: $url", e)
            ParsedM3u()
        }
    }
}
