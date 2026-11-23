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

    /**
     * 18 Nov 2026, his S25U again (Preview 57ae8ec1f, which has the realign): upright, Quick picks stopped a
     * quarter column in after a fling. Each row's snap was remembered with the column's share of the width
     * of Home's first drawing; drawn first on its side (two columns, 0.475), it went on snapping upright
     * (one column, 0.94) at 0.475 / 2 - 0.94 / 2 of the width, a quarter column to the left.
     */
    @Test
    fun `every row snaps for the column width of the moment, not the first one`() {
        listOf("quickPicks", "discover", "forgottenFavorites").forEach { row ->
            assertTrue(
                "$row's snap is made again when the column's share of the width changes",
                home.contains("val ${row}SnapLayoutInfoProvider = remember(${row}LazyGridState, horizontalLazyGridItemWidthFactor) {"),
            )
        }
    }

    @Test
    fun `the realign keeps the same first column and never jumps to the start`() {
        val effect = home.substringAfter("LaunchedEffect(horizontalLazyGridItemWidth) {").substringBefore("\n        }\n")
        assertTrue(effect.contains("scrollToItem(state.firstVisibleItemIndex)"))
        assertTrue(!effect.contains("scrollToItem(0"))
    }
}
