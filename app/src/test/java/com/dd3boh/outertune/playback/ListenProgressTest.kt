/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenProgressTest {

    @Test
    fun `forward playback accumulates the distance since the last checkpoint`() {
        val playedMs = ListenProgress.accumulate(playedMsSoFar = 60_000L, lastCheckpointPositionMs = 60_000L, newPositionMs = 120_000L)
        assertEquals(120_000L, playedMs)
    }

    // A position behind the last checkpoint adds nothing and keeps everything already
    // accumulated, so a rewind can never lower playedMs.
    @Test
    fun `a rewind past the last checkpoint loses nothing already played`() {
        val playedMs = ListenProgress.accumulate(playedMsSoFar = 60_000L, lastCheckpointPositionMs = 60_000L, newPositionMs = 10_000L)
        assertEquals(60_000L, playedMs)
    }

    // Playing forward again after that rewind still only credits the new ground covered.
    @Test
    fun `playing forward again after a rewind only adds the new ground covered`() {
        val afterRewind = ListenProgress.accumulate(playedMsSoFar = 60_000L, lastCheckpointPositionMs = 60_000L, newPositionMs = 10_000L)
        val playedMs = ListenProgress.accumulate(playedMsSoFar = afterRewind, lastCheckpointPositionMs = 10_000L, newPositionMs = 40_000L)
        assertEquals(90_000L, playedMs)
    }

    @Test
    fun `a resumed play that starts mid song accumulates from its own start`() {
        // info.lastCheckpointPositionMs starts out equal to info.startPositionMs (a resume at 90s).
        val playedMs = ListenProgress.accumulate(playedMsSoFar = 0L, lastCheckpointPositionMs = 90_000L, newPositionMs = 150_000L)
        assertEquals(60_000L, playedMs)
    }

    @Test
    fun `a seek before the first checkpoint credits the stretch up to where it left from`() {
        // Played from 0 to 50s with no tick yet, then a seek back to 0.
        val afterSeek = ListenProgress.creditBeforeSeek(opened = true, playedMsSoFar = 0L, lastCheckpointPositionMs = 0L, oldPositionMs = 50_000L)
        assertEquals(50_000L, afterSeek)
        // The next tick at 10s measures from where the seek landed.
        val playedMs = ListenProgress.accumulate(playedMsSoFar = afterSeek, lastCheckpointPositionMs = 0L, newPositionMs = 10_000L)
        assertEquals(60_000L, playedMs)
    }

    @Test
    fun `a seek on a song loaded but not started credits nothing`() {
        // A saved queue loaded paused at 3:00 and dragged to 0:30 before play: nothing was heard.
        val afterSeek = ListenProgress.creditBeforeSeek(opened = false, playedMsSoFar = 0L, lastCheckpointPositionMs = 0L, oldPositionMs = 180_000L)
        assertEquals(0L, afterSeek)
    }

    @Test
    fun `a still-open row within the gap continues its own session`() {
        val sessionId = ListenProgress.sessionIdFor(
            startedAt = 1_000_000L,
            openSessionId = 42L,
            openLastKnownAt = 999_500L, // half a second of silence: well within the gap
            lastEndedAt = 0L, // an ancient closed session, which must not win over the fresher open row
            lastSessionId = 7L,
            sessionGapMs = 1_800_000L,
        )
        assertEquals(42L, sessionId)
    }

    // A row can be left OPEN until the next process starts (a leaked row, or one whose close
    // failed); it must not capture every later listen.
    @Test
    fun `a stale open row from days ago does not capture a new session`() {
        val sessionId = ListenProgress.sessionIdFor(
            startedAt = 1_000_000_000L,
            openSessionId = 1L,
            openLastKnownAt = 1_000L, // last known to be running long, long ago
            lastEndedAt = null,
            lastSessionId = null,
            sessionGapMs = 1_800_000L,
        )
        assertEquals(1_000_000_000L, sessionId)
    }

    @Test
    fun `an open row older than the last closed play loses to it`() {
        val sessionId = ListenProgress.sessionIdFor(
            startedAt = 1_000_000L,
            openSessionId = 1L,
            openLastKnownAt = 100_000L, // left open long before the last closed play ended
            lastEndedAt = 990_000L,
            lastSessionId = 7L,
            sessionGapMs = 1_800_000L,
        )
        assertEquals(7L, sessionId)
    }

    @Test
    fun `no open row and a short gap continues the last closed session`() {
        val sessionId = ListenProgress.sessionIdFor(
            startedAt = 1_000_000L,
            openSessionId = null,
            openLastKnownAt = null,
            lastEndedAt = 999_000L,
            lastSessionId = 7L,
            sessionGapMs = 1_800_000L,
        )
        assertEquals(7L, sessionId)
    }

    @Test
    fun `no open row and a long gap starts a new session`() {
        val sessionId = ListenProgress.sessionIdFor(
            startedAt = 3_000_000L,
            openSessionId = null,
            openLastKnownAt = null,
            lastEndedAt = 0L,
            lastSessionId = 7L,
            sessionGapMs = 1_800_000L,
        )
        assertEquals(3_000_000L, sessionId)
    }

    @Test
    fun `nothing played before continues nothing`() {
        val sessionId = ListenProgress.sessionIdFor(
            startedAt = 5_000L,
            openSessionId = null,
            openLastKnownAt = null,
            lastEndedAt = null,
            lastSessionId = null,
            sessionGapMs = 1_800_000L,
        )
        assertEquals(5_000L, sessionId)
    }

    // How a play ended

    @Test
    fun `the player's own end is an end, whatever else happened`() {
        assertEquals(EndReason.ENDED, ListenProgress.endReason(endedByPlayer = true, failed = false, transition = EndReason.ENDED))
        // An error the play got over (the network came back, the retry played on) changes nothing.
        assertEquals(EndReason.ENDED, ListenProgress.endReason(endedByPlayer = true, failed = true, transition = null))
    }

    @Test
    fun `a stream that died and was skipped past is an error, not a skip`() {
        // Skip on error moves on by seeking, the same transition as the listener pressing next.
        assertEquals(EndReason.ERROR, ListenProgress.endReason(endedByPlayer = false, failed = true, transition = EndReason.SKIPPED))
        // And a new queue chosen to get away from a song that stopped on its error.
        assertEquals(EndReason.ERROR, ListenProgress.endReason(endedByPlayer = false, failed = true, transition = EndReason.REPLACED))
    }

    @Test
    fun `a dead song still dead when the service goes is an error`() {
        assertEquals(EndReason.ERROR, ListenProgress.endReason(endedByPlayer = false, failed = true, transition = null))
    }

    @Test
    fun `a repeat paused and then closed was stopped, not played to the end`() {
        // On repeat one the repeat before it left ENDED for the same song, and nothing moved on
        // from this one: the service was released while it sat paused.
        assertEquals(EndReason.STOPPED, ListenProgress.endReason(endedByPlayer = false, failed = false, transition = EndReason.ENDED))
    }

    @Test
    fun `otherwise the transition says how it ended, and none means stopped`() {
        assertEquals(EndReason.SKIPPED, ListenProgress.endReason(endedByPlayer = false, failed = false, transition = EndReason.SKIPPED))
        assertEquals(EndReason.REPLACED, ListenProgress.endReason(endedByPlayer = false, failed = false, transition = EndReason.REPLACED))
        assertEquals(EndReason.UNKNOWN, ListenProgress.endReason(endedByPlayer = false, failed = false, transition = EndReason.UNKNOWN))
        assertEquals(EndReason.STOPPED, ListenProgress.endReason(endedByPlayer = false, failed = false, transition = null))
    }

    // Continuing an earlier play

    private val t = 1_790_000_000_000L
    private val minute = 60_000L

    private fun continues(endReason: Int, endPositionMs: Long = 83_000, endedAt: Long = t, startPositionMs: Long = 83_000, startedAt: Long = t + 10 * minute) =
        ListenProgress.continues(endReason, endPositionMs, previousStartedAt = endedAt - 83_000, previousEndedAt = endedAt, startPositionMs = startPositionMs, startedAt = startedAt)

    @Test
    fun `a play started where a stop left it continues the stop`() {
        assertTrue(continues(EndReason.STOPPED))
    }

    @Test
    fun `a play started where a failed play died continues it, as a stop did before failures were written`() {
        assertTrue(continues(EndReason.ERROR))
    }

    @Test
    fun `nothing else is continued`() {
        assertFalse(continues(EndReason.ENDED))
        assertFalse(continues(EndReason.SKIPPED))
        assertFalse(continues(EndReason.REPLACED))
        assertFalse(continues(EndReason.UNKNOWN))
        // A newer play still waiting for its close is not one to continue either.
        assertFalse(continues(EndReason.OPEN))
    }

    @Test
    fun `only from the same spot, within the day, from a known position`() {
        assertTrue(continues(EndReason.ERROR, startPositionMs = 83_000 + ListenProgress.RESUME_TOLERANCE_MS))
        assertFalse(continues(EndReason.ERROR, startPositionMs = 83_000 + ListenProgress.RESUME_TOLERANCE_MS + 1))
        assertFalse(continues(EndReason.ERROR, startPositionMs = 0))
        assertTrue(continues(EndReason.STOPPED, startedAt = t + ListenProgress.RESUME_WINDOW_MS))
        assertFalse(continues(EndReason.STOPPED, startedAt = t + ListenProgress.RESUME_WINDOW_MS + 1))
        assertFalse(continues(EndReason.STOPPED, endPositionMs = -1, startPositionMs = 0))
    }
}
