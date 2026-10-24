/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.utils.artSizeBucket
import com.dd3boh.outertune.ui.utils.coverSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover handed to the system: its media player in the shade and on the lock screen, the
 * notification, a watch, a car. Most songs are stored with the 120 pixel thumbnail of the list they
 * were first seen in, and the system used to be given exactly that to stretch across its player.
 */
class SessionArtworkTest {

    private val stored = "https://yt3.googleusercontent.com/AbC-dEf_123=w120-h120-l90-rj"
    private val sized = "https://yt3.googleusercontent.com/AbC-dEf_123=w1024-h1024-l90-rj"

    @Test
    fun `a cover stored as a thumbnail is asked for at the size the system keeps`() {
        // 900 pixels on a 1080 wide screen, which is asked for as 1024
        assertEquals(sized, sessionArtwork(stored, 900).first())
        assertEquals("https://yt3.googleusercontent.com/AbC-dEf_123=w1280-h1280-l90-rj", sessionArtwork(stored, 1200).first())
        assertEquals(sized, sessionArtwork(stored, 1024).first())
    }

    @Test
    fun `what the song is stored with is the second try`() {
        // with no connection the large one cannot be fetched, and the small one may be on the phone already
        assertEquals(listOf(sized, stored), sessionArtwork(stored, 900))
    }

    @Test
    fun `a cover already at that size is tried once`() {
        assertEquals(listOf(sized), sessionArtwork(sized, 900))
    }

