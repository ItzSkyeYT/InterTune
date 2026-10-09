/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A title reads in the direction of its own letters, whatever the app's language.
 *
 * The player scrolls a long title as a marquee, and a marquee starts at the layout's first edge.
 * In the Arabic app that is the right, where an English title ends: "Instant Crush (feat. Julian
 * Casablancas)" showed as "…Julian Casablancas)" (Pixel 5, 9 Oct 2026). The mirror happens to an
 * Arabic title in the English app.
 */
class ReadingDirectionTest {
    private val ltr = LayoutDirection.Ltr
    private val rtl = LayoutDirection.Rtl

    @Test
    fun `a title in Latin letters reads left to right in a right-to-left app`() {
        assertEquals(ltr, readingDirection("Instant Crush (feat. Julian Casablancas)", rtl))
        assertEquals(ltr, readingDirection("Daft Punk", rtl))
    }

    @Test
    fun `a title in Arabic, Hebrew or Persian reads right to left in a left-to-right app`() {
        assertEquals(rtl, readingDirection("حبيتك بالصيف", ltr))
        assertEquals(rtl, readingDirection("שיר לשלום", ltr))
        assertEquals(rtl, readingDirection("دوباره می‌سازمت وطن", ltr))
    }

    @Test
    fun `the first letter decides, not what stands before it`() {
        // Digits, brackets and quotes have no direction of their own.
        assertEquals(ltr, readingDirection("24K Magic", rtl))
        assertEquals(ltr, readingDirection("(I Can't Get No) Satisfaction", rtl))
        assertEquals(rtl, readingDirection("3 دقات", ltr))
        assertEquals(rtl, readingDirection("\"أنت عمري\" (Live)", ltr))
    }

    @Test
    fun `a title with no letters keeps the app's direction`() {
        for (app in listOf(ltr, rtl)) {
            assertEquals(app, readingDirection("", app))
            assertEquals(app, readingDirection("1999", app))
            assertEquals(app, readingDirection("…", app))
            assertEquals(app, readingDirection("#1 (2010)", app))
        }
    }

    @Test
    fun `other scripts read left to right`() {
        assertEquals(ltr, readingDirection("夜に駆ける", rtl))
        assertEquals(ltr, readingDirection("봄날", rtl))
        assertEquals(ltr, readingDirection("Кукушка", rtl))
    }

    @Test
    fun `letters outside the basic plane are read whole`() {
        // An emoji is two chars and has no direction; the letter after it decides.
        assertEquals(ltr, readingDirection("🔥 Fire", rtl))
        assertEquals(rtl, readingDirection("🔥 نار", ltr))
    }
}
