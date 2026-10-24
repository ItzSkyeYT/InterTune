/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.artist

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.AppBarHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An artist's page upright made its list's top padding by taking the status bar and the top bar
 * away from the player-aware insets. On a frame where what was taken away was more than the insets
 * held, the top went below zero, and Compose refuses such a list: "invalid beforeContentPadding"
 * (#38, a Lenovo tablet on Android 10).
 */
class ArtistListPaddingTest {

    private val density = Density(1.5f)

    // The player-aware insets as measured while the status bar slides away, 20dp of it left.
    private val measured = WindowInsets(left = 80.dp, top = 20.dp + AppBarHeight, right = 0.dp, bottom = 144.dp)

    @Test
    fun `taking the bar away a frame late goes below zero`() {
        // The 24dp bar the page was composed with a frame before, taken away as it used to be.
        val composed = WindowInsets(top = -(24.dp + AppBarHeight))
        assertEquals(-6, measured.add(composed).getTop(density))
    }

    @Test
    fun `the list under the picture has no top, whatever the status bar does`() {
        val list = artistListInsets(measured)
        assertEquals(0, list.getTop(density))
        assertEquals(measured.getBottom(density), list.getBottom(density))
        for (direction in LayoutDirection.entries) {
            assertEquals(measured.getLeft(density, direction), list.getLeft(density, direction))
            assertEquals(measured.getRight(density, direction), list.getRight(density, direction))
        }
    }

    @Test
    fun `no insets are made with a side below zero`() {
        // A side below zero is always a subtraction, and a list that gets one throws.
        val negativeSide = Regex("""\bWindowInsets\(\s*(?:\w+\s*=\s*[^,()]*,\s*)*(left|top|right|bottom)\s*=\s*-""")
        val found = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                negativeSide.findAll(file.readText()).map { "${file.name}: ${it.value.replace(Regex("\\s+"), " ")}" }
            }
            .toList()
        assertTrue("insets with a side below zero: $found", found.isEmpty())
    }
}
