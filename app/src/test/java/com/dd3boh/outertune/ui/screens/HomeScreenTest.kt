/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import com.dd3boh.outertune.constants.QuickPicksSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest {

    @Test
    fun `label names YouTube only when YouTube's row is actually shown`() {
        assertEquals(
            QuickPicksLabelKind.YOUTUBE,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 2, ytPicksShown = true, localPicksNonEmpty = false),
        )
    }

    @Test
    fun `label falls back to the library when the fallback shelf never shows`() {
        // engineFallback == 2 (fell all the way back) but YouTube's shelf is not there, so ytPicks
        // is null and the grid draws localPicks.
        assertEquals(
            QuickPicksLabelKind.LIBRARY,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 2, ytPicksShown = false, localPicksNonEmpty = true),
        )
    }

    @Test
    fun `label is empty when nothing is drawn at all`() {
        assertEquals(
            QuickPicksLabelKind.NONE,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 2, ytPicksShown = false, localPicksNonEmpty = false),
        )
    }

    @Test
    fun `library fallback still labels the library when nothing further fell back to YouTube`() {
        assertEquals(
            QuickPicksLabelKind.LIBRARY,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 1, ytPicksShown = false, localPicksNonEmpty = true),
        )
    }

    @Test
    fun `no label on a source other than the engine or Try both`() {
        assertEquals(
            QuickPicksLabelKind.NONE,
            quickPicksLabelKind(QuickPicksSource.YOUTUBE, engineFallback = 2, ytPicksShown = true, localPicksNonEmpty = true),
        )
    }

    @Test
    fun `Try both keeps its own label while the engine's own row is still drafted`() {
        assertEquals(
            QuickPicksLabelKind.TRY_BOTH,
            quickPicksLabelKind(QuickPicksSource.COMPARE, engineFallback = 0, ytPicksShown = false, localPicksNonEmpty = false),
        )
    }

    @Test
    fun `Try both names the library while it stands in`() {
        assertEquals(
            QuickPicksLabelKind.LIBRARY,
            quickPicksLabelKind(QuickPicksSource.COMPARE, engineFallback = 1, ytPicksShown = false, localPicksNonEmpty = true),
        )
    }

    @Test
    fun `Try both names YouTube once its shelf stands in and is shown`() {
        assertEquals(
            QuickPicksLabelKind.YOUTUBE,
            quickPicksLabelKind(QuickPicksSource.COMPARE, engineFallback = 2, ytPicksShown = true, localPicksNonEmpty = false),
        )
    }

    @Test
    fun `the engine's own row says what it leans toward, and only while it is the row on screen`() {
        assertEquals(
            QuickPicksLabelKind.LEAN,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 0, ytPicksShown = false, localPicksNonEmpty = true, leaning = true),
        )
        // Try both keeps its own label: half of its row is the other source's.
        assertEquals(
            QuickPicksLabelKind.TRY_BOTH,
            quickPicksLabelKind(QuickPicksSource.COMPARE, engineFallback = 0, ytPicksShown = false, localPicksNonEmpty = true, leaning = true),
        )
        // Fallen back to the library, the row is the library's whatever was chosen.
        assertEquals(
            QuickPicksLabelKind.LIBRARY,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 1, ytPicksShown = false, localPicksNonEmpty = true, leaning = true),
        )
        assertEquals(
            QuickPicksLabelKind.NONE,
            quickPicksLabelKind(QuickPicksSource.ENGINE, engineFallback = 0, ytPicksShown = false, localPicksNonEmpty = true, leaning = false),
        )
    }

    @Test
    fun `the first chip carries the lean's short name, and Auto otherwise`() {
        assertEquals(com.dd3boh.outertune.R.string.chip_auto, leanChipName(com.dd3boh.outertune.engine.Lean.AUTO))
        assertEquals(com.dd3boh.outertune.R.string.chip_lean_new, leanChipName(com.dd3boh.outertune.engine.Lean.NEW))
        assertEquals(com.dd3boh.outertune.R.string.chip_lean_similar, leanChipName(com.dd3boh.outertune.engine.Lean.SIMILAR))
    }

    @Test
    fun `a lean is named in the setting's words, one name each`() {
        val names = com.dd3boh.outertune.engine.Lean.entries.associateWith { leanOptionName(it) }
        assertEquals(com.dd3boh.outertune.R.string.quick_picks_lean_auto, names[com.dd3boh.outertune.engine.Lean.AUTO])
        assertEquals(com.dd3boh.outertune.R.string.quick_picks_lean_new, names[com.dd3boh.outertune.engine.Lean.NEW])
        assertEquals(com.dd3boh.outertune.R.string.quick_picks_lean_artist, names[com.dd3boh.outertune.engine.Lean.ARTIST])
        assertEquals(com.dd3boh.outertune.R.string.quick_picks_lean_forgotten, names[com.dd3boh.outertune.engine.Lean.FORGOTTEN])
        assertEquals(com.dd3boh.outertune.R.string.quick_picks_lean_similar, names[com.dd3boh.outertune.engine.Lean.SIMILAR])
        assertEquals(names.size, names.values.toSet().size)
    }
}
