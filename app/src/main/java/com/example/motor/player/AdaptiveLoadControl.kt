package com.example.motor.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Production-grade LoadControl for IPTV streams (Live TV, VOD Movies, and Series).
 * Dynamically selects between fast-zapping Live TV buffers and deep VOD buffers
 * while sharing a single underlying memory allocator.
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class AdaptiveLoadControl : LoadControl {

    @Volatile
    var isLiveStream: Boolean = true

    private val sharedAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private val liveControl: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(sharedAllocator)
        .setBufferDurationsMs(
            /* minBufferMs = */ 8000,
            /* maxBufferMs = */ 20000,
            /* bufferForPlaybackMs = */ 1000,
            /* bufferForPlaybackAfterRebufferMs = */ 3000
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val vodControl: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(sharedAllocator)
        .setBufferDurationsMs(
            /* minBufferMs = */ 20000,
            /* maxBufferMs = */ 60000,
            /* bufferForPlaybackMs = */ 2500,
            /* bufferForPlaybackAfterRebufferMs = */ 5000
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val activeControl: LoadControl
        get() = if (isLiveStream) liveControl else vodControl

    override fun onPrepared(playerId: PlayerId) {
        activeControl.onPrepared(playerId)
    }

    override fun onPrepared() {
        activeControl.onPrepared()
    }

    override fun onStopped(playerId: PlayerId) {
        activeControl.onStopped(playerId)
    }

    override fun onStopped() {
        activeControl.onStopped()
    }

    override fun onReleased(playerId: PlayerId) {
        activeControl.onReleased(playerId)
    }

    override fun onReleased() {
        activeControl.onReleased()
    }

    override fun onTracksSelected(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>
    ) {
        activeControl.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>
    ) {
        activeControl.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun getAllocator(): Allocator = sharedAllocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        activeControl.getBackBufferDurationUs(playerId)

    override fun getBackBufferDurationUs(): Long =
        activeControl.backBufferDurationUs

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        activeControl.retainBackBufferFromKeyframe(playerId)

    override fun retainBackBufferFromKeyframe(): Boolean =
        activeControl.retainBackBufferFromKeyframe()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        activeControl.shouldContinueLoading(parameters)

    override fun shouldContinueLoading(playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float): Boolean =
        activeControl.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        activeControl.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        activeControl.shouldStartPlayback(parameters)

    override fun shouldStartPlayback(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean =
        activeControl.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)

    override fun shouldStartPlayback(bufferedDurationUs: Long, playbackSpeed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        activeControl.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
}
