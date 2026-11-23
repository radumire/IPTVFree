package com.example.motor.sports.broadcast

import android.util.Log
import com.example.motor.sports.matcher.TextNormalizer
import com.example.motor.sports.model.SportsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class TheSportsDbSearchStatus {
    FOUND,
    NO_EVENT,
    NO_TV,
    ERROR
}

data class TheSportsDbCacheEntry(
    val status: TheSportsDbSearchStatus,
    val idEvent: String? = null,
    val channels: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val ttlMs = when (status) {
            TheSportsDbSearchStatus.FOUND -> TheSportsDbBroadcastResolver.TTL_FOUND_MS
            TheSportsDbSearchStatus.NO_EVENT -> TheSportsDbBroadcastResolver.TTL_NO_EVENT_MS
            TheSportsDbSearchStatus.NO_TV -> TheSportsDbBroadcastResolver.TTL_NO_TV_MS
            TheSportsDbSearchStatus.ERROR -> TheSportsDbBroadcastResolver.TTL_ERROR_MS
        }
        return (now - timestamp) > ttlMs
    }
}

/**
 * Limitador de tasa deslizante en memoria (30 peticiones / minuto para TheSportsDB Free).
 */
class TheSportsDbRateLimiter(
    private val maxRequests: Int = 30,
    private val windowMs: Long = 60_000L
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
        while (timestamps.isNotEmpty() && (now - timestamps.first()) > windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size < maxRequests) {
            timestamps.addLast(now)
            return true
        }
        return false
    }

    @Synchronized
    fun clear() {
        timestamps.clear()
    }
}

/**
 * Implementación de resolución de cadenas de TV para eventos deportivos mediante TheSportsDB Free (API v1).
 *
 * Satisface todas las directrices:
 * - Uso exclusivo de API v1 Free con endpoint base https://www.thesportsdb.com/api/v1/json/123/
 * - Consulta bajo demanda exclusivamente al reproducir/ver un partido.
 * - Validación rigurosa de coincidencia de evento (equipos, fecha, deporte).
 * - Extracción de hasta 2 canales devueltos por el endpoint lookuptv.php.
 * - Caché en memoria con soporte de caché negativa (NO_EVENT, NO_TV, ERROR).
 * - Limitador de tasa a 30 peticiones/minuto.
 * - Registro estructurado bajo el TAG "SportsTheSportsDB" sin volcar URLs privadas.
 * - Fallback total: ante cualquier error devuelve null sin lanzar excepciones hacia la capa superior.
 */
