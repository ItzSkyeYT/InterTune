/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a song of the queue becomes a line of the TV's "Up next" shelf. */
class CastUpNextTest {
    private fun song(title: String, artists: List<String> = listOf("Band"), thumbnail: String? = null) = MediaMetadata(
        id = title,
        title = title,
        artists = artists.map { MediaMetadata.Artist(null, it) },
        duration = 200,
        thumbnailUrl = thumbnail,
        genre = null,
    )

    @Test
    fun `a song is its title, its artists and a small cover`() {
        val next = castNext(song("Sea of Lanterns", listOf("The Paper Moons", "Guest"), "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"))!!
        assertEquals("Sea of Lanterns", next.title)
        assertEquals("The Paper Moons, Guest", next.artist)
        assertEquals("https://lh3.googleusercontent.com/abc=w256-h256-l90-rj", next.cover)
    }

    @Test
    fun `a song with no artist or cover is just its title, and a cover of a file on the phone is left out`() {
        val bare = castNext(song("Green Hours", emptyList(), null))!!
        assertNull(bare.artist)
        assertNull(bare.cover)
        assertNull(castNext(song("Local", thumbnail = "/storage/emulated/0/Music/cover.jpg"))!!.cover)
    }

    @Test
    fun `a song with no title, or no information, is not listed`() {
        assertNull(castNext(song("  ")))
        assertNull(castNext(null))
    }

    @Test
    fun `the shelf's covers are small`() {
        assertEquals(256, CAST_SHELF_ART_PX)
    }
}
