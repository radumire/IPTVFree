package com.example

import com.example.interfaz.THEMES_LIST
import com.example.interfaz.getFailedChannelColors
import com.example.interfaz.viewmodel.LiveTvViewModel
import com.example.motor.db.IptvItem
import org.junit.Assert.*
import org.junit.Test

class LiveTvChannelCheckTest {

    @Test
    fun testChannelCheckKeyDeterminism() {
        val ch1 = IptvItem(
            name = "La 1 HD",
            group = "Generalistas",
            url = "http://stream.example.com/live/1.m3u8",
            stalkerCmd = null
        )
        val ch2 = IptvItem(
            name = "La 1 HD",
            group = "Generalistas",
            url = "http://stream.example.com/live/1.m3u8",
            stalkerCmd = null
        )
        val chStalker = IptvItem(
            name = "Telecinco",
            group = "Generalistas",
            url = null,
            stalkerCmd = "auto /media/123.mpg"
        )

        assertEquals(LiveTvViewModel.getChannelCheckKey(ch1), LiveTvViewModel.getChannelCheckKey(ch2))
        assertNotEquals(LiveTvViewModel.getChannelCheckKey(ch1), LiveTvViewModel.getChannelCheckKey(chStalker))
        assertTrue(LiveTvViewModel.getChannelCheckKey(chStalker).contains("auto /media/123.mpg"))
    }

    @Test
    fun testVisibleCategoriesFilteringExcludesHiddenCategories() {
        val channels = mapOf(
            "Generalistas" to listOf(
                IptvItem(name = "La 1", group = "Generalistas", url = "http://s/1"),
                IptvItem(name = "La 2", group = "Generalistas", url = "http://s/2")
            ),
            "Deportes" to listOf(
                IptvItem(name = "Teledeporte", group = "Deportes", url = "http://s/3")
            ),
            "Adultos" to listOf(
                IptvItem(name = "Canal X", group = "Adultos", url = "http://s/4")
            )
        )

        val hiddenCategories = listOf("Adultos")
        val hiddenSet = hiddenCategories.toSet()

        val visibleChannels = channels
            .filterKeys { it !in hiddenSet }
            .values
            .flatten()
            .distinctBy { LiveTvViewModel.getChannelCheckKey(it) }

        assertEquals(3, visibleChannels.size)
        assertTrue(visibleChannels.none { it.group == "Adultos" })
        assertTrue(visibleChannels.any { it.name == "La 1" })
        assertTrue(visibleChannels.any { it.name == "Teledeporte" })
    }

    @Test
    fun testChannelCheckProgressDataModel() {
        val initial = LiveTvViewModel.ChannelCheckProgress()
        assertEquals(0, initial.total)
        assertEquals(0, initial.checked)
        assertEquals(0, initial.online)
        assertEquals(0, initial.failed)
        assertFalse(initial.isRunning)
        assertFalse(initial.isCompleted)

        val running = initial.copy(
            total = 100,
            checked = 45,
            online = 40,
            failed = 5,
            isRunning = true
        )
        assertEquals(100, running.total)
        assertEquals(45, running.checked)
        assertEquals(40, running.online)
        assertEquals(5, running.failed)
        assertTrue(running.isRunning)
        assertFalse(running.isCompleted)
    }

    @Test
    fun testFailedChannelThemeColorsDerivation() {
        val darkTheme = THEMES_LIST.first { it.id == "dark" }
        val failedColors = darkTheme.getFailedChannelColors()
        assertNotNull(failedColors)
        assertNotNull(failedColors.bg)
        assertNotNull(failedColors.border)
        assertNotNull(failedColors.title)
        assertNotNull(failedColors.subtitle)

        val purpleTheme = THEMES_LIST.first { it.id == "purple" }
        val purpleFailedColors = purpleTheme.getFailedChannelColors()
        assertNotEquals(failedColors.bg, purpleFailedColors.bg)
    }
}
