package com.example.subtitles

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.interfaz.components.anchorSubtitleViewToViewport
import com.example.interfaz.components.applySubtitleStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlayerSubtitleViewportTest {

    @OptIn(UnstableApi::class)
    @Test
    fun testSubtitleViewRemainsWithinVisibleScreenInRellenarZoomMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playerView = LayoutInflater.from(context)
            .inflate(R.layout.player_view_texture, null) as PlayerView

        val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
        val subView = playerView.subtitleView
        assertNotNull(contentFrame)
        assertNotNull(subView)

        // Simulate a 20:9 landscape screen (2400x1080) playing a 16:9 video in "Rellenar" (RESIZE_MODE_ZOOM)
        val screenWidth = 2400
        val screenHeight = 1080
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        contentFrame.setAspectRatio(16f / 9f)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY)

        // Before anchoring, AspectRatioFrameLayout stretches beyond screenHeight (2400 * 9 / 16 = 1350 > 1080)
        playerView.measure(widthSpec, heightSpec)
        playerView.layout(0, 0, screenWidth, screenHeight)
        assertTrue(
            "Expected zoomed contentFrame (${contentFrame.height}) to exceed screenHeight ($screenHeight)",
            contentFrame.height > screenHeight
        )
        assertTrue(
            "Expected unanchored subtitleView (${subView!!.bottom}) to clip past screenHeight ($screenHeight)",
            subView.bottom > screenHeight
        )

        // Apply our fix
        applySubtitleStyles(playerView, "Blanco", false, "Normal")
        anchorSubtitleViewToViewport(playerView) // idempotent

        playerView.measure(widthSpec, heightSpec)
        playerView.layout(0, 0, screenWidth, screenHeight)

        // Video stays zoomed to fill screen, while subtitleView is anchored strictly to visible viewport
        assertTrue(contentFrame.height > screenHeight)
        assertSame(playerView, subView.parent)
        assertEquals(0, subView.top)
        assertEquals(screenWidth, subView.width)
        assertEquals(screenHeight, subView.height)
        assertEquals(screenHeight, subView.bottom)
        assertTrue(playerView.indexOfChild(subView) > playerView.indexOfChild(contentFrame))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun testSubtitleViewRemainsWithinVisibleScreenOnUltraWideMovieAndAllResizeModes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playerView = LayoutInflater.from(context)
            .inflate(R.layout.player_view_texture, null) as PlayerView
        val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
        val subView = playerView.subtitleView!!

        applySubtitleStyles(playerView, "Blanco", true, "Normal")

        val screenWidth = 1920
        val screenHeight = 1080
        contentFrame.setAspectRatio(2.39f)

        val resizeModes = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        )

        val widthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY)

        for (mode in resizeModes) {
            playerView.resizeMode = mode
            playerView.measure(widthSpec, heightSpec)
            playerView.layout(0, 0, screenWidth, screenHeight)

            assertEquals("Mode $mode width", screenWidth, subView.width)
            assertEquals("Mode $mode height", screenHeight, subView.height)
            assertEquals("Mode $mode top", 0, subView.top)
            assertEquals("Mode $mode bottom", screenHeight, subView.bottom)
        }
    }
}
