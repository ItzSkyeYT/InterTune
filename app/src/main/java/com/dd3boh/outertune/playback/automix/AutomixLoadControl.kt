/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * The player's load control with one thing more: while AutoMix asks for it ([ahead]), it loads on
 * where the control would rest, never past [AHEAD_CAP_US] ahead, its own five minutes.
 *
 * The control reads a song in one burst up to five minutes ahead and rests until 30 s are left,
 * so a song a little longer than that rests half a second short of its end, with nothing of the
 * next one loaded, until 30 s before its end. A change held earlier than Auto's usual window
 * needs both before its end can be held, so AutoMix asks for them for that change alone; Auto's
 * own changes, near the end, never ask, and the player loads exactly as before.
 *
 * Every call the player of media3 1.8 makes goes through to the control, which is asked each time,
 * so its own state moves as it would without this.
 */
@UnstableApi
class AutomixLoadControl(private val inner: LoadControl) : LoadControl {

    /** Set on the main thread by AutoMix's controller, read on the player's. */
    @Volatile
    var ahead = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val own = inner.shouldContinueLoading(parameters)
        return own || (ahead && parameters.bufferedDurationUs < AHEAD_CAP_US)
    }

    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        inner.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun getAllocator(): Allocator = inner.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = inner.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = inner.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        inner.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        /** No further ahead than the player's own maximum, five minutes. */
        const val AHEAD_CAP_US = 300_000_000L
    }
}
