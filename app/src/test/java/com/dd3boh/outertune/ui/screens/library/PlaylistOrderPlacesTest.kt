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
 * Playlists in an order of your own (a listener's ask, 8 Nov 2026): Custom order in Library >
 * Playlists, dragged by handles as a playlist's songs are, with the same lock and words.
 */
class PlaylistOrderPlacesTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `Custom order is offered only behind its flag, and a stored one reads as Date added without it`() {
        assertTrue("val PLAYLIST_ORDER = BuildConfig.DEBUG" in code("constants/Unreleased.kt"))
        for (path in listOf("ui/screens/library/LibraryPlaylistsScreen.kt", "ui/dialog/AddToPlaylistDialog.kt")) {
            val screen = code(path)
            assertTrue(path, "offered = { it != PlaylistSortType.CUSTOM || Unreleased.PLAYLIST_ORDER }" in screen)
            assertTrue(path, "if (storedSortType == PlaylistSortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) PlaylistSortType.CREATE_DATE" in screen)
            assertTrue(path, "PlaylistSortType.CUSTOM -> R.string.sort_by_custom" in screen)
        }
        assertTrue("if (sort == PlaylistSortType.CUSTOM && !Unreleased.PLAYLIST_ORDER) PlaylistSortType.CREATE_DATE" in code("viewmodels/LibraryViewModels.kt"))
    }

    @Test
    fun `rows are dragged only in Custom order, unlocked, over the whole library, one to a line`() {
        val screen = code("ui/screens/library/LibraryPlaylistsScreen.kt")
        assertTrue("val canDrag = customOrder && !orderLocked && filter == PlaylistFilter.LIBRARY" in screen)
        assertTrue("is Playlist -> if (canDrag && columns == 1) {" in screen)
        assertTrue("ReorderableItem(state = reorderableListState, key = row.id)" in screen)
        assertTrue("ReorderableItem(state = reorderableGridState, key = playlist.id)" in screen)
        assertTrue("moved by key, not place", "dragged.indexOfFirst { it.id == fromKey }" in screen)
        assertTrue("the order kept when a drag ends", "viewModel.saveCustomOrder(shown)" in screen)
    }

    @Test
    fun `the lock and the words are the playlist's own`() {
        val screen = code("ui/screens/library/LibraryPlaylistsScreen.kt")
        assertTrue("R.string.playlist_order_locked else R.string.playlist_order_unlocked" in screen)
        assertTrue("rememberPreference(PlaylistsOrderLockKey, defaultValue = false)" in screen)
    }

    @Test
    fun `an order of your own has no way round, a playlist's songs and the library's playlists alike`() {
        assertTrue("if (sortType.name != \"CUSTOM\")" in code("ui/component/SortHeader.kt"))
        assertTrue("database.playlists(filter, sortType, false)" in code("viewmodels/LibraryViewModels.kt"))
    }

    @Test
    fun `Add to playlist lists them in the same order, and follows a sort changed there`() {
        val dialog = code("ui/dialog/AddToPlaylistDialog.kt")
        assertTrue("PlaylistOrder.apply(it, PlaylistOrder.decode(customOrder))" in dialog)
        assertTrue("LaunchedEffect(sortType, sortDescending, customOrder)" in dialog)
    }
}
