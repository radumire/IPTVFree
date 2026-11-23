package com.example.motor.sports.remote

import android.util.Log
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.parser.BaseballSportsParser
import com.example.motor.sports.parser.BasketballSportsParser
import com.example.motor.sports.parser.FootballSportsParser
import com.example.motor.sports.parser.RugbySportsParser
import com.example.motor.sports.repository.SportsHttpException
import com.example.motor.sports.repository.SportsNotSupportedException
import com.example.motor.sports.repository.SportsUnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

sealed class ApiKeyValidationResult {
    object Valid : ApiKeyValidationResult()
    object InvalidKey : ApiKeyValidationResult()
    data class NetworkError(val message: String) : ApiKeyValidationResult()
}

interface SportsRemoteDataSource {
    fun isApiKeyConfigured(): Boolean
    suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent>
    suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent>
    suspend fun getUpcomingWeekEvents(sport: SportsSport, startDate: LocalDate, endDate: LocalDate): List<SportsEvent> = emptyList()
    suspend fun validateApiKey(apiKey: String): ApiKeyValidationResult = ApiKeyValidationResult.Valid
}

/**
 * Implementación oficial y segura para API-Sports.
 *
 * REGLAS ESTRICTAS DE CONSOLIDACIÓN (FASE 2.1):
 * - NUNCA genera datos ficticios ni eventos simulados.
 * - Si no hay API key configurada -> devuelve lista vacía.
 * - Si ocurre error HTTP, timeout o JSON inválido -> registra en Logcat con TAG SportsRemoteDataSource
 *   y propaga / devuelve vacío sin inventar ningún dato.
 * - Cada deporte cuenta con su parser real o devuelve lista vacía si aún no dispone de endpoint verificado.
 */
