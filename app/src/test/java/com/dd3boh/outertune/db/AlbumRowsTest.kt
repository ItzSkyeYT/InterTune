/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumRowsTest {
    private val savedQueen = AlbumEntity(id = "MPREb_queen", title = "Greatest Hits", songCount = 17, duration = 3600)
    private val localHits = AlbumEntity(id = "LBaaaaaaaa", title = "Greatest Hits", songCount = 3, duration = 600, isLocal = true)

    private fun neverAsked(title: String): AlbumEntity? = throw AssertionError("looked up $title by title")

    @Test
    fun `a YouTube album is found by its id`() {
        val album = MediaMetadata.Album(id = "MPREb_queen", title = "Greatest Hits")
        assertSame(savedQueen, AlbumRows.storedAlbumFor(album, savedQueen, ::neverAsked))
    }

    @Test
    fun `a YouTube album not stored is new, whatever else shares its title`() {
        // Journey's Greatest Hits, with Queen's saved: it went into Queen's.
        val album = MediaMetadata.Album(id = "MPREb_journey", title = "Greatest Hits")
        assertNull(AlbumRows.storedAlbumFor(album, null, ::neverAsked))
    }

    @Test
    fun `a local song finds its album by title`() {
        // The scanner gives each song a new album id, so the id never matches.
        val album = MediaMetadata.Album(id = "LBbbbbbbbb", title = "Greatest Hits", isLocal = true)
        assertSame(localHits, AlbumRows.storedAlbumFor(album, null) { title ->
            assertEquals("Greatest Hits", title)
            localHits
        })
    }

    @Test
    fun `a stored album with every song of the page is left alone`() {
        assertFalse(AlbumRows.pageAddsSongs(savedQueen, listOf("a", "b", "c"), listOf("a", "b", "c")))
        // One recorded from a play that is not on the page does not make it write again and again.
        assertFalse(AlbumRows.pageAddsSongs(savedQueen, listOf("a", "b", "c", "video"), listOf("a", "b", "c")))
    }

    @Test
    fun `an album not stored is written`() {
        assertTrue(AlbumRows.pageAddsSongs(null, emptyList(), listOf("a")))
    }

    @Test
    fun `a saved album with only the song played from it is filled`() {
        val playedOnce = savedQueen.copy(songCount = 1)
        assertTrue(AlbumRows.pageAddsSongs(playedOnce, listOf("b"), listOf("a", "b", "c")))
    }

    @Test
    fun `a saved album with no songs is written, as before`() {
        val saved = savedQueen.copy(songCount = 0)
        assertTrue(AlbumRows.pageAddsSongs(saved, emptyList(), listOf("a", "b")))
        assertTrue(AlbumRows.pageAddsSongs(saved, emptyList(), emptyList()))
    }

    @Test
    fun `an album with no artist is written for the one its page names`() {
        // A single made from its song: the page adds no song, so the artist was never written.
        val single = savedQueen.copy(songCount = 1)
        assertFalse(AlbumRows.pageAddsSongs(single, listOf("a"), listOf("a")))
        assertTrue(AlbumRows.pageAddsArtist(emptyList(), listOf("Queen")))
    }

    @Test
    fun `an album with an artist is not written for it`() {
        assertFalse(AlbumRows.pageAddsArtist(listOf("UCqueen"), listOf("Queen")))
    }

    @Test
    fun `a page naming no artist is not written for one, or every opening would write it`() {
        assertFalse(AlbumRows.pageAddsArtist(emptyList(), emptyList()))
    }
}
