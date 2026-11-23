package com.example.motor.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test double implementing [IPlayerPlaybackController] for deterministic virtual-time testing.
 */
class FakePlayerPlaybackController : IPlayerPlaybackController {
    override var currentPosition: Long = 0L
    override var duration: Long = 0L
    override var playbackState: Int = Player.STATE_IDLE
    override var isPlaying: Boolean = false
    override var playWhenReady: Boolean = true
    override var isLive: Boolean = true

    var prepareCalls = 0
    var playCalls = 0
    var pauseCalls = 0
    var resetCalls = 0
    var lastResetIsLive: Boolean? = null
    var lastResetTargetPos: Long? = null

    override fun prepare() { prepareCalls++ }
    override fun play() { playCalls++ }
    override fun pause() { pauseCalls++ }
    override fun executeStreamReset(resetPosition: Boolean, targetPositionMs: Long) {
        resetCalls++
        lastResetIsLive = resetPosition
        lastResetTargetPos = targetPositionMs
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlayerReconnectTest {

    // ─── ORIGINAL ROBOLECTRIC COMPATIBILITY TESTS ─────────────────────────────

    @Test
    fun testRecoverStalledPlaybackGracefulWhenNotInitialized() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PlayerRepository.recoverStalledPlayback(context)
    }

    @Test
    fun testLiveStreamReconnectionLogic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = PlayerRepository.getExoPlayer(context)
        assertNotNull("ExoPlayer should be instantiated", player)

        val liveUrl = "http://iptv.server.com/live/channel1.ts"
        PlayerRepository.playMedia(
            context = context,
            src = liveUrl,
            headers = mapOf("User-Agent" to "TestAgent"),
            forceReload = true
        )

        val currentItem = player.currentMediaItem
        assertNotNull("Current media item should be loaded", currentItem)

        PlayerRepository.recoverStalledPlayback(context)
        assertTrue("Player should have playWhenReady enabled", player.playWhenReady)
        assertNotNull("Player should have active media item after recovery", player.currentMediaItem)
    }

    // ─── COMPREHENSIVE ARCHITECTURAL RECOVERY SCENARIOS ───────────────────────

