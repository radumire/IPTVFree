package com.example.motor.sports

import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.parser.BaseballSportsParser
import com.example.motor.sports.parser.BasketballSportsParser
import com.example.motor.sports.parser.FootballSportsParser
import com.example.motor.sports.parser.RugbySportsParser
import com.example.motor.sports.remote.ApiSportsRemoteDataSource
import com.example.motor.sports.remote.SportsRemoteDataSource
import com.example.motor.sports.repository.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SportsApiIntegrationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. BASKETBALL PARSER CON ESQUEMA REAL DE API-SPORTS (Campos en raíz del item)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test01_basketballParserParsesRootLevelRealResponse() {
        val realJson = """
        {
          "get": "games",
          "parameters": { "date": "2026-10-01" },
          "errors": [],
          "results": 2,
          "response": [
            {
              "id": 1001,
              "date": "2026-10-01T19:00:00+00:00",
              "time": "19:00",
              "timestamp": 1790881200,
              "timezone": "UTC",
              "status": { "long": "In Play", "short": "Q2", "timer": 8 },
              "league": { "id": 12, "name": "NBA", "logo": "http://nba.png" },
              "country": { "id": 1, "name": "USA" },
              "teams": {
                "home": { "name": "Lakers", "logo": "http://lakers.png" },
                "away": { "name": "Warriors", "logo": "http://warriors.png" }
              },
              "scores": {
                "home": { "total": 45 },
                "away": { "total": 42 }
              }
            },
            {
              "id": 1002,
              "date": "2026-10-01T21:00:00+00:00",
              "time": "21:00",
              "timestamp": 1790888400,
              "timezone": "UTC",
              "status": { "long": "Finished", "short": "FT" },
              "league": { "id": 12, "name": "NBA" },
              "country": { "name": "USA" },
              "teams": {
                "home": { "name": "Celtics" },
                "away": { "name": "Bulls" }
              },
              "scores": { "home": { "total": 110 }, "away": { "total": 95 } }
            }
          ]
        }
        """.trimIndent()

        val events = BasketballSportsParser.parse(realJson, isLiveOnly = false)
        // El evento 1002 está FT (FINISHED), por lo que debe filtrarse
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("1001", event.id)
        assertEquals("basketball", event.sport)
        assertEquals("NBA", event.league)
        assertEquals("Lakers", event.homeTeam)
        assertEquals("Warriors", event.awayTeam)
        assertEquals(45, event.homeScore)
        assertEquals(42, event.awayScore)
        assertEquals(SportsEventStatus.LIVE, event.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. BASEBALL PARSER CON ESQUEMA REAL DE API-SPORTS
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test02_baseballParserParsesRootLevelRealResponse() {
        val realJson = """
        {
          "get": "games",
          "results": 1,
          "response": [
            {
              "id": 2001,
              "date": "2026-10-01T18:00:00+00:00",
              "timestamp": 1790877600,
              "status": { "long": "Not Started", "short": "NS" },
              "league": { "name": "MLB", "logo": "http://mlb.png" },
              "country": { "name": "USA" },
              "teams": {
                "home": { "name": "Yankees", "logo": "http://yankees.png" },
                "away": { "name": "Red Sox", "logo": "http://redsox.png" }
              },
              "scores": {
                "home": { "total": 0 },
                "away": { "total": 0 }
              }
            }
          ]
        }
        """.trimIndent()

        val events = BaseballSportsParser.parse(realJson, isLiveOnly = false)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("2001", event.id)
        assertEquals("baseball", event.sport)
        assertEquals("MLB", event.league)
        assertEquals("Yankees", event.homeTeam)
        assertEquals("Red Sox", event.awayTeam)
        assertEquals(SportsEventStatus.SCHEDULED, event.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. RUGBY PARSER CON ESQUEMA REAL DE API-SPORTS
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test03_rugbyParserParsesRootLevelRealResponse() {
        val realJson = """
        {
          "get": "games",
          "results": 1,
          "response": [
            {
              "id": 3001,
              "date": "2026-10-01T16:00:00+00:00",
              "timestamp": 1790870400,
              "status": { "long": "1st Half", "short": "1H" },
              "league": { "name": "Six Nations", "logo": "http://sixnations.png" },
              "country": { "name": "Europe" },
              "teams": {
                "home": { "name": "Francia", "logo": "http://francia.png" },
                "away": { "name": "Irlanda", "logo": "http://irlanda.png" }
              },
              "scores": {
                "home": 14,
                "away": 10
              }
            }
          ]
        }
        """.trimIndent()

        val events = RugbySportsParser.parse(realJson, isLiveOnly = false)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("3001", event.id)
        assertEquals("rugby", event.sport)
        assertEquals("Six Nations", event.league)
        assertEquals("Francia", event.homeTeam)
        assertEquals("Irlanda", event.awayTeam)
        assertEquals(14, event.homeScore)
        assertEquals(10, event.awayScore)
        assertEquals(SportsEventStatus.LIVE, event.status)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. DETECCIÓN DE ERROR DE AUTORIZACIÓN / SUSCRIPCIÓN EN API-SPORTS (Caso B)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test04_remoteDataSourceDetectsUnauthorizedPayload() = runBlocking {
        val unauthorizedJson = """
        {
          "get": "games",
          "parameters": { "live": "all" },
          "errors": {
            "token": "Error/Unauthorized : you are not authorized to use this endpoint."
          },
          "results": 0,
          "response": []
        }
        """.trimIndent()

        val mockClient = createMockClient(200, unauthorizedJson)
        val dataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "fake_key" }, client = mockClient)

        try {
            dataSource.getLiveEvents(SportsSport.BASKETBALL)
            fail("Debe lanzar SportsUnauthorizedException")
        } catch (e: SportsUnauthorizedException) {
            assertEquals(SportsSport.BASKETBALL, e.sport)
            assertTrue(e.errorPayload!!.replace("\\/", "/").contains("Error/Unauthorized"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. RESPUESTA 403 LANZA SportsUnauthorizedException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test05_remoteDataSourceDetectsHttp403Forbidden() = runBlocking {
        val mockClient = createMockClient(403, "{\"message\": \"Forbidden\"}")
        val dataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "fake_key" }, client = mockClient)

        try {
            dataSource.getLiveEvents(SportsSport.BASKETBALL)
            fail("Debe lanzar SportsUnauthorizedException ante 403")
        } catch (e: SportsUnauthorizedException) {
            assertEquals(SportsSport.BASKETBALL, e.sport)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. RESPUESTA VÁLIDA CON 0 EVENTOS (Caso C) RETORNA LISTA VACÍA
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test06_remoteDataSourceHandlesCleanEmptyResponse() = runBlocking {
        val cleanEmptyJson = """
        {
          "get": "games",
          "errors": [],
          "results": 0,
          "response": []
        }
        """.trimIndent()

        val mockClient = createMockClient(200, cleanEmptyJson)
        val dataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "valid_key" }, client = mockClient)

        val result = dataSource.getLiveEvents(SportsSport.BASKETBALL)
        assertTrue(result.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. DEPORTES NO IMPLEMENTADOS LANZAN SportsNotSupportedException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test07_unsupportedSportsThrowNotSupportedException() = runBlocking {
        val mockClient = createMockClient(200, "{}")
        val dataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "valid_key" }, client = mockClient)

        try {
            dataSource.getLiveEvents(SportsSport.TENNIS)
            fail("Tenis debe lanzar SportsNotSupportedException")
        } catch (e: SportsNotSupportedException) {
            assertEquals(SportsSport.TENNIS, e.sport)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. SportsRepository REGISTRA ESTADO UNAUTHORIZED SIN CACHEAR DATOS FALSOS
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun test08_repositoryRecordsUnauthorizedStatus() = runBlocking {
        val failingDataSource = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> {
                throw SportsUnauthorizedException(sport, "No suscrito", "{\"token\":\"Error\"}")
            }
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }

        val repository = SportsRepository(failingDataSource)
        val liveEvents = repository.getLiveEvents(SportsSport.BASKETBALL)

        assertTrue(liveEvents.isEmpty())
        val status = repository.getSportStatus(SportsSport.BASKETBALL)
        assertTrue(status is SportStatus.Unauthorized)
        assertEquals(0, repository.getCachedEventCount(SportsSport.BASKETBALL))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER PARA MOCK HTTP
    // ─────────────────────────────────────────────────────────────────────────
    private fun createMockClient(statusCode: Int, responseBody: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
