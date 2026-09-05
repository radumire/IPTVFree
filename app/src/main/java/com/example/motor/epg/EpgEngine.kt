package com.example.motor.epg

import android.util.Log
import android.util.Xml
import com.example.motor.db.EpgChannelData
import com.example.motor.db.EpgProgramme
import com.example.motor.parser.BaseEngine
import com.example.motor.parser.NetworkError
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream

object EpgEngine {
    private const val TAG = "EpgEngine"

    suspend fun fetchAndParseEPG(url: String): Map<String, EpgChannelData> {
        var lastError: Exception? = null
        val backoffs = listOf(1000L, 2500L, 5000L)

        for (attempt in 1..3) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18").build()
                BaseEngine.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val httpErr = BaseEngine.classifyHttpStatus(response.code, response.message)
                        if (httpErr != null) throw httpErr
                    }
                    val bodyBytes = response.body?.bytes() ?: throw NetworkError.NotFound("Cuerpo de respuesta EPG vacío")
                    
                    val decompressedStream = decompressIfNeeded(bodyBytes)
                    return parseXMLTVStream(decompressedStream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "EPG load attempt $attempt failed: ${e.message}")
                lastError = e
                if (e is NetworkError && (e.code == "AUTH" || e.code == "NOT_FOUND")) {
                    throw e
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(backoffs[attempt - 1])
                    } catch (ie: InterruptedException) {
                        // ignore
                    }
                }
            }
        }
        throw BaseEngine.classifyException(lastError ?: Exception("Error de red desconocido"))
    }

    private fun decompressIfNeeded(bytes: ByteArray): InputStream {
        return if (bytes.size > 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes))
        } else {
            ByteArrayInputStream(bytes)
        }
    }

    private fun parseXMLTVStream(stream: InputStream): Map<String, EpgChannelData> {
        val result = HashMap<String, EpgChannelData>()
        val cutoffTime = System.currentTimeMillis() - 4 * 3600 * 1000L // Prune programmes finished over 4 hours ago to conserve memory

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, "UTF-8")

            var eventType = parser.eventType
            var currentChannelId: String? = null
            var currentChannelName: String? = null
            var currentChannelIcon: String? = null

            var progChannelId: String? = null
            var progStart: Long? = null
            var progStop: Long? = null
            var progTitle: String? = null
            var progSubTitle: String? = null
            var progDesc: String? = null
            var progCategory: String? = null
            var progRating: String? = null
            var progIcon: String? = null

            var currentTag: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = tagName
                        when (tagName) {
                            "channel" -> {
                                currentChannelId = parser.getAttributeValue(null, "id")
                                currentChannelName = null
                                currentChannelIcon = null
                            }
                            "icon" -> {
                                val src = parser.getAttributeValue(null, "src")
                                if (currentChannelId != null && currentChannelName == null) {
                                    currentChannelIcon = src
                                } else if (progChannelId != null) {
                                    progIcon = src
                                }
                            }
                            "programme" -> {
                                progChannelId = parser.getAttributeValue(null, "channel")
                                val startRaw = parser.getAttributeValue(null, "start")
                                val stopRaw = parser.getAttributeValue(null, "stop")
                                progStart = parseXMLTVDate(startRaw)
                                progStop = parseXMLTVDate(stopRaw)
                                progTitle = null
                                progSubTitle = null
                                progDesc = null
                                progCategory = null
                                progRating = null
                                progIcon = null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty() && currentTag != null) {
                            if (currentChannelId != null) {
                                if (currentTag == "display-name" && currentChannelName == null) {
                                    currentChannelName = text
                                }
                            } else if (progChannelId != null) {
                                when (currentTag) {
                                    "title" -> if (progTitle == null) progTitle = text
                                    "sub-title" -> if (progSubTitle == null) progSubTitle = text
                                    "desc" -> if (progDesc == null) progDesc = text
                                    "category" -> progCategory = if (progCategory == null) text else "$progCategory · $text"
                                    "value" -> if (progRating == null) progRating = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = null
                        when (tagName) {
                            "channel" -> {
                                val id = currentChannelId
                                if (!id.isNullOrEmpty()) {
                                    val name = currentChannelName ?: id
                                    result[id] = EpgChannelData(id = id, name = name, icon = currentChannelIcon, programmes = ArrayList())
                                }
                                currentChannelId = null
                            }
                            "programme" -> {
                                val chId = progChannelId
                                val start = progStart
                                val stop = progStop
                                if (!chId.isNullOrEmpty() && start != null && stop != null && stop > cutoffTime) {
                                    val chData = result[chId] ?: EpgChannelData(id = chId, name = chId, programmes = ArrayList())
                                    val list = (chData.programmes as? ArrayList<EpgProgramme>) ?: ArrayList(chData.programmes)
                                    list.add(EpgProgramme(
                                        start = start,
                                        stop = stop,
                                        title = progTitle ?: "Sin título",
                                        subtitle = progSubTitle ?: "",
                                        desc = progDesc ?: "",
                                        category = progCategory ?: "",
                                        rating = progRating ?: "",
                                        icon = progIcon
                                    ))
                                    result[chId] = chData.copy(programmes = list)
                                }
                                progChannelId = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming XMLTV pull parser: ${e.message}", e)
        }

        // Sort programmes chronologically
        result.values.forEach { ch ->
            (ch.programmes as? ArrayList<EpgProgramme>)?.sortBy { it.start }
        }

        return result
    }

    private fun parseXMLTVDate(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        val clean = s.trim()
        val parts = clean.split(Regex("\\s+"))
        val datePart = parts[0]
        val tzPart = if (parts.size > 1) parts[1] else "+0000"

        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return try {
            val parsedDate = sdf.parse(datePart.padEnd(14, '0')) ?: return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
            cal.time = parsedDate

            // Apply time zone offsets manually
            if (tzPart.length >= 5) {
                val sign = if (tzPart[0] == '-') -1 else 1
                val hr = tzPart.substring(1, 3).toIntOrNull() ?: 0
                val mn = tzPart.substring(3, 5).toIntOrNull() ?: 0
                cal.add(Calendar.HOUR_OF_DAY, -sign * hr)
                cal.add(Calendar.MINUTE, -sign * mn)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