    /**
     * Prueba 1: error transitorio
     * - El reproductor informa de un error recuperable (ej. IO connection failed).
     * - Se programa una única recuperación y se ejecuta tras el retardo de backoff.
     */
    @Test
    fun test01_transientErrorTriggersRecoverySequence() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 1000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            val error = PlaybackException(
                "Network connection reset",
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
            coordinator.onPlayerError(error)

            assertEquals("Attempt count should be 1", 1, coordinator.currentAttemptCount)
            assertTrue("Coordinator should be in recovering state", coordinator.isCurrentlyRecovering)
            assertEquals("Stream reset should not execute before backoff", 0, fake.resetCalls)

            advanceTimeBy(1001L)

            assertEquals("Stream reset should have executed after backoff", 1, fake.resetCalls)
            assertEquals("Should reset position for live streams", true, fake.lastResetIsLive)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 2: errores duplicados / concurrentes
     * - Varios errores y solicitudes llegan simultáneamente durante un intento activo.
     * - Se comprueba que no se ejecutan recuperaciones duplicadas.
     */
    @Test
    fun test02_duplicateErrorsDuringRecoveryAreDeduplicated() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 2000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            val error1 = PlaybackException("Timeout 1", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            val error2 = PlaybackException("Timeout 2", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

            coordinator.onPlayerError(error1)
            val acceptedSecond = coordinator.requestRecovery(RecoveryReason.PlayerError(error2.errorCode, error2.message))
            val acceptedThird = coordinator.requestRecovery(RecoveryReason.StalledBuffering)

            assertFalse("Second concurrent recovery request must be rejected", acceptedSecond)
            assertFalse("Third concurrent recovery request must be rejected", acceptedThird)
            assertEquals("Attempt count should remain 1", 1, coordinator.currentAttemptCount)

            advanceTimeBy(2001L)
            assertEquals("Stream reset should execute exactly once", 1, fake.resetCalls)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 3: bloqueo en BUFFERING
     * - La reproducción permanece en STATE_BUFFERING durante >= 4 segundos.
     * - El watchdog detecta el bloqueo y solicita recuperación sin duplicados.
     */
    @Test
    fun test03_prolongedBufferingTriggersRecoveryWithoutDuplicates() = runTest {
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_BUFFERING
            playWhenReady = true
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 1000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            // Grace period (2s) + buffering threshold (4s) = 6s total
            advanceTimeBy(6100L)

            assertEquals("Should trigger recovery after >= 4s buffering", 1, coordinator.currentAttemptCount)
            assertTrue("Coordinator should be in recovering state", coordinator.isCurrentlyRecovering)

            advanceTimeBy(1001L)
            assertEquals(1, fake.resetCalls)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 4: STATE_IDLE inesperado vs parada intencionada
     * - STATE_IDLE mientras la sesión debería estar activa dispara recuperación.
     * - Una parada intencionada cancela los watchdogs y nunca dispara recuperación.
     */
    @Test
    fun test04_unexpectedIdleTriggersRecoveryWhileIntentionalStopDoesNot() = runTest {
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_READY
            playWhenReady = true
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 500L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)
            advanceTimeBy(2500L)

            // Unexpected STATE_IDLE while playWhenReady is true
            fake.playbackState = Player.STATE_IDLE
            advanceTimeBy(1100L)

            assertEquals(1, coordinator.currentAttemptCount)
            advanceTimeBy(501L)
            assertEquals(1, fake.resetCalls)

            // Intentional stop cancels and never triggers recovery
            coordinator.onSessionEnded()
            fake.resetCalls = 0
            fake.playbackState = Player.STATE_IDLE
            advanceTimeBy(5000L)
            assertEquals("Intentional stop should never trigger recovery", 0, fake.resetCalls)
            assertEquals(0, coordinator.currentAttemptCount)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 5: cambio de canal durante la espera de backoff
     * - Se programa una recuperación para el canal A.
     * - Antes de cumplirse el backoff, el usuario cambia al canal B.
     * - La tarea antigua queda invalidada y no se reinicia el canal A.
     */
    @Test
    fun test05_channelChangeDuringBackoffCancelsOldTask() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 5000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/channelA.ts", isLive = true)

            coordinator.onPlayerError(PlaybackException("Disconnect A", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            assertEquals(1, coordinator.currentAttemptCount)
            assertTrue(coordinator.isCurrentlyRecovering)

            // User switches to Channel B after 2 seconds
            advanceTimeBy(2000L)
            assertEquals(0, fake.resetCalls)

            coordinator.onSessionStarted("http://iptv.server.com/live/channelB.ts", isLive = true)
            assertEquals("Switching channel must reset attempt count to 0", 0, coordinator.currentAttemptCount)
            assertFalse("Must not be recovering for Channel A", coordinator.isCurrentlyRecovering)

            advanceTimeBy(4000L)
            assertEquals("Channel A reset must NOT run after channel switch", 0, fake.resetCalls)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 6: pausa voluntaria del usuario
     * - El usuario pausa la reproducción voluntariamente.
     * - Errores o cambios de estado posteriores NO inician reproducción ni auto-recuperación.
     */
    @Test
    fun test06_userVoluntaryPauseSuppressesAutoRecovery() = runTest {
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_READY
            playWhenReady = true
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 1000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)
            advanceTimeBy(2500L)

            // User pauses
            coordinator.onUserPause()
            fake.playWhenReady = false

            coordinator.onPlayerError(PlaybackException("EOF while paused", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            assertEquals("Must not increment attempt count while paused", 0, coordinator.currentAttemptCount)
            assertFalse(coordinator.isCurrentlyRecovering)

            advanceTimeBy(3000L)
            assertEquals("Must never execute reset when paused", 0, fake.resetCalls)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 7: servidor temporalmente no disponible que se restablece
     * - Reintentos con backoff progresivo.
     * - El servidor vuelve a estar disponible y la reproducción se estabiliza.
     */
    @Test
    fun test07_serverTemporarilyUnavailableRecoversWhenBackOnline() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { attempt -> (attempt * 1000L) }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            // Attempt 1
            coordinator.onPlayerError(PlaybackException("Offline 1", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            assertEquals(1, coordinator.currentAttemptCount)
            advanceTimeBy(1001L)
            assertEquals(1, fake.resetCalls)
            advanceTimeBy(1600L)

            // Attempt 2
            coordinator.onPlayerError(PlaybackException("Offline 2", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            assertEquals(2, coordinator.currentAttemptCount)
            advanceTimeBy(2001L)
            assertEquals(2, fake.resetCalls)
            advanceTimeBy(1600L)

            // Server restored: advancing position across 3 consecutive seconds
            fake.playbackState = Player.STATE_READY
            fake.isPlaying = true
            fake.currentPosition = 1000L
            advanceTimeBy(1000L)
            fake.currentPosition = 2000L
            advanceTimeBy(1000L)
            fake.currentPosition = 3000L
            advanceTimeBy(1000L)
            fake.currentPosition = 4000L
            advanceTimeBy(1500L)

            assertEquals("Attempt count should be reset after stable recovery", 0, coordinator.currentAttemptCount)
            assertFalse(coordinator.isCurrentlyRecovering)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 8: fallo persistente respeta la política de backoff
     * - Múltiples fallos consecutivos respetan la progresión de retardos sin ciclos descontrolados.
     */
    @Test
    fun test08_persistentFailureRespectsBackoffPolicyWithoutUnboundedSpam() = runTest {
        val recordedDelays = mutableListOf<Long>()
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_BUFFERING
            playWhenReady = true
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { attempt ->
                val delays = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L)
                val d = if (attempt - 1 in delays.indices) delays[attempt - 1] else 15000L
                recordedDelays.add(d)
                d
            }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            for (i in 1..5) {
                coordinator.onPlayerError(PlaybackException("Persistent failure $i", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
                val expectedDelay = recordedDelays.last()
                advanceTimeBy(expectedDelay + 100L)
                advanceTimeBy(1500L)
            }

            assertEquals(5, fake.resetCalls)
            assertEquals(5, coordinator.currentAttemptCount)
            assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L), recordedDelays)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 9: isPlaying = true con posición congelada
     * - El reproductor reporta isPlaying = true pero la posición permanece idéntica durante >= 4s.
     * - El coordinador NO resetea el contador y detecta el bloqueo (StalledPlayback), disparando recuperación.
     */
    @Test
    fun test09_frozenPositionWhileIsPlayingTrueTriggersStallRecovery() = runTest {
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_READY
            isPlaying = true
            playWhenReady = true
            currentPosition = 5000L
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 1000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            // Pass initial grace period (2s)
            advanceTimeBy(2500L)

            // Position remains frozen at 5000L despite isPlaying = true
            // Watchdog checks every 1s:
            // 1st tick: lastTrackedPos initialized to 5000L
            advanceTimeBy(1000L)
            // 2nd tick: curPos (5000L) == lastTrackedPos -> samePosSeconds = 1
            advanceTimeBy(1000L)
            // 3rd tick: samePosSeconds = 2
            advanceTimeBy(1000L)
            // 4th tick: samePosSeconds = 3
            advanceTimeBy(1000L)
            // 5th tick: samePosSeconds = 4 -> triggers StalledPlayback recovery
            advanceTimeBy(1000L)

            assertEquals("Should trigger recovery after position frozen for >= 4s", 1, coordinator.currentAttemptCount)
            assertTrue("Coordinator should be recovering from frozen stall", coordinator.isCurrentlyRecovering)

            advanceTimeBy(1001L)
            assertEquals("Stream reset should execute", 1, fake.resetCalls)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 10: recuperación estable con posición que avanza continuamente
     * - La posición avanza durante >= 3 segundos consecutivos.
     * - Solo entonces se resetea el contador de reintentos a 0 y pasa a estado Stable.
     */
    @Test
    fun test10_stableRecoveryResetsCounterOnlyAfterConfirmedProgress() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 1000L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)

            coordinator.onPlayerError(PlaybackException("Glitch", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            advanceTimeBy(1001L)
            advanceTimeBy(1600L)
            assertEquals(1, coordinator.currentAttemptCount)

            // Playback resumes with advancing position
            fake.playbackState = Player.STATE_READY
            fake.isPlaying = true
            fake.currentPosition = 500L

            // 1 second advance: not enough to reset
            advanceTimeBy(1000L)
            fake.currentPosition = 1500L
            assertEquals("Should not reset after only 1 second", 1, coordinator.currentAttemptCount)

            // Advance through 3 consecutive seconds of advancing position
            advanceTimeBy(1000L)
            fake.currentPosition = 2500L
            advanceTimeBy(1000L)
            fake.currentPosition = 3500L
            advanceTimeBy(1500L)

            assertEquals("Counter must reset to 0 after continuous stable playback", 0, coordinator.currentAttemptCount)
            assertTrue("Coordinator state should be Stable", coordinator.coordinatorState.value is CoordinatorState.Stable)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 11: errores permanentes HTTP 401, 403, 404 y 410
     * - Cuando ocurre un error permanente, se marca la sesión como permanentemente fallida.
     * - El watchdog se suprime automáticamente y NO realiza reintentos posteriores para esa sesión.
     */
    @Test
    fun test11_httpPermanentErrorsBlockAutomaticRetries() = runTest {
        val permanentCodes = listOf(401, 403, 404, 410)

        for (code in permanentCodes) {
            val fake = FakePlayerPlaybackController().apply {
                playbackState = Player.STATE_BUFFERING
                playWhenReady = true
            }
            val coordinator = PlaybackRecoveryCoordinator(
                controller = fake,
                coroutineScope = this,
                backoffPolicy = { 1000L }
            )
            try {
                coordinator.onSessionStarted("http://iptv.server.com/live/ch_$code.ts", isLive = true)

                val httpException = HttpDataSource.InvalidResponseCodeException(
                    code,
                    "HTTP $code Error",
                    null,
                    emptyMap(),
                    DataSpec(Uri.parse("http://iptv.server.com/live/ch_$code.ts")),
                    byteArrayOf()
                )
                val playbackException = PlaybackException(
                    "Response code: $code",
                    httpException,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                )

                coordinator.onPlayerError(playbackException)

                assertTrue("Session must be marked permanently failed for HTTP $code", coordinator.isSessionPermanentlyFailed)
                assertFalse("Must not be in recovering state for permanent error", coordinator.isCurrentlyRecovering)
                assertEquals("Attempt count should remain 0", 0, coordinator.currentAttemptCount)
                assertTrue("Coordinator state must be PermanentFailure", coordinator.coordinatorState.value is CoordinatorState.PermanentFailure)

                // Advance time significantly: watchdog must NEVER trigger recovery
                advanceTimeBy(15000L)
                assertEquals("Watchdog must never execute stream reset for permanent error HTTP $code", 0, fake.resetCalls)
            } finally {
                coordinator.release()
            }
        }
    }

    /**
     * Prueba 12: desbloqueo de error permanente al cambiar de canal o reintento manual
     * - Un error 403 bloquea la sesión actual.
     * - El usuario cambia de canal o pulsa Reintentar (ManualRetry).
     * - La nueva sesión o reintento manual se ejecuta con éxito.
     */
    @Test
    fun test12_manualRetryOrChannelChangeClearsPermanentFailure() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 500L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/forbidden.ts", isLive = true)

            val error403 = PlaybackException("HTTP 403 Forbidden", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            coordinator.onPlayerError(error403)
            assertTrue("Session should be permanently failed", coordinator.isSessionPermanentlyFailed)

            // Case A: Manual retry from user clears permanent failure
            val manualAccepted = coordinator.requestRecovery(RecoveryReason.ManualRetry)
            assertTrue("Manual retry must be accepted even if previously permanently failed", manualAccepted)
            assertFalse("Permanent failure flag should be cleared by manual retry", coordinator.isSessionPermanentlyFailed)

            advanceTimeBy(501L)
            assertEquals("Manual retry should have executed reset", 1, fake.resetCalls)

            // Case B: Channel change starts fresh session without permanent failure carryover
            coordinator.onPlayerError(error403)
            assertTrue("Re-triggered 403 marks permanently failed again", coordinator.isSessionPermanentlyFailed)

            coordinator.onSessionStarted("http://iptv.server.com/live/working.ts", isLive = true)
            assertFalse("New channel must start with permanent failure cleared", coordinator.isSessionPermanentlyFailed)
            assertEquals("Attempt count should be 0 for new channel", 0, coordinator.currentAttemptCount)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 13: transiciones normales de Media3 durante la preparación
     * - Durante la carga inicial, el reproductor puede pasar por STATE_IDLE o STATE_BUFFERING.
     * - El coordinador no debe entrar en bucles espurios de reconexión mientras isPreparing sea true.
     */
    @Test
    fun test13_normalMedia3PreparationStateIdleDoesNotTriggerRecovery() = runTest {
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_IDLE
            playWhenReady = true
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 500L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/initial.ts", isLive = true)

            // Player reports normal STATE_IDLE right after starting preparation
            coordinator.onPlaybackStateChanged(Player.STATE_IDLE)

            assertEquals("Normal preparation STATE_IDLE must not trigger recovery", 0, coordinator.currentAttemptCount)
            assertFalse(coordinator.isCurrentlyRecovering)

            // When player moves to BUFFERING and then READY, preparation concludes smoothly
            coordinator.onPlaybackStateChanged(Player.STATE_BUFFERING)
            coordinator.onPlaybackStateChanged(Player.STATE_READY)

            assertEquals(0, coordinator.currentAttemptCount)
            assertEquals(0, fake.resetCalls)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 14: preservación de posición en VOD vs reinicio al directo en Live TV
     * - Para Live TV (isLive = true), executeStreamReset pasa resetPosition = true y targetPositionMs = 0.
     * - Para VOD (isLive = false), executeStreamReset pasa resetPosition = false y targetPositionMs = posición actual.
     */
    @Test
    fun test14_liveStreamResetsPositionWhileVodPreservesPosition() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 500L }
        )
        try {
            // Case 1: Live TV stream
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)
            fake.currentPosition = 45000L
            coordinator.onPlayerError(PlaybackException("Live glitch", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            advanceTimeBy(501L)

            assertEquals("Live stream should call reset with resetPosition = true", true, fake.lastResetIsLive)
            assertEquals("Live stream target position should be 0", 0L, fake.lastResetTargetPos)

            advanceTimeBy(1600L) // Wait out cooldown

            // Case 2: VOD movie stream
            coordinator.onSessionStarted("http://iptv.server.com/movie/test.mp4", isLive = false)
            fake.currentPosition = 120000L // 2 minutes in
            coordinator.onPlayerError(PlaybackException("VOD glitch", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            advanceTimeBy(501L)

            assertEquals("VOD stream should call reset with resetPosition = false", false, fake.lastResetIsLive)
            assertEquals("VOD stream should preserve exact target position", 120000L, fake.lastResetTargetPos)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 15: desconexión por fin prematuro de stream (EOF drop / STATE_ENDED)
     * - En canales Live TV, STATE_ENDED siempre dispara auto-recuperación al directo (pos 0).
     * - En VOD (películas y series), STATE_ENDED antes del final (>15s antes de duración)
     *   dispara auto-recuperación preservando la posición exacta antes del corte.
     */
    @Test
    fun test15_prematureStreamEndTriggersAutoRecoveryForLiveAndVod() = runTest {
        val fake = FakePlayerPlaybackController()
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 500L }
        )
        try {
            // Live TV EOF cut
            coordinator.onSessionStarted("http://iptv.server.com/live/ch1.ts", isLive = true)
            fake.currentPosition = 30000L
            coordinator.onPlaybackStateChanged(Player.STATE_ENDED)

            assertEquals("Premature STATE_ENDED on Live TV must trigger recovery", 1, coordinator.currentAttemptCount)
            advanceTimeBy(501L)
            assertEquals(1, fake.resetCalls)
            assertEquals(true, fake.lastResetIsLive)
            assertEquals(0L, fake.lastResetTargetPos)

            advanceTimeBy(1600L) // Wait cooldown

            // VOD cut at 45m out of 120m
            coordinator.onSessionStarted("http://iptv.server.com/movie/film.mp4", isLive = false)
            fake.duration = 7200000L // 2 hours
            fake.currentPosition = 2700000L // 45 mins
            coordinator.onPlaybackStateChanged(Player.STATE_READY)
            coordinator.onPlaybackStateChanged(Player.STATE_ENDED)

            assertEquals("Premature STATE_ENDED on VOD must trigger recovery", 1, coordinator.currentAttemptCount)
            advanceTimeBy(501L)
            assertEquals(2, fake.resetCalls)
            assertEquals(false, fake.lastResetIsLive)
            assertEquals(2700000L, fake.lastResetTargetPos)
        } finally {
            coordinator.release()
        }
    }

    /**
     * Prueba 16: refresco de token tras reproducción sostenida (>5s) ante error HTTP 401/403
     * - Si el stream llevaba tiempo reproduciéndose y caduca el token (401/403), intenta
     *   una única recuperación automática para renovar la URL; si vuelve a fallar, se detiene.
     */
    @Test
    fun test16_midStreamTokenExpiryTriggersSingleRefreshRecovery() = runTest {
        val fake = FakePlayerPlaybackController().apply {
            playbackState = Player.STATE_READY
            isPlaying = true
            playWhenReady = true
            currentPosition = 600_000L // 10 minutes into stream
        }
        val coordinator = PlaybackRecoveryCoordinator(
            controller = fake,
            coroutineScope = this,
            backoffPolicy = { 500L }
        )
        try {
            coordinator.onSessionStarted("http://iptv.server.com/live/tokenized.ts?token=old", isLive = true)
            coordinator.onPlaybackStateChanged(Player.STATE_READY)
            coordinator.onRenderedFirstFrame()

            val expiredTokenError = PlaybackException("HTTP 401 Unauthorized", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            coordinator.onPlayerError(expiredTokenError)

            assertFalse("Mid-stream 401 after sustained playback should allow 1 refresh attempt", coordinator.isSessionPermanentlyFailed)
            assertEquals(1, coordinator.currentAttemptCount)

            advanceTimeBy(501L)
            assertEquals("Reset should execute to fetch fresh stream URL", 1, fake.resetCalls)
            advanceTimeBy(1600L)

            // If fresh URL ALSO returns 401/403 immediately, treat as permanent failure
            coordinator.onPlayerError(expiredTokenError)
            assertTrue("Second consecutive 401 must mark permanent failure", coordinator.isSessionPermanentlyFailed)
        } finally {
            coordinator.release()
        }
    }
}
