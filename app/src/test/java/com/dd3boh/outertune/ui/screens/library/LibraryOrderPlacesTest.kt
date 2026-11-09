/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Custom order on the Library page too (8 Nov 2026: the first place anyone looks for it): its
 * albums, artists and playlists dragged by their handles, in one order with the Playlists page.
 */
class LibraryOrderPlacesTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the Library page offers Custom order behind the same flag, read as Date added without it`() {
        assertTrue("CREATE_DATE, NAME, CUSTOM" in code("constants/Settings.kt").substringAfter("enum class LibrarySortType").substringBefore("}"))
        val screen = code("ui/screens/library/LibraryScreen.kt")
        assertTrue("offered = { it != LibrarySortType.CUSTOM || Unreleased.PLAYLIST_ORDER }" in screen)
        assertTrue("if (storedSortType == LibrarySortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) LibrarySortType.CREATE_DATE" in screen)
        assertTrue("LibrarySortType.CUSTOM -> R.string.sort_by_custom" in screen)
        assertTrue("if (sort == LibrarySortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) LibrarySortType.CREATE_DATE" in code("viewmodels/LibraryViewModels.kt"))
    }

    @Test
    fun `everything there is dragged by its handle, one to a line, under the Playlists page's lock`() {
        val screen = code("ui/screens/library/LibraryScreen.kt")
        assertTrue("val canDrag = customOrder && !orderLocked" in screen)
        assertTrue("rememberPreference(PlaylistsOrderLockKey, defaultValue = false)" in screen)
        assertTrue("R.string.playlist_order_locked else R.string.playlist_order_unlocked" in screen)
        assertTrue("} else if (canDrag && columns == 1) {" in screen)
        assertTrue("ReorderableItem(state = reorderableListState, key = libraryKey(item))" in screen)
        assertTrue("ReorderableItem(state = reorderableGridState, key = libraryKey(item))" in screen)
        assertTrue("moved by key, not place", "dragged.indexOfFirst { libraryKey(it) == fromKey }" in screen)
        assertTrue("the order kept when a drag ends", "viewModel.saveCustomOrder(shown.map { it.toString() })" in screen)
        val items = code("ui/component/Library.kt")
        for (name in listOf("LibraryArtistListItem", "LibraryArtistGridItem", "LibraryAlbumListItem", "LibraryAlbumGridItem", "LibraryPlaylistListItem", "LibraryPlaylistGridItem")) {
            assertTrue(name, "dragHandle: Modifier? = null" in items.substringAfter("fun $name(").substringBefore("\n)"))
        }
    }

    @Test
    fun `one order for the Library page, the Playlists page and Add to playlist`() {
        val models = code("viewmodels/LibraryViewModels.kt")
        assertTrue("LibraryOrder.playlistIds(LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey]))" in models)
        assertTrue("if (sortType == LibrarySortType.CUSTOM) LibraryOrder.saved(it[LibraryCustomOrderKey], it[PlaylistCustomOrderKey])" in models)
        assertTrue("a drag under Playlists keeps the albums and artists in place", "LibraryOrder.refill(saved, ids.map { id -> LibraryOrder.PLAYLIST + id })" in models)
        assertTrue("it[LibraryCustomOrderKey] = PlaylistOrder.encode(keys)" in models)
        assertTrue("PlaylistOrder.apply(it, LibraryOrder.playlistIds(LibraryOrder.saved(libraryOrder, customOrder)))" in code("ui/dialog/AddToPlaylistDialog.kt"))
    }
}
