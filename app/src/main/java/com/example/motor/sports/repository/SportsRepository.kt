package com.example.motor.sports.repository

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.remote.ApiKeyValidationResult
import com.example.motor.sports.remote.ApiSportsRemoteDataSource
import com.example.motor.sports.remote.SportsRemoteDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

sealed class SportStatus {
    object Ok : SportStatus()
    data class Unauthorized(val message: String, val payload: String?) : SportStatus()
    data class Error(val message: String) : SportStatus()
    object NotSupported : SportStatus()
}

/**
 * Excepción específica cuando falla el refresco de datos pero existen datos previos válidos en caché.
 */
class SportsRefreshException(
    message: String,
    val cachedData: List<SportsEvent>,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Repositorio del módulo de Deportes.
 *
 * Mantiene total aislamiento con respecto a IPTV, ExoPlayer, Compose y la UI.
 *
 * REGLAS (FASE 2.1):
 * - LIVE TTL = 60 segundos.
 * - DATE TTL = 5 minutos.
 * - Filtra de forma estricta los eventos FINISHED y CANCELLED para que NUNCA aparezcan.
 * - Si no hay API key configurada, retorna listas vacías.
 * - Si falla la red y existen datos previos, los conserva y señaliza SportsRefreshException.
 * - Si no existen datos previos y falla la red, retorna lista vacía sin inventar ningún dato.
 */
class SportsRepository(
    private val remoteDataSource: SportsRemoteDataSource,
    private val apiKeyRepository: SportsApiKeyRepository? = null
) {
    companion object {
        private const val TAG = "SportsRemoteDataSource"

        @Volatile
        private var INSTANCE: SportsRepository? = null

        fun getInstance(context: Context): SportsRepository {
            return INSTANCE ?: synchronized(this) {
                val apiKeyRepo = DefaultSportsApiKeyRepository.getInstance(context.applicationContext)
                INSTANCE ?: SportsRepository(
                    remoteDataSource = com.example.motor.sports.remote.OpenSportsRemoteDataSource(),
                    apiKeyRepository = apiKeyRepo
                ).also { INSTANCE = it }
            }
        }

        fun resolveSportsApiKey(context: Context?, apiKeyRepo: SportsApiKeyRepository? = null): String {
            // 1. PRIORIDAD: API key guardada por el usuario en SportsApiKeyRepository
            val userKey = apiKeyRepo?.getUserApiKey()?.trim()
            if (!userKey.isNullOrBlank()) {
                return userKey
            }

            // 2. Secret / BuildConfig de desarrollo, SOLO si realmente está disponible
            val devKey = getDevelopmentSecretKey()
            if (!devKey.isNullOrBlank() && devKey != "MY_SPORTS_API_KEY" && devKey != "DEFAULT_SPORTS_API_KEY") {
                return devKey
            }

            // 3. Estado "API no configurada"
            return ""
        }

        fun getDevelopmentSecretKey(): String? {
            try {
                val field = BuildConfig::class.java.getField("SPORTS_API_KEY")
                val key = field.get(null) as? String
                if (!key.isNullOrBlank()) return key.trim()
            } catch (_: Exception) {}

            val envKey = System.getenv("SPORTS_API_KEY")
            if (!envKey.isNullOrBlank()) return envKey.trim()

            return null
        }
    }

    private val liveCache = ConcurrentHashMap<String, Pair<Long, List<SportsEvent>>>()
    private val dateCache = ConcurrentHashMap<String, Pair<Long, List<SportsEvent>>>()
    private val sportStatusMap = ConcurrentHashMap<SportsSport, SportStatus>()

    private val LIVE_TTL_MS = 60_000L // 60 seconds
    private val DATE_TTL_MS = 300_000L // 5 minutes

    fun getSportStatus(sport: SportsSport): SportStatus {
        return sportStatusMap[sport] ?: SportStatus.Ok
    }

    fun isApiKeyConfigured(): Boolean {
        return remoteDataSource.isApiKeyConfigured()
    }

    fun hasUserApiKey(): Boolean {
        return apiKeyRepository?.hasUserApiKey() == true
    }

    fun getMaskedUserApiKey(): String? {
        return apiKeyRepository?.getMaskedUserApiKey()
    }

    suspend fun saveAndValidateUserApiKey(apiKey: String): ApiKeyValidationResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return ApiKeyValidationResult.InvalidKey
        }

        val result = remoteDataSource.validateApiKey(cleanKey)
        if (result is ApiKeyValidationResult.Valid) {
            apiKeyRepository?.setUserApiKey(cleanKey)
            clearCache()
        }
        return result
    }

    fun removeUserApiKey() {
        apiKeyRepository?.clearUserApiKey()
        clearCache()
    }

    fun getCachedEventCount(sport: SportsSport): Int {
        val today = LocalDate.now(ZoneId.systemDefault())
        val tomorrow = today.plusDays(1)
        val liveCount = liveCache[sport.id]?.second?.size ?: 0
        val todayCount = dateCache["${sport.id}_today_${today}"]?.second?.size ?: 0
        val tomorrowCount = dateCache["${sport.id}_tomorrow_${tomorrow}"]?.second?.size ?: 0
        val weekCount = dateCache["${sport.id}_week_${today}"]?.second?.size ?: 0
        return liveCount + todayCount + tomorrowCount + weekCount
    }

    fun getAllCachedEventCounts(): Map<SportsSport, Int> {
        return SportsSport.entries.associateWith { getCachedEventCount(it) }
    }

    suspend fun getLiveEvents(sport: SportsSport, forceRefresh: Boolean = false): List<SportsEvent> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext emptyList()
        }

        pruneExpiredCaches()

        val cacheKey = sport.id
        val cached = liveCache[cacheKey]
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && (now - cached.first) < LIVE_TTL_MS) {
            return@withContext cached.second
        }

        try {
            val events = remoteDataSource.getLiveEvents(sport)
                .filter { it.status != SportsEventStatus.FINISHED && it.status != SportsEventStatus.CANCELLED }

            sportStatusMap[sport] = SportStatus.Ok
            liveCache[cacheKey] = Pair(now, events)
            events
        } catch (e: SportsUnauthorizedException) {
            Log.w(TAG, "Acceso no autorizado para $sport en getLiveEvents: ${e.message}")
            sportStatusMap[sport] = SportStatus.Unauthorized(e.message ?: "No autorizado", e.errorPayload)
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos en vivo de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: SportsNotSupportedException) {
            Log.d(TAG, "Deporte no soportado en getLiveEvents: $sport")
            sportStatusMap[sport] = SportStatus.NotSupported
            emptyList()
        } catch (e: SportsHttpException) {
            Log.e(TAG, "Error HTTP ${e.statusCode} en getLiveEvents para $sport: ${e.message}")
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error HTTP")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos en vivo de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo eventos en vivo para $sport: ${e.message}", e)
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error de red")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos en vivo de $sport", cached.second, e)
            }
            emptyList()
        }
    }

    suspend fun getTodayEvents(sport: SportsSport, forceRefresh: Boolean = false): List<SportsEvent> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext emptyList()
        }

        pruneExpiredCaches()

        val today = LocalDate.now(ZoneId.systemDefault())
        val cacheKey = "${sport.id}_today_${today}"
        val cached = dateCache[cacheKey]
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && (now - cached.first) < DATE_TTL_MS) {
            return@withContext cached.second
        }

        try {
            val events = remoteDataSource.getEventsByDate(sport, today)
                .filter { it.status != SportsEventStatus.FINISHED && it.status != SportsEventStatus.CANCELLED }

            sportStatusMap[sport] = SportStatus.Ok
            dateCache[cacheKey] = Pair(now, events)
            events
        } catch (e: SportsUnauthorizedException) {
            Log.w(TAG, "Acceso no autorizado para $sport en getTodayEvents: ${e.message}")
            sportStatusMap[sport] = SportStatus.Unauthorized(e.message ?: "No autorizado", e.errorPayload)
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de hoy de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: SportsNotSupportedException) {
            Log.d(TAG, "Deporte no soportado en getTodayEvents: $sport")
            sportStatusMap[sport] = SportStatus.NotSupported
            emptyList()
        } catch (e: SportsHttpException) {
            Log.e(TAG, "Error HTTP ${e.statusCode} en getTodayEvents para $sport ($today): ${e.message}")
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error HTTP")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de hoy de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo eventos de hoy para $sport ($today): ${e.message}", e)
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error de red")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de hoy de $sport", cached.second, e)
            }
            emptyList()
        }
    }

    suspend fun getTomorrowEvents(sport: SportsSport, forceRefresh: Boolean = false): List<SportsEvent> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext emptyList()
        }

        pruneExpiredCaches()

        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1)
        val cacheKey = "${sport.id}_tomorrow_${tomorrow}"
        val cached = dateCache[cacheKey]
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && (now - cached.first) < DATE_TTL_MS) {
            return@withContext cached.second
        }

        try {
            val events = remoteDataSource.getEventsByDate(sport, tomorrow)
                .filter { it.status != SportsEventStatus.FINISHED && it.status != SportsEventStatus.CANCELLED }

            sportStatusMap[sport] = SportStatus.Ok
            dateCache[cacheKey] = Pair(now, events)
            events
        } catch (e: SportsUnauthorizedException) {
            Log.w(TAG, "Acceso no autorizado para $sport en getTomorrowEvents: ${e.message}")
            sportStatusMap[sport] = SportStatus.Unauthorized(e.message ?: "No autorizado", e.errorPayload)
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de mañana de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: SportsNotSupportedException) {
            Log.d(TAG, "Deporte no soportado en getTomorrowEvents: $sport")
            sportStatusMap[sport] = SportStatus.NotSupported
            emptyList()
        } catch (e: SportsHttpException) {
            Log.e(TAG, "Error HTTP ${e.statusCode} en getTomorrowEvents para $sport ($tomorrow): ${e.message}")
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error HTTP")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de mañana de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo eventos de mañana para $sport ($tomorrow): ${e.message}", e)
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error de red")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de mañana de $sport", cached.second, e)
            }
            emptyList()
        }
    }

    suspend fun getWeekEvents(sport: SportsSport, forceRefresh: Boolean = false): List<SportsEvent> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext emptyList()
        }

        pruneExpiredCaches()

        val today = LocalDate.now(ZoneId.systemDefault())
        val startDate = today.plusDays(2)
        val endDate = today.plusDays(7)
        val cacheKey = "${sport.id}_week_${today}"
        val cached = dateCache[cacheKey]
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && (now - cached.first) < DATE_TTL_MS) {
            return@withContext cached.second
        }

        try {
            val events = remoteDataSource.getUpcomingWeekEvents(sport, startDate, endDate)
                .filter { it.status != SportsEventStatus.FINISHED && it.status != SportsEventStatus.CANCELLED }

            sportStatusMap[sport] = SportStatus.Ok
            dateCache[cacheKey] = Pair(now, events)
            events
        } catch (e: SportsUnauthorizedException) {
            Log.w(TAG, "Acceso no autorizado para $sport en getWeekEvents: ${e.message}")
            sportStatusMap[sport] = SportStatus.Unauthorized(e.message ?: "No autorizado", e.errorPayload)
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de la semana de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: SportsNotSupportedException) {
            Log.d(TAG, "Deporte no soportado en getWeekEvents: $sport")
            sportStatusMap[sport] = SportStatus.NotSupported
            emptyList()
        } catch (e: SportsHttpException) {
            Log.e(TAG, "Error HTTP ${e.statusCode} en getWeekEvents para $sport: ${e.message}")
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error HTTP")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de la semana de $sport", cached.second, e)
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo eventos de la semana para $sport: ${e.message}", e)
            sportStatusMap[sport] = SportStatus.Error(e.message ?: "Error de red")
            if (cached != null && cached.second.isNotEmpty()) {
                throw SportsRefreshException("Error al refrescar eventos de la semana de $sport", cached.second, e)
            }
            emptyList()
        }
    }

    private fun pruneExpiredCaches() {
        val now = System.currentTimeMillis()
        liveCache.entries.removeIf { (now - it.value.first) > (LIVE_TTL_MS * 3) }
        dateCache.entries.removeIf { (now - it.value.first) > (DATE_TTL_MS * 2) }
    }

    fun clearCache() {
        liveCache.clear()
        dateCache.clear()
        sportStatusMap.clear()
    }
}
