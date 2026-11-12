/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.models.LibraryOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Albums and artists in an order of your own too (the 0.13 roadmap, 9 Nov 2026): Custom order in Library > Albums
 * and Library > Artists, dragged by handles as the playlists are, each page with its own lock. The order is the
 * library's one order (LibraryOrder), which the Library page shows whole: dragging an album here moves it there.
 */
class AlbumArtistOrderPlacesTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private data class Page(val kind: String, val file: String, val sort: String, val filter: String, val row: String, val lock: String)

    private val pages = listOf(
        Page("album", "ui/screens/library/LibraryAlbumsScreen.kt", "AlbumSortType", "AlbumFilter", "album", "AlbumsOrderLockKey"),
        Page("artist", "ui/screens/library/LibraryArtistsScreen.kt", "ArtistSortType", "ArtistFilter", "artist", "ArtistsOrderLockKey"),
    )

    @Test
    fun `the library's one order names each album and artist by the key the Library page uses`() {
        val saved = listOf("playlist:p1", "album:a2", "artist:r1", "album:a1", "artist:r2")
        assertEquals(listOf("a2", "a1"), LibraryOrder.idsOf(saved, LibraryOrder.ALBUM))
        assertEquals(listOf("r1", "r2"), LibraryOrder.idsOf(saved, LibraryOrder.ARTIST))
        assertEquals(listOf("p1"), LibraryOrder.playlistIds(saved))
        // a drag on the Albums page: the albums take the albums' places, the rest stays where it was
        val whole = LibraryOrder.refill(saved, listOf("album:a1", "album:a2")) { it.startsWith(LibraryOrder.ALBUM) }
        assertEquals(listOf("playlist:p1", "album:a1", "artist:r1", "album:a2", "artist:r2"), whole)
        assertEquals("album:", LibraryOrder.ALBUM)
        assertEquals("artist:", LibraryOrder.ARTIST)
        val keys = code("viewmodels/LibraryViewModels.kt")
        assertTrue("is Album -> \"album:\${item.id}\"" in keys && "is Artist -> \"artist:\${item.id}\"" in keys)
    }

    @Test
    fun `Custom order is offered only behind the playlists' flag, and a stored one reads as Date added without it`() {
        val models = code("viewmodels/LibraryViewModels.kt")
        for (p in pages) {
            val screen = code(p.file)
            assertTrue(p.file, "offered = { it != ${p.sort}.CUSTOM || Unreleased.PLAYLIST_ORDER }" in screen)
            assertTrue(p.file, "if (storedSortType == ${p.sort}.CUSTOM && !Unreleased.PLAYLIST_ORDER) ${p.sort}.CREATE_DATE" in screen)
            assertTrue(p.file, "${p.sort}.CUSTOM -> R.string.sort_by_custom" in screen)
            assertTrue(p.kind, "if (sort == ${p.sort}.CUSTOM && !Unreleased.PLAYLIST_ORDER) ${p.sort}.CREATE_DATE" in models)
        }
        assertTrue("CREATE_DATE, NAME, ARTIST, YEAR, SONG_COUNT, LENGTH, CUSTOM" in code("constants/Settings.kt"))
        assertTrue("CREATE_DATE, NAME, SONG_COUNT, CUSTOM\n}\n\nenum class ArtistSongSortType" in code("constants/Settings.kt"))
        assertTrue("AlbumSortType.CUSTOM -> \"album.rowId ASC\"" in code("db/daos/AlbumsDao.kt"))
        assertTrue("ArtistSortType.CUSTOM -> \"artist.rowId ASC\"" in code("db/LibrarySql.kt"))
    }

    @Test
    fun `rows are dragged only in Custom order, unlocked, over the liked ones the Library page shows, one to a line`() {
        for (p in pages) {
            val screen = code(p.file)
            assertTrue(p.file, "val canDrag = customOrder && !orderLocked && filter == ${p.filter}.LIKED" in screen)
            assertTrue(p.file, "if (canDrag && columns == 1) {" in screen)
            assertTrue(p.file, "ReorderableItem(state = reorderableListState, key = ${p.row}.id)" in screen)
            assertTrue(p.file, "ReorderableItem(state = reorderableGridState, key = ${p.row}.id)" in screen)
            assertTrue(p.file, "dragged.indexOfFirst { it.id == fromKey }" in screen)
            assertTrue(p.file, "viewModel.saveCustomOrder(shown)" in screen)
            assertTrue(p.file, "rememberPreference(${p.lock}, defaultValue = false)" in screen)
            assertTrue(p.file, "R.string.playlist_order_locked else R.string.playlist_order_unlocked" in screen)
        }
    }

    @Test
    fun `each page keeps its part of the one order and leaves the rest where it was`() {
        val models = code("viewmodels/LibraryViewModels.kt")
        for (p in pages) {
            val prefix = if (p.kind == "album") "LibraryOrder.ALBUM" else "LibraryOrder.ARTIST"
            assertTrue(p.kind, "LibraryOrder.idsOf(LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey]), $prefix)" in models)
            assertTrue(p.kind, "LibraryOrder.refill(saved, ids.map { id -> $prefix + id }) { key -> key.startsWith($prefix) }" in models)
        }
    }
}
