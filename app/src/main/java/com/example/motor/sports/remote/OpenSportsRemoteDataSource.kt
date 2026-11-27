package com.example.motor.sports.remote

import android.util.Log
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.concurrent.TimeUnit

/**
 * Proveedor abierto y permanente de eventos deportivos sin necesidad de API Key ni cuentas externas.
 *
 * Fuentes utilizadas:
 * - ESPN Public Scoreboard API (Fútbol, F1, Baloncesto, Tenis, Béisbol, Rugby, Golf)
 * - MotoGP PulseLive API (Motos / Grandes Premios oficiales)
 */
class OpenSportsRemoteDataSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : SportsRemoteDataSource {

    companion object {
        private const val TAG = "OpenSportsRemoteDataSource"
        private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports"
        private const val MOTOGP_BASE = "https://api.motogp.pulselive.com/motogp/v1/results"

        // Ligas de fútbol principales y torneos internacionales de selecciones
        private val TOP_FOOTBALL_LEAGUES = listOf(
            "soccer/uefa.nations" to "UEFA Nations League",
            "soccer/esp.1" to "LALIGA EA Sports",
            "soccer/uefa.champions" to "Champions League",
            "soccer/esp.2" to "LALIGA Hypermotion",
            "soccer/esp.copa_del_rey" to "Copa del Rey",
            "soccer/eng.1" to "Premier League",
            "soccer/uefa.europa" to "Europa League",
            "soccer/uefa.europa.conf" to "Conference League",
            "soccer/ita.1" to "Serie A",
            "soccer/ger.1" to "Bundesliga",
            "soccer/fra.1" to "Ligue 1",
            "soccer/fifa.friendly" to "Amistosos Internacionales",
            "soccer/fifa.worldq.uefa" to "Clasificación Mundial",
            "soccer/uefa.euroq" to "Clasificación Eurocopa",
            "soccer/uefa.w.nations" to "UEFA Nations League Femenina",
            "soccer/uefa.euro" to "Eurocopa",
            "soccer/fifa.world" to "Copa del Mundo FIFA"
        )

        // Ligas de baloncesto (eliminados endpoints muertos espn/euroleague y espn/acb; se cubren por APIs oficiales)
        private val TOP_BASKETBALL_LEAGUES = listOf(
            "basketball/nba" to "NBA",
            "basketball/fiba" to "FIBA",
            "basketball/mens-college-basketball" to "NCAA",
            "basketball/nba-development" to "NBA G-League",
            "basketball/womens-college-basketball" to "NCAA Femenina",
            "basketball/wnba" to "WNBA"
        )

        // Ligas de MMA / Artes Marciales Mixtas
        private val TOP_MMA_LEAGUES = listOf(
            "mma/ufc" to "UFC",
            "mma/pfl" to "PFL",
            "mma/lfa" to "LFA",
            "mma/cage-warriors" to "Cage Warriors"
        )

        // Ligas de Motor / Automovilismo
        private val TOP_RACING_LEAGUES = listOf(
            "racing/f1" to "Formula 1",
            "racing/irl" to "IndyCar",
            "racing/nascar-premier" to "NASCAR"
        )

        // Otros deportes no cubiertos en el resto de pestañas (Hockey hielo, Fútbol americano)
        private val OTHER_SPORTS_LEAGUES = listOf(
            "hockey/nhl" to "NHL Hockey",
            "football/nfl" to "NFL",
            "football/college-football" to "NCAA Football"
        )

        private const val EUROLEAGUE_BASE = "https://api-live.euroleague.net/v2/competitions"
        private const val ACB_URL = "https://www.acb.com/es/liga/partidos"

        private val ESPN_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

        private val FLEXIBLE_ISO_FORMATTER = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm")
            .optionalStart()
            .appendPattern(":ss")
            .optionalEnd()
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendPattern("X")
            .toFormatter()
    }

    @Volatile
    private var cachedMotoGpSeasonUuid: String? = null

    override fun isApiKeyConfigured(): Boolean = true

    override suspend fun validateApiKey(apiKey: String): ApiKeyValidationResult {
        return ApiKeyValidationResult.Valid
    }

