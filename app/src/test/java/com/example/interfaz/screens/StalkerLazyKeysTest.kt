package com.example.interfaz.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.db.PlaybackHistory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StalkerLazyKeysTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeNewestMovieKeysAreUniqueForDuplicateStalkerVodItems() {
        val stalkerMovies = listOf(
            IptvItem(name = "Matrix", group = "Acción", url = null, stalkerCmd = "/media/101.mkv"),
            IptvItem(name = "Matrix", group = "4K", url = null, stalkerCmd = "/media/202.mkv"),
            IptvItem(name = "Matrix", group = "4K", url = null, stalkerCmd = "/media/202.mkv")
        )

        val keys = stalkerMovies.mapIndexed { index, item -> homeNewestMovieKey(index, item) }
        assertEquals(stalkerMovies.size, keys.toSet().size)

        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyRow {
                    itemsIndexed(
                        stalkerMovies,
                        key = { index, movie -> homeNewestMovieKey(index, movie) }
                    ) { index, movie ->
                        Text("${movie.name} #$index")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Matrix #0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Matrix #1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Matrix #2").assertIsDisplayed()
    }

    @Test
    fun homeHistoryAndFavoriteKeysAreUniqueEvenWithDuplicates() {
        val history = listOf(
            PlaybackHistory(src = "", title = "Película A", position = 120L, duration = 600L, pct = 0.2f, type = "movie", ts = 1000L, playlistId = 1L),
            PlaybackHistory(src = "", title = "Película A", position = 240L, duration = 600L, pct = 0.4f, type = "movie", ts = 2000L, playlistId = 1L)
        )
        val favorites = listOf(
            FavoriteItem(id = "Dup", title = "Dup", type = "movie", url = "", addedAt = 1000L, playlistId = 1L),
            FavoriteItem(id = "Dup", title = "Dup", type = "movie", url = "", addedAt = 2000L, playlistId = 1L)
        )

        assertEquals(2, history.mapIndexed { i, h -> homeContinueWatchingKey(i, h) }.toSet().size)
        assertEquals(2, favorites.mapIndexed { i, f -> homeFavoriteKey(i, f) }.toSet().size)

        composeTestRule.setContent {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    history,
                    key = { index, hist -> homeContinueWatchingKey(index, hist) }
                ) { index, hist ->
                    Text("${hist.title} $index")
                }
                itemsIndexed(
                    favorites,
                    key = { index, fav -> homeFavoriteKey(index, fav) }
                ) { index, fav ->
                    Text("${fav.title} fav $index")
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Película A 0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Película A 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dup fav 0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dup fav 1").assertIsDisplayed()
    }

    @Test
    fun liveChannelKeysAreUniqueForDuplicateStalkerChannels() {
        val stalkerChannels = listOf(
            IptvItem(name = "Canal Deportes HD", group = "Deportes", url = null, stalkerCmd = "ffmpeg http://localhost/ch/10"),
            IptvItem(name = "Canal Deportes HD", group = "Deportes", url = null, stalkerCmd = "ffmpeg http://localhost/ch/11"),
            IptvItem(name = "Canal Deportes HD", group = "Deportes", url = null, stalkerCmd = "ffmpeg http://localhost/ch/11")
        )

        val keys = stalkerChannels.mapIndexed { index, ch -> liveChannelItemKey(index, ch) }
        assertEquals(stalkerChannels.size, keys.toSet().size)

        composeTestRule.setContent {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    stalkerChannels,
                    key = { index, ch -> liveChannelItemKey(index, ch) }
                ) { index, ch ->
                    Text("${ch.name} [$index]")
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Canal Deportes HD [0]").assertIsDisplayed()
        composeTestRule.onNodeWithText("Canal Deportes HD [1]").assertIsDisplayed()
        composeTestRule.onNodeWithText("Canal Deportes HD [2]").assertIsDisplayed()
    }
}
