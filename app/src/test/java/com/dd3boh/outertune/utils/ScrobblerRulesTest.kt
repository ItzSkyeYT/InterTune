/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.lastfm.LastFm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes to Last.fm. Scrobbles used to name "A, B" as one artist, which Last.fm files as an
 * artist of its own, and a song started from search results arrived with length -1 and never
 * scrobbled at all.
 */
class ScrobblerRulesTest {

    @Test
    fun `the first artist is sent, not all of them joined`() {
        assertEquals("Daft Punk", Scrobbler.primaryArtist(listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers")))
        assertEquals("Adele", Scrobbler.primaryArtist(listOf("Adele")))
    }

    @Test
    fun `a blank first name falls through to the next one`() {
        assertEquals("Björk", Scrobbler.primaryArtist(listOf("  ", " Björk ")))
    }

    @Test
    fun `no artist at all sends nothing`() {
        assertNull(Scrobbler.primaryArtist(emptyList()))
        assertNull(Scrobbler.primaryArtist(listOf("", " ")))
    }

    @Test
    fun `a song whose metadata says -1 qualifies once its real length is used`() {
        // Four minutes played of a five-minute song found through search: the metadata length
        // failed the rule, the recovered one passes it.
        val played = 4 * 60 * 1000L
        assertFalse(LastFm.qualifies(played, -1))
        assertTrue(LastFm.qualifies(played, 300))
    }
}
