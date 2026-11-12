/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.playback.automix.Crossfade.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crossfade beside AutoMix, and the rule that keeps it out of a queue that plays as a mix (his
 * words of 5 Nov 2026: "when a song is mixed with another song, or a playlist has a single
 * transition, disable the thing entirely for that playlist").
 */
class CrossfadeRuleTest {

    @Test
    fun `AutoMix plans first, and Crossfade takes the changes it leaves where the queue allows one`() {
        assertEquals(Way.AUTO_THEN_CROSSFADE, Crossfade.way(automix = true, crossfade = true, crossfadeOff = false))
        assertEquals(Way.AUTO, Crossfade.way(automix = true, crossfade = true, crossfadeOff = true))
        assertEquals(Way.AUTO, Crossfade.way(automix = true, crossfade = false, crossfadeOff = false))
        assertEquals(Way.CROSSFADE, Crossfade.way(automix = false, crossfade = true, crossfadeOff = false))
        // Crossfade alone in a queue that plays as a mix: the change is today's.
        assertEquals(Way.NONE, Crossfade.way(automix = false, crossfade = true, crossfadeOff = true))
        assertEquals(Way.NONE, Crossfade.way(automix = false, crossfade = false, crossfadeOff = false))
        assertTrue(Way.AUTO.auto && Way.AUTO_THEN_CROSSFADE.auto && !Way.CROSSFADE.auto && !Way.NONE.auto)
    }

    @Test
    fun `a queue may have crossfades when nothing in it was mixed`() {
        assertNull(Crossfade.whyOff(mixPlaylist = false, autoMixed = false, savedPair = null))
    }

    @Test
    fun `a playlist whose Mix switch is on, a pair Auto mixed, or a saved change keeps Crossfade out, each saying why`() {
        assertEquals("it comes from a playlist that plays as a mix", Crossfade.whyOff(mixPlaylist = true, autoMixed = false, savedPair = null))
        assertEquals("AutoMix has mixed two of its songs", Crossfade.whyOff(mixPlaylist = false, autoMixed = true, savedPair = null))
        assertEquals(
            "a and b follow each other in it with a change saved for them",
            Crossfade.whyOff(mixPlaylist = false, autoMixed = false, savedPair = SongPair("a", "b")),
        )
    }

    @Test
    fun `a saved change counts only between two songs side by side, in its own order`() {
        val songs = listOf("a", "b", "c", "d")
        assertEquals(SongPair("b", "c"), Crossfade.savedSideBySide(songs, setOf(SongPair("b", "c"))))
        // The first such pair, in the queue's order.
        assertEquals(SongPair("a", "b"), Crossfade.savedSideBySide(songs, setOf(SongPair("c", "d"), SongPair("a", "b"))))
        // The other way round, two songs further apart, or songs not in the queue: none.
        assertNull(Crossfade.savedSideBySide(songs, setOf(SongPair("c", "b"))))
        assertNull(Crossfade.savedSideBySide(songs, setOf(SongPair("a", "c"))))
        assertNull(Crossfade.savedSideBySide(songs, setOf(SongPair("x", "y"))))
        // The last song does not run into the first.
        assertNull(Crossfade.savedSideBySide(songs, setOf(SongPair("d", "a"))))
        assertNull(Crossfade.savedSideBySide(songs, emptySet()))
        assertNull(Crossfade.savedSideBySide(listOf("a"), setOf(SongPair("a", "a"))))
        assertNull(Crossfade.savedSideBySide(emptyList(), setOf(SongPair("a", "b"))))
    }

    @Test
    fun `a long queue is read in one pass`() {
        val songs = List(20_000) { "song$it" }
        assertEquals(SongPair("song19998", "song19999"), Crossfade.savedSideBySide(songs, setOf(SongPair("song19998", "song19999"))))
    }

    @Test
    fun `its lengths are the fade's, six seconds when nothing is stored, and a stored one out of range is brought back`() {
        assertEquals(listOf(2, 4, 6, 8, 10, 12), Crossfade.SECONDS_CHOICES)
        assertEquals(6, Crossfade.seconds(null))
        assertEquals(10, Crossfade.seconds(10))
        assertEquals(2, Crossfade.seconds(0))
        assertEquals(12, Crossfade.seconds(30))
        assertTrue(Crossfade.DEFAULT_SECONDS in Crossfade.SECONDS_CHOICES)
    }
}
