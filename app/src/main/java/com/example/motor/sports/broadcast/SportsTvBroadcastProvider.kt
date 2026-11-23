package com.example.motor.sports.broadcast

import com.example.motor.sports.model.SportsEvent

/**
 * Información de emisión televisiva resuelta para un evento deportivo.
 */
data class SportsBroadcastInfo(
    val eventId: String? = null,
    val channels: List<String> = emptyList(),
    val source: String = "TheSportsDB"
)

/**
 * Contrato para proveedores de información de retransmisión televisiva de eventos deportivos.
 */
interface SportsTvBroadcastProvider {

    /**
     * Resuelve de forma asíncrona la lista de emisoras de televisión que emiten el [event] dado.
     *
     * @param event El evento deportivo a consultar.
     * @return [SportsBroadcastInfo] si se localizó información de TV, o null en caso contrario o error/timeout.
     */
    suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo?
}
