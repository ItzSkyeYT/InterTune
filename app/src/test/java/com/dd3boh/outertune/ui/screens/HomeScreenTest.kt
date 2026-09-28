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
}
