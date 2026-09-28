/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsViewModelTest {
    private val greatestHits = Album(
        AlbumEntity(id = "MPREb_queen", title = "Greatest Hits", thumbnailUrl = "cover", songCount = 17, duration = 3600),
        downloadCount = 0,
        artists = listOf(ArtistEntity(id = "UCqueen", name = "Queen")),
    )

    @Test
    fun `a most played album with its songs and artist is not fetched`() {
        assertFalse(albumPageWanted(greatestHits))
    }

    @Test
    fun `one with no songs is fetched, as before`() {
        assertTrue(albumPageWanted(greatestHits.copy(album = greatestHits.album.copy(songCount = 0))))
    }

    @Test
    fun `one with no artist is fetched, so Stats lists it with one`() {
        // Made from songs, as LgAlbumRepair makes most of them.
        assertTrue(albumPageWanted(greatestHits.copy(artists = emptyList())))
    }

    @Test
    fun `a local album is never fetched, having no page`() {
        // It never has an artist either, and YouTube answers its id with an HTTP 400.
        val tapes = greatestHits.copy(album = greatestHits.album.copy(id = "LBaaaaaaaa", isLocal = true), artists = emptyList())
        assertFalse(albumPageWanted(tapes))
        assertFalse(albumPageWanted(tapes.copy(album = tapes.album.copy(songCount = 0))))
    }

    @Test
    fun `an album YouTube no longer has is deleted only when it has no songs`() {
        assertTrue(albumDeletedWhenGone(greatestHits.copy(album = greatestHits.album.copy(songCount = 0))))
        // One the repair made from songs keeps them together until someone opens it.
        assertFalse(albumDeletedWhenGone(greatestHits.copy(artists = emptyList())))
    }
}
