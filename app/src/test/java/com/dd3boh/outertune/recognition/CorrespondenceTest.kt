/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides what continuous listening is allowed to add without asking.
 *
 * Worth testing rather than eyeballing, because both ways of being wrong are invisible in use. Too
 * loose and a karaoke backing track quietly lands in somebody's playlist during a party, and nobody
 * finds out until they play it back. Too tight and continuous mode stops on every song to ask, at
 * which point it is not continuous.
 *
 * Shazam gives a clean title and artist. YouTube gives whatever the uploader typed, which is the
 * same title wearing "(Official Video)", "- Remastered 2011" or a feature credit. The cases below
 * are that gap in both directions.
 */
class CorrespondenceTest {

    private fun shazam(title: String, artist: String?) =
        Recognised(title = title, artist = artist, artworkUrl = null, isrc = null, shazamKey = null)

    private fun youtube(title: String, vararg artists: String) = SongItem(
        id = "x",
        title = title,
        artists = artists.map { Artist(name = it, id = null) },
        thumbnail = "",
    )

    @Test
    fun theSameSongDressedUpByYouTubeStillCorresponds() {
        val track = shazam("Blinding Lights", "The Weeknd")
        assertTrue(corresponds(track, youtube("Blinding Lights", "The Weeknd")))
        assertTrue(corresponds(track, youtube("Blinding Lights (Official Video)", "The Weeknd")))
        assertTrue(corresponds(track, youtube("Blinding Lights [Official Audio]", "The Weeknd")))
        assertTrue(corresponds(track, youtube("Blinding Lights", "The Weeknd", "Rosalía")))
    }

    /** Punctuation and case are the uploader's business, not a reason to stop and ask. */
    @Test
    fun punctuationAndCaseDoNotMatter() {
        assertTrue(
            corresponds(
                shazam("Don't Stop Me Now", "Queen"),
                youtube("Dont Stop Me Now", "queen"),
            )
        )
    }

    /**
     * The ones that must not slip through. Each has the right title and the wrong recording, which
     * is exactly what a title-and-artist search turns up and exactly what a user would not want
     * appearing in a playlist unasked.
     */
    @Test
    fun coversAndOtherRecordingsDoNotCorrespond() {
        val track = shazam("Instant Crush", "Daft Punk")
        assertFalse("a cover by someone else", corresponds(track, youtube("Instant Crush", "Pomplamoose")))
        assertFalse("a karaoke backing track", corresponds(track, youtube("Instant Crush", "Karaoke Version")))
        assertFalse("a different song entirely", corresponds(track, youtube("Get Lucky", "Daft Punk")))
    }

    /**
     * A longer YouTube title for the same recording still corresponds.
     *
     * This is the case that cost a real listening test. Shazam names it "Children"; YouTube lists
     * "Children (Dream Version)". Requiring the titles to be equal rejected it on every pass, and
     * continuous mode then discarded a correct match in silence.
     */
    @Test
    fun aLongerTitleForTheSameRecordingCorresponds() {
        val track = shazam("Children", "Robert Miles")
        assertTrue(corresponds(track, youtube("Children (Dream Version)", "Robert Miles")))
        assertTrue(corresponds(track, youtube("Children - Original Mix", "Robert Miles")))
    }

    /** But a longer title that is a different song does not, which is what the artist is for. */
    @Test
    fun aDifferentSongSharingAPrefixDoesNotCorrespond() {
        assertFalse(
            corresponds(shazam("Children", "Robert Miles"), youtube("Children of the Grave", "Black Sabbath"))
        )
    }

    /** A title that merely contains the other, mid-phrase, is not the other. */
    @Test
    fun midPhraseMatchesDoNotCorrespond() {
        val track = shazam("Crush", "Yuna")
        assertFalse(corresponds(track, youtube("Instant Crush", "Yuna")))
    }

    /** No artist means nothing to check the recording against, so it goes to the user. */
    @Test
    fun aMissingArtistIsNeverCertain() {
        assertFalse(corresponds(shazam("Blinding Lights", null), youtube("Blinding Lights", "The Weeknd")))
        assertFalse(corresponds(shazam("Blinding Lights", ""), youtube("Blinding Lights", "The Weeknd")))
    }
}
