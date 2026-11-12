/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's load control with AutoMix's one addition: on its own word it loads on past the
 * control's rest, never past its five minutes ahead, for a change held far from the end. Asked
 * for nothing, it is the control it wraps, answer for answer and call for call. And the word is
 * only given for such a change, never for Auto's usual one near the end.
 */
class AutomixLoadControlTest {

    /** A control that answers what it is told and writes down what it was asked. */
    private class Inner : LoadControl {
        var answer = false
        val calls = ArrayList<String>()
        private val allocator = DefaultAllocator(true, 65_536)
        override fun getAllocator(): Allocator = allocator.also { calls += "allocator" }
        override fun onPrepared(playerId: PlayerId) { calls += "prepared" }
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) { calls += "tracks" }
        override fun onStopped(playerId: PlayerId) { calls += "stopped" }
        override fun onReleased(playerId: PlayerId) { calls += "released" }
        override fun getBackBufferDurationUs(playerId: PlayerId): Long = 7L.also { calls += "back" }
        override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = true.also { calls += "keyframe" }
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = answer.also { calls += "load" }
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = true.also { calls += "start" }
    }

    private fun parameters(bufferedS: Double) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()), 0L, (bufferedS * 1_000_000).toLong(), 1f, true, false, 0L, 0L,
    )

    @Test
    fun `asked for nothing it answers as the control it wraps`() {
        val inner = Inner()
        val control = AutomixLoadControl(inner)
        for (answer in listOf(true, false)) for (buffered in listOf(0.5, 105.1, 299.0, 301.0)) {
            inner.answer = answer
            assertEquals(answer, control.shouldContinueLoading(parameters(buffered)))
        }
        // Every call goes through, in the variants the player of media3 1.8 makes.
        inner.calls.clear()
        control.onPrepared(PlayerId.UNSET)
        control.onTracksSelected(parameters(0.0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
        control.allocator
        assertEquals(7L, control.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue(control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        assertTrue(control.shouldStartPlayback(parameters(1.0)))
        control.onStopped(PlayerId.UNSET)
        control.onReleased(PlayerId.UNSET)
        assertEquals(listOf("prepared", "tracks", "allocator", "back", "keyframe", "start", "stopped", "released"), inner.calls)
    }

    @Test
    fun `asked to load ahead it loads on past the control's rest, never past five minutes`() {
        val inner = Inner()
        val control = AutomixLoadControl(inner)
        control.ahead = true
        inner.answer = false
        // The Pixel's case: A loaded to 301.2 s of 301.7, 105.1 s ahead, the control resting till 30 s are left.
        assertTrue(control.shouldContinueLoading(parameters(105.1)))
        assertFalse(control.shouldContinueLoading(parameters(300.0)))
        // The control is still asked every time, so its own state moves as it would.
        inner.calls.clear()
        control.shouldContinueLoading(parameters(50.0))
        assertEquals(listOf("load"), inner.calls)
        control.ahead = false
        assertFalse(control.shouldContinueLoading(parameters(105.1)))
    }

    @Test
    fun `only a change held earlier than Auto's window asks, and only till the songs are loaded`() {
        val far = 106_000L
        // Auto's usual change near the end: never.
        for (left in listOf(130_000L, 120_000L, 100_000L, 40_000L, 30_000L)) {
            assertFalse(AutomixSchedule.loadAhead(AutomixSchedule.ARM_FROM_LEFT_MS, left, loaded = false, armRequested = false))
        }
        // A far change: from where the next song's opening is read to where its end is held.
        assertFalse(AutomixSchedule.loadAhead(far, AutomixSchedule.READ_LEFT_MS + 1, loaded = false, armRequested = false))
        assertTrue(AutomixSchedule.loadAhead(far, AutomixSchedule.READ_LEFT_MS, loaded = false, armRequested = false))
        assertTrue(AutomixSchedule.loadAhead(far, 105_100L, loaded = false, armRequested = false))
        assertFalse(AutomixSchedule.loadAhead(far, 105_100L, loaded = true, armRequested = false))
        assertFalse(AutomixSchedule.loadAhead(far, 105_100L, loaded = false, armRequested = true))
        // Past the last moment the end may be held there is nothing to load for.
        assertFalse(AutomixSchedule.loadAhead(far, AutomixSchedule.ARM_UNTIL_LEFT_MS - 1, loaded = false, armRequested = false))
    }

    @Test
    fun `the service wraps the player's load control and the controller gives the word`() {
        val service = java.io.File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(service.contains("AutomixLoadControl(") && service.contains(".setBufferDurationsMs(30_000, 300_000, 1_000, 2_000)"))
        assertTrue(service.contains("loadAhead = { on -> automixLoadControl?.ahead = on }"))
        val controller = java.io.File("src/main/java/com/dd3boh/outertune/playback/automix/AutomixController.kt").readText()
        assertTrue(controller.contains("AutomixSchedule.loadAhead("))
    }
}
