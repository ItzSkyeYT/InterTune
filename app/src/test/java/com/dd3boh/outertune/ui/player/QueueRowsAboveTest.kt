/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue opens with a few songs above the one playing (#80, 4 Nov 2026): the playing song was the
 * first row, with nothing of what came before it in sight. About a third of the list shows above it,
 * three songs at most, as in the reporter's picture.
 */
class QueueRowsAboveTest {
    @Test
    fun `a phone held upright shows three songs above the one playing`() {
        // Pixel 5: the list is 1,764 px tall above the glass panel, a row 176 px.
        assertEquals(3, queueRowsAbove(visibleHeight = 1764, rowHeight = 176))
        // The reporter's Galaxy A53, 1080 x 2400.
        assertEquals(3, queueRowsAbove(visibleHeight = 1900, rowHeight = 180))
        // A third of the list rounded, not cut down: a list a little shorter still shows three.
        assertEquals(3, queueRowsAbove(visibleHeight = 1700, rowHeight = 176))
    }

    @Test
    fun `a short list on its side shows one`() {
        assertEquals(1, queueRowsAbove(visibleHeight = 700, rowHeight = 176))
    }

    @Test
    fun `never more than three, and none before the rows are laid out`() {
        assertEquals(3, queueRowsAbove(visibleHeight = 4000, rowHeight = 176))
        assertEquals(0, queueRowsAbove(visibleHeight = 1764, rowHeight = 0))
        assertEquals(0, queueRowsAbove(visibleHeight = 0, rowHeight = 176))
    }

    @Test
    fun `the queue's scroll to the song playing leaves them above it`() {
        val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
        val scroll = queue.substringAfter("internal suspend fun LazyListState.scrollToWhenLaidOut(index: Int): Boolean")
            .substringBefore("\n}\n")
        assertTrue(scroll, scroll.contains("queueRowsAbove("))
        assertTrue(scroll, scroll.contains("scrollToItem((index - "))
    }
}
