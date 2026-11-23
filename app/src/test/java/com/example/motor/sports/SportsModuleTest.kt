package com.example.motor.sports

import com.example.interfaz.viewmodel.SportsUiState
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.parser.FootballSportsParser
import com.example.motor.sports.remote.ApiSportsRemoteDataSource
import com.example.motor.sports.remote.SportsRemoteDataSource
import com.example.motor.sports.repository.SportsRepository
import com.example.motor.sports.matcher.*
import com.example.motor.db.IptvItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SportsModuleTest {

    // ─────────────────────────────────────────────────────────────────────────
    // FIXTURE JSON REALISTA DE API-FOOTBALL (v3.football.api-sports.io)
    // ─────────────────────────────────────────────────────────────────────────
    private val REALISTIC_FOOTBALL_LIVE_JSON = """
    {
      "get": "fixtures",
      "parameters": { "live": "all" },
      "errors": [],
      "results": 1,
      "response": [
        {
          "fixture": {
            "id": 1035084,
            "referee": "C. Soto",
            "timezone": "UTC",
            "date": "2026-09-29T18:00:00+00:00",
            "timestamp": 1727632800,
            "status": {
              "long": "Second Half",
              "short": "2H",
              "elapsed": 68
            }
          },
          "league": {
            "id": 140,
            "name": "La Liga",
            "country": "Spain",
            "logo": "https://media.api-sports.io/football/leagues/140.png"
          },
          "teams": {
            "home": {
              "id": 541,
              "name": "Real Madrid",
              "logo": "https://media.api-sports.io/football/teams/541.png"
            },
            "away": {
              "id": 529,
              "name": "Barcelona",
              "logo": "https://media.api-sports.io/football/teams/529.png"
            }
          },
          "goals": {
            "home": 2,
            "away": 1
          }
        }
      ]
    }
    """.trimIndent()

    private val REALISTIC_FOOTBALL_FINISHED_JSON = """
    {
      "get": "fixtures",
      "response": [
        {
          "fixture": {
            "id": 99901,
            "timestamp": 1727600000,
            "status": { "long": "Match Finished", "short": "FT", "elapsed": 90 }
          },
          "league": { "id": 140, "name": "La Liga", "country": "Spain" },
          "teams": {
            "home": { "id": 10, "name": "Sevilla" },
            "away": { "id": 11, "name": "Betis" }
          },
          "goals": { "home": 1, "away": 1 }
        }
      ]
    }
    """.trimIndent()

    private val REALISTIC_FOOTBALL_CANCELLED_JSON = """
    {
      "get": "fixtures",
      "response": [
        {
          "fixture": {
            "id": 99902,
            "timestamp": 1727600000,
            "status": { "long": "Match Cancelled", "short": "CANC" }
          },
          "league": { "id": 140, "name": "La Liga" },
          "teams": {
            "home": { "name": "Valencia" },
            "away": { "name": "Villarreal" }
          },
          "goals": { "home": null, "away": null }
        }
      ]
    }
    """.trimIndent()

    private val REALISTIC_FOOTBALL_SCHEDULED_JSON = """
    {
      "get": "fixtures",
      "response": [
        {
          "fixture": {
            "id": 99903,
            "timestamp": 1727650000,
            "status": { "long": "Not Started", "short": "NS" }
          },
          "league": { "id": 140, "name": "La Liga" },
          "teams": {
            "home": { "name": "Atlético Madrid" },
            "away": { "name": "Athletic Club" }
          },
          "goals": { "home": null, "away": null }
        }
      ]
    }
    """.trimIndent()

    private val REALISTIC_FOOTBALL_POSTPONED_JSON = """
    {
      "get": "fixtures",
      "response": [
        {
          "fixture": {
            "id": 99904,
            "timestamp": 1727650000,
            "status": { "long": "Match Postponed", "short": "PST" }
          },
          "league": { "id": 140, "name": "La Liga" },
          "teams": {
            "home": { "name": "Mallorca" },
            "away": { "name": "Osasuna" }
          },
          "goals": { "home": null, "away": null }
        }
      ]
    }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Un evento FINISHED no aparece.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test1_finishedEventDoesNotAppear() {
        val events = FootballSportsParser.parse(REALISTIC_FOOTBALL_FINISHED_JSON, isLiveOnly = false)
        assertTrue("Los eventos finalizados (FT) deben ser excluidos", events.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Un evento CANCELLED no aparece.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test2_cancelledEventDoesNotAppear() {
        val events = FootballSportsParser.parse(REALISTIC_FOOTBALL_CANCELLED_JSON, isLiveOnly = false)
        assertTrue("Los eventos cancelados (CANC) deben ser excluidos", events.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Un evento LIVE se clasifica como LIVE.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test3_liveEventClassifiedAsLive() {
        val events = FootballSportsParser.parse(REALISTIC_FOOTBALL_LIVE_JSON, isLiveOnly = true)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(SportsEventStatus.LIVE, event.status)
        assertTrue(event.isLive)
        assertEquals(68, event.elapsedMinutes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Un evento SCHEDULED se clasifica como SCHEDULED.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test4_scheduledEventClassifiedAsScheduled() {
        val events = FootballSportsParser.parse(REALISTIC_FOOTBALL_SCHEDULED_JSON, isLiveOnly = false)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(SportsEventStatus.SCHEDULED, event.status)
        assertFalse(event.isLive)
        assertFalse(event.isFinished)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Un evento POSTPONED se conserva correctamente.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test5_postponedEventPreservedCorrectly() {
        val events = FootballSportsParser.parse(REALISTIC_FOOTBALL_POSTPONED_JSON, isLiveOnly = false)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(SportsEventStatus.POSTPONED, event.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Un evento de hoy se clasifica como TODAY.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test6_todayEventClassifiedAsToday() {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val event = SportsEvent(
            id = "today_1",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Equipo A",
            awayTeam = "Equipo B",
            startTime = now,
            status = SportsEventStatus.SCHEDULED
        )
        assertTrue("El evento debe clasificarse como Today", event.isToday(zone))
        assertFalse("El evento no debe ser Tomorrow", event.isTomorrow(zone))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Un evento de mañana se clasifica como TOMORROW.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test7_tomorrowEventClassifiedAsTomorrow() {
        val zone = ZoneId.systemDefault()
        val tomorrowMs = System.currentTimeMillis() + 86_400_000L
        val event = SportsEvent(
            id = "tom_1",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Equipo A",
            awayTeam = "Equipo B",
            startTime = tomorrowMs,
            status = SportsEventStatus.SCHEDULED
        )
        assertTrue("El evento debe clasificarse como Tomorrow", event.isTomorrow(zone))
        assertFalse("El evento de mañana no debe ser Today", event.isToday(zone))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Una respuesta API vacía produce lista vacía.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test8_emptyApiResponseProducesEmptyList() {
        val emptyJson = """{ "get": "fixtures", "results": 0, "response": [] }"""
        val events = FootballSportsParser.parse(emptyJson, isLiveOnly = false)
        assertTrue(events.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Un JSON inválido no genera eventos ficticios.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test9_invalidJsonDoesNotGenerateFictitiousEvents() {
        val invalidJson = """{ broken json :::: null """
        val events = FootballSportsParser.parse(invalidJson, isLiveOnly = false)
        assertTrue("JSON roto nunca debe generar datos ficticios", events.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Falta de API key no genera eventos ficticios.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test10_missingApiKeyDoesNotGenerateFictitiousEvents() = runBlocking {
        val remoteDataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "" })
        assertFalse(remoteDataSource.isApiKeyConfigured())

        val liveEvents = remoteDataSource.getLiveEvents(SportsSport.FOOTBALL)
        val dateEvents = remoteDataSource.getEventsByDate(SportsSport.FOOTBALL, LocalDate.now())

        assertTrue("Sin API key, getLiveEvents debe ser lista vacía", liveEvents.isEmpty())
        assertTrue("Sin API key, getEventsByDate debe ser lista vacía", dateEvents.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Error HTTP no genera eventos ficticios.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test11_httpErrorDoesNotGenerateFictitiousEvents() = runBlocking {
        // Fuente de datos simulando error HTTP o de red
        val failingRemoteDataSource = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> {
                throw IOException("HTTP 500 Internal Server Error")
            }
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> {
                throw IOException("HTTP 401 Unauthorized")
            }
        }

        val repository = SportsRepository(remoteDataSource = failingRemoteDataSource)
        val liveEvents = repository.getLiveEvents(SportsSport.FOOTBALL, forceRefresh = true)
        val todayEvents = repository.getTodayEvents(SportsSport.FOOTBALL, forceRefresh = true)

        assertTrue("Ante error HTTP sin caché previa, debe devolver lista vacía", liveEvents.isEmpty())
        assertTrue("Ante error HTTP sin caché previa, debe devolver lista vacía", todayEvents.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. Los eventos mantienen correctamente equipos, liga, logos, timestamp y marcador.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test12_eventsMaintainTeamsLeagueLogosTimestampAndScore() {
        val events = FootballSportsParser.parse(REALISTIC_FOOTBALL_LIVE_JSON, isLiveOnly = true)
        assertEquals(1, events.size)

        val event = events.first()
        assertEquals("1035084", event.id)
        assertEquals("Real Madrid", event.homeTeam)
        assertEquals("Barcelona", event.awayTeam)
        assertEquals("https://media.api-sports.io/football/teams/541.png", event.homeTeamLogo)
        assertEquals("https://media.api-sports.io/football/teams/529.png", event.awayTeamLogo)
        assertEquals("La Liga", event.league)
        assertEquals("https://media.api-sports.io/football/leagues/140.png", event.leagueLogo)
        assertEquals("Spain", event.country)
        assertEquals(1727632800000L, event.startTime)
        assertEquals(2, event.homeScore)
        assertEquals(1, event.awayScore)
        assertEquals(68, event.elapsedMinutes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 13. Los eventos FINISHED nunca llegan al SportsUiState final.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test13_finishedEventsNeverReachFinalSportsUiState() {
        val finishedEvent = SportsEvent(
            id = "fin_1",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Equipo A",
            awayTeam = "Equipo B",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.FINISHED
        )
        val liveEvent = SportsEvent(
            id = "live_1",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Equipo C",
            awayTeam = "Equipo D",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.LIVE
        )

        // Filtro estricto aplicado
        val cleanList = listOf(finishedEvent, liveEvent).filter {
            it.status != SportsEventStatus.FINISHED && it.status != SportsEventStatus.CANCELLED
        }

        val uiState = SportsUiState(
            liveEvents = cleanList
        )

        assertEquals(1, uiState.liveEvents.size)
        assertEquals("live_1", uiState.liveEvents.first().id)
        assertFalse(uiState.liveEvents.any { it.status == SportsEventStatus.FINISHED })
        assertFalse(uiState.liveEvents.any { it.status == SportsEventStatus.CANCELLED })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TESTS EXHAUSTIVOS DE FASE 3: SPORTS CHANNEL MATCHER
    // ─────────────────────────────────────────────────────────────────────────

    private val matcher = DefaultSportsChannelMatcher()

    private fun createSampleEvent(
        sport: String = "football",
        league: String = "LaLiga EA Sports",
        home: String = "Real Madrid",
        away: String = "Barcelona",
        country: String? = "España",
        broadcaster: String? = "DAZN LaLiga"
    ) = SportsEvent(
        id = "event_test_1",
        sport = sport,
        league = league,
        homeTeam = home,
        awayTeam = away,
        startTime = System.currentTimeMillis(),
        status = SportsEventStatus.LIVE,
        country = country,
        broadcaster = broadcaster
    )

    // 14. Coincidencia exacta de ambos equipos
    @Test
    fun test14_matchBothTeamsExact() = runBlocking {
        val event = createSampleEvent(home = "Real Madrid", away = "Barcelona")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "EVENTOS | Real Madrid vs Barcelona", group = "DEPORTES", url = "http://stream.m3u8"),
            com.example.motor.db.IptvItem(name = "Canal Cocina", group = "VARIOS", url = "http://cocina.m3u8")
        )
        val result = matcher.matchChannel(event, channels, playlistId = 1L)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertEquals("EVENTOS | Real Madrid vs Barcelona", result.topMatch?.channel?.name)
        assertTrue(result.topMatch?.score ?: 0 >= SportsChannelMatcher.AUTO_PLAY_THRESHOLD)
    }

    // 15. Coincidencia por competición
    @Test
    fun test15_matchByCompetition() = runBlocking {
        val event = createSampleEvent(league = "Premier League", home = "Arsenal", away = "Chelsea")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "DAZN Premier League", group = "DEPORTES", url = "http://stream.m3u8"),
            com.example.motor.db.IptvItem(name = "Canal Toros", group = "VARIOS", url = "http://toros.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
        assertEquals("DAZN Premier League", result.candidates.first().channel.name)
    }

    // 16. Coincidencia por deporte
    @Test
    fun test16_matchBySportCategory() = runBlocking {
        val event = createSampleEvent(sport = "basketball", league = "Euroleague", home = "Panathinaikos", away = "Olympiacos")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Euroleague TV", group = "BASKET", url = "http://stream.m3u8"),
            com.example.motor.db.IptvItem(name = "Canal Noticias 24H", group = "NOTICIAS", url = "http://news.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
        assertEquals("Euroleague TV", result.topMatch?.channel?.name)
    }

    // 17. Coincidencia por nombre del canal
    @Test
    fun test17_matchByChannelName() = runBlocking {
        val event = createSampleEvent(broadcaster = "Movistar LaLiga")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Movistar LaLiga HD", group = "SPAIN", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
        assertEquals("Movistar LaLiga HD", result.candidates.first().channel.name)
    }

    // 18. Coincidencia usando tvgName
    @Test
    fun test18_matchByTvgName() = runBlocking {
        val event = createSampleEvent(home = "Liverpool", away = "Everton")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "CH 102", tvgName = "Liverpool vs Everton", group = "DEPORTES", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
    }

    // 19. Coincidencia usando group
    @Test
    fun test19_matchByGroup() = runBlocking {
        val event = createSampleEvent(sport = "formula1", league = "Formula 1")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "DAZN F1 1080p", group = "F1 MOTOR", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
    }

    // 20. Coincidencia usando tvgId
    @Test
    fun test20_matchByTvgId() = runBlocking {
        val event = createSampleEvent(broadcaster = "DAZN")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Canal 55", tvgId = "dazn.laliga.es", group = "DEPORTES", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
    }

    // 21. Normalización de acentos
    @Test
    fun test21_normalizeAccents() = runBlocking {
        val event = createSampleEvent(home = "Atlético de Madrid", away = "Bayern Múnich")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Atletico de Madrid vs Bayern Munich", group = "FUTBOL", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
    }

    // 22. Normalización de mayúsculas/minúsculas
    @Test
    fun test22_normalizeCase() = runBlocking {
        val event = createSampleEvent(home = "real madrid", away = "barcelona")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "REAL MADRID - BARCELONA (FHD)", group = "SPORTS", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
    }

    // 23. Normalización de separadores
    @Test
    fun test23_normalizeSeparators() = runBlocking {
        val event = createSampleEvent(home = "PSG", away = "Inter")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "ES | PSG_vs_Inter | 1080p [VIP]", group = "DEPORTES", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
    }

    // 24. Un candidato fuerte produce AUTO_PLAY
    @Test
    fun test24_strongCandidateProducesAutoPlay() = runBlocking {
        val event = createSampleEvent(home = "Betis", away = "Sevilla")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "DIRECTO: Betis vs Sevilla", group = "FUTBOL", url = "http://stream.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertNotNull(result.topMatch)
    }

    // 25. Varios candidatos producen SELECTION
    @Test
    fun test25_multipleCandidatesProduceSelection() = runBlocking {
        val event = createSampleEvent(league = "LaLiga", home = "Real Madrid", away = "Barcelona")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "DAZN LaLiga 1", group = "DEPORTES", url = "http://stream1.m3u8"),
            com.example.motor.db.IptvItem(name = "Movistar LaLiga 1", group = "DEPORTES", url = "http://stream2.m3u8"),
            com.example.motor.db.IptvItem(name = "LaLiga TV Bar", group = "DEPORTES", url = "http://stream3.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.SELECTION, result.decision)
        assertTrue(result.candidates.size >= 2)
    }

    // 26. Puntuación insuficiente produce NO_MATCH
    @Test
    fun test26_insufficientScoreProducesNoMatch() = runBlocking {
        val event = createSampleEvent(home = "Alavés", away = "Getafe")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Canal Cocina", group = "VARIOS", url = "http://stream.m3u8"),
            com.example.motor.db.IptvItem(name = "Telesur Noticias", group = "NOTICIAS", url = "http://news.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue(result.candidates.isEmpty())
    }

    // 27. Un canal de otra playlist nunca se utiliza
    @Test
    fun test27_otherPlaylistChannelNotUsed() = runBlocking {
        val event = createSampleEvent()
        val playlist1Channels = listOf(
            com.example.motor.db.IptvItem(name = "DAZN LaLiga (PL 1)", group = "DEPORTES", url = "http://pl1.m3u8")
        )
        val playlist2Channels = listOf(
            com.example.motor.db.IptvItem(name = "M+ LaLiga (PL 2)", group = "DEPORTES", url = "http://pl2.m3u8")
        )

        val result1 = matcher.matchChannel(event, playlist1Channels, playlistId = 1L)
        val result2 = matcher.matchChannel(event, playlist2Channels, playlistId = 2L)

        assertEquals("http://pl1.m3u8", result1.topMatch?.channel?.url)
        assertEquals("http://pl2.m3u8", result2.topMatch?.channel?.url)
        assertEquals(1L, result1.playlistId)
        assertEquals(2L, result2.playlistId)
    }

    // 28. Canal sin URL reproducible no se devuelve como candidato
    @Test
    fun test28_channelWithoutPlayableUrlExcluded() = runBlocking {
        val event = createSampleEvent(home = "Real Madrid", away = "Barcelona")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "EVENTO | Real Madrid vs Barcelona", group = "DEPORTES", url = null, stalkerCmd = null)
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue(result.candidates.isEmpty())
    }

    // 29. No se inventa ninguna URL
    @Test
    fun test29_neverInventUrls() = runBlocking {
        val event = createSampleEvent()
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://servidor-real.com/live/123.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        val matchedUrl = result.topMatch?.channel?.url
        assertEquals("http://servidor-real.com/live/123.m3u8", matchedUrl)
    }

    // 30. Evento de fútbol no debe seleccionar arbitrariamente un canal genérico de noticias
    @Test
    fun test30_footballDoesNotSelectGenericNews() = runBlocking {
        val event = createSampleEvent(sport = "football", home = "Valencia", away = "Celta")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Noticias 24H España", group = "NOTICIAS", url = "http://news.m3u8"),
            com.example.motor.db.IptvItem(name = "Canal Parlamento", group = "GENERAL", url = "http://parlamento.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.NO_MATCH, result.decision)
    }

    // 31. Evento NBA debe poder encontrar un canal NBA cuando exista
    @Test
    fun test31_nbaEventMatchesNbaChannel() = runBlocking {
        val event = createSampleEvent(sport = "basketball", league = "NBA", home = "Lakers", away = "Celtics")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "NBA TV HD", group = "BASKETBALL", url = "http://nba.m3u8"),
            com.example.motor.db.IptvItem(name = "Canal Cocina", group = "VARIOS", url = "http://cocina.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
        assertEquals("NBA TV HD", result.topMatch?.channel?.name)
    }

    // 32. Evento de rugby debe poder encontrar un canal rugby cuando exista
    @Test
    fun test32_rugbyEventMatchesRugbyChannel() = runBlocking {
        val event = createSampleEvent(sport = "rugby", league = "Six Nations", home = "Francia", away = "Irlanda")
        val channels = listOf(
            com.example.motor.db.IptvItem(name = "Rugby Channel HD", group = "RUGBY", url = "http://rugby.m3u8"),
            com.example.motor.db.IptvItem(name = "Canal Cine", group = "CINE", url = "http://cine.m3u8")
        )
        val result = matcher.matchChannel(event, channels)
        assertTrue(result.candidates.isNotEmpty())
        assertEquals("Rugby Channel HD", result.topMatch?.channel?.name)
    }

    // 33. Lista vacía produce NO_MATCH
    @Test
    fun test33_emptyListProducesNoMatch() = runBlocking {
        val event = createSampleEvent()
        val result = matcher.matchChannel(event, emptyList())
        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue(result.candidates.isEmpty())
        assertNull(result.topMatch)
    }

    // 34. Playlist grande no provoca duplicación innecesaria dentro del matcher
    @Test
    fun test34_largePlaylistDoesNotDuplicateMemory() = runBlocking {
        val event = createSampleEvent(home = "PSG", away = "Monaco")
        // Simular playlist grande de 10,000 canales
        val largeList = (1..10_000).map { i ->
            com.example.motor.db.IptvItem(
                name = if (i == 4200) "Ligue 1 - PSG vs Monaco" else "Canal General $i",
                group = if (i == 4200) "DEPORTES" else "GENERAL",
                url = "http://stream/$i.m3u8"
            )
        }

        val startTime = System.currentTimeMillis()
        val result = matcher.matchChannel(event, largeList)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertEquals("Ligue 1 - PSG vs Monaco", result.topMatch?.channel?.name)
        assertTrue("El matching sobre 10,000 canales debe completarse en menos de 1 segundo", elapsed < 1000)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRUEBAS OBLIGATORIAS — CORRECCIÓN INTEGRAL DEL MATCHER DEPORTIVO
    // ─────────────────────────────────────────────────────────────────────────

    // ─── A. FÚTBOL ───────────────────────────────────────────────────────────

    // 35. Fútbol Positivo: Real Madrid vs Barcelona prioriza canales de fútbol y descarta baloncesto y tenis
    @Test
    fun test35_football_positiveScenario_realMadridVsBarcelona_selectsLaLigaChannelsOnly() = runBlocking {
        val event = createSampleEvent(
            sport = "football",
            league = "LaLiga",
            home = "Real Madrid",
            away = "Barcelona",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://dazn.m3u8"),
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistar.m3u8"),
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8"),
            IptvItem(name = "Tennis Channel", group = "DEPORTES", url = "http://tennis.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.SELECTION, result.decision)
        val candidateNames = result.candidates.map { it.channel.name }
        assertTrue("DAZN LaLiga debe ser candidato", candidateNames.contains("DAZN LaLiga"))
        assertTrue("Movistar LaLiga debe ser candidato", candidateNames.contains("Movistar LaLiga"))
        assertFalse("NBA TV no debe ser candidato para fútbol", candidateNames.contains("NBA TV"))
        assertFalse("Tennis Channel no debe ser candidato para fútbol", candidateNames.contains("Tennis Channel"))
    }

    // 36. Fútbol Negativo: Evento de fútbol sin ningún canal compatible produce NO_MATCH
    @Test
    fun test36_football_negativeScenario_noCompatibleChannels_returnsNoMatch() = runBlocking {
        val event = createSampleEvent(
            sport = "football",
            league = "LaLiga",
            home = "Girona",
            away = "Rayo Vallecano",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8"),
            IptvItem(name = "Tennis Channel", group = "DEPORTES", url = "http://tennis.m3u8"),
            IptvItem(name = "Deportes 1", group = "DEPORTES", url = "http://dep1.m3u8"),
            IptvItem(name = "Sport HD", group = "SPORTS", url = "http://sporthd.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue("No debe haber candidatos fiables", result.candidates.isEmpty())
        assertNull(result.topMatch)
    }

    // ─── B. TENIS ────────────────────────────────────────────────────────────

    // 37. Tenis Positivo: Torneo Wimbledon prioriza canales de tenis y descarta canales de fútbol
    @Test
    fun test37_tennis_positiveScenario_wimbledon_prioritizesTennisChannels() = runBlocking {
        val event = createSampleEvent(
            sport = "tennis",
            league = "Wimbledon",
            home = "Carlos Alcaraz",
            away = "Novak Djokovic",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "Tennis Channel", group = "DEPORTES", url = "http://tennis.m3u8"),
            IptvItem(name = "ESPN Tennis", group = "DEPORTES", url = "http://espntennis.m3u8"),
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://daznlaliga.m3u8"),
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistarlaliga.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.SELECTION, result.decision)
        val candidateNames = result.candidates.map { it.channel.name }
        assertTrue("Tennis Channel debe ser candidato", candidateNames.contains("Tennis Channel"))
        assertTrue("ESPN Tennis debe ser candidato", candidateNames.contains("ESPN Tennis"))
        assertFalse("DAZN LaLiga no debe ser candidato para tenis", candidateNames.contains("DAZN LaLiga"))
        assertFalse("Movistar LaLiga no debe ser candidato para tenis", candidateNames.contains("Movistar LaLiga"))
    }

    // 38. Tenis Positivo: Roland Garros, US Open, ATP y WTA coinciden con canales temáticos pertinentes
    @Test
    fun test38_tennis_positiveScenarios_otherGrandSlamsAndTours() = runBlocking {
        // Roland Garros
        val rgEvent = createSampleEvent(
            sport = "tennis",
            league = "Roland Garros",
            home = "Jannik Sinner",
            away = "Carlos Alcaraz",
            broadcaster = null
        )
        val rgChannels = listOf(
            IptvItem(name = "Eurosport 1 HD", group = "DEPORTES", url = "http://eurosport1.m3u8"),
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://daznlaliga.m3u8")
        )
        val rgResult = matcher.matchChannel(rgEvent, rgChannels)
        assertEquals("Eurosport 1 HD", rgResult.topMatch?.channel?.name)
        assertFalse(rgResult.candidates.any { it.channel.name == "DAZN LaLiga" })

        // US Open
        val usoEvent = createSampleEvent(
            sport = "tennis",
            league = "US Open",
            home = "Daniil Medvedev",
            away = "Alexander Zverev",
            broadcaster = null
        )
        val usoChannels = listOf(
            IptvItem(name = "Tennis Channel HD", group = "DEPORTES", url = "http://tennis.m3u8"),
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistarlaliga.m3u8")
        )
        val usoResult = matcher.matchChannel(usoEvent, usoChannels)
        assertEquals("Tennis Channel HD", usoResult.topMatch?.channel?.name)

        // ATP Tour
        val atpEvent = createSampleEvent(
            sport = "tennis",
            league = "ATP Masters 1000 Madrid",
            home = "Rafael Nadal",
            away = "Stefanos Tsitsipas",
            broadcaster = null
        )
        val atpChannels = listOf(
            IptvItem(name = "Tennis Channel", group = "TENNIS", url = "http://tennis.m3u8"),
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8")
        )
        val atpResult = matcher.matchChannel(atpEvent, atpChannels)
        assertEquals("Tennis Channel", atpResult.topMatch?.channel?.name)

        // WTA Tour
        val wtaEvent = createSampleEvent(
            sport = "tennis",
            league = "WTA Finals",
            home = "Iga Swiatek",
            away = "Aryna Sabalenka",
            broadcaster = null
        )
        val wtaChannels = listOf(
            IptvItem(name = "WTA Tennis Live", group = "DEPORTES", url = "http://wta.m3u8"),
            IptvItem(name = "DAZN Premier League", group = "DEPORTES", url = "http://daznpl.m3u8")
        )
        val wtaResult = matcher.matchChannel(wtaEvent, wtaChannels)
        assertEquals("WTA Tennis Live", wtaResult.topMatch?.channel?.name)
    }

    // 39. Tenis Negativo: Evento de Wimbledon y solo canales de fútbol produce NO_MATCH
    @Test
    fun test39_tennis_negativeScenario_wimbledonWithOnlyFootballChannels_returnsNoMatch() = runBlocking {
        val event = createSampleEvent(
            sport = "tennis",
            league = "Wimbledon",
            home = "Alcaraz",
            away = "Djokovic",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://dazn.m3u8"),
            IptvItem(name = "Movistar LaLiga 1", group = "DEPORTES", url = "http://movistar1.m3u8"),
            IptvItem(name = "LaLiga TV Bar", group = "DEPORTES", url = "http://bar.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue("Los canales de fútbol no deben aceptarse para tenis", result.candidates.isEmpty())
    }

    // 40. Tenis Negativo: Evento de tenis y únicamente canales genéricos (Deportes 1, Sport HD) produce NO_MATCH
    @Test
    fun test40_tennis_negativeScenario_onlyGenericSportsChannels_returnsNoMatch() = runBlocking {
        val event = createSampleEvent(
            sport = "tennis",
            league = "Roland Garros",
            home = "Jannik Sinner",
            away = "Daniil Medvedev",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "Deportes 1", group = "DEPORTES", url = "http://dep1.m3u8"),
            IptvItem(name = "Deportes 2", group = "DEPORTES", url = "http://dep2.m3u8"),
            IptvItem(name = "Sport HD", group = "SPORTS", url = "http://sporthd.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue("Canales genéricos de deportes no deben presentarse como coincidencias para tenis", result.candidates.isEmpty())
    }

    // ─── C. BALONCESTO ───────────────────────────────────────────────────────

    // 41. Baloncesto Positivo: Lakers vs Celtics prioriza canales de baloncesto y descarta fútbol por proveedor
    @Test
    fun test41_basketball_positiveScenario_lakersVsCeltics_prioritizesBasketballChannels() = runBlocking {
        val event = createSampleEvent(
            sport = "basketball",
            league = "NBA",
            home = "Los Angeles Lakers",
            away = "Boston Celtics",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8"),
            IptvItem(name = "Basketball TV", group = "DEPORTES", url = "http://basket.m3u8"),
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://daznlaliga.m3u8"),
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistarlaliga.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.SELECTION, result.decision)
        val candidateNames = result.candidates.map { it.channel.name }
        assertTrue("NBA TV debe ser candidato", candidateNames.contains("NBA TV"))
        assertTrue("Basketball TV debe ser candidato", candidateNames.contains("Basketball TV"))
        assertFalse("DAZN LaLiga no debe ser candidato para baloncesto", candidateNames.contains("DAZN LaLiga"))
        assertFalse("Movistar LaLiga no debe ser candidato para baloncesto", candidateNames.contains("Movistar LaLiga"))
    }

    // 42. Baloncesto Positivo: EuroLeague, EuroCup y ACB coinciden con canales temáticos pertinentes
    @Test
    fun test42_basketball_positiveScenarios_euroleague_eurocup_acb() = runBlocking {
        // EuroLeague
        val euroleagueEvent = createSampleEvent(
            sport = "basketball",
            league = "EuroLeague",
            home = "Panathinaikos",
            away = "Olympiacos",
            broadcaster = null
        )
        val euroleagueChannels = listOf(
            IptvItem(name = "Euroleague TV HD", group = "BASKET", url = "http://euroleague.m3u8"),
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://dazn.m3u8")
        )
        val euroResult = matcher.matchChannel(euroleagueEvent, euroleagueChannels)
        assertEquals("Euroleague TV HD", euroResult.topMatch?.channel?.name)
        assertFalse(euroResult.candidates.any { it.channel.name == "DAZN LaLiga" })

        // EuroCup
        val eurocupEvent = createSampleEvent(
            sport = "basketball",
            league = "EuroCup",
            home = "Valencia Basket",
            away = "Hapoel Tel Aviv",
            broadcaster = null
        )
        val eurocupChannels = listOf(
            IptvItem(name = "Eurocup TV", group = "DEPORTES", url = "http://eurocup.m3u8"),
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistar.m3u8")
        )
        val cupResult = matcher.matchChannel(eurocupEvent, eurocupChannels)
        assertEquals("Eurocup TV", cupResult.topMatch?.channel?.name)

        // ACB / Liga Endesa
        val acbEvent = createSampleEvent(
            sport = "basketball",
            league = "Liga Endesa",
            home = "Real Madrid Baloncesto",
            away = "Unicaja",
            broadcaster = null
        )
        val acbChannels = listOf(
            IptvItem(name = "ACB TV", group = "BASKET", url = "http://acb.m3u8"),
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://dazn.m3u8")
        )
        val acbResult = matcher.matchChannel(acbEvent, acbChannels)
        assertEquals("ACB TV", acbResult.topMatch?.channel?.name)
    }

    // 43. Baloncesto Negativo: Evento de EuroLeague y solo canales de fútbol produce NO_MATCH
    @Test
    fun test43_basketball_negativeScenario_euroleagueWithOnlyFootballChannels_returnsNoMatch() = runBlocking {
        val event = createSampleEvent(
            sport = "basketball",
            league = "EuroLeague",
            home = "Real Madrid",
            away = "Barcelona",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://dazn.m3u8"),
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistar.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue("Los canales de fútbol no deben aceptarse para baloncesto", result.candidates.isEmpty())
    }

    // 44. Baloncesto Negativo: Evento de baloncesto y solo canales genéricos de deportes produce NO_MATCH
    @Test
    fun test44_basketball_negativeScenario_onlyGenericSportsChannels_returnsNoMatch() = runBlocking {
        val event = createSampleEvent(
            sport = "basketball",
            league = "NBA",
            home = "Lakers",
            away = "Warriors",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "Deportes 1", group = "DEPORTES", url = "http://dep1.m3u8"),
            IptvItem(name = "Sport HD", group = "SPORTS", url = "http://sport.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue("Canales genéricos no tienen evidencia suficiente para baloncesto", result.candidates.isEmpty())
    }

    // ─── D. PRUEBAS CRUZADAS (Evitar regresiones) ─────────────────────────────

    // 45. Wimbledon NUNCA debe aceptar DAZN LaLiga únicamente porque el grupo sea DEPORTES
    @Test
    fun test45_crossSport_wimbledonNeverAcceptsDaznLaLigaEvenIfGroupIsDeportes() = runBlocking {
        val event = createSampleEvent(
            sport = "tennis",
            league = "Wimbledon",
            home = "Alcaraz",
            away = "Sinner",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "DAZN LaLiga", group = "DEPORTES", url = "http://dazn.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue(result.candidates.isEmpty())
    }

    // 46. EuroLeague NUNCA debe aceptar Movistar LaLiga únicamente porque el grupo sea DEPORTES
    @Test
    fun test46_crossSport_euroleagueNeverAcceptsMovistarLaLigaEvenIfGroupIsDeportes() = runBlocking {
        val event = createSampleEvent(
            sport = "basketball",
            league = "EuroLeague",
            home = "Efes",
            away = "Fenerbahce",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "Movistar LaLiga", group = "DEPORTES", url = "http://movistar.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue(result.candidates.isEmpty())
    }

    // 47. Un evento de fútbol NO debe aceptar NBA TV solo porque sea un canal deportivo
    @Test
    fun test47_crossSport_footballNeverAcceptsNbaTvBecauseItIsSportsChannel() = runBlocking {
        val event = createSampleEvent(
            sport = "football",
            league = "LaLiga",
            home = "Betis",
            away = "Sevilla",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
        assertTrue(result.candidates.isEmpty())
    }

    // 48. La presencia de DAZN, Movistar o M+ no debe resolver por sí sola la disciplina
    @Test
    fun test48_crossSport_providerWordsAloneDoNotResolveDiscipline() = runBlocking {
        val tennisEvent = createSampleEvent(
            sport = "tennis",
            league = "ATP",
            home = "Zverev",
            away = "Rublev",
            broadcaster = null
        )
        val tennisChannels = listOf(
            IptvItem(name = "DAZN 1", group = "DEPORTES", url = "http://dazn1.m3u8"),
            IptvItem(name = "Movistar Plus+", group = "GENERAL", url = "http://movistar.m3u8")
        )
        val tennisResult = matcher.matchChannel(tennisEvent, tennisChannels)
        assertEquals(MatchDecision.NO_MATCH, tennisResult.decision)

        val basketEvent = createSampleEvent(
            sport = "basketball",
            league = "EuroLeague",
            home = "Monaco",
            away = "Baskonia",
            broadcaster = null
        )
        val basketChannels = listOf(
            IptvItem(name = "DAZN 2", group = "DEPORTES", url = "http://dazn2.m3u8"),
            IptvItem(name = "M+", group = "DEPORTES", url = "http://mplus.m3u8")
        )
        val basketResult = matcher.matchChannel(basketEvent, basketChannels)
        assertEquals(MatchDecision.NO_MATCH, basketResult.decision)
    }

    // 49. Un canal llamado simplemente "Deportes 1" no debe ganar frente a un canal temático adecuado
    @Test
    fun test49_crossSport_genericDeportes1NeverWinsOverSpecificThematicChannel() = runBlocking {
        val tennisEvent = createSampleEvent(
            sport = "tennis",
            league = "Wimbledon",
            home = "Alcaraz",
            away = "Djokovic",
            broadcaster = null
        )
        val channels = listOf(
            IptvItem(name = "Deportes 1", group = "DEPORTES", url = "http://dep1.m3u8"),
            IptvItem(name = "Tennis Channel", group = "DEPORTES", url = "http://tennis.m3u8")
        )

        val result = matcher.matchChannel(tennisEvent, channels)

        assertEquals("Tennis Channel", result.topMatch?.channel?.name)
        assertFalse("Deportes 1 no debe ser candidato para Wimbledon", result.candidates.any { it.channel.name == "Deportes 1" })
    }

    // 50. Prefijos y sufijos habituales (HD, FHD, 4K, Multi, Bar, ES, SPAIN) no rompen el matching
    @Test
    fun test50_crossSport_channelQualityAndRegionPrefixesDoNotBreakMatching() = runBlocking {
        val event = createSampleEvent(
            sport = "football",
            league = "LaLiga",
            home = "Real Madrid",
            away = "Barcelona",
            broadcaster = null
        )
        val variants = listOf(
            "[ES] DAZN LALIGA HD",
            "DAZN LaLiga FHD",
            "ESP - DAZN LALIGA",
            "SPAIN - DAZN LALIGA 4K",
            "DAZN LaLiga Multi 1",
            "DAZN LaLiga Bar"
        )
        for (variantName in variants) {
            val channels = listOf(
                IptvItem(name = variantName, group = "DEPORTES", url = "http://stream.m3u8")
            )
            val result = matcher.matchChannel(event, channels)
            assertTrue(
                "La variante '$variantName' debe ser reconocida como candidato de LaLiga",
                result.candidates.isNotEmpty() && result.topMatch?.channel?.name == variantName
            )
        }
    }

    // 51. Las abreviaturas comunes no introducen colisiones (RM o BCN con nombres no relacionados)
    @Test
    fun test51_crossSport_shortAbbreviationsDoNotCollideWithUnrelatedNames() = runBlocking {
        val event = createSampleEvent(
            sport = "formula1",
            league = "Formula 1",
            home = "Grand Prix",
            away = "Monaco",
            broadcaster = null
        )
        // Canales que contienen subcadenas "RM" en FORMULA o nombres no relacionados
        val channels = listOf(
            IptvItem(name = "ALBUM DEPORTIVO", group = "DEPORTES", url = "http://album.m3u8"),
            IptvItem(name = "CANAL SUR", group = "GENERAL", url = "http://sur.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        assertEquals(MatchDecision.NO_MATCH, result.decision)
    }

    // 52. En tenis, canales como M. Clásicos (cine) y M. Golf NUNCA deben seleccionarse
    @Test
    fun test52_tennis_doesNotSelectMClasicosOrMGolf() = runBlocking {
        val event = createSampleEvent(
            sport = "tennis",
            league = "Wimbledon",
            home = "Carlos Alcaraz",
            away = "Novak Djokovic",
            broadcaster = "Movistar Deportes, Eurosport, Tennis Channel"
        )
        val channels = listOf(
            IptvItem(name = "M. CLASICOS HD", group = "MOVISTAR PLUS+", url = "http://clasicos.m3u8"),
            IptvItem(name = "M. GOLF HD", group = "MOVISTAR PLUS+", url = "http://golf.m3u8"),
            IptvItem(name = "M. DEPORTES HD", group = "MOVISTAR PLUS+", url = "http://deportes.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        val candidateNames = result.candidates.map { it.channel.name }
        assertFalse("M. CLASICOS HD (cine) nunca debe asociarse a tenis", candidateNames.contains("M. CLASICOS HD"))
        assertFalse("M. GOLF HD nunca debe asociarse a tenis", candidateNames.contains("M. GOLF HD"))
        assertTrue("M. DEPORTES HD sí debe ser candidato de tenis", candidateNames.contains("M. DEPORTES HD"))
        assertEquals("M. DEPORTES HD", result.topMatch?.channel?.name)

        // Caso negativo: solo existen M. Clásicos y M. Golf en la lista
        val negativeChannels = listOf(
            IptvItem(name = "M. CLASICOS HD", group = "MOVISTAR PLUS+", url = "http://clasicos.m3u8"),
            IptvItem(name = "M. GOLF HD", group = "MOVISTAR PLUS+", url = "http://golf.m3u8")
        )
        val negResult = matcher.matchChannel(event, negativeChannels)
        assertEquals(MatchDecision.NO_MATCH, negResult.decision)
        assertTrue(negResult.candidates.isEmpty())
    }

    // 53. UFC / MMA: Evento de UFC prioriza canales temáticos (Eurosport, UFC Fight Pass) y rechaza canales de fútbol o baloncesto
    @Test
    fun test53_ufc_positiveAndNegativeMatching() = runBlocking {
        val event = createSampleEvent(
            sport = "ufc",
            league = "UFC 333: Volkanovski vs. Evloev",
            home = "Alexander Volkanovski",
            away = "Movsar Evloev",
            broadcaster = "Eurosport 2, Eurosport, Max, UFC Fight Pass"
        )
        val channels = listOf(
            IptvItem(name = "Eurosport 2 HD", group = "DEPORTES", url = "http://es2.m3u8"),
            IptvItem(name = "UFC Fight Pass HD", group = "DEPORTES", url = "http://ufc.m3u8"),
            IptvItem(name = "DAZN LaLiga HD", group = "DEPORTES", url = "http://daznlaliga.m3u8"),
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8")
        )

        val result = matcher.matchChannel(event, channels)

        val candidateNames = result.candidates.map { it.channel.name }
        assertTrue("UFC Fight Pass o Eurosport 2 deben ser candidatos para UFC", candidateNames.contains("UFC Fight Pass HD") || candidateNames.contains("Eurosport 2 HD"))
        assertFalse("DAZN LaLiga no debe asociarse a un evento de UFC", candidateNames.contains("DAZN LaLiga HD"))
        assertFalse("NBA TV no debe asociarse a un evento de UFC", candidateNames.contains("NBA TV"))

        // Escenario negativo: sólo canales de fútbol y baloncesto disponibles
        val negativeChannels = listOf(
            IptvItem(name = "DAZN LaLiga HD", group = "DEPORTES", url = "http://daznlaliga.m3u8"),
            IptvItem(name = "NBA TV", group = "DEPORTES", url = "http://nba.m3u8")
        )
        val negResult = matcher.matchChannel(event, negativeChannels)
        assertEquals(MatchDecision.NO_MATCH, negResult.decision)
        assertTrue(negResult.candidates.isEmpty())
    }
}

