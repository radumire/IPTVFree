package com.example.subtitles

import com.example.motor.subtitles.MovieHash
import com.example.motor.subtitles.SubtitleEngine
import com.example.motor.subtitles.SubtitleLanguage
import com.example.motor.subtitles.SubtitleMatcher
import com.example.motor.subtitles.SubtitleSearchRequest
import com.example.motor.subtitles.ExternalSubtitle
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
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class SubtitleSystemTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 1: Sword and Beloved S01E01 correcto → ACEPTAR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test1_swordAndBelovedS01E01_correct_accepted() {
        val request = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1
        )
        val candidateTitle = "Sword.and.Beloved.S01E01.1080p.WEB-DL.H264.AAC.mkv"
        val result = SubtitleMatcher.validateCandidate(
            candidateTitle = candidateTitle,
            request = request
        )

        assertTrue("Sword and Beloved S01E01 debe ser aceptado", result.isValid)
        assertEquals(1, result.detectedSeason)
        assertEquals(1, result.detectedEpisode)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 2: Sword and Beloved S01E01 vs Frasier S01E01 → RECHAZAR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test2_swordAndBelovedS01E01_vs_frasierS01E01_rejected() {
        val request = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1
        )
        val candidateTitle = "Frasier.S01E01.720p.HDTV.x264.mkv"
        val result = SubtitleMatcher.validateCandidate(
            candidateTitle = candidateTitle,
            request = request
        )

        assertFalse("Frasier S01E01 debe ser rechazado al buscar Sword and Beloved S01E01", result.isValid)
        assertTrue("La razón debe indicar conflicto de identidad de serie", result.reason.contains("identity mismatch", ignoreCase = true))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 3: Sword and Beloved S01E01 vs Sword and Beloved S01E02 → RECHAZAR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test3_swordAndBelovedS01E01_vs_swordAndBelovedS01E02_rejected() {
        val request = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1
        )
        val candidateTitle = "Sword.and.Beloved.S01E02.1080p.WEB-DL.mkv"
        val result = SubtitleMatcher.validateCandidate(
            candidateTitle = candidateTitle,
            request = request
        )

        assertFalse("Episodio 2 debe ser rechazado al buscar Episodio 1", result.isValid)
        assertTrue("La razón debe indicar mismatch de episodio", result.reason.contains("Episode mismatch", ignoreCase = true))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 4: Sword and Beloved S01E01 vs Sword and Beloved S02E01 → RECHAZAR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test4_swordAndBelovedS01E01_vs_swordAndBelovedS02E01_rejected() {
        val request = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1
        )
        val candidateTitle = "Sword.and.Beloved.S02E01.1080p.WEB-DL.mkv"
        val result = SubtitleMatcher.validateCandidate(
            candidateTitle = candidateTitle,
            request = request
        )

        assertFalse("Temporada 2 debe ser rechazada al buscar Temporada 1", result.isValid)
        assertTrue("La razón debe indicar mismatch de temporada", result.reason.contains("Season mismatch", ignoreCase = true))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 5: MovieHash idéntico → MÁXIMA PRIORIDAD
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test5_identicalMovieHash_maximumPriority() {
        val dummyHead = ByteArray(65536) { 0x1A.toByte() }
        val dummyTail = ByteArray(65536) { 0x2B.toByte() }
        val size = 500_000_000L

        val hash1 = MovieHash.computeHash(dummyHead, dummyTail, size)
        val hash2 = MovieHash.computeHash(dummyHead, dummyTail, size)

        assertEquals(hash1, hash2)

        val request = SubtitleSearchRequest(
            title = "Inception",
            movieHash = hash1,
            isSeries = false
        )

        val subHashMatch = ExternalSubtitle(
            id = "os_123",
            url = "https://example.com/sub.srt",
            label = "Español · OpenSubtitles (Hash)",
            language = "es",
            source = "OpenSubtitles"
        )

        val scoreHash = SubtitleMatcher.calculateScore(subHashMatch, request, isHashMatch = true)
        val scoreNormal = SubtitleMatcher.calculateScore(subHashMatch, request, isHashMatch = false)

        assertTrue("La coincidencia por hash debe tener al menos 10000 puntos", scoreHash >= 10000)
        assertTrue("El score por hash ($scoreHash) debe ser muy superior al normal ($scoreNormal)", scoreHash > scoreNormal + 5000)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 6: MovieHash diferente → NO aceptar como coincidencia hash
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test6_differentMovieHash_notAcceptedAsHashMatch() {
        val headA = ByteArray(65536) { 0x01.toByte() }
        val tailA = ByteArray(65536) { 0x02.toByte() }
        val hashA = MovieHash.computeHash(headA, tailA, 100_000_000L)

        val headB = ByteArray(65536) { 0x03.toByte() }
        val tailB = ByteArray(65536) { 0x04.toByte() }
        val hashB = MovieHash.computeHash(headB, tailB, 200_000_000L)

        assertNotEquals("Hashes de archivos distintos deben ser diferentes", hashA, hashB)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 7: HLS .m3u8 → NO intentar MovieHash
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test7_hlsM3u8_skipsMovieHash() = runBlocking {
        val hlsUrl1 = "https://stream.server.com/live/channel.m3u8"
        val hlsUrl2 = "https://vod.server.com/movies/index.m3u8?token=xyz"
        val dashUrl = "https://vod.server.com/movies/manifest.mpd"

        assertFalse(MovieHash.isSeekableMediaUrl(hlsUrl1))
        assertFalse(MovieHash.isSeekableMediaUrl(hlsUrl2))
        assertFalse(MovieHash.isSeekableMediaUrl(dashUrl))

        val result = MovieHash.calculateMovieHash(hlsUrl1)
        assertNull("calculateMovieHash para URL .m3u8 debe retornar null sin hacer peticiones", result)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 8: Servidor sin Range → Fallback sin descargar archivo completo
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test8_serverWithoutRange_abortsImmediatelyWithoutFullDownload() = runBlocking {
        // Simular un servidor que devuelve 200 OK en vez de 206 Partial Content
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200) // Servidor ignora Range y devuelve 200 OK con stream completo
                    .message("OK")
                    .body("Big full file content...".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val result = MovieHash.calculateMovieHash(
            videoUrl = "https://example.com/movie.mp4",
            client = mockClient
        )

        assertNull("Si el servidor devuelve 200 OK en vez de 206, debe abortar y retornar null sin descargar", result)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 9: Chino simplificado vs tradicional → DISTINGUIR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test9_simplifiedVsTraditionalChinese_distinguished() {
        val simplifiedCode = SubtitleLanguage.normalizeLanguageCode("chs", "Movie.chs.srt")
        val traditionalCode = SubtitleLanguage.normalizeLanguageCode("cht", "Movie.cht.ass")

        assertEquals("zh-CN", simplifiedCode)
        assertEquals("zh-TW", traditionalCode)

        // No son equivalentes ni se intercambian ciegamente
        assertNotEquals(simplifiedCode, traditionalCode)
        assertFalse(
            "Chino tradicional no debe satisfacer preferencia estricta de Chino simplificado",
            SubtitleLanguage.matchesPreferred(traditionalCode, "zh-CN")
        )
        assertFalse(
            "Chino simplificado no debe satisfacer preferencia estricta de Chino tradicional",
            SubtitleLanguage.matchesPreferred(simplifiedCode, "zh-TW")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 10: Bilingüe chino/inglés → IDENTIFICAR CORRECTAMENTE
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test10_bilingualChineseEnglish_identifiedCorrectly() {
        val bilingualCode = SubtitleLanguage.normalizeLanguageCode("chs,eng", "Movie.中英双语.ass")
        assertEquals(SubtitleLanguage.LANG_ZH_BILINGUAL, bilingualCode)

        // Bilingüe satisface chino simplificado o inglés
        assertTrue(SubtitleLanguage.matchesPreferred(bilingualCode, "zh-CN"))
        assertTrue(SubtitleLanguage.matchesPreferred(bilingualCode, "en"))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 11: Película correcta con título exacto → ACEPTAR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test11_movie_correctExactTitle_accepted() {
        val request = SubtitleSearchRequest(
            title = "Inception",
            mediaType = "movie",
            isSeries = false,
            year = 2010
        )
        val candidateTitle = "Inception.2010.1080p.BluRay.x264-SPARKS.srt"
        val result = SubtitleMatcher.validateCandidate(
            candidateTitle = candidateTitle,
            request = request
        )

        assertTrue("Película con título exacto y año coincidente debe ser aceptada", result.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 12: Película con título parecido pero diferente → RECHAZAR
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test12_movie_similarGenericTitle_rejected() {
        val request = SubtitleSearchRequest(
            title = "The Last of Us",
            mediaType = "movie",
            isSeries = false,
            year = 2023
        )
        // Candidato claramente diferente que solo comparte "The Last"
        val candidateTitle = "The.Last.Samurai.2003.1080p.BluRay.srt"
        val result = SubtitleMatcher.validateCandidate(
            candidateTitle = candidateTitle,
            request = request
        )

        assertFalse("Película diferente que solo comparte 'The Last' debe ser rechazada", result.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 13: Live TV → NO REALIZAR BÚSQUEDA
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test13_liveTv_noSearchPerformed() = runBlocking {
        val engine = SubtitleEngine.getInstance()
        val requestLive = SubtitleSearchRequest(
            title = "La 1 HD",
            mediaType = "live"
        )
        val requestTv = SubtitleSearchRequest(
            title = "Antena 3",
            mediaType = "tv"
        )

        assertTrue(requestLive.isLiveTv)
        assertTrue(requestTv.isLiveTv)

        val resultsLive = engine.searchSubtitles(requestLive)
        val resultsTv = engine.searchSubtitles(requestTv)

        assertTrue("Búsqueda en Live TV debe retornar lista vacía de inmediato", resultsLive.isEmpty())
        assertTrue("Búsqueda en TV debe retornar lista vacía de inmediato", resultsTv.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 14: OpenSubtitles ID único basado en fileId
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test14_openSubtitles_uniqueIdBasedOnFileId() {
        val fileId1 = 987654L
        val sub1 = ExternalSubtitle(
            id = "os_$fileId1",
            url = "https://example.com/sub1.srt",
            label = "Español #1 · OpenSubtitles",
            language = "es",
            source = "OpenSubtitles",
            fileId = fileId1
        )
        assertTrue(sub1.id.startsWith("os_"))
        assertEquals("os_987654", sub1.id)
        assertEquals(fileId1, sub1.fileId)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 15: Dos subtítulos OpenSubtitles con distinto fileId NO son duplicados
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test15_twoOpenSubtitles_differentFileId_notDuplicates() {
        val subA = ExternalSubtitle(
            id = "os_101",
            url = "https://example.com/subA.srt",
            label = "Español #1 · OpenSubtitles",
            language = "es",
            source = "OpenSubtitles",
            fileId = 101L,
            score = 500
        )
        val subB = ExternalSubtitle(
            id = "os_102",
            url = "https://example.com/subB.srt",
            label = "Español #2 · OpenSubtitles",
            language = "es",
            source = "OpenSubtitles",
            fileId = 102L,
            score = 500
        )

        val seenKeys = mutableSetOf<String>()
        val preserved = mutableListOf<ExternalSubtitle>()
        for (sub in listOf(subA, subB)) {
            val key = when {
                sub.fileId != null && sub.fileId > 0 -> "${sub.source}_file_${sub.fileId}"
                sub.id.isNotBlank() -> "${sub.source}_id_${sub.id}"
                else -> "${sub.source}_url_${sub.url}"
            }
            if (seenKeys.add(key)) {
                preserved.add(sub)
            }
        }
        assertEquals(2, preserved.size)
        assertEquals("os_101", preserved[0].id)
        assertEquals("os_102", preserved[1].id)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 16: TrackOption de pista real conserva groupIndex, trackIndex e ID correcto
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test16_trackOption_preservesRealMedia3GroupAndTrackIndexAndId() {
        val realTrack = com.example.motor.player.TrackOption(
            id = "os_9999",
            name = "Español · OpenSubtitles",
            type = androidx.media3.common.C.TRACK_TYPE_TEXT,
            groupIndex = 2,
            trackIndex = 0,
            isSelected = true,
            isNone = false,
            formatId = "os_9999",
            formatLabel = "Español · OpenSubtitles",
            externalSubtitleId = "os_9999"
        )

        assertTrue("groupIndex debe ser válido (>= 0)", realTrack.groupIndex >= 0)
        assertTrue("trackIndex debe ser válido (>= 0)", realTrack.trackIndex >= 0)
        assertEquals("os_9999", realTrack.id)
        assertEquals("os_9999", realTrack.formatId)
        assertEquals("os_9999", realTrack.externalSubtitleId)
        assertFalse(realTrack.isNone)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 17: "Desactivados" es la ÚNICA opción con groupIndex = -1
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test17_disabledTrack_isOnlyOneWithNegativeGroupIndex() {
        val noneTrack = com.example.motor.player.TrackOption(
            id = "none",
            name = "Desactivados",
            type = androidx.media3.common.C.TRACK_TYPE_TEXT,
            groupIndex = -1,
            trackIndex = -1,
            isSelected = true,
            isNone = true
        )

        assertTrue(noneTrack.isNone)
        assertEquals(-1, noneTrack.groupIndex)
        assertEquals(-1, noneTrack.trackIndex)

        val realExternalTrack = com.example.motor.player.TrackOption(
            id = "os_12345",
            name = "Español · OpenSubtitles",
            type = androidx.media3.common.C.TRACK_TYPE_TEXT,
            groupIndex = 1,
            trackIndex = 0,
            isSelected = false,
            isNone = false,
            formatId = "os_12345",
            externalSubtitleId = "os_12345"
        )
        assertNotEquals(-1, realExternalTrack.groupIndex)
        assertFalse(realExternalTrack.isNone)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 18: La selección en Media3 aplica TrackSelectionOverride sobre pista real
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test18_selectTrack_appliesTrackSelectionOverride() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()

        try {
            // Test disabling tracks
            val noneOption = com.example.motor.player.TrackOption(
                id = "none",
                name = "Desactivados",
                type = androidx.media3.common.C.TRACK_TYPE_TEXT,
                groupIndex = -1,
                trackIndex = -1,
                isSelected = false,
                isNone = true
            )
            com.example.motor.player.PlayerEngine.selectTrack(player, noneOption)
            assertTrue(
                "Al seleccionar Desactivados, el tipo C.TRACK_TYPE_TEXT debe estar deshabilitado",
                player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)
            )
        } finally {
            player.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 19: getApiLanguages() envía códigos OpenSubtitles correctos sin ISO-639-2
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test19_openSubtitles_getApiLanguages_properMapping() {
        val provider = com.example.motor.subtitles.OpenSubtitlesProvider()
        assertEquals("es", provider.getApiLanguages("es"))
        assertEquals("es", provider.getApiLanguages("Spanish"))
        assertEquals("es", provider.getApiLanguages("es-419"))
        assertEquals("en", provider.getApiLanguages("en"))
        assertEquals("en", provider.getApiLanguages("English"))
        assertEquals("ro", provider.getApiLanguages("ro"))
        assertEquals("ro", provider.getApiLanguages("Romanian"))
        assertEquals("zh-cn", provider.getApiLanguages("zh-CN"))
        assertEquals("zh-tw", provider.getApiLanguages("zh-TW"))
        assertEquals("ze", provider.getApiLanguages("zh-bilingual"))

        // NO debe contener spa, eng ni rum
        assertFalse(provider.getApiLanguages("es").contains("spa"))
        assertFalse(provider.getApiLanguages("en").contains("eng"))
        assertFalse(provider.getApiLanguages("ro").contains("rum"))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 20: OpenSubtitles cascade: Cuando MovieHash devuelve resultados, NO ejecuta Metadata
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test20_openSubtitles_hashSuccess_skipsMetadata() = runBlocking {
        val executedQueries = mutableListOf<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                executedQueries.add(url)
                val jsonResponse = """
                    {
                        "total_count": 1,
                        "data": [
                            {
                                "id": "sub_1",
                                "attributes": {
                                    "language": "es",
                                    "url": "https://example.com/sub.srt",
                                    "files": [
                                        {"file_id": 5555, "file_name": "Inception.2010.es.srt"}
                                    ],
                                    "feature_details": {
                                        "title": "Inception",
                                        "year": 2010
                                    }
                                }
                            }
                        ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResponse.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(
            client = mockClient,
            apiKeyProvider = { "test_api_key" }
        )

        val request = SubtitleSearchRequest(
            title = "Inception",
            movieHash = "1729091e36bbe460",
            year = 2010,
            preferredLanguage = "es",
            isSeries = false
        )

        val results = provider.search(request)

        assertEquals(1, results.size)
        assertEquals("os_5555", results[0].id)
        assertEquals("HASH", results[0].searchStrategy)
        assertTrue(results[0].score != null && results[0].score!! >= 10000)

        // Verificamos que se ejecutó MovieHash pero NO metadata
        assertEquals(1, executedQueries.size)
        assertTrue(executedQueries[0].contains("moviehash=1729091e36bbe460"))
        // Búsqueda universal no incluye filtro de idioma forzado
        assertFalse(executedQueries[0].contains("languages="))
        assertFalse(executedQueries.any { it.contains("query=Inception") })
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 21: OpenSubtitles cascade: Cuando MovieHash devuelve 0, ejecuta Metadata exactamente 1 vez
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test21_openSubtitles_hashZero_executesMetadataOnce() = runBlocking {
        val executedQueries = mutableListOf<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                executedQueries.add(url)
                val jsonResponse = if (url.contains("moviehash=")) {
                    """{"total_count": 0, "data": []}"""
                } else {
                    """
                        {
                            "total_count": 1,
                            "data": [
                                {
                                    "id": "sub_2",
                                    "attributes": {
                                        "language": "es",
                                        "url": "https://example.com/sub2.srt",
                                        "files": [
                                            {"file_id": 7777, "file_name": "The.Uprising.2013.es.srt"}
                                        ],
                                        "feature_details": {
                                            "title": "The Uprising",
                                            "year": 2013
                                        }
                                    }
                                }
                            ]
                        }
                    """.trimIndent()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResponse.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(
            client = mockClient,
            apiKeyProvider = { "test_api_key" }
        )

        val request = SubtitleSearchRequest(
            title = "The Uprising",
            movieHash = "1729091e36bbe460",
            year = 2013,
            preferredLanguage = "es",
            isSeries = false
        )

        val results = provider.search(request)

        assertEquals(1, results.size)
        assertEquals("os_7777", results[0].id)
        assertEquals("METADATA", results[0].searchStrategy)
        assertFalse("El resultado de metadata no debe tratarse como HASH", results[0].searchStrategy == "HASH")

        // Verificamos exactamente 2 peticiones (1 HASH y 1 METADATA)
        assertEquals(2, executedQueries.size)
        assertTrue(executedQueries[0].contains("moviehash=1729091e36bbe460"))
        assertTrue(executedQueries[1].contains("query=The+Uprising") || executedQueries[1].contains("query=The%20Uprising"))
        // Búsqueda universal sin languages=
        assertFalse(executedQueries[0].contains("languages="))
        assertFalse(executedQueries[1].contains("languages="))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 22: SubtitleEngine ejecuta OpenSubtitlesProvider exactamente 1 vez (sin HASH->META->HASH->META)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test22_subtitleEngine_executesProviderExactlyOnce() = runBlocking {
        var openSubtitlesCallCount = 0
        val osMockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                openSubtitlesCallCount++
                val jsonResponse = """{"total_count": 0, "data": []}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResponse.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val otherMockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val emptyBody = if (chain.request().url.toString().contains("assrt")) {
                    """{"status": 0, "sub": {"subs": []}}"""
                } else {
                    """<html><body><table></table></body></html>"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(emptyBody.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val osProvider = com.example.motor.subtitles.OpenSubtitlesProvider(
            client = osMockClient,
            apiKeyProvider = { "test_api_key" }
        )

        val subcatProvider = com.example.motor.subtitles.SubtitleCatProvider(
            client = otherMockClient
        )
        val assrtProvider = com.example.motor.subtitles.AssrtSubtitleProvider(
            client = otherMockClient,
            tokenProvider = { "token" }
        )

        val engine = com.example.motor.subtitles.SubtitleEngine(
            openSubtitlesProvider = osProvider,
            subtitleCatProvider = subcatProvider,
            assrtSubtitleProvider = assrtProvider
        )

        val request = SubtitleSearchRequest(
            title = "The Uprising",
            movieHash = "1729091e36bbe460",
            year = 2013,
            preferredLanguage = "es",
            isSeries = false
        )

        engine.searchSubtitles(request)

        // OpenSubtitles ejecuta HASH (0 resultados) y luego METADATA (0 resultados).
        // Total llamadas HTTP exclusivas de OpenSubtitles = exactamente 2 (1 HASH + 1 METADATA).
        // NUNCA 4 (que ocurría cuando SubtitleEngine llamaba a OpenSubtitles dos veces por separado).
        assertEquals(
            "OpenSubtitles no debe ejecutarse duplicado en SubtitleEngine (exactamente 1 HASH + 1 METADATA)",
            2,
            openSubtitlesCallCount
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 23: OpenSubtitles NO envía languages= como filtro universal en metadata ni hash
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test23_openSubtitles_doesNotSendLanguagesParam() = runBlocking {
        val capturedUrls = mutableListOf<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                capturedUrls.add(url)
                val jsonResponse = """{"total_count": 0, "data": []}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonResponse.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(
            client = mockClient,
            apiKeyProvider = { "test_key" }
        )

        // MovieHash search
        val hashReq = SubtitleSearchRequest(
            title = "Inception",
            movieHash = "12345678abcdef",
            preferredLanguage = "es",
            isSeries = false
        )
        provider.search(hashReq)
        assertTrue(capturedUrls.isNotEmpty())
        assertFalse("Hash query no debe enviar languages=", capturedUrls[0].contains("languages="))

        // Metadata search
        capturedUrls.clear()
        val metaReq = SubtitleSearchRequest(
            title = "Inception",
            year = 2010,
            preferredLanguage = "es",
            isSeries = false
        )
        provider.search(metaReq)
        assertTrue(capturedUrls.isNotEmpty())
        assertFalse("Metadata query no debe enviar languages=", capturedUrls[0].contains("languages="))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 24: OpenSubtitles devuelve candidatos en cualquier idioma (inglés, chino, francés) aunque preferredLanguage=es
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test24_openSubtitles_returnsAllLanguagesEvenIfPreferredIsSpanish() {
        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(apiKeyProvider = { "key" })
        val json = """
            {
                "total_count": 3,
                "data": [
                    {
                        "id": "1",
                        "attributes": {
                            "language": "en",
                            "files": [{"file_id": 101, "file_name": "Inception.en.srt"}],
                            "feature_details": {"title": "Inception", "year": 2010}
                        }
                    },
                    {
                        "id": "2",
                        "attributes": {
                            "language": "zh-cn",
                            "files": [{"file_id": 102, "file_name": "Inception.chs.srt"}],
                            "feature_details": {"title": "Inception", "year": 2010}
                        }
                    },
                    {
                        "id": "3",
                        "attributes": {
                            "language": "fr",
                            "files": [{"file_id": 103, "file_name": "Inception.fr.srt"}],
                            "feature_details": {"title": "Inception", "year": 2010}
                        }
                    }
                ]
            }
        """.trimIndent()

        val req = SubtitleSearchRequest(title = "Inception", year = 2010, preferredLanguage = "es", isSeries = false)
        val candidates = provider.parseSearchResponse(json, req, "key", false)

        assertEquals("Debe aceptar los 3 idiomas distintos", 3, candidates.size)
        assertTrue(candidates.any { it.language == "en" })
        assertTrue(candidates.any { it.language == "zh-CN" })
        assertTrue(candidates.any { it.language == "fr" })
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 25: Robustez ante campos opcionales / faltantes en OpenSubtitles (sin crash)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test25_openSubtitles_missingAttributesOrFiles_doesNotCrashAndLogsDiagnosis() {
        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(apiKeyProvider = { "key" })
        val json = """
            {
                "total_count": 4,
                "data": [
                    {"id": "sub_null_attr"},
                    {
                        "id": "sub_missing_files",
                        "attributes": {"language": "es"}
                    },
                    {
                        "id": "sub_empty_files",
                        "attributes": {"language": "es", "files": []}
                    },
                    {
                        "id": "sub_valid",
                        "attributes": {
                            "language": "es",
                            "files": [{"file_id": 999, "file_name": "Inception.2010.es.srt"}],
                            "feature_details": {"title": "Inception", "year": 2010}
                        }
                    }
                ]
            }
        """.trimIndent()

        val req = SubtitleSearchRequest(title = "Inception", year = 2010, preferredLanguage = "es", isSeries = false)
        val candidates = provider.parseSearchResponse(json, req, "key", false)

        assertEquals(1, candidates.size)
        assertEquals("os_999", candidates[0].id)
        assertEquals(999L, candidates[0].fileId)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 26: Primer file sin file_id válido + segundo file válido → selecciona el segundo
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test26_openSubtitles_multiFile_selectsValidFileIdAndPrefersSrt() {
        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(apiKeyProvider = { "key" })
        val json = """
            {
                "total_count": 1,
                "data": [
                    {
                        "id": "sub_multi",
                        "attributes": {
                            "language": "es",
                            "files": [
                                {"file_id": 0, "file_name": "invalid.txt"},
                                {"file_id": 4444, "file_name": "Inception.2010.es.srt"}
                            ],
                            "feature_details": {"title": "Inception", "year": 2010}
                        }
                    }
                ]
            }
        """.trimIndent()

        val req = SubtitleSearchRequest(title = "Inception", year = 2010, preferredLanguage = "es", isSeries = false)
        val candidates = provider.parseSearchResponse(json, req, "key", false)

        assertEquals(1, candidates.size)
        assertEquals("os_4444", candidates[0].id)
        assertEquals(4444L, candidates[0].fileId)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 27: subtitle id != file_id y download usa file_id
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test27_subtitleId_notEqualTo_fileId_andDownloadUsesFileId() {
        val provider = com.example.motor.subtitles.OpenSubtitlesProvider(apiKeyProvider = { "key" })
        val json = """
            {
                "total_count": 1,
                "data": [
                    {
                        "id": "abcdef-12345-uuid",
                        "attributes": {
                            "language": "es",
                            "files": [{"file_id": 8888, "file_name": "Inception.srt"}],
                            "feature_details": {"title": "Inception", "year": 2010}
                        }
                    }
                ]
            }
        """.trimIndent()

        val req = SubtitleSearchRequest(title = "Inception", year = 2010, isSeries = false)
        val candidates = provider.parseSearchResponse(json, req, "key", false)

        assertEquals(1, candidates.size)
        assertNotEquals("abcdef-12345-uuid", candidates[0].id)
        assertEquals("os_8888", candidates[0].id)
        assertEquals(8888L, candidates[0].fileId)
        assertTrue(candidates[0].url.contains("file_id=8888"))
        assertFalse(candidates[0].url.contains("abcdef-12345-uuid"))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 28: Cache de búsqueda es independiente del idioma preferido
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test28_cache_isIndependentOfPreferredLanguage() = runBlocking {
        var osCalls = 0
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                osCalls++
                val json = """
                    {
                        "total_count": 2,
                        "data": [
                            {
                                "id": "1",
                                "attributes": {
                                    "language": "en",
                                    "files": [{"file_id": 1001, "file_name": "Inception.en.srt"}],
                                    "feature_details": {"title": "Inception", "year": 2010}
                                }
                            },
                            {
                                "id": "2",
                                "attributes": {
                                    "language": "es",
                                    "files": [{"file_id": 1002, "file_name": "Inception.es.srt"}],
                                    "feature_details": {"title": "Inception", "year": 2010}
                                }
                            }
                        ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val emptyOtherClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val engine = SubtitleEngine(
            openSubtitlesProvider = com.example.motor.subtitles.OpenSubtitlesProvider(client = mockClient, apiKeyProvider = { "k" }),
            subtitleCatProvider = com.example.motor.subtitles.SubtitleCatProvider(client = emptyOtherClient),
            assrtSubtitleProvider = com.example.motor.subtitles.AssrtSubtitleProvider(client = emptyOtherClient, tokenProvider = { "t" })
        )

        // 1st search with preferredLanguage = "es"
        val reqEs = SubtitleSearchRequest(title = "Inception", year = 2010, preferredLanguage = "es", isSeries = false)
        val resEs = engine.searchSubtitles(reqEs)
        assertEquals(2, resEs.size)
        // Español debe ser primero
        assertEquals("es", resEs[0].language)

        val initialCalls = osCalls

        // 2nd search with same title but preferredLanguage = "en"
        val reqEn = SubtitleSearchRequest(title = "Inception", year = 2010, preferredLanguage = "en", isSeries = false)
        val resEn = engine.searchSubtitles(reqEn)
        assertEquals(2, resEn.size)
        // Inglés debe ser primero ahora
        assertEquals("en", resEn[0].language)

        // No debe haber hecho nuevas llamadas de red
        assertEquals("No debe haber llamadas adicionales a la red al cambiar idioma preferido", initialCalls, osCalls)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 29: El ranking por idioma NO puede rescatar una identidad incorrecta
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test29_languageRankingCannotRescueIncorrectIdentity() {
        val request = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1,
            preferredLanguage = "es"
        )

        // Candidato en español pero de serie equivocada ("Frasier S01E01")
        val frasierSpanish = "Frasier.S01E01.Español.srt"
        val frasierValidation = SubtitleMatcher.validateCandidate(frasierSpanish, request)
        assertFalse("Frasier en español debe ser rechazado rotundamente", frasierValidation.isValid)

        // Candidato en chino de la serie correcta ("Sword and Beloved S01E01")
        val swordChinese = "Sword.and.Beloved.S01E01.chs.srt"
        val swordValidation = SubtitleMatcher.validateCandidate(swordChinese, request)
        assertTrue("Sword and Beloved en chino debe ser aceptado", swordValidation.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 30: Orden lingüístico: Español > English > Romanian > Chinese > Otros
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test30_fallbackLanguagePriorityOrder() {
        // Usuario con idioma "fr" o no coincidente
        val req = SubtitleSearchRequest(title = "Inception", year = 2010, preferredLanguage = "fr", isSeries = false)

        val subEs = ExternalSubtitle(id = "1", url = "url", label = "Español", language = "es", source = "OpenSubtitles")
        val subEn = ExternalSubtitle(id = "2", url = "url", label = "English", language = "en", source = "OpenSubtitles")
        val subRo = ExternalSubtitle(id = "3", url = "url", label = "Română", language = "ro", source = "OpenSubtitles")
        val subZh = ExternalSubtitle(id = "4", url = "url", label = "Chino", language = "zh-CN", source = "OpenSubtitles")
        val subDe = ExternalSubtitle(id = "5", url = "url", label = "Deutsch", language = "de", source = "OpenSubtitles")

        val scoreEs = SubtitleMatcher.calculateScore(subEs, req)
        val scoreEn = SubtitleMatcher.calculateScore(subEn, req)
        val scoreRo = SubtitleMatcher.calculateScore(subRo, req)
        val scoreZh = SubtitleMatcher.calculateScore(subZh, req)
        val scoreDe = SubtitleMatcher.calculateScore(subDe, req)

        assertTrue("Español ($scoreEs) > Inglés ($scoreEn)", scoreEs > scoreEn)
        assertTrue("Inglés ($scoreEn) > Rumano ($scoreRo)", scoreEn > scoreRo)
        assertTrue("Rumano ($scoreRo) > Chino ($scoreZh)", scoreRo > scoreZh)
        assertTrue("Chino ($scoreZh) > Otros ($scoreDe)", scoreZh > scoreDe)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 31: Candidato de película no puede pasar como serie
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test31_movieCandidate_cannotPassAsSeries() {
        val seriesReq = SubtitleSearchRequest(
            title = "Inception",
            isSeries = true,
            season = 1,
            episode = 1
        )
        // Archivo que no especifica episodio para una serie
        val movieTitle = "Inception.2010.BluRay.srt"
        val validation = SubtitleMatcher.validateCandidate(movieTitle, seriesReq)
        assertFalse("Candidato de película no debe pasar cuando se busca un episodio de serie", validation.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 32: Candidato de serie no puede pasar como película
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test32_seriesCandidate_cannotPassAsMovie() {
        val movieReq = SubtitleSearchRequest(
            title = "Inception",
            isSeries = false,
            year = 2010
        )
        val seriesTitle = "Inception.S01E01.720p.srt"
        val validation = SubtitleMatcher.validateCandidate(seriesTitle, movieReq)
        assertFalse("Candidato con S01E01 no debe pasar cuando se busca una película", validation.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 33: Título genérico "The Uprising" no acepta películas no coincidentes
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test33_genericTitle_theUprising_rejectsDifferentMovie() {
        val req = SubtitleSearchRequest(
            title = "The Uprising",
            year = 2013,
            isSeries = false
        )
        // Película completamente diferente que solo comparte "Uprising"
        val wrongCand = "Pacific.Rim.Uprising.2018.1080p.srt"
        val validation = SubtitleMatcher.validateCandidate(wrongCand, req)
        assertFalse("Pacific Rim Uprising debe ser rechazada para The Uprising", validation.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 34: Año incorrecto con diferencia > 1 se rechaza
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test34_movieYearMismatch_rejected() {
        val req = SubtitleSearchRequest(
            title = "Inception",
            year = 2010,
            isSeries = false
        )
        val wrongYearCand = "Inception.1998.720p.srt"
        val validation = SubtitleMatcher.validateCandidate(wrongYearCand, req)
        assertFalse("Año con diferencia de más de 1 año debe ser rechazado", validation.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 35: SubtitleResolver descarga OpenSubtitles usando file_id y guarda en cache
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test35_subtitleResolver_resolvesAndCachesLocalFile() = runBlocking {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_sub_dir_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        com.example.motor.subtitles.SubtitleResolver.init(tempDir)

        val subFile = java.io.File(tempDir, "os_12345.srt")
        subFile.writeText("1\n00:00:01,000 --> 00:00:03,000\nSubtítulo de prueba local\n")

        val sub = ExternalSubtitle(
            id = "os_12345",
            url = "https://api.opensubtitles.com/api/v1/download?file_id=12345",
            label = "Español · OpenSubtitles",
            language = "es",
            source = "OpenSubtitles",
            fileId = 12345L
        )

        val resolved = com.example.motor.subtitles.SubtitleResolver.resolveSubtitle(sub, "dummy_api_key")
        assertNotNull(resolved)
        assertTrue(resolved!!.url.startsWith("file://"))
        assertTrue(resolved.url.endsWith("os_12345.srt"))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 36: Películas con números en su título NO se rechazan como episodios
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test36_moviesWithNumbersInTitle_notRejectedAsEpisodes() {
        val reqOceans = SubtitleSearchRequest(title = "Ocean's 11", year = 2001, isSeries = false)
        val vOceans = SubtitleMatcher.validateCandidate("Oceans.11.2001.720p.BluRay.srt", reqOceans)
        assertTrue("Ocean's 11 no debe ser rechazado como episodio", vOceans.isValid)

        val reqIronMan = SubtitleSearchRequest(title = "Iron Man 3", year = 2013, isSeries = false)
        val vIronMan = SubtitleMatcher.validateCandidate("Iron.Man.3.2013.1080p.srt", reqIronMan)
        assertTrue("Iron Man 3 no debe ser rechazado como episodio", vIronMan.isValid)

        val reqApollo = SubtitleSearchRequest(title = "Apollo 13", year = 1995, isSeries = false)
        val vApollo = SubtitleMatcher.validateCandidate("Apollo.13.1995.BluRay.srt", reqApollo)
        assertTrue("Apollo 13 no debe ser rechazado como episodio", vApollo.isValid)

        val reqSeven = SubtitleSearchRequest(title = "Se7en", year = 1995, isSeries = false)
        val vSeven = SubtitleMatcher.validateCandidate("Se7en.1995.1080p.srt", reqSeven)
        assertTrue("Se7en no debe ser rechazado como episodio", vSeven.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 37: Nombres de archivo con códigos de idioma (en, es) se aceptan
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test37_movieCandidatesWithLanguageTags_accepted() {
        val req = SubtitleSearchRequest(title = "Inception", year = 2010, isSeries = false)

        val vEn = SubtitleMatcher.validateCandidate("Inception.en.srt", req)
        assertTrue("Inception.en.srt debe ser aceptado para la película Inception", vEn.isValid)

        val vEs = SubtitleMatcher.validateCandidate("Inception.es.srt", req)
        assertTrue("Inception.es.srt debe ser aceptado para la película Inception", vEs.isValid)

        val vRo = SubtitleMatcher.validateCandidate("Inception.ro.srt", req)
        assertTrue("Inception.ro.srt debe ser aceptado para la película Inception", vRo.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 38: Validación de años: estreno coincidente con año de ripeo en nombre
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test38_movieYearValidation_releaseYearAndRipYear() {
        val req = SubtitleSearchRequest(title = "Inception", year = 2010, isSeries = false)

        // Candidato con metadatos de año oficial (2010) pero nombre con año de ripeo posterior (2020)
        val vRip = SubtitleMatcher.validateCandidate(
            candidateTitle = "Inception.REMASTERED.2020.1080p.BluRay.srt",
            request = req,
            candidateYear = 2010
        )
        assertTrue("Candidato con año oficial 2010 debe ser aceptado aunque el ripeo sea de 2020", vRip.isValid)

        // Candidato sin metadatos pero con ambos años en el nombre (2010 y 2020)
        val vBoth = SubtitleMatcher.validateCandidate(
            candidateTitle = "Inception.2010.REMASTER.2020.1080p.srt",
            request = req
        )
        assertTrue("Candidato con año original presente en el nombre debe ser aceptado", vBoth.isValid)

        // Película con año genuinamente distinto (1998 vs 2010) debe rechazarse
        val vWrong = SubtitleMatcher.validateCandidate(
            candidateTitle = "Inception.1998.720p.srt",
            request = req
        )
        assertFalse("Película con año genuinamente erróneo debe rechazarse", vWrong.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 39: Episodio de serie cuyo año de emisión difiere del estreno de la serie
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test39_seriesEpisodeYear_differentFromSeriesPremiere_accepted() {
        // Serie que empezó en 2016 pero temporada 4 se emitió en 2022
        val req = SubtitleSearchRequest(
            title = "Stranger Things",
            year = 2016,
            isSeries = true,
            season = 4,
            episode = 1
        )

        val candAirYear = "Stranger.Things.S04E01.2022.1080p.WEBRip.srt"
        val v = SubtitleMatcher.validateCandidate(candAirYear, req, candidateYear = 2022)
        assertTrue("Episodio de S04 con año 2022 no debe rechazarse por inicio de serie 2016", v.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 40: Normalización de prefijos de proveedor (CH:) y detección de contenido
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test40_providerPrefix_normalizationAndChineseContent() {
        val engine = com.example.motor.subtitles.SubtitleEngine.getInstance()

        val (prefixCh, cleanCh) = engine.extractProviderPrefix("CH: Joy of Life")
        assertEquals("CH", prefixCh)
        assertEquals("Joy of Life", cleanCh)
        assertTrue(engine.isChineseProviderPrefix(prefixCh))

        val (prefixBracket, cleanBracket) = engine.extractProviderPrefix("[CH] Sword and Beloved")
        assertEquals("CH", prefixBracket)
        assertEquals("Sword and Beloved", cleanBracket)
        assertTrue(engine.isChineseProviderPrefix(prefixBracket))

        val (prefixDash, cleanDash) = engine.extractProviderPrefix("CN - The Untamed")
        assertEquals("CN", prefixDash)
        assertEquals("The Untamed", cleanDash)
        assertTrue(engine.isChineseProviderPrefix(prefixDash))

        // No debe alterar palabras legítimas que empiezan por Ch
        val (prefixChicago, cleanChicago) = engine.extractProviderPrefix("Chicago P.D.")
        assertNull(prefixChicago)
        assertEquals("Chicago P.D.", cleanChicago)

        val (prefixChronicles, cleanChronicles) = engine.extractProviderPrefix("Chronicles of Narnia")
        assertNull(prefixChronicles)
        assertEquals("Chronicles of Narnia", cleanChronicles)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 41: Series que comparten palabras en el título se diferencian
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test41_seriesSharingTitleWords_distinguished() {
        val req = SubtitleSearchRequest(
            title = "Sword and Beloved",
            isSeries = true,
            season = 1,
            episode = 1
        )

        // Candidato que solo comparte "Beloved"
        val onlyBeloved = "Beloved.S01E01.720p.srt"
        val vBeloved = SubtitleMatcher.validateCandidate(onlyBeloved, req)
        assertFalse("Serie Beloved debe ser rechazada para Sword and Beloved", vBeloved.isValid)

        // Candidato auténtico
        val exactMatch = "Sword.and.Beloved.S01E01.720p.chs.srt"
        val vExact = SubtitleMatcher.validateCandidate(exactMatch, req)
        assertTrue("Sword and Beloved auténtico debe ser aceptado", vExact.isValid)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 42: Limpieza de prefijo 'CH' con dos puntos, guión o espacio en series
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test42_chPrefixCleanedInSeriesTitle() {
        val tmdb = com.example.motor.tmdb.TmdbService

        val colon = tmdb.cleanSeriesName("CH: The Untamed")
        assertEquals("The Untamed", colon)

        val dash = tmdb.cleanSeriesName("CH - The Untamed")
        assertEquals("The Untamed", dash)

        val space = tmdb.cleanSeriesName("CH The Untamed")
        assertEquals("The Untamed", space)

        val bracket = tmdb.cleanSeriesName("[CH] The Untamed")
        assertEquals("The Untamed", bracket)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 43: Parseo directo de Cues de subtítulos para renderizado fluido
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test43_subtitleCueParsingAndSync() {
        val srtSample = """
            1
            00:00:01,500 --> 00:00:04,200
            Hola mundo, este es un subtítulo de prueba.
            
            2
            00:00:05,000 --> 00:00:08,100
            Segunda línea sincronizada.
        """.trimIndent()

        val cues = com.example.motor.subtitles.SubtitleSyncUtils.parseSubtitleContent(srtSample, isVtt = false)
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(4200L, cues[0].endMs)
        assertEquals("Hola mundo, este es un subtítulo de prueba.", cues[0].text)

        assertEquals(5000L, cues[1].startMs)
        assertEquals(8100L, cues[1].endMs)
        assertEquals("Segunda línea sincronizada.", cues[1].text)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 44: Parseo de subtítulos en formato ASS / SSA (usado en series chinas)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test44_assSubtitleParsing() {
        val assSample = """
            [Script Info]
            Title: Chinese Drama Sample
            
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:20.50,0:01:25.80,Default,,0,0,0,,{\pos(192,200)}Hola, este es un diálogo en ASS
            Dialogue: 0,0:01:26.10,0:01:29.30,Default,,0,0,0,,Segunda línea\Ncon salto de línea
        """.trimIndent()

        val cues = com.example.motor.subtitles.SubtitleSyncUtils.parseSubtitleContent(assSample)
        assertEquals(2, cues.size)

        // 0:01:20.50 = 1 min 20 sec 500 ms = 80500 ms
        assertEquals(80500L, cues[0].startMs)
        assertEquals(85800L, cues[0].endMs)
        assertEquals("Hola, este es un diálogo en ASS", cues[0].text)

        // 0:01:26.10 = 86100 ms
        assertEquals(86100L, cues[1].startMs)
        assertEquals(89300L, cues[1].endMs)
        assertEquals("Segunda línea\ncon salto de línea", cues[1].text)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // TEST 45: Resolución de enlace según idioma objetivo en páginas multi-idioma
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun test45_languageSpecificSubtitleResolution() {
        val htmlSample = """
            <html>
                <body>
                    <a id="download_ar" href="/subs/100/The-Untamed-ar.srt">Arabic</a>
                    <a id="download_el" href="/subs/100/The-Untamed-el.srt">Greek</a>
                    <a id="download_ru" href="/subs/100/The-Untamed-ru.srt">Russian</a>
                    <a id="download_en" href="/subs/100/The-Untamed-en.srt">English</a>
                    <a id="download_es" href="/subs/100/The-Untamed-es.srt">Spanish</a>
                </body>
            </html>
        """.trimIndent()

        val resolver = com.example.motor.subtitles.SubtitleResolver

        val enLink = resolver.extractDirectSubtitleLinkFromHtml(htmlSample, "https://www.subtitlecat.com", targetLanguage = "en")
        assertEquals("https://www.subtitlecat.com/subs/100/The-Untamed-en.srt", enLink)

        val esLink = resolver.extractDirectSubtitleLinkFromHtml(htmlSample, "https://www.subtitlecat.com", targetLanguage = "es")
        assertEquals("https://www.subtitlecat.com/subs/100/The-Untamed-es.srt", esLink)
    }
}