class ApiSportsRemoteDataSource(
    private val apiKeyProvider: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : SportsRemoteDataSource {

    companion object {
        const val TAG = "SportsRemoteDataSource"
        private const val FOOTBALL_BASE_URL = "https://v3.football.api-sports.io"
        private const val BASKETBALL_BASE_URL = "https://v1.basketball.api-sports.io"
        private const val BASEBALL_BASE_URL = "https://v1.baseball.api-sports.io"
        private const val RUGBY_BASE_URL = "https://v1.rugby.api-sports.io"
    }

    override fun isApiKeyConfigured(): Boolean {
        val key = apiKeyProvider()?.trim()
        return !key.isNullOrBlank()
    }

    override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrBlank()) {
            Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = N/A | HTTP = N/A | RESULT_COUNT = 0 | ERROR = Clave SPORTS_API_KEY no configurada")
            return@withContext emptyList()
        }

        val url = getLiveUrlForSport(sport)
        if (url == null) {
            Log.d(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = No endpoint configured | HTTP = N/A | RESULT_COUNT = 0 | ERROR = NOT_SUPPORTED (Deporte sin endpoint implementado en este proveedor)")
            throw SportsNotSupportedException(sport)
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("x-apisports-key", key)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                var apiErrorText: String? = null
                var rawResultsCount = -1
                try {
                    val root = org.json.JSONObject(body)
                    rawResultsCount = root.optInt("results", -1)
                    val errorsObj = root.optJSONObject("errors")
                    val errorsArr = root.optJSONArray("errors")
                    if (errorsObj != null && errorsObj.length() > 0) {
                        apiErrorText = errorsObj.toString()
                    } else if (errorsArr != null && errorsArr.length() > 0) {
                        apiErrorText = errorsArr.toString()
                    }
                } catch (_: Exception) {}

                if (code == 401 || code == 403) {
                    val authError = apiErrorText ?: "HTTP $code: No autorizado para $sport"
                    Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = UNAUTHORIZED_SUBSCRIPTION: $authError")
                    throw SportsUnauthorizedException(sport, "No autorizado o sin suscripción activa para $sport", authError)
                }

                if (!response.isSuccessful) {
                    val errorMsg = "HTTP $code: ${response.message} (Payload: ${apiErrorText ?: body.take(120)})"
                    Log.e(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = $errorMsg")
                    throw SportsHttpException(sport, code, errorMsg, apiErrorText ?: body)
                }

                if (apiErrorText != null) {
                    val isAuthError = apiErrorText.contains("token", ignoreCase = true) ||
                        apiErrorText.contains("subscription", ignoreCase = true) ||
                        apiErrorText.contains("unauthorized", ignoreCase = true) ||
                        apiErrorText.contains("forbidden", ignoreCase = true) ||
                        apiErrorText.contains("key", ignoreCase = true)

                    if (isAuthError) {
                        Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = UNAUTHORIZED_SUBSCRIPTION: $apiErrorText")
                        throw SportsUnauthorizedException(sport, "Suscripción no activa para $sport en API-Sports", apiErrorText)
                    }

                    val isQuotaError = apiErrorText.contains("requests", ignoreCase = true) ||
                        apiErrorText.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = QUOTA_EXCEEDED: $apiErrorText")
                        throw SportsHttpException(sport, 429, "Límite de peticiones alcanzado en API-Sports", apiErrorText)
                    }

                    Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = API_ERROR: $apiErrorText")
                    throw SportsHttpException(sport, code, "Error devuelto por la API: $apiErrorText", apiErrorText)
                }

                val events = parseResponseForSport(sport, body, isLiveOnly = true)
                if (events.isEmpty()) {
                    Log.i(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = None (Sin eventos programados)")
                } else {
                    Log.i(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = ${events.size} | ERROR = None")
                }
                events
            }
        } catch (e: SportsUnauthorizedException) {
            throw e
        } catch (e: SportsHttpException) {
            throw e
        } catch (e: SportsNotSupportedException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = FAILED | RESULT_COUNT = 0 | ERROR = NETWORK_ERROR: ${e.message}", e)
            throw e
        }
    }

    override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrBlank()) {
            Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = N/A | HTTP = N/A | RESULT_COUNT = 0 | ERROR = Clave SPORTS_API_KEY no configurada")
            return@withContext emptyList()
        }

        val url = getDateUrlForSport(sport, date)
        if (url == null) {
            Log.d(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = No endpoint configured | HTTP = N/A | RESULT_COUNT = 0 | ERROR = NOT_SUPPORTED (Deporte sin endpoint implementado en este proveedor)")
            throw SportsNotSupportedException(sport)
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("x-apisports-key", key)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                var apiErrorText: String? = null
                var rawResultsCount = -1
                try {
                    val root = org.json.JSONObject(body)
                    rawResultsCount = root.optInt("results", -1)
                    val errorsObj = root.optJSONObject("errors")
                    val errorsArr = root.optJSONArray("errors")
                    if (errorsObj != null && errorsObj.length() > 0) {
                        apiErrorText = errorsObj.toString()
                    } else if (errorsArr != null && errorsArr.length() > 0) {
                        apiErrorText = errorsArr.toString()
                    }
                } catch (_: Exception) {}

                if (code == 401 || code == 403) {
                    val authError = apiErrorText ?: "HTTP $code: No autorizado para $sport"
                    Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = UNAUTHORIZED_SUBSCRIPTION: $authError")
                    throw SportsUnauthorizedException(sport, "No autorizado o sin suscripción activa para $sport", authError)
                }

                if (!response.isSuccessful) {
                    val errorMsg = "HTTP $code: ${response.message} (Payload: ${apiErrorText ?: body.take(120)})"
                    Log.e(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = $errorMsg")
                    throw SportsHttpException(sport, code, errorMsg, apiErrorText ?: body)
                }

                if (apiErrorText != null) {
                    val isAuthError = apiErrorText.contains("token", ignoreCase = true) ||
                        apiErrorText.contains("subscription", ignoreCase = true) ||
                        apiErrorText.contains("unauthorized", ignoreCase = true) ||
                        apiErrorText.contains("forbidden", ignoreCase = true) ||
                        apiErrorText.contains("key", ignoreCase = true)

                    if (isAuthError) {
                        Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = UNAUTHORIZED_SUBSCRIPTION: $apiErrorText")
                        throw SportsUnauthorizedException(sport, "Suscripción no activa para $sport en API-Sports", apiErrorText)
                    }

                    val isQuotaError = apiErrorText.contains("requests", ignoreCase = true) ||
                        apiErrorText.contains("quota", ignoreCase = true)
                    if (isQuotaError) {
                        Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = QUOTA_EXCEEDED: $apiErrorText")
                        throw SportsHttpException(sport, 429, "Límite de peticiones alcanzado en API-Sports", apiErrorText)
                    }

                    Log.w(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = API_ERROR: $apiErrorText")
                    throw SportsHttpException(sport, code, "Error devuelto por la API: $apiErrorText", apiErrorText)
                }

                val events = parseResponseForSport(sport, body, isLiveOnly = false)
                if (events.isEmpty()) {
                    Log.i(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = 0 | ERROR = None (Sin eventos programados)")
                } else {
                    Log.i(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = $code | RESULT_COUNT = ${events.size} | ERROR = None")
                }
                events
            }
        } catch (e: SportsUnauthorizedException) {
            throw e
        } catch (e: SportsHttpException) {
            throw e
        } catch (e: SportsNotSupportedException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[DIAGNOSTIC] SPORT = $sport | REQUEST = $url | HTTP = FAILED | RESULT_COUNT = 0 | ERROR = NETWORK_ERROR: ${e.message}", e)
            throw e
        }
    }

    override suspend fun validateApiKey(apiKey: String): ApiKeyValidationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val keyPresent = cleanKey.isNotEmpty()
        val keyLength = cleanKey.length
        val url = "$FOOTBALL_BASE_URL/status"

        Log.i(
            TAG,
            "validateApiKey START\n" +
            "endpoint=$url\n" +
            "keyPresent=$keyPresent\n" +
            "keyLength=$keyLength"
        )

        if (!keyPresent) {
            Log.w(
                TAG,
                "validateApiKey END\n" +
                "endpoint=$url\n" +
                "keyPresent=false\n" +
                "keyLength=0\n" +
                "HTTP=N/A\n" +
                "result=InvalidKey (clave vacía)"
            )
            return@withContext ApiKeyValidationResult.InvalidKey
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("x-apisports-key", cleanKey)
                .addHeader("Accept", "application/json")
                .build()

            val headerPresent = request.header("x-apisports-key") != null

            client.newCall(request).execute().use { response ->
                val code = response.code
                val rawBody = response.body?.string() ?: ""
                val sanitizedBody = if (cleanKey.isNotEmpty()) rawBody.replace(cleanKey, "[REDACTED_API_KEY]") else rawBody

                val category = when (code) {
                    200 -> "HTTP 200"
                    401 -> "HTTP 401"
                    403 -> "HTTP 403"
                    else -> "otro código HTTP"
                }

                Log.i(
                    TAG,
                    "validateApiKey RESPONSE\n" +
                    "endpoint=$url\n" +
                    "keyPresent=$headerPresent\n" +
                    "keyLength=$keyLength\n" +
                    "HTTP=$code ($category)\n" +
                    "responseBody=$sanitizedBody"
                )

                if (code == 401 || code == 403) {
                    Log.w(
                        TAG,
                        "validateApiKey RESULT\n" +
                        "endpoint=$url\n" +
                        "keyPresent=$headerPresent\n" +
                        "keyLength=$keyLength\n" +
                        "HTTP=$code ($category)\n" +
                        "responseBody=$sanitizedBody\n" +
                        "result=$category (Acceso denegado o clave no autorizada)"
                    )
                    return@withContext ApiKeyValidationResult.InvalidKey
                }

                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "validateApiKey RESULT\n" +
                        "endpoint=$url\n" +
                        "keyPresent=$headerPresent\n" +
                        "keyLength=$keyLength\n" +
                        "HTTP=$code ($category)\n" +
                        "responseBody=$sanitizedBody\n" +
                        "result=$category (Respuesta HTTP no exitosa)"
                    )
                    return@withContext ApiKeyValidationResult.InvalidKey
                }

                try {
                    val json = org.json.JSONObject(rawBody)
                    val errorsObj = json.optJSONObject("errors")
                    val errorsArr = json.optJSONArray("errors")
                    val hasErrors = (errorsObj != null && errorsObj.length() > 0) || (errorsArr != null && errorsArr.length() > 0)

                    if (hasErrors) {
                        val errorContent = errorsObj?.toString() ?: errorsArr?.toString() ?: "desconocido"
                        val safeErrorContent = if (cleanKey.isNotEmpty()) errorContent.replace(cleanKey, "[REDACTED_API_KEY]") else errorContent
                        Log.w(
                            TAG,
                            "validateApiKey RESULT\n" +
                            "endpoint=$url\n" +
                            "keyPresent=$headerPresent\n" +
                            "keyLength=$keyLength\n" +
                            "HTTP=$code (HTTP 200 con errores reportados por API-Sports)\n" +
                            "errors=$safeErrorContent\n" +
                            "responseBody=$sanitizedBody\n" +
                            "result=HTTP 200 (Respuesta de API-Sports reporta errores en la clave)"
                        )
                        ApiKeyValidationResult.InvalidKey
                    } else {
                        Log.i(
                            TAG,
                            "validateApiKey RESULT\n" +
                            "endpoint=$url\n" +
                            "keyPresent=$headerPresent\n" +
                            "keyLength=$keyLength\n" +
                            "HTTP=$code (HTTP 200 OK sin errores)\n" +
                            "responseBody=$sanitizedBody\n" +
                            "result=HTTP 200 (Clave validada correctamente)"
                        )
                        ApiKeyValidationResult.Valid
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "validateApiKey RESULT\n" +
                        "endpoint=$url\n" +
                        "keyPresent=$headerPresent\n" +
                        "keyLength=$keyLength\n" +
                        "HTTP=$code (excepción al procesar la respuesta)\n" +
                        "exception=${e.javaClass.name}: ${e.message}\n" +
                        "responseBody=$sanitizedBody\n" +
                        "result=HTTP 200 (Excepción al parsear JSON, considerando válida por respuesta HTTP 200)"
                    )
                    ApiKeyValidationResult.Valid
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(
                TAG,
                "validateApiKey FAILURE\n" +
                "endpoint=$url\n" +
                "keyPresent=true\n" +
                "keyLength=$keyLength\n" +
                "HTTP=timeout\n" +
                "exception=${e.javaClass.name}: ${e.message}\n" +
                "result=timeout"
            )
            ApiKeyValidationResult.NetworkError("No se pudo comprobar la API key. Comprueba tu conexión.")
        } catch (e: IOException) {
            Log.e(
                TAG,
                "validateApiKey FAILURE\n" +
                "endpoint=$url\n" +
                "keyPresent=true\n" +
                "keyLength=$keyLength\n" +
                "HTTP=error de red\n" +
                "exception=${e.javaClass.name}: ${e.message}\n" +
                "result=error de red"
            )
            ApiKeyValidationResult.NetworkError("No se pudo comprobar la API key. Comprueba tu conexión.")
        } catch (e: Exception) {
            Log.e(
                TAG,
                "validateApiKey FAILURE\n" +
                "endpoint=$url\n" +
                "keyPresent=true\n" +
                "keyLength=$keyLength\n" +
                "HTTP=excepción al procesar la respuesta\n" +
                "exception=${e.javaClass.name}: ${e.message}\n" +
                "result=excepción al procesar la respuesta"
            )
            ApiKeyValidationResult.InvalidKey
        }
    }

    private fun getLiveUrlForSport(sport: SportsSport): String? {
        return when (sport) {
            SportsSport.FOOTBALL -> "$FOOTBALL_BASE_URL/fixtures?live=all"
            SportsSport.BASKETBALL -> "$BASKETBALL_BASE_URL/games?live=all"
            SportsSport.BASEBALL -> "$BASEBALL_BASE_URL/games?live=all"
            SportsSport.RUGBY -> "$RUGBY_BASE_URL/games?live=all"
            else -> null // Deportes sin endpoint real todavía devuelven null de forma segura
        }
    }

    private fun getDateUrlForSport(sport: SportsSport, date: LocalDate): String? {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val zoneId = ZoneId.systemDefault().id

        return when (sport) {
            SportsSport.FOOTBALL -> "$FOOTBALL_BASE_URL/fixtures?date=$dateStr&timezone=$zoneId"
            SportsSport.BASKETBALL -> "$BASKETBALL_BASE_URL/games?date=$dateStr&timezone=$zoneId"
            SportsSport.BASEBALL -> "$BASEBALL_BASE_URL/games?date=$dateStr&timezone=$zoneId"
            SportsSport.RUGBY -> "$RUGBY_BASE_URL/games?date=$dateStr&timezone=$zoneId"
            else -> null // Deportes sin endpoint real todavía devuelven null de forma segura
        }
    }

    private fun parseResponseForSport(sport: SportsSport, jsonString: String, isLiveOnly: Boolean): List<SportsEvent> {
        return when (sport) {
            SportsSport.FOOTBALL -> FootballSportsParser.parse(jsonString, isLiveOnly)
            SportsSport.BASKETBALL -> BasketballSportsParser.parse(jsonString, isLiveOnly)
            SportsSport.BASEBALL -> BaseballSportsParser.parse(jsonString, isLiveOnly)
            SportsSport.RUGBY -> RugbySportsParser.parse(jsonString, isLiveOnly)
            else -> emptyList()
        }
    }
}
