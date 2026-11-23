package com.example

import com.example.motor.parser.StalkerParser
import com.example.motor.parser.StalkerParser.StalkerEpisodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerSeriesPlaybackTest {

    @Test
    fun testEncodeAndParseStalkerEpisodeDescriptor() {
        val info = StalkerEpisodeInfo(
            seriesId = "987",
            seasonId = "55",
            episodeId = "1044",
            videoId = "2088",
            seriesNumber = "1",
            seasonNum = "2",
            episodeNum = "3",
            cmd = "ffmpeg /media/series/987_s02_e03.mp4",
            file = "/media/series/987_s02_e03.mp4",
            fileId = "5544",
            url = "",
            isRealEpisode = true
        )

        val encoded = StalkerParser.encodeStalkerEpisodeDescriptor(info)
        assertTrue("Encoded descriptor must start with stalker_ep://", encoded.startsWith("stalker_ep://"))

        val parsed = StalkerParser.parseStalkerEpisodeDescriptor(encoded)
        assertNotNull("Parsed descriptor must not be null", parsed)
        assertEquals("987", parsed?.seriesId)
        assertEquals("55", parsed?.seasonId)
        assertEquals("1044", parsed?.episodeId)
        assertEquals("2088", parsed?.videoId)
        assertEquals("2", parsed?.seasonNum)
        assertEquals("3", parsed?.episodeNum)
        assertEquals("ffmpeg /media/series/987_s02_e03.mp4", parsed?.cmd)
        assertEquals("/media/series/987_s02_e03.mp4", parsed?.file)
        assertEquals("5544", parsed?.fileId)
        assertTrue(parsed?.isRealEpisode == true)
    }

    @Test
    fun testNormalizeResultUrlWithPortalBase() {
        val portalUrl = "http://stalker.provider.net:8080/c/index.html"

        val directHttp = StalkerParser.normalizeResultUrl("http://cdn.stream.com:8080/live/play.m3u8", portalUrl)
        assertEquals("http://cdn.stream.com:8080/live/play.m3u8", directHttp)

        val localhostRemap = StalkerParser.normalizeResultUrl("ffmpeg http://localhost/ch/504_", portalUrl)
        assertEquals("http://stalker.provider.net:8080/ch/504_", localhostRemap)

        val relativeMediaPath = StalkerParser.normalizeResultUrl("/media/vod/sample.mkv", portalUrl)
        assertEquals("http://stalker.provider.net:8080/media/vod/sample.mkv", relativeMediaPath)
    }

    @Test
    fun testDirectUrlExtraction() {
        val infoWithDirectUrl = StalkerEpisodeInfo(
            seriesId = "10",
            seasonNum = "1",
            episodeNum = "1",
            url = "http://direct.server.net/series/10_1.mp4",
            isRealEpisode = true
        )

        val encoded = StalkerParser.encodeStalkerEpisodeDescriptor(infoWithDirectUrl)
        val parsed = StalkerParser.parseStalkerEpisodeDescriptor(encoded)
        assertEquals("http://direct.server.net/series/10_1.mp4", parsed?.url)
    }
}
