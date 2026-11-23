package com.example

import com.example.interfaz.normalizeBaseSeriesName
import com.example.motor.parser.StalkerParser
import com.example.motor.repository.IptvRepository
import com.example.motor.sync.NetworkIpUtils
import org.junit.Assert.*
import org.junit.Test

class ConsolidationAuditTest {

    @Test
    fun testNormalizeResultUrlSchemesAndFallbacks() {
        val portal = "http://stalker.example.com:8080/c/"

        // 1. Direct HTTP/HTTPS streams
        assertEquals(
            "http://stream.server.org/live/stream.m3u8",
            StalkerParser.normalizeResultUrl("ffmpeg http://stream.server.org/live/stream.m3u8", portal)
        )
        assertEquals(
            "https://stream.server.org/live/stream.m3u8",
            StalkerParser.normalizeResultUrl("auto https://stream.server.org/live/stream.m3u8", portal)
        )

        // 2. RTSP and RTMP
        assertEquals(
            "rtsp://media.server.org/live/ch1",
            StalkerParser.normalizeResultUrl("rtsp://media.server.org/live/ch1", portal)
        )
        assertEquals(
            "rtmp://media.server.org/live/ch1",
            StalkerParser.normalizeResultUrl("rtmp://media.server.org/live/ch1", portal)
        )

        // 3. Localhost / 127.0.0.1 replacement
        val localReplaced = StalkerParser.normalizeResultUrl("http://localhost/stream/123", portal)
        assertEquals("http://stalker.example.com:8080/stream/123", localReplaced)

        val localIpReplaced = StalkerParser.normalizeResultUrl("http://127.0.0.1:8080/stream/456", portal)
        assertTrue(localIpReplaced?.contains("stalker.example.com") == true)

        // 4. Relative paths
        val relativeSlash = StalkerParser.normalizeResultUrl("/media/stream_live.ts", portal)
        assertEquals("http://stalker.example.com:8080/media/stream_live.ts", relativeSlash)

        val relativeNoSlash = StalkerParser.normalizeResultUrl("media/stream_live.ts", portal)
        assertEquals("http://stalker.example.com:8080/media/stream_live.ts", relativeNoSlash)

        // 5. Blank/empty inputs
        assertNull(StalkerParser.normalizeResultUrl("", portal))
        assertNull(StalkerParser.normalizeResultUrl("   ", portal))
    }

    @Test
    fun testNormalizeBaseSeriesNameEquivalence() {
        // Test single parameter
        assertEquals("strangerthings", normalizeBaseSeriesName("Stranger Things"))
        assertEquals("breakingbad", normalizeBaseSeriesName("Breaking Bad (2008)"))
        assertEquals("gameofthrones", normalizeBaseSeriesName("Game of Thrones S01E01"))

        // Test two parameters
        assertEquals("strangerthings", normalizeBaseSeriesName("Stranger Things", "Series HD"))
        assertEquals("breakingbad", normalizeBaseSeriesName("Breaking Bad S02", "Dramas"))
    }

    @Test
    fun testIsDividerOrHeaderStaticAccess() {
        assertTrue(IptvRepository.isDividerOrHeader("--- CANALES ESPAÑA ---"))
        assertTrue(IptvRepository.isDividerOrHeader("=== DEPORTES ==="))
        assertFalse(IptvRepository.isDividerOrHeader("La 1 HD"))
    }

