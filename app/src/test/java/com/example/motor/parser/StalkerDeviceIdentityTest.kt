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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StalkerDeviceIdentityTest {

    private val sha256HexRegex = Regex("^[0-9A-F]{64}$")

    @Test
    fun serialNumberStripsColonsAndHyphensCleanly() {
        assertEquals("001A79112233", StalkerParser.resolveSerialNumber("00:1A:79:11:22:33"))
        assertEquals("001A79AABBCC", StalkerParser.resolveSerialNumber("00-1a-79-aa-bb-cc"))
        assertEquals("CUSTOMSN9988", StalkerParser.resolveSerialNumber("00:1A:79:11:22:33", "custom:sn-9988"))
    }

    @Test
    fun deviceIdAndSignaturesAreDeterministicSha256WithoutTimestampDrift() {
        val mac = "00:1A:79:44:55:66"
        val sn = StalkerParser.resolveSerialNumber(mac)
        val model = "MAG254"

        val id1 = StalkerParser.generateDeviceId(mac, sn, model)
        val id2 = StalkerParser.generateDeviceId(mac, sn, model)
        assertEquals(id1, id2)
        assertTrue(sha256HexRegex.matches(id1.primary))
        assertEquals("001A79445566", id1.secondary)

        val devId2a = StalkerParser.generateDeviceId2(id1.primary, mac)
        val devId2b = StalkerParser.generateDeviceId2(id2.primary, mac)
        assertEquals(devId2a, devId2b)
        assertTrue(sha256HexRegex.matches(devId2a))

        val sigA = StalkerParser.generateSignature(mac, sn, model, "MAG254", "5.0.10", id1.primary, "218", "Infomir")
        val sigB = StalkerParser.generateSignature(mac, sn, model, "MAG254", "5.0.10", id1.primary, "218", "Infomir")
        assertEquals(sigA, sigB)
        assertTrue(sha256HexRegex.matches(sigA))

        val apiSigA = StalkerParser.generateApiSignature("tok123", "rnd999", mac)
        val apiSigB = StalkerParser.generateApiSignature("tok123", "rnd999", mac)
        assertEquals(apiSigA, apiSigB)
        assertTrue(sha256HexRegex.matches(apiSigA))

        // Different MAC produces distinct deterministic identity
        val otherId = StalkerParser.generateDeviceId("00:1A:79:44:55:67", StalkerParser.resolveSerialNumber("00:1A:79:44:55:67"), model)
        assertNotEquals(id1.primary, otherId.primary)
    }

    @Test
    fun handshakeAndCatalogReuseNegotiatedDeviceAndConstantIdentityOverHttp() = runBlocking {
        data class RecordedCall(
            val action: String,
            val type: String,
            val userAgent: String,
            val deviceId: String,
            val deviceId2: String,
            val signature: String,
            val sn: String
        )

        val recordedCalls = CopyOnWriteArrayList<RecordedCall>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal.php") { exchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            val params = query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }.toMap()

            val action = params["action"] ?: ""
            val type = params["type"] ?: ""
            val ua = exchange.requestHeaders.getFirst("User-Agent") ?: ""
            recordedCalls.add(
                RecordedCall(
                    action = action,
                    type = type,
                    userAgent = ua,
                    deviceId = params["device_id"] ?: "",
                    deviceId2 = params["device_id2"] ?: "",
                    signature = params["signature"] ?: "",
                    sn = params["sn"] ?: ""
                )
            )

            // Reject LEGACY_HYBRID (MAG200), accept ONLY MAG254_COHERENT (MAG254 stbapp ver: 4)
            val isMag254Coherent = ua.contains("MAG254 stbapp ver: 4")
            val responseJson = if (!isMag254Coherent) {
                """{"js":[]}"""
            } else {
                when {
                    type == "stb" && action == "handshake" ->
                        """{"js":{"token":"TOK_MAG254_OK","random":"rnd123"}}"""
                    type == "stb" && action == "get_profile" ->
                        """{"js":{"id":"1","token":"TOK_MAG254_OK"}}"""
                    type == "itv" && action == "get_genres" ->
                        """{"js":[{"id":"10","title":"General"}]}"""
                    type == "itv" && action == "get_all_channels" ->
                        """{"js":{"data":[{"id":"1","name":"Channel One","tv_genre_id":"10","cmd":"ffmpeg http://127.0.0.1/live/1.ts"}]}}"""
                    type == "vod" && action == "get_categories" ->
                        """{"js":[{"id":"20","title":"Acción"}]}"""
                    type == "vod" && action == "get_ordered_list" && (params["p"] == "1") ->
                        """{"js":{"data":[{"id":"100","name":"Test Movie","category_id":"20","cmd":"/media/100.mkv"}]}}"""
                    type == "vod" && action == "get_ordered_list" ->
                        """{"js":{"data":[]}}"""
                    type == "series" && action == "get_categories" ->
                        """{"js":[{"id":"30","title":"Drama"}]}"""
                    type == "series" && action == "get_ordered_list" && (params["p"] == "1") ->
                        """{"js":{"data":[{"id":"200","name":"Test Series","category_id":"30","cmd":"series_200"}]}}"""
                    type == "series" && action == "get_ordered_list" ->
                        """{"js":{"data":[]}}"""
                    action == "create_link" ->
                        """{"js":{"cmd":"ffmpeg http://stream.test.local/play.ts"}}"""
                    type == "watchdog" && action == "get_events" ->
                        """{"js":{"msgs":0}}"""
                    else -> """{"js":[]}"""
                }
            }

            val bytes = responseJson.toByteArray(Charsets.UTF_8)
            val statusCode = if (isMag254Coherent) 200 else 200
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val port = server.address.port
            val baseHost = "http://127.0.0.1:$port"
            val mac = "00:1A:79:AB:CD:EF"
            val defaultLegacyDevice = StalkerDevice() // LEGACY_HYBRID by default

            val session = StalkerParser.performStalkerHandshake(
                host = baseHost,
                mac = mac,
                device = defaultLegacyDevice,
                playlistId = 999L
            )
            val negotiatedDevice = session["device"] as StalkerDevice
            assertEquals("MAG254", negotiatedDevice.hwVersion)
            assertTrue(negotiatedDevice.userAgent.contains("MAG254 stbapp ver: 4"))

            // Pass defaultLegacyDevice intentionally; fetchStalker* MUST reuse session["device"]
            val tv = StalkerParser.fetchStalkerTv(baseHost, mac, defaultLegacyDevice, existingSession = session)
            val vod = StalkerParser.fetchStalkerVod(baseHost, mac, defaultLegacyDevice, existingSession = session)
            val series = StalkerParser.fetchStalkerSeries(baseHost, mac, defaultLegacyDevice, existingSession = session)
            val link = StalkerParser.resolveStalkerPlayLink(session, "/media/100.mkv", "vod")
            val watchdogOk = StalkerParser.sendStalkerWatchdogPing(session)

            assertEquals(1, tv.size)
            assertEquals(1, vod.size)
            assertEquals(1, series.size)
            assertEquals("http://stream.test.local/play.ts", link)
            assertTrue(watchdogOk)

            val acceptedCalls = recordedCalls.filter { it.userAgent.contains("MAG254 stbapp ver: 4") }
            assertTrue(acceptedCalls.size >= 8)

            // Every accepted call across handshake, profile, TV, VOD, series, create_link, and watchdog
            // must carry the exact same sn, device_id, device_id2, and signature without colons in sn.
            val first = acceptedCalls.first()
            assertEquals("001A79ABCDEF", first.sn)
            acceptedCalls.forEach { call ->
                assertEquals("sn mismatch on ${call.type}/${call.action}", first.sn, call.sn)
                assertEquals("device_id mismatch on ${call.type}/${call.action}", first.deviceId, call.deviceId)
                assertEquals("device_id2 mismatch on ${call.type}/${call.action}", first.deviceId2, call.deviceId2)
                assertEquals("signature mismatch on ${call.type}/${call.action}", first.signature, call.signature)
            }

            // Verify StalkerSource.buildHeaders prioritizes negotiated session device over default LEGACY_HYBRID JSON
            val app = ApplicationProvider.getApplicationContext<Application>()
            val repo = PortalSessionRepository.getInstance(app)
            val playlist = Playlist(
                id = 999L,
                type = "stalker",
                name = "Test Portal",
                host = baseHost,
                mac = mac,
                stalkerDeviceJson = JsonUtils.stalkerDeviceToJson(StalkerDevice())
            )
            val source = StalkerSource(repo)
            val headers = source.buildHeaders(playlist, session)
            assertTrue(headers["User-Agent"]!!.contains("MAG254 stbapp ver: 4"))
        } finally {
            server.stop(0)
        }
    }
}
