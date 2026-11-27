package com.example.motor.parser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.motor.config.JsonUtils
import com.example.motor.config.Playlist
import com.example.motor.config.StalkerDevice
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.source.StalkerSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StalkerHandshakeDiscoveryTest {

    @Before
    fun setUp() {
        StalkerParser.clearPortalCookiesForTests()
    }

    @Test
    fun urlCandidatesNormalizePathsPortsAndSchemesCleanly() {
        val slashC = StalkerParser.buildStalkerPortalCandidates("http://portal.test.net:8080/c/")
        assertEquals(
            listOf(
                "http://portal.test.net:8080/portal.php",
                "http://portal.test.net:8080/stalker_portal/server/load.php",
                "http://portal.test.net:8080/server/load.php",
                "http://portal.test.net:8080/c/portal.php"
            ),
            slashC
        )
        assertFalse(slashC.any { it.contains("/c/stalker_portal") || it.contains("/c/index.html") })

        val stalkerSub = StalkerParser.buildStalkerPortalCandidates("http://portal.test.net/stalker_portal/c/index.html")
        assertEquals("http://portal.test.net/stalker_portal/server/load.php", stalkerSub.first())

        val https80 = StalkerParser.buildStalkerPortalCandidates("https://portal.test.net:80/c/")
        assertTrue(https80.all { it.startsWith("http://portal.test.net/") && !it.contains(":80") })

        val explicitPhp = StalkerParser.buildStalkerPortalCandidates("http://portal.test.net/custom/portal.php")
        assertEquals("http://portal.test.net/custom/portal.php", explicitPhp.first())

        assertEquals(
            "http://portal.test.net:8080/c/",
            StalkerParser.derivePortalReferer("http://portal.test.net:8080/portal.php")
        )
        assertEquals(
            "http://portal.test.net/stalker_portal/c/",
            StalkerParser.derivePortalReferer("http://portal.test.net/stalker_portal/server/load.php")
        )
    }

    @Test
    fun handshakeSkipsDead404EndpointsAfterFirstCheckAndForwardsRefererAndCookies() = runBlocking {
        val portalPhpCalls = AtomicInteger(0)
        val warmupCalls = AtomicInteger(0)
        val recordedReferers = CopyOnWriteArrayList<String>()
        val recordedCookies = CopyOnWriteArrayList<String>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            portalPhpCalls.incrementAndGet()
            val body = "Not Found".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/stalker_portal/c/") { exchange ->
            warmupCalls.incrementAndGet()
            exchange.responseHeaders.add("Set-Cookie", "PHPSESSID=sess_xyz_123; Path=/; HttpOnly")
            val body = "<html><body>Portal</body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/stalker_portal/server/load.php") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie") ?: ""
            val referer = exchange.requestHeaders.getFirst("Referer") ?: ""
            val ua = exchange.requestHeaders.getFirst("User-Agent") ?: ""
            recordedCookies.add(cookie)
            recordedReferers.add(referer)

            val query = exchange.requestURI.rawQuery ?: ""
            val params = query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }.toMap()

            val action = params["action"] ?: ""
            val type = params["type"] ?: ""

            // Require PHPSESSID cookie obtained via /stalker_portal/c/ warm-up
            if (!cookie.contains("PHPSESSID=sess_xyz_123")) {
                val err = "<html>Forbidden</html>".toByteArray()
                exchange.sendResponseHeaders(403, err.size.toLong())
                exchange.responseBody.use { it.write(err) }
                return@createContext
            }

            // Require LEGACY_HYBRID (MAG200) to force falling back across models after MAG254_COHERENT
            val responseJson = if (!ua.contains("MAG200 stbapp")) {
                """{"js":[]}"""
            } else {
                when {
                    type == "stb" && action == "handshake" ->
                        """{"js":{"token":"TOK_COOKIE_OK","random":"r42"}}"""
                    type == "stb" && action == "get_profile" ->
                        """{"js":{"id":"7","token":"TOK_COOKIE_OK"}}"""
                    type == "itv" && action == "get_genres" ->
                        """{"js":[{"id":"1","title":"News"}]}"""
                    type == "itv" && action == "get_all_channels" ->
                        """{"js":{"data":[{"id":"10","name":"Ch News","tv_genre_id":"1","cmd":"http://127.0.0.1/live/10.ts"}]}}"""
                    else -> """{"js":[]}"""
                }
            }
            val bytes = responseJson.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            // Deliberately pass root host (no /stalker_portal hint) so /portal.php is tried first and returns 404
            val session = StalkerParser.performStalkerHandshake(
                host = "http://127.0.0.1:$port/c/",
                mac = "00:1A:79:12:34:56",
                device = StalkerDevice()
            )

            assertEquals("http://127.0.0.1:$port/stalker_portal/server/load.php", session["portalUrl"])
            assertEquals("TOK_COOKIE_OK", session["token"])
            // 404 on /portal.php must abort create_token & skip /portal.php on subsequent models => hit only ONCE
            assertEquals(1, portalPhpCalls.get())
            assertEquals(1, warmupCalls.get())

            assertTrue(recordedReferers.all { it == "http://127.0.0.1:$port/stalker_portal/c/" })

            // Verify TV fetch continues sending Referer and captured PHPSESSID
            val tv = StalkerParser.fetchStalkerTv(
                host = "http://127.0.0.1:$port/c/",
                mac = "00:1A:79:12:34:56",
                device = StalkerDevice(),
                existingSession = session
            )
            assertEquals(1, tv.size)
            assertTrue(recordedCookies.last().contains("PHPSESSID=sess_xyz_123"))

            // Verify StalkerSource.buildHeaders includes both normalized Referer and captured PHPSESSID
            val app = ApplicationProvider.getApplicationContext<Application>()
            val source = StalkerSource(PortalSessionRepository.getInstance(app))
            val pl = Playlist(
                id = 777L,
                type = "stalker",
                name = "CookiePortal",
                host = "http://127.0.0.1:$port/c/",
                mac = "00:1A:79:12:34:56",
                stalkerDeviceJson = JsonUtils.stalkerDeviceToJson(StalkerDevice())
            )
            val playbackHeaders = source.buildHeaders(pl, session)
            assertEquals("http://127.0.0.1:$port/stalker_portal/c/", playbackHeaders["Referer"])
            assertTrue(playbackHeaders["Cookie"]!!.contains("PHPSESSID=sess_xyz_123"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun handshakeAbortsImmediatelyOnBlockedMacInPhase1() = runBlocking {
        val totalCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            totalCalls.incrementAndGet()
            val bytes = """{"js":{"block_msg":"MAC expirada o bloqueada"}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            try {
                StalkerParser.performStalkerHandshake(
                    host = "http://127.0.0.1:$port",
                    mac = "00:1A:79:99:88:77",
                    device = StalkerDevice()
                )
                fail("Expected PortalBlockedMac to be thrown")
            } catch (e: NetworkError.PortalBlockedMac) {
                assertTrue(e.message!!.contains("MAC expirada o bloqueada"))
                // Must abort immediately on the very first call instead of looping models/endpoints
                assertEquals(1, totalCalls.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun handshakeSupportsPrehashAndSecondStepProfileAuth() = runBlocking {
        val seenSecondStep = AtomicInteger(0)
        val seenNonZeroPrehash = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            val params = query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }.toMap()

            val type = params["type"] ?: ""
            val action = params["action"] ?: ""
            val prehash = params["prehash"] ?: "0"
            val authSecondStep = params["auth_second_step"] ?: "0"

            val responseJson = when {
                type == "stb" && action == "handshake" && prehash == "0" ->
                    // Challenge first without token
                    """{"js":{"random":"challenge_abc"}}"""
                type == "stb" && action == "handshake" && prehash != "0" -> {
                    seenNonZeroPrehash.incrementAndGet()
                    """{"js":{"token":"TOK_STEP1","random":"challenge_abc"}}"""
                }
                type == "stb" && action == "get_profile" && authSecondStep == "0" ->
                    """{"js":{"auth_second_step":1}}"""
                type == "stb" && action == "get_profile" && authSecondStep == "1" -> {
                    seenSecondStep.incrementAndGet()
                    """{"js":{"id":"5","token":"TOK_FINAL_STEP2"}}"""
                }
                else -> """{"js":[]}"""
            }
            val bytes = responseJson.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            val session = StalkerParser.performStalkerHandshake(
                host = "http://127.0.0.1:$port",
                mac = "00:1A:79:55:44:33",
                device = StalkerDevice()
            )

            assertEquals(1, seenNonZeroPrehash.get())
            assertEquals(1, seenSecondStep.get())
            assertEquals("TOK_FINAL_STEP2", session["token"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun handshakeThrowsAuthErrorWhenAllProfileVariantsReturn403() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            val params = query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }.toMap()

            val action = params["action"] ?: ""
            if (action == "handshake") {
                val bytes = """{"js":{"token":"TOK_TEMP"}}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val bytes = """{"js":false,"error":"Access denied"}""".toByteArray()
                exchange.sendResponseHeaders(403, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()

        try {
            val port = server.address.port
            try {
                StalkerParser.performStalkerHandshake(
                    host = "http://127.0.0.1:$port",
                    mac = "00:1A:79:11:00:22",
                    device = StalkerDevice()
                )
                fail("Expected NetworkError.Auth when all get_profile variants return 403")
            } catch (_: NetworkError.Auth) {
                // Expected
            }
        } finally {
            server.stop(0)
        }
    }
}
