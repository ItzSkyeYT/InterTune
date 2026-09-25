/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notes in the list of what was heard and not added a mashup's piece takes back out.
 *
 * Found to be part of a mashup, a piece's note goes, since the question about the mashup covers it.
 * It used to be every note with the piece's title, from any time in the run and by anybody, so an
 * unsure Stay by Rihanna went when a mashup with the Kid LAROI's Stay in it turned up an hour on.
 */
class UnsureNotesTest {

    private val kidLaroi = "The Kid LAROI & Justin Bieber"

    private fun note(title: String, artist: String, second: Int) = noteAt(title, artist, second * 1000L)

    private fun noteAt(title: String, artist: String, ms: Long) =
        RecognitionEngine.Added(title, artist, auto = false, heardAtMs = ms)

    private fun piece(title: String, artist: String?) = MixWatch.Sighting("key", title, artist, 0L)

    /** The mashup's Stay was first heard at 600 s, in windows of 12 s. */
    private val appeared = RecognitionEngine.appearanceStart(600_000L, 12_000L)

    @Test
    fun `a note of the piece from this appearance of it goes`() {
        assertTrue(RecognitionEngine.isNoteOf(note("Stay", kidLaroi, 612), piece("Stay", kidLaroi), appeared))
    }

    @Test
    fun `another song of the same name stays`() {
        assertFalse(RecognitionEngine.isNoteOf(note("Stay", "Rihanna feat. Mikky Ekko", 612), piece("Stay", kidLaroi), appeared))
    }

    @Test
    fun `an earlier play of the same song stays`() {
        assertFalse(RecognitionEngine.isNoteOf(note("Stay", kidLaroi, 100), piece("Stay", kidLaroi), appeared))
    }

    @Test
    fun `a note from the window before the piece was first heard goes`() {
        // Noted under another entry of the song, one window before this entry was first heard.
        // Windows are timed from the clock, so they come a few milliseconds over a window apart
        // or under it, never exactly. Allowed one window to the millisecond, 12.005 s stayed.
        for (before in listOf(11_990L, 12_000L, 12_005L, 12_100L)) {
            assertTrue("$before ms before", RecognitionEngine.isNoteOf(noteAt("Stay", kidLaroi, 600_000L - before), piece("Stay", kidLaroi), appeared))
        }
    }

    @Test
    fun `a note from two windows before the piece was first heard stays`() {
        for (before in listOf(23_990L, 24_000L, 24_010L)) {
            assertFalse("$before ms before", RecognitionEngine.isNoteOf(noteAt("Stay", kidLaroi, 600_000L - before), piece("Stay", kidLaroi), appeared))
        }
    }

    @Test
    fun `a remix noted under its full title is its piece under the bare one`() {
        assertTrue(RecognitionEngine.isNoteOf(note("Lean On (ATAX Remix)", "Major Lazer", 612), piece("Lean On", "Major Lazer & DJ Snake"), appeared))
    }

    @Test
    fun `a missing credit is no disagreement`() {
        assertTrue(RecognitionEngine.isNoteOf(note("Stay", "", 612), piece("Stay", kidLaroi), appeared))
        assertTrue(RecognitionEngine.isNoteOf(note("Stay", "Rihanna", 612), piece("Stay", null), appeared))
    }

    @Test
    fun `credits agree when they share a name`() {
        assertTrue(MixSearch.artistsAgree("Rihanna feat. Mikky Ekko", "Rihanna"))
        assertTrue(MixSearch.artistsAgree("Major Lazer & DJ Snake", "Major Lazer x DJ Snake feat. MØ"))
        assertTrue(MixSearch.artistsAgree("Eminem", "EMINEM"))
        assertFalse(MixSearch.artistsAgree("Rihanna", kidLaroi))
        assertTrue(MixSearch.artistsAgree(null, "Rihanna"))
        assertTrue(MixSearch.artistsAgree("", "Rihanna"))
    }
}
