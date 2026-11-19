/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #189, #192, #211, #212 (0.12.11) and #209 (0.12.9): media3's play-time counter threw on its own
 * bookkeeping inside the player's listener calls and the app closed. The counter's throw now stops
 * at the guard, which makes a new counter, and every call the counter takes still reaches it.
 */
class GuardedPlaybackStatsTest {

    private fun timeAt(ms: Long) = AnalyticsListener.EventTime(ms, Timeline.EMPTY, 0, null, 0L, Timeline.EMPTY, 0, null, 0L, 0L)
    private val time = timeAt(0L)

    /** A counter that records its calls, and throws as media3's did when [throws] is set. */
    private class Counter(val throws: Boolean) : AnalyticsListener {
        val calls = mutableListOf<String>()
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            calls += "dropped $droppedFrames"
            if (throws) throw IllegalArgumentException()
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            calls += "size ${videoSize.width}"
            if (throws) throw NullPointerException()
        }
    }

    @Test
    fun `media3's counter throws when a song's session is finished at a time before it began`() {
        // What #189, #192, #211 and #212 threw, PlaybackStatsTracker.updatePlaybackState's check,
        // made here directly on media3 1.8.0's counter: it takes a batch of the player's events in
        // the order of their kinds, not of their times, and can finish a session with the time of
        // an event that came before the session's last state began. A media3 that no longer throws
        // here fails this test: the guard may then go.
        val counter = PlaybackStatsListener(false, null)
        counter.onSessionCreated(timeAt(2_000L), "song")
        assertThrows(IllegalArgumentException::class.java) { counter.onSessionFinished(timeAt(1_000L), "song", false) }
    }

    @Test
    fun `a counter that throws is made again and the throw goes no further`() {
        val made = mutableListOf<Counter>()
        // The first counter throws, as media3's did; the ones after it do not.
        val guard = GuardedPlaybackStats({ _, _ -> }) { Counter(throws = made.isEmpty()).also { made += it } }

        guard.onDroppedVideoFrames(time, 3, 10L)
        assertEquals("the throw made a new counter", 2, made.size)
        assertEquals(1, guard.restarts)

        guard.onDroppedVideoFrames(time, 4, 10L)
        guard.onVideoSizeChanged(time, VideoSize(640, 360))
        assertEquals("the calls after it reach the new counter", listOf("dropped 4", "size 640"), made[1].calls)
        assertEquals(listOf("dropped 3"), made[0].calls)
        assertEquals(1, guard.restarts)
    }

    @Test
    fun `each new counter reports to the same place`() {
        val callbacks = mutableListOf<PlaybackStatsListener.Callback>()
        val ours = PlaybackStatsListener.Callback { _, _ -> }
        val guard = GuardedPlaybackStats(ours) { callback -> callbacks += callback; Counter(throws = true) }
        guard.onDroppedVideoFrames(time, 1, 1L)
        guard.onVideoSizeChanged(time, VideoSize(1, 1))
        assertEquals(3, callbacks.size)
        assertTrue("every counter reports the plays to the service", callbacks.all { it === ours })
    }

    @Test
    fun `every call media3's counter takes is passed on`() {
        // The calls PlaybackStatsListener overrides: a newer media3 that adds one fails here, or
        // the counter would stop hearing it and the play counts would drift without a word.
        val listener = AnalyticsListener::class.java
        val taken = PlaybackStatsListener::class.java.declaredMethods
            .filter { m -> runCatching { listener.getMethod(m.name, *m.parameterTypes) }.isSuccess }
        assertTrue("media3's counter takes calls of the player's listener", taken.size >= 8)
        val guard = GuardedPlaybackStats::class.java
        val missing = taken.filter { m -> runCatching { guard.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure }
            .map { it.name }
        assertTrue("not passed on: $missing", missing.isEmpty())
    }

    @Test
    fun `the player counts through the guard`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("addAnalyticsListener(GuardedPlaybackStats(this@MusicService))" in service)
        assertTrue("media3's counter is never added bare", "addAnalyticsListener(PlaybackStatsListener(" !in service)
    }
}
