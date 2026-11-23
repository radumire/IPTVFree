package com.example

import com.example.cast.CastStreamProxyServer
import com.example.cast.TvCastManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastProxyStreamResolutionTest {

    @Test
    fun testLiveChannelUrlResolutionForChromecast() {
        val manager = TvCastManager.getInstance()
        
        // Xtream live with .ts should be converted to .m3u8 for Chromecast
        val tsUrl = "http://fortv.cc:8080/live/user/pass/41836.ts"
        val chromecastUrl = manager.resolveDirectStreamUrl(tsUrl, isLive = true, isChromecast = true)
        assertEquals("http://fortv.cc:8080/live/user/pass/41836.m3u8", chromecastUrl)

        // For DLNA, it should preserve .ts
        val dlnaUrl = manager.resolveDirectStreamUrl(tsUrl, isLive = true, isChromecast = false)
        assertEquals(tsUrl, dlnaUrl)
    }

    @Test
    fun testMovieAndSeriesUrlPreservedAsVod() {
        val manager = TvCastManager.getInstance()

        // Movies (.mp4) should NEVER be converted to .m3u8 even on port :8080
        val movieUrl = "http://fortv.cc:8080/movie/eyes4u2796@aol.com/eyes4u2796@2023/30913.mp4"
        val resolvedMovie = manager.resolveDirectStreamUrl(movieUrl, isLive = false, isChromecast = true)
        assertEquals(movieUrl, resolvedMovie)

        // Series (.mkv) should NEVER be converted to .m3u8 even on port :8080
        val seriesUrl = "http://fortv.cc:8080/series/eyes4u2796@aol.com/eyes4u2796@2023/190320.mkv"
        val resolvedSeries = manager.resolveDirectStreamUrl(seriesUrl, isLive = false, isChromecast = true)
        assertEquals(seriesUrl, resolvedSeries)
    }

    @Test
    fun testRewriteM3u8PlaylistInjectsLocalProxy() {
        val sampleM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:10.0,
            /hlsr/token123/user/pass/41836/41836_100.ts
            #EXTINF:10.0,
            http://lb59.1xtr.icu:25461/hlsr/token123/user/pass/41836/41836_101.ts
        """.trimIndent()

        val baseUrl = "http://lb59.1xtr.icu:25461/live/user/pass/41836.m3u8?token=xyz"
        val rewritten = CastStreamProxyServer.rewriteM3u8Playlist(
            m3u8Text = sampleM3u8,
            baseUrl = baseUrl,
            localIp = "192.168.1.150",
            port = 8998
        )

        assertTrue(rewritten.contains("#EXTM3U"))
        assertTrue(rewritten.contains("http://192.168.1.150:8998/chunk?u="))
        // Both relative and absolute segment URLs should now route via local proxy
        val lines = rewritten.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("http://192.168.1.150:8998/chunk?u=http%3A%2F%2Flb59.1xtr.icu%3A25461%2Fhlsr%2F"))
        assertTrue(lines[1].startsWith("http://192.168.1.150:8998/chunk?u=http%3A%2F%2Flb59.1xtr.icu%3A25461%2Fhlsr%2F"))
    }
}
