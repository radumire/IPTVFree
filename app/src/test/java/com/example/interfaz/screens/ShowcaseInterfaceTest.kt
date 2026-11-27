package com.example.interfaz.screens

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.Translator
import com.example.interfaz.PrecomputedMoviesData
import com.example.interfaz.PrecomputedSeriesData
import com.example.interfaz.THEMES_LIST
import com.example.interfaz.viewmodel.SettingsViewModel
import com.example.interfaz.viewmodel.SportsUiState
import com.example.motor.config.JsonUtils
import com.example.motor.config.PlayerSettings
import com.example.motor.db.FavoriteItem
import com.example.motor.db.IptvItem
import com.example.motor.db.UserProfile
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h720dp-land")
class ShowcaseInterfaceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun channel(name: String, group: String): IptvItem = IptvItem(
        name = name,
        url = "http://example.com/$name",
        logo = null,
        group = group
    )

    @Test
    fun homeInterfaceOptions_containsAllSixInterfacesWithLocalizedStrings() {
        assertEquals(6, HOME_INTERFACE_OPTIONS.size)
        val expectedIds = setOf("vivo_glass", "classic_tiles", "modern", "cinema_bar", "showcase", "live_stage")
        assertEquals(expectedIds, HOME_INTERFACE_OPTIONS.map { it.id }.toSet())

        for (lang in listOf("es", "en", "ro")) {
            for (opt in HOME_INTERFACE_OPTIONS) {
                val title = Translator.translate(opt.titleKey, lang)
                val desc = Translator.translate(opt.descKey, lang)
                assertNotEquals("Missing translation for ${opt.titleKey} ($lang)", opt.titleKey, title)
                assertNotEquals("Missing translation for ${opt.descKey} ($lang)", opt.descKey, desc)
            }
        }
    }

    @Test
    fun pickPreferredSpanishTvCategory_prefersSpanishVisibleCategory() {
        val channels = linkedMapOf(
            "UK | Entertainment" to listOf(channel("BBC One", "UK | Entertainment")),
            "FR | Cinema" to listOf(channel("Canal+", "FR | Cinema")),
            "ES | España TDT" to listOf(channel("La 1", "ES | España TDT"), channel("Antena 3", "ES | España TDT")),
            "USA | News" to listOf(channel("CNN", "USA | News"))
        )

        val picked = pickPreferredSpanishTvCategory(channels, hiddenTvCategories = emptyList())
        assertNotNull(picked)
        assertEquals("ES | España TDT", picked?.first)
        assertEquals(2, picked?.second?.size)
    }

    @Test
    fun pickPreferredSpanishTvCategory_respectsHiddenCategoriesAndFallsBack() {
        val channels = linkedMapOf(
            "España VIP" to listOf(channel("La 1", "España VIP")),
            "DE | Sports" to listOf(channel("Sky DE", "DE | Sports")),
            "Empty Cat" to emptyList()
        )

        val picked = pickPreferredSpanishTvCategory(channels, hiddenTvCategories = listOf("España VIP"))
        assertNotNull(picked)
        assertEquals("DE | Sports", picked?.first)

        val noneVisible = pickPreferredSpanishTvCategory(
            channels,
            hiddenTvCategories = listOf("España VIP", "DE | Sports")
        )
        assertNull(noneVisible)
    }

    @Test
    fun interfaceSelectorFullScreen_displaysAllFiveInterfacesAndSelectsShowcase() {
        var selected: String? = null
        var backPressed = false
        val theme = THEMES_LIST.first()

        composeTestRule.setContent {
            InterfaceSelectorFullScreen(
                selectedLayout = "vivo_glass",
                onSelectInterface = { selected = it },
                onBack = { backPressed = true },
                theme = theme,
                lang = "es"
            )
        }

        for (opt in HOME_INTERFACE_OPTIONS) {
            composeTestRule.onNodeWithTag("interface_option_${opt.id}").assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("ACTIVA").assertIsDisplayed()

        composeTestRule.onNodeWithTag("interface_option_showcase").performClick()
        assertEquals("showcase", selected)

        composeTestRule.onNodeWithTag("interface_selector_back").performClick()
        assertEquals(true, backPressed)
    }

    @Test
    fun showcaseHomeContent_rendersAllPanelsScrollsAndNavigatesToEveryCategory() {
        val navigatedRoutes = mutableListOf<String>()
        var clickedChannel: Triple<IptvItem, String, List<IptvItem>>? = null
        var clickedMovie: IptvItem? = null
        var clickedSeries: IptvItem? = null
        var clickedMatch: SportsEvent? = null
        var clickedFavorite: FavoriteItem? = null

        val spanishChannels = (1..8).map { idx ->
            channel(if (idx == 1) "La 1 HD" else "Canal ES $idx", "ES | España TDT")
        }
        val channels = linkedMapOf(
            "UK | News" to listOf(channel("BBC News", "UK | News")),
            "ES | España TDT" to spanishChannels
        )
        val movies = (1..6).map { idx ->
            IptvItem(name = if (idx == 1) "Dune: Part Two" else "Película $idx", url = "http://ex/m$idx.mp4", logo = null, group = "Ciencia Ficción")
        }
        val series = (1..5).map { idx ->
            IptvItem(name = if (idx == 1) "Severance" else "Serie $idx", url = "http://ex/s$idx.mp4", logo = null, group = "Drama", seriesId = "${100 + idx}")
        }
        val sportsEvents = (1..4).map { idx ->
            SportsEvent(
                id = "match-$idx",
                sport = "football",
                league = "LaLiga EA Sports",
                homeTeam = if (idx == 1) "Real Madrid" else "Equipo Local $idx",
                awayTeam = if (idx == 1) "Athletic" else "Equipo Visitante $idx",
                startTime = System.currentTimeMillis() + idx * 600_000L,
                status = if (idx == 1) SportsEventStatus.LIVE else SportsEventStatus.SCHEDULED
            )
        }
        val favorites = (1..5).map { idx ->
            FavoriteItem(
                id = "movie::fav$idx",
                title = if (idx == 1) "Dune Favorita" else "Favorita $idx",
                type = "movie",
                url = "http://ex/fav$idx.mp4",
                addedAt = 1000L + idx,
                playlistId = 1L
            )
        }

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 1280.dp, height = 720.dp)) {
                ShowcaseHomeContent(
                    settings = PlayerSettings(lang = "es", homeLayout = "showcase"),
                    liveChannels = channels,
                    newestMovies = movies,
                    precomputedMoviesData = PrecomputedMoviesData(
                        groups = listOf("Ciencia Ficción"),
                        allMovies = movies,
                        groupToMovies = mapOf("Ciencia Ficción" to movies),
                        categoryCounts = mapOf("Ciencia Ficción" to movies.size),
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
                        liveEvents = listOf(sportsEvents.first()),
                        todayEvents = sportsEvents.drop(1)
                    ),
                    favorites = favorites,
                    onChannelClick = { ch, catName, catChannels ->
                        clickedChannel = Triple(ch, catName, catChannels)
                    },
                    onMovieClick = { clickedMovie = it },
                    onSeriesClick = { clickedSeries = it },
                    onMatchClick = { clickedMatch = it },
                    onFavoriteClick = { clickedFavorite = it },
                    onNavigate = { navigatedRoutes.add(it) },
                    onExitRequest = {},
                    theme = THEMES_LIST.first()
                )
            }
        }
        composeTestRule.waitForIdle()

        // No app title on top bar, only action buttons Listas / Perfiles / Ajustes
        composeTestRule.onNodeWithText("Radu IPTV").assertDoesNotExist()
        composeTestRule.onNodeWithText("Listas").assertIsDisplayed()
        composeTestRule.onNodeWithText("Perfiles").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ajustes").assertIsDisplayed()

        // Live TV category label must NOT be shown; channels, Movies, Series, Sports & VOD Favorites ARE visible
        composeTestRule.onNodeWithText("ES | España TDT").assertDoesNotExist()
        composeTestRule.onNodeWithText("La 1 HD").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Dune: Part Two", useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Severance", useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Real Madrid vs Athletic", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Dune Favorita", useUnmergedTree = true).onFirst().assertIsDisplayed()

        // Click every header / top button -> navigates to its screen
        val tagToExpectedRoute = listOf(
            "showcase_btn_playlists" to "playlists",
            "showcase_btn_accounts" to "accounts",
            "showcase_btn_settings" to "settings",
            "showcase_card_live_tv" to "live_tv",
            "showcase_card_movies" to "movies",
            "showcase_card_series" to "series",
            "showcase_card_sports" to "sports",
            "showcase_card_favorites" to "favorites"
        )
        for ((tag, route) in tagToExpectedRoute) {
            composeTestRule.onNodeWithTag(tag).performClick()
            assertEquals(route, navigatedRoutes.last())
        }
        val headerNavCount = navigatedRoutes.size

        // Scroll vertically in Live TV and horizontally in Movies, Series, Sports & Favorites,
        // then click scrolled items -> invokes item callbacks without triggering header navigation
        composeTestRule.onNodeWithTag("showcase_live_tv_list").performScrollToIndex(7)
        composeTestRule.onNodeWithTag("showcase_live_tv_item_7").assertIsDisplayed().performClick()
        assertEquals("Canal ES 8", clickedChannel?.first?.name)
        assertEquals("ES | España TDT", clickedChannel?.second)
        assertEquals(8, clickedChannel?.third?.size)

        composeTestRule.onNodeWithTag("showcase_card_movies_list").performScrollToIndex(5)
        composeTestRule.onNodeWithTag("showcase_card_movies_item_5").assertIsDisplayed().performClick()
        assertEquals("Película 6", clickedMovie?.name)

        composeTestRule.onNodeWithTag("showcase_card_series_list").performScrollToIndex(4)
        composeTestRule.onNodeWithTag("showcase_card_series_item_4").assertIsDisplayed().performClick()
        assertEquals("Serie 5", clickedSeries?.name)

        composeTestRule.onNodeWithTag("showcase_sports_list").performScrollToIndex(3)
        composeTestRule.onNodeWithTag("showcase_sports_item_3").assertIsDisplayed().performClick()
        assertEquals("match-4", clickedMatch?.id)

        composeTestRule.onNodeWithTag("showcase_favorites_list").performScrollToIndex(4)
        composeTestRule.onNodeWithTag("showcase_favorites_item_4").assertIsDisplayed().performClick()
        assertEquals("movie::fav5", clickedFavorite?.id)

        // Item clicks did not invoke header onNavigate fallback
        assertEquals(headerNavCount, navigatedRoutes.size)
    }

    @Test
    fun showcaseHomeContent_showsEmptyFavoritesBodyWhenOnlyLiveTvOrNoFavoritesExist() {
        val tvOnlyFavorites = listOf(
            FavoriteItem(
                id = "tv::la1",
                title = "Canal TV Favorito",
                type = "tv",
                url = "http://ex/tv.ts",
                addedAt = 1000L,
                playlistId = 1L
            )
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 960.dp, height = 540.dp)) {
                ShowcaseHomeContent(
                    settings = PlayerSettings(lang = "es", homeLayout = "showcase"),
                    liveChannels = emptyMap(),
                    newestMovies = emptyList(),
                    precomputedMoviesData = null,
                    precomputedSeriesData = null,
                    sportsUiState = SportsUiState(isLoading = false),
                    favorites = tvOnlyFavorites,
                    onNavigate = {},
                    onExitRequest = {},
                    theme = THEMES_LIST.first()
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("showcase_card_favorites").assertIsDisplayed()
        composeTestRule.onNodeWithTag("showcase_favorites_list").assertDoesNotExist()
        composeTestRule.onNodeWithText("Canal TV Favorito", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun settingsScreen_interfaceSidebarOpensSelectorUpdatesLayoutAndRestoresFocus() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sessionRepo = PortalSessionRepository.getInstance(app)
        sessionRepo.selectProfile(
            UserProfile(
                id = 99L,
                name = "Test TV",
                avatar = "avatar_plumber",
                colorIdx = 0,
                pin = "",
                playlistsJson = "[]",
                settingsJson = JsonUtils.settingsToJson(PlayerSettings(lang = "es", homeLayout = "vivo_glass")),
                themeId = "purple",
                customThemesJson = "{}"
            )
        )
        val settingsViewModel = SettingsViewModel(app)
        val fullScreenStates = mutableListOf<Boolean>()

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 1280.dp, height = 720.dp)) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    theme = THEMES_LIST.first(),
                    onFullScreenChange = { fullScreenStates.add(it) },
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Interfaz sits in the Settings left sidebar alongside General, Categorías, etc.
        composeTestRule.onNodeWithTag("settings_category_general").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_category_interface").assertIsDisplayed()

        // Clicking Interfaz opens the 5-interface full-screen selector
        composeTestRule.onNodeWithTag("settings_category_interface").performClick()
        composeTestRule.waitForIdle()

        assertTrue(fullScreenStates.contains(true))
        composeTestRule.onNodeWithTag("interface_option_showcase").assertIsDisplayed()

        // Selecting Showcase closes the selector, updates settings, and restores D-Pad focus to Interfaz
        composeTestRule.onNodeWithTag("interface_option_showcase").performClick()
        val deadline = System.currentTimeMillis() + 3000L
        while (settingsViewModel.settings.value.homeLayout != "showcase" && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()

        assertFalse(fullScreenStates.last())
        assertEquals("showcase", settingsViewModel.settings.value.homeLayout)
        composeTestRule.onNodeWithTag("settings_interface_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_category_interface").assertIsFocused()
    }
}

