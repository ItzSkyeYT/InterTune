/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.Screens.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the Songs, Albums and Playlists launcher shortcuts open, for the tabs the bar has.
 *
 * Since upstream 25d504fe1 they only set the Library filter and opened on the default tab. What
 * MainActivity does with the answer, write the filter once and then go there, needs a device.
 */
class LibraryShortcutTest {

    @Test
    fun `with the default bar, Songs has its tab and the others open Library on their filter`() {
        // Home, Songs, Folders and Library.
        val bar = Screens.getScreens(DEFAULT_ENABLED_TABS)
        assertEquals(LibraryShortcut(Screens.Songs, null), libraryShortcut(MainActivity.ACTION_SONGS, bar))
        assertEquals(LibraryShortcut(Screens.Library, LibraryFilter.ALBUMS), libraryShortcut(MainActivity.ACTION_ALBUMS, bar))
        assertEquals(LibraryShortcut(Screens.Library, LibraryFilter.PLAYLISTS), libraryShortcut(MainActivity.ACTION_PLAYLISTS, bar))
    }

    @Test
    fun `a tab of its own wins over Library, and with no tab and no Library it is still Library`() {
        val everyTab = Screens.getScreens("HSFABLM")
        assertEquals(LibraryShortcut(Screens.Albums, null), libraryShortcut(MainActivity.ACTION_ALBUMS, everyTab))
        assertEquals(LibraryShortcut(Screens.Playlists, null), libraryShortcut(MainActivity.ACTION_PLAYLISTS, everyTab))
        // Library is a destination whether or not the bar shows it, and it can show the filter.
        assertEquals(LibraryShortcut(Screens.Library, LibraryFilter.SONGS), libraryShortcut(MainActivity.ACTION_SONGS, Screens.getScreens("HF")))
    }

    @Test
    fun `every other action is not a library shortcut`() {
        val bar = Screens.getScreens(DEFAULT_ENABLED_TABS)
        assertNull(libraryShortcut(MainActivity.ACTION_SEARCH, bar))
        assertNull(libraryShortcut(MainActivity.ACTION_PLAY_LIKED, bar))
        assertNull(libraryShortcut("android.intent.action.MAIN", bar))
        assertNull(libraryShortcut(null, bar))
    }
}
