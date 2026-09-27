/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How polling answers become a song's state on a DLNA renderer. */
class DlnaTrackerTest {
    private fun tracker(startMs: Long = 0, durationMs: Long = 200_000, play: Boolean = true) =
        DlnaTracker(startMs, durationMs, "TV", play, startGraceMs = 8_000, endSlackMs = 5_000)

    private fun DlnaTracker.poll(state: String, at: Long, rel: Long? = null, duration: Long? = null, status: String = "OK") =
        report(state, status, rel, duration, at)

    @Test
    fun `a song sent to play opens, plays and pauses`() {
        val song = tracker()
        assertEquals(ReceiverState.LOADING, song.view.state)
        song.playAsked(0)
        assertEquals(ReceiverState.LOADING, song.poll("TRANSITIONING", 500, rel = 0).state)
        val playing = song.poll("PLAYING", 1_500, rel = 1_000, duration = 210_000)
        assertEquals(ReceiverState.PLAYING, playing.state)
        assertEquals(1_000L, playing.positionMs)
        assertEquals(210_000L, playing.durationMs)
        assertEquals(ReceiverState.PAUSED, song.poll("PAUSED_PLAYBACK", 2_500, rel = 2_000).state)
    }

    @Test
    fun `stopped before playing is still opening, until the grace runs out`() {
        val song = tracker()
        song.playAsked(0)
        assertEquals(ReceiverState.LOADING, song.poll("STOPPED", 1_000).state)
        assertEquals(ReceiverState.LOADING, song.poll("STOPPED", 7_999).state)
        val failed = song.poll("STOPPED", 8_000)
        assertEquals(ReceiverState.FAILED, failed.state)
        assertEquals("TV stopped without playing it", failed.error)
        // Failed is final for this song.
        assertEquals(ReceiverState.FAILED, song.poll("PLAYING", 9_000, rel = 1_000).state)
    }

    @Test
    fun `an error status fails the song at once, unless it is playing anyway`() {
        val broken = tracker()
        broken.playAsked(0)
        val failed = broken.poll("STOPPED", 500, status = "ERROR_OCCURRED")
        assertEquals(ReceiverState.FAILED, failed.state)
        assertEquals("TV could not play it", failed.error)

        val shrugging = tracker()
        shrugging.playAsked(0)
        assertEquals(ReceiverState.PLAYING, shrugging.poll("PLAYING", 500, rel = 100, status = "ERROR_OCCURRED").state)
    }

    @Test
    fun `stopped at the end after playing is the end, even when the position went back to 0`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 197_000, duration = 200_000)
        val ended = song.poll("STOPPED", 2_000, rel = 0)
        assertEquals(ReceiverState.ENDED, ended.state)
        assertEquals(200_000L, ended.positionMs)
        assertEquals(ReceiverState.ENDED, song.poll("PLAYING", 3_000, rel = 0).state)
    }

    @Test
    fun `NO_MEDIA_PRESENT at the end counts too`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 196_000)
        assertEquals(ReceiverState.ENDED, song.poll("NO_MEDIA_PRESENT", 2_000).state)
    }

    @Test
    fun `the end is judged from where the song had got to by now`() {
        val song = tracker()
        song.playAsked(0)
        // Last heard 10 s before the end, then nothing for 6 s: 4 s short, within the slack.
        song.poll("PLAYING", 0, rel = 190_000)
        assertEquals(ReceiverState.ENDED, song.poll("STOPPED", 6_000).state)
    }

    @Test
    fun `stopped halfway on the device is a stop, where it was, and stays there`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 60_000)
        val stopped = song.poll("STOPPED", 2_000, rel = 0)
        assertEquals(ReceiverState.IDLE, stopped.state)
        assertEquals(61_000L, stopped.positionMs)
        assertEquals("Stopped on TV", stopped.error)
        // Polled again long after: still where it stopped, and never mistaken for the end.
        val later = song.poll("STOPPED", 500_000, rel = 0)
        assertEquals(ReceiverState.IDLE, later.state)
        assertEquals(61_000L, later.positionMs)
    }

    @Test
    fun `a stop that was asked for is only a stop`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 199_000)
        song.stopAsked()
        assertEquals(ReceiverState.IDLE, song.view.state)
        assertEquals(ReceiverState.IDLE, song.poll("STOPPED", 2_000).state)
    }

    @Test
    fun `paused, then stopped by the renderer's own timeout, is not the end`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 100_000)
        song.poll("PAUSED_PLAYBACK", 2_000, rel = 101_000)
        val stopped = song.poll("STOPPED", 900_000)
        assertEquals(ReceiverState.IDLE, stopped.state)
        assertEquals(101_000L, stopped.positionMs)
    }

    @Test
    fun `a renderer that never says the length ends a song when it stops after playing`() {
        val song = tracker(durationMs = -1)
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 5_000, duration = null)
        assertEquals(ReceiverState.ENDED, song.poll("STOPPED", 2_000).state)
    }

    @Test
    fun `positions a renderer does not report run on from the last one`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 10_000)
        assertEquals(12_000L, song.poll("PLAYING", 3_000, rel = null).positionMs)
    }

    @Test
    fun `a song loaded paused sits stopped, shown as paused where it will start`() {
        val song = tracker(startMs = 30_000, play = false)
        assertEquals(ReceiverState.PAUSED, song.view.state)
        assertEquals(30_000L, song.view.positionMs)
        val held = song.poll("STOPPED", 60_000, rel = 0)
        assertEquals(ReceiverState.PAUSED, held.state)
        assertEquals(30_000L, held.positionMs)
        assertEquals(30_000L, song.pendingSeekMs)
        assertNull(song.takePendingSeek())
    }

    @Test
    fun `a start past 0 is sought once the renderer plays, and shown meanwhile`() {
        val song = tracker(startMs = 90_000)
        song.playAsked(0)
        assertNull(song.takePendingSeek())
        val playing = song.poll("PLAYING", 1_000, rel = 500)
        assertEquals(90_000L, playing.positionMs)
        assertEquals(90_000L, song.takePendingSeek())
        assertNull(song.takePendingSeek())
        assertEquals(90_400L, song.poll("PLAYING", 2_000, rel = 90_400).positionMs)
    }

    @Test
    fun `a seek while playing goes now, and one before waits`() {
        val song = tracker()
        assertEquals(false, song.seekAsked(50_000))
        assertEquals(50_000L, song.pendingSeekMs)
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 0)
        assertEquals(50_000L, song.takePendingSeek())
        assertEquals(true, song.seekAsked(120_000))
        assertEquals(-1L, song.pendingSeekMs)
        assertEquals(120_000L, song.view.positionMs)
    }

    @Test
    fun `a state nobody knows changes nothing`() {
        val song = tracker()
        song.playAsked(0)
        song.poll("PLAYING", 1_000, rel = 10_000)
        assertEquals(ReceiverState.PLAYING, song.poll("CUSTOM_VENDOR_STATE", 2_000, rel = 11_000).state)
        assertEquals(ReceiverState.PLAYING, song.report(null, null, null, null, 3_000).state)
    }
}
