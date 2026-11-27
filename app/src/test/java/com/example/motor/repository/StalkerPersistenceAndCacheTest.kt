package com.example.motor.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.motor.config.JsonUtils
import com.example.motor.db.IptvItem
import com.example.motor.parser.PortalCompatibilityState
import com.example.motor.parser.StalkerAuthMode
import com.example.motor.parser.StalkerDeviceModel
import com.example.motor.parser.StalkerProfileVariant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StalkerPersistenceAndCacheTest {

    private lateinit var app: Application
    private lateinit var sessionRepo: PortalSessionRepository
    private lateinit var cacheRepo: MediaCacheRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        sessionRepo = PortalSessionRepository.getInstance(app)
        cacheRepo = MediaCacheRepository.getInstance(app)
        app.getSharedPreferences("stalker_compat_states", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        sessionRepo.clearInMemoryCompatCacheForTests()
    }

    @Test
    fun compatStatePersistsAcrossInMemoryResetAndHandlesCorruptEntries() {
        val state = PortalCompatibilityState(
            playlistId = 4242L,
            host = "http://portal.example.net:8080/c/",
            resolvedPortalUrl = "http://portal.example.net:8080/stalker_portal/server/load.php",
            effectiveScheme = "http",
            deviceModel = StalkerDeviceModel.MAG254_COHERENT,
            profileVariant = StalkerProfileVariant.SIMPLIFIED_V2,
            authMode = StalkerAuthMode.COOKIE_ONLY,
            preferredTvCreateLinkVariant = "ffmpeg_cmd",
            lastDiscoveredAt = 1_700_000_123L
        )

        sessionRepo.setStalkerCompatState("4242", state)

        // Simulate app restart by clearing the in-memory ConcurrentHashMap
        sessionRepo.clearInMemoryCompatCacheForTests()

        val restored = sessionRepo.getStalkerCompatState("4242")
        assertNotNull(restored)
        assertEquals(state, restored)

        // Clearing removes from both RAM and SharedPreferences
        sessionRepo.clearStalkerCompatState("4242")
        sessionRepo.clearInMemoryCompatCacheForTests()
        assertNull(sessionRepo.getStalkerCompatState("4242"))

        // Corrupt JSON entry returns null cleanly without throwing
        app.getSharedPreferences("stalker_compat_states", Context.MODE_PRIVATE)
            .edit()
            .putString("9999", "{not valid json")
            .commit()
        assertNull(sessionRepo.getStalkerCompatState("9999"))
        assertNull(
            app.getSharedPreferences("stalker_compat_states", Context.MODE_PRIVATE)
                .getString("9999", null)
        )
    }

    @Test
    fun concurrentSaveCategoryCacheWritesRemainAtomicAndValid() = runBlocking {
        val playlistId = 7701L
        cacheRepo.clearCategoryCache(playlistId)

        val jobs = (1..16).map { batchIdx ->
            async {
                val items = (1..25).map { itemIdx ->
                    IptvItem(
                        name = "Película ${batchIdx}_$itemIdx",
                        group = "Acción",
                        stalkerCmd = "ffmpeg http://vod.test/$batchIdx/$itemIdx.mkv"
                    )
                }
                cacheRepo.saveCategoryCache(
                    playlistId = playlistId,
                    type = "movie",
                    data = mapOf("Acción" to items)
                )
            }
        }
        jobs.awaitAll()

        val diskFile = cacheRepo.getCacheFile(playlistId, "movie")
        assertTrue(diskFile.exists())
        val rawJson = diskFile.readText()
        val parsed = JsonUtils.iptvItemsMapFromJson(rawJson)
        assertEquals(25, parsed["Acción"]?.size)

        // Ensure no leftover temporary swap files exist on disk
        val leftoverTmpFiles = diskFile.parentFile
            ?.listFiles { _, name -> name.startsWith(diskFile.name) && name.contains(".tmp") }
            ?: emptyArray()
        assertEquals(0, leftoverTmpFiles.size)

        cacheRepo.clearCategoryCache(playlistId)
    }

    @Test
    fun throttledCategoryCacheSaverCoalescesBurstsAndSerializesFinalSave() = runBlocking {
        val fakeClock = AtomicLong(1_000L)
        val activeSaves = AtomicInteger(0)
        val maxConcurrentSaves = AtomicInteger(0)
        val savedSnapshots = CopyOnWriteArrayList<Pair<String, Int>>()

        val saver = ThrottledCategoryCacheSaver(
            minIntervalMs = 2_500L,
            clockMs = { fakeClock.get() },
            onPersist = { type, grouped ->
                val active = activeSaves.incrementAndGet()
                maxConcurrentSaves.updateAndGet { prev -> maxOf(prev, active) }
                try {
                    delay(40)
                    val total = grouped.values.sumOf { it.size }
                    savedSnapshots.add(type to total)
                } finally {
                    activeSaves.decrementAndGet()
                }
            }
        )

        val batch1 = (1..10).map { IptvItem(name = "M$it", group = "Drama") }
        val batch2 = (1..30).map { IptvItem(name = "M$it", group = "Drama") }
        val batch3 = (1..60).map { IptvItem(name = "M$it", group = "Drama") }
        val finalBatch = (1..120).map { IptvItem(name = "M$it", group = "Drama") }

        // First partial triggers immediately; rapid bursts within 2500ms must be throttled/skipped
        saver.submitPartial(this, "movie", batch1) { true }
        fakeClock.set(1_200L)
        saver.submitPartial(this, "movie", batch2) { true }
        fakeClock.set(1_500L)
        saver.submitPartial(this, "movie", batch3) { true }

        // Advance clock past throttle window so a newer partial can run
        delay(80)
        fakeClock.set(5_000L)
        saver.submitPartial(this, "movie", batch3) { true }

        // Final save immediately follows without waiting manually — saveFinal must join partial & write 120 last
        saver.saveFinal("movie", finalBatch) { true }

        assertEquals(1, maxConcurrentSaves.get())
        assertTrue(savedSnapshots.size <= 3)
        assertEquals("movie" to 120, savedSnapshots.last())

        // Cancelled generation check prevents stale partial or final writes
        val beforeCancelCount = savedSnapshots.size
        saver.submitPartial(this, "series", batch1) { false }
        saver.saveFinal("series", finalBatch) { false }
        delay(20)
        assertEquals(beforeCancelCount, savedSnapshots.size)
        assertFalse(savedSnapshots.any { it.first == "series" })
    }
}
