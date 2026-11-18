/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * media3's play-time counter (PlaybackStatsListener), which gives each song's time heard to the
 * play counts and the history, kept from closing the app (#189, #192, #209, #211, #212).
 *
 * The counter checks its own bookkeeping as it goes and throws when the player's events reach it
 * in an order it does not expect: a song's session finished at a time before its last state began
 * (IllegalArgumentException in PlaybackStatsTracker.updatePlaybackState, 0.12.11 on four phones)
 * or a current session it no longer holds (NullPointerException in
 * DefaultPlaybackSessionManager.updateCurrentSession, 0.12.9). It runs inside the player's own
 * listener calls on the main thread, so that throw closed the app, music and all, over a count.
 * media3 1.8.0 catches nothing there.
 *
 * Every call the counter takes is passed on here inside a catch. When it throws, a new counter
 * takes its place: at most the song playing at that moment counts short, and the music goes on.
 * The calls it takes are the ones PlaybackStatsListener overrides; GuardedPlaybackStatsTest fails
 * when a newer media3 adds one this does not pass on.
 */
@OptIn(UnstableApi::class)
class GuardedPlaybackStats(
    private val callback: PlaybackStatsListener.Callback,
    private val fresh: (PlaybackStatsListener.Callback) -> AnalyticsListener = { PlaybackStatsListener(false, it) },
) : AnalyticsListener {

    private var counter: AnalyticsListener = fresh(callback)

    /** How many times the counter failed and was made again. */
    var restarts = 0
        private set

    private inline fun guarded(call: String, block: (AnalyticsListener) -> Unit) {
        try {
            block(counter)
        } catch (e: RuntimeException) {
            restarts++
            // A line for the first few, with the trace, so Report a problem shows what media3 tripped on.
            if (restarts <= LOGGED) Log.w(TAG, "media3's play-time counter failed in $call and starts again: the song playing may count short", e)
            counter = fresh(callback)
        }
    }

    override fun onEvents(player: Player, events: AnalyticsListener.Events) =
        guarded("onEvents") { it.onEvents(player, events) }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = guarded("onPositionDiscontinuity") { it.onPositionDiscontinuity(eventTime, oldPosition, newPosition, reason) }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
        guarded("onDroppedVideoFrames") { it.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs) }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) = guarded("onLoadError") { it.onLoadError(eventTime, loadEventInfo, mediaLoadData, error, wasCanceled) }

    override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) =
        guarded("onDrmSessionManagerError") { it.onDrmSessionManagerError(eventTime, error) }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) =
        guarded("onBandwidthEstimate") { it.onBandwidthEstimate(eventTime, totalLoadTimeMs, totalBytesLoaded, bitrateEstimate) }

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) =
        guarded("onDownstreamFormatChanged") { it.onDownstreamFormatChanged(eventTime, mediaLoadData) }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) =
        guarded("onVideoSizeChanged") { it.onVideoSizeChanged(eventTime, videoSize) }

    private companion object {
        const val TAG = "PlaybackStats"

        /** Failures written to the log with their trace; later ones are only counted. */
        const val LOGGED = 3
    }
}
