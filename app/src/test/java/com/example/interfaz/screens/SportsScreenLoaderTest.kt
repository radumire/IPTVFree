package com.example.interfaz.screens

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.interfaz.THEMES_LIST
import com.example.interfaz.components.PlayerChannelsOverlay
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.motor.config.JsonUtils
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.IptvItem
import com.example.motor.db.UserProfile
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.sports.broadcast.SportsBroadcastInfo
import com.example.motor.sports.broadcast.SportsTvBroadcastProvider
import com.example.motor.sports.matcher.ActiveTvChannelsProvider
import com.example.motor.sports.matcher.DefaultSportsChannelMatcher
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.remote.SportsRemoteDataSource
import com.example.motor.sports.repository.SportsRepository
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SportsScreenLoaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `SportsScreen shows and hides modal loading dialog during Stalker channel matching`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val event = SportsEvent(
            id = "evt-ui-1",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Real Madrid",
            awayTeam = "Barcelona",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.LIVE
        )
        val stalkerChannel = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> =
                if (sport == SportsSport.FOOTBALL) listOf(event) else emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = 100L
            override suspend fun getActiveTvChannels(): List<IptvItem> = listOf(stalkerChannel)
        }
        val fakeBroadcast = object : SportsTvBroadcastProvider {
            override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo =
                SportsBroadcastInfo(channels = listOf("DAZN LaLiga"))
        }

        val vm = SportsViewModel(
            application = app,
            repository = SportsRepository(fakeRemote),
            channelsProvider = fakeProvider,
            matcher = DefaultSportsChannelMatcher(),
            broadcastResolver = fakeBroadcast
        )

        val streamGate = CompletableDeferred<Pair<String, Map<String, String>>>()
        val resolverStarted = CompletableDeferred<Unit>()
        vm.streamResolverOverride = {
            resolverStarted.complete(Unit)
            streamGate.await()
        }

        var playedUrl: String? = null
        composeTestRule.setContent {
            SportsScreen(
                viewModel = vm,
                onPlay = { src, _, _, _, _, _, _ -> playedUrl = src },
                onBack = {},
                theme = THEMES_LIST.first(),
                lang = "es"
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sports_matching_loader_dialog").assertDoesNotExist()

        vm.matchAndPlayEvent(
            event = event,
            onPlay = { src, _, _, _, _, _, _ -> playedUrl = src },
            onNotice = {}
        )

        val deadline = System.currentTimeMillis() + 5000L
        while (!resolverStarted.isCompleted && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertTrue("Stream resolver should have started", resolverStarted.isCompleted)
        composeTestRule.waitForIdle()

        assertTrue(vm.uiState.value.isMatchingChannel)
        composeTestRule.onNodeWithTag("sports_matching_loader_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Buscando canal para el partido...").assertIsDisplayed()

        streamGate.complete("http://stream.real.example/live.ts" to emptyMap())

        val finishDeadline = System.currentTimeMillis() + 5000L
        while (playedUrl == null && System.currentTimeMillis() < finishDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        composeTestRule.waitForIdle()

        assertEquals("http://stream.real.example/live.ts", playedUrl)
        assertFalse(vm.uiState.value.isMatchingChannel)
        composeTestRule.onNodeWithTag("sports_matching_loader_dialog").assertDoesNotExist()
    }

    @Test
    fun `SportsScreen shows ParentalPinDialog for restricted channel and dismisses on Cancelar`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sessionRepo = PortalSessionRepository.getInstance(app)
        val playlistId = 901L
        val profile = UserProfile(
            id = 99L,
            name = "Parental UI",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(
                listOf(Playlist(id = playlistId, name = "Stalker UI", type = "stalker", host = "http://p/c/", mac = "00:1A:79:aa:bb:cc", active = true))
            ),
            settingsJson = JsonUtils.settingsToJson(
                PlayerSettings(parentalEnabled = true, parentalPin = "1234", parentalRating = "12+")
            ),
            themeId = "purple",
            customThemesJson = "{}"
        )
        sessionRepo.selectProfile(profile)

        val event = SportsEvent(
            id = "evt-ui-parental",
            sport = "football",
            league = "LaLiga",
            homeTeam = "Sevilla",
            awayTeam = "Betis",
            startTime = System.currentTimeMillis(),
            status = SportsEventStatus.LIVE
        )
        val restrictedChannel = IptvItem(
            name = "DAZN LaLiga +18",
            group = "ADULTOS",
            url = null,
            stalkerCmd = "ffrt http://p/18"
        )
        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> =
                if (sport == SportsSport.FOOTBALL) listOf(event) else emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = playlistId
            override suspend fun getActiveTvChannels(): List<IptvItem> = listOf(restrictedChannel)
        }
        val fakeBroadcast = object : SportsTvBroadcastProvider {
            override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo =
                SportsBroadcastInfo(channels = listOf("DAZN LaLiga"))
        }

        val vm = SportsViewModel(
            application = app,
            repository = SportsRepository(fakeRemote),
            channelsProvider = fakeProvider,
            matcher = DefaultSportsChannelMatcher(),
            broadcastResolver = fakeBroadcast
        )
        var played = false
        vm.streamResolverOverride = {
            "http://stream.real.example/18.ts" to emptyMap()
        }

        composeTestRule.setContent {
            SportsScreen(
                viewModel = vm,
                onPlay = { _, _, _, _, _, _, _ -> played = true },
                onBack = {},
                theme = THEMES_LIST.first(),
                lang = "es"
            )
        }
        composeTestRule.waitForIdle()

        vm.matchAndPlayEvent(
            event = event,
            onPlay = { _, _, _, _, _, _, _ -> played = true },
            onNotice = {}
        )

        val deadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.pendingParentalUnlock == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Control Parental").assertIsDisplayed()
        composeTestRule.onNodeWithText("Desbloquear").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancelar").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Control Parental").assertDoesNotExist()
        assertFalse(played)
    }

    @Test
    fun `SportsScreen renders LazyColumn events and duplicate-name candidates with stable keys and English UI`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val events = listOf(
            SportsEvent(
                id = "evt-1",
                sport = "football",
                league = "LaLiga",
                homeTeam = "Real Madrid",
                awayTeam = "Barcelona",
                startTime = System.currentTimeMillis(),
                status = SportsEventStatus.LIVE
            ),
            SportsEvent(
                id = "evt-2",
                sport = "football",
                league = "LaLiga",
                homeTeam = "Atlético Madrid",
                awayTeam = "Valencia",
                startTime = System.currentTimeMillis(),
                status = SportsEventStatus.LIVE
            ),
            SportsEvent(
                id = "evt-3",
                sport = "football",
                league = "Premier",
                homeTeam = "Arsenal",
                awayTeam = "Chelsea",
                startTime = System.currentTimeMillis() + 3_600_000L,
                status = SportsEventStatus.SCHEDULED
            )
        )
        val ch1 = IptvItem(name = "DAZN LaLiga HD", group = "ESPAÑA", url = null, stalkerCmd = "ffrt http://p/101")
        val ch2 = IptvItem(name = "DAZN LaLiga HD", group = "ESPAÑA BACKUP", url = null, stalkerCmd = "ffrt http://p/102")
        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> =
                events.filter { it.status == SportsEventStatus.LIVE }
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> =
                events.filter { it.status != SportsEventStatus.LIVE }
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = 500L
            override suspend fun getActiveTvChannels(): List<IptvItem> = listOf(ch1, ch2)
        }
        val fakeBroadcast = object : SportsTvBroadcastProvider {
            override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo =
                SportsBroadcastInfo(channels = listOf("DAZN LaLiga"))
        }

        val vm = SportsViewModel(
            application = app,
            repository = SportsRepository(fakeRemote),
            channelsProvider = fakeProvider,
            matcher = DefaultSportsChannelMatcher(),
            broadcastResolver = fakeBroadcast
        )

        composeTestRule.setContent {
            SportsScreen(
                viewModel = vm,
                onPlay = { _, _, _, _, _, _, _ -> },
                onBack = {},
                theme = THEMES_LIST.first(),
                lang = "en"
            )
        }
        composeTestRule.waitForIdle()

        vm.matchAndPlayEvent(events.first(), onPlay = { _, _, _, _, _, _, _ -> }, onNotice = {})
        val deadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.channelSelectionState == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Select channel").assertIsDisplayed()
        assertEquals(2, composeTestRule.onAllNodesWithText("DAZN LaLiga HD").fetchSemanticsNodes().size)
    }

    @Test
    fun `PlayerChannelsOverlay highlights only exact active channel when duplicate names exist`() {
        val chFirst = IptvItem(name = "DAZN 1", group = "ESPAÑA", url = "http://live.example/dazn1_a.ts")
        val chSecond = IptvItem(name = "DAZN 1", group = "PORTUGAL", url = "http://live.example/dazn1_b.ts")
        var selectedChannel: IptvItem? = null

        composeTestRule.setContent {
            Box(modifier = Modifier.size(800.dp, 600.dp)) {
                PlayerChannelsOverlay(
                    visible = true,
                    categoryName = "Deportes",
                    channels = listOf(chFirst, chSecond),
                    currentChannelName = "DAZN 1",
                    currentChannel = chSecond,
                    currentChannelUrl = chSecond.url,
                    onSelectChannel = { selectedChannel = it },
                    onDismiss = {},
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("player_overlay_active_dot_0", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("player_overlay_active_dot_1", useUnmergedTree = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("player_overlay_channel_0").performClick()
        assertEquals(chFirst, selectedChannel)
    }
}
