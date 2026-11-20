/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's Gradient takes a colourless cover's own white and black (his yes of 20 Nov 2026, after dvd's
 * black-and-white cover gave a player as black as Pure black): Palette's filter, which drops near-white and
 * near-black, is cleared, and Score still keeps greys out where a cover has colours.
 */
class GradientColoursTest {
    @Test
    fun `the palette keeps white and black, the colours still come first`() {
        val theme = File("src/main/java/com/dd3boh/outertune/ui/theme/Theme.kt").readText()
        val body = theme.substringAfter("fun Bitmap.extractGradientColors()").substringBefore("\n}\n")
        assertTrue(".clearFilters()" in body)
        assertTrue(body.indexOf(".clearFilters()") < body.indexOf(".generate()"))
        assertTrue("Score.score(extractedColors, 2, 0xff4285f4.toInt(), true)" in body)
    }
}
