package com.example.motor.sports.parser

import android.util.Log
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import org.json.JSONObject

/**
 * Parser para API-Baseball (v1.baseball.api-sports.io).
 */
object BaseballSportsParser {
    private const val TAG = "BaseballSportsParser"

    fun parse(jsonString: String, isLiveOnly: Boolean): List<SportsEvent> {
        val events = mutableListOf<SportsEvent>()
        if (jsonString.isBlank()) return events

        try {
            val root = JSONObject(jsonString)

            val errorsObj = root.optJSONObject("errors")
            if (errorsObj != null && errorsObj.length() > 0) {
                Log.w(TAG, "API-Baseball response errors: $errorsObj")
            }
            val errorsArr = root.optJSONArray("errors")
            if (errorsArr != null && errorsArr.length() > 0) {
                Log.w(TAG, "API-Baseball response errors: $errorsArr")
            }

            val responseArray = root.optJSONArray("response") ?: return emptyList()

            for (i in 0 until responseArray.length()) {
                val item = responseArray.optJSONObject(i) ?: continue
                // En API-Baseball, los campos del partido vienen en el objeto raíz 'item'.
                // Mantenemos fallback a 'game' o 'fixture' para compatibilidad retroactiva.
                val gameContainer = item.optJSONObject("game") ?: item.optJSONObject("fixture") ?: item

                val id = when {
                    item.has("id") && !item.isNull("id") -> item.opt("id")?.toString()?.trim()
                    gameContainer.has("id") && !gameContainer.isNull("id") -> gameContainer.opt("id")?.toString()?.trim()
                    else -> null
                }?.takeIf { it.isNotBlank() && it != "null" } ?: continue

                val timestampSec = when {
                    item.has("timestamp") && !item.isNull("timestamp") -> item.optLong("timestamp", 0L)
                    gameContainer.has("timestamp") && !gameContainer.isNull("timestamp") -> gameContainer.optLong("timestamp", 0L)
                    else -> 0L
                }
                val dateStr = item.optString("date").takeIf { it.isNotBlank() && it != "null" }
                    ?: gameContainer.optString("date").takeIf { it.isNotBlank() && it != "null" }
                val startTimeMs = parseStartTime(timestampSec, dateStr)

                val statusObj = item.optJSONObject("status") ?: gameContainer.optJSONObject("status")
                val shortStatus = when {
                    statusObj != null -> statusObj.optString("short", "NS").trim().uppercase().takeIf { it != "null" && it.isNotBlank() } ?: "NS"
                    item.has("status") && !item.isNull("status") -> item.optString("status", "NS").trim().uppercase().takeIf { it != "null" && it.isNotBlank() } ?: "NS"
                    gameContainer.has("status") && !gameContainer.isNull("status") -> gameContainer.optString("status", "NS").trim().uppercase().takeIf { it != "null" && it.isNotBlank() } ?: "NS"
                    else -> "NS"
                }

                val eventStatus = mapBaseballStatus(shortStatus)
                if (eventStatus == SportsEventStatus.FINISHED || eventStatus == SportsEventStatus.CANCELLED) continue
                if (isLiveOnly && eventStatus != SportsEventStatus.LIVE) continue

                val teams = item.optJSONObject("teams") ?: gameContainer.optJSONObject("teams")
                val homeObj = teams?.optJSONObject("home") ?: item.optJSONObject("home")
                val awayObj = teams?.optJSONObject("away") ?: item.optJSONObject("away")
                val homeName = homeObj?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("home_team").takeIf { it.isNotBlank() && it != "null" }
                    ?: "Local"
                val awayName = awayObj?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("away_team").takeIf { it.isNotBlank() && it != "null" }
                    ?: "Visitante"
                val homeLogo = homeObj?.optString("logo")?.takeIf { it.isNotBlank() && it != "null" }
                val awayLogo = awayObj?.optString("logo")?.takeIf { it.isNotBlank() && it != "null" }

                val league = item.optJSONObject("league") ?: gameContainer.optJSONObject("league")
                val leagueName = league?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: "Béisbol"
                val leagueLogo = league?.optString("logo")?.takeIf { it.isNotBlank() && it != "null" }

                val countryObj = item.optJSONObject("country") ?: league?.optJSONObject("country")
                val country = countryObj?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("country").takeIf { it.isNotBlank() && it != "null" }
                    ?: league?.optString("country")?.takeIf { it.isNotBlank() && it != "null" }

                val scores = item.optJSONObject("scores") ?: gameContainer.optJSONObject("scores") ?: item.optJSONObject("goals")
                val homeScore = scores?.optJSONObject("home")?.optInt("total", -1)?.takeIf { it >= 0 }
                    ?: scores?.optInt("home", -1)?.takeIf { it >= 0 }
                val awayScore = scores?.optJSONObject("away")?.optInt("total", -1)?.takeIf { it >= 0 }
                    ?: scores?.optInt("away", -1)?.takeIf { it >= 0 }

                events.add(
                    SportsEvent(
                        id = id,
                        sport = SportsSport.BASEBALL.id,
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
                        elapsedMinutes = null,
                        country = country,
                        broadcaster = null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear Baseball JSON: ${e.message}", e)
        }
        return events
    }

    private fun parseStartTime(timestampSec: Long, dateString: String?): Long {
        if (timestampSec > 0) return timestampSec * 1000L
        if (!dateString.isNullOrBlank()) {
            try {
                return java.time.OffsetDateTime.parse(dateString).toInstant().toEpochMilli()
            } catch (_: Exception) {}
            try {
                return java.time.Instant.parse(dateString).toEpochMilli()
            } catch (_: Exception) {}
            try {
                val cleanDate = dateString.substringBefore("T")
                return java.time.LocalDate.parse(cleanDate)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    fun mapBaseballStatus(shortStatus: String): SportsEventStatus {
        return when (shortStatus.trim().uppercase()) {
            "NS", "TBD" -> SportsEventStatus.SCHEDULED
            "IN1", "IN2", "IN3", "IN4", "IN5", "IN6", "IN7", "IN8", "IN9", "EI", "LIVE", "IN_PLAY", "INT", "MID", "TOP", "BOT" -> SportsEventStatus.LIVE
            "FT", "AOT", "FINAL" -> SportsEventStatus.FINISHED
            "POST", "PST", "SUSP", "DELAYED" -> SportsEventStatus.POSTPONED
            "CANC", "ABD" -> SportsEventStatus.CANCELLED
            else -> SportsEventStatus.SCHEDULED
        }
    }
}
