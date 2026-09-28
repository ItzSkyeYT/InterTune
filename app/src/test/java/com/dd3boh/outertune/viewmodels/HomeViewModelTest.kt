/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun `YouTube's own source always counts, whatever the fallback`() {
        assertTrue(ytRowOnScreenFor(QuickPicksSource.YOUTUBE, engineFallback = 0, poolNonEmpty = true))
        assertTrue(ytRowOnScreenFor(QuickPicksSource.YOUTUBE, engineFallback = 2, poolNonEmpty = true))
    }

    @Test
    fun `Try both's own drafted row is not mistaken for YouTube's`() {
        // engineFallback == 0: the engine has a row and draftCompareRow mixed it with the other
        // source, which HomeScreen never treats as YouTube's shelf on its own.
        assertFalse(ytRowOnScreenFor(QuickPicksSource.COMPARE, engineFallback = 0, poolNonEmpty = true))
    }

    @Test
    fun `Try both does not count YouTube's shelf while the library stands in`() {
        // engineFallback == 1: the library's row stands in, not YouTube's shelf, so a held YouTube
        // pool is not on screen.
        assertFalse(ytRowOnScreenFor(QuickPicksSource.COMPARE, engineFallback = 1, poolNonEmpty = true))
    }

    @Test
    fun `Try both does fall back to YouTube's shelf once the engine has fallen back that far`() {
        assertTrue(ytRowOnScreenFor(QuickPicksSource.COMPARE, engineFallback = 2, poolNonEmpty = true))
    }

    @Test
    fun `the engine source only shows YouTube's shelf at the last fallback step`() {
        assertFalse(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 0, poolNonEmpty = true))
        assertFalse(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 1, poolNonEmpty = true))
        assertTrue(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 2, poolNonEmpty = true))
    }

    @Test
    fun `an empty pool is never on screen, whatever the source says`() {
        assertFalse(ytRowOnScreenFor(QuickPicksSource.YOUTUBE, engineFallback = 0, poolNonEmpty = false))
        assertFalse(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 2, poolNonEmpty = false))
    }

    @Test
    fun `the row is off, never YouTube's`() {
        assertFalse(ytRowOnScreenFor(QuickPicksSource.OFF, engineFallback = 2, poolNonEmpty = true))
    }

    private val queen = ArtistEntity(id = "UCqueen", name = "Queen")
    private val greatestHits = Album(
        AlbumEntity(id = "MPREb_queen", title = "Greatest Hits", thumbnailUrl = "cover", songCount = 17, duration = 3600),
        downloadCount = 0,
        artists = listOf(queen),
    )

    @Test
    fun `Keep listening shows an album with a cover and an artist`() {
        assertTrue(keepListeningAlbum(greatestHits))
    }

    @Test
    fun `Keep listening leaves out an album with no artist yet`() {
        // Made from songs, as LgAlbumRepair makes most of them: a title and nothing under it.
        assertFalse(keepListeningAlbum(greatestHits.copy(artists = emptyList())))
    }

    @Test
    fun `Keep listening shows a local album, which never has an artist`() {
        // Only YouTube's pages give an album an artist, and a local album has no page.
        val tapes = greatestHits.copy(album = greatestHits.album.copy(id = "LBaaaaaaaa", isLocal = true), artists = emptyList())
        assertTrue(keepListeningAlbum(tapes))
        assertFalse(keepListeningAlbum(tapes.copy(album = tapes.album.copy(thumbnailUrl = null))))
    }

    @Test
    fun `Keep listening leaves out an album with no cover, as before`() {
        assertFalse(keepListeningAlbum(greatestHits.copy(album = greatestHits.album.copy(thumbnailUrl = null))))
    }
}
