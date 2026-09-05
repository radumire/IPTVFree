package com.example

import com.example.motor.tmdb.TmdbService
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testParseSubtitleCatLanguages() {
        assertEquals("es", TmdbService.parseSubtitleCatLang("Spanish", "/subs/123/movie.html"))
        assertEquals("es", TmdbService.parseSubtitleCatLang("Español", "/subs/123/movie-es.html"))
        assertEquals("es", TmdbService.parseSubtitleCatLang("download_es", "/subs/123/movie-es.html"))
        assertEquals("es-419", TmdbService.parseSubtitleCatLang("Spanish (Latin America)", "/subs/123/movie-es-419.html"))
        assertEquals("es-419", TmdbService.parseSubtitleCatLang("Latino", "/subs/123/movie-latino.html"))
        assertEquals("es-419", TmdbService.parseSubtitleCatLang("download_es-419", "/subs/123/movie.html"))
        assertEquals("ro", TmdbService.parseSubtitleCatLang("Romanian", "/subs/123/movie-ro.html"))
        assertEquals("ro", TmdbService.parseSubtitleCatLang("Română", "/subs/123/movie.html"))
        assertEquals("en", TmdbService.parseSubtitleCatLang("English", "/subs/123/movie-en.html"))
        assertEquals("fr", TmdbService.parseSubtitleCatLang("French", "/subs/123/movie-fr.html"))
        assertEquals("de", TmdbService.parseSubtitleCatLang("German", "/subs/123/movie-de.html"))
        assertEquals("it", TmdbService.parseSubtitleCatLang("Italian", "/subs/123/movie-it.html"))
        assertEquals("pt", TmdbService.parseSubtitleCatLang("Portuguese", "/subs/123/movie-pt.html"))
        assertEquals("ru", TmdbService.parseSubtitleCatLang("Russian", "/subs/123/movie-ru.html"))
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

        val pages = TmdbService.extractSubtitleCatResultPages(searchHtml)
        assertEquals(2, pages.size)
        assertTrue(pages.contains("https://www.subtitlecat.com/subs/54/Inception-2010.html"))
        assertTrue(pages.contains("https://www.subtitlecat.com/subs/99/Inception-Bonus.html"))
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

        val entries = TmdbService.extractSubtitleCatLanguageEntries(detailHtml, "https://www.subtitlecat.com/subs/54/Inception-2010.html")
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

        val directLink = TmdbService.extractDirectSubtitleLinkFromHtml(intermediateHtml, "https://www.subtitlecat.com/subs/54/Inception-2010-es.html")
        assertNotNull(directLink)
        assertEquals("https://www.subtitlecat.com/download/54-es.srt", directLink)
    }
}