    @Test
    fun `a cover stored larger than the system keeps is asked for smaller`() {
        val large = "https://lh3.googleusercontent.com/XyZ=w2000-h2000-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/XyZ=w1024-h1024-l90-rj", sessionArtwork(large, 900).first())
    }

    @Test
    fun `what follows the size is kept as it is`() {
        val cropped = "https://yt3.googleusercontent.com/AbC=w60-h60-s-l90-rj"
        assertEquals("https://yt3.googleusercontent.com/AbC=w768-h768-s-l90-rj", sessionArtwork(cropped, 640).first())
    }

    @Test
    fun `the older host is asked its own way`() {
        val old = "https://yt3.ggpht.com/AbC=s120"
        assertEquals(listOf("$old-s1024", old), sessionArtwork(old, 900))
    }

    @Test
    fun `a video still, a file and anything else is tried as it is`() {
        for (other in listOf(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCghqEJQEGHgg6AJIWg&rs=AMzJL3k",
            "/storage/emulated/0/Music/song.flac",
            "content://media/external/audio/albumart/12",
            "",
        )) {
            assertEquals(other, listOf(other), sessionArtwork(other, 900))
        }
    }

    @Test
    fun `it is the address the player asks for, so one download serves both`() {
        val playing = MediaMetadata(id = "dQw4w9WgXcQ", title = "Song", artists = emptyList(), duration = 213, thumbnailUrl = stored, genre = null)
        // 1080 wide at 450 dpi is 384 dp: the system keeps 320 dp, and the player draws its cover in
        // the width less 32 dp on each side, which is the same 900 pixels
        val drawn = artSizeBucket(1080 - 2 * 90)
        assertEquals(playing.getThumbnailModel(drawn, drawn), sessionArtwork(stored, sessionArtPx(2.8125f)).first())
        // 1440 wide at 600 dpi: 1200 for both
        val drawnLarge = artSizeBucket(1440 - 2 * 120)
        assertEquals(playing.getThumbnailModel(drawnLarge, drawnLarge), sessionArtwork(stored, sessionArtPx(3.75f)).first())
    }

    @Test
    fun `the system keeps 320 dp of a cover`() {
        assertEquals(900, sessionArtPx(2.8125f))     // 1080 pixels wide at 450 dpi
        assertEquals(1200, sessionArtPx(3.75f))      // 1440 wide at 600 dpi
        assertEquals(960, sessionArtPx(3f))
        assertEquals(840, sessionArtPx(2.625f))
        assertEquals(320, sessionArtPx(1f))
    }

    @Test
    fun `covers are asked for in steps, so two sizes close together are one download`() {
        assertEquals(1280, artSizeBucket(1152))      // the player upright
        assertEquals(1280, artSizeBucket(1248))      // and on its side
        assertEquals(1024, artSizeBucket(900))       // what the system keeps on a 1080 wide screen, and the player draws
        assertEquals(1024, artSizeBucket(960))
        assertEquals(1024, artSizeBucket(1024))
        assertEquals(1280, artSizeBucket(1025))
        assertEquals(256, artSizeBucket(1))
    }

    @Test
    fun `asked for by its large address, a cover is the one already in hand`() {
        // which is how the session names it once the large cover has taken the small one's place
        val first = SessionCover(sessionArtwork(stored, 900))
        val renamed = SessionCover(sessionArtwork(sized, 900))
        assertEquals(first, renamed)
        assertEquals(first.hashCode(), renamed.hashCode())
        assertEquals(sized, first.sharp)
        assertEquals(stored, first.stored)
    }

    @Test
    fun `two songs are two covers`() {
        val other = "https://yt3.googleusercontent.com/Other-123=w120-h120-l90-rj"
        assertNotEquals(SessionCover(sessionArtwork(stored, 900)), SessionCover(sessionArtwork(other, 900)))
    }

    @Test
    fun `a cover with one address has nothing lesser to hand out first`() {
        for (single in listOf(sized, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "/storage/emulated/0/Music/song.flac")) {
            val cover = SessionCover(sessionArtwork(single, 900))
            assertEquals(single, cover.sharp, cover.stored)
        }
    }

    // When the large cover is not on the phone. Offline, a cover that was not on the phone at the
    // size it was stored with gave the system the app's logo, though the app's own screens had
    // fetched it at four other sizes.

    @Test
    fun `offline, the cover is looked for at every size the app's own screens ask for, largest first`() {
        // 450 dpi: the grids at 96 dp are 270 pixels, the mini player and the lists at 48 dp 135,
        // the player's underlay 128, the colours 100
        assertEquals(
            listOf(270, 135, 128, 120, 100).map { "https://yt3.googleusercontent.com/AbC-dEf_123=w$it-h$it-l90-rj" },
            coverOnPhone(stored, sized, 2.8125f),
        )
    }

    @Test
    fun `a cover stored at a larger size is the best of them`() {
        val at544 = "https://lh3.googleusercontent.com/XyZ=w544-h544-l90-rj"
        val onPhone = coverOnPhone(at544, "https://lh3.googleusercontent.com/XyZ=w1024-h1024-l90-rj", 3f)
        assertEquals(at544, onPhone.first())
        assertEquals(listOf(544, 288, 144, 128, 100), onPhone.map(::coverSide))
    }

    @Test
    fun `the large one is never among them`() {
        // asked for first, and on its own
        for (density in listOf(1f, 2.625f, 2.8125f, 3f, 3.75f, 10.7f)) {
            val large = sessionArtwork(stored, sessionArtPx(density)).first()
            assertFalse(large in coverOnPhone(stored, large, density))
        }
    }

    @Test
    fun `the older host is looked for its own way`() {
        val old = "https://yt3.ggpht.com/AbC=s120"
        assertEquals(listOf("$old-s270", "$old-s135", "$old-s128", old, "$old-s100"), coverOnPhone(old, "$old-s1024", 2.8125f))
    }

    @Test
    fun `a cover with one address has nowhere else to be`() {
        for (single in listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "/storage/emulated/0/Music/song.flac", "")) {
            assertEquals(single, emptyList<String>(), coverOnPhone(single, single, 2.8125f))
        }
    }

    @Test
    fun `the side of a cover is read off its address`() {
        assertEquals(120, coverSide(stored))
        assertEquals(1024, coverSide(sized))
        assertEquals(60, coverSide("https://yt3.googleusercontent.com/AbC=w60-h60-s-l90-rj"))
        assertEquals(120, coverSide("https://yt3.ggpht.com/AbC=s120"))
        assertEquals(1024, coverSide("https://yt3.ggpht.com/AbC=s120-s1024"))
        assertEquals(0, coverSide("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg"))
    }

    // A better cover in the middle of a song. media3 hands the session a new picture only when the
    // song's metadata changes, so the session names the cover anew each time a better one is in
    // hand (see SessionPlayer). A cover with one address, a video still or a file, could not be
    // named anew at all, and kept the stand-in to the end of the song.

    @Test
    fun `a cover is named by its large address, and by a mark on it when that is its name already`() {
        assertEquals(sized, nextCoverName(current = stored, sharp = sized, n = 1))
        assertEquals("$sized#cover-2", nextCoverName(current = sized, sharp = sized, n = 2))
        assertEquals(sized, nextCoverName(current = "$sized#cover-2", sharp = sized, n = 3))
        val still = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg"
        assertEquals("$still#cover-1", nextCoverName(current = still, sharp = still, n = 1))
    }

    @Test
    fun `asked for by a marked name, a cover is the one already in hand`() {
        val first = SessionCover(sessionArtwork(stored, 900))
        val marked = SessionCover(sessionArtwork(unmarkedCover(markedCover(sized, 7)), 900))
        assertEquals(first, marked)
        val still = "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCghqEJQEGHgg6AJIWg&rs=AMzJL3k"
        assertEquals(still, unmarkedCover(markedCover(still, 1)))
        assertEquals(SessionCover(sessionArtwork(still, 900)), SessionCover(sessionArtwork(unmarkedCover(markedCover(still, 12)), 900)))
    }

    @Test
    fun `only the mark is read past`() {
        for (address in listOf(stored, "/storage/emulated/0/Music/Song #1.mp3", "/storage/emulated/0/Music/a#cover.mp3", "https://example.com/a.jpg#top")) {
            assertEquals(address, unmarkedCover(address))
        }
    }

    // How hard the cover is tried for, offline or on a poor connection.

    @Test
    fun `a cover that could not be had is tried for again within seconds, then less often, a few times a song`() {
        val waits = COVER_RETRY_DELAYS_MS
        assertTrue("the first try again comes soon: $waits", waits.first() <= 5_000)
        assertEquals("never sooner than the one before", waits.sorted(), waits)
        assertTrue("a handful, not a stream: $waits", waits.size in 3..6)
        assertTrue("all of them within a few minutes: $waits", waits.sum() <= 5 * 60_000)
    }

    @Test
    fun `a picture in a file is decoded no larger than it is shown`() {
        assertEquals(1, sampleSize(900, 900, 900))
        assertEquals(1, sampleSize(1000, 1000, 900))
        assertEquals(2, sampleSize(1800, 1800, 900))
        assertEquals(2, sampleSize(3000, 3000, 900))
        assertEquals(4, sampleSize(3600, 4000, 900))
        assertEquals("the shorter side decides", 1, sampleSize(4000, 1000, 900))
        assertEquals(1, sampleSize(120, 120, 900))
        assertEquals(1, sampleSize(0, 0, 900))
    }
}