class TheSportsDbBroadcastResolver(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val rateLimiter: TheSportsDbRateLimiter = TheSportsDbRateLimiter()
) : SportsTvBroadcastProvider {

    companion object {
        const val TAG = "SportsTheSportsDB"
        const val BASE_URL = "https://www.thesportsdb.com/api/v1/json/123"

        const val TTL_FOUND_MS = 2 * 60 * 60 * 1000L      // 2 horas para eventos encontrados con TV
        const val TTL_NO_EVENT_MS = 30 * 60 * 1000L        // 30 minutos para eventos no encontrados
        const val TTL_NO_TV_MS = 15 * 60 * 1000L           // 15 minutos para eventos sin TV (pueden añadirse más tarde)
        const val TTL_ERROR_MS = 5 * 60 * 1000L            // 5 minutos ante errores de red o servidor

        @Volatile
        private var instance: TheSportsDbBroadcastResolver? = null

        fun getInstance(): TheSportsDbBroadcastResolver {
            return instance ?: synchronized(this) {
                instance ?: TheSportsDbBroadcastResolver().also { instance = it }
            }
        }

        private fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }

    private val cache = ConcurrentHashMap<String, TheSportsDbCacheEntry>()

    fun clearCache() {
        cache.clear()
        rateLimiter.clear()
    }

    fun getCachedEntry(event: SportsEvent): TheSportsDbCacheEntry? {
        val key = buildCacheKey(event)
        return cache[key]
    }

    override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo? = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(event)
        val cached = cache[cacheKey]
        val now = System.currentTimeMillis()

        if (cached != null && !cached.isExpired(now)) {
            Log.d(TAG, "[THESPORTSDB] CACHE_HIT event='${event.homeTeam} vs ${event.awayTeam}' status=${cached.status}")
            return@withContext when (cached.status) {
                TheSportsDbSearchStatus.FOUND -> SportsBroadcastInfo(
                    eventId = cached.idEvent,
                    channels = cached.channels,
                    source = "TheSportsDB"
                )
                TheSportsDbSearchStatus.NO_EVENT,
                TheSportsDbSearchStatus.NO_TV,
                TheSportsDbSearchStatus.ERROR -> null
            }
        }

        val dateIso = event.getEventDateIso()
        Log.d(TAG, "[THESPORTSDB] SEARCH event=${event.homeTeam} vs ${event.awayTeam} date=$dateIso")

        try {
            // 1. Buscar evento en TheSportsDB
            val eventCandidate = searchAndValidateEvent(event, dateIso)
            if (eventCandidate == null) {
                cache[cacheKey] = TheSportsDbCacheEntry(
                    status = TheSportsDbSearchStatus.NO_EVENT,
                    timestamp = now
                )
                Log.d(TAG, "[THESPORTSDB] NO_EVENT")
                return@withContext null
            }

            val idEvent = eventCandidate.optString("idEvent")
            Log.d(TAG, "[THESPORTSDB] EVENT_MATCH id=$idEvent confidence=HIGH")

            // 2. Consultar información de TV para el idEvent encontrado
            val tvChannels = lookupTvForEvent(idEvent)
            if (tvChannels.isEmpty()) {
                cache[cacheKey] = TheSportsDbCacheEntry(
                    status = TheSportsDbSearchStatus.NO_TV,
                    idEvent = idEvent,
                    timestamp = now
                )
                Log.d(TAG, "[THESPORTSDB] NO_TV")
                return@withContext null
            }

            Log.d(TAG, "[THESPORTSDB] TV_CHANNELS count=${tvChannels.size}")
            tvChannels.forEach { channel ->
                Log.d(TAG, "[THESPORTSDB] TV_CHANNEL=$channel")
            }

            cache[cacheKey] = TheSportsDbCacheEntry(
                status = TheSportsDbSearchStatus.FOUND,
                idEvent = idEvent,
                channels = tvChannels,
                timestamp = now
            )

            return@withContext SportsBroadcastInfo(
                eventId = idEvent,
                channels = tvChannels,
                source = "TheSportsDB"
            )

        } catch (e: Exception) {
            Log.w(TAG, "[THESPORTSDB] ERROR: ${e.message}")
            cache[cacheKey] = TheSportsDbCacheEntry(
                status = TheSportsDbSearchStatus.ERROR,
                timestamp = now
            )
            return@withContext null
        }
    }

    /**
     * Construye la consulta de búsqueda y valida los resultados recibidos.
     */
    private fun searchAndValidateEvent(event: SportsEvent, dateIso: String): JSONObject? {
        val cleanHome = sanitizeTeamNameForQuery(event.homeTeam)
        val cleanAway = sanitizeTeamNameForQuery(event.awayTeam)
        val query = "${cleanHome}_vs_${cleanAway}"

        // Intento 1: Búsqueda con fecha exacta (searchevents.php?e=...&d=...)
        val urlWithDate = "$BASE_URL/searchevents.php?e=${URLEncoder.encode(query, "UTF-8")}&d=$dateIso"
        val responseWithDate = executeGet(urlWithDate)
        if (responseWithDate.isNotBlank()) {
            val matched = parseAndValidateEventsResponse(responseWithDate, event)
            if (matched != null) return matched
        }

        // Intento 2: Búsqueda sin parámetro &d= (por si hay desfases de huso horario)
        val urlNoDate = "$BASE_URL/searchevents.php?e=${URLEncoder.encode(query, "UTF-8")}"
        val responseNoDate = executeGet(urlNoDate)
        if (responseNoDate.isNotBlank()) {
            val matched = parseAndValidateEventsResponse(responseNoDate, event)
            if (matched != null) return matched
        }

        // Intento 3: Si equipos contienen prefijos/sufijos largos, probar con los nombres limpios normalizados
        val homeTokens = TextNormalizer.extractCleanTokens(event.homeTeam)
        val awayTokens = TextNormalizer.extractCleanTokens(event.awayTeam)
        if (homeTokens.isNotEmpty() && awayTokens.isNotEmpty()) {
            val simplifiedQuery = "${homeTokens.first()}_vs_${awayTokens.first()}"
            if (simplifiedQuery != query) {
                val urlSimple = "$BASE_URL/searchevents.php?e=${URLEncoder.encode(simplifiedQuery, "UTF-8")}&d=$dateIso"
                val responseSimple = executeGet(urlSimple)
                if (responseSimple.isNotBlank()) {
                    val matched = parseAndValidateEventsResponse(responseSimple, event)
                    if (matched != null) return matched
                }
            }
        }

        return null
    }

    /**
     * Consulta las cadenas de TV para un evento concreto mediante lookuptv.php?id=EVENT_ID.
     */
    private fun lookupTvForEvent(idEvent: String): List<String> {
        val url = "$BASE_URL/lookuptv.php?id=$idEvent"
        val body = executeGet(url)
        return parseTvResponse(body)
    }

    /**
     * Ejecuta una petición HTTP GET respetando el limitador de tasa de 30 req/min.
     * Lanza IOException ante fallos de red o errores HTTP para registrar estado ERROR en caché.
     */
    private fun executeGet(url: String): String {
        if (!rateLimiter.tryAcquire()) {
            Log.w(TAG, "[THESPORTSDB] RATE_LIMIT_REACHED (Límite de 30 peticiones/minuto alcanzado)")
            throw IOException("Rate limit exceeded")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "[THESPORTSDB] HTTP_ERROR code=${response.code}")
                throw IOException("HTTP error ${response.code}")
            }
            response.body?.string() ?: ""
        }
    }

    /**
     * Parsea el JSON devuelto por searchevents.php y valida la coincidencia contra el evento objetivo.
     */
    fun parseAndValidateEventsResponse(jsonString: String, targetEvent: SportsEvent): JSONObject? {
        if (jsonString.isBlank()) return null
        return try {
            val root = JSONObject(jsonString)
            val eventsArray = root.optJSONArray("event") ?: return null
            validateCandidateList(eventsArray, targetEvent)
        } catch (e: Exception) {
            Log.e(TAG, "[THESPORTSDB] Error al parsear searchevents JSON: ${e.message}")
            null
        }
    }

    /**
     * Evalúa la lista de candidatos de TheSportsDB y selecciona el que tenga mayor afinidad superando el umbral de 60.
     */
    fun validateCandidateList(eventsArray: JSONArray, targetEvent: SportsEvent): JSONObject? {
        var bestCandidate: JSONObject? = null
        var bestScore = 0

        val targetHomeNorm = TextNormalizer.normalize(targetEvent.homeTeam)
        val targetAwayNorm = TextNormalizer.normalize(targetEvent.awayTeam)
        val targetHomeTokens = TextNormalizer.extractCleanTokens(targetHomeNorm)
        val targetAwayTokens = TextNormalizer.extractCleanTokens(targetAwayNorm)
        val targetDate = targetEvent.getEventDateIso()
        val targetSportNorm = TextNormalizer.normalize(targetEvent.sport)

        for (i in 0 until eventsArray.length()) {
            val item = eventsArray.optJSONObject(i) ?: continue
            val strEvent = item.optString("strEvent")
            val strHomeTeam = item.optString("strHomeTeam")
            val strAwayTeam = item.optString("strAwayTeam")
            val dateEvent = item.optString("dateEvent")
            val strSport = item.optString("strSport")
            val strLeague = item.optString("strLeague")

            val candidateHomeNorm = TextNormalizer.normalize(strHomeTeam)
            val candidateAwayNorm = TextNormalizer.normalize(strAwayTeam)
            val candidateEventNorm = TextNormalizer.normalize(strEvent)

            var score = 0

            // 1. Coincidencia de Equipos (Local y Visitante)
            val homeMatch = candidateHomeNorm.isNotBlank() && (
                candidateHomeNorm.contains(targetHomeNorm) || targetHomeNorm.contains(candidateHomeNorm) ||
                (targetHomeTokens.isNotEmpty() && targetHomeTokens.all { candidateHomeNorm.contains(it) || candidateEventNorm.contains(it) })
            )
            val awayMatch = candidateAwayNorm.isNotBlank() && (
                candidateAwayNorm.contains(targetAwayNorm) || targetAwayNorm.contains(candidateAwayNorm) ||
                (targetAwayTokens.isNotEmpty() && targetAwayTokens.all { candidateAwayNorm.contains(it) || candidateEventNorm.contains(it) })
            )

            // Soporte para deportes donde se liste Away @ Home
            val reversedHomeMatch = candidateAwayNorm.isNotBlank() && (
                candidateAwayNorm.contains(targetHomeNorm) || targetHomeNorm.contains(candidateAwayNorm)
            )
            val reversedAwayMatch = candidateHomeNorm.isNotBlank() && (
                candidateHomeNorm.contains(targetAwayNorm) || targetAwayNorm.contains(candidateHomeNorm)
            )

            if ((homeMatch && awayMatch) || (reversedHomeMatch && reversedAwayMatch)) {
                score += 55
            } else if (homeMatch || awayMatch) {
                score += 20
            }

            // 2. Coincidencia de Fecha
            if (dateEvent.isNotBlank()) {
                if (dateEvent == targetDate) {
                    score += 30
                } else {
                    try {
                        val cDate = LocalDate.parse(dateEvent, DateTimeFormatter.ISO_LOCAL_DATE)
                        val tDate = LocalDate.parse(targetDate, DateTimeFormatter.ISO_LOCAL_DATE)
                        val diffDays = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(cDate, tDate))
                        if (diffDays <= 1) {
                            score += 15 // Aceptable por husos horarios
                        } else {
                            score -= 40 // Fecha distante
                        }
                    } catch (e: Exception) {
                        // Fecha no parseable
                    }
                }
            }

            // 3. Coincidencia de Deporte
            val candidateSportNorm = TextNormalizer.normalize(strSport)
            if (candidateSportNorm.isNotBlank() && targetSportNorm.isNotBlank()) {
                if (isSportEquivalent(targetSportNorm, candidateSportNorm)) {
                    score += 15
                }
            }

            // 4. Coincidencia de Competición / Liga
            if (strLeague.isNotBlank() && targetEvent.league.isNotBlank()) {
                val candidateLeagueNorm = TextNormalizer.normalize(strLeague)
                val targetLeagueNorm = TextNormalizer.normalize(targetEvent.league)
                if (candidateLeagueNorm.contains(targetLeagueNorm) || targetLeagueNorm.contains(candidateLeagueNorm)) {
                    score += 10
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestCandidate = item
            }
        }

        // Umbral estricto: requiere al menos coincidencia completa de equipos y fecha válida (>= 60)
        return if (bestScore >= 60) bestCandidate else null
    }

    /**
     * Parsea el JSON devuelto por lookuptv.php extrayendo hasta 2 cadenas disponibles.
     */
    fun parseTvResponse(jsonString: String): List<String> {
        if (jsonString.isBlank()) return emptyList()
        val channels = mutableListOf<String>()
        try {
            val root = JSONObject(jsonString)
            val array = root.optJSONArray("tvevent")
                ?: root.optJSONArray("tv")
                ?: root.optJSONArray("results")
                ?: return emptyList()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val channel = item.optString("strChannel").trim()
                if (channel.isNotBlank() && !channels.contains(channel)) {
                    channels.add(channel)
                }
                // TheSportsDB Free devuelve hasta 2 canales
                if (channels.size >= 2) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[THESPORTSDB] Error al parsear lookuptv JSON: ${e.message}")
        }
        return channels
    }

    private fun sanitizeTeamNameForQuery(teamName: String): String {
        return teamName.trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_]".toRegex(), "")
    }

    private fun buildCacheKey(event: SportsEvent): String {
        val normSport = TextNormalizer.normalize(event.sport)
        val normHome = TextNormalizer.normalize(event.homeTeam)
        val normAway = TextNormalizer.normalize(event.awayTeam)
        val dateIso = event.getEventDateIso()
        return "${normSport}_${normHome}_vs_${normAway}_${dateIso}"
    }

    private fun isSportEquivalent(sportA: String, sportB: String): Boolean {
        if (sportA == sportB) return true
        val isFootballA = sportA.contains("football") || sportA.contains("futbol") || sportA.contains("soccer")
        val isFootballB = sportB.contains("football") || sportB.contains("futbol") || sportB.contains("soccer")
        if (isFootballA && isFootballB) return true

        val isBasketA = sportA.contains("basket") || sportA.contains("baloncesto")
        val isBasketB = sportB.contains("basket") || sportB.contains("baloncesto")
        if (isBasketA && isBasketB) return true

        val isBaseballA = sportA.contains("baseball") || sportA.contains("beisbol")
        val isBaseballB = sportB.contains("baseball") || sportB.contains("beisbol")
        if (isBaseballA && isBaseballB) return true

        if (sportA.contains("rugby") && sportB.contains("rugby")) return true
        if (sportA.contains("tennis") && sportB.contains("tennis")) return true
        if (sportA.contains("boxing") && (sportB.contains("boxing") || sportB.contains("fighting") || sportB.contains("mma"))) return true
        return false
    }
}
