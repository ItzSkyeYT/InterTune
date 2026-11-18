/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 Nov 2026: after turning the phone to landscape and back, Home's Quick picks grid stopped halfway
 * through a column. A column is as wide as the screen, so the pixel offset kept across the turn belonged
 * to the other width, and the snap only acts after a fling. Every snapping row now goes back to a whole
 * column, the same first one, whenever its width changes.
 */
class HomeRotationRealignTest {
    private val home = File("src/main/java/com/dd3boh/outertune/ui/screens/HomeScreen.kt").readText()

    @Test
    fun `every snapping row realigns to a whole column when the width changes`() {
        val snapping = Regex("""rememberSnapFlingBehavior\((\w+)SnapLayoutInfoProvider\)""").findAll(home).count()
        assertEquals("three snapping rows", 3, snapping)
        listOf("quickPicksLazyGridState", "discoverLazyGridState", "forgottenFavoritesLazyGridState").forEach { state ->
            val effect = home.substringAfter("LaunchedEffect(horizontalLazyGridItemWidth) {").let {
                if (state in it.substringBefore("\n        }\n")) it.substringBefore("\n        }\n") else ""
            }
            assertTrue("$state is realigned keyed on the item width", effect.isNotEmpty())
        }
    }

    @Test
    fun `the realign keeps the same first column and never jumps to the start`() {
        val effect = home.substringAfter("LaunchedEffect(horizontalLazyGridItemWidth) {").substringBefore("\n        }\n")
        assertTrue(effect.contains("scrollToItem(state.firstVisibleItemIndex)"))
        assertTrue(!effect.contains("scrollToItem(0"))
    }
}
