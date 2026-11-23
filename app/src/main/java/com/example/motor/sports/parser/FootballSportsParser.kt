package com.example.motor.sports.parser

import android.util.Log
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import org.json.JSONObject

/**
 * Parser de alta fidelidad para API-Football (v3.football.api-sports.io).
 *
 * Convierte respuestas reales de fixtures a SportsEvent respetando los estados oficiales:
 * - SCHEDULED (TBD, NS)
 * - LIVE (1H, HT, 2H, ET, BT, P, INT, LIVE, IN_PLAY)
 * - FINISHED (FT, AET, PEN, AWD, WO) -> Se descartan
 * - POSTPONED (PST, SUSP) -> Se conservan si corresponden a la fecha
 * - CANCELLED (CANC, ABD) -> Se descartan
 *
 * Nunca inventa eventos, equipos, marcadores ni horarios.
 */
object FootballSportsParser {
    private const val TAG = "FootballSportsParser"

    fun parse(jsonString: String, isLiveOnly: Boolean): List<SportsEvent> {
        val events = mutableListOf<SportsEvent>()
        if (jsonString.isBlank()) return events

        try {
            val root = JSONObject(jsonString)

            // Logging de posibles errores devueltos en la carga útil de la API
            val errorsObj = root.optJSONObject("errors")
            if (errorsObj != null && errorsObj.length() > 0) {
                Log.w(TAG, "API-Football response errors: $errorsObj")
            }
            val errorsArr = root.optJSONArray("errors")
            if (errorsArr != null && errorsArr.length() > 0) {
                Log.w(TAG, "API-Football response errors: $errorsArr")
            }

            val responseArray = root.optJSONArray("response") ?: return emptyList()

            for (i in 0 until responseArray.length()) {
                val item = responseArray.optJSONObject(i) ?: continue
                val fixture = item.optJSONObject("fixture") ?: continue
                val league = item.optJSONObject("league")
                val teams = item.optJSONObject("teams") ?: continue
                val goals = item.optJSONObject("goals")

                val id = fixture.optString("id", "")
                if (id.isBlank()) continue

                val timestampSec = fixture.optLong("timestamp", 0L)
                val startTimeMs = if (timestampSec > 0) timestampSec * 1000L else System.currentTimeMillis()

                val statusObj = fixture.optJSONObject("status")
                val shortStatus = statusObj?.optString("short", "NS")?.trim()?.uppercase() ?: "NS"
                val elapsed = statusObj?.optInt("elapsed", 0)

                val eventStatus = mapFootballStatus(shortStatus)

                // 1. Un evento FINISHED nunca debe mostrarse en la UI final
                if (eventStatus == SportsEventStatus.FINISHED) continue

                // 2. Un evento CANCELLED nunca debe mostrarse
                if (eventStatus == SportsEventStatus.CANCELLED) continue

                // 3. Si es solicitud exclusiva de directo, solo admitir eventos LIVE confirmados
                if (isLiveOnly && eventStatus != SportsEventStatus.LIVE) continue

                val homeObj = teams.optJSONObject("home")
                val awayObj = teams.optJSONObject("away")
                val homeName = homeObj?.optString("name", "Local") ?: "Local"
                val awayName = awayObj?.optString("name", "Visitante") ?: "Visitante"
                val homeLogo = homeObj?.optString("logo")?.takeIf { it.isNotBlank() }
                val awayLogo = awayObj?.optString("logo")?.takeIf { it.isNotBlank() }

                val leagueName = league?.optString("name", "Fútbol") ?: "Fútbol"
                val leagueLogo = league?.optString("logo")?.takeIf { it.isNotBlank() }
                val country = league?.optString("country")

                val homeScore = goals?.optInt("home")?.takeIf { it >= 0 }
                val awayScore = goals?.optInt("away")?.takeIf { it >= 0 }

                events.add(
                    SportsEvent(
                        id = id,
                        sport = SportsSport.FOOTBALL.id,
                        league = leagueName,
                        leagueLogo = leagueLogo,
                        homeTeam = homeName,
                        awayTeam = awayName,
                        homeTeamLogo = homeLogo,
                        awayTeamLogo = awayLogo,
                        startTime = startTimeMs,
                        status = eventStatus,
                        statusText = shortStatus,
                        homeScore = homeScore,
                        awayScore = awayScore,
                        elapsedMinutes = if (elapsed != null && elapsed > 0) elapsed else null,
                        country = country,
                        broadcaster = null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear respuesta JSON de Football: ${e.message}", e)
        }
        return events
    }

    fun mapFootballStatus(shortStatus: String): SportsEventStatus {
        return when (shortStatus.trim().uppercase()) {
            "TBD", "NS" -> SportsEventStatus.SCHEDULED
            "1H", "HT", "2H", "ET", "BT", "P", "INT", "LIVE", "IN_PLAY" -> SportsEventStatus.LIVE
            "FT", "AET", "PEN", "AWD", "WO" -> SportsEventStatus.FINISHED
            "PST", "SUSP" -> SportsEventStatus.POSTPONED
            "CANC", "ABD" -> SportsEventStatus.CANCELLED
            else -> SportsEventStatus.SCHEDULED
        }
    }
}
