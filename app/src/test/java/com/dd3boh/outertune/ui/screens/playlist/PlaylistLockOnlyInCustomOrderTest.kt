/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lock beside the sort order says whether the rows can be dragged, which they can only in the order of your own.
 * On a playlist's page it showed with any order picked, and could be tapped to no effect (a tester, 15 Nov 2026); the
 * Library's artists, albums and playlists already hid it unless Custom order was picked. This reads the source, as the
 * Library's tests do.
 */
class PlaylistLockOnlyInCustomOrderTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `a playlist's page shows the lock only with Custom order picked`() {
        val page = code("ui/screens/playlist/LocalPlaylistScreen.kt")
        assertTrue("if (editable && sortType == PlaylistSongSortType.CUSTOM && !(inSelectMode || isSearching)) {" in page)
        // the button is the one under that condition, and nothing else shows it
        val lock = page.substringAfter("if (editable && sortType == PlaylistSongSortType.CUSTOM && !(inSelectMode || isSearching)) {").substringBefore("Icons.Rounded.LockOpen")
        assertTrue("onClick = { locked = !locked }" in lock)
        assertTrue(page.split("Icons.Rounded.LockOpen").size == 2)
        // what the lock holds is the same order: the drag handle
        assertTrue("dragHandleModifier = if (sortType == PlaylistSongSortType.CUSTOM && !locked" in page)
    }

    @Test
    fun `the Library's lists show theirs only with Custom order picked, as before`() {
        for (path in listOf("LibraryArtistsScreen", "LibraryAlbumsScreen", "LibraryPlaylistsScreen", "LibraryScreen")) {
            val screen = code("ui/screens/library/$path.kt")
            assertTrue("$path: customOrder is Custom order picked", "val customOrder = Unreleased.PLAYLIST_ORDER && sortType ==" in screen)
            val before = screen.substringBefore("Icons.Rounded.LockOpen")
            assertTrue("$path: the lock sits under if (customOrder)", "if (customOrder) {" in before.takeLast(600))
        }
    }
}
