/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** "Nothing playing" was cut to "Nothing playi..." on the Now playing widget at 4 x 2 (Pixel 5, 5 Nov 2026). */
class WidgetEmptyTitleTest {
    @Test
    fun `nothing playing takes the artist's line too, a song keeps its lines`() {
        assertEquals(2, emptyTitleLines(empty = true, titleLines = 1, showArtist = true))
        assertEquals(1, emptyTitleLines(empty = false, titleLines = 1, showArtist = true))
        assertEquals(2, emptyTitleLines(empty = false, titleLines = 2, showArtist = true))
        // No artist line to borrow in a layout that shows none.
        assertEquals(1, emptyTitleLines(empty = true, titleLines = 1, showArtist = false))
        assertEquals(1, emptyTitleLines(empty = true, titleLines = 0, showArtist = false))
    }

    @Test
    fun `both places that draw the title use it`() {
        val widget = File("src/main/java/com/dd3boh/outertune/widget/MusicWidget.kt").readText()
        assertEquals(2, Regex("""maxLines = emptyTitleLines\(song == null, """).findAll(widget).count())
        assertFalse("maxLines = titleLines.coerceAtLeast(1)," in widget)
    }
}
