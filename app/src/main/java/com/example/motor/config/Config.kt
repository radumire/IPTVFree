package com.example.motor.config

import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesSeason
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import android.util.Log

// ─── PLAYLIST DATA MODEL ─────────────────────────────────────────────────────

data class Playlist(
    val id: Long = 0L,
    val type: String = "m3u", // "m3u", "xtream", "stalker"
    val name: String = "",
    val url: String = "",
    val host: String = "",
    val user: String = "",
    val pass: String = "",
    val port: String = "80",
    val expires: String = "",
    val mac: String = "",
    val stalkerDeviceJson: String = "", // StalkerDevice serialized as JSON
    val active: Boolean = false
)

fun formatMacAddress(input: String): String {
    val cleanHex = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(12)
    return cleanHex.chunked(2).joinToString(":")
}

fun normalizeHost(host: String): String {
    var h = host.trim().trimEnd('/')
    if (h.isEmpty()) return ""
    if (!h.startsWith("http://", ignoreCase = true) && !h.startsWith("https://", ignoreCase = true)) {
        h = "http://$h"
    }
    return try {
        val uri = java.net.URI(h)
        val hostPart = uri.host?.lowercase() ?: h.lowercase()
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val pathPart = uri.path?.trimEnd('/')?.lowercase() ?: ""
        val queryPart = if (!uri.query.isNullOrEmpty()) "?${uri.query}" else ""
        "$hostPart$portPart$pathPart$queryPart"
    } catch (e: Exception) {
        h.lowercase().removePrefix("http://").removePrefix("https://").trimEnd('/')
    }
}

fun normalizeUrl(url: String): String {
    return url.trim().trimEnd('/').lowercase()
}

fun findDuplicatePlaylist(newPl: Playlist, existingList: List<Playlist>, excludeId: Long? = null): Playlist? {
    val normNewType = newPl.type.lowercase().trim()
    val isNewXtream = normNewType == "xtream" || normNewType == "xc"
    val isNewM3u = normNewType == "m3u" || normNewType == "m3u8"
    val isNewStalker = normNewType == "stalker"

    val newNormHost = normalizeHost(newPl.host)
    val newRawUrl = newPl.url.trim()
    val newNormUrl = if (newRawUrl.isNotBlank()) normalizeUrl(newRawUrl) else ""
    val newNormUser = newPl.user.trim().lowercase()
    val newMacFormatted = formatMacAddress(newPl.mac)

    for (existing in existingList) {
        if (excludeId != null && existing.id == excludeId) continue

        val normExistType = existing.type.lowercase().trim()
        val isExistXtream = normExistType == "xtream" || normExistType == "xc"
        val isExistM3u = normExistType == "m3u" || normExistType == "m3u8"
        val isExistStalker = normExistType == "stalker"

        val existNormHost = normalizeHost(existing.host)
        val existRawUrl = existing.url.trim()
        val existNormUrl = if (existRawUrl.isNotBlank()) normalizeUrl(existRawUrl) else ""
        val existNormUser = existing.user.trim().lowercase()
        val existMacFormatted = formatMacAddress(existing.mac)

        // 1. Xtream / XC Duplicate Check:
        // Same server is allowed for different accounts. Only duplicate if BOTH host AND username match!
        if (isNewXtream && isExistXtream) {
            if (newNormHost.isNotBlank() && newNormHost == existNormHost &&
                newNormUser.isNotBlank() && newNormUser == existNormUser) {
                return existing
            }
            // If explicit URLs are entered, duplicate if both URL and user match
            if (newNormUrl.isNotBlank() && newNormUrl == existNormUrl &&
                (newNormUser.isBlank() || existNormUser.isBlank() || newNormUser == existNormUser)) {
                return existing
            }
            continue
        }

        // 2. Stalker Duplicate Check:
        // Host and MAC address must match
        if (isNewStalker && isExistStalker) {
            if (newNormHost.isNotBlank() && newNormHost == existNormHost &&
                newMacFormatted.isNotBlank() && newMacFormatted == existMacFormatted) {
                return existing
            }
            continue
        }

        // 3. M3U Duplicate Check:
        if (isNewM3u && isExistM3u) {
            val effectiveNewUrl = if (newNormUrl.isNotBlank()) newNormUrl else newNormHost
            val effectiveExistUrl = if (existNormUrl.isNotBlank()) existNormUrl else existNormHost
            if (effectiveNewUrl.isNotBlank() && effectiveNewUrl == effectiveExistUrl) {
                return existing
            }
            continue
        }

        // 4. Cross-type fallbacks:
        if (newNormHost.isNotBlank() && newNormHost == existNormHost) {
            if (newNormUser.isNotBlank() && newNormUser == existNormUser) {
                return existing
            }
            if (newMacFormatted.isNotBlank() && newMacFormatted == existMacFormatted) {
                return existing
            }
        }
    }
    return null
}

// ─── SETTINGS DATA MODEL ─────────────────────────────────────────────────────

