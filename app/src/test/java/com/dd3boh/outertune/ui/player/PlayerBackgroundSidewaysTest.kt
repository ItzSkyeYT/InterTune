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
 * A phone turned on its side kept its Blur upright but showed a black background sideways while Android's battery
 * saver was on (Niko, #testers, 18 Nov 2026): sideways the window is 600dp wide or more, so Blur was swapped for the
 * Gradient meant for tablets, and the Gradient took no colours under the battery saver.
 */
class PlayerBackgroundSidewaysTest {

    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
    private val choice = player.substringAfter("val effectivePlayerBackground =").substringBefore("// gradient colours")
    private val colours = player.substringAfter("// gradient colours").substringBefore("// Twice a second")

    @Test
    fun `only a tablet swaps Blur for the Gradient, never a phone on its side`() {
        assertTrue(choice.contains("smallestScreenWidthDp >= 600"))
        assertFalse("the window's width grows when a phone turns", choice.contains("supportsWideScreen()"))
    }

    @Test
    fun `the Gradient always has colours, battery saver or not`() {
        assertFalse(colours.contains("isPowerSaver()"))
        assertTrue("a cover that cannot be read still gives a gradient", colours.contains("NO_COVER_GRADIENT"))
    }
}
