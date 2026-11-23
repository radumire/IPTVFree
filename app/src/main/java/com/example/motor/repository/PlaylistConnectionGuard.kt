package com.example.motor.repository

import android.content.Context
import android.util.Log

/**
 * Guardián de seguridad para transacciones de conexión de playlists.
 *
 * Previene crash-loops causados por cierres inesperados (OOM, excepciones no recuperables,
 * apagados) durante la conexión de playlists (especialmente Stalker/MAC).
 *
 * Reglas de seguridad:
 * 1. Antes de iniciar la conexión física con el servidor, se registra el intento en disco de forma síncrona.
 * 2. Si el proceso muere durante la conexión, en el próximo arranque se detecta el crash y se marca
 *    esa playlist como fallida para impedir la re-conexión automática en bucle infinito.
 * 3. Si la conexión tiene éxito, se limpia el intento en vuelo y cualquier marca de fallo previa.
 * 4. Si la conexión falla con error, se marca como fallida y se restaura la playlist anterior estable.
 */
object PlaylistConnectionGuard {
    private const val TAG = "PlaylistConnGuard"
    private const val PREFS_NAME = "playlist_connection_guard"

    private const val KEY_IN_FLIGHT_PLAYLIST_ID = "in_flight_playlist_id"
    private const val KEY_IN_FLIGHT_TIMESTAMP = "in_flight_timestamp"
    private const val KEY_PREVIOUS_ACTIVE_ID = "previous_active_playlist_id"

    private const val KEY_FAILED_PLAYLIST_ID = "failed_playlist_id"
    private const val KEY_FAILED_TIMESTAMP = "failed_timestamp"
    private const val KEY_FAILED_REASON = "failed_reason"

    /**
     * Registra de forma síncrona (.commit()) el inicio de una conexión antes de cualquier I/O de red.
     */
    fun recordConnectionAttempt(context: Context, playlistId: Long, previousActiveId: Long?) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putLong(KEY_IN_FLIGHT_PLAYLIST_ID, playlistId)
                .putLong(KEY_IN_FLIGHT_TIMESTAMP, System.currentTimeMillis())

