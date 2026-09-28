/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
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
}
