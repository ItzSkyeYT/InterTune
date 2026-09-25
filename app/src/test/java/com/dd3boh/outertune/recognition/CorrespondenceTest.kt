/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
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

    /** A whole DJ mix filed as one track is not the song; a mix of one song is. */
    @Test
    fun aContinuousMixIsNotASong() {
        listOf(
            "Nils van Zandt Hitmix 2K16 (Mixed By Joost XXL) [Continious Mix]",
            "Ministry of Sound Anthems (Continuous Mix)",
            "Summer Megamix 2015",
            "Ibiza Non-Stop Mix",
            "Yearmix 2016",
        ).forEach { assertTrue(it, isDjMix(it)) }
        listOf(
            "What About Us (Radio Mix)",
            "Party Crasher (feat. Mayra Veronica) [Original Extended Mix]",
            "Nobody To Love (Mixed)",
            "Lean On (Averez Remix)",
            "Club Mix",
            "Mixed Emotions",
        ).forEach { assertFalse(it, isDjMix(it)) }
    }

    /** One track as Shazam places it, [offsetSeconds] into the reference recording. */
    private fun heard(offsetSeconds: Double, key: String? = "k", title: String = "Children") = Recognised(
        title = title,
        artist = "Robert Miles",
        artworkUrl = null,
        isrc = null,
        shazamKey = key,
        offsetSeconds = offsetSeconds,
    )

    /**
     * Two listens a window apart still tell an edit from the original. Pinned next to the floor
     * below, so the floor cannot creep up into the rates real edits play at.
     */
    @Test
    fun twoListensAWindowApartMeasureTheRate() {
        assertEquals(PlaybackVariant.ORIGINAL, PlaybackVariant.between(heard(60.0), heard(72.0), 12.0))
        assertEquals("0.8, slowed", PlaybackVariant.SLOWER, PlaybackVariant.between(heard(60.0), heard(69.6), 12.0))
        assertEquals("1.25, sped up", PlaybackVariant.FASTER, PlaybackVariant.between(heard(60.0), heard(75.0), 12.0))
    }

    /**
     * The same track heard twenty minutes apart is not a slowed edit.
     *
     * Twenty minutes of wall clock against a couple of minutes of offset is a rate near zero, and
     * with a ceiling but no floor that read as SLOWER, which chose the slowed upload over the song.
     */
    @Test
    fun listensFarApartSayNothingAboutTheRate() {
        assertEquals(PlaybackVariant.ORIGINAL, PlaybackVariant.between(heard(30.0), heard(200.0), 1200.0))
    }

    /** A first sighting is confirmable for a few windows, including across one failed request. */
    @Test
    fun aFirstSightingExpires() {
        val lifetime = 3 * 12 * 1000L
        assertTrue("the next window", isSecondListen(heard(60.0), 0L, heard(73.0), 13_000L, lifetime))
        assertTrue("one failed window between", isSecondListen(heard(60.0), 0L, heard(86.0), 26_000L, lifetime))
        assertFalse("twenty minutes later", isSecondListen(heard(60.0), 0L, heard(90.0), 1_200_000L, lifetime))
    }

    /** No key means nothing to tell one track from another by, so it never confirms anything. */
    @Test
    fun aMissingKeyNeverConfirms() {
        val lifetime = 3 * 12 * 1000L
        assertFalse(isSecondListen(heard(60.0, key = null), 0L, heard(72.0, key = null), 12_000L, lifetime))
        assertFalse(isSecondListen(heard(60.0, key = "a"), 0L, heard(72.0, key = null), 12_000L, lifetime))
        assertFalse(isSecondListen(heard(60.0, key = null), 0L, heard(72.0, key = "a"), 12_000L, lifetime))
        assertFalse(isSecondListen(heard(60.0, key = "a"), 0L, heard(72.0, key = "b", title = "Fable"), 12_000L, lifetime))
    }

    /**
     * Shazam knows some recordings under several entries and flips between them. The same song
     * carrying on along its timeline under another entry is the second listen; a remix of it, which
     * lands somewhere else, is not.
     */
    @Test
    fun oneRecordingUnderAnotherEntryIsTheSecondListen() {
        val lifetime = 3 * 12 * 1000L
        assertTrue(isSecondListen(heard(60.0, key = "a"), 0L, heard(72.4, key = "b", title = "Children (Dream Version)"), 12_000L, lifetime))
        assertFalse("a remix elsewhere in it", isSecondListen(heard(60.0, key = "a"), 0L, heard(80.0, key = "b", title = "Children (Remix)"), 12_000L, lifetime))
        assertFalse("too late", isSecondListen(heard(60.0, key = "a"), 0L, heard(1260.0, key = "b"), 1_200_000L, lifetime))
    }
}