            if (previousActiveId != null && previousActiveId > 0 && previousActiveId != playlistId) {
                editor.putLong(KEY_PREVIOUS_ACTIVE_ID, previousActiveId)
            } else {
                editor.remove(KEY_PREVIOUS_ACTIVE_ID)
            }
            editor.commit()
            Log.d(TAG, "Connection attempt recorded for playlistId=$playlistId, previousActive=$previousActiveId")
        } catch (t: Throwable) {
            Log.e(TAG, "Error recording connection attempt for playlist $playlistId", t)
        }
    }

    /**
     * Registra el éxito confirmado de la conexión.
     */
    fun recordConnectionSuccess(context: Context, playlistId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .remove(KEY_IN_FLIGHT_PLAYLIST_ID)
                .remove(KEY_IN_FLIGHT_TIMESTAMP)
                .remove(KEY_PREVIOUS_ACTIVE_ID)

            if (prefs.getLong(KEY_FAILED_PLAYLIST_ID, -1L) == playlistId) {
                editor.remove(KEY_FAILED_PLAYLIST_ID)
                editor.remove(KEY_FAILED_TIMESTAMP)
                editor.remove(KEY_FAILED_REASON)
            }
            editor.commit()
            Log.d(TAG, "Connection success recorded for playlistId=$playlistId")
        } catch (t: Throwable) {
            Log.e(TAG, "Error recording connection success for playlist $playlistId", t)
        }
    }

    /**
     * Registra que la conexión falló con una excepción o error capturado.
     */
    fun recordConnectionFailed(context: Context, playlistId: Long, reason: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_IN_FLIGHT_PLAYLIST_ID)
                .remove(KEY_IN_FLIGHT_TIMESTAMP)
                .remove(KEY_PREVIOUS_ACTIVE_ID)
                .putLong(KEY_FAILED_PLAYLIST_ID, playlistId)
                .putLong(KEY_FAILED_TIMESTAMP, System.currentTimeMillis())
                .putString(KEY_FAILED_REASON, reason)
                .commit()
            Log.w(TAG, "Connection failed recorded for playlistId=$playlistId, reason=$reason")
        } catch (t: Throwable) {
            Log.e(TAG, "Error recording connection failure for playlist $playlistId", t)
        }
    }

    /**
     * Registra que la conexión fue cancelada intencionadamente por el usuario.
     */
    fun recordConnectionCancelled(context: Context, playlistId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_IN_FLIGHT_PLAYLIST_ID)
                .remove(KEY_IN_FLIGHT_TIMESTAMP)
                .remove(KEY_PREVIOUS_ACTIVE_ID)
                .commit()
            Log.d(TAG, "Connection cancelled recorded for playlistId=$playlistId")
        } catch (t: Throwable) {
            Log.e(TAG, "Error recording connection cancellation for playlist $playlistId", t)
        }
    }

    /**
     * Llamado en el arranque de la app. Comprueba si el proceso murió mientras conectaba una playlist.
     * Si ocurrió un crash:
     * - Marca esa playlist como fallida.
     * - Devuelve un CrashRecoveryInfo con el id de la playlist que crasheó y la playlist previa estable.
     */
    fun checkAndResolveCrashOnStartup(context: Context): CrashRecoveryInfo? {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val inFlightId = prefs.getLong(KEY_IN_FLIGHT_PLAYLIST_ID, -1L)
            if (inFlightId != -1L && inFlightId > 0) {
                val previousActiveId = prefs.getLong(KEY_PREVIOUS_ACTIVE_ID, -1L).takeIf { it > 0 }
                Log.e(TAG, "CRASH DETECTED ON STARTUP: Process died during connection of playlist $inFlightId. Previous active was $previousActiveId.")

                // Marcar como fallida para impedir re-conexión automática
                prefs.edit()
                    .remove(KEY_IN_FLIGHT_PLAYLIST_ID)
                    .remove(KEY_IN_FLIGHT_TIMESTAMP)
                    .remove(KEY_PREVIOUS_ACTIVE_ID)
                    .putLong(KEY_FAILED_PLAYLIST_ID, inFlightId)
                    .putLong(KEY_FAILED_TIMESTAMP, System.currentTimeMillis())
                    .putString(KEY_FAILED_REASON, "El proceso se detuvo inesperadamente durante la conexión previa.")
                    .commit()

                return CrashRecoveryInfo(
                    crashedPlaylistId = inFlightId,
                    previousActivePlaylistId = previousActiveId
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error checking crash guard on startup", t)
        }
        return null
    }

    fun isPlaylistFailed(context: Context, playlistId: Long): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_FAILED_PLAYLIST_ID, -1L) == playlistId
        } catch (_: Throwable) {
            false
        }
    }

    fun getFailedReason(context: Context, playlistId: Long): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_FAILED_PLAYLIST_ID, -1L) == playlistId) {
                prefs.getString(KEY_FAILED_REASON, null)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clearFailure(context: Context, playlistId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_FAILED_PLAYLIST_ID, -1L) == playlistId) {
                prefs.edit()
                    .remove(KEY_FAILED_PLAYLIST_ID)
                    .remove(KEY_FAILED_TIMESTAMP)
                    .remove(KEY_FAILED_REASON)
                    .commit()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error clearing failure for playlist $playlistId", t)
        }
    }

    fun clearAllForPlaylist(context: Context, playlistId: Long) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var changed = false
            if (prefs.getLong(KEY_FAILED_PLAYLIST_ID, -1L) == playlistId) {
                editor.remove(KEY_FAILED_PLAYLIST_ID)
                    .remove(KEY_FAILED_TIMESTAMP)
                    .remove(KEY_FAILED_REASON)
                changed = true
            }
            if (prefs.getLong(KEY_IN_FLIGHT_PLAYLIST_ID, -1L) == playlistId) {
                editor.remove(KEY_IN_FLIGHT_PLAYLIST_ID)
                    .remove(KEY_IN_FLIGHT_TIMESTAMP)
                changed = true
            }
            if (prefs.getLong(KEY_PREVIOUS_ACTIVE_ID, -1L) == playlistId) {
                editor.remove(KEY_PREVIOUS_ACTIVE_ID)
                changed = true
            }
            if (changed) {
                editor.commit()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error clearing all guard state for playlist $playlistId", t)
        }
    }

    data class CrashRecoveryInfo(
        val crashedPlaylistId: Long,
        val previousActivePlaylistId: Long?
    )
}
