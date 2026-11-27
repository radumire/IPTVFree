package com.example.motor.sports.matcher

import com.example.motor.db.IptvItem
import com.example.motor.sports.model.SportsEvent

internal const val STALKER_NO_PLAYABLE_CMD = "CHANNEL_NO_PLAYABLE_CMD"

internal fun IptvItem.isPlayableChannel(): Boolean {
    val cmdTrimmed = stalkerCmd?.trim().orEmpty()
    if (cmdTrimmed.equals(STALKER_NO_PLAYABLE_CMD, ignoreCase = true)) {
        return false
    }
    val urlTrimmed = url?.trim().orEmpty()
    val hasValidUrl = urlTrimmed.isNotEmpty() && !urlTrimmed.equals(STALKER_NO_PLAYABLE_CMD, ignoreCase = true)
    val hasValidStalkerCmd = cmdTrimmed.isNotEmpty()
    return hasValidUrl || hasValidStalkerCmd
}

internal fun IptvItem.dedupIdentityKey(): String {
    val normName = name.trim().lowercase()
    val normCmd = stalkerCmd?.trim().orEmpty()
    val normUrl = url?.trim().orEmpty()
    return "$normName\u0000$normCmd\u0000$normUrl"
}

enum class MatchConfidence {
    HIGH,     // Coincidencia fuerte -> elegible para AUTO_PLAY
    MEDIUM,   // Coincidencia suficiente -> elegible para SELECTION
    LOW,      // Coincidencia débil -> descartada
    NONE      // Sin coincidencia
}

enum class MatchDecision {
    AUTO_PLAY,  // Exactamente 1 candidato con confianza HIGH
    SELECTION,  // Múltiples candidatos que superan el umbral SELECTION
    NO_MATCH    // Ningún candidato supera el umbral SELECTION
}

data class SportsChannelCandidate(
    val channel: IptvItem,
    val score: Int,
    val confidence: MatchConfidence,
    val reasons: List<String>
)

data class SportsChannelMatchResult(
    val decision: MatchDecision,
    val candidates: List<SportsChannelCandidate>,
    val topMatch: SportsChannelCandidate? = candidates.firstOrNull(),
    val playlistId: Long? = null
)

/**
 * Contrato principal del vinculador entre eventos deportivos y canales IPTV de la playlist activa.
 */
interface SportsChannelMatcher {

    companion object {
        const val AUTO_PLAY_THRESHOLD = 50
        const val SELECTION_THRESHOLD = 25
    }

    /**
     * Evalúa un evento deportivo contra los canales de TV de la playlist activa.
     *
     * @param event El evento deportivo a emparejar.
     * @param availableChannels Lista de canales de TV disponibles en la playlist activa (sin re-descargas).
     * @param playlistId ID de la playlist activa para asegurar invalidación si cambia.
     * @return [SportsChannelMatchResult] con la decisión tomada (AUTO_PLAY, SELECTION, NO_MATCH) y candidatos ordenados.
     */
    suspend fun matchChannel(
        event: SportsEvent,
        availableChannels: List<IptvItem>,
        playlistId: Long? = null
    ): SportsChannelMatchResult
}
