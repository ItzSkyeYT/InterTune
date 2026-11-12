/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Custom order on its side (9 Nov 2026): with the phone turned, or on a tablet, a list stands two rows abreast,
 * and rows two abreast cannot be dragged, so the handles were simply not there and nothing said why. While an
 * order can be changed (Custom order, unlocked) the list is one row to a line, handles and all, as upright;
 * locked, it goes back to two abreast.
 */
class CustomOrderSidewaysTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val pages = listOf(
        "ui/screens/library/LibraryScreen.kt",
        "ui/screens/library/LibraryPlaylistsScreen.kt",
        "ui/screens/library/LibraryAlbumsScreen.kt",
        "ui/screens/library/LibraryArtistsScreen.kt",
    )

    @Test
    fun `an order that can be changed is one row to a line, and a locked one as wide as the window allows`() {
        assertEquals(1, columnsWhileOrdering(columns = 2, canDrag = true))
        assertEquals(1, columnsWhileOrdering(columns = 3, canDrag = true))
        assertEquals(1, columnsWhileOrdering(columns = 1, canDrag = true))
        assertEquals(2, columnsWhileOrdering(columns = 2, canDrag = false))
        assertEquals(1, columnsWhileOrdering(columns = 1, canDrag = false))
    }

    @Test
    fun `every page with Custom order takes its columns through the rule, so its handles show on its side`() {
        for (file in pages) {
            val screen = code(file)
            val rule = screen.indexOf("val columns = columnsWhileOrdering(rememberListColumns(), canDrag)")
            assertTrue("$file: the columns go through columnsWhileOrdering", rule >= 0)
            assertTrue("$file: canDrag is known before the columns are", screen.indexOf("val canDrag = ") in 0 until rule)
            assertTrue("$file: no other column count", Regex("""val columns = rememberListColumns\(\)""").find(screen) == null)
            // the rows themselves still take the handles only one to a line, which the rule now gives them
            assertTrue("$file: the dragged rows", "if (canDrag && columns == 1) {" in screen)
        }
    }
}
