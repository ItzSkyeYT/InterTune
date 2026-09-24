/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the room is in a song, worked out from one Shazam match.
 *
 * Shazam's offset is where the start of the submitted sample falls in the track. Checked against the
 * live endpoint on 24 Sep with the Sneaky Snitch clip the fingerprint tests use, which starts 60 s
 * into the track: the whole ten seconds matched at 59.98, the first seven at 59.98 and the last
 * seven at 62.98. So the offset belongs to the moment the window began, not to when it ended and
 * certainly not to when the answer came back, and these pin that down.
 */
class NowPlayingTest {

    private fun playing(
        offset: Double = 60.0,
        sampledAtMs: Long = 0L,
        duration: Int? = 200,
        rate: Double = 1.0,
    ) = RecognitionEngine.NowPlaying(
        title = "Sneaky Snitch",
        artist = "Kevin MacLeod",
        artworkUrl = null,
        offsetAtMatch = offset,
        sampledAtMs = sampledAtMs,
        durationSeconds = duration,
        rate = rate,
    )

    @Test
    fun `the position counts from when the sample began, not from when the answer arrived`() {
        // A twelve second window and two seconds of asking: the room is 14 s past the offset by
        // the time anything can be shown, and the estimate has to say so from the first redraw.
        assertEquals(74, playing(sampledAtMs = 1_000L).positionSeconds(nowMs = 15_000L))
    }

    @Test
    fun `a faster copy moves the position faster`() {
        assertEquals(75, playing(rate = 1.25).positionSeconds(nowMs = 12_000L))
    }

    @Test
    fun `the position stops at the end of the track`() {
        assertEquals(200, playing().positionSeconds(nowMs = 10_000_000L))
    }

    @Test
    fun `the track ends when the estimate reaches its length`() {
        assertEquals(140_000L, playing().endsAtMs())
        assertEquals(70_000L, playing(rate = 2.0).endsAtMs())
        assertEquals(1_140_000L, playing(sampledAtMs = 1_000_000L).endsAtMs())
    }

    @Test
    fun `with no length there is no end to wait for`() {
        assertNull(playing(duration = null).endsAtMs())
        assertNull(playing(rate = 0.0).endsAtMs())
    }

    @Test
    fun `a new match is the same song only by title and artist`() {
        val song = playing()
        fun heard(title: String, artist: String?) =
            Recognised(title = title, artist = artist, artworkUrl = null, isrc = null, shazamKey = null)
        assertTrue(song.isOf(heard("Sneaky Snitch", "Kevin MacLeod")))
        assertFalse(song.isOf(heard("Sneaky Snitch", "Somebody else")))
        assertFalse(song.isOf(heard("Monkeys Spinning Monkeys", "Kevin MacLeod")))
    }
}