data class PlayerSettings(
    val lang: String = "es",
    val subtitles: Boolean = false,
    val subtitleLang: String = "Español",
    val subtitleSize: String = "Normal",
    val subtitleColor: String = "Blanco",
    val subtitleBg: Boolean = true,
    val player: String = "Player integrado",
    val decode: String = "Automático",
    val ratio: String = "Ajustar",
    val autoFullscreen: Boolean = false,
    val skipSilence: Boolean = false,
    val notifications: Boolean = true,
    val autoUpdate: Boolean = false,
    val tvMode: Boolean = false,
    val homeLayout: String = "vivo_glass",
    val quality: String = "Auto",
    val timeout: String = "15s",
    val timeFormat: String = "24h",
    val parentalEnabled: Boolean = false,
    val parentalPin: String = "",
    val parentalRating: String = "12+",
    val showTv: Boolean = true,
    val showMovies: Boolean = true,
    val showSeries: Boolean = true,
    val hiddenTvCategories: List<String> = emptyList(),
    val hiddenMovieCategories: List<String> = emptyList(),
    val hiddenSeriesCategories: List<String> = emptyList(),
    val assrtToken: String = "",
    val openSubtitlesKey: String = ""
)

// ─── STALKER DEVICE CONFIG ───────────────────────────────────────────────────

data class StalkerDevice(
    val hwVersion: String = "MAG254",
    val vendor: String = "Infomir",
    val imageVersion: String = "218",
    val userAgent: String = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
    val xUserAgent: String = "Model: MAG254; Link: Ethernet",
    val serialNumber: String = "",
    val hwVersion2: String = "1.7-BD-00",
    val swVersion: String = "5.0.10"
)

// ─── JSON UTILITIES ──────────────────────────────────────────────────────────

object JsonUtils {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun playlistsFromJson(json: String): List<Playlist> {
        val type = Types.newParameterizedType(List::class.java, Playlist::class.java)
        return try { moshi.adapter<List<Playlist>>(type).fromJson(json) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun playlistsToJson(list: List<Playlist>): String {
        val type = Types.newParameterizedType(List::class.java, Playlist::class.java)
        return moshi.adapter<List<Playlist>>(type).toJson(list)
    }

    fun stringSetFromJson(json: String): Set<String> {
        val type = Types.newParameterizedType(Set::class.java, String::class.java)
        return try { moshi.adapter<Set<String>>(type).fromJson(json) ?: emptySet() } catch (e: Exception) { emptySet() }
    }

    fun stringSetToJson(set: Set<String>): String {
        val type = Types.newParameterizedType(Set::class.java, String::class.java)
        return moshi.adapter<Set<String>>(type).toJson(set)
    }

    fun customThemesFromJson(json: String): Map<String, Map<String, String>> {
        val stringMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        val type = Types.newParameterizedType(Map::class.java, String::class.java, stringMapType)
        return try { moshi.adapter<Map<String, Map<String, String>>>(type).fromJson(json) ?: emptyMap() } catch (e: Exception) { emptyMap() }
    }

    fun customThemesToJson(map: Map<String, Map<String, String>>): String {
        val stringMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        val type = Types.newParameterizedType(Map::class.java, String::class.java, stringMapType)
        return moshi.adapter<Map<String, Map<String, String>>>(type).toJson(map)
    }

    fun settingsFromJson(json: String): PlayerSettings {
        return try { moshi.adapter(PlayerSettings::class.java).fromJson(json) ?: PlayerSettings() } catch (e: Exception) { PlayerSettings() }
    }

    fun settingsToJson(settings: PlayerSettings): String {
        return moshi.adapter(PlayerSettings::class.java).toJson(settings)
    }

    fun stalkerDeviceFromJson(json: String): StalkerDevice {
        return try { moshi.adapter(StalkerDevice::class.java).fromJson(json) ?: StalkerDevice() } catch (e: Exception) { StalkerDevice() }
    }

    fun stalkerDeviceToJson(device: StalkerDevice): String {
        return moshi.adapter(StalkerDevice::class.java).toJson(device)
    }

    fun stalkerCompatStateFromJson(json: String): com.example.motor.parser.PortalCompatibilityState? {
        if (json.isBlank()) return null
        return try {
            moshi.adapter(com.example.motor.parser.PortalCompatibilityState::class.java).fromJson(json)
        } catch (_: Exception) {
            null
        }
    }

    fun stalkerCompatStateToJson(state: com.example.motor.parser.PortalCompatibilityState): String {
        return moshi.adapter(com.example.motor.parser.PortalCompatibilityState::class.java).toJson(state)
    }

    fun iptvItemsMapFromJson(json: String): Map<String, List<IptvItem>> {
        val listType = Types.newParameterizedType(List::class.java, IptvItem::class.java)
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, listType)
        return try { moshi.adapter<Map<String, List<IptvItem>>>(mapType).fromJson(json) ?: emptyMap() } catch (e: Exception) { emptyMap() }
    }

    fun iptvItemsMapToJson(map: Map<String, List<IptvItem>>): String {
        val listType = Types.newParameterizedType(List::class.java, IptvItem::class.java)
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, listType)
        return moshi.adapter<Map<String, List<IptvItem>>>(mapType).toJson(map)
    }

    fun seriesSeasonsFromJson(json: String): List<SeriesSeason> {
        val type = Types.newParameterizedType(List::class.java, SeriesSeason::class.java)
        return try { moshi.adapter<List<SeriesSeason>>(type).fromJson(json) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun seriesSeasonsToJson(list: List<SeriesSeason>): String {
        val type = Types.newParameterizedType(List::class.java, SeriesSeason::class.java)
        return moshi.adapter<List<SeriesSeason>>(type).toJson(list)
    }

    fun episodesListFromJson(json: String): List<SeriesSeason> = seriesSeasonsFromJson(json)

    fun episodesListToJson(list: List<SeriesSeason>): String = seriesSeasonsToJson(list)
}
