package com.example.motor.parser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.motor.config.StalkerDevice
import com.example.motor.repository.PortalSessionRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StalkerCatalogResilienceTest {

    @Before
    fun setUp() {
        StalkerParser.clearPortalCookiesForTests()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    @Test
    fun tvGenreFallbackPaginatesAllPagesEvenWithCrossGenreOverlap() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val type = params["type"] ?: ""
            val action = params["action"] ?: ""
            val genre = params["genre"] ?: ""
            val page = params["p"]?.toIntOrNull() ?: 1

            val (status, json) = when {
                type == "stb" && action == "handshake" ->
                    200 to """{"js":{"token":"TOK_TV_PAGES","random":"rnd1"}}"""
                type == "stb" && action == "get_profile" ->
                    200 to """{"js":{"id":"1","token":"TOK_TV_PAGES"}}"""
                type == "itv" && action == "get_genres" ->
                    200 to """{"js":[{"id":"1","title":"Deportes"},{"id":"2","title":"Cine"}]}"""
                type == "itv" && (action == "get_all_channels" || action == "get_channels" || action == "get_short_channels") ->
                    404 to "Not supported"
                type == "itv" && action == "get_ordered_list" && genre == "1" -> when (page) {
                    1 -> 200 to """{"js":{"data":[{"id":"101","name":"Sport 1","tv_genre_id":"1","cmd":"http://stream/101.ts"}]}}"""
                    2 -> 200 to """{"js":{"data":[{"id":"102","name":"Sport 2","tv_genre_id":"1","cmd":"http://stream/102.ts"}]}}"""
                    3 -> 200 to """{"js":{"data":[{"id":"103","name":"Sport 3","tv_genre_id":"1","cmd":"http://stream/103.ts"}]}}"""
                    else -> 200 to """{"js":{"data":[]}}"""
                }
                type == "itv" && action == "get_ordered_list" && genre == "2" -> when (page) {
                    // Page 1 overlaps Sport 1 intentionally; page 2 has brand-new Cine 1
                    1 -> 200 to """{"js":{"data":[{"id":"101","name":"Sport 1","tv_genre_id":"1","cmd":"http://stream/101.ts"}]}}"""
                    2 -> 200 to """{"js":{"data":[{"id":"201","name":"Cine 1","tv_genre_id":"2","cmd":"http://stream/201.ts"}]}}"""
                    // Portal quirk: page 3 repeats page 2 forever; seenInGenre guard must stop cleanly
                    else -> 200 to """{"js":{"data":[{"id":"201","name":"Cine 1","tv_genre_id":"2","cmd":"http://stream/201.ts"}]}}"""
                }
                else -> 200 to """{"js":[]}"""
            }
            val bytes = json.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val baseHost = "http://127.0.0.1:${server.address.port}"
            val tv = StalkerParser.fetchStalkerTv(baseHost, "00:1A:79:10:20:30", StalkerDevice())
            val names = tv.map { it.name }.toSet()
            assertEquals(setOf("Sport 1", "Sport 2", "Sport 3", "Cine 1"), names)
            assertEquals(4, tv.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun vodRetriesTransientHttp500AndHtmlResponseInsteadOfTruncatingCatalog() = runBlocking {
        val page2VodAttempts = AtomicInteger(0)
        val page2SeriesAttempts = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val type = params["type"] ?: ""
            val action = params["action"] ?: ""
            val page = params["p"]?.toIntOrNull() ?: 1

            val (status, body) = when {
                type == "stb" && action == "handshake" ->
                    200 to """{"js":{"token":"TOK_RETRY","random":"r1"}}"""
                type == "stb" && action == "get_profile" ->
                    200 to """{"js":{"id":"1","token":"TOK_RETRY"}}"""
                type == "vod" && action == "get_categories" ->
                    200 to """{"js":[{"id":"10","title":"Acción"}]}"""
                type == "vod" && action == "get_ordered_list" && page == 1 ->
                    200 to """{"js":{"data":[{"id":"1","name":"Movie P1","category_id":"10","cmd":"/media/1.mkv"}]}}"""
                type == "vod" && action == "get_ordered_list" && page == 2 -> {
                    val attempt = page2VodAttempts.incrementAndGet()
                    if (attempt == 1) {
                        500 to "Internal Server Error"
                    } else {
                        200 to """{"js":{"data":[{"id":"2","name":"Movie P2","category_id":"10","cmd":"/media/2.mkv"}]}}"""
                    }
                }
                type == "series" && action == "get_categories" ->
                    200 to """{"js":[{"id":"20","title":"Drama"}]}"""
                type == "series" && action == "get_ordered_list" && page == 1 ->
                    200 to """{"js":{"data":[{"id":"11","name":"Series P1","category_id":"20","cmd":"s_11"}]}}"""
                type == "series" && action == "get_ordered_list" && page == 2 -> {
                    val attempt = page2SeriesAttempts.incrementAndGet()
                    if (attempt == 1) {
                        200 to "<html><body>502 Bad Gateway</body></html>"
                    } else {
                        200 to """{"js":{"data":[{"id":"12","name":"Series P2","category_id":"20","cmd":"s_12"}]}}"""
                    }
                }
                else -> 200 to """{"js":{"data":[]}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val baseHost = "http://127.0.0.1:${server.address.port}"
            val session = StalkerParser.performStalkerHandshake(baseHost, "00:1A:79:11:22:33", StalkerDevice())

            val movies = StalkerParser.fetchStalkerVod(baseHost, "00:1A:79:11:22:33", StalkerDevice(), existingSession = session)
            assertEquals(setOf("Movie P1", "Movie P2"), movies.map { it.name }.toSet())
            assertTrue(page2VodAttempts.get() >= 2)

            val series = StalkerParser.fetchStalkerSeries(baseHost, "00:1A:79:11:22:33", StalkerDevice(), existingSession = session)
            assertEquals(setOf("Series P1", "Series P2"), series.map { it.name }.toSet())
            assertTrue(page2SeriesAttempts.get() >= 2)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun catalogRefreshesExpiredTokenOn401AndUpdatesRepository() = runBlocking {
        val handshakeCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val type = params["type"] ?: ""
            val action = params["action"] ?: ""
            val token = params["token"] ?: ""
            val page = params["p"]?.toIntOrNull() ?: 1

            val (status, body) = when {
                type == "stb" && action == "handshake" -> {
                    val count = handshakeCount.incrementAndGet()
                    val tok = if (count == 1) "TOK_OLD" else "TOK_FRESH"
                    200 to """{"js":{"token":"$tok","random":"rnd"}}"""
                }
                type == "stb" && action == "get_profile" ->
                    200 to """{"js":{"id":"1","token":"$token"}}"""
                type == "vod" && action == "get_categories" ->
                    200 to """{"js":[{"id":"10","title":"Sci-Fi"}]}"""
                type == "vod" && action == "get_ordered_list" && page == 1 ->
                    200 to """{"js":{"data":[{"id":"1","name":"SciFi P1","category_id":"10","cmd":"/media/1.mkv"}]}}"""
                type == "vod" && action == "get_ordered_list" && page == 2 -> {
                    // Reject old token with HTTP 401; accept refreshed token!
                    if (token == "TOK_OLD") {
                        401 to "Token expired"
                    } else {
                        200 to """{"js":{"data":[{"id":"2","name":"SciFi P2","category_id":"10","cmd":"/media/2.mkv"}]}}"""
                    }
                }
                else -> 200 to """{"js":{"data":[]}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val repo = PortalSessionRepository.getInstance(app)
            val plId = 888L
            val baseHost = "http://127.0.0.1:${server.address.port}"
            val mac = "00:1A:79:44:55:99"

            val initialSession = StalkerParser.performStalkerHandshake(baseHost, mac, StalkerDevice(), playlistId = plId)
            repo.setStalkerSession(plId.toString(), initialSession)
            assertEquals("TOK_OLD", initialSession["token"])

            val movies = StalkerParser.fetchStalkerVod(baseHost, mac, StalkerDevice(), existingSession = initialSession)
            assertEquals(setOf("SciFi P1", "SciFi P2"), movies.map { it.name }.toSet())
            assertEquals(2, handshakeCount.get())
            assertEquals("TOK_FRESH", repo.getStalkerSession(plId.toString())?.get("token"))
        } finally {
            server.stop(0)
        }
    }
}
