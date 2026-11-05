/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue's rows passed under the status bar's clock as they scrolled up (Pixel 5, 5 Nov 2026),
 * as the lyrics did before LyricsTopEdgeTest. The status bar's space now sits outside the list.
 */
class QueueTopEdgeTest {
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `the song list stops at the status bar's edge`() {
        val list = queue.substringAfter("val songList: @Composable ColumnScope.(PaddingValues) -> Unit").substringBefore("item {")
        assertTrue(".padding(top = contentPadding.calculateTopPadding())" in list)
        assertFalse("the top inset inside the list lets rows scroll under the clock", "contentPadding = contentPadding," in list)
        assertTrue("bottom = contentPadding.calculateBottomPadding()" in list)
    }

    @Test
    fun `a dragged song starts the scroll where it did`() {
        val drag = queue.substringAfter("val reorderableState = rememberReorderableLazyListState(").substringBefore(") { from, to ->")
        assertTrue("WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)" in drag)
    }
}
