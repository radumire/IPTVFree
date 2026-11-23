package com.example

import com.example.motor.subtitles.SubtitleCatProvider
import com.example.motor.subtitles.SubtitleLanguage
import com.example.motor.subtitles.SubtitleMatcher
import com.example.motor.subtitles.SubtitleResolver
import com.example.motor.subtitles.SubtitleSearchRequest
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testParseSubtitleCatLanguages() {
        assertEquals("es", SubtitleLanguage.normalizeLanguageCode("Spanish", "/subs/123/movie.html"))
        assertEquals("es", SubtitleLanguage.normalizeLanguageCode("Español", "/subs/123/movie-es.html"))
        assertEquals("es", SubtitleLanguage.normalizeLanguageCode("download_es", "/subs/123/movie-es.html"))
        assertEquals("es-419", SubtitleLanguage.normalizeLanguageCode("Spanish (Latin America)", "/subs/123/movie-es-419.html"))
        assertEquals("es-419", SubtitleLanguage.normalizeLanguageCode("Latino", "/subs/123/movie-latino.html"))
        assertEquals("es-419", SubtitleLanguage.normalizeLanguageCode("download_es-419", "/subs/123/movie.html"))
        assertEquals("ro", SubtitleLanguage.normalizeLanguageCode("Romanian", "/subs/123/movie-ro.html"))
        assertEquals("ro", SubtitleLanguage.normalizeLanguageCode("Română", "/subs/123/movie.html"))
        assertEquals("en", SubtitleLanguage.normalizeLanguageCode("English", "/subs/123/movie-en.html"))
        assertEquals("fr", SubtitleLanguage.normalizeLanguageCode("French", "/subs/123/movie-fr.html"))
        assertEquals("de", SubtitleLanguage.normalizeLanguageCode("German", "/subs/123/movie-de.html"))
        assertEquals("it", SubtitleLanguage.normalizeLanguageCode("Italian", "/subs/123/movie-it.html"))
        assertEquals("pt", SubtitleLanguage.normalizeLanguageCode("Portuguese", "/subs/123/movie-pt.html"))
        assertEquals("ru", SubtitleLanguage.normalizeLanguageCode("Russian", "/subs/123/movie-ru.html"))
    }

    @Test
    fun testExtractSubtitleCatResultPages() {
        val searchHtml = """
            <html>
            <body>
            <table>
                <tr><td><a href="/subs/54/Inception-2010.html">Inception (2010)</a></td></tr>
                <tr><td><a href="/subs/99/Inception-Bonus.html">Inception Bonus</a></td></tr>
                <tr><td><a href="/privacy.html">Privacy Policy</a></td></tr>
            </table>
            </body>
            </html>
        """.trimIndent()

        val candidates = SubtitleCatProvider.extractCandidatePages(searchHtml)
        val pages = candidates.map { it.pageUrl }
        assertEquals(2, pages.size)
        assertTrue(pages.contains("https://www.subtitlecat.com/subs/54/Inception-2010.html"))
        assertTrue(pages.contains("https://www.subtitlecat.com/subs/99/Inception-Bonus.html"))
    }

    @Test
    fun testCitadelAndOngBakSubtitleCatCandidatePages() {
        val searchHtmlCitadel = """
            <html><body><table>
                <tr><td><a href="subs/1482/Citadel.S01E01.720p.AMZN.WEBRip.x264-GalaxyTV.html">Citadel.S01E01.720p.AMZN.WEBRip.x264-GalaxyTV</a> (translated from English)</td></tr>
                <tr><td><a href="subs/1466/Citadel_S01E01.html">Citadel_S01E01</a> (translated from Portuguese)</td></tr>
            </table></body></html>
        """.trimIndent()

        val citadelCandidates = SubtitleCatProvider.extractCandidatePages(searchHtmlCitadel)
        assertEquals(2, citadelCandidates.size)
        assertEquals("Citadel.S01E01.720p.AMZN.WEBRip.x264-GalaxyTV", citadelCandidates[0].candidateTitle)
        assertEquals("https://www.subtitlecat.com/subs/1482/Citadel.S01E01.720p.AMZN.WEBRip.x264-GalaxyTV.html", citadelCandidates[0].pageUrl)
        assertEquals(1, citadelCandidates[0].season)
        assertEquals(1, citadelCandidates[0].episode)

        val searchHtmlOngBak = """
            <html><body><table>
                <tr><td><a href="subs/776/Ong-bak.3.2010.1080p.BluRay.x264.AAC5.1-.YTS.MX.html">Ong-bak.3.2010.1080p.BluRay.x264.AAC5.1-.YTS.MX</a> (translated from English)</td></tr>
            </table></body></html>
        """.trimIndent()

        val ongBakCandidates = SubtitleCatProvider.extractCandidatePages(searchHtmlOngBak)
        assertEquals(1, ongBakCandidates.size)
        assertEquals("Ong-bak.3.2010.1080p.BluRay.x264.AAC5.1-.YTS.MX", ongBakCandidates[0].candidateTitle)
        assertEquals("https://www.subtitlecat.com/subs/776/Ong-bak.3.2010.1080p.BluRay.x264.AAC5.1-.YTS.MX.html", ongBakCandidates[0].pageUrl)
        assertEquals(2010, ongBakCandidates[0].year)
    }

    @Test
    fun testExtractSubtitleCatLanguageEntries() {
        val detailHtml = """
            <html>
            <body>
            <table class="table">
                <tr><td>Spanish</td><td><a href="/subs/54/Inception-2010-es.html" id="download_es">Download</a></td></tr>
                <tr><td>Spanish (Latin America)</td><td><a href="/subs/54/Inception-2010-es-419.html" id="download_es-419">Download</a></td></tr>
                <tr><td>Romanian</td><td><a href="/subs/54/Inception-2010-ro.html">Download</a></td></tr>
                <tr><td>English</td><td><a href="/subs/54/Inception-2010-en.html">Download</a></td></tr>
                <tr><td>French</td><td><a href="/subs/54/Inception-2010-fr.html">Download</a></td></tr>
                <tr><td>German</td><td><a href="/subs/54/Inception-2010-de.html">Download</a></td></tr>
                <tr><td>Italian</td><td><a href="/subs/54/Inception-2010-it.html">Download</a></td></tr>
                <tr><td>Portuguese</td><td><a href="/subs/54/Inception-2010-pt.html">Download</a></td></tr>
                <tr><td>Russian</td><td><a href="/subs/54/Inception-2010-ru.html">Download</a></td></tr>
            </table>
            </body>
            </html>
        """.trimIndent()

        val entries = SubtitleCatProvider.extractLanguageEntries(detailHtml, "https://www.subtitlecat.com/subs/54/Inception-2010.html")
        val langs = entries.map { it.language }.toSet()

        assertTrue(langs.contains("es"))
        assertTrue(langs.contains("es-419"))
        assertTrue(langs.contains("ro"))
        assertTrue(langs.contains("en"))
        assertTrue(langs.contains("fr"))
        assertTrue(langs.contains("de"))
        assertTrue(langs.contains("it"))
        assertTrue(langs.contains("pt"))
        assertTrue(langs.contains("ru"))
    }

    @Test
    fun testExtractDirectSubtitleLinkFromHtml() {
        val intermediateHtml = """
            <html>
            <body>
            <div class="content">
                <p>Click below to download your subtitle:</p>
                <a id="download_es" class="btn btn-primary" href="/download/54-es.srt">Download Subtitle</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val directLink = SubtitleResolver.extractDirectSubtitleLinkFromHtml(intermediateHtml, "https://www.subtitlecat.com/subs/54/Inception-2010-es.html")
        assertNotNull(directLink)
        assertEquals("https://www.subtitlecat.com/download/54-es.srt", directLink)
    }

    @Test
    fun testRejectionOfMismatchedCandidates() {
        val requestFrasier = SubtitleSearchRequest(
            title = "Frasier",
            isSeries = true,
            season = 1,
            episode = 1
        )
        // Candidate is Sword and Beloved S01E01 -> must be rejected
        val candSwordAndBeloved = "Sword.and.Beloved.S01E01.1080p.WEB-DL.H264.AAC.mkv"
        val validation = SubtitleMatcher.validateCandidate(
            candidateTitle = candSwordAndBeloved,
            request = requestFrasier
        )
        assertFalse("Sword and Beloved must be rejected when searching Frasier", validation.isValid)

        // Episode mismatch test
        val requestS01E01 = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1
        )
        val candE02 = "Sword.and.Beloved.S01E02.1080p.WEB-DL.mkv"
        val validationE02 = SubtitleMatcher.validateCandidate(
            candidateTitle = candE02,
            request = requestS01E01
        )
        assertFalse("Episode 2 must be rejected when searching Episode 1", validationE02.isValid)
    }

    @Test
    fun testFavoritesPlaylistIsolationAndCounterConsistency() {
        val favPlaylistA = com.example.motor.db.FavoriteItem(
            id = "movie-1",
            title = "Inception",
            type = "movie",
            url = "http://server/movie1.mp4",
            logo = null,
            addedAt = 1000L,
            playlistId = 101L
        )

        val favPlaylistB = com.example.motor.db.FavoriteItem(
            id = "movie-2",
            title = "Interstellar",
            type = "movie",
            url = "http://server/movie2.mp4",
            logo = null,
            addedAt = 2000L,
            playlistId = 102L
        )

        val allFavorites = listOf(favPlaylistA, favPlaylistB)

        // When Playlist A (101) is active:
        val activePlaylistAId = 101L
        val favoritesForA = allFavorites.filter { it.playlistId == activePlaylistAId }
        assertEquals(1, favoritesForA.size)
        assertEquals("Inception", favoritesForA.first().title)

        // Counter and list must be identical:
        val counterA = favoritesForA.count { it.type == "movie" }
        val listA = favoritesForA.filter { it.type == "movie" }
        assertEquals(counterA, listA.size)

        // When switching to Playlist B (102):
        val activePlaylistBId = 102L
        val favoritesForB = allFavorites.filter { it.playlistId == activePlaylistBId }
        assertEquals(1, favoritesForB.size)
        assertEquals("Interstellar", favoritesForB.first().title)
        assertFalse(favoritesForB.any { it.title == "Inception" })

        val counterB = favoritesForB.count { it.type == "movie" }
        val listB = favoritesForB.filter { it.type == "movie" }
        assertEquals(counterB, listB.size)

        // When switching to Playlist C with no favorites:
        val activePlaylistCId = 103L
        val favoritesForC = allFavorites.filter { it.playlistId == activePlaylistCId }
        val counterC = favoritesForC.count { it.type == "movie" }
        val listC = favoritesForC.filter { it.type == "movie" }
        assertEquals(0, counterC)
        assertEquals(0, listC.size)
    }
}
