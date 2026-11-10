/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * At the widest zoom the ruler's last label ran past the canvas's edge and showed "+1" for "+13"
 * (the Pixel 5, 10 Nov 2026). A ruler label that does not lie whole inside the canvas is not drawn.
 */
class RulerLabelTest {

    // A Pixel 5: 1080 px wide, the canvas drawing within 12 dp (33 px) of each edge.
    private val left = 33f
    private val right = 1080f - 33f

    @Test
    fun `a label that ends past the canvas's edge is not drawn`() {
        // "+13" at 11 sp is about 60 px: from 1012 it ends at 1072, past 1047.
        assertFalse(labelFits(x = 1012f, width = 60f, left = left, right = right))
        // The one before it, a step of 4 bars back, lies whole.
        assertTrue(labelFits(x = 912f, width = 60f, left = left, right = right))
    }

    @Test
    fun `a label that ends exactly at the edge, or starts exactly at it, is drawn`() {
        assertTrue(labelFits(x = 987f, width = 60f, left = left, right = right))
        assertTrue(labelFits(x = left, width = 60f, left = left, right = right))
        assertFalse(labelFits(x = left - 4f, width = 60f, left = left, right = right))
    }

    @Test
    fun `the ruler's bar labels, beat numbers and seconds are all drawn by it`() {
        val canvas = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixCanvas.kt").readText()
        val ruler = canvas.substringAfter("private fun DrawScope.drawRuler(").substringBefore("/** How wide the widest bar or second label in view is drawn. */")
        assertTrue(ruler, "rulerLabel(c, c.words.barName(" in ruler)
        assertTrue(ruler, "rulerLabel(c, b.toString()" in ruler)
        assertTrue(ruler, "rulerLabel(c, if (s == 0)" in ruler)
        assertTrue("no bare label left in the ruler", "label(c.measurer" !in ruler)
        val fn = canvas.substringAfter("private fun DrawScope.rulerLabel(").substringBefore("\n}\n")
        assertTrue(fn, "labelFits(x, r.size.width.toFloat(), c.layout.pad, size.width - c.layout.pad)" in fn)
    }
}
