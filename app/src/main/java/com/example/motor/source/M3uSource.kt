package com.example.motor.source

import android.util.Log
import com.example.motor.config.Playlist
import com.example.motor.db.IptvItem
import com.example.motor.db.SeriesSeason
import com.example.motor.parser.IptvM3uParser
import com.example.motor.parser.XtreamCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fuente M3U: Módulo de fuente IPTV aislado que utiliza el motor Xtream
 * (XtreamSource / XtreamParser) para toda la lógica de conexión, catálogo,
 * categorías, series y reproducción.
 */
class M3uSource(
    private val xtreamSource: XtreamSource
) {
    private val TAG = "M3uSource"

    /**
     * Carga las categorías de canales en vivo utilizando el motor Xtream.
     */
    suspend fun loadLiveCategories(playlist: Playlist): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        xtreamSource.loadLiveCategories(playlist)
    }

    /**
     * Descarga y construye el catálogo completo (Live, Películas, Series)
     * utilizando el motor Xtream. Si la lista M3U contiene credenciales en la URL
     * o en su contenido, las resuelve y consulta la API Xtream.
     * En caso de lista estática pura, cuenta con fallback a parseo M3U.
     */
    suspend fun fetchCatalog(playlist: Playlist, onProgress: (String) -> Unit): Map<String, List<IptvItem>> = withContext(Dispatchers.IO) {
        var host = playlist.host
        var port = playlist.port.ifEmpty { "80" }
        var user = playlist.user
        var pass = playlist.pass

        if (user.isEmpty() || pass.isEmpty() || host.isEmpty()) {
            val inputUrl = playlist.url.ifEmpty { playlist.host }
            val creds = xtreamSource.resolveCredentials(inputUrl)
            if (creds != null) {
                host = creds.host
                port = creds.port
                user = creds.user
                pass = creds.pass
            }
        }

        if (host.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) {
            return@withContext xtreamSource.fetchCatalog(host, port, user, pass, onProgress)
        }

        // Fallback para listas M3U estáticas/locales sin servidor Xtream subyacente
        val url = playlist.url.ifEmpty { playlist.host }
        if (url.isNotBlank()) {
            val parsed = IptvM3uParser.downloadAndParse(url, headers = getStreamHeaders(playlist), onProgress = onProgress)
            val result = mutableMapOf<String, List<IptvItem>>()
            if (parsed.live.isNotEmpty()) result["tv"] = parsed.live
            if (parsed.movies.isNotEmpty()) result["movie"] = parsed.movies
            if (parsed.series.isNotEmpty()) result["series"] = parsed.series
            if (parsed.radio.isNotEmpty()) result["radio"] = parsed.radio
            return@withContext result
        }

        emptyMap()
    }

    /**
     * Obtiene los episodios de una serie utilizando el motor Xtream.
     */
    suspend fun fetchSeriesEpisodes(playlist: Playlist, seriesId: String): List<SeriesSeason> = withContext(Dispatchers.IO) {
        xtreamSource.fetchSeriesEpisodes(playlist, seriesId)
    }

    /**
     * Obtiene información detallada de VOD utilizando el motor Xtream.
     */
    suspend fun fetchVodInfo(
        host: String,
        port: String,
        user: String,
        pass: String,
        vodId: String
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
        xtreamSource.fetchVodInfo(host, port, user, pass, vodId)
    }

    /**
     * Obtiene información detallada de series utilizando el motor Xtream.
     */
    suspend fun fetchSeriesInfo(
        host: String,
        port: String,
        user: String,
        pass: String,
        seriesId: String
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
        xtreamSource.fetchSeriesInfo(host, port, user, pass, seriesId)
    }

    /**
     * Resuelve credenciales Xtream a partir de cualquier URL o texto M3U.
     */
    suspend fun resolveCredentials(rawInput: String): XtreamCredentials? = withContext(Dispatchers.IO) {
        xtreamSource.resolveCredentials(rawInput)
    }

    fun resolveStreamUrl(playlist: Playlist, item: IptvItem): Pair<String, Map<String, String>> {
        return Pair(item.url ?: "", getStreamHeaders(playlist))
    }

    fun getStreamHeaders(playlist: Playlist): Map<String, String> {
        return mapOf("User-Agent" to "IPTVSmartersPro/3.1.5 (Linux; Android 11)")
    }
}
