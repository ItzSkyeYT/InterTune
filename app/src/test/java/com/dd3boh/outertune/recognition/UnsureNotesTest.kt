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
 * Which notes in the list of what was heard and not added a mashup's piece takes back out, which
 * the mashup takes when it is added, and when a playlist's run has noted a mashup already.
 *
 * Found to be part of a mashup, a piece's note goes, since the question about the mashup covers it.
 * It used to be every note with the piece's title, from any time in the run, so an unsure Stay by
 * Rihanna went when a mashup with the Kid LAROI's Stay in it turned up an hour on. Only notes from
 * the piece's present appearance go now, still by title whoever they are credited to.
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
    fun `another song of the same name from before this appearance stays`() {
        assertFalse(RecognitionEngine.isNoteOf(note("Stay", "Rihanna feat. Mikky Ekko", 100), piece("Stay", kidLaroi), appeared))
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
    fun `a remix credited to its remixer goes, whoever the credit names`() {
        // The Averez remix of Lean On as Shazam credited it on 25 Sep, noted the window before the
        // Robin Schulz entry was first heard. Kept for a credit that shares no name with the
        // piece's, it stayed in the list next to the edit note, as if it were a second thing heard.
        val robinSchulz = piece("Lean On (feat. MØ) [Robin Schulz Extended Remix]", "Major Lazer & DJ Snake")
        assertTrue(RecognitionEngine.isNoteOf(note("Lean On", "DjSunnymega", 588), robinSchulz, appeared))
        assertTrue(RecognitionEngine.isNoteOf(note("Lean On", "", 612), robinSchulz, appeared))
        assertTrue(RecognitionEngine.isNoteOf(note("Lean On", "Major Lazer", 612), piece("Lean On", null), appeared))
    }

    private fun mashupNote(title: String, vararg keys: String) =
        RecognitionEngine.Added(title, "mashup", auto = false, keys = keys.toSet())

    private fun isAbout(note: RecognitionEngine.Added, vararg keys: String) =
        RecognitionEngine.isMashupNoteOf(note, keys.toSet(), "Stay x Peaches (Mashup)", "mashup")

    @Test
    fun `a mashup added takes every note a playlist's run made about it`() {
        // A quiet spell ended it once, and the run noted it again with its pieces the other way round.
        assertTrue(isAbout(mashupNote("Stay + Peaches", "stayk", "peaches"), "peaches", "stayk"))
        assertTrue(isAbout(mashupNote("Peaches + Stay", "peaches", "stayk"), "peaches", "stayk"))
        // Or heard under fewer of its pieces then.
        assertTrue(isAbout(mashupNote("Stay + Peaches", "stayk", "peaches"), "stayk", "peaches", "peaches-alt"))
        assertTrue(isAbout(mashupNote("Stay x Peaches (Mashup)"), "stayk", "peaches"))
    }

    @Test
    fun `a mashup added leaves the notes about other things`() {
        // Another mashup with one of its songs in it.
        assertFalse(isAbout(mashupNote("Stay + Beggin'", "stayk", "beggin"), "stayk", "peaches"))
        // A song that was unsure, which is a piece's to take, not the mashup's.
        assertFalse(isAbout(RecognitionEngine.Added("Stay + Peaches", "someone", auto = false), "stayk", "peaches"))
        assertFalse(isAbout(RecognitionEngine.Added("Peaches", "Justin Bieber", auto = false), "stayk", "peaches"))
    }

    private fun notedAlready(notes: List<RecognitionEngine.Added>, name: String, vararg keys: String) =
        RecognitionEngine.notedAlready(notes, keys.toSet(), name, "mashup")

    @Test
    fun `a playlist's run notes a mashup once`() {
        // Faint x No Love taken, and then asked again for Numb: noted under the upload naming all
        // three. While the question waits, a search clear for another upload of Faint and No Love,
        // or one with only a toss-up named after the songs it carried, notes nothing more, though
        // more of the mashup has been heard since.
        val notes = listOf(mashupNote("Faint x No Love x Numb (Mashup)", "faint", "nolove", "numb"))
        assertTrue(notedAlready(notes, "Eminem & LINKIN PARK - No Love / Faint (No Love x Faint Mashup)", "faint", "nolove", "numb"))
        assertTrue(notedAlready(notes, "Faint + No Love (feat. Lil Wayne)", "faint", "nolove", "numb", "faint-alt"))
        // Another mashup with one of its songs in it gets a note of its own.
        assertFalse(notedAlready(notes, "Faint + In The End", "faint", "intheend"))
        // A note of the same name, whatever it is about, as before.
        assertTrue(notedAlready(listOf(RecognitionEngine.Added("Faint + In The End", "someone", auto = false)), "Faint + In The End", "faint", "intheend"))
        assertFalse(notedAlready(emptyList(), "Faint + In The End", "faint", "intheend"))
    }
}
