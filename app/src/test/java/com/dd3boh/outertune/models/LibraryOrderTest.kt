/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom order on the Library page and under Playlists is one order: everything on the Library
 * page, of which the Playlists page shows the playlists.
 */
class LibraryOrderTest {

    private val isPlaylist: (String) -> Boolean = { it.startsWith(LibraryOrder.PLAYLIST) }

    @Test
    fun `a drag under Playlists moves the playlists and leaves the albums and artists where they were`() {
        val saved = listOf("playlist:1", "album:a", "playlist:2", "artist:r", "playlist:3")
        assertEquals(
            listOf("playlist:3", "album:a", "playlist:1", "artist:r", "playlist:2"),
            LibraryOrder.refill(saved, listOf("playlist:3", "playlist:1", "playlist:2"), isPlaylist)
        )
    }

    @Test
    fun `under Playlists the playlists read back exactly as they were dropped`() {
        val saved = listOf("playlist:1", "album:a", "playlist:2")
        // 9 was made since the last drag, and dropped between the other two.
        val whole = LibraryOrder.refill(saved, listOf("playlist:1", "playlist:9", "playlist:2"), isPlaylist)
        assertEquals(listOf("1", "9", "2"), LibraryOrder.playlistIds(whole))
        assertEquals("one made since takes a place at the front", listOf("playlist:1", "playlist:9", "album:a", "playlist:2"), whole)
    }

    @Test
    fun `a playlist deleted since leaves no hole`() {
        val saved = listOf("playlist:1", "album:a", "playlist:gone", "artist:r", "playlist:2")
        assertEquals(
            listOf("playlist:2", "album:a", "artist:r", "playlist:1"),
            LibraryOrder.refill(saved, listOf("playlist:2", "playlist:1"), isPlaylist)
        )
    }

    /**
     * 26 Nov 2026 (the whole-app scan): the lists dragged leave out a blocked artist and its albums,
     * and a drag threw them out of the order, so they came back at the top once unblocked.
     */
    @Test
    fun `an album or an artist not shown keeps its place through a drag, a playlist gone does not`() {
        val saved = listOf("album:a1", "album:blocked", "artist:r", "album:a2", "playlist:gone", "playlist:p")
        // The Albums page, the blocked artist's album left out.
        assertEquals(
            listOf("album:a2", "album:blocked", "artist:r", "album:a1", "playlist:gone", "playlist:p"),
            LibraryOrder.refill(saved, listOf("album:a2", "album:a1")) { it.startsWith(LibraryOrder.ALBUM) },
        )
        // The Library page, everything it shows: the album not shown stays, the playlist gone goes.
        assertEquals(
            listOf("playlist:p", "album:blocked", "artist:r", "album:a1", "album:a2"),
            LibraryOrder.refill(saved, listOf("playlist:p", "artist:r", "album:a1", "album:a2")) { true },
        )
        assertTrue(LibraryOrder.keepsPlace("album:x") && LibraryOrder.keepsPlace("artist:x") && !LibraryOrder.keepsPlace("playlist:x"))
    }

    @Test
    fun `the playlists' order of the first tester build starts the library's`() {
        assertEquals(listOf("playlist:b", "playlist:a"), LibraryOrder.saved(null, "b\na"))
        assertEquals(listOf("playlist:b", "playlist:a"), LibraryOrder.saved("", "b\na"))
        assertEquals("once the library's is kept, it wins", listOf("album:x"), LibraryOrder.saved("album:x", "b\na"))
        assertEquals(listOf("b", "a"), LibraryOrder.playlistIds(listOf("playlist:b", "album:x", "playlist:a")))
    }

    @Test
    fun `the Library page lists everything in the order kept, newest first before any drag`() {
        // Oldest first, as the Library page hands them over.
        val items = listOf("album:a", "playlist:1", "artist:r")
        assertEquals(listOf("artist:r", "album:a", "playlist:1"), PlaylistOrder.apply(items, listOf("artist:r", "album:a", "playlist:1")) { it })
        assertEquals(listOf("artist:r", "playlist:1", "album:a"), PlaylistOrder.apply(items, emptyList()) { it })
        // An album saved since the last drag comes first.
        assertEquals(listOf("album:a", "playlist:1", "artist:r"), PlaylistOrder.apply(items, listOf("playlist:1", "artist:r")) { it })
    }
}
