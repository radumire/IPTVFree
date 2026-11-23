package com.example.motor.sports.repository

import com.example.motor.sports.model.SportsSport

/**
 * Excepción cuando la API devuelve un error de autorización o falta de suscripción activa
 * (ej: HTTP 401, HTTP 403, o respuesta con payload "errors": {"token": ...} o {"subscription": ...}).
 */
class SportsUnauthorizedException(
    val sport: SportsSport,
    message: String,
    val errorPayload: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Excepción cuando la API devuelve un error HTTP no exitoso (ej: 429, 500, 503).
 */
class SportsHttpException(
    val sport: SportsSport,
    val statusCode: Int,
    message: String,
    val errorPayload: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Excepción cuando el deporte no cuenta con un endpoint implementado o compatible en el proveedor actual.
 */
class SportsNotSupportedException(
    val sport: SportsSport,
    message: String = "El deporte ${sport.id} no cuenta con endpoint implementado en el proveedor actual."
) : Exception(message)
