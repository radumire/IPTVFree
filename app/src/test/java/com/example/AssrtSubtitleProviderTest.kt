package com.example

import com.example.motor.subtitles.AssrtSubtitleProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AssrtSubtitleProviderTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun mockClient(responder: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> responder(chain.request()) }
            .build()
    }

    private fun successResponse(request: Request, json: String, code: Int = 200): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(json.toResponseBody(jsonMediaType))
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FIXTURES REALES DE ASSRT / 射手网
    // ─────────────────────────────────────────────────────────────────────────────

    // Fixture real para la serie china "墨雨云间" (The Double, 2024)
    private val theDoubleSearchResponse = """
        {
          "status": 0,
          "sub": {
            "action": "search",
            "result": "succeed",
            "subs": [
              {
                "id": 612450,
                "native_name": "墨雨云间 第1集",
                "videoname": "The.Double.2024.S01E01.1080p.WEB-DL.H264.AAC-HQC.mp4",
                "upload_time": "2024-06-02 21:30:00",
                "subtype": "ass",
                "vote_score": 4.8,
                "release_site": "YYeTs",
                "lang": {
                  "desc": "简体",
                  "langlist": { "langlist": ["chs"] }
                }
              },
              {
                "id": 612451,
                "native_name": "墨雨云间 第2集",
                "videoname": "The.Double.2024.S01E02.1080p.WEB-DL.H264.AAC-HQC.mp4",
                "upload_time": "2024-06-02 22:00:00",
                "subtype": "ass",
                "vote_score": 4.7,
                "release_site": "YYeTs",
                "lang": {
                  "desc": "简体",
                  "langlist": { "langlist": ["chs"] }
                }
              },
              {
                "id": 612452,
                "native_name": "墨雨云间 S01E01 繁体中字",
                "videoname": "The.Double.S01E01.720p.HDTV.x264.mkv",
                "upload_time": "2024-06-02 22:15:00",
                "subtype": "srt",
                "vote_score": 4.9,
                "release_site": "SubHD",
                "lang": {
                  "desc": "繁体",
                  "langlist": { "langlist": ["cht"] }
                }
              },
              {
                "id": 612453,
                "native_name": "墨雨云间 S01 全集打包",
                "videoname": "The.Double.S01.Complete.Pack",
                "upload_time": "2024-06-15 10:00:00",
                "subtype": "zip",
                "vote_score": 5.0,
                "release_site": "Zimuku",
                "lang": {
                  "desc": "中英双语",
                  "langlist": { "langlist": ["chs", "eng"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val theDoubleDetail612450 = """
        {
          "status": 0,
          "sub": {
            "action": "detail",
            "result": "succeed",
            "subs": [
              {
                "id": 612450,
                "native_name": "墨雨云间 第1集",
                "videoname": "The.Double.2024.S01E01.1080p.WEB-DL.H264.AAC-HQC.mp4",
                "filename": "The.Double.2024.S01E01.chs.ass",
                "url": "https://api.assrt.net/download/612450/The.Double.2024.S01E01.chs.ass",
                "filelist": [
                  {
                    "f": "The.Double.2024.S01E01.chs.ass",
                    "s": 51200,
                    "url": "https://api.assrt.net/download/612450/The.Double.2024.S01E01.chs.ass"
                  }
                ],
                "lang": {
                  "desc": "简体",
                  "langlist": { "langlist": ["chs"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val theDoubleDetail612451 = """
        {
          "status": 0,
          "sub": {
            "action": "detail",
            "result": "succeed",
            "subs": [
              {
                "id": 612451,
                "native_name": "墨雨云间 第2集",
                "videoname": "The.Double.2024.S01E02.1080p.WEB-DL.H264.AAC-HQC.mp4",
                "filename": "The.Double.2024.S01E02.chs.ass",
                "url": "https://api.assrt.net/download/612451/The.Double.2024.S01E02.chs.ass",
                "filelist": [
                  {
                    "f": "The.Double.2024.S01E02.chs.ass",
                    "s": 51900,
                    "url": "https://api.assrt.net/download/612451/The.Double.2024.S01E02.chs.ass"
                  }
                ],
                "lang": {
                  "desc": "简体",
                  "langlist": { "langlist": ["chs"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val theDoubleDetail612452 = """
        {
          "status": 0,
          "sub": {
            "action": "detail",
            "result": "succeed",
            "subs": [
              {
                "id": 612452,
                "native_name": "墨雨云间 S01E01 繁体中字",
                "videoname": "The.Double.S01E01.720p.HDTV.x264.mkv",
                "filename": "The.Double.S01E01.cht.srt",
                "url": "https://api.assrt.net/download/612452/The.Double.S01E01.cht.srt",
                "filelist": [
                  {
                    "f": "The.Double.S01E01.cht.srt",
                    "s": 42000,
                    "url": "https://api.assrt.net/download/612452/The.Double.S01E01.cht.srt"
                  }
                ],
                "lang": {
                  "desc": "繁体",
                  "langlist": { "langlist": ["cht"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val theDoubleDetail612453 = """
        {
          "status": 0,
          "sub": {
            "action": "detail",
            "result": "succeed",
            "subs": [
              {
                "id": 612453,
                "native_name": "墨雨云间 S01 全集打包",
                "videoname": "The.Double.S01.Complete.Pack",
                "filename": "The.Double.S01.Complete.zip",
                "url": "https://api.assrt.net/download/612453/The.Double.S01.Complete.zip",
                "filelist": [
                  {
                    "f": "The.Double.S01E01.Bilingual.ass",
                    "s": 48000,
                    "url": "https://api.assrt.net/download/612453/The.Double.S01E01.Bilingual.ass"
                  },
                  {
                    "f": "The.Double.S01E02.Bilingual.ass",
                    "s": 49000,
                    "url": "https://api.assrt.net/download/612453/The.Double.S01E02.Bilingual.ass"
                  }
                ],
                "lang": {
                  "desc": "中英双语",
                  "langlist": { "langlist": ["chs", "eng"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    // Fixture real para película "流浪地球2" (The Wandering Earth II, 2023)
    private val wanderingEarthSearchResponse = """
        {
          "status": 0,
          "sub": {
            "action": "search",
            "result": "succeed",
            "subs": [
              {
                "id": 599100,
                "native_name": "流浪地球2",
                "videoname": "The.Wandering.Earth.II.2023.2160p.HQ.WEB-DL.H265.DDP5.1.Atmos-GM.mp4",
                "upload_time": "2023-04-14 18:00:00",
                "subtype": "srt",
                "vote_score": 4.9,
                "release_site": "YYeTs",
                "lang": {
                  "desc": "简体中文",
                  "langlist": { "langlist": ["chs"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private val wanderingEarthDetail599100 = """
        {
          "status": 0,
          "sub": {
            "action": "detail",
            "result": "succeed",
            "subs": [
              {
                "id": 599100,
                "native_name": "流浪地球2",
                "videoname": "The.Wandering.Earth.II.2023.2160p.HQ.WEB-DL.H265.DDP5.1.Atmos-GM.mp4",
                "filename": "The.Wandering.Earth.II.2023.chs.srt",
                "url": "https://api.assrt.net/download/599100/The.Wandering.Earth.II.2023.chs.srt",
                "filelist": [
                  {
                    "f": "The.Wandering.Earth.II.2023.chs.srt",
                    "s": 65000,
                    "url": "https://api.assrt.net/download/599100/The.Wandering.Earth.II.2023.chs.srt"
                  }
                ],
                "lang": {
                  "desc": "简体中文",
                  "langlist": { "langlist": ["chs"] }
                }
              }
            ]
          }
        }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────────
    // TESTS UNITARIOS EXIGIDOS
    // ─────────────────────────────────────────────────────────────────────────────

    // 1. Búsqueda de película
    @Test
    fun testMovieSearch() = runBlocking {
        val client = mockClient { req ->
            val url = req.url.toString()
            if (url.contains("/sub/search")) {
                successResponse(req, wanderingEarthSearchResponse)
            } else if (url.contains("/sub/detail")) {
                successResponse(req, wanderingEarthDetail599100)
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build()
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "valid_test_token_32chars_ok" })
        val results = provider.search(
            rawTitle = "The Wandering Earth II",
            normTitle = "The Wandering Earth II",
            isSeries = false,
            year = 2023,
            originalTitle = "流浪地球2"
        )

        assertFalse(results.isEmpty())
        val sub = results[0]
        assertEquals("ASSRT", sub.source)
        assertEquals("zh-CN", sub.language)
        assertEquals("https://api.assrt.net/download/599100/The.Wandering.Earth.II.2023.chs.srt", sub.url)
    }

    // 2. Búsqueda de serie & 3. S01E01
    @Test
    fun testSeriesSearchS01E01() = runBlocking {
        val client = mockClient { req ->
            val url = req.url.toString()
            when {
                url.contains("/sub/search") -> successResponse(req, theDoubleSearchResponse)
                url.contains("id=612450") -> successResponse(req, theDoubleDetail612450)
                url.contains("id=612452") -> successResponse(req, theDoubleDetail612452)
                url.contains("id=612453") -> successResponse(req, theDoubleDetail612453)
                else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build()
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "valid_test_token_32chars_ok" })
        val results = provider.search(
            rawTitle = "The Double S01E01",
            normTitle = "The Double",
            isSeries = true,
            season = 1,
            episode = 1,
            originalTitle = "墨雨云间"
        )

        assertFalse(results.isEmpty())
        // S01E01 must be accepted, S01E02 must NOT be included in results
        val epNumbers = results.map { it.episode }.distinct()
        assertEquals(listOf(1), epNumbers)
        assertTrue(results.any { it.language == "zh-CN" })
        assertTrue(results.any { it.language == "zh-TW" })
    }

    // 4. S01E02
    @Test
    fun testSeriesSearchS01E02() = runBlocking {
        val client = mockClient { req ->
            val url = req.url.toString()
            when {
                url.contains("/sub/search") -> successResponse(req, theDoubleSearchResponse)
                url.contains("id=612451") -> successResponse(req, theDoubleDetail612451)
                url.contains("id=612453") -> successResponse(req, theDoubleDetail612453)
                else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build()
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "valid_test_token_32chars_ok" })
        val results = provider.search(
            rawTitle = "The Double S01E02",
            normTitle = "The Double",
            isSeries = true,
            season = 1,
            episode = 2,
            originalTitle = "墨雨云间"
        )

        assertFalse(results.isEmpty())
        val epNumbers = results.map { it.episode }.distinct()
        assertEquals(listOf(2), epNumbers)
    }

    // 5. Título internacional + título original chino
    @Test
    fun testChineseCharactersDetectionAndQueryPriority() {
        assertTrue(AssrtSubtitleProvider.containsChinese("墨雨云间"))
        assertTrue(AssrtSubtitleProvider.containsChinese("The Double (墨雨云间)"))
        assertFalse(AssrtSubtitleProvider.containsChinese("The Double S01E01"))
    }

    // 6. Chino Simplificado & 7. Chino Tradicional
    @Test
    fun testLanguageIdentificationSimplifiedAndTraditional() {
        val provider = AssrtSubtitleProvider()

        val (chsCode, chsName) = provider.parseAssrtLanguage(listOf("chs"), "简体中文", "Movie.chs.srt")
        assertEquals("zh-CN", chsCode)
        assertEquals("Chino Simplificado", chsName)

        val (chtCode, chtName) = provider.parseAssrtLanguage(listOf("cht"), "繁体中字", "Movie.cht.ass")
        assertEquals("zh-TW", chtCode)
        assertEquals("Chino Tradicional", chtName)

        val (bilingualCode, bilingualName) = provider.parseAssrtLanguage(listOf("chs", "eng"), "中英双语", "Movie.bilingual.ass")
        assertEquals("zh-CN", bilingualCode)
        assertEquals("Chino (Bilingüe)", bilingualName)
    }

    // 8. Resultado sin idioma
    @Test
    fun testResultWithoutLanguageDefaultsToGeneralChinese() {
        val provider = AssrtSubtitleProvider()
        val (code, name) = provider.parseAssrtLanguage(emptyList(), "", "Movie.ass")
        assertEquals("zh", code)
        assertEquals("Chino", name)
    }

    // 9. Resultado sin episodio & 10. Episodio incorrecto
    @Test
    fun testEpisodeValidationRejectsConflictingEpisode() {
        val client = mockClient { req ->
            val url = req.url.toString()
            if (url.contains("/sub/search")) {
                val json = """
                    {
                      "status": 0,
                      "sub": {
                        "action": "search",
                        "subs": [
                          {
                            "id": 9991,
                            "native_name": "Series Name S01E05",
                            "videoname": "Series.Name.S01E05.720p.mkv",
                            "subtype": "srt",
                            "vote_score": 5.0,
                            "release_site": "Web",
                            "lang": { "desc": "简体", "langlist": { "langlist": ["chs"] } }
                          }
                        ]
                      }
                    }
                """.trimIndent()
                successResponse(req, json)
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build()
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = runBlocking {
            provider.search(
                rawTitle = "Series Name",
                normTitle = "Series Name",
                isSeries = true,
                season = 1,
                episode = 1
            )
        }
        // Candidate is E05, target is E01 -> MUST be rejected
        assertTrue(results.isEmpty())
    }

    // 11. Temporada incorrecta
    @Test
    fun testSeasonMismatchRejection() {
        val client = mockClient { req ->
            val url = req.url.toString()
            if (url.contains("/sub/search")) {
                val json = """
                    {
                      "status": 0,
                      "sub": {
                        "action": "search",
                        "subs": [
                          {
                            "id": 9992,
                            "native_name": "Series S02E01",
                            "videoname": "Series.S02E01.720p.mkv",
                            "subtype": "srt",
                            "vote_score": 5.0,
                            "release_site": "Web",
                            "lang": { "desc": "简体", "langlist": { "langlist": ["chs"] } }
                          }
                        ]
                      }
                    }
                """.trimIndent()
                successResponse(req, json)
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build()
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = runBlocking {
            provider.search(
                rawTitle = "Series",
                normTitle = "Series",
                isSeries = true,
                season = 1,
                episode = 1
            )
        }
        assertTrue(results.isEmpty())
    }

    // 12. Deduplicación
    @Test
    fun testDeduplicationIdenticalUrls() = runBlocking {
        val client = mockClient { req ->
            val url = req.url.toString()
            if (url.contains("/sub/search")) {
                successResponse(req, wanderingEarthSearchResponse)
            } else {
                successResponse(req, wanderingEarthDetail599100)
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = provider.search(
            rawTitle = "The Wandering Earth II",
            normTitle = "The Wandering Earth II",
            isSeries = false,
            year = 2023
        )
        val urls = results.map { it.url }
        assertEquals(urls.distinct().size, urls.size)
    }

    // 13. Respuesta vacía
    @Test
    fun testEmptySearchResponse() = runBlocking {
        val client = mockClient { req ->
            successResponse(req, """{ "status": 0, "sub": { "subs": [] } }""")
        }
        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = provider.search(rawTitle = "Nonexistent", normTitle = "Nonexistent", isSeries = false)
        assertTrue(results.isEmpty())
    }

    // 14. HTTP Error
    @Test
    fun testHttpErrorHandling() = runBlocking {
        val client = mockClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("{}".toResponseBody(jsonMediaType))
                .build()
        }
        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = provider.search(rawTitle = "Any Title", normTitle = "Any Title", isSeries = false)
        assertTrue(results.isEmpty())
    }

    // 15. Timeout / error de red
    @Test
    fun testNetworkTimeoutExceptionHandling() = runBlocking {
        val client = mockClient { _ ->
            throw IOException("Socket timeout connecting to api.assrt.net")
        }
        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = provider.search(rawTitle = "Any Title", normTitle = "Any Title", isSeries = false)
        assertTrue(results.isEmpty())
    }

    // 16. Credencial / Token ausente
    @Test
    fun testMissingTokenSkipsGracefully() = runBlocking {
        val provider = AssrtSubtitleProvider(tokenProvider = { null })
        val results = provider.search(rawTitle = "The Double", normTitle = "The Double", isSeries = true, season = 1, episode = 1)
        assertTrue(results.isEmpty())
    }

    // 17. JSON / respuesta inválida
    @Test
    fun testInvalidJsonResponseHandling() = runBlocking {
        val client = mockClient { req ->
            successResponse(req, "<html><body>502 Bad Gateway nginx</body></html>")
        }
        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "token_32chars_test_value_ok" })
        val results = provider.search(rawTitle = "Any", normTitle = "Any", isSeries = false)
        assertTrue(results.isEmpty())
    }

    // 18. Descarga válida
    @Test
    fun testValidDownloadResolution() {
        val provider = AssrtSubtitleProvider()
        val cand = AssrtSubtitleProvider.AssrtSubSummary(
            id = 599100,
            nativeName = "流浪地球2",
            videoName = "The.Wandering.Earth.II.2023.mkv",
            subtype = "srt",
            voteScore = 4.9,
            releaseSite = "YYeTs",
            langDesc = "简体",
            langList = listOf("chs")
        )

        val detailJson = """
            {
              "status": 0,
              "sub": {
                "subs": [
                  {
                    "id": 599100,
                    "filename": "movie.chs.srt",
                    "url": "https://api.assrt.net/download/movie.chs.srt",
                    "filelist": []
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = provider.parseDetailResponse(detailJson, cand, isSeries = false, targetSeason = null, targetEpisode = null, targetYear = 2023)
        assertEquals(1, parsed.size)
        assertEquals("https://api.assrt.net/download/movie.chs.srt", parsed[0].url)
        assertEquals("ASSRT", parsed[0].source)
    }

    // 19. Descarga no disponible (sin url y sin filelist)
    @Test
    fun testDownloadUnavailableReturnsEmpty() {
        val provider = AssrtSubtitleProvider()
        val cand = AssrtSubtitleProvider.AssrtSubSummary(
            id = 599100,
            nativeName = "Test",
            videoName = "Test.mkv",
            subtype = "srt",
            voteScore = 4.9,
            releaseSite = "YYeTs",
            langDesc = "简体",
            langList = listOf("chs")
        )

        val detailJson = """
            {
              "status": 0,
              "sub": {
                "subs": [
                  {
                    "id": 599100,
                    "filename": "",
                    "url": "",
                    "filelist": []
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = provider.parseDetailResponse(detailJson, cand, isSeries = false, targetSeason = null, targetEpisode = null, targetYear = null)
        assertTrue(parsed.isEmpty())
    }

    // 20. Integración con el motor centralizado (SubtitleMatcher.calculateScore)
    @Test
    fun testIntegrationWithSubtitleScoring() {
        val sub = com.example.motor.subtitles.ExternalSubtitle(
            id = "assrt_612450_123",
            url = "https://api.assrt.net/download/612450/sub.ass",
            label = "Chino Simplificado · ASSRT · Original",
            language = "zh-CN",
            source = "ASSRT",
            isTranslated = false,
            displayTitle = "墨雨云间 S01E01",
            season = 1,
            episode = 1
        )

        val req = com.example.motor.subtitles.SubtitleSearchRequest(
            title = "墨雨云间",
            isSeries = true,
            season = 1,
            episode = 1,
            year = 2024
        )

        val score = com.example.motor.subtitles.SubtitleMatcher.calculateScore(sub, req)
        assertTrue(score > 1000)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PRUEBA ESPECÍFICA PARA SERIES CHINAS (DEMOSTRACIÓN COMPLETA)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun testRealChineseSeriesEndToEndFlow() = runBlocking {
        // Serie real: "墨雨云间" (The Double) S01E01
        val client = mockClient { req ->
            val url = req.url.toString()
            when {
                url.contains("/sub/search") -> successResponse(req, theDoubleSearchResponse)
                url.contains("id=612450") -> successResponse(req, theDoubleDetail612450)
                url.contains("id=612452") -> successResponse(req, theDoubleDetail612452)
                url.contains("id=612453") -> successResponse(req, theDoubleDetail612453)
                else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build()
            }
        }

        val provider = AssrtSubtitleProvider(client = client, tokenProvider = { "valid_token_demo" })

        // Búsqueda completa de la serie
        val subtitles = provider.search(
            rawTitle = "The Double",
            normTitle = "The Double",
            isSeries = true,
            season = 1,
            episode = 1,
            originalTitle = "墨雨云间",
            tmdbTitle = "The Double"
        )

        // 1. Debe encontrar candidatos
        assertTrue("Debe encontrar subtítulos para 墨雨云间 S01E01", subtitles.isNotEmpty())

        // 2. Todos los candidatos deben corresponder a la temporada 1 episodio 1
        for (s in subtitles) {
            assertEquals("Todos los subtítulos deben ser del episodio 1", 1, s.episode)
            assertEquals("Todos los subtítulos deben ser de la temporada 1", 1, s.season)
            assertEquals("Proveedor debe ser ASSRT", "ASSRT", s.source)
            assertTrue("URL de descarga debe ser válida", s.url.startsWith("https://api.assrt.net/download/"))
        }

        // 3. Debe incluir Chino Simplificado y Tradicional
        val languages = subtitles.map { it.language }.toSet()
        assertTrue("Debe incluir Chino Simplificado zh-CN", languages.contains("zh-CN"))
        assertTrue("Debe incluir Chino Tradicional zh-TW", languages.contains("zh-TW"))

        // 4. Ningún subtítulo del episodio 2 debe filtrarse
        assertFalse(subtitles.any { it.episode == 2 })
    }

    @Test
    fun testIsMatchingEpisode_withStandaloneNumber() {
        val provider = AssrtSubtitleProvider()
        val candidate = AssrtSubtitleProvider.AssrtSubSummary(
            id = 1001,
            nativeName = "墨雨云间 01",
            videoName = "The.Double.2024.01.1080p.mp4",
            subtype = "ass",
            voteScore = 4.5,
            releaseSite = "YYeTs",
            langDesc = "简体",
            langList = listOf("chs")
        )

        assertTrue(provider.isMatchingEpisode(candidate, targetSeason = 1, targetEpisode = 1))
        assertFalse(provider.isMatchingEpisode(candidate, targetSeason = 1, targetEpisode = 2))
    }

    @Test
    fun testIsMatchingEpisode_withSeasonPackRange() {
        val provider = AssrtSubtitleProvider()
        val candidate = AssrtSubtitleProvider.AssrtSubSummary(
            id = 1002,
            nativeName = "墨雨云间 第01-40集 全集",
            videoName = "The.Double.S01.Complete",
            subtype = "zip",
            voteScore = 4.9,
            releaseSite = "Zimuku",
            langDesc = "双语",
            langList = listOf("chs", "eng")
        )

        assertTrue(provider.isMatchingEpisode(candidate, targetSeason = 1, targetEpisode = 1))
        assertTrue(provider.isMatchingEpisode(candidate, targetSeason = 1, targetEpisode = 15))
    }
}

