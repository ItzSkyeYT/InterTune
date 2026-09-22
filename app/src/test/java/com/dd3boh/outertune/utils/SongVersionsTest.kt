/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Titles taken from YouTube Music's related pages on 11 Sep 2026, not invented. */
class SongVersionsTest {

    @Test
    fun `every version of Take on Me the related page offered is caught`() {
        listOf(
            "Take on Me (1985 Single Mix) (1985 Single Mix; 2015 Remaster)", // in "You might also like"
            "Take On Me",
            "Take on Me (Symphonic Version)",
            "Take on Me (Electrobossa Mix)",
            "Take On Me (2017 Acoustic)",
        ).forEach { assertTrue(it, SongVersions.isVersionOf(it, "Take on Me")) }
    }

    @Test
    fun `a live recording with a long bracket is a version`() {
        assertTrue(
            SongVersions.isVersionOf(
                "Hotel California (Live at the Millennium Concert, Staples Center, Los Angeles, CA, 12/31/1999; 2018 Remaster)",
                "Hotel California",
            )
        )
    }

    @Test
    fun `other songs on the same related page are not versions`() {
        // "Every Breath You Take" contains "take", which a containment test would have caught out.
        listOf("Africa", "Every Breath You Take", "Time After Time", "Take Me Home", "Forever Young")
            .forEach { assertFalse(it, SongVersions.isVersionOf(it, "Take on Me")) }
    }

    @Test
    fun `the seed's own bracket does not stop its plain version matching`() {
        assertTrue(SongVersions.isVersionOf("Heart of Glass", "Heart Of Glass (Single Version / Remastered)"))
    }

    @Test
    fun `a spaced dash suffix is a qualifier`() {
        assertTrue(SongVersions.isVersionOf("Bohemian Rhapsody - Remastered 2011", "Bohemian Rhapsody"))
    }

    @Test
    fun `case and punctuation are ignored`() {
        assertTrue(SongVersions.isVersionOf("Stayin' Alive", "Stayin Alive"))
    }

    @Test
    fun `a title that is only brackets is nobody's version`() {
        // Both reduce to nothing; matching empty to empty would filter every untitled track.
        assertFalse(SongVersions.isVersionOf("(Untitled)", "(Intro)"))
    }

    @Test
    fun `versions in other scripts and full-width brackets are caught`() {
        assertTrue(SongVersions.isVersionOf("夜に駆ける（Live）", "夜に駆ける"))
        assertTrue(SongVersions.isVersionOf("夜に駆ける【MV】", "夜に駆ける"))
        assertTrue(SongVersions.isVersionOf("Кино (Live)", "Кино"))
    }

    @Test
    fun `different titles in other scripts are not versions`() {
        assertFalse(SongVersions.isVersionOf("クイーン", "キング"))
        // A title quoted in corner brackets keeps its words: stripping them would have emptied it.
        assertFalse(SongVersions.isVersionOf("「群青」", "「夜に駆ける」"))
        assertTrue(SongVersions.isVersionOf("「群青」", "「群青」"))
    }

    @Test
    fun `a dash followed by a qualifier is stripped, one followed by a name is not`() {
        // "Levitating - Maduk Remix" is a version of "Levitating". "Initial D - Deja Vu" is not a
        // version of "Initial D - Night Of Fire", and treating it as one deleted 64 candidates
        // from a single build on a real library.
        assertTrue(SongVersions.isVersionOf("Levitating - Maduk Remix", "Levitating"))
        assertTrue(SongVersions.isVersionOf("Africa - 2020 Remaster", "Africa"))
        assertTrue(SongVersions.isVersionOf("Faded - Official Video", "Faded"))

        assertFalse(SongVersions.isVersionOf("Initial D - Deja Vu", "Initial D - Night Of Fire"))
        assertFalse(SongVersions.isVersionOf("Initial D - Deja Vu", "Initial D - Running In The 90's"))
    }

