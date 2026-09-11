/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RowVisibilityTest {
    /** Two columns of four cards, 300 wide and 100 tall, as the Quick picks grid lays them out. */
    private val grid = (0 until 8).map { CardBox(index = it, x = (it / 4) * 300, y = (it % 4) * 100, width = 300, height = 100) }

    @Test
    fun `a row fully on screen counts every card in the viewport`() {
        assertEquals((0 until 8).toSet(), seenSlots(grid, 0, 600, rowTop = 200, screenTop = 0, screenBottom = 2000))
    }

    @Test
    fun `a row half pushed off the top counts only its lower cards`() {
        // Row starts 250 above the screen: cards at y 0 and 100 are gone, y 200 is 50 in (half), y 300 is in.
        assertEquals(setOf(2, 3, 6, 7), seenSlots(grid, 0, 600, rowTop = -250, screenTop = 0, screenBottom = 2000))
    }

    @Test
    fun `a card less than half in from the top is not seen`() {
        assertEquals(setOf(3, 7), seenSlots(grid, 0, 600, rowTop = -260, screenTop = 0, screenBottom = 2000))
    }

    @Test
    fun `a row below the screen counts nothing`() {
        assertEquals(emptySet<Int>(), seenSlots(grid, 0, 600, rowTop = 2100, screenTop = 0, screenBottom = 2000))
    }

    @Test
    fun `a row peeking in at the bottom counts its top cards only`() {
        assertEquals(setOf(0, 4), seenSlots(grid, 0, 600, rowTop = 1950, screenTop = 0, screenBottom = 2000))
    }

    @Test
    fun `a column scrolled half out sideways is dropped`() {
        // Viewport 400 wide starting at 200: column 1 (x 300 to 600) has 300 in, column 0 has 100 in.
        assertEquals(setOf(4, 5, 6, 7), seenSlots(grid, 200, 600, rowTop = 0, screenTop = 0, screenBottom = 2000))
    }

    @Test
    fun `cards with no size are never seen`() {
        val unmeasured = listOf(CardBox(0, 0, 0, 0, 0), CardBox(1, 0, 0, 300, 0))
        assertEquals(emptySet<Int>(), seenSlots(unmeasured, 0, 600, rowTop = 0, screenTop = 0, screenBottom = 2000))
    }
}
