package com.example.motor.source

import android.util.Log
import com.example.motor.config.JsonUtils
import com.example.motor.config.Playlist
import com.example.motor.config.StalkerDevice
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesSeason
import com.example.motor.parser.NetworkError
import com.example.motor.parser.StalkerParser
import com.example.motor.repository.PortalSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StalkerSource(
    private val sessionRepo: PortalSessionRepository
) {
    private val TAG = "StalkerSource"

    suspend fun loadLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        val device = resolveEffectiveDevice(playlist, session)
        val result = mutableMapOf<String, List<IptvItem>>()

        try {
            val channels = StalkerParser.fetchStalkerTv(
                host = playlist.host,
                mac = playlist.mac,
                device = device,
                onProgress = {},
                existingSession = session
            )

            if (channels.isNotEmpty()) {
                result["Todos"] = channels
            }

            val grouped = channels.groupBy { if (it.group.isBlank()) "General" else it.group }
            grouped.forEach { (catName, items) ->
                if (catName.isNotBlank() && items.isNotEmpty()) {
                    result[catName] = items
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker live channels", e)
        }

        result
    }

    suspend fun loadVodCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        val device = resolveEffectiveDevice(playlist, session)
        val result = mutableMapOf<String, List<IptvItem>>()

        try {
            val movies = StalkerParser.fetchStalkerVod(
                host = playlist.host,
                mac = playlist.mac,
                device = device,
                onProgress = {},
                existingSession = session
            )

            if (movies.isNotEmpty()) {
                result["Todos"] = movies
            }

            val grouped = movies.groupBy { if (it.group.isBlank()) "General" else it.group }
            grouped.forEach { (catName, items) ->
                if (catName.isNotBlank() && items.isNotEmpty()) {
                    result[catName] = items
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker VOD", e)
        }

        result
    }

    suspend fun loadSeriesCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        val device = resolveEffectiveDevice(playlist, session)
        val result = mutableMapOf<String, List<IptvItem>>()

        try {
            val series = StalkerParser.fetchStalkerSeries(
                host = playlist.host,
                mac = playlist.mac,
                device = device,
                onProgress = {},
                existingSession = session
            )

            if (series.isNotEmpty()) {
                result["Todos"] = series
            }

            val grouped = series.groupBy { if (it.group.isBlank()) "General" else it.group }
            grouped.forEach { (catName, items) ->
                if (catName.isNotBlank() && items.isNotEmpty()) {
                    result[catName] = items
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker series", e)
        }

        result
    }

    suspend fun fetchSeriesEpisodes(playlist: Playlist, seriesId: String, seriesCmd: String? = null): List<SeriesSeason> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        if (session == null) return@withContext emptyList()
        val portalUrl = session["portalUrl"] as? String ?: return@withContext emptyList()
        val mac = session["mac"] as? String ?: return@withContext emptyList()
        val token = session["token"] as? String ?: ""
        val device = (session["device"] as? StalkerDevice) ?: resolveEffectiveDevice(playlist, session)

        try {
            StalkerParser.fetchStalkerSeriesEpisodes(portalUrl, mac, seriesId, token, device, seriesCmd)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Stalker series episodes", e)
            emptyList()
        }
    }

    suspend fun resolvePlayUrl(playlist: Playlist, cmd: String, mediaType: String = "series"): String = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }
        if (session == null) {
            if (cmd.startsWith("http://", ignoreCase = true) || cmd.startsWith("https://", ignoreCase = true) || cmd.startsWith("rtsp://", ignoreCase = true) || cmd.startsWith("rtmp://", ignoreCase = true)) {
                return@withContext cmd
            }
            throw NetworkError.Auth("No se pudo iniciar sesión en el portal Stalker")
        }
        try {
            StalkerParser.resolveStalkerPlayLink(session, cmd, mediaType)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Stalker play link for $mediaType: $cmd", e)
            try {
                Log.i(TAG, "Attempting silent re-handshake and retry once for Stalker $mediaType playback...")
                sessionRepo.reconnectActivePlaylistSilently()
                val freshSession = sessionRepo.getStalkerSession(plIdStr)
                if (freshSession != null) {
                    return@withContext StalkerParser.resolveStalkerPlayLink(freshSession, cmd, mediaType)
                }
            } catch (retryEx: Exception) {
                Log.e(TAG, "Silent re-handshake retry failed for Stalker $mediaType playback", retryEx)
            }
            if (cmd.startsWith("http://", ignoreCase = true) || cmd.startsWith("https://", ignoreCase = true) || cmd.startsWith("rtsp://", ignoreCase = true) || cmd.startsWith("rtmp://", ignoreCase = true)) {
                cmd
            } else {
                throw e
            }
        }
    }

    fun buildHeaders(playlist: Playlist, session: Map<String, Any>? = null): Map<String, String> {
        val targetSession = session ?: sessionRepo.getStalkerSession(playlist.id.toString())
        val macClean = playlist.mac.trim().uppercase()
        val device = resolveEffectiveDevice(playlist, targetSession)
        val token = (targetSession?.get("token") as? String) ?: ""
        val userAgent = if (device.userAgent.isNotBlank()) device.userAgent else "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        val portalUrl = (targetSession?.get("portalUrl") as? String) ?: playlist.host
        @Suppress("UNCHECKED_CAST")
        val extraCookies = (targetSession?.get("cookies") as? Map<String, String>)
            ?: StalkerParser.getPortalCookiesSnapshot(portalUrl, macClean)

        val cookieParts = ArrayList<String>()
        if (macClean.isNotEmpty()) {
            cookieParts.add("mac=$macClean")
            cookieParts.add("stb_lang=en")
            cookieParts.add("timezone=Europe/Madrid")
            if (token.isNotEmpty()) {
                cookieParts.add("token=$token")
            }
        }
        val reserved = setOf("mac", "stb_lang", "timezone", "token")
        extraCookies.forEach { (k, v) ->
            if (k.lowercase() !in reserved && v.isNotBlank()) {
                cookieParts.add("$k=$v")
            }
        }
        val cookieHeader = if (cookieParts.isNotEmpty()) cookieParts.joinToString("; ") + ";" else ""

        val referer = StalkerParser.derivePortalReferer(portalUrl)

        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "X-User-Agent" to (if (device.xUserAgent.isNotBlank()) device.xUserAgent else "Model: MAG254; Link: Ethernet"),
            "Accept" to "*/*"
        )
        if (cookieHeader.isNotEmpty()) {
            headers["Cookie"] = cookieHeader
        }
        if (referer.isNotBlank()) {
            headers["Referer"] = referer
        }
        return headers
    }

    suspend fun resolveStreamUrl(playlist: Playlist, item: IptvItem, mediaType: String = "live"): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val plIdStr = playlist.id.toString()
        var session = sessionRepo.getStalkerSession(plIdStr)
        if (session == null || (session["token"] as? String).isNullOrEmpty()) {
            sessionRepo.reconnectActivePlaylistSilently()
            session = sessionRepo.getStalkerSession(plIdStr)
        }

        if (session == null) {
            val fallbackUrl = item.url ?: ""
            if (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true)) {
                return@withContext Pair(fallbackUrl, emptyMap())
            }
            throw NetworkError.Auth("No se pudo iniciar sesión en el portal Stalker")
        }

        val cmd = item.stalkerCmd ?: item.url ?: ""
        val targetType = if (mediaType == "live") "itv" else mediaType

        val resolvedUrl = try {
            StalkerParser.resolveStalkerPlayLink(session, cmd, targetType)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Stalker stream link for $targetType: $cmd", e)
            try {
                Log.i(TAG, "Attempting silent re-handshake and retry once for Stalker $targetType stream...")
                sessionRepo.reconnectActivePlaylistSilently()
                val freshSession = sessionRepo.getStalkerSession(plIdStr)
                if (freshSession != null) {
                    StalkerParser.resolveStalkerPlayLink(freshSession, cmd, targetType)
                } else {
                    throw e
                }
            } catch (retryEx: Exception) {
                Log.e(TAG, "Silent re-handshake retry failed for Stalker $targetType stream", retryEx)
                if (cmd.startsWith("http://", ignoreCase = true) || cmd.startsWith("https://", ignoreCase = true) || cmd.startsWith("rtsp://", ignoreCase = true) || cmd.startsWith("rtmp://", ignoreCase = true)) {
                    cmd
                } else {
                    throw e
                }
            }
        }
        val latestSession = sessionRepo.getStalkerSession(plIdStr) ?: session
        val headers = buildHeaders(playlist, latestSession)

        Pair(resolvedUrl, headers)
    }

    suspend fun fetchCatalog(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit,
        onPartialUpdate: (String, List<IptvItem>) -> Unit,
        onSessionReady: (Map<String, Any>) -> Unit,
        playlistId: Long? = null
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        StalkerParser.fetchStalkerCatalog(
            host = host,
            mac = mac,
            device = device,
            onProgress = onProgress,
            onPartialUpdate = onPartialUpdate,
            onSessionReady = onSessionReady,
            playlistId = playlistId
        )
    }

    suspend fun performHandshake(host: String, mac: String, device: StalkerDevice, playlistId: Long? = null): Map<String, Any> = withContext(Dispatchers.IO) {
        StalkerParser.performStalkerHandshake(host, mac, device, playlistId)
    }

    suspend fun fetchTv(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> = withContext(Dispatchers.IO) {
        StalkerParser.fetchStalkerTv(host, mac, device, onProgress, onPartialUpdate, existingSession)
    }

    suspend fun fetchVod(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> = withContext(Dispatchers.IO) {
        StalkerParser.fetchStalkerVod(host, mac, device, onProgress, onPartialUpdate, existingSession)
    }

    suspend fun fetchSeries(
        host: String,
        mac: String,
        device: StalkerDevice,
        onProgress: (String) -> Unit = {},
        onPartialUpdate: ((type: String, items: List<IptvItem>) -> Unit)? = null,
        existingSession: Map<String, Any>? = null
    ): List<IptvItem> = withContext(Dispatchers.IO) {
        StalkerParser.fetchStalkerSeries(host, mac, device, onProgress, onPartialUpdate, existingSession)
    }

    internal fun resolveEffectiveDevice(playlist: Playlist, session: Map<String, Any>?): StalkerDevice {
        val sessionDevice = session?.get("device") as? StalkerDevice
        if (sessionDevice != null) {
            return sessionDevice
        }
        val configuredDevice = if (playlist.stalkerDeviceJson.isNotBlank()) {
            try { JsonUtils.stalkerDeviceFromJson(playlist.stalkerDeviceJson) } catch (_: Exception) { null }
        } else null
        val compatState = sessionRepo.getStalkerCompatState(playlist.id.toString())
        if (compatState != null) {
            return compatState.deviceModel.toStalkerDevice(configuredDevice?.serialNumber ?: "")
        }
        return configuredDevice ?: StalkerDevice()
    }
}