    @Test
    fun `a bare year after a dash is still a qualifier`() {
        assertTrue(SongVersions.isVersionOf("Africa - 1982", "Africa"))
    }

    @Test
    fun `real version sets are untouched by the bound`() {
        assertTrue(SongVersions.isVersionOf("Stay (Maduk Remix)", "Stay"))
        assertTrue(SongVersions.isVersionOf("HOME (SLOWED)", "Home"))
    }

    @Test
    fun `a treatment after the dash is still stripped when the artist is known`() {
        assertEquals("bohemian rhapsody", SongVersions.baseTitle("Bohemian Rhapsody - Remastered 2011", "Queen"))
        assertEquals("africa", SongVersions.baseTitle("Africa - 2020 Remaster", "Toto"))
    }

    @Test
    fun `an upload titled Artist - Track by that artist is the track`() {
        assertEquals("hold on", SongVersions.baseTitle("Southbound - Hold On", "Southbound"))
        assertEquals(SongVersions.baseTitle("One More Time", "Daft Punk"), SongVersions.baseTitle("Daft Punk - One More Time (Official Video)", "Daft Punk"))
        // Compared the way titles are, so spacing, case and punctuation in either do not matter.
        assertEquals("ecuador", SongVersions.baseTitle("Sash!  - Ecuador (Official Video)", "Sash!"))
        assertEquals("i love u", SongVersions.baseTitle("wiv - i love u. - sped up + reverb", "wiv"))
    }

    @Test
    fun `different songs by one artist no longer reduce to the artist's name`() {
        // All four were "pizza hotline" before, because "OST" is a treatment word. That made them
        // one version group and one "Not this song" ban, on the 11 Sep library.
        val automata = SongVersions.baseTitle("Pizza Hotline - Automata | MOTORSLICE OST", "Pizza Hotline")
        val heavyMachine = SongVersions.baseTitle("Pizza Hotline - Heavy Machine (Boss Theme 1) | MOTORSLICE OST", "Pizza Hotline")
        assertEquals("automata motorslice ost", automata)
        assertEquals("heavy machine motorslice ost", heavyMachine)
        assertEquals(automata, SongVersions.baseTitle("Pizza Hotline - Automata (Ambient Mix) | MOTORSLICE OST", "Pizza Hotline"))
        assertEquals("take me home", SongVersions.baseTitle("Southbound - Take Me Home", "Southbound"))
    }

    @Test
    fun `a dash after somebody else's name is left whole`() {
        // Only the song's own artist, matched whole, is ever taken off the front.
        assertEquals("some band some song", SongVersions.baseTitle("Some Band - Some Song", "Someone Else"))
        assertEquals("initial d deja vu", SongVersions.baseTitle("Initial D - Deja Vu", "Dave Rodgers"))
        assertEquals("kinneret southbound wind resistance", SongVersions.baseTitle("Kinneret, Southbound - Wind Resistance", "Southbound"))
        assertEquals("south hold on", SongVersions.baseTitle("South - Hold On", "Southbound"))
    }

    @Test
    fun `without an artist nothing changes`() {
        // The Last.fm trial that turned this up saw "Southbound", but that was its own copy of the
        // rule from before the dash was bounded. The app kept the artist in the name instead.
        assertEquals("southbound hold on", SongVersions.baseTitle("Southbound - Hold On"))
        assertEquals("southbound hold on", SongVersions.baseTitle("Southbound - Hold On", null))
        assertEquals("southbound hold on", SongVersions.baseTitle("Southbound - Hold On", "  "))
        assertEquals("levitating", SongVersions.baseTitle("Levitating - Maduk Remix"))
    }

    @Test
    fun `a title with no dash is untouched by its artist`() {
        assertEquals("hold on", SongVersions.baseTitle("Hold On", "Southbound"))
        // A song named after its artist keeps its name.
        assertEquals("southbound", SongVersions.baseTitle("Southbound", "Southbound"))
        assertEquals("take on me", SongVersions.baseTitle("Take on Me (2017 Acoustic)", "a-ha"))
    }
}
