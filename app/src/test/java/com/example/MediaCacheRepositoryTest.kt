package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.motor.db.IptvItem
import com.example.motor.repository.MediaCacheRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaCacheRepositoryTest {

    private lateinit var app: Application
    private lateinit var repo: MediaCacheRepository

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        repo = MediaCacheRepository.getInstance(app)
        repo.clearAllCaches()
    }

    @Test
    fun testMemoryCacheLifecycle() = runBlocking {
        val playlistId = 999L
        val type = "tv"
        val sampleData = mapOf(
            "General" to listOf(
                IptvItem(name = "Channel 1", group = "General", url = "http://test.com/1"),
                IptvItem(name = "Channel 2", group = "General", url = "http://test.com/2")
            )
        )

        // Initially no memory cache
        assertFalse(repo.hasMemoryCache(playlistId, type))

        // Save data -> should be in memory cache
        repo.saveCategoryCache(playlistId, type, sampleData)
        assertTrue(repo.hasMemoryCache(playlistId, type))
        assertEquals(2, repo.getCategoryCount(playlistId, type))

        // Get data -> memory hit
        val cached = repo.getCategoryCache(playlistId, type)
        assertNotNull(cached.data)
        assertEquals(1, cached.data!!.size)
        assertEquals(2, cached.data!!["General"]?.size)

        // Clear cache -> memory cache removed
        repo.clearCategoryCache(playlistId)
        assertFalse(repo.hasMemoryCache(playlistId, type))
    }

    @Test
    fun testConcurrentGetCategoryCache() = runBlocking {
        val playlistId = 888L
        val type = "movie"
        val sampleData = mapOf(
            "Action" to listOf(
                IptvItem(name = "Movie 1", group = "Action", url = "http://test.com/m1")
            )
        )
        repo.saveCategoryCache(playlistId, type, sampleData)

        // Concurrent requests
        val deferred1 = async(Dispatchers.IO) { repo.getCategoryCache(playlistId, type) }
        val deferred2 = async(Dispatchers.IO) { repo.getCategoryCache(playlistId, type) }
        val deferred3 = async(Dispatchers.IO) { repo.getCategoryCache(playlistId, type) }

        val res1 = deferred1.await()
        val res2 = deferred2.await()
        val res3 = deferred3.await()

        assertEquals(res1.data, res2.data)
        assertEquals(res2.data, res3.data)
    }

    @Test
    fun testPlaylistSwitchCancelsAndFreesInactiveRam() = runBlocking {
        val playlistA = 101L
        val playlistB = 102L
        val type = "tv"

        val sampleDataA = mapOf("News" to listOf(IptvItem(name = "News A", group = "News", url = "http://a.com")))
        val sampleDataB = mapOf("News" to listOf(IptvItem(name = "News B", group = "News", url = "http://b.com")))

        repo.setActivePlaylist(playlistA)
        repo.saveCategoryCache(playlistA, type, sampleDataA)
        assertTrue(repo.hasMemoryCache(playlistA, type))

        // Switch to playlist B: RAM cache of playlist A must be immediately cleared!
        repo.setActivePlaylist(playlistB)
        repo.saveCategoryCache(playlistB, type, sampleDataB)

        assertFalse("Inactive playlist A must be freed from RAM", repo.hasMemoryCache(playlistA, type))
        assertTrue("Active playlist B must be in RAM", repo.hasMemoryCache(playlistB, type))
    }

    @Test
    fun testGetCategoryNamesStreaming() = runBlocking {
        val playlistId = 200L
        val type = "movie"
        val sampleData = mapOf(
            "Drama" to listOf(IptvItem(name = "Drama 1", group = "Drama", url = "http://d1.com")),
            "Accion" to listOf(IptvItem(name = "Accion 1", group = "Accion", url = "http://a1.com")),
            "Comedia" to listOf(IptvItem(name = "Comedia 1", group = "Comedia", url = "http://c1.com"))
        )
        repo.saveCategoryCache(playlistId, type, sampleData)

        val categories = repo.getCategoryNames(playlistId, type)
        assertEquals(listOf("Accion", "Comedia", "Drama"), categories)
    }

    @Test
    fun testGetNewestMoviesPreview() = runBlocking {
        val playlistId = 300L
        val type = "movie"
        val sampleData = mapOf(
            "Estrenos 2026" to listOf(
                IptvItem(name = "Movie 2026", group = "Estrenos 2026", year = 2026, url = "http://m2026.com"),
                IptvItem(name = "Movie 2024", group = "Estrenos 2026", year = 2024, url = "http://m2024.com")
            ),
            "Clasicos" to listOf(
                IptvItem(name = "Old Movie 1999", group = "Clasicos", year = 1999, url = "http://m1999.com")
            )
        )
        repo.saveCategoryCache(playlistId, type, sampleData)

        val preview = repo.getNewestMoviesPreview(playlistId, limit = 2)
        assertEquals(2, preview.size)
        assertEquals("Movie 2026", preview[0].name)
    }
}
