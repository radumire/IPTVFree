package com.example

import com.example.motor.db.IptvItem
import com.example.motor.parser.IptvM3uParser
import com.example.motor.parser.M3uAttributeParser
import com.example.motor.parser.MediaClassifier
import com.example.motor.parser.MediaType
import com.example.motor.parser.SeriesEpisodeParser
import com.example.motor.parser.SeriesGrouper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IptvM3uParserTest {

    @Test
    fun testM3uAttributeParser() {
        val line = """#EXTINF:-1 tvg-id="La1.es" tvg-name="La 1 HD" tvg-logo="https://logo.com/la1.png" group-title="España TDT" license_type="widevine" license_key="https://lic.com/key",La 1 HD 1080p"""
        
        val name = M3uAttributeParser.extractName(line)
        assertEquals("La 1 HD 1080p", name)

        val group = M3uAttributeParser.extractAttribute(line, "group-title")
        assertEquals("España TDT", group)

        val logo = M3uAttributeParser.extractAttribute(line, "tvg-logo")
        assertEquals("https://logo.com/la1.png", logo)

        val item = M3uAttributeParser.parseExtInfLine(line)
        assertEquals("La 1 HD 1080p", item.name)
        assertEquals("España TDT", item.group)
        assertEquals("La1.es", item.tvgId)
    }

    @Test
    fun testMediaClassifier() {
        // Live channels (including channels with 'Series' or '24/7' in name or in Series groups)
        assertEquals(MediaType.LIVE, MediaClassifier.classify("DAZN LaLiga HD", "Deportes España", "http://server.com/live/ch1.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("Movistar Series HD", "ES | MOVISTAR PLUS+", "http://server.com/live/movistar_series.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("Movistar Series 2", "MOVISTAR SERIES", "http://server.com/live/ch2.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("AXN Series HD", "Series España", "http://server.com/stream/axn.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("TNT Series 1080p", "Canales Series", "http://server.com/live/tnt.ts"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("Warner TV Series", "ES: Warner", "http://server.com/live/warner.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("Atreseries HD", "TDT España", "http://server.com/stream/atreseries.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("Movistar Estrenos 24/7", "Canales 24/7", "http://server.com/live/m_estrenos.ts"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("Abu Dhabi Sports 1 HD", "AD SPORTS", "http://server.com/stream/adsport1.m3u8"))
        assertEquals(MediaType.LIVE, MediaClassifier.classify("BBC News HD", "UK News", "http://server.com/stream.m3u8"))

        // Movies
        assertEquals(MediaType.MOVIE, MediaClassifier.classify("Avatar: The Way of Water (2022)", "Películas Acción", "http://server.com/movie/avatar2.mp4"))
        assertEquals(MediaType.MOVIE, MediaClassifier.classify("Inception (2010)", "VOD Cine", "http://server.com/vod/inception.mkv"))

        // Series
        assertEquals(MediaType.SERIES, MediaClassifier.classify("Breaking Bad S01 E01 Pilot", "Series Drama", "http://server.com/series/bb_s1e1.mp4"))
        assertEquals(MediaType.SERIES, MediaClassifier.classify("Game of Thrones 1x01 Winter is Coming", "Series HBO", "http://server.com/series/got_101.mkv"))
        assertEquals(MediaType.SERIES, MediaClassifier.classify("La Casa de Papel - Temporada 1 Capitulo 2", "Series España", "http://server.com/vod/lcdp_t1_c2.mp4"))
        assertEquals(MediaType.SERIES, MediaClassifier.classify("مسلسل الحفرة الموسم 1 الحلقة 5", "مسلسلات تركية", "http://server.com/series/cukur_s1e5.mp4"))

        // Radio
        assertEquals(MediaType.RADIO, MediaClassifier.classify("Los 40 Principales", "Radio España", "http://server.com/radio.mp3", isRadio = true))
    }

    @Test
    fun testSeriesEpisodeParser() {
        val meta1 = SeriesEpisodeParser.parseEpisode("Stranger Things S04E09 The Piggyback", "Series Netflix", "")
        assertEquals("Stranger Things", meta1.seriesName)
        assertEquals(4, meta1.seasonNumber)
        assertEquals(9, meta1.episodeNumber)

        val meta2 = SeriesEpisodeParser.parseEpisode("The Mandalorian 2x08 The Rescue", "Series Disney+", "")
        assertEquals("The Mandalorian", meta2.seriesName)
        assertEquals(2, meta2.seasonNumber)
        assertEquals(8, meta2.episodeNumber)

        val meta3 = SeriesEpisodeParser.parseEpisode("Vikingos Temporada 3 Episodio 5", "Series Históricas", "")
        assertEquals("Vikingos", meta3.seriesName)
        assertEquals(3, meta3.seasonNumber)
        assertEquals(5, meta3.episodeNumber)

        val meta4 = SeriesEpisodeParser.parseEpisode("قيامة أرطغرل الموسم 2 الحلقة 15", "مسلسلات تاريخية", "")
        assertEquals("قيامة أرطغرل", meta4.seriesName)
        assertEquals(2, meta4.seasonNumber)
        assertEquals(15, meta4.episodeNumber)
    }

    @Test
    fun testSeriesGrouper() {
        val ep1 = IptvItem(name = "Loki S01E01", group = "Marvel Series", url = "http://server/loki_s1e1.mp4")
        val ep2 = IptvItem(name = "Loki S01E02", group = "Marvel Series", url = "http://server/loki_s1e2.mp4")
        val ep3 = IptvItem(name = "Loki S02E01", group = "Marvel Series", url = "http://server/loki_s2e1.mp4")

        val grouped = SeriesGrouper.groupSeriesHierarchy(listOf(ep1, ep2, ep3))
        assertEquals(1, grouped.size)

        val loki = grouped[0]
        assertEquals("Loki", loki.seriesTitle)
        assertEquals(2, loki.seasons.size)

        val s1 = loki.seasons[0]
        assertEquals(1, s1.seasonNumber)
        assertEquals(2, s1.episodes.size)

        val s2 = loki.seasons[1]
        assertEquals(2, s2.seasonNumber)
        assertEquals(1, s2.episodes.size)
    }

    @Test
    fun testFullM3uParsingPipeline() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dazn1" group-title="Deportes Directo",DAZN 1 HD
            http://stream.server.com/live/dazn1.m3u8
            #EXTINF:-1 group-title="Películas 2024",Dune: Part Two (2024)
            http://stream.server.com/movie/dune2.mp4
            #EXTINF:-1 group-title="Series",The Boys S04E01 Department of Dirty Tricks
            http://stream.server.com/series/the_boys_s4e1.mp4
            #EXTINF:-1 group-title="Series",The Boys S04E02 Life Among the Septics
            http://stream.server.com/series/the_boys_s4e2.mp4
            #EXTINF:-1 radio="true" group-title="Radio",Cadena SER
            http://stream.server.com/radio/ser.mp3
        """.trimIndent()

        val parsed = IptvM3uParser.parseFromString(m3uContent)

        assertEquals(1, parsed.live.size)
        assertEquals("DAZN 1 HD", parsed.live[0].name)

        assertEquals(1, parsed.movies.size)
        assertEquals("Dune: Part Two (2024)", parsed.movies[0].name)

        assertEquals(2, parsed.series.size)
        assertEquals(1, parsed.radio.size)

        val seriesHierarchy = SeriesGrouper.groupSeriesHierarchy(parsed.series)
        assertEquals(1, seriesHierarchy.size)
        assertEquals("The Boys", seriesHierarchy[0].seriesTitle)
        assertEquals(1, seriesHierarchy[0].seasons.size)
        assertEquals(4, seriesHierarchy[0].seasons[0].seasonNumber)
        assertEquals(2, seriesHierarchy[0].seasons[0].episodes.size)
    }
}
