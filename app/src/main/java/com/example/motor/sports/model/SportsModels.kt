package com.example.motor.sports.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SportsSport(val id: String, val translationKey: String, val apiEndpoint: String, val emoji: String) {
    FOOTBALL("football", "football", "football", "⚽"),
    FORMULA1("formula1", "formula1", "formula1", "🏎"),
    MOTOS("motos", "motos", "motos", "🏍"),
    BASKETBALL("basketball", "basketball", "basketball", "🏀"),
    BOXING("boxing", "boxing", "boxing", "🥊"),
    TENNIS("tennis", "tennis", "tennis", "🎾"),
    UFC("ufc", "ufc", "mma/ufc", "🥋"),
    RUGBY("rugby", "rugby", "rugby", "🏉"),
    BASEBALL("baseball", "baseball", "baseball", "⚾"),
    GOLF("golf", "golf", "golf", "⛳"),
    OTHER("other_sports", "other_sports", "other", "•••");

    companion object {
        fun fromId(id: String): SportsSport {
            return entries.firstOrNull {
                it.id.equals(id, ignoreCase = true) ||
                it.apiEndpoint.equals(id, ignoreCase = true) ||
                (it == UFC && (id.equals("mma", ignoreCase = true) || id.equals("ufc_mma", ignoreCase = true)))
            } ?: FOOTBALL
        }
    }
}

enum class SportsFilter(val id: String, val translationKey: String) {
    ALL("all", "all"),
    LIVE("live", "live"),
    TODAY("today", "today"),
    TOMORROW("tomorrow", "tomorrow"),
    THIS_WEEK("this_week", "this_week")
}

enum class SportsEventStatus {
    SCHEDULED,
    LIVE,
    FINISHED,
    POSTPONED,
    CANCELLED
}

data class SportsEvent(
    val id: String,
    val sport: String,
    val league: String,
    val leagueLogo: String? = null,
    val homeTeam: String,
    val awayTeam: String,
    val homeTeamLogo: String? = null,
    val awayTeamLogo: String? = null,
    val startTime: Long, // Epoch millis
    val status: SportsEventStatus,
    val statusText: String = "",
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val elapsedMinutes: Int? = null,
    val country: String? = null,
    val broadcaster: String? = null,
    val broadcastChannels: List<String> = emptyList(),
    val isLive: Boolean = (status == SportsEventStatus.LIVE),
    val isFinished: Boolean = (status == SportsEventStatus.FINISHED)
) {
    fun withBroadcasters(channels: List<String>): SportsEvent {
        return copy(
            broadcaster = channels.firstOrNull() ?: this.broadcaster,
            broadcastChannels = if (channels.isNotEmpty()) channels else this.broadcastChannels
        )
    }

    fun getEventDateIso(zoneId: ZoneId = ZoneId.systemDefault()): String {
        val eventDate = Instant.ofEpochMilli(startTime).atZone(zoneId).toLocalDate()
        return eventDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    fun getFormattedTime(zoneId: ZoneId = ZoneId.systemDefault()): String {
        val instant = Instant.ofEpochMilli(startTime)
        val zonedDateTime = instant.atZone(zoneId)
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return zonedDateTime.format(formatter)
    }

    fun getFormattedDateTime(zoneId: ZoneId = ZoneId.systemDefault()): String {
        val zonedDateTime = Instant.ofEpochMilli(startTime).atZone(zoneId)
        val eventDate = zonedDateTime.toLocalDate()
        val today = LocalDate.now(zoneId)
        val timeStr = zonedDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when {
            eventDate == today -> timeStr
            eventDate == today.plusDays(1) -> "Mañana · $timeStr"
            else -> {
                val dateStr = zonedDateTime.format(DateTimeFormatter.ofPattern("dd/MM"))
                "$dateStr · $timeStr"
            }
        }
    }

    fun isToday(zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val eventDate = Instant.ofEpochMilli(startTime).atZone(zoneId).toLocalDate()
        val today = LocalDate.now(zoneId)
        return eventDate == today
    }

    fun isTomorrow(zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val eventDate = Instant.ofEpochMilli(startTime).atZone(zoneId).toLocalDate()
        val tomorrow = LocalDate.now(zoneId).plusDays(1)
        return eventDate == tomorrow
    }
}

data class ChannelMatchCandidate(
    val channelName: String,
    val channelId: String? = null,
    val streamUrl: String? = null,
    val confidence: Float = 0f
)
