package com.example.interfaz.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.THEMES_LIST
import com.example.interfaz.viewmodel.SportsUiState
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h720dp-land")
class LiveStageInterfaceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun channel(name: String, group: String): IptvItem = IptvItem(
        name = name,
        url = "http://example.com/$name.ts",
        logo = null,
        group = group
    )

    @Test
    fun pickPreferredSpanishOrRomanianTvCategory_appliesSpanishThenRomanianThenFirstFallback() {
        val esAndRo = linkedMapOf(
            "UK | Entertainment" to listOf(channel("BBC One", "UK | Entertainment")),
            "RO | Romania HD" to listOf(channel("PRO TV", "RO | Romania HD")),
            "ES | España TDT" to listOf(channel("La 1 HD", "ES | España TDT"), channel("Antena 3", "ES | España TDT"))
        )
        val pickedEs = pickPreferredSpanishOrRomanianTvCategory(esAndRo, hiddenTvCategories = emptyList())
        assertNotNull(pickedEs)
        assertEquals("ES | España TDT", pickedEs?.first)

        // When Spanish is hidden or absent, Romanian must be chosen
        val pickedRo = pickPreferredSpanishOrRomanianTvCategory(esAndRo, hiddenTvCategories = listOf("ES | España TDT"))
        assertNotNull(pickedRo)
        assertEquals("RO | Romania HD", pickedRo?.first)

        // When neither Spanish nor Romanian exists, pick the first visible category
        val pickedFirst = pickPreferredSpanishOrRomanianTvCategory(
            esAndRo,
            hiddenTvCategories = listOf("ES | España TDT", "RO | Romania HD")
        )
        assertNotNull(pickedFirst)
        assertEquals("UK | Entertainment", pickedFirst?.first)

        // All hidden returns null
        assertNull(
            pickPreferredSpanishOrRomanianTvCategory(
                esAndRo,
                hiddenTvCategories = listOf("ES | España TDT", "RO | Romania HD", "UK | Entertainment")
            )
        )
    }

    @Test
    fun liveStageHomeContent_startsWithoutSelectedTabAndHandlesTwoTapNavigationAndTabs() {
        val navigatedRoutes = mutableListOf<String>()
        val selectedChannels = mutableListOf<IptvItem>()
        val selectedMovies = mutableListOf<IptvItem>()
        val selectedSeries = mutableListOf<IptvItem>()
        val selectedMatches = mutableListOf<SportsEvent>()
        val selectedFavorites = mutableListOf<FavoriteItem>()
        val selectedInterfaces = mutableListOf<String>()
        var fullscreenOpenedCount = 0

        val channels = (1..12).map { idx ->
            channel(if (idx == 1) "Antena 3 HD" else "Canal ES $idx", "ES | España")
        }
        val movies = (1..5).map { idx ->
            IptvItem(name = "Película Destacada $idx", url = "http://ex/m$idx.mp4", logo = null, group = "Acción")
        }
        val series = (1..5).map { idx ->
            IptvItem(name = "Serie Destacada $idx", url = "http://ex/s$idx.mp4", logo = null, group = "Drama", seriesId = "${200 + idx}")
        }
        val match = SportsEvent(
            id = "m-1",
            sport = "football",
            league = "Champions League",
            homeTeam = "FC Barcelona",
            awayTeam = "Inter",
            startTime = System.currentTimeMillis() + 600_000L,
            status = SportsEventStatus.LIVE
        )
        val favs = listOf(
            FavoriteItem(
                id = "movie::fav1",
                title = "Interstellar",
                type = "movie",
                url = "http://ex/interstellar.mp4",
                addedAt = 1000L,
                playlistId = 1L
            )
        )
        val playlist = Playlist(
            id = 1L,
            name = "Mi Lista IPTV",
            type = "xtream",
            host = "http://demo.tv:8080"
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 1280.dp, height = 720.dp)) {
                LiveStageHomeContent(
                    settings = PlayerSettings(lang = "es", homeLayout = "live_stage"),
                    activePlaylist = playlist,
                    isOnline = true,
                    totalLiveCount = 120,
                    totalMoviesCount = 450,
                    totalSeriesCount = 85,
                    categoryChannels = channels,
                    selectedPreviewChannel = channels.first(),
                    isChannelLocked = { false },
                    isResolvingPreview = false,
                    hasPreviewStream = false,
                    newestMovies = movies,
                    precomputedMoviesData = PrecomputedMoviesData(
                        groups = listOf("Acción"),
                        allMovies = movies,
                        groupToMovies = mapOf("Acción" to movies),
                        categoryCounts = mapOf("Acción" to movies.size),
                        totalMoviesCount = movies.size
                    ),
                    precomputedSeriesData = PrecomputedSeriesData(
                        groups = listOf("Drama"),
                        seriesBaseNameCache = emptyMap(),
                        categoryCounts = mapOf("Drama" to series.size),
                        allSeriesUnique = series,
                        groupToUniqueSeries = mapOf("Drama" to series),
                        allSeries = series
                    ),
                    sportsUiState = SportsUiState(
                        isLoading = false,
                        liveEvents = listOf(match),
                        todayEvents = emptyList()
                    ),
                    favorites = favs,
                    onSelectPreviewChannel = { selectedChannels.add(it) },
                    onOpenFullscreenPreview = { fullscreenOpenedCount++ },
                    onMatchClick = { selectedMatches.add(it) },
                    onMovieClick = { selectedMovies.add(it) },
                    onSeriesClick = { selectedSeries.add(it) },
                    onFavoriteClick = { selectedFavorites.add(it) },
                    onSelectInterface = { selectedInterfaces.add(it) },
                    onNavigate = { navigatedRoutes.add(it) },
                    onExitRequest = {},
                    theme = THEMES_LIST.first()
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        // No app title, bottom-right clock & symmetric bottom-left Profiles shown, NONE of the 7 top tabs or Profiles focused on launch
        composeTestRule.onNodeWithText("Radu IPTV").assertDoesNotExist()
        composeTestRule.onNodeWithText("Xuper Hydra").assertDoesNotExist()
        val clockNode = composeTestRule.onNodeWithTag("livestage_bottom_clock").assertIsDisplayed()
        val profilesNode = composeTestRule.onNodeWithTag("livestage_btn_profiles").assertIsDisplayed().assertIsNotFocused()
        composeTestRule.onNodeWithText("Perfiles", useUnmergedTree = true).assertIsDisplayed()

        val profilesBounds = profilesNode.getBoundsInRoot()
        val clockBounds = clockNode.getBoundsInRoot()
        assertEquals(clockBounds.right - clockBounds.left, profilesBounds.right - profilesBounds.left)
        assertEquals(clockBounds.bottom - clockBounds.top, profilesBounds.bottom - profilesBounds.top)
        assertTrue(profilesBounds.left < clockBounds.left)

        for (tab in LiveStageTab.entries) {
            composeTestRule.onNodeWithTag(tab.testTag).assertIsDisplayed().assertIsNotFocused()
        }

        // Clicking bottom-left Profiles button opens accounts screen
        profilesNode.performClick()
        assertEquals("accounts", navigatedRoutes.last())
        navigatedRoutes.clear()

        // TV channel list (all channels scrollable) & preview caption shown by default
        composeTestRule.onAllNodesWithText("Antena 3 HD", useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithTag("livestage_channel_list").performScrollToIndex(11)
        composeTestRule.onNodeWithTag("livestage_channel_item_11").assertIsDisplayed().performClick()
        assertEquals("Canal ES 12", selectedChannels.last().name)

        // Clicking preview opens fullscreen callback
        composeTestRule.onNodeWithTag("livestage_preview_box").performClick()
        assertEquals(1, fullscreenOpenedCount)

        // Point 1: 1st tap on TV selects button WITHOUT navigating; 2nd tap navigates to live_tv
        composeTestRule.onNodeWithTag("livestage_tab_tv").performClick()
        assertTrue(navigatedRoutes.isEmpty())
        composeTestRule.onNodeWithTag("livestage_tab_tv").performClick()
        assertEquals("live_tv", navigatedRoutes.last())

        // Deportes: 1st tap shows featured matches, match click invokes callback, 2nd tap opens sports
        composeTestRule.onNodeWithTag("livestage_tab_sports").performClick()
        composeTestRule.onNodeWithText("FC Barcelona vs Inter", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("livestage_sports_item_0").performClick()
        assertEquals("m-1", selectedMatches.last().id)
        composeTestRule.onNodeWithTag("livestage_tab_sports").performClick()
        assertEquals("sports", navigatedRoutes.last())

        // Favoritos: 1st tap shows favorite cards, click resolves favorite, 2nd tap opens favorites
        composeTestRule.onNodeWithTag("livestage_tab_favorites").performClick()
        composeTestRule.onNodeWithText("Interstellar", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("livestage_favorites_item_0").performClick()
        assertEquals("Interstellar", selectedFavorites.last().title)
        composeTestRule.onNodeWithTag("livestage_tab_favorites").performClick()
        assertEquals("favorites", navigatedRoutes.last())

        // Películas: 1st tap shows featured movies, click opens movie detail callback, 2nd tap opens movies
        composeTestRule.onNodeWithTag("livestage_tab_movies").performClick()
        composeTestRule.onNodeWithText("Película Destacada 1", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("livestage_movies_item_0").performClick()
        assertEquals("Película Destacada 1", selectedMovies.last().name)
        composeTestRule.onNodeWithTag("livestage_tab_movies").performClick()
        assertEquals("movies", navigatedRoutes.last())

        // Series: 1st tap shows featured series, click opens series detail callback, 2nd tap opens series
        composeTestRule.onNodeWithTag("livestage_tab_series").performClick()
        composeTestRule.onNodeWithText("Serie Destacada 1", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("livestage_series_item_0").performClick()
        assertEquals("Serie Destacada 1", selectedSeries.last().name)
        composeTestRule.onNodeWithTag("livestage_tab_series").performClick()
        assertEquals("series", navigatedRoutes.last())

        // Listas: 1st tap shows connected playlist card, 2nd tap opens playlists
        composeTestRule.onNodeWithTag("livestage_tab_playlists").performClick()
        composeTestRule.onNodeWithText("Mi Lista IPTV", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("livestage_tab_playlists").performClick()
        assertEquals("playlists", navigatedRoutes.last())

        // Ajustes: 1st tap shows all 6 interfaces inline, clicking switches interface, 2nd tap opens settings
        composeTestRule.onNodeWithTag("livestage_tab_settings").performClick()
        composeTestRule.onNodeWithTag("livestage_interface_item_showcase").assertIsDisplayed().performClick()
        assertEquals("showcase", selectedInterfaces.last())
        composeTestRule.onNodeWithTag("livestage_tab_settings").performClick()
        assertEquals("settings", navigatedRoutes.last())
    }

    @Test
    fun liveStageHomeContent_showsEmptyAreaWhenNoFavoritesExist() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 1280.dp, height = 720.dp)) {
                LiveStageHomeContent(
                    settings = PlayerSettings(lang = "es", homeLayout = "live_stage"),
                    activePlaylist = null,
                    isOnline = true,
                    totalLiveCount = 0,
                    totalMoviesCount = 0,
                    totalSeriesCount = 0,
                    categoryChannels = emptyList(),
                    selectedPreviewChannel = null,
                    isChannelLocked = { false },
                    isResolvingPreview = false,
                    hasPreviewStream = false,
                    newestMovies = emptyList(),
                    precomputedMoviesData = null,
                    precomputedSeriesData = null,
                    sportsUiState = SportsUiState(isLoading = false),
                    favorites = emptyList(),
                    onSelectPreviewChannel = {},
                    onOpenFullscreenPreview = {},
                    onMatchClick = {},
                    onMovieClick = {},
                    onSeriesClick = {},
                    onFavoriteClick = {},
                    onSelectInterface = {},
                    onNavigate = {},
                    onExitRequest = {},
                    theme = THEMES_LIST.first()
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("livestage_tab_favorites").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("livestage_favorites_list").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun liveStageHomeContent_remoteDpadFocusesTvFirstMovesToSportsAndOpensOnOk() {
        val navigatedRoutes = mutableListOf<String>()
        val match = SportsEvent(
            id = "m-live",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Real Madrid",
            awayTeam = "Sevilla",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.LIVE
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 1280.dp, height = 720.dp)) {
                LiveStageHomeContent(
                    settings = PlayerSettings(lang = "es", homeLayout = "live_stage"),
                    activePlaylist = null,
                    isOnline = true,
                    totalLiveCount = 1,
                    totalMoviesCount = 0,
                    totalSeriesCount = 0,
                    categoryChannels = listOf(channel("La 1 HD", "ES | TDT")),
                    selectedPreviewChannel = channel("La 1 HD", "ES | TDT"),
                    isChannelLocked = { false },
                    isResolvingPreview = false,
                    hasPreviewStream = false,
                    newestMovies = emptyList(),
                    precomputedMoviesData = null,
                    precomputedSeriesData = null,
                    sportsUiState = SportsUiState(isLoading = false, liveEvents = listOf(match)),
                    favorites = emptyList(),
                    onSelectPreviewChannel = {},
                    onOpenFullscreenPreview = {},
                    onMatchClick = {},
                    onMovieClick = {},
                    onSeriesClick = {},
                    onFavoriteClick = {},
                    onSelectInterface = {},
                    onNavigate = { navigatedRoutes.add(it) },
                    onExitRequest = {},
                    theme = THEMES_LIST.first()
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        // First D-Pad key wakes focus onto the TV tab
        composeTestRule.onNodeWithTag("livestage_startup_sentinel").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("livestage_tab_tv").assertIsFocused()

        // Moving right onto Deportes updates the content panel live to Sports
        composeTestRule.onNodeWithTag("livestage_tab_tv").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("livestage_tab_sports").assertIsFocused()
        composeTestRule.onNodeWithText("Real Madrid vs Sevilla", useUnmergedTree = true).assertIsDisplayed()

        // Pressing OK (DirectionCenter) on the focused Deportes tab navigates directly to sports
        composeTestRule.onNodeWithTag("livestage_tab_sports").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf("sports"), navigatedRoutes)
    }
}