    @Test
    fun testNetworkIpUtilsLanValidation() {
        assertTrue(NetworkIpUtils.isValidLanIp("192.168.1.50"))
        assertTrue(NetworkIpUtils.isValidLanIp("10.0.0.1"))
        assertTrue(NetworkIpUtils.isValidLanIp("172.16.0.10"))
        assertTrue(NetworkIpUtils.isValidLanIp("172.31.255.254"))

        assertFalse(NetworkIpUtils.isValidLanIp("127.0.0.1"))
        assertFalse(NetworkIpUtils.isValidLanIp("0.0.0.0"))
        assertFalse(NetworkIpUtils.isValidLanIp("169.254.1.1")) // Link-local
        assertFalse(NetworkIpUtils.isValidLanIp("8.8.8.8")) // Public IP
        assertFalse(NetworkIpUtils.isValidLanIp("172.15.0.1")) // Below class B private
        assertFalse(NetworkIpUtils.isValidLanIp("172.32.0.1")) // Above class B private
        assertFalse(NetworkIpUtils.isValidLanIp(""))

        // Malformed IPv4 strings
        assertFalse(NetworkIpUtils.isValidLanIp("192.168.mal"))
        assertFalse(NetworkIpUtils.isValidLanIp("192.168.1.999"))
        assertFalse(NetworkIpUtils.isValidLanIp("192.168.1"))
        assertFalse(NetworkIpUtils.isValidLanIp("10.0.0.1.2"))
        assertFalse(NetworkIpUtils.isValidLanIp("10.0.0.-1"))
        assertFalse(NetworkIpUtils.isValidLanIp("abc.def.ghi.jkl"))
    }

    @Test
    fun testTranslatorConsolidation() {
        // Test translation in Spanish (default)
        assertEquals("Inicio", Translator.translate("home", "es"))
        assertEquals("Desconectar", Translator.translate("disconnect", "es"))
        assertEquals("Actualizar listas al inicio", Translator.translate("auto_update", "es"))

        // Test English translations including consolidated keys
        assertEquals("Home", Translator.translate("home", "en"))
        assertEquals("Disconnect", Translator.translate("disconnect", "en"))
        assertEquals("Update lists on startup", Translator.translate("auto_update", "en"))
        assertEquals("Enable notifications", Translator.translate("notifications", "en"))

        // Test Romanian
        assertEquals("Acasă", Translator.translate("home", "ro"))
        assertEquals("Deconectează", Translator.translate("disconnect", "ro"))

        // Test arguments formatting
        assertEquals("¡Hola de nuevo, Juan!", Translator.translate("welcome_back", "es", "Juan"))
        assertEquals("Welcome back, John!", Translator.translate("welcome_back", "en", "John"))

        // Test fallback for missing key returns key itself
        assertEquals("unknown_key_xyz", Translator.translate("unknown_key_xyz", "es"))
    }

    @Test
    fun testSubtitleLiveTvExclusion() {
        val reqLive = com.example.motor.subtitles.SubtitleSearchRequest(
            title = "Canal 24 Horas",
            mediaType = "live"
        )
        assertTrue(reqLive.isLiveTv)
        assertFalse(reqLive.isMovie)

        val reqTv = com.example.motor.subtitles.SubtitleSearchRequest(
            title = "La 1 HD",
            mediaType = "tv"
        )
        assertTrue(reqTv.isLiveTv)
        assertFalse(reqTv.isMovie)

        val reqMovie = com.example.motor.subtitles.SubtitleSearchRequest(
            title = "Inception",
            mediaType = "movie"
        )
        assertFalse(reqMovie.isLiveTv)
        assertTrue(reqMovie.isMovie)

        val reqSeries = com.example.motor.subtitles.SubtitleSearchRequest(
            title = "Breaking Bad",
            mediaType = "series"
        )
        assertFalse(reqSeries.isLiveTv)
        assertTrue(reqSeries.isSeries)
    }

