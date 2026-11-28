/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Select all on a very large list, then Home or the screen locked: every id selected went into
 * the state the app hands Android, about 32 bytes a song (16,000 songs are some 500 KB), and the
 * app closed as it went to the background, as #107 did at 527 KB. A selection is kept up to
 * KeptSelection.MOST ids, and one larger comes back empty, with its page out of select mode.
 */
class KeptSelectionTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun `a selection up to the most is kept whole, and one larger is not kept at all`() {
        val saver = KeptSelection.saver<String>()
        val few = mutableListOf("LSa1b2c3d4", "dQw4w9WgXcQ")
        val saved = with(saver) { scope.save(few) }
        assertEquals(few, saved)
        assertEquals(few, saver.restore(saved!!))

        val most = MutableList(KeptSelection.MOST) { "LS%08d".format(it) }
        assertEquals(KeptSelection.MOST, (with(saver) { scope.save(most) } as List<*>).size)
        most += "one more"
        assertNull("nothing is handed to Android for a selection this large", with(saver) { scope.save(most) })
    }

    @Test
    fun `the history's selection of plays is kept the same way`() {
        val saver = KeptSelection.saver<Long>()
        assertEquals(listOf(1L, 2L), with(saver) { scope.save(mutableListOf(1L, 2L)) })
        assertNull(with(saver) { scope.save(MutableList(KeptSelection.MOST + 1) { it.toLong() }) })
    }

    @Test
    fun `every list with Select all over a library's worth of songs keeps its selection so, and leaves select mode when it comes back empty`() {
        for (page in listOf(
            "library/LibrarySongsScreen.kt", "HistoryScreen.kt", "playlist/AutoPlaylistScreen.kt",
            "library/FolderScreen.kt", "playlist/LocalPlaylistScreen.kt",
        )) {
            val code = File("src/main/java/com/dd3boh/outertune/ui/screens/$page").readText()
            assertTrue("$page keeps every id selected", "rememberSaveable(saver = KeptSelection.saver<" in code)
            assertTrue("$page stays in select mode with nothing selected", "LaunchedEffect(Unit) { if (inSelectMode && selection.isEmpty()) inSelectMode = false }" in code)
        }
    }
}
