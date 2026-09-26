/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
