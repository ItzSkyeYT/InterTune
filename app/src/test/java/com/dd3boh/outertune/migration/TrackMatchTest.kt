/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases a library import actually has to survive, written as the wrong answers rather than the
 * right ones.
 *
 * Matching is easy when the catalogues agree. It is only interesting where they do not, and the
 * expensive failure is never "found nothing": it is a remix, a live take or a karaoke version
 * imported silently under the right name, which nobody notices until it plays.
 */
class TrackMatchTest {

    private fun song(title: String, artist: String, seconds: Int?, id: String = title) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(name = artist, id = null)),
        album = null,
        duration = seconds,
        thumbnail = "",
        explicit = false,
    )

    @Test
    fun `the plain case matches confidently`() {
        val wanted = WantedTrack("Blinding Lights", "The Weeknd", 200)
        val best = match(wanted, listOf(song("Blinding Lights", "The Weeknd", 200)))

        assertNotNull(best)
        assertTrue("should not need review, scored ${best!!.total}", best.confident)
    }

    @Test
    fun `duration separates the original from an extended remix`() {
        // Both carry the right title and the right artist, so naming alone cannot tell them apart.
        // This is the whole reason duration is weighted rather than used as a tiebreak.
        val wanted = WantedTrack("Midnight City", "M83", 244)
        val candidates = listOf(
            song("Midnight City (Extended Mix)", "M83", 431, id = "remix"),
            song("Midnight City", "M83", 245, id = "original"),
        )

        assertEquals("original", match(wanted, candidates)!!.candidate.id)
    }

    @Test
    fun `duration separates a studio cut from a live one`() {
        val wanted = WantedTrack("Wish You Were Here", "Pink Floyd", 334)
        val candidates = listOf(
            song("Wish You Were Here (Live)", "Pink Floyd", 412, id = "live"),
            song("Wish You Were Here", "Pink Floyd", 336, id = "studio"),
        )

        assertEquals("studio", match(wanted, candidates)!!.candidate.id)
    }

    @Test
    fun `a cover by the wrong artist loses to nothing`() {
        // The failure that matters most. A karaoke or cover upload has the exact title and a
        // plausible length, and only the artist says it is wrong.
        val wanted = WantedTrack("Creep", "Radiohead", 239)
        val best = match(wanted, listOf(song("Creep", "Karaoke Hits Band", 240)))!!

        assertTrue("a cover should not import silently, scored ${best.total}", !best.confident)
    }

    @Test
    fun `remaster and version decoration does not block a match`() {
        // Exports are full of this and YouTube usually is not, or the other way round.
        val wanted = WantedTrack("Come Together - Remastered 2009", "The Beatles", 259)
        val best = match(wanted, listOf(song("Come Together", "The Beatles", 259)))!!

        assertTrue("decoration should be stripped, scored ${best.total}", best.confident)
    }

    @Test
    fun `a remaster year is decoration before the word as well as after it`() {
        assertEquals(normalise("Hotel California"), normalise("Hotel California - 2013 Remaster"))
        assertEquals(normalise("Hotel California"), normalise("Hotel California - 2001 Digital Remaster"))
        assertEquals(normalise("Bohemian Rhapsody"), normalise("Bohemian Rhapsody - Remastered 2011"))
        // A year that is the title stays.
        assertEquals("1979", normalise("1979"))
    }

    @Test
    fun `a dash suffix is cleaned the way the same words in brackets are`() {
        // His import on 29 Sep. Spotify writes a version after a dash and YouTube in brackets, and
        // the two were cleaned by different rules: the bracket went whole, the dash stayed.
        assertEquals("hide cs01", normalise("Hide - CS01 Version"))
        assertEquals("hide cs01", normalise("Hide (CS01 Version)"))

        assertEquals(normalise("Song (Remastered 2011)"), normalise("Song - Remastered 2011"))
        assertEquals(normalise("Song"), normalise("Song - Remastered 2011"))
        assertEquals(normalise("Song (Radio Edit)"), normalise("Song - Radio Edit"))
        assertEquals(normalise("Song (Extended Mix)"), normalise("Song - Extended Mix"))
        assertEquals(normalise("Song (Skrillex Remix)"), normalise("Song - Skrillex Remix"))
        assertEquals(normalise("Song (Slowed)"), normalise("Song - Slowed"))
        assertEquals(normalise("Song [Live]"), normalise("Song - Live"))
        // An en dash, which some uploads use instead.
        assertEquals(normalise("Song (Slowed Version)"), normalise("Song \u2013 Slowed Version"))
    }

    @Test
    fun `his CS01 pair imports without review`() {
        val wanted = WantedTrack("Hide - CS01 Version", "Dorian Concept", 181)
        val best = match(wanted, listOf(song("Hide (CS01 Version)", "Dorian Concept", 182)))!!

        assertTrue("the same version written two ways, scored ${best.total}", best.confident)
    }

    @Test
    fun `inside a version tag only the generic words go`() {
        // Deleting "(Slowed Version)" whole turned a slowed edit into the plain song.
        assertEquals("song slowed", normalise("Song (Slowed Version)"))
        assertEquals("song sped up", normalise("Song - Sped Up Version"))
        assertEquals("song slowed reverb", normalise("Song (Slowed + Reverb Mix)"))
        assertEquals("song instrumental", normalise("Song (Instrumental Version)"))
        assertEquals("song live", normalise("Song (Live Version)"))
        assertEquals("song acoustic", normalise("Song - Acoustic Version"))
        assertEquals("song extended", normalise("Song (Extended Mix)"))
        assertEquals("love story taylors", normalise("Love Story (Taylor's Version)"))

        // What says nothing about which recording it is still goes.
        assertEquals("song", normalise("Song (Radio Edit)"))
        assertEquals("song", normalise("Song (2009 Remaster)"))
        assertEquals("song", normalise("Song [Remastered 2011]"))
        assertEquals("song", normalise("Song - Single Version"))
        assertEquals("song", normalise("Song - Original Mix"))
        assertEquals("song", normalise("Song - Mono"))
        assertEquals("song", normalise("Song (Album Version) [Bonus Track]"))
    }

    @Test
    fun `a slowed or live edit is not the song`() {
        assertNotEquals(normalise("Song"), normalise("Song - Slowed"))
        assertNotEquals(normalise("Song"), normalise("Song (Slowed Version)"))
        assertNotEquals(normalise("Song - Slowed"), normalise("Song - Super Slowed"))
        assertNotEquals(normalise("Song"), normalise("Song - Live Version"))
        assertNotEquals(normalise("Song"), normalise("Song (Instrumental Version)"))
    }

    @Test
    fun `a version in brackets still counts against a plain title of the same length`() {
        // Before, the bracket was deleted, the names were identical and this imported silently.
        val best = match(
            WantedTrack("Hide", "Dorian Concept", 181),
            listOf(song("Hide (CS01 Version)", "Dorian Concept", 182)),
        )!!
        assertFalse("another version should go to review, scored ${best.total}", best.confident)

        // And given both, the tag is what picks the right one.
        val picked = match(
            WantedTrack("Hide - CS01 Version", "Dorian Concept", 181),
            listOf(
                song("Hide", "Dorian Concept", 181, id = "plain"),
                song("Hide (CS01 Version)", "Dorian Concept", 182, id = "cs01"),
            ),
        )!!
        assertEquals("cs01", picked.candidate.id)
    }

    @Test
    fun `featured artists still go around a version tag`() {
        assertEquals(normalise("Stay"), normalise("Stay (feat. Justin Bieber) - Remastered 2011"))
        assertEquals(normalise("Stay"), normalise("Stay feat. Justin Bieber - Radio Edit"))
        assertEquals(normalise("Stay (Slowed)"), normalise("Stay (feat. Justin Bieber) [Slowed Version]"))
        // A bare "feat." inside a tag stops at the bracket, so the tag is still cleaned whole.
        assertEquals(
            normalise("Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]"),
            normalise("Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)"),
        )
    }

    @Test
    fun `brackets that are part of the title are left alone`() {
        assertEquals(
            "sittin on the dock of the bay",
            normalise("(Sittin' On) The Dock of the Bay - 2002 Remaster"),
        )
        assertEquals("i cant get no satisfaction", normalise("(I Can't Get No) Satisfaction"))
        // A dash that is part of an artist or a word is not a suffix.
        assertEquals("jay z song", normalise("Jay-Z Song"))
    }

    @Test
    fun `featured artists written differently still match`() {
        val wanted = WantedTrack("Stay (feat. Justin Bieber)", "The Kid LAROI", 141)
        val best = match(wanted, listOf(song("Stay", "The Kid LAROI, Justin Bieber", 142)))!!

        assertTrue("feat. forms differ between services, scored ${best.total}", best.confident)
    }

    @Test
    fun `accents surviving or not surviving an export do not matter`() {
        assertEquals(normalise("Béyoncé"), normalise("Beyonce"))
        assertEquals(normalise("Sigur Rós"), normalise("Sigur Ros"))

        val best = match(
            WantedTrack("Hoppipolla", "Sigur Ros", 268),
            listOf(song("Hoppípolla", "Sigur Rós", 268)),
        )!!
        assertTrue("accents should not cost a match, scored ${best.total}", best.confident)
    }

    @Test
    fun `titles in other scripts are kept, not emptied`() {
        assertEquals("がらくた", normalise("がらくた"))
        assertEquals("группа крови", normalise("Группа крови"))

        val best = match(
            WantedTrack("夜に駆ける", "YOASOBI", 261),
            listOf(song("夜に駆ける", "YOASOBI", 261), song("群青", "YOASOBI", 245)),
        )!!
        assertEquals("夜に駆ける", best.candidate.title)
        assertTrue("an exact Japanese title should be confident, scored ${best.total}", best.confident)
    }

    @Test
    fun `an export with no duration can still match on name alone`() {
        // Some exporters omit the column. Refusing everything from those would be worse than
        // matching on two fields, so a missing duration is neutral rather than disqualifying.
        val best = match(
            WantedTrack("Paranoid Android", "Radiohead", null),
            listOf(song("Paranoid Android", "Radiohead", 383)),
        )!!

        assertTrue("a perfect name with no duration should pass, scored ${best.total}", best.confident)
    }

    @Test
    fun `a wrong track with a perfect duration does not win`() {
        // Duration is the discriminator among plausible candidates, not a substitute for the name.
        val best = match(
            WantedTrack("Paranoid Android", "Radiohead", 383),
            listOf(song("Some Other Song", "Another Band", 383)),
        )!!

        assertTrue("length alone must not carry it, scored ${best.total}", !best.confident)
    }

    @Test
    fun `nothing to choose from returns nothing rather than throwing`() {
        assertEquals(null, match(WantedTrack("x", "y", 100), emptyList()))
    }
}
