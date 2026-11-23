package com.example.motor.sports

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.motor.db.IptvItem
import com.example.motor.sports.matcher.DefaultSportsChannelMatcher
import com.example.motor.sports.matcher.MatchDecision
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.parser.BaseballSportsParser
import com.example.motor.sports.parser.BasketballSportsParser
import com.example.motor.sports.parser.FootballSportsParser
import com.example.motor.sports.parser.RugbySportsParser
import com.example.motor.sports.remote.ApiKeyValidationResult
import com.example.motor.sports.remote.ApiSportsRemoteDataSource
import com.example.motor.sports.remote.SportsRemoteDataSource
import com.example.motor.sports.repository.DefaultSportsApiKeyRepository
import com.example.motor.sports.repository.SportsApiKeyRepository
import com.example.motor.sports.repository.SportsRepository
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
import org.robolectric.shadows.ShadowLog
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SportsApiKeyTest {

    private lateinit var context: Context
    private lateinit var apiKeyRepo: SportsApiKeyRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        apiKeyRepo = DefaultSportsApiKeyRepository(context)
        apiKeyRepo.clearUserApiKey()
        ShadowLog.clear()
    }

    // 1. No existe clave
    @Test
    fun test01_noExisteClave() {
        assertFalse(apiKeyRepo.hasUserApiKey())
        assertNull(apiKeyRepo.getUserApiKey())
        assertNull(apiKeyRepo.getMaskedUserApiKey())
    }

    // 2. Existe clave
    @Test
    fun test02_existeClave() {
        apiKeyRepo.setUserApiKey("api_key_test_12345678")
        assertTrue(apiKeyRepo.hasUserApiKey())
        val masked = apiKeyRepo.getMaskedUserApiKey()
        assertNotNull(masked)
        assertTrue(masked!!.endsWith("5678"))
        assertTrue(masked.startsWith("••••••••••"))
    }

    // 3. Guardar clave
    @Test
    fun test03_guardarClave() {
        apiKeyRepo.setUserApiKey("mi_clave_deportes_segura_9999")
        assertEquals("mi_clave_deportes_segura_9999", apiKeyRepo.getUserApiKey())
    }

    // 4. Leer clave
    @Test
    fun test04_leerClave() {
        apiKeyRepo.setUserApiKey("clave_de_lectura_1111")
        val leida = apiKeyRepo.getUserApiKey()
        assertNotNull(leida)
        assertEquals("clave_de_lectura_1111", leida)
    }

    // 5. Eliminar clave
    @Test
    fun test05_eliminarClave() {
        apiKeyRepo.setUserApiKey("clave_a_borrar_2222")
        assertTrue(apiKeyRepo.hasUserApiKey())

        apiKeyRepo.clearUserApiKey()
        assertFalse(apiKeyRepo.hasUserApiKey())
        assertNull(apiKeyRepo.getUserApiKey())
        assertNull(apiKeyRepo.getMaskedUserApiKey())
    }

    // 6. Reemplazar clave
    @Test
    fun test06_reemplazarClave() {
        apiKeyRepo.setUserApiKey("clave_inicial_1111")
        assertEquals("clave_inicial_1111", apiKeyRepo.getUserApiKey())

        apiKeyRepo.setUserApiKey("clave_nueva_2222")
        assertEquals("clave_nueva_2222", apiKeyRepo.getUserApiKey())
        assertTrue(apiKeyRepo.getMaskedUserApiKey()!!.endsWith("2222"))
    }

    // 7. Clave vacía
    @Test
    fun test07_claveVacia() = runBlocking {
        apiKeyRepo.setUserApiKey("   ")
        assertFalse(apiKeyRepo.hasUserApiKey())
        assertNull(apiKeyRepo.getUserApiKey())

        val mockDataSource = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = false
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: java.time.LocalDate): List<SportsEvent> = emptyList()
            override suspend fun validateApiKey(apiKey: String): ApiKeyValidationResult {
                return if (apiKey.isBlank()) ApiKeyValidationResult.InvalidKey else ApiKeyValidationResult.Valid
            }
        }

        val sportsRepo = SportsRepository(mockDataSource, apiKeyRepo)
        val res = sportsRepo.saveAndValidateUserApiKey("")
        assertTrue(res is ApiKeyValidationResult.InvalidKey)
        assertFalse(sportsRepo.hasUserApiKey())
    }

    // 8. Error 401
    @Test
    fun test08_error401() = runBlocking {
        val client = createMockClient(statusCode = 401, responseBody = "{\"message\":\"Invalid API Key\"}")
        val remoteDataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "bad_key" }, client = client)
        val result = remoteDataSource.validateApiKey("bad_key")
        assertTrue(result is ApiKeyValidationResult.InvalidKey)
    }

    // 9. Error de red
    @Test
    fun test09_errorDeRed() = runBlocking {
        val client = createFailingNetworkClient(IOException("Connection timeout"))
        val remoteDataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "some_key" }, client = client)
        val result = remoteDataSource.validateApiKey("some_key")
        assertTrue(result is ApiKeyValidationResult.NetworkError)
        assertEquals("No se pudo comprobar la API key. Comprueba tu conexión.", (result as ApiKeyValidationResult.NetworkError).message)
    }

    // 10. API válida
    @Test
    fun test10_apiValida() = runBlocking {
        val successJson = """{"get":"status","parameters":[],"errors":[],"results":0,"response":{"account":{"firstname":"User"},"subscription":{"active":true}}}"""
        val client = createMockClient(statusCode = 200, responseBody = successJson)
        val remoteDataSource = ApiSportsRemoteDataSource(apiKeyProvider = { "valid_key" }, client = client)
        val result = remoteDataSource.validateApiKey("valid_key")
        assertTrue(result is ApiKeyValidationResult.Valid)
    }

    // 11. La clave nunca aparece en logs
    @Test
    fun test11_laClaveNuncaApareceEnLogs() = runBlocking {
        val secretTestKey = "SECRET_KEY_NEVER_LOGGED_987654321"
        apiKeyRepo.setUserApiKey(secretTestKey)
        apiKeyRepo.getMaskedUserApiKey()

        val client = createMockClient(statusCode = 403, responseBody = "{\"message\":\"Forbidden\"}")
        val remoteDataSource = ApiSportsRemoteDataSource(apiKeyProvider = { secretTestKey }, client = client)
        remoteDataSource.validateApiKey(secretTestKey)

        // Verificar todos los logs capturados por ShadowLog
        val logs = ShadowLog.getLogs()
        for (logItem in logs) {
            assertFalse(
                "La clave privada jamás debe aparecer en ningún mensaje de log: ${logItem.msg}",
                logItem.msg.contains(secretTestKey)
            )
        }
    }

    // 12. SportsRepository utiliza la clave del usuario cuando existe
    @Test
    fun test12_sportsRepositoryUtilizaLaClaveDelUsuarioCuandoExiste() {
        val userSecretKey = "user_configured_key_1234"
        apiKeyRepo.setUserApiKey(userSecretKey)

        val resolved = SportsRepository.resolveSportsApiKey(context, apiKeyRepo)
        assertEquals("La clave del usuario debe tener prioridad absoluta", userSecretKey, resolved)
    }

    // 13. No se modifica SportsChannelMatcher
    @Test
    fun test13_noSeModificaSportsChannelMatcher() = runBlocking {
        val matcher = DefaultSportsChannelMatcher()
        val event = SportsEvent(
            id = "test_fb_1",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Real Madrid",
            awayTeam = "Barcelona",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.LIVE
        )
        val channels = listOf(
            IptvItem(name = "M+ LaLiga | Real Madrid vs Barcelona", group = "DEPORTES", url = "http://stream.m3u8"),
            IptvItem(name = "Canal Cine", group = "CINE", url = "http://cine.m3u8")
        )

        val result = matcher.matchChannel(event, channels)
        assertEquals(MatchDecision.AUTO_PLAY, result.decision)
        assertEquals("M+ LaLiga | Real Madrid vs Barcelona", result.topMatch?.channel?.name)
    }

    // 14. No se rompe el polling
    @Test
    fun test14_noSeRompeElPolling() = runBlocking {
        var callCount = 0
        val mockDataSource = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> {
                callCount++
                return listOf(
                    SportsEvent(
                        id = "p_$callCount",
                        sport = sport.id,
                        league = "League $callCount",
                        homeTeam = "Team A",
                        awayTeam = "Team B",
                        startTime = System.currentTimeMillis(),
                        status = SportsEventStatus.LIVE
                    )
                )
            }
            override suspend fun getEventsByDate(sport: SportsSport, date: java.time.LocalDate): List<SportsEvent> = emptyList()
            override suspend fun validateApiKey(apiKey: String): ApiKeyValidationResult = ApiKeyValidationResult.Valid
        }

        val repo = SportsRepository(mockDataSource, apiKeyRepo)
        val initial = repo.getLiveEvents(SportsSport.FOOTBALL)
        assertEquals(1, callCount)
        assertEquals("p_1", initial.first().id)

        // Cached call (TTL no expirado)
        val cached = repo.getLiveEvents(SportsSport.FOOTBALL, forceRefresh = false)
        assertEquals(1, callCount) // No vuelve a llamar
        assertEquals("p_1", cached.first().id)

        // Force refresh
        val refreshed = repo.getLiveEvents(SportsSport.FOOTBALL, forceRefresh = true)
        assertEquals(2, callCount)
        assertEquals("p_2", refreshed.first().id)
    }

    // 15. No se rompen los parsers existentes
    @Test
    fun test15_noSeRompenLosParsersExistentes() {
        val footballEmpty = FootballSportsParser.parse("{}", isLiveOnly = true)
        assertTrue(footballEmpty.isEmpty())

        val basketballEmpty = BasketballSportsParser.parse("{}", isLiveOnly = true)
        assertTrue(basketballEmpty.isEmpty())

        val baseballEmpty = BaseballSportsParser.parse("{}", isLiveOnly = true)
        assertTrue(baseballEmpty.isEmpty())

        val rugbyEmpty = RugbySportsParser.parse("{}", isLiveOnly = true)
        assertTrue(rugbyEmpty.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS PARA MOCK HTTP
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

    private fun createFailingNetworkClient(exception: IOException): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { _ ->
                throw exception
            }
            .build()
    }
}
