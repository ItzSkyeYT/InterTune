/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.utils.artSizeBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
