/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
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
