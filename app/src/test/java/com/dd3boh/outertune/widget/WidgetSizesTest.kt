/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's pictures are drawn ahead for the sizes the launcher gave it, and what is drawn ahead
 * is only worth anything if it is for the sizes Glance then composes: the same ones, as whole dp.
 */
class WidgetSizesTest {
    @Test
    fun `the sizes a launcher lists are the ones drawn, in whole dp`() {
        // The Pixel 5's: upright and on its side.
        assertEquals(listOf(277 to 220, 530 to 117), sizesFrom(listOf(277.4f to 220.9f, 530.0f to 117.5f), 0, 0, 0, 0))
        assertEquals("a size listed twice is drawn once", listOf(277 to 220), sizesFrom(listOf(277.1f to 220.1f, 277.9f to 220.9f), 0, 0, 0, 0))
    }

    @Test
    fun `with no list the two sizes Glance estimates are used, upright and on its side`() {
        // min width and max height upright, max width and min height on its side
        assertEquals(listOf(277 to 220, 530 to 117), sizesFrom(null, minWidth = 277, minHeight = 117, maxWidth = 530, maxHeight = 220))
        assertEquals(listOf(277 to 220, 530 to 117), sizesFrom(emptyList(), minWidth = 277, minHeight = 117, maxWidth = 530, maxHeight = 220))
        assertEquals("a widget that never moved has one size", listOf(250 to 110), sizesFrom(null, 250, 110, 250, 110))
    }

    @Test
    fun `a launcher that has said nothing yet gives no size to draw for`() {
        assertEquals(emptyList<Pair<Int, Int>>(), sizesFrom(null, 0, 0, 0, 0))
    }

    @Test
    fun `a launcher that gave one whole size draws for that one alone`() {
        assertEquals(listOf(530 to 117), sizesFrom(null, 0, 117, 530, 0))
    }
}
