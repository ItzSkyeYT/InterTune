/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Two things seen on the Pixel 5 on 5 Nov 2026, in the Songs tab and its selection. */
class SongsTabWordsTest {
    @Test
    fun `an empty Songs tab says which list is empty`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/library/LibrarySongsScreen.kt").readText()
        // Under Liked it said "Library songs will show up here", as if the library were empty.
        assertTrue("SongFilter.LIKED -> R.string.library_song_empty_liked" in screen)
        assertTrue("SongFilter.DOWNLOADED -> R.string.library_song_empty_downloaded" in screen)
        assertTrue("SongFilter.LIBRARY -> R.string.library_song_empty" in screen)
        for (folder in listOf("values", "values-fr")) {
            val words = File("src/main/res/$folder/strings-ot.xml").readText()
            assertTrue(folder, "<string name=\"library_song_empty_liked\" translatable=\"false\">" in words)
            assertTrue(folder, "<string name=\"library_song_empty_downloaded\" translatable=\"false\">" in words)
        }
    }

    @Test
    fun `removing a selection from the library closes the sheet and the selection, as the other removals do`() {
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/SelectionSongsMenu.kt").readText()
        val remove = menu.substringAfter("title = R.string.remove_all_from_library").substringBefore("} else {")
        assertTrue("toggleInLibrary(song.id, null)" in remove)
        assertTrue("the sheet stayed open over a 0/0 selected bar", remove.indexOf("onDismiss()") > remove.indexOf("toggleInLibrary"))
        assertTrue("clearAction()" in remove)
        // And the tab leaves selection mode once its list is empty: clearAction only deselects.
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/library/LibrarySongsScreen.kt").readText()
        assertTrue("if (inSelectMode && songs?.isEmpty() == true) onExitSelectionMode()" in screen)
    }
}
