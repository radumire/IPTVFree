package com.example.motor.sports

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.interfaz.viewmodel.SportsViewModel
import com.example.motor.config.JsonUtils
import com.example.motor.config.PlayerSettings
import com.example.motor.config.Playlist
import com.example.motor.db.IptvItem
import com.example.motor.db.UserProfile
import com.example.motor.parser.NetworkError
import com.example.motor.repository.MediaCacheRepository
import com.example.motor.repository.PortalSessionRepository
import com.example.motor.sports.broadcast.SportsBroadcastInfo
import com.example.motor.sports.broadcast.SportsTvBroadcastProvider
import com.example.motor.sports.matcher.ActiveTvChannelsProvider
import com.example.motor.sports.matcher.DefaultActiveTvChannelsProvider
import com.example.motor.sports.matcher.DefaultSportsChannelMatcher
import com.example.motor.sports.model.SportsEvent
import com.example.motor.sports.model.SportsEventStatus
import com.example.motor.sports.model.SportsSport
import com.example.motor.sports.remote.SportsRemoteDataSource
import com.example.motor.sports.repository.SportsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SportsStalkerIntegrationTest {

    private lateinit var app: Application
    private lateinit var sessionRepo: PortalSessionRepository
    private lateinit var mediaCacheRepo: MediaCacheRepository
    private lateinit var provider: DefaultActiveTvChannelsProvider

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        sessionRepo = PortalSessionRepository.getInstance(app)
        mediaCacheRepo = MediaCacheRepository.getInstance(app)
        mediaCacheRepo.clearAllCaches()
        provider = DefaultActiveTvChannelsProvider(sessionRepo, mediaCacheRepo)
    }

    private fun createSportsViewModel(
        playlistId: Long?,
        channels: List<IptvItem>,
        broadcastChannels: List<String> = listOf("DAZN LaLiga")
    ): SportsViewModel {
        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = playlistId
            override suspend fun getActiveTvChannels(): List<IptvItem> = channels
        }
        val fakeBroadcast = object : SportsTvBroadcastProvider {
            override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo? {
                return if (broadcastChannels.isEmpty()) null else SportsBroadcastInfo(channels = broadcastChannels)
            }
        }
        return SportsViewModel(
            application = app,
            repository = SportsRepository(fakeRemote),
            channelsProvider = fakeProvider,
            matcher = DefaultSportsChannelMatcher(),
            broadcastResolver = fakeBroadcast
        )
    }

    private fun createSampleMatch(): SportsEvent = SportsEvent(
        id = "evt-100",
        sport = "football",
        league = "LaLiga",
        homeTeam = "Real Madrid",
        awayTeam = "Barcelona",
        startTime = System.currentTimeMillis(),
        status = SportsEventStatus.LIVE
    )

    private suspend fun awaitIdleCondition(condition: () -> Boolean) {
        withTimeout(5000L) {
            while (!condition()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(15L)
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `provider excludes CHANNEL_NO_PLAYABLE_CMD and preserves valid Stalker MAC channels`() = runBlocking {
        val playlistId = 701L
        val stalkerPlaylist = Playlist(
            id = playlistId,
            name = "Stalker MAC Test",
            type = "stalker",
            host = "http://portal.example/c/",
            mac = "00:1A:79:11:22:33",
            active = true
        )
        val profile = UserProfile(
            id = 77L,
            name = "Tester",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(listOf(stalkerPlaylist)),
            settingsJson = JsonUtils.settingsToJson(PlayerSettings()),
            themeId = "purple",
            customThemesJson = "{}"
        )

        sessionRepo.selectProfile(profile)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        val tvMap = mapOf(
            "DEPORTES" to listOf(
                IptvItem(
                    name = "DAZN LaLiga HD",
                    group = "DEPORTES",
                    url = null,
                    stalkerCmd = "ffrt http://portal.example/ch/101"
                ),
                IptvItem(
                    name = "M+ LaLiga Roto",
                    group = "DEPORTES",
                    url = null,
                    stalkerCmd = "CHANNEL_NO_PLAYABLE_CMD"
                ),
                IptvItem(
                    name = "M+ Deportes Blanco",
                    group = "DEPORTES",
                    url = null,
                    stalkerCmd = "  "
                ),
                IptvItem(
                    name = "M+ Champions Trim",
                    group = "DEPORTES",
                    url = "http://fallback.example/invalid",
                    stalkerCmd = " CHANNEL_NO_PLAYABLE_CMD "
                )
            )
        )
        mediaCacheRepo.setActivePlaylist(playlistId)
        mediaCacheRepo.saveCategoryCache(playlistId, "tv", tvMap)

        val activeChannels = provider.getActiveTvChannels()
        assertEquals(1, activeChannels.size)
        assertEquals("DAZN LaLiga HD", activeChannels.first().name)
        assertFalse(activeChannels.any { it.stalkerCmd?.trim() == "CHANNEL_NO_PLAYABLE_CMD" })
        assertTrue(activeChannels.all { !it.stalkerCmd.isNullOrBlank() || !it.url.isNullOrBlank() })
    }

    @Test
    fun `AUTO_PLAY Stalker auth failure reports real session error instead of channel not found`() = runBlocking {
        val stalkerChannel = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val vm = createSportsViewModel(playlistId = 10L, channels = listOf(stalkerChannel))
        vm.streamResolverOverride = {
            throw NetworkError.Auth("Sesión Stalker expirada o no autorizada")
        }

        var playedUrl: String? = null
        var notice: String? = null

        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { src, _, _, _, _, _, _ -> playedUrl = src },
            onNotice = { notice = it }
        )

        awaitIdleCondition { notice != null || playedUrl != null }

        assertNull(playedUrl)
        assertNotNull(notice)
        assertFalse("No debe decir Canal no encontrado", notice!!.contains("Canal no encontrado", ignoreCase = true))
        assertTrue(notice!!.contains("DAZN LaLiga HD"))
        assertTrue(notice!!.contains("Sesión del portal expirada"))
    }

    @Test
    fun `AUTO_PLAY Stalker blocked MAC error reports access denied`() = runBlocking {
        val stalkerChannel = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val vm = createSportsViewModel(playlistId = 11L, channels = listOf(stalkerChannel))
        vm.streamResolverOverride = {
            throw NetworkError.PortalBlockedMac("MAC bloqueada por el portal")
        }

        var notice: String? = null
        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = { notice = it }
        )

        awaitIdleCondition { notice != null }

        assertNotNull(notice)
        assertTrue(notice!!.contains("Acceso denegado"))
        assertTrue(notice!!.contains("DAZN LaLiga HD"))
    }

    @Test
    fun `SELECTION candidate resolution failure surfaces portal create_link error`() = runBlocking {
        val ch1 = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val ch2 = IptvItem(
            name = "DAZN LaLiga FHD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/102"
        )
        val vm = createSportsViewModel(playlistId = 12L, channels = listOf(ch1, ch2))
        vm.streamResolverOverride = {
            throw NetworkError.NotFound("No se pudo obtener el enlace de reproducción para este canal en Stalker")
        }

        var notice: String? = null
        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = { notice = it }
        )

        awaitIdleCondition { vm.uiState.value.channelSelectionState != null }
        assertNull(notice)

        val candidate = vm.uiState.value.channelSelectionState!!.candidates.first()
        vm.selectCandidateToPlay(
            candidate = candidate,
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = { notice = it }
        )

        awaitIdleCondition { notice != null }

        assertNotNull(notice)
        assertTrue(notice!!.contains("No se pudo abrir"))
        assertTrue(notice!!.contains("Stalker"))
        assertFalse(notice == "Canal no reproducible.")
    }

    @Test
    fun `AUTO_PLAY succeeds with valid Stalker resolved stream URL and headers`() = runBlocking {
        val stalkerChannel = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val vm = createSportsViewModel(playlistId = 13L, channels = listOf(stalkerChannel))
        vm.streamResolverOverride = {
            Pair("http://stream.real.example/play/token123.ts", mapOf("Cookie" to "mac=00:1A:79:11:22:33;"))
        }

        var playedUrl: String? = null
        var playedHeaders: Map<String, String> = emptyMap()
        var notice: String? = null

        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { src, _, _, _, _, _, headers ->
                playedUrl = src
                playedHeaders = headers
            },
            onNotice = { notice = it }
        )

        awaitIdleCondition { playedUrl != null || notice != null }

        assertNull(notice)
        assertEquals("http://stream.real.example/play/token123.ts", playedUrl)
        assertEquals("mac=00:1A:79:11:22:33;", playedHeaders["Cookie"])
    }

    @Test
    fun `provider deduplicates Stalker channels when synthetic Todos category exists`() = runBlocking {
        val playlistId = 702L
        val stalkerPlaylist = Playlist(
            id = playlistId,
            name = "Stalker Todos Test",
            type = "stalker",
            host = "http://portal.example/c/",
            mac = "00:1A:79:44:55:66",
            active = true
        )
        val profile = UserProfile(
            id = 78L,
            name = "Tester",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(listOf(stalkerPlaylist)),
            settingsJson = JsonUtils.settingsToJson(PlayerSettings()),
            themeId = "purple",
            customThemesJson = "{}"
        )

        sessionRepo.selectProfile(profile)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        val chPrimary = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val chBackup = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES BACKUP",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/202"
        )
        val chSameCmdOtherGroup = IptvItem(
            name = "DAZN LaLiga HD",
            group = "FÚTBOL",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )

        val tvMap = linkedMapOf(
            "Todos" to listOf(chPrimary, chBackup, chSameCmdOtherGroup),
            "DEPORTES" to listOf(chPrimary),
            "DEPORTES BACKUP" to listOf(chBackup),
            "FÚTBOL" to listOf(chSameCmdOtherGroup)
        )
        mediaCacheRepo.setActivePlaylist(playlistId)
        mediaCacheRepo.saveCategoryCache(playlistId, "tv", tvMap)

        val activeChannels = provider.getActiveTvChannels()
        assertEquals(2, activeChannels.size)
        assertEquals(
            setOf("ffrt http://portal.example/ch/101", "ffrt http://portal.example/ch/202"),
            activeChannels.map { it.stalkerCmd }.toSet()
        )
    }

    @Test
    fun `provider keeps channels if Todos is the only category in cache`() = runBlocking {
        val playlistId = 703L
        val stalkerPlaylist = Playlist(
            id = playlistId,
            name = "Stalker Todos Only",
            type = "stalker",
            host = "http://portal.example/c/",
            mac = "00:1A:79:77:88:99",
            active = true
        )
        val profile = UserProfile(
            id = 79L,
            name = "Tester",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(listOf(stalkerPlaylist)),
            settingsJson = JsonUtils.settingsToJson(PlayerSettings()),
            themeId = "purple",
            customThemesJson = "{}"
        )

        sessionRepo.selectProfile(profile)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        val ch = IptvItem(
            name = "M+ LaLiga HD",
            group = "Todos",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/303"
        )
        mediaCacheRepo.setActivePlaylist(playlistId)
        mediaCacheRepo.saveCategoryCache(playlistId, "tv", mapOf("Todos" to listOf(ch, ch)))

        val activeChannels = provider.getActiveTvChannels()
        assertEquals(1, activeChannels.size)
        assertEquals("M+ LaLiga HD", activeChannels.first().name)
    }

    @Test
    fun `matchAndPlayEvent ignores duplicate taps while Stalker lookup and create_link are in flight`() = runBlocking {
        val stalkerChannel = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val broadcastCalls = AtomicInteger(0)
        val streamCalls = AtomicInteger(0)
        val streamGate = CompletableDeferred<Pair<String, Map<String, String>>>()
        val resolverEntered = CompletableDeferred<Unit>()

        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = 14L
            override suspend fun getActiveTvChannels(): List<IptvItem> = listOf(stalkerChannel)
        }
        val fakeBroadcast = object : SportsTvBroadcastProvider {
            override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo {
                broadcastCalls.incrementAndGet()
                return SportsBroadcastInfo(channels = listOf("DAZN LaLiga"))
            }
        }
        val vm = SportsViewModel(
            application = app,
            repository = SportsRepository(fakeRemote),
            channelsProvider = fakeProvider,
            matcher = DefaultSportsChannelMatcher(),
            broadcastResolver = fakeBroadcast
        )
        vm.streamResolverOverride = {
            streamCalls.incrementAndGet()
            resolverEntered.complete(Unit)
            streamGate.await()
        }

        val playCount = AtomicInteger(0)
        var lastPlayedUrl: String? = null
        var notice: String? = null
        val match = createSampleMatch()

        vm.matchAndPlayEvent(
            event = match,
            onPlay = { src, _, _, _, _, _, _ ->
                playCount.incrementAndGet()
                lastPlayedUrl = src
            },
            onNotice = { notice = it }
        )
        // Segundo clic inmediato mientras isMatchingChannel está activo
        vm.matchAndPlayEvent(
            event = match,
            onPlay = { _, _, _, _, _, _, _ -> playCount.incrementAndGet() },
            onNotice = { notice = it }
        )

        assertTrue(vm.uiState.value.isMatchingChannel)
        awaitIdleCondition { resolverEntered.isCompleted }

        // Tercer clic mientras create_link sigue suspendido
        vm.matchAndPlayEvent(
            event = match,
            onPlay = { _, _, _, _, _, _, _ -> playCount.incrementAndGet() },
            onNotice = { notice = it }
        )
        assertTrue(vm.uiState.value.isMatchingChannel)
        assertEquals(1, broadcastCalls.get())
        assertEquals(1, streamCalls.get())

        streamGate.complete("http://stream.real.example/ch/101.ts" to emptyMap())
        awaitIdleCondition { playCount.get() > 0 && !vm.uiState.value.isMatchingChannel }

        assertNull(notice)
        assertEquals(1, playCount.get())
        assertEquals("http://stream.real.example/ch/101.ts", lastPlayedUrl)
        assertFalse(vm.uiState.value.isMatchingChannel)
    }

    @Test
    fun `selectCandidateToPlay ignores duplicate taps while Stalker create_link is in flight`() = runBlocking {
        val ch1 = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val ch2 = IptvItem(
            name = "DAZN LaLiga FHD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/102"
        )
        val vm = createSportsViewModel(playlistId = 15L, channels = listOf(ch1, ch2))

        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = {}
        )
        awaitIdleCondition { vm.uiState.value.channelSelectionState != null }

        val candidate = vm.uiState.value.channelSelectionState!!.candidates.first()
        val streamCalls = AtomicInteger(0)
        val resolverEntered = CompletableDeferred<Unit>()
        val streamGate = CompletableDeferred<Pair<String, Map<String, String>>>()
        vm.streamResolverOverride = {
            streamCalls.incrementAndGet()
            resolverEntered.complete(Unit)
            streamGate.await()
        }

        val playCount = AtomicInteger(0)
        vm.selectCandidateToPlay(
            candidate = candidate,
            onPlay = { _, _, _, _, _, _, _ -> playCount.incrementAndGet() },
            onNotice = {}
        )
        vm.selectCandidateToPlay(
            candidate = candidate,
            onPlay = { _, _, _, _, _, _, _ -> playCount.incrementAndGet() },
            onNotice = {}
        )

        assertTrue(vm.uiState.value.isMatchingChannel)
        awaitIdleCondition { resolverEntered.isCompleted }
        assertEquals(1, streamCalls.get())

        streamGate.complete("http://stream.real.example/ch/102.ts" to emptyMap())
        awaitIdleCondition { playCount.get() > 0 && !vm.uiState.value.isMatchingChannel }

        assertEquals(1, playCount.get())
        assertFalse(vm.uiState.value.isMatchingChannel)
    }

    @Test
    fun `matchAndPlayEvent aborts cleanly if active playlist switches mid-flight`() = runBlocking {
        val stalkerChannel = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        var currentPlaylistId = 20L
        val broadcastEntered = CompletableDeferred<Unit>()
        val broadcastGate = CompletableDeferred<SportsBroadcastInfo>()

        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = currentPlaylistId
            override suspend fun getActiveTvChannels(): List<IptvItem> = listOf(stalkerChannel)
        }
        val fakeBroadcast = object : SportsTvBroadcastProvider {
            override suspend fun resolveBroadcastInfo(event: SportsEvent): SportsBroadcastInfo {
                broadcastEntered.complete(Unit)
                return broadcastGate.await()
            }
        }
        val vm = SportsViewModel(
            application = app,
            repository = SportsRepository(fakeRemote),
            channelsProvider = fakeProvider,
            matcher = DefaultSportsChannelMatcher(),
            broadcastResolver = fakeBroadcast
        )

        var played = false
        var notice: String? = null
        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { _, _, _, _, _, _, _ -> played = true },
            onNotice = { notice = it }
        )

        awaitIdleCondition { broadcastEntered.isCompleted }
        currentPlaylistId = 99L
        broadcastGate.complete(SportsBroadcastInfo(channels = listOf("DAZN LaLiga")))

        awaitIdleCondition { notice != null && !vm.uiState.value.isMatchingChannel }

        assertFalse(played)
        assertNull(vm.uiState.value.channelSelectionState)
        assertFalse(vm.uiState.value.isMatchingChannel)
        assertEquals("La lista activa ha cambiado. Vuelve a pulsar Ver.", notice)
    }

    @Test
    fun `selectCandidateToPlay aborts cleanly if active playlist switches during create_link`() = runBlocking {
        val ch1 = IptvItem(
            name = "DAZN LaLiga HD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/101"
        )
        val ch2 = IptvItem(
            name = "DAZN LaLiga FHD",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/102"
        )
        var currentPlaylistId = 21L
        val fakeRemote = object : SportsRemoteDataSource {
            override fun isApiKeyConfigured(): Boolean = true
            override suspend fun getLiveEvents(sport: SportsSport): List<SportsEvent> = emptyList()
            override suspend fun getEventsByDate(sport: SportsSport, date: LocalDate): List<SportsEvent> = emptyList()
        }
        val fakeProvider = object : ActiveTvChannelsProvider {
            override fun getActivePlaylistId(): Long? = currentPlaylistId
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

        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = {}
        )
        awaitIdleCondition { vm.uiState.value.channelSelectionState != null }

        val candidate = vm.uiState.value.channelSelectionState!!.candidates.first()
        val resolverEntered = CompletableDeferred<Unit>()
        val streamGate = CompletableDeferred<Pair<String, Map<String, String>>>()
        vm.streamResolverOverride = {
            resolverEntered.complete(Unit)
            streamGate.await()
        }

        var played = false
        var notice: String? = null
        vm.selectCandidateToPlay(
            candidate = candidate,
            onPlay = { _, _, _, _, _, _, _ -> played = true },
            onNotice = { notice = it }
        )

        awaitIdleCondition { resolverEntered.isCompleted }
        currentPlaylistId = 88L
        streamGate.complete("http://stream.real.example/ch/101.ts" to emptyMap())

        awaitIdleCondition { notice != null && !vm.uiState.value.isMatchingChannel }

        assertFalse(played)
        assertFalse(vm.uiState.value.isMatchingChannel)
        assertEquals("La lista activa ha cambiado. Vuelve a pulsar Ver.", notice)
    }

    @Test
    fun `AUTO_PLAY blocks restricted Stalker channel before create_link until parental PIN unlock`() = runBlocking {
        val playlistId = 801L
        val profile = UserProfile(
            id = 91L,
            name = "Child Guard",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(
                listOf(
                    Playlist(
                        id = playlistId,
                        name = "Stalker Guard",
                        type = "stalker",
                        host = "http://portal.example/c/",
                        mac = "00:1A:79:99:00:11",
                        active = true
                    )
                )
            ),
            settingsJson = JsonUtils.settingsToJson(
                PlayerSettings(
                    parentalEnabled = true,
                    parentalPin = "2468",
                    parentalRating = "12+"
                )
            ),
            themeId = "purple",
            customThemesJson = "{}"
        )
        sessionRepo.selectProfile(profile)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        val restrictedChannel = IptvItem(
            name = "DAZN LaLiga 18+",
            group = "DEPORTES",
            url = null,
            stalkerCmd = "ffrt http://portal.example/ch/180"
        )
        val vm = createSportsViewModel(playlistId = playlistId, channels = listOf(restrictedChannel))
        val resolverCalls = AtomicInteger(0)
        vm.streamResolverOverride = {
            resolverCalls.incrementAndGet()
            "http://stream.real.example/ch/180.ts" to emptyMap()
        }

        var playedUrl: String? = null
        var notice: String? = null
        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { src, _, _, _, _, _, _ -> playedUrl = src },
            onNotice = { notice = it }
        )

        awaitIdleCondition { vm.uiState.value.pendingParentalUnlock != null && !vm.uiState.value.isMatchingChannel }

        assertNull(playedUrl)
        assertNull(notice)
        assertEquals(0, resolverCalls.get())
        assertEquals("DAZN LaLiga 18+", vm.uiState.value.pendingParentalUnlock?.candidate?.channel?.name)
        assertEquals("2468", vm.getExpectedParentalPin())
        assertFalse(vm.uiState.value.isParentalUnlocked)

        vm.confirmParentalUnlockAndPlay(
            onPlay = { src, _, _, _, _, _, _ -> playedUrl = src },
            onNotice = { notice = it }
        )
        awaitIdleCondition { playedUrl != null && !vm.uiState.value.isMatchingChannel }

        assertEquals(1, resolverCalls.get())
        assertEquals("http://stream.real.example/ch/180.ts", playedUrl)
        assertTrue(vm.uiState.value.isParentalUnlocked)
        assertNull(vm.uiState.value.pendingParentalUnlock)
    }

    @Test
    fun `SELECTION filters restricted peers from matchChannels and gates restricted candidate`() = runBlocking {
        val playlistId = 802L
        val profile = UserProfile(
            id = 92L,
            name = "Parent Filter",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(
                listOf(Playlist(id = playlistId, name = "Stalker Mix", type = "stalker", host = "http://p/c/", mac = "00:1A:79:12:34:56", active = true))
            ),
            settingsJson = JsonUtils.settingsToJson(
                PlayerSettings(parentalEnabled = true, parentalPin = "1111", parentalRating = "12+")
            ),
            themeId = "purple",
            customThemesJson = "{}"
        )
        sessionRepo.selectProfile(profile)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        val safeCh = IptvItem(name = "DAZN LaLiga HD", group = "DEPORTES", url = null, stalkerCmd = "ffrt http://p/1")
        val restrictedCh = IptvItem(name = "DAZN LaLiga FHD", group = "ADULTOS", url = null, stalkerCmd = "ffrt http://p/2")
        val vm = createSportsViewModel(playlistId = playlistId, channels = listOf(safeCh, restrictedCh))

        assertFalse(vm.isChannelParentalLocked(safeCh))
        assertTrue(vm.isChannelParentalLocked(restrictedCh))

        vm.matchAndPlayEvent(
            event = createSampleMatch(),
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = {}
        )
        awaitIdleCondition { vm.uiState.value.channelSelectionState != null }

        val resolverCalls = AtomicInteger(0)
        vm.streamResolverOverride = {
            resolverCalls.incrementAndGet()
            "http://stream.real.example/safe.ts" to emptyMap()
        }

        var playedPeers: List<IptvItem> = emptyList()
        var playedUrl: String? = null
        val safeCandidate = vm.uiState.value.channelSelectionState!!.candidates.first { it.channel.name == "DAZN LaLiga HD" }
        vm.selectCandidateToPlay(
            candidate = safeCandidate,
            onPlay = { src, _, _, _, peers, _, _ ->
                playedUrl = src
                playedPeers = peers
            },
            onNotice = {}
        )
        awaitIdleCondition { playedUrl != null && !vm.uiState.value.isMatchingChannel }

        assertEquals(1, resolverCalls.get())
        assertEquals(listOf("DAZN LaLiga HD"), playedPeers.map { it.name })

        // Ahora verificar que seleccionar el candidato restringido pide PIN antes de resolver el stream
        val restrictedCandidate = safeCandidate.copy(channel = restrictedCh)
        vm.selectCandidateToPlay(
            candidate = restrictedCandidate,
            onPlay = { _, _, _, _, _, _, _ -> },
            onNotice = {}
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, resolverCalls.get())
        assertEquals("DAZN LaLiga FHD", vm.uiState.value.pendingParentalUnlock?.candidate?.channel?.name)
    }

    @Test
    fun `switching profile resets Sports parental unlock session`() = runBlocking {
        val playlistId = 803L
        val profileA = UserProfile(
            id = 93L,
            name = "Profile A",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(
                listOf(Playlist(id = playlistId, name = "List A", type = "stalker", host = "http://p/c/", mac = "00:1A:79:11:11:11", active = true))
            ),
            settingsJson = JsonUtils.settingsToJson(PlayerSettings(parentalEnabled = true, parentalPin = "4321", parentalRating = "12+")),
            themeId = "purple",
            customThemesJson = "{}"
        )
        val profileB = profileA.copy(id = 94L, name = "Profile B")

        sessionRepo.selectProfile(profileA)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        val restrictedCh = IptvItem(name = "DAZN LaLiga +18", group = "DEPORTES", url = null, stalkerCmd = "ffrt http://p/18")
        val vm = createSportsViewModel(playlistId = playlistId, channels = listOf(restrictedCh))
        vm.streamResolverOverride = { "http://stream.real.example/18.ts" to emptyMap() }

        var playedUrl: String? = null
        vm.matchAndPlayEvent(createSampleMatch(), onPlay = { _, _, _, _, _, _, _ -> }, onNotice = {})
        awaitIdleCondition { vm.uiState.value.pendingParentalUnlock != null }
        vm.confirmParentalUnlockAndPlay(onPlay = { src, _, _, _, _, _, _ -> playedUrl = src }, onNotice = {})
        awaitIdleCondition { playedUrl != null }

        assertTrue(vm.uiState.value.isParentalUnlocked)
        assertFalse(vm.isChannelParentalLocked(restrictedCh))

        sessionRepo.selectProfile(profileB)
        awaitIdleCondition { !vm.uiState.value.isParentalUnlocked }

        assertFalse(vm.uiState.value.isParentalUnlocked)
        assertTrue(vm.isChannelParentalLocked(restrictedCh))
    }

    @Test
    fun `SportsViewModel localizes notices to profile lang and tracks lastPlayedChannel`() = runBlocking {
        val playlistId = 804L
        val profileEn = UserProfile(
            id = 95L,
            name = "English User",
            avatar = "avatar_1",
            colorIdx = 0,
            pin = "",
            playlistsJson = JsonUtils.playlistsToJson(
                listOf(Playlist(id = playlistId, name = "List EN", type = "stalker", host = "http://p/c/", mac = "00:1A:79:22:22:22", active = true))
            ),
            settingsJson = JsonUtils.settingsToJson(PlayerSettings(lang = "en")),
            themeId = "purple",
            customThemesJson = "{}"
        )
        sessionRepo.selectProfile(profileEn)
        withTimeout(3000L) {
            sessionRepo.activePlaylist.first { it?.id == playlistId }
        }

        // 1. No channels notice in English
        val vmEmpty = createSportsViewModel(playlistId = playlistId, channels = emptyList())
        var emptyNotice: String? = null
        vmEmpty.matchAndPlayEvent(createSampleMatch(), onPlay = { _, _, _, _, _, _, _ -> }, onNotice = { emptyNotice = it })
        awaitIdleCondition { emptyNotice != null }
        assertEquals("No channels available in the active playlist.", emptyNotice)

        // 2. No match notice in English
        val unrelated = IptvItem(name = "Canal Cocina", group = "VARIOS", url = null, stalkerCmd = "ffrt http://p/9")
        val vmNoMatch = createSportsViewModel(playlistId = playlistId, channels = listOf(unrelated))
        var noMatchNotice: String? = null
        vmNoMatch.matchAndPlayEvent(createSampleMatch(), onPlay = { _, _, _, _, _, _, _ -> }, onNotice = { noMatchNotice = it })
        awaitIdleCondition { noMatchNotice != null }
        assertEquals("Channel not found in the active playlist.", noMatchNotice)

        // 3. lastPlayedChannel is updated on AUTO_PLAY
        val matchChannel = IptvItem(name = "DAZN LaLiga HD", group = "DEPORTES", url = null, stalkerCmd = "ffrt http://p/100")
        val vmPlay = createSportsViewModel(playlistId = playlistId, channels = listOf(matchChannel))
        vmPlay.streamResolverOverride = { "http://stream.real.example/100.ts" to emptyMap() }
        var playedUrl: String? = null
        vmPlay.matchAndPlayEvent(createSampleMatch(), onPlay = { src, _, _, _, _, _, _ -> playedUrl = src }, onNotice = {})
        awaitIdleCondition { playedUrl != null }
        assertEquals(matchChannel, vmPlay.lastPlayedChannel.value)
    }
}



