package com.example.subtitles

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.example.interfaz.viewmodel.PlayerViewModel
import com.example.motor.player.PlayerRepository
import com.example.motor.player.TrackOption
import com.example.motor.subtitles.ExternalSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlayerSubtitleThreadTest {

    @After
    fun tearDown() {
        PlayerRepository.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun testDisableTextTracks_runsSafelyFromIOThread() = runBlocking {
        withTimeout(15_000L) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val player = PlayerRepository.getExoPlayer(context)
            assertNotNull(player)

            val mediaFile = File(context.cacheDir, "sample.mp4").apply {
                if (!exists()) createNewFile()
            }

            PlayerRepository.playMedia(
                context = context,
                src = android.net.Uri.fromFile(mediaFile).toString(),
                initialPosMs = 1234L,
                subtitlesEnabled = true
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

            // Invoke disableTextTracks from background IO thread
            withContext(Dispatchers.IO) {
                PlayerRepository.disableTextTracks()
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(player, PlayerRepository.getExoPlayer(context))
            assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        }
    }

    @Test
    fun testSelectExternalSubtitle_switchesFromIOToMainAndSetsState() = runBlocking {
        withTimeout(15_000L) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val mediaFile = File(app.cacheDir, "movie.mp4").apply {
                if (!exists()) createNewFile()
            }
            PlayerRepository.playMedia(
                context = app,
                src = android.net.Uri.fromFile(mediaFile).toString(),
                initialPosMs = 5000L
            )
            shadowOf(Looper.getMainLooper()).idle()

            val subFile = File(app.cacheDir, "movie_es.srt").apply {
                writeText("1\n00:00:01,000 --> 00:00:04,000\nHola Mundo\n")
            }

            val viewModel = PlayerViewModel(app)
            val testSub = ExternalSubtitle(
                id = "es_track_1",
                url = subFile.absolutePath,
                label = "Español",
                language = "es",
                source = "OpenSubtitles"
            )

            viewModel.selectExternalSubtitle(testSub)
            var attempts = 0
            while (viewModel.selectedExternalSubtitle.value == null && attempts++ < 100) {
                delay(50)
                shadowOf(Looper.getMainLooper()).idle()
            }

            assertEquals("es_track_1", viewModel.selectedExternalSubtitle.value?.id)
            assertEquals(1, viewModel.activeSubtitleCues.value.size)
            assertEquals("Hola Mundo", viewModel.activeSubtitleCues.value.first().text)
        }
    }

    @Test
    fun testSelectingSecondExternalSubtitle_replacesFirstCompletelyAndDisablesExoTextTracks() = runBlocking {
        withTimeout(15_000L) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val mediaFile = File(app.cacheDir, "movie_two_subs.mp4").apply {
                if (!exists()) createNewFile()
            }
            val player = PlayerRepository.getExoPlayer(app)
            PlayerRepository.playMedia(
                context = app,
                src = android.net.Uri.fromFile(mediaFile).toString(),
                initialPosMs = 1000L,
                subtitlesEnabled = true
            )
            shadowOf(Looper.getMainLooper()).idle()

            val subFile1 = File(app.cacheDir, "subcat_first.srt").apply {
                writeText("1\n00:00:01,000 --> 00:00:05,000\nPrimer subtítulo incorrecto\n")
            }
            val subFile2 = File(app.cacheDir, "subcat_second.srt").apply {
                writeText("1\n00:00:01,000 --> 00:00:05,000\nSegundo subtítulo correcto\n")
            }

            val viewModel = PlayerViewModel(app)
            val sub1 = ExternalSubtitle(
                id = "subcat_1",
                url = subFile1.absolutePath,
                label = "Español #1",
                language = "es",
                source = "SubtitleCat"
            )
            val sub2 = ExternalSubtitle(
                id = "subcat_2",
                url = subFile2.absolutePath,
                label = "Español #2",
                language = "es",
                source = "SubtitleCat"
            )

            viewModel.selectExternalSubtitle(sub1)
            var attempts = 0
            while (viewModel.selectedExternalSubtitle.value?.id != "subcat_1" && attempts++ < 100) {
                delay(50)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals("subcat_1", viewModel.selectedExternalSubtitle.value?.id)
            assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

            // Select second subtitle from SubtitleCat
            viewModel.selectExternalSubtitle(sub2)
            attempts = 0
            while (viewModel.selectedExternalSubtitle.value?.id != "subcat_2" && attempts++ < 100) {
                delay(50)
                shadowOf(Looper.getMainLooper()).idle()
            }
            shadowOf(Looper.getMainLooper()).idle()

            // First subtitle cues must be completely replaced and ExoPlayer native text track must remain disabled
            assertEquals("subcat_2", viewModel.selectedExternalSubtitle.value?.id)
            assertEquals(1, viewModel.activeSubtitleCues.value.size)
            assertEquals("Segundo subtítulo correcto", viewModel.activeSubtitleCues.value.first().text)
            assertFalse(viewModel.activeSubtitleCues.value.any { it.text.contains("Primer subtítulo") })
            assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

            // Disabling via noneTrack must clear external cues cleanly
            viewModel.selectSubtitleTrack(
                TrackOption(
                    id = "none",
                    name = "Desactivar",
                    type = C.TRACK_TYPE_TEXT,
                    groupIndex = -1,
                    trackIndex = -1,
                    isSelected = false,
                    isNone = true
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(viewModel.selectedExternalSubtitle.value)
            assertTrue(viewModel.activeSubtitleCues.value.isEmpty())
            assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        }
    }

    @Test
    fun testSelectExternalSubtitle_handlesMissingFileCleanlyWithoutBlockingUI() = runBlocking {
        withTimeout(15_000L) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PlayerViewModel(app)
            val invalidSub = ExternalSubtitle(
                id = "invalid_sub_99",
                url = "/non/existent/path/sub.srt",
                label = "Invalid Sub",
                language = "es",
                source = "OpenSubtitles"
            )

            val notifications = mutableListOf<String>()
            val collectJob = launch(Dispatchers.Unconfined) {
                viewModel.subtitleNotification.collect { note ->
                    if (note != null) notifications.add(note)
                }
            }

            viewModel.selectExternalSubtitle(invalidSub)
            var attempts = 0
            while (notifications.none { it.contains("No se pudo") || it.contains("Error") } && attempts++ < 100) {
                delay(50)
                shadowOf(Looper.getMainLooper()).idle()
            }
            collectJob.cancel()

            assertNull(viewModel.selectedExternalSubtitle.value)
            assertTrue(
                "Notifications should contain error, but had: $notifications",
                notifications.any { it.contains("No se pudo") || it.contains("Error") }
            )
        }
    }

    @Test
    fun testRefreshTracks_canBeCalledFromBackgroundSafely() = runBlocking {
        withTimeout(15_000L) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = PlayerViewModel(app)

            withContext(Dispatchers.IO) {
                viewModel.refreshTracks()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(viewModel.subtitleTracks.value)
        }
    }
}