    override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = withContext(Dispatchers.IO) {
        try {
            when (sport) {
                SportsSport.FOOTBALL -> fetchFootballEvents(dateParam = null, liveOnly = true)
                SportsSport.FORMULA1 -> fetchFormula1Events(dateParam = null, liveOnly = true)
                SportsSport.MOTOS -> fetchMotoGpEvents(dateFilter = null, liveOnly = true)
                SportsSport.BASKETBALL -> fetchBasketballEvents(dateParam = null, liveOnly = true)
                SportsSport.TENNIS -> fetchTennisEvents(dateParam = null, liveOnly = true)
                SportsSport.UFC -> fetchUfcEvents(dateParam = null, liveOnly = true)
                SportsSport.BASEBALL -> fetchEspnGenericEvents("baseball/mlb", sport, null, true)
                SportsSport.RUGBY -> fetchEspnGenericEvents("rugby", sport, null, true)
                SportsSport.GOLF -> fetchEspnGenericEvents("golf/pga", sport, null, true)
                SportsSport.BOXING -> fetchEspnGenericEvents("boxing", sport, null, true)
                SportsSport.OTHER -> fetchOtherSportsEvents(dateParam = null, liveOnly = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching live events for $sport", e)
            emptyList()
        }
    }

    override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = withContext(Dispatchers.IO) {
        val dateParam = date.format(ESPN_DATE_FORMAT)
        try {
            when (sport) {
                SportsSport.FOOTBALL -> fetchFootballEvents(dateParam = dateParam, liveOnly = false)
                SportsSport.FORMULA1 -> fetchFormula1Events(dateParam = dateParam, liveOnly = false)
                SportsSport.MOTOS -> fetchMotoGpEvents(dateFilter = date, liveOnly = false)
                SportsSport.BASKETBALL -> fetchBasketballEvents(dateParam = dateParam, liveOnly = false)
                SportsSport.TENNIS -> fetchTennisEvents(dateParam = dateParam, liveOnly = false)
                SportsSport.UFC -> fetchUfcEvents(dateParam = dateParam, liveOnly = false)
                SportsSport.BASEBALL -> fetchEspnGenericEvents("baseball/mlb", sport, dateParam, false)
                SportsSport.RUGBY -> fetchEspnGenericEvents("rugby", sport, dateParam, false)
                SportsSport.GOLF -> fetchEspnGenericEvents("golf/pga", sport, dateParam, false)
                SportsSport.BOXING -> fetchEspnGenericEvents("boxing", sport, dateParam, false)
                SportsSport.OTHER -> fetchOtherSportsEvents(dateParam = dateParam, liveOnly = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching events by date for $sport on $date", e)
            emptyList()
        }
    }

    override suspend fun getUpcomingWeekEvents(
        sport: SportsSport,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SportsEvent> = withContext(Dispatchers.IO) {
        if (endDate.isBefore(startDate)) return@withContext emptyList()
        try {
            when (sport) {
                SportsSport.FOOTBALL -> fetchFootballWeekEvents(startDate, endDate)
                SportsSport.OTHER -> fetchOtherSportsWeekEvents(startDate, endDate)
                SportsSport.FORMULA1 -> fetchFormula1Events(
                    dateParam = null,
                    liveOnly = false,
                    rangeStart = startDate,
                    rangeEnd = endDate.plusDays(7)
                )
                SportsSport.MOTOS -> fetchMotoGpEvents(
                    dateFilter = null,
                    liveOnly = false,
                    rangeStart = startDate,
                    rangeEnd = endDate.plusDays(7)
                )
                SportsSport.BASKETBALL -> fetchBasketballWeekEvents(startDate, endDate)
                SportsSport.TENNIS -> fetchTennisEvents(
                    dateParam = null,
                    liveOnly = false,
                    rangeStart = startDate,
                    rangeEnd = endDate
                )
                SportsSport.UFC -> fetchUfcEvents(
                    dateParam = null,
                    liveOnly = false,
                    rangeStart = startDate,
                    rangeEnd = endDate
                )
                SportsSport.BASEBALL -> fetchGenericWeekEvents("baseball/mlb", sport, startDate, endDate)
                SportsSport.RUGBY -> fetchGenericWeekEvents("rugby", sport, startDate, endDate)
                SportsSport.GOLF -> fetchGenericWeekEvents("golf/pga", sport, startDate, endDate)
                SportsSport.BOXING -> fetchGenericWeekEvents("boxing", sport, startDate, endDate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching upcoming week events for $sport ($startDate..$endDate)", e)
            emptyList()
        }
    }

    private fun buildDateRange(startDate: LocalDate, endDate: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var current = startDate
        while (!current.isAfter(endDate)) {
            dates.add(current)
            current = current.plusDays(1)
        }
        return dates
    }

    // ─── FÚTBOL (ESPN) ────────────────────────────────────────────────────────────

    private suspend fun fetchFootballEvents(dateParam: String?, liveOnly: Boolean): List<SportsEvent> = supervisorScope {
        val deferredList = TOP_FOOTBALL_LEAGUES.map { (leagueEndpoint, leagueDefaultName) ->
            async(Dispatchers.IO) {
                val url = if (dateParam != null) {
                    "$ESPN_BASE/$leagueEndpoint/scoreboard?dates=$dateParam"
                } else {
                    "$ESPN_BASE/$leagueEndpoint/scoreboard"
                }
                parseEspnScoreboard(url, SportsSport.FOOTBALL, leagueDefaultName, liveOnly)
            }
        }

        val allEvents = deferredList.flatMap {
            try {
                it.await()
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Deduplicar por id
        allEvents.distinctBy { it.id }.sortedBy { it.startTime }
    }

    private suspend fun fetchFootballWeekEvents(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SportsEvent> = supervisorScope {
        val dates = buildDateRange(startDate, endDate)
        val deferredByDay = dates.map { date ->
            async(Dispatchers.IO) {
                fetchFootballEvents(dateParam = date.format(ESPN_DATE_FORMAT), liveOnly = false)
            }
        }
        deferredByDay.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.distinctBy { it.id }.sortedBy { it.startTime }
    }

    // ─── OTROS DEPORTES (ESPN: NHL, NFL, NCAA Football) ───────────────────────────

    private suspend fun fetchOtherSportsEvents(dateParam: String?, liveOnly: Boolean): List<SportsEvent> = supervisorScope {
        val deferredList = OTHER_SPORTS_LEAGUES.map { (endpoint, defaultName) ->
            async(Dispatchers.IO) {
                val url = if (dateParam != null) {
                    "$ESPN_BASE/$endpoint/scoreboard?dates=$dateParam"
                } else {
                    "$ESPN_BASE/$endpoint/scoreboard"
                }
                parseEspnScoreboard(url, SportsSport.OTHER, defaultName, liveOnly)
            }
        }

        deferredList.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.distinctBy { it.id }.sortedBy { it.startTime }
    }

    private suspend fun fetchOtherSportsWeekEvents(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SportsEvent> = supervisorScope {
        val dates = buildDateRange(startDate, endDate)
        val deferredByDay = dates.map { date ->
            async(Dispatchers.IO) {
                fetchOtherSportsEvents(dateParam = date.format(ESPN_DATE_FORMAT), liveOnly = false)
            }
        }
        deferredByDay.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.filter {
            val eventDate = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            !eventDate.isBefore(startDate) && !eventDate.isAfter(endDate)
        }.distinctBy { it.id }.sortedBy { it.startTime }
    }

    // ─── FÓRMULA 1 Y MOTOR (ESPN: F1, IndyCar, NASCAR) ───────────────────────────

    private suspend fun fetchFormula1Events(
        dateParam: String?,
        liveOnly: Boolean,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> = supervisorScope {
        val currentYear = LocalDate.now().year
        val deferredList = TOP_RACING_LEAGUES.map { (endpoint, defaultName) ->
            async(Dispatchers.IO) {
                val url = if (dateParam != null) {
                    "$ESPN_BASE/$endpoint/scoreboard?dates=$dateParam"
                } else {
                    "$ESPN_BASE/$endpoint/scoreboard?dates=$currentYear"
                }
                parseEspnScoreboard(url, SportsSport.FORMULA1, defaultName, liveOnly = false)
            }
        }

        val rawEvents = deferredList.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.distinctBy { it.id }

        val filtered = when {
            liveOnly -> rawEvents.filter { it.status == SportsEventStatus.LIVE }
            dateParam != null -> {
                val filterDate = try {
                    LocalDate.parse(dateParam, ESPN_DATE_FORMAT)
                } catch (_: Exception) {
                    null
                }
                if (filterDate != null) {
                    rawEvents.filter {
                        val eventDate = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                        eventDate == filterDate
                    }
                } else {
                    rawEvents
                }
            }
            rangeStart != null && rangeEnd != null -> {
                rawEvents.filter {
                    val eventDate = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                    !eventDate.isBefore(rangeStart) && !eventDate.isAfter(rangeEnd)
                }
            }
            else -> rawEvents
        }

        filtered.sortedBy { it.startTime }
    }

    // ─── MOTOGP (PulseLive Oficial) ──────────────────────────────────────────────

    private suspend fun fetchMotoGpEvents(
        dateFilter: LocalDate?,
        liveOnly: Boolean,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> = withContext(Dispatchers.IO) {
        val seasonUuid = getOrFetchMotoGpSeasonUuid() ?: return@withContext emptyList()
        val url = "$MOTOGP_BASE/events?seasonUuid=$seasonUuid"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .header("Accept", "application/json")
            .build()

        val jsonString = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                response.body?.string() ?: return@withContext emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching MotoGP events", e)
            return@withContext emptyList()
        }

        val jsonArray = try {
            JSONArray(jsonString)
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        val events = mutableListOf<SportsEvent>()
        val today = LocalDate.now(ZoneId.systemDefault())

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            val statusStr = item.optString("status", "")
            if (statusStr.equals("FINISHED", ignoreCase = true) ||
                statusStr.contains("CANCEL", ignoreCase = true)) continue

            val name = item.optString("name", "Gran Premio de Motociclismo")
            val dateStartStr = item.optString("date_start", "")
            val dateEndStr = item.optString("date_end", dateStartStr)

            val startDate = try { LocalDate.parse(dateStartStr) } catch (_: Exception) { null }
            val endDate = try { LocalDate.parse(dateEndStr) } catch (_: Exception) { startDate }
            if (startDate == null) continue

            val effectiveEnd = endDate ?: startDate
            val isOngoing = (today.isEqual(startDate) || today.isAfter(startDate)) &&
                    (today.isEqual(effectiveEnd) || today.isBefore(effectiveEnd))

            val status = if (isOngoing) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED
            val statusText = if (isOngoing) "EN DIRECTO" else "Programado"

            if (liveOnly && status != SportsEventStatus.LIVE) continue

            if (dateFilter != null) {
                val matchesDate = !dateFilter.isBefore(startDate) && !dateFilter.isAfter(effectiveEnd)
                if (!matchesDate) continue
            } else if (rangeStart != null && rangeEnd != null) {
                val intersectsRange = !effectiveEnd.isBefore(rangeStart) && !startDate.isAfter(rangeEnd)
                if (!intersectsRange) continue
            }

            val startMillis = startDate.atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val countryName = item.optJSONObject("country")?.optString("name") ?: "Mundial"
            val id = item.optString("id", "motogp_${startDate}_$i")

            events.add(
                SportsEvent(
                    id = id,
                    sport = SportsSport.MOTOS.id,
                    league = "MotoGP World Championship",
                    leagueLogo = "https://static.motogp.com/assets/assets-frontend/motogp/logo/motogp.svg",
                    homeTeam = name,
                    awayTeam = "MotoGP",
                    homeTeamLogo = null,
                    awayTeamLogo = null,
                    startTime = startMillis,
                    status = status,
                    statusText = statusText,
                    country = countryName,
                    broadcaster = "DAZN, DAZN 1, Movistar Plus+, ServusTV, MotoGP"
                )
            )
        }

        events.sortedBy { it.startTime }
    }

    private fun getOrFetchMotoGpSeasonUuid(): String? {
        val cached = cachedMotoGpSeasonUuid
        if (!cached.isNullOrBlank()) return cached

        try {
            val req = Request.Builder()
                .url("$MOTOGP_BASE/seasons")
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val arr = JSONArray(body)
                val currentYear = LocalDate.now().year
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (s.optBoolean("current", false) || s.optInt("year") == currentYear) {
                        val uuid = s.optString("id")
                        if (uuid.isNotBlank()) {
                            cachedMotoGpSeasonUuid = uuid
                            return uuid
                        }
                    }
                }
                if (arr.length() > 0) {
                    val fallbackUuid = arr.optJSONObject(0)?.optString("id")
                    cachedMotoGpSeasonUuid = fallbackUuid
                    return fallbackUuid
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching MotoGP seasons", e)
        }
        return null
    }

    // ─── TENIS (ESPN ATP + WTA Scoreboards combinados) ──────────────────────────

    private suspend fun fetchTennisEvents(
        dateParam: String?,
        liveOnly: Boolean,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> = supervisorScope {
        val circuits = listOf("atp" to "ATP", "wta" to "WTA")
        val rangeParam = if (rangeStart != null && rangeEnd != null) {
            "${rangeStart.format(ESPN_DATE_FORMAT)}-${rangeEnd.format(ESPN_DATE_FORMAT)}"
        } else {
            null
        }
        val queryDatesParam = dateParam ?: rangeParam

        val deferredList = circuits.map { (circuitSlug, circuitLabel) ->
            async(Dispatchers.IO) {
                val url = if (queryDatesParam != null) {
                    "$ESPN_BASE/tennis/$circuitSlug/scoreboard?dates=$queryDatesParam"
                } else {
                    "$ESPN_BASE/tennis/$circuitSlug/scoreboard"
                }
                val primary = parseEspnTennisScoreboard(url, circuitLabel, dateParam, rangeStart, rangeEnd, liveOnly)

                if (rangeStart != null && rangeEnd != null && !liveOnly) {
                    val defaultUrl = "$ESPN_BASE/tennis/$circuitSlug/scoreboard"
                    val fallback = parseEspnTennisScoreboard(defaultUrl, circuitLabel, null, rangeStart, rangeEnd, liveOnly)
                    (primary + fallback).distinctBy { it.id }
                } else {
                    primary
                }
            }
        }

        val allEvents = deferredList.flatMap {
            try {
                it.await()
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching tennis circuit", e)
                emptyList()
            }
        }

        allEvents.distinctBy { it.id }.sortedBy { it.startTime }
    }

    private fun parseEspnTennisScoreboard(
        url: String,
        circuitLabel: String,
        dateParam: String?,
        rangeStart: LocalDate?,
        rangeEnd: LocalDate?,
        liveOnly: Boolean
    ): List<SportsEvent> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string() ?: return emptyList()
            }

            val root = JSONObject(body)
            val leagueObj = root.optJSONArray("leagues")?.optJSONObject(0)
            val leagueLogo = leagueObj?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")

            val filterDate = dateParam?.let {
                try {
                    LocalDate.parse(it, ESPN_DATE_FORMAT)
                } catch (_: Exception) {
                    null
                }
            }
            val today = LocalDate.now(ZoneId.systemDefault())

            val eventsArr = root.optJSONArray("events") ?: return emptyList()
            val result = mutableListOf<SportsEvent>()

            for (i in 0 until eventsArr.length()) {
                val tournamentEv = eventsArr.optJSONObject(i) ?: continue
                val tournamentName = tournamentEv.optString("name", "Torneo")
                val tournamentId = tournamentEv.optString("id", "$i")

                val allComps = mutableListOf<JSONObject>()
                val directComps = tournamentEv.optJSONArray("competitions")
                if (directComps != null) {
                    for (cIdx in 0 until directComps.length()) {
                        directComps.optJSONObject(cIdx)?.let { allComps.add(it) }
                    }
                }
                val groupings = tournamentEv.optJSONArray("groupings")
                if (groupings != null) {
                    for (gIdx in 0 until groupings.length()) {
                        val g = groupings.optJSONObject(gIdx) ?: continue
                        val gComps = g.optJSONArray("competitions") ?: continue
                        for (cIdx in 0 until gComps.length()) {
                            gComps.optJSONObject(cIdx)?.let { allComps.add(it) }
                        }
                    }
                }

                for (comp in allComps) {
                    val compId = comp.optString("id", "${tournamentId}_$i")
                    val dateStr = comp.optString("date", "")
                    val startTimeMillis = parseEpochMillis(dateStr)

                    val statusObj = comp.optJSONObject("status")
                    val statusType = statusObj?.optJSONObject("type")
                    val state = statusType?.optString("state", "pre")
                    val statusDesc = statusType?.optString("description", "Programado") ?: "Programado"
                    val shortDetail = statusType?.optString("shortDetail", "") ?: ""

                    val isLive = state.equals("in", ignoreCase = true)
                    val isFinished = state.equals("post", ignoreCase = true) ||
                            statusType?.optBoolean("completed", false) == true

                    if (isFinished || isEventCanceled(statusType)) continue
                    if (liveOnly && !isLive) continue

                    val matchDate = Instant.ofEpochMilli(startTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate()

                    if (filterDate != null) {
                        if (matchDate != filterDate) continue
                    } else if (rangeStart != null && rangeEnd != null) {
                        if (matchDate.isBefore(rangeStart) || matchDate.isAfter(rangeEnd)) continue
                    } else {
                        if (matchDate.isBefore(today)) continue
                    }

                    val status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED
                    val roundName = comp.optJSONObject("round")?.optString("displayName") ?: ""

                    val competitors = comp.optJSONArray("competitors")
                    var homeName = "TBD"
                    var awayName = "TBD"
                    var homeLogo: String? = null
                    var awayLogo: String? = null
                    var homeScore: Int? = null
                    var awayScore: Int? = null

                    if (competitors != null && competitors.length() >= 2) {
                        for (cIdx in 0 until competitors.length()) {
                            val c = competitors.optJSONObject(cIdx) ?: continue
                            val isHome = (cIdx == 0)

                            var pName = ""
                            var pLogo: String? = null

                            val ath = c.optJSONObject("athlete")
                            if (ath != null) {
                                pName = ath.optString("displayName").takeIf { it.isNotBlank() }
                                    ?: ath.optString("fullName").takeIf { it.isNotBlank() }
                                    ?: ath.optString("shortName", "Jugador")
                                pLogo = ath.optJSONObject("flag")?.optString("href")
                            } else {
                                val roster = c.optJSONObject("roster")
                                val aths = roster?.optJSONArray("athletes") ?: c.optJSONArray("athletes")
                                if (aths != null && aths.length() > 0) {
                                    val names = mutableListOf<String>()
                                    for (aIdx in 0 until aths.length()) {
                                        val a = aths.optJSONObject(aIdx) ?: continue
                                        val dName = a.optString("displayName").ifBlank { a.optString("shortName") }
                                        if (dName.isNotBlank()) names.add(dName)
                                    }
                                    pName = names.joinToString(" / ")
                                } else {
                                    pName = roster?.optString("displayName")?.takeIf { it.isNotBlank() }
                                        ?: c.optJSONObject("team")?.optString("displayName")
                                        ?: "TBD"
                                }
                            }

                            val scoreVal = c.optString("score").toIntOrNull()

                            if (isHome) {
                                homeName = pName
                                homeLogo = pLogo
                                homeScore = scoreVal
                            } else {
                                awayName = pName
                                awayLogo = pLogo
                                awayScore = scoreVal
                            }
                        }
                    }

                    if (homeName.equals("TBD", ignoreCase = true) && awayName.equals("TBD", ignoreCase = true)) {
                        continue
                    }

                    val leagueFullName = if (tournamentName.isNotBlank()) "$circuitLabel - $tournamentName" else circuitLabel
                    val displayStatusText = when {
                        isLive -> shortDetail.ifBlank { "EN DIRECTO" }
                        roundName.isNotBlank() -> roundName
                        shortDetail.isNotBlank() -> shortDetail
                        else -> statusDesc
                    }

                    result.add(
                        SportsEvent(
                            id = "tennis_${circuitLabel}_$compId",
                            sport = SportsSport.TENNIS.id,
                            league = leagueFullName,
                            leagueLogo = leagueLogo,
                            homeTeam = homeName,
                            awayTeam = awayName,
                            homeTeamLogo = homeLogo,
                            awayTeamLogo = awayLogo,
                            startTime = startTimeMillis,
                            status = status,
                            statusText = displayStatusText,
                            homeScore = homeScore,
                            awayScore = awayScore,
                            elapsedMinutes = null,
                            country = null,
                            broadcaster = "Movistar Deportes, Eurosport, Tennis Channel"
                        )
                    )
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tennis scoreboard from $url", e)
            return emptyList()
        }
    }

    // ─── BALONCESTO (Euroliga, EuroCup, ACB y Ligas Internacionales) ──────────────

    private suspend fun fetchBasketballEvents(dateParam: String?, liveOnly: Boolean): List<SportsEvent> = supervisorScope {
        val filterDate = dateParam?.let {
            try {
                LocalDate.parse(it, ESPN_DATE_FORMAT)
            } catch (_: Exception) {
                null
            }
        }

        // 1. Euroliga y EuroCup Oficiales
        val euroleagueDeferred = async(Dispatchers.IO) {
            fetchEuroleagueOfficialEvents(filterDate, liveOnly)
        }

        // 2. ACB / Liga Endesa Oficial
        val acbDeferred = async(Dispatchers.IO) {
            fetchAcbOfficialEvents(filterDate, liveOnly)
        }

        // 3. Scoreboards de ESPN (NBA, FIBA, NCAA, G-League, NCAA Femenina, WNBA)
        val espnDeferredList = TOP_BASKETBALL_LEAGUES.map { (endpoint, defaultName) ->
            async(Dispatchers.IO) {
                val url = if (dateParam != null) {
                    "$ESPN_BASE/$endpoint/scoreboard?dates=$dateParam"
                } else {
                    "$ESPN_BASE/$endpoint/scoreboard"
                }
                parseEspnScoreboard(url, SportsSport.BASKETBALL, defaultName, liveOnly)
            }
        }

        val euroleagueEvents = try { euroleagueDeferred.await() } catch (e: Exception) {
            Log.w(TAG, "Error fetching Euroleague", e)
            emptyList()
        }
        val acbEvents = try { acbDeferred.await() } catch (e: Exception) {
            Log.w(TAG, "Error fetching ACB", e)
            emptyList()
        }
        val espnEvents = espnDeferredList.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }

        val allEvents = euroleagueEvents + acbEvents + espnEvents

        allEvents.distinctBy {
            val dateKey = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            "${it.sport}_${it.homeTeam.lowercase().trim()}_${it.awayTeam.lowercase().trim()}_$dateKey"
        }.sortedBy { it.startTime }
    }

    private suspend fun fetchBasketballWeekEvents(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SportsEvent> = supervisorScope {
        val euroleagueDeferred = async(Dispatchers.IO) {
            fetchEuroleagueOfficialEvents(filterDate = null, liveOnly = false, rangeStart = startDate, rangeEnd = endDate)
        }
        val acbDeferred = async(Dispatchers.IO) {
            fetchAcbOfficialEvents(filterDate = null, liveOnly = false, rangeStart = startDate, rangeEnd = endDate)
        }
        val dates = buildDateRange(startDate, endDate)
        val espnDeferredList = dates.flatMap { date ->
            val dateParam = date.format(ESPN_DATE_FORMAT)
            TOP_BASKETBALL_LEAGUES.map { (endpoint, defaultName) ->
                async(Dispatchers.IO) {
                    val url = "$ESPN_BASE/$endpoint/scoreboard?dates=$dateParam"
                    parseEspnScoreboard(url, SportsSport.BASKETBALL, defaultName, liveOnly = false)
                }
            }
        }

        val euroleagueEvents = try { euroleagueDeferred.await() } catch (_: Exception) { emptyList() }
        val acbEvents = try { acbDeferred.await() } catch (_: Exception) { emptyList() }
        val espnEvents = espnDeferredList.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.filter {
            val matchDate = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            !matchDate.isBefore(startDate) && !matchDate.isAfter(endDate)
        }

        (euroleagueEvents + acbEvents + espnEvents).distinctBy {
            val dateKey = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            "${it.sport}_${it.homeTeam.lowercase().trim()}_${it.awayTeam.lowercase().trim()}_$dateKey"
        }.distinctBy { it.id }.sortedBy { it.startTime }
    }

    private suspend fun fetchEuroleagueOfficialEvents(
        filterDate: LocalDate?,
        liveOnly: Boolean,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SportsEvent>()
        val competitions = listOf("E" to "EuroLeague", "U" to "EuroCup")
        val currentYear = LocalDate.now().year
        val seasonCodes = listOf("$currentYear", "${currentYear - 1}", "${currentYear + 1}")

        for ((compCode, compName) in competitions) {
            for (year in seasonCodes) {
                val seasonId = "$compCode$year"
                val url = "$EUROLEAGUE_BASE/$compCode/seasons/$seasonId/games"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/json")
                    .build()

                val games = try {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string() ?: return@use null
                        JSONObject(body).optJSONArray("data")
                    }
                } catch (e: Exception) {
                    null
                }

                if (games != null && games.length() > 0) {
                    val now = System.currentTimeMillis()
                    val today = LocalDate.now(ZoneId.systemDefault())

                    for (i in 0 until games.length()) {
                        val g = games.optJSONObject(i) ?: continue
                        val gId = g.optString("id", "${seasonId}_$i")
                        val dateStr = g.optString("date", "").takeIf { it.isNotBlank() }
                            ?: g.optString("utcDate", "")
                        if (dateStr.isBlank()) continue

                        val startTime = parseEpochMillis(dateStr)
                        val matchDate = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDate()

                        val played = g.optBoolean("played", false)
                        val isLive = !played && now >= startTime && now <= (startTime + 3 * 3600 * 1000L)

                        if (played) continue
                        if (liveOnly && !isLive) continue

                        if (filterDate != null) {
                            if (matchDate != filterDate) continue
                        } else if (rangeStart != null && rangeEnd != null) {
                            if (matchDate.isBefore(rangeStart) || matchDate.isAfter(rangeEnd)) continue
                        } else {
                            if (matchDate.isBefore(today) || matchDate.isAfter(today.plusDays(7))) {
                                continue
                            }
                        }

                        val localClub = g.optJSONObject("local")?.optJSONObject("club")
                        val roadClub = g.optJSONObject("road")?.optJSONObject("club")

                        val homeName = localClub?.optString("name") ?: "Equipo Local"
                        val awayName = roadClub?.optString("name") ?: "Equipo Visitante"
                        val homeLogo = localClub?.optJSONObject("images")?.optString("crest")
                        val awayLogo = roadClub?.optJSONObject("images")?.optString("crest")

                        val homeScore = g.optJSONObject("local")?.optInt("score", -1)?.takeIf { it >= 0 }
                        val awayScore = g.optJSONObject("road")?.optInt("score", -1)?.takeIf { it >= 0 }

                        val roundName = g.optString("roundName", "").ifBlank {
                            g.optString("roundAlias", "")
                        }

                        val status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED
                        val statusText = if (isLive) "EN DIRECTO" else roundName.ifBlank { "Programado" }

                        result.add(
                            SportsEvent(
                                id = "el_${compCode}_$gId",
                                sport = SportsSport.BASKETBALL.id,
                                league = compName,
                                leagueLogo = if (compCode == "E") "https://media-cdn.incrowdsports.com/061d4b4a-a28a-493e-a14a-7bc9b69b82fa.png" else null,
                                homeTeam = homeName,
                                awayTeam = awayName,
                                homeTeamLogo = homeLogo,
                                awayTeamLogo = awayLogo,
                                startTime = startTime,
                                status = status,
                                statusText = statusText,
                                homeScore = homeScore,
                                awayScore = awayScore,
                                elapsedMinutes = null,
                                country = "Europa",
                                broadcaster = "Movistar Deportes, Movistar Plus+, DAZN, EuroLeague TV"
                            )
                        )
                    }
                    break
                }
            }
        }
        result
    }

    private suspend fun fetchAcbOfficialEvents(
        filterDate: LocalDate?,
        liveOnly: Boolean,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SportsEvent>()
        val req = Request.Builder()
            .url(ACB_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        val html = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching ACB HTML", e)
            null
        } ?: return@withContext emptyList()

        val unescaped = html.replace("\\\"", "\"").replace("\\\\", "\\")
        val sfIdx = unescaped.indexOf("\"selectedFilters\":")
        if (sfIdx == -1) return@withContext emptyList()

        val teamsIdx = unescaped.indexOf("\"teams\":[", sfIdx)
        val matchesIdx = unescaped.indexOf("\"matches\":[", sfIdx)
        if (teamsIdx == -1 || matchesIdx == -1) return@withContext emptyList()

        val teamsBlock = unescaped.substring(teamsIdx, matchesIdx)
        val matchesBlock = unescaped.substring(matchesIdx)

        val teamPattern = Regex("""\{"id":\d+,"competitionId":.*?"fullName":"([^"]+)".*?"logo":"([^"]+)"""")
        val teamsList = teamPattern.findAll(teamsBlock).map { matchResult ->
            val fullName = matchResult.groupValues[1]
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            val logo = matchResult.groupValues[2]
            fullName to logo
        }.toList()

        if (teamsList.isEmpty()) return@withContext emptyList()

        val matchPattern = Regex("""\{"id":(\d+),"homeTeam":"[^"]*?:teams:(\d+)","awayTeam":"[^"]*?:teams:(\d+)","homeScore":(\d+),"awayScore":(\d+),"startDateTime":"([^"]+)","matchStatus":"([^"]+)"""")
        val now = System.currentTimeMillis()

        for (m in matchPattern.findAll(matchesBlock)) {
            val mid = m.groupValues[1]
            val homeIdx = m.groupValues[2].toIntOrNull() ?: continue
            val awayIdx = m.groupValues[3].toIntOrNull() ?: continue
            val homeScoreVal = m.groupValues[4].toIntOrNull()
            val awayScoreVal = m.groupValues[5].toIntOrNull()
            val startDateTimeStr = m.groupValues[6]
            val matchStatus = m.groupValues[7]

            if (homeIdx >= teamsList.size || awayIdx >= teamsList.size) continue

            val (homeName, homeLogo) = teamsList[homeIdx]
            val (awayName, awayLogo) = teamsList[awayIdx]

            val startTimeMillis = parseEpochMillis(startDateTimeStr)
            val matchDate = Instant.ofEpochMilli(startTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate()

            val isFinished = matchStatus.equals("FINISHED", ignoreCase = true)
            val isLive = matchStatus.equals("LIVE", ignoreCase = true) ||
                    (!isFinished && now >= startTimeMillis && now <= (startTimeMillis + 2.5 * 3600 * 1000L))

            if (isFinished) continue
            if (liveOnly && !isLive) continue

            if (filterDate != null) {
                if (matchDate != filterDate) continue
            } else if (rangeStart != null && rangeEnd != null) {
                if (matchDate.isBefore(rangeStart) || matchDate.isAfter(rangeEnd)) continue
            }

            val status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED
            val statusText = if (isLive) "EN DIRECTO" else "Liga Endesa"

            result.add(
                SportsEvent(
                    id = "acb_$mid",
                    sport = SportsSport.BASKETBALL.id,
                    league = "Liga Endesa (ACB)",
                    leagueLogo = "https://static.acb.com/next/images/components/main-navbar/logo-acb.svg",
                    homeTeam = homeName,
                    awayTeam = awayName,
                    homeTeamLogo = homeLogo,
                    awayTeamLogo = awayLogo,
                    startTime = startTimeMillis,
                    status = status,
                    statusText = statusText,
                    homeScore = if (isLive || isFinished) homeScoreVal else null,
                    awayScore = if (isLive || isFinished) awayScoreVal else null,
                    elapsedMinutes = null,
                    country = "España",
                    broadcaster = "Movistar Plus+, DAZN, Movistar Deportes, Orange TV"
                )
            )
        }

        result
    }

    // ─── DEPORTES GENÉRICOS (ESPN) ───────────────────────────────────────────────

    private suspend fun fetchEspnGenericEvents(
        endpoint: String,
        sport: SportsSport,
        dateParam: String?,
        liveOnly: Boolean
    ): List<SportsEvent> = withContext(Dispatchers.IO) {
        val url = if (dateParam != null) {
            "$ESPN_BASE/$endpoint/scoreboard?dates=$dateParam"
        } else {
            "$ESPN_BASE/$endpoint/scoreboard"
        }
        parseEspnScoreboard(url, sport, sport.translationKey, liveOnly)
    }

    private suspend fun fetchGenericWeekEvents(
        endpoint: String,
        sport: SportsSport,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SportsEvent> = supervisorScope {
        val dates = buildDateRange(startDate, endDate)
        val deferredByDay = dates.map { date ->
            async(Dispatchers.IO) {
                fetchEspnGenericEvents(endpoint, sport, date.format(ESPN_DATE_FORMAT), liveOnly = false)
            }
        }
        deferredByDay.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.filter {
            val eventDate = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
            !eventDate.isBefore(startDate) && !eventDate.isAfter(endDate)
        }.distinctBy { it.id }.sortedBy { it.startTime }
    }

    // ─── PARSER COMÚN DE SCOREBOARDS DE ESPN ─────────────────────────────────────

    private fun parseEspnScoreboard(
        url: String,
        sport: SportsSport,
        defaultLeagueName: String,
        liveOnly: Boolean
    ): List<SportsEvent> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string() ?: return emptyList()
            }

            val root = JSONObject(body)
            val leagueObj = root.optJSONArray("leagues")?.optJSONObject(0)
            val leagueName = leagueObj?.optString("name")?.takeIf { it.isNotBlank() } ?: defaultLeagueName
            val leagueLogo = leagueObj?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")

            val eventsArr = root.optJSONArray("events") ?: return emptyList()
            val result = mutableListOf<SportsEvent>()

            for (i in 0 until eventsArr.length()) {
                val ev = eventsArr.optJSONObject(i) ?: continue
                val evId = ev.optString("id", "${sport.id}_$i")
                val dateStr = ev.optString("date", "")
                val startTimeMillis = parseEpochMillis(dateStr)

                val compArr = ev.optJSONArray("competitions")
                val comp = compArr?.optJSONObject(0)

                val statusObj = comp?.optJSONObject("status") ?: ev.optJSONObject("status")
                val statusType = statusObj?.optJSONObject("type")
                val state = statusType?.optString("state", "pre") // "pre", "in", "post"
                val statusDesc = statusType?.optString("description", "Programado") ?: "Programado"
                val shortDetail = statusType?.optString("shortDetail", "") ?: ""

                val isLive = state.equals("in", ignoreCase = true)
                val isFinished = state.equals("post", ignoreCase = true) ||
                        statusType?.optBoolean("completed", false) == true

                if (isFinished || isEventCanceled(statusType)) continue
                if (liveOnly && !isLive) continue

                val status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED

                val competitors = comp?.optJSONArray("competitors")
                var homeTeamName = ""
                var awayTeamName = ""
                var homeTeamLogo: String? = null
                var awayTeamLogo: String? = null
                var homeScore: Int? = null
                var awayScore: Int? = null

                if (competitors != null && competitors.length() >= 2) {
                    for (cIdx in 0 until competitors.length()) {
                        val c = competitors.optJSONObject(cIdx) ?: continue
                        val isHome = c.optString("homeAway").equals("home", ignoreCase = true) || (cIdx == 0 && !c.optString("homeAway").equals("away", ignoreCase = true))
                        val teamObj = c.optJSONObject("team")
                        val athleteObj = c.optJSONObject("athlete")
                        val teamName = teamObj?.optString("displayName")
                            ?: teamObj?.optString("name")
                            ?: athleteObj?.optString("displayName")
                            ?: athleteObj?.optString("fullName")
                            ?: "Equipo"
                        val teamLogo = teamObj?.optString("logo")
                            ?: athleteObj?.optJSONObject("flag")?.optString("href")
                        val scoreVal = c.optString("score").toIntOrNull()

                        if (isHome) {
                            homeTeamName = teamName
                            homeTeamLogo = teamLogo
                            homeScore = scoreVal
                        } else {
                            awayTeamName = teamName
                            awayTeamLogo = teamLogo
                            awayScore = scoreVal
                        }
                    }
                } else {
                    homeTeamName = ev.optString("name", "Evento Deportivo")
                    awayTeamName = leagueName
                }

                // Canales emisores reportados por ESPN
                val broadcastersList = mutableListOf<String>()
                val broadcastsArr = comp?.optJSONArray("broadcasts")
                if (broadcastsArr != null) {
                    for (bIdx in 0 until broadcastsArr.length()) {
                        val b = broadcastsArr.optJSONObject(bIdx) ?: continue
                        val namesArr = b.optJSONArray("names")
                        if (namesArr != null) {
                            for (nIdx in 0 until namesArr.length()) {
                                val name = namesArr.optString(nIdx)
                                if (name.isNotBlank() && name !in broadcastersList) {
                                    broadcastersList.add(name)
                                }
                            }
                        }
                    }
                }

                val geoArr = comp?.optJSONArray("geoBroadcasts")
                if (geoArr != null) {
                    for (gIdx in 0 until geoArr.length()) {
                        val gb = geoArr.optJSONObject(gIdx) ?: continue
                        val shortName = gb.optJSONObject("media")?.optString("shortName")
                        if (!shortName.isNullOrBlank() && shortName !in broadcastersList) {
                            broadcastersList.add(shortName)
                        }
                    }
                }

                // Minuto transcurrido
                val elapsed = parseElapsedMinutes(statusObj?.optString("displayClock"))

                val broadcasterStr = if (broadcastersList.isNotEmpty()) {
                    broadcastersList.joinToString(", ")
                } else {
                    null
                }

                result.add(
                    SportsEvent(
                        id = evId,
                        sport = sport.id,
                        league = leagueName,
                        leagueLogo = leagueLogo,
                        homeTeam = homeTeamName,
                        awayTeam = awayTeamName,
                        homeTeamLogo = homeTeamLogo,
                        awayTeamLogo = awayTeamLogo,
                        startTime = startTimeMillis,
                        status = status,
                        statusText = if (isLive) shortDetail.ifBlank { "EN DIRECTO" } else shortDetail.ifBlank { statusDesc },
                        homeScore = homeScore,
                        awayScore = awayScore,
                        elapsedMinutes = elapsed,
                        country = null,
                        broadcaster = broadcasterStr
                    )
                )
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ESPN scoreboard from $url", e)
            return emptyList()
        }
    }

    private fun isEventCanceled(statusType: JSONObject?): Boolean {
        if (statusType == null) return false
        val statusName = statusType.optString("name", "")
        val statusDesc = statusType.optString("description", "")
        return statusName.contains("CANCEL", ignoreCase = true) ||
            statusName.contains("POSTPONED", ignoreCase = true) ||
            statusDesc.contains("Cancel", ignoreCase = true) ||
            statusDesc.contains("Postponed", ignoreCase = true)
    }

    // ─── UFC / MMA (ESPN: UFC, PFL, LFA, Cage Warriors) ─────────────────────────

    private suspend fun fetchUfcEvents(
        dateParam: String?,
        liveOnly: Boolean,
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> = supervisorScope {
        val rangeParam = if (rangeStart != null && rangeEnd != null) {
            "${rangeStart.format(ESPN_DATE_FORMAT)}-${rangeEnd.format(ESPN_DATE_FORMAT)}"
        } else {
            null
        }
        val queryDatesParam = dateParam ?: rangeParam

        val deferredList = TOP_MMA_LEAGUES.map { (endpoint, defaultLabel) ->
            async(Dispatchers.IO) {
                val url = if (queryDatesParam != null) {
                    "$ESPN_BASE/$endpoint/scoreboard?dates=$queryDatesParam"
                } else {
                    "$ESPN_BASE/$endpoint/scoreboard"
                }

                var events = parseEspnUfcScoreboard(
                    url = url,
                    dateParam = dateParam,
                    liveOnly = liveOnly,
                    defaultLeagueLabel = defaultLabel,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd
                )

                // Si con queryDatesParam no devuelve eventos, comprobamos la cartelera activa del endpoint principal
                if (events.isEmpty() && queryDatesParam != null && !liveOnly) {
                    val fallbackUrl = "$ESPN_BASE/$endpoint/scoreboard"
                    val fallbackEvents = parseEspnUfcScoreboard(
                        url = fallbackUrl,
                        dateParam = dateParam,
                        liveOnly = liveOnly,
                        defaultLeagueLabel = defaultLabel,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd
                    )
                    if (fallbackEvents.isNotEmpty()) {
                        events = fallbackEvents
                    }
                }
                events
            }
        }

        deferredList.flatMap {
            try { it.await() } catch (_: Exception) { emptyList() }
        }.distinctBy { it.id }.sortedBy { it.startTime }
    }

    private fun parseEspnUfcScoreboard(
        url: String,
        dateParam: String?,
        liveOnly: Boolean,
        defaultLeagueLabel: String = "UFC",
        rangeStart: LocalDate? = null,
        rangeEnd: LocalDate? = null
    ): List<SportsEvent> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string() ?: return emptyList()
            }

            val root = JSONObject(body)
            val leagueObj = root.optJSONArray("leagues")?.optJSONObject(0)
            val leagueName = leagueObj?.optString("displayName")?.takeIf { it.isNotBlank() } ?: defaultLeagueLabel
            val leagueLogo = leagueObj?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
                ?: "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png"

            val filterDate = dateParam?.let {
                try {
                    LocalDate.parse(it, ESPN_DATE_FORMAT)
                } catch (_: Exception) {
                    null
                }
            }

            val defaultBroadcasters = when (defaultLeagueLabel) {
                "PFL" -> listOf("DAZN", "ESPN+")
                "LFA", "Cage Warriors" -> listOf("UFC Fight Pass")
                else -> listOf("Eurosport", "Eurosport 2", "Max", "UFC Fight Pass")
            }

            val eventsArr = root.optJSONArray("events") ?: return emptyList()
            val result = mutableListOf<SportsEvent>()

            for (i in 0 until eventsArr.length()) {
                val ev = eventsArr.optJSONObject(i) ?: continue
                val evName = ev.optString("name", "$defaultLeagueLabel Fight Night")
                val evId = ev.optString("id", "${defaultLeagueLabel.lowercase()}_$i")
                val evDateStr = ev.optString("date", "")

                val compsArr = ev.optJSONArray("competitions")
                if (compsArr != null && compsArr.length() > 0) {
                    val totalFights = compsArr.length()
                    // Iterar en orden inverso: el último combate en ESPN es el Combate Estelar (Main Event),
                    // el penúltimo es el Co-Estelar, etc.
                    for (cIdx in totalFights - 1 downTo 0) {
                        val comp = compsArr.optJSONObject(cIdx) ?: continue
                        val compId = comp.optString("id", "${evId}_fight_$cIdx")
                        val dateStr = comp.optString("date", evDateStr)
                        val startTimeMillis = parseEpochMillis(dateStr)

                        val statusObj = comp.optJSONObject("status") ?: ev.optJSONObject("status")
                        val statusType = statusObj?.optJSONObject("type")
                        val state = statusType?.optString("state", "pre")
                        val isLive = state.equals("in", ignoreCase = true)
                        val isFinished = state.equals("post", ignoreCase = true) ||
                                statusType?.optBoolean("completed", false) == true

                        if (isFinished || isEventCanceled(statusType)) continue
                        if (liveOnly && !isLive) continue

                        val matchDate = Instant.ofEpochMilli(startTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (filterDate != null) {
                            if (matchDate != filterDate) continue
                        } else if (rangeStart != null && rangeEnd != null) {
                            if (matchDate.isBefore(rangeStart) || matchDate.isAfter(rangeEnd)) continue
                        }

                        val status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED
                        val weightClass = comp.optJSONObject("type")?.optString("abbreviation")
                            ?: comp.optJSONObject("type")?.optString("text")
                            ?: ""

                        val boutRole = when (cIdx) {
                            totalFights - 1 -> if (weightClass.isNotBlank()) "Combate Estelar • $weightClass" else "Combate Estelar"
                            totalFights - 2 -> if (weightClass.isNotBlank()) "Co-Estelar • $weightClass" else "Co-Estelar"
                            else -> weightClass.ifBlank { "Cartelera $defaultLeagueLabel" }
                        }

                        val competitors = comp.optJSONArray("competitors")
                        var fighter1 = "Luchador 1"
                        var fighter2 = "Luchador 2"
                        var fighter1Logo: String? = null
                        var fighter2Logo: String? = null
                        var fighter1Score: Int? = null
                        var fighter2Score: Int? = null

                        if (competitors != null && competitors.length() >= 2) {
                            for (k in 0 until competitors.length()) {
                                val c = competitors.optJSONObject(k) ?: continue
                                val ath = c.optJSONObject("athlete")
                                val name = ath?.optString("displayName")?.takeIf { it.isNotBlank() }
                                    ?: ath?.optString("fullName")?.takeIf { it.isNotBlank() }
                                    ?: ath?.optString("shortName")?.takeIf { it.isNotBlank() }
                                    ?: c.optJSONObject("team")?.optString("displayName")
                                    ?: "Luchador"
                                val logo = ath?.optJSONObject("flag")?.optString("href")
                                    ?: ath?.optJSONObject("headshot")?.optString("href")
                                    ?: c.optJSONObject("team")?.optString("logo")
                                val scoreVal = c.optString("score").toIntOrNull()

                                if (k == 0) {
                                    fighter1 = name
                                    fighter1Logo = logo
                                    fighter1Score = scoreVal
                                } else {
                                    fighter2 = name
                                    fighter2Logo = logo
                                    fighter2Score = scoreVal
                                }
                            }
                        } else {
                            fighter1 = evName
                            fighter2 = defaultLeagueLabel
                        }

                        // Emisoras
                        val broadcastersList = mutableListOf<String>()
                        val broadcastsArr = comp.optJSONArray("broadcasts") ?: ev.optJSONArray("broadcasts")
                        if (broadcastsArr != null) {
                            for (bIdx in 0 until broadcastsArr.length()) {
                                val b = broadcastsArr.optJSONObject(bIdx) ?: continue
                                val namesArr = b.optJSONArray("names")
                                if (namesArr != null) {
                                    for (nIdx in 0 until namesArr.length()) {
                                        val name = namesArr.optString(nIdx)
                                        if (name.isNotBlank() && name !in broadcastersList) {
                                            broadcastersList.add(name)
                                        }
                                    }
                                }
                            }
                        }
                        val geoArr = comp.optJSONArray("geoBroadcasts") ?: ev.optJSONArray("geoBroadcasts")
                        if (geoArr != null) {
                            for (gIdx in 0 until geoArr.length()) {
                                val gb = geoArr.optJSONObject(gIdx) ?: continue
                                val shortName = gb.optJSONObject("media")?.optString("shortName")
                                if (!shortName.isNullOrBlank() && shortName !in broadcastersList) {
                                    broadcastersList.add(shortName)
                                }
                            }
                        }
                        if (broadcastersList.isEmpty()) {
                            broadcastersList.addAll(defaultBroadcasters)
                        }

                        val statusDesc = statusType?.optString("description", "Programado") ?: "Programado"
                        val shortDetail = statusType?.optString("shortDetail", "") ?: ""
                        val statusText = if (isLive) {
                            shortDetail.ifBlank { "EN DIRECTO • $boutRole" }
                        } else {
                            "$boutRole • $statusDesc"
                        }

                        result.add(
                            SportsEvent(
                                id = compId,
                                sport = SportsSport.UFC.id,
                                league = evName.ifBlank { leagueName },
                                leagueLogo = leagueLogo,
                                homeTeam = fighter1,
                                awayTeam = fighter2,
                                homeTeamLogo = fighter1Logo,
                                awayTeamLogo = fighter2Logo,
                                startTime = startTimeMillis,
                                status = status,
                                statusText = statusText,
                                homeScore = fighter1Score,
                                awayScore = fighter2Score,
                                elapsedMinutes = null,
                                country = "Internacional",
                                broadcaster = broadcastersList.joinToString(", ")
                            )
                        )
                    }
                } else {
                    val startTimeMillis = parseEpochMillis(evDateStr)
                    val statusObj = ev.optJSONObject("status")
                    val statusType = statusObj?.optJSONObject("type")
                    val state = statusType?.optString("state", "pre")
                    val isLive = state.equals("in", ignoreCase = true)
                    val isFinished = state.equals("post", ignoreCase = true) ||
                            statusType?.optBoolean("completed", false) == true

                    if (isFinished || isEventCanceled(statusType)) continue
                    if (liveOnly && !isLive) continue

                    val matchDate = Instant.ofEpochMilli(startTimeMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                    if (filterDate != null) {
                        if (matchDate != filterDate) continue
                    } else if (rangeStart != null && rangeEnd != null) {
                        if (matchDate.isBefore(rangeStart) || matchDate.isAfter(rangeEnd)) continue
                    }

                    result.add(
                        SportsEvent(
                            id = evId,
                            sport = SportsSport.UFC.id,
                            league = leagueName,
                            leagueLogo = leagueLogo,
                            homeTeam = evName,
                            awayTeam = defaultLeagueLabel,
                            homeTeamLogo = leagueLogo,
                            awayTeamLogo = null,
                            startTime = startTimeMillis,
                            status = if (isLive) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED,
                            statusText = if (isLive) "EN DIRECTO" else "Programado",
                            homeScore = null,
                            awayScore = null,
                            elapsedMinutes = null,
                            country = "Internacional",
                            broadcaster = defaultBroadcasters.joinToString(", ")
                        )
                    )
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ESPN UFC scoreboard from $url", e)
            return emptyList()
        }
    }

    private fun parseEpochMillis(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        val clean = dateStr.trim()
        return try {
            ZonedDateTime.parse(clean, FLEXIBLE_ISO_FORMATTER).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(clean).toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(clean).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            }
        }
    }

    private fun parseElapsedMinutes(displayClock: String?): Int? {
        if (displayClock.isNullOrBlank()) return null
        val clean = displayClock.replace("'", "").replace("+", "").trim()
        val parts = clean.split(":")
        return parts.firstOrNull()?.toIntOrNull()
    }
}
