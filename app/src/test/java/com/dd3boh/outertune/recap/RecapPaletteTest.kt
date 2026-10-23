/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RecapPaletteTest {
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun lightness(c: Int) = RecapPalette.oklab(r(c), g(c), b(c))[0]

    /** A 40 by 40 cover of one colour, with [other] painted over its first [share] of pixels. */
    private fun cover(colour: Int, other: Int = colour, share: Double = 0.0): IntArray {
        val px = IntArray(1600) { colour }
        for (i in 0 until (1600 * share).toInt()) px[i] = other
        return px
    }

    @Test
    fun `a red cover gives red slides at the mock's lightnesses`() {
        val hue = RecapPalette.measure(cover(0xFFD02030.toInt()))
        assertFalse(hue.grey)
        assertTrue("hue ${hue.degrees}", hue.degrees in 10.0..40.0)
        val colours = RecapPalette.colours(hue)
        assertTrue(r(colours.accent) > g(colours.accent) && r(colours.accent) > b(colours.accent))
        assertEquals(0.86, lightness(colours.accent), 0.01)
        assertEquals(0.97, lightness(colours.ink), 0.01)
        assertEquals(0.2, lightness(colours.deep), 0.01)
        assertTrue(r(colours.deep) > b(colours.deep))
    }

    @Test
    fun `a blue cover gives blue slides`() {
        val accent = RecapPalette.of(cover(0xFF2050D0.toInt())).accent
        assertTrue(b(accent) > r(accent) && b(accent) > g(accent))
    }

    @Test
    fun `a cover of greys gives grey slides`() {
        for (c in listOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF101214.toInt())) {
            val colours = RecapPalette.of(cover(c))
            for (each in listOf(colours.accent, colours.ink, colours.ink2, colours.deep, colours.onAccent)) {
                assertTrue("grey ${Integer.toHexString(each)}", abs(r(each) - g(each)) <= 1 && abs(g(each) - b(each)) <= 1)
            }
        }
    }

    @Test
    fun `a speck of colour on a black cover is no colour`() {
        // Under one pixel in twenty coloured: grey.
        assertTrue(RecapPalette.measure(cover(0xFF000000.toInt(), 0xFFE0C020.toInt(), share = 0.04)).grey)
        assertFalse(RecapPalette.measure(cover(0xFF000000.toInt(), 0xFFE0C020.toInt(), share = 0.2)).grey)
    }

    @Test
    fun `the heavier of two hues wins`() {
        val hue = RecapPalette.measure(cover(0xFF2050D0.toInt(), 0xFFD02030.toInt(), share = 0.3))
        assertTrue("hue ${hue.degrees}", hue.degrees in 240.0..290.0)
    }

    @Test
    fun `a colour too strong for the screen loses chroma, not lightness`() {
        val c = RecapPalette.oklch(0.86, 0.4, 145.0)
        assertEquals(0.86, lightness(c), 0.01)
    }

    @Test
    fun `nothing measured is grey`() {
        assertTrue(RecapPalette.measure(IntArray(0)).grey)
    }
}