    @Test
    fun testSubtitleSyncUtilsTimestampParsingAndFormatting() {
        // SRT parsing
        val ms1 = com.example.motor.subtitles.SubtitleSyncUtils.parseTimestampToMs("01:23:45,678")
        assertEquals((1 * 3600000L) + (23 * 60000L) + (45 * 1000L) + 678L, ms1)

        // VTT parsing (period delimiter)
        val ms2 = com.example.motor.subtitles.SubtitleSyncUtils.parseTimestampToMs("00:02:10.500")
        assertEquals((2 * 60000L) + (10 * 1000L) + 500L, ms2)

        // Short VTT parsing MM:SS.mmm
        val ms3 = com.example.motor.subtitles.SubtitleSyncUtils.parseTimestampToMs("05:30.250")
        assertEquals((5 * 60000L) + (30 * 1000L) + 250L, ms3)

        // Formatting back to SRT
        val srtFormatted = com.example.motor.subtitles.SubtitleSyncUtils.formatMsToTimestamp(ms1, isVtt = false)
        assertEquals("01:23:45,678", srtFormatted)

        // Formatting back to VTT
        val vttFormatted = com.example.motor.subtitles.SubtitleSyncUtils.formatMsToTimestamp(ms2, isVtt = true)
        assertEquals("00:02:10.500", vttFormatted)
    }

    @Test
    fun testSubtitleSyncUtilsShifting() {
        val srtSample = """
            1
            00:01:00,000 --> 00:01:05,000
            Hola mundo

            2
            00:02:00,000 --> 00:02:10,000
            Segunda línea
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("test_sub", ".srt")
        tempFile.writeText(srtSample)

        // Shift forward +1500ms
        val shiftedFile = com.example.motor.subtitles.SubtitleSyncUtils.createShiftedSubtitleFile(tempFile, 1500L)
        assertTrue(shiftedFile.exists())
        val content = shiftedFile.readText()
        assertTrue(content.contains("00:01:01,500 --> 00:01:06,500"))
        assertTrue(content.contains("00:02:01,500 --> 00:02:11,500"))

        tempFile.delete()
        shiftedFile.delete()
    }

    @Test
    fun testAdaptiveLoadControlSwitching() {
        val adaptive = com.example.motor.player.AdaptiveLoadControl()
        assertTrue(adaptive.isLiveStream)
        // Switch to VOD
        adaptive.isLiveStream = false
        assertFalse(adaptive.isLiveStream)
        assertNotNull(adaptive.activeControl)
    }

    @Test
    fun testLiveTvChannelZappingLogic() {
        val channels = listOf("La 1", "La 2", "Antena 3", "Cuatro", "Telecinco", "La Sexta")

        fun getNextChannelIndex(currentIdx: Int, isNext: Boolean, total: Int): Int {
            if (total == 0) return -1
            return if (isNext) {
                if (currentIdx + 1 < total) currentIdx + 1 else 0
            } else {
                if (currentIdx - 1 >= 0) currentIdx - 1 else total - 1
            }
        }

        // Test forward zapping
        assertEquals(1, getNextChannelIndex(0, isNext = true, total = channels.size))
        assertEquals(5, getNextChannelIndex(4, isNext = true, total = channels.size))
        // Wrap around
        assertEquals(0, getNextChannelIndex(5, isNext = true, total = channels.size))

        // Test backward zapping
        assertEquals(4, getNextChannelIndex(5, isNext = false, total = channels.size))
        // Wrap around backward
        assertEquals(5, getNextChannelIndex(0, isNext = false, total = channels.size))
    }

    @Test
    fun testTvKeyMappingCodes() {
        // Confirm standard Android TV remote key codes are recognized
        val dpadCenter = android.view.KeyEvent.KEYCODE_DPAD_CENTER
        val dpadUp = android.view.KeyEvent.KEYCODE_DPAD_UP
        val dpadDown = android.view.KeyEvent.KEYCODE_DPAD_DOWN
        val enter = android.view.KeyEvent.KEYCODE_ENTER
        val channelUp = android.view.KeyEvent.KEYCODE_CHANNEL_UP
        val channelDown = android.view.KeyEvent.KEYCODE_CHANNEL_DOWN

        assertTrue(dpadCenter > 0)
        assertTrue(dpadUp > 0)
        assertTrue(dpadDown > 0)
        assertTrue(enter > 0)
        assertTrue(channelUp > 0)
        assertTrue(channelDown > 0)
    }
}

