package com.example.motor.sports

import com.example.motor.db.IptvItem
import com.example.motor.sports.broadcast.TheSportsDbBroadcastResolver
import com.example.motor.sports.broadcast.TheSportsDbSearchStatus
import com.example.motor.sports.matcher.DefaultSportsChannelMatcher
import com.example.motor.sports.matcher.MatchConfidence
import com.example.motor.sports.matcher.MatchDecision
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TheSportsDbBroadcastResolverTest {

    private val matcher = DefaultSportsChannelMatcher()

    private fun createSampleEvent(
        home: String = "Real Madrid",
        away: String = "Barcelona",
        league: String = "LaLiga",
        sport: String = "football",
        date: LocalDate = LocalDate.of(2026, 10, 1),
        broadcasters: List<String> = emptyList()
    ): SportsEvent {
        val startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SportsEvent(
            id = "test_event_1",
            sport = sport,
            league = league,
            homeTeam = home,
            awayTeam = away,
            startTime = startTime,
            status = SportsEventStatus.SCHEDULED,
            broadcaster = broadcasters.firstOrNull(),
            broadcastChannels = broadcasters
        )
    }

    private fun createDispatcherMockClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun createJsonResponse(request: Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val VALID_SEARCH_JSON = """
    {
      "event": [
        {
          "idEvent": "584911",
          "strEvent": "Real Madrid vs Barcelona",
          "strSport": "Soccer",
          "strLeague": "Spanish La Liga",
          "strHomeTeam": "Real Madrid",
          "strAwayTeam": "Barcelona",
          "dateEvent": "2026-10-01"
        }
      ]
    }
    """.trimIndent()

    private val VALID_TV_TWO_CHANNELS_JSON = """
    {
      "tvevent": [
        {
          "id": "1",
          "idEvent": "584911",
          "strChannel": "DAZN LaLiga",
          "strCountry": "Spain"
        },
        {
          "id": "2",
          "idEvent": "584911",
          "strChannel": "Movistar LaLiga",
          "strCountry": "Spain"
        },
        {
          "id": "3",
          "idEvent": "584911",
          "strChannel": "Third Channel Ignored",
          "strCountry": "Spain"
        }
      ]
    }
    """.trimIndent()

    private val VALID_TV_ONE_CHANNEL_JSON = """
    {
      "tvevent": [
        {
          "id": "1",
          "idEvent": "584911",
          "strChannel": "DAZN LaLiga",
          "strCountry": "Spain"
        }
      ]
    }
    """.trimIndent()

    private val EMPTY_SEARCH_JSON = """
    {
      "event": null
    }
    """.trimIndent()

    private val EMPTY_TV_JSON = """
    {
      "tvevent": null
    }
    """.trimIndent()

    @Before
    fun setup() {
        TheSportsDbBroadcastResolver.getInstance().clearCache()
    }

    // 1. Construcción correcta de búsqueda del evento
    @Test
    fun test01_searchQueryConstruction() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = createDispatcherMockClient { req ->
            requestedUrls.add(req.url.toString())
            if (req.url.encodedPath.contains("searchevents.php")) {
                createJsonResponse(req, 200, VALID_SEARCH_JSON)
            } else {
                createJsonResponse(req, 200, VALID_TV_ONE_CHANNEL_JSON)
            }
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent(home = "Arsenal", away = "Chelsea", date = LocalDate.of(2026, 10, 1))

        resolver.resolveBroadcastInfo(event)

        assertTrue("Debe registrar peticiones HTTP", requestedUrls.isNotEmpty())
        val searchUrl = requestedUrls.first()
        assertTrue("La URL debe contener la consulta de búsqueda e=Arsenal_vs_Chelsea", searchUrl.contains("e=Arsenal_vs_Chelsea"))
        assertTrue("La URL debe contener la fecha d=2026-10-01", searchUrl.contains("d=2026-10-01"))
        assertTrue("Debe usar la base de TheSportsDB Free V1", searchUrl.startsWith("https://www.thesportsdb.com/api/v1/json/123/"))
    }

    // 2. Parseo correcto de respuesta TheSportsDB
    @Test
    fun test02_parseEventsResponseCorrectly() {
        val resolver = TheSportsDbBroadcastResolver()
        val event = createSampleEvent(home = "Real Madrid", away = "Barcelona", date = LocalDate.of(2026, 10, 1))
        val matched = resolver.parseAndValidateEventsResponse(VALID_SEARCH_JSON, event)

        assertNotNull(matched)
        assertEquals("584911", matched?.optString("idEvent"))
        assertEquals("Real Madrid vs Barcelona", matched?.optString("strEvent"))
    }

    // 3. Extracción de idEvent
    @Test
    fun test03_extractIdEvent() = runBlocking {
        var requestedTvUrl = ""
        val client = createDispatcherMockClient { req ->
            if (req.url.encodedPath.contains("searchevents.php")) {
                createJsonResponse(req, 200, VALID_SEARCH_JSON)
            } else {
                requestedTvUrl = req.url.toString()
                createJsonResponse(req, 200, VALID_TV_ONE_CHANNEL_JSON)
            }
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent()

        val result = resolver.resolveBroadcastInfo(event)

        assertNotNull(result)
        assertEquals("584911", result?.eventId)
        assertTrue(requestedTvUrl.contains("lookuptv.php?id=584911"))
    }

    // 4. Extracción de canales TV
    @Test
    fun test04_extractTvChannels() {
        val resolver = TheSportsDbBroadcastResolver()
        val channels = resolver.parseTvResponse(VALID_TV_ONE_CHANNEL_JSON)

        assertEquals(1, channels.size)
        assertEquals("DAZN LaLiga", channels.first())
    }

    // 5. Respuesta con dos canales (devuelve hasta 2 canales en Free)
    @Test
    fun test05_responseWithTwoChannels() {
        val resolver = TheSportsDbBroadcastResolver()
        val channels = resolver.parseTvResponse(VALID_TV_TWO_CHANNELS_JSON)

        assertEquals(2, channels.size)
        assertEquals("DAZN LaLiga", channels[0])
        assertEquals("Movistar LaLiga", channels[1])
        assertFalse("No debe exceder el límite de 2 canales", channels.contains("Third Channel Ignored"))
    }

    // 6. Evento no encontrado
    @Test
    fun test06_eventNotFound() = runBlocking {
        val client = createDispatcherMockClient { req ->
            createJsonResponse(req, 200, EMPTY_SEARCH_JSON)
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent()

        val result = resolver.resolveBroadcastInfo(event)
        assertNull(result)

        val cached = resolver.getCachedEntry(event)
        assertNotNull(cached)
        assertEquals(TheSportsDbSearchStatus.NO_EVENT, cached?.status)
    }

    // 7. Evento encontrado sin TV
    @Test
    fun test07_eventFoundWithoutTv() = runBlocking {
        val client = createDispatcherMockClient { req ->
            if (req.url.encodedPath.contains("searchevents.php")) {
                createJsonResponse(req, 200, VALID_SEARCH_JSON)
            } else {
                createJsonResponse(req, 200, EMPTY_TV_JSON)
            }
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent()

        val result = resolver.resolveBroadcastInfo(event)
        assertNull(result)

        val cached = resolver.getCachedEntry(event)
        assertNotNull(cached)
        assertEquals(TheSportsDbSearchStatus.NO_TV, cached?.status)
    }

    // 8. Error HTTP (ej. 429 o 500 no rompe y hace fallback)
    @Test
    fun test08_httpErrorHandlesGracefully() = runBlocking {
        val client = createDispatcherMockClient { req ->
            createJsonResponse(req, 429, "{\"message\": \"Too Many Requests\"}")
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent()

        val result = resolver.resolveBroadcastInfo(event)
        assertNull(result)

        val cached = resolver.getCachedEntry(event)
        assertNotNull(cached)
        assertEquals(TheSportsDbSearchStatus.ERROR, cached?.status)
    }

    // 9. Timeout / Error de red maneja gracefully
    @Test
    fun test09_timeoutHandlesGracefully() = runBlocking {
        val client = createDispatcherMockClient {
            throw SocketTimeoutException("Read timed out")
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent()

        val result = resolver.resolveBroadcastInfo(event)
        assertNull(result)

        val cached = resolver.getCachedEntry(event)
        assertNotNull(cached)
        assertEquals(TheSportsDbSearchStatus.ERROR, cached?.status)
    }

    // 10. Cache hit evita llamadas duplicadas a la red
    @Test
    fun test10_cacheHitAvoidsRepeatedNetworkCalls() = runBlocking {
        var callCount = 0
        val client = createDispatcherMockClient { req ->
            callCount++
            if (req.url.encodedPath.contains("searchevents.php")) {
                createJsonResponse(req, 200, VALID_SEARCH_JSON)
            } else {
                createJsonResponse(req, 200, VALID_TV_TWO_CHANNELS_JSON)
            }
        }
        val resolver = TheSportsDbBroadcastResolver(client)
        val event = createSampleEvent()

        val first = resolver.resolveBroadcastInfo(event)
        assertEquals(2, first?.channels?.size)
        val callsAfterFirst = callCount

        val second = resolver.resolveBroadcastInfo(event)
        assertEquals(2, second?.channels?.size)
        assertEquals("No debe haber realizado más llamadas HTTP tras el cache hit", callsAfterFirst, callCount)
    }

    // 11. Normalización: "DAZN LaLiga" vs "[ES] DAZN LALIGA HD" produce coincidencia fuerte
    @Test
    fun test11_normalizationMatchesDaznLaLigaVariants() = runBlocking {
        val event = createSampleEvent(broadcasters = listOf("DAZN LaLiga"))
        val channels = listOf(
            IptvItem(name = "[ES] DAZN LALIGA HD", group = "DEPORTES", url = "http://dazn.m3u8"),
            IptvItem(name = "Canal Cocina", group = "VARIOS", url = "http://cocina.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertEquals("[ES] DAZN LALIGA HD", result.topMatch?.channel?.name)
        assertTrue(result.topMatch?.score ?: 0 >= 75)
        assertTrue(result.topMatch?.reasons?.any { it.contains("BROADCASTER_VERIFIED_BY_THESPORTSDB") } == true)
    }

    // 12. No confundir "DAZN LaLiga" con un canal genérico "DAZN 1" ni "DAZN LaLiga 2"
    @Test
    fun test12_doNotConfuseDaznLaLigaWithDazn1OrDaznLaLiga2() {
        val target = "DAZN LaLiga"

        // Coincidencias válidas
        assertTrue(matcher.matchBroadcasterPrecise("[ES] DAZN LALIGA HD", "[ES] DAZN LALIGA HD", target))
        assertTrue(matcher.matchBroadcasterPrecise("DAZN LaLiga FHD", "DAZN LaLiga FHD", target))
        assertTrue(matcher.matchBroadcasterPrecise("ESP - DAZN LALIGA", "ESP - DAZN LALIGA", target))
        assertTrue(matcher.matchBroadcasterPrecise("DAZN LALIGA 4K", "DAZN LALIGA 4K", target))

        // Falsos positivos que deben ser rechazados estrictamente
        assertFalse("DAZN no debe coincidir con DAZN LaLiga", matcher.matchBroadcasterPrecise("DAZN", "DAZN", target))
        assertFalse("DAZN 1 no debe coincidir con DAZN LaLiga", matcher.matchBroadcasterPrecise("DAZN 1", "DAZN 1", target))
        assertFalse("DAZN 2 no debe coincidir con DAZN LaLiga", matcher.matchBroadcasterPrecise("DAZN 2", "DAZN 2", target))
        assertFalse("DAZN LaLiga 2 no debe coincidir con DAZN LaLiga", matcher.matchBroadcasterPrecise("DAZN LaLiga 2", "DAZN LaLiga 2", target))
    }

    // 13. Fallback al matcher actual cuando TheSportsDB falla o no devuelve datos
    @Test
    fun test13_fallbackToDefaultMatcherWhenTheSportsDbFails() = runBlocking {
        // Evento sin broadcasters verificados (como cuando TheSportsDB devuelve null)
        val eventWithoutBroadcaster = createSampleEvent(broadcasters = emptyList())
        val channels = listOf(
            IptvItem(name = "M+ LaLiga | Real Madrid vs Barcelona", group = "DEPORTES", url = "http://match.m3u8"),
            IptvItem(name = "Canal Noticias 24H", group = "NOTICIAS", url = "http://news.m3u8")
        )

        val result = matcher.matchChannel(eventWithoutBroadcaster, channels)

        // El matcher existente debe seguir funcionando por equipos y competición
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertEquals("M+ LaLiga | Real Madrid vs Barcelona", result.topMatch?.channel?.name)
    }

    // 14. El comportamiento actual de SportsChannelMatcher sigue pasando sus tests existentes
    @Test
    fun test14_existingMatcherBehaviorPreserved() = runBlocking {
        val event = SportsEvent(
            id = "test_fb_legacy",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Real Madrid",
            awayTeam = "Barcelona",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.LIVE
        )
        val channels = listOf(
            IptvItem(name = "EVENTOS | Real Madrid vs Barcelona", group = "DEPORTES", url = "http://stream.m3u8"),
            IptvItem(name = "Canal Cine", group = "CINE", url = "http://cine.m3u8")
        )

        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertEquals("EVENTOS | Real Madrid vs Barcelona", result.topMatch?.channel?.name)
    }

    // 15. SportsViewModel puede ser creado mediante ViewModelProvider.AndroidViewModelFactory sin NoSuchMethodException
    @Test
    fun test15_sportsViewModelCanBeCreatedByAndroidViewModelFactory() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val viewModel = factory.create(com.example.interfaz.viewmodel.SportsViewModel::class.java)

        assertNotNull(viewModel)
        assertNotNull(viewModel.uiState.value)
    }
}
