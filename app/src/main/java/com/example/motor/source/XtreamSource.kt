package com.example.motor.source

import android.util.Log
import com.example.motor.config.Playlist
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesSeason
import com.example.motor.parser.XtreamCredentials
import com.example.motor.parser.XtreamParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XtreamSource {
    private val TAG = "XtreamSource"

    suspend fun loadLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, List<IptvItem>>()
        try {
            var host = playlist.host
            var port = playlist.port
            var user = playlist.user
            var pass = playlist.pass

            if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
                val inputUrl = playlist.url.ifEmpty { playlist.host }
                val creds = XtreamParser.resolveXtreamCredentials(inputUrl)
                if (creds != null) {
                    host = creds.host
                    port = creds.port
                    user = creds.user
                    pass = creds.pass
                }
            }

            val catalog = XtreamParser.fetchXtreamCatalog(host, port, user, pass) { }
            val channels = catalog["tv"] ?: emptyList()

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
            Log.e(TAG, "Error fetching Xtream live channels", e)
        }

        result
    }

    suspend fun fetchSeriesEpisodes(playlist: Playlist, seriesId: String): List<SeriesSeason> = withContext(Dispatchers.IO) {
        try {
            var host = playlist.host
            var port = playlist.port.ifEmpty { "80" }
            var user = playlist.user
            var pass = playlist.pass

            if (host.isBlank() || user.isBlank() || pass.isBlank()) {
                val creds = XtreamParser.resolveXtreamCredentials(playlist.url.ifEmpty { playlist.host })
                if (creds != null) {
                    host = creds.host
                    port = creds.port
                    user = creds.user
                    pass = creds.pass
                }
            }

            if (host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                XtreamParser.fetchXtreamSeriesEpisodes(
                    host = host,
                    port = port,
                    user = user,
                    pass = pass,
                    seriesId = seriesId
                )
            } else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Xtream series episodes", e)
            emptyList()
        }
    }

    suspend fun fetchCatalog(
        host: String,
        port: String,
        user: String,
        pass: String,
        onProgress: (String) -> Unit
    ): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        XtreamParser.fetchXtreamCatalog(host, port, user, pass, onProgress)
    }

    fun parseCredentialsDirect(rawInput: String): XtreamCredentials? {
        return XtreamParser.parseXtreamCredentialsDirect(rawInput)
    }

    suspend fun resolveCredentials(rawInput: String): XtreamCredentials? = withContext(Dispatchers.IO) {
        XtreamParser.resolveXtreamCredentials(rawInput)
    }

    suspend fun fetchVodInfo(
        host: String,
        port: String,
        user: String,
        pass: String,
        vodId: String
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
        XtreamParser.fetchXtreamVodInfo(host, port, user, pass, vodId)
    }

    suspend fun fetchSeriesInfo(
        host: String,
        port: String,
        user: String,
        pass: String,
        seriesId: String
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
        XtreamParser.fetchXtreamSeriesInfo(host, port, user, pass, seriesId)
    }
}
