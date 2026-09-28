/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.constants.QuickPicksSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun `YouTube's own source always counts, whatever the fallback`() {
        assertTrue(ytRowOnScreenFor(QuickPicksSource.YOUTUBE, engineFallback = 0, poolNonEmpty = true))
        assertTrue(ytRowOnScreenFor(QuickPicksSource.YOUTUBE, engineFallback = 2, poolNonEmpty = true))
    }

    @Test
    fun `Try both's own drafted row is not mistaken for YouTube's`() {
        // engineFallback == 0: the engine has a row and draftCompareRow mixed it with the other
        // source, which HomeScreen never treats as YouTube's shelf on its own.
        assertFalse(ytRowOnScreenFor(QuickPicksSource.COMPARE, engineFallback = 0, poolNonEmpty = true))
    }

    @Test
    fun `Try both does not count YouTube's shelf while the library stands in`() {
        // engineFallback == 1: the library's row stands in, not YouTube's shelf, so a held YouTube
        // pool is not on screen.
        assertFalse(ytRowOnScreenFor(QuickPicksSource.COMPARE, engineFallback = 1, poolNonEmpty = true))
    }

    @Test
    fun `Try both does fall back to YouTube's shelf once the engine has fallen back that far`() {
        assertTrue(ytRowOnScreenFor(QuickPicksSource.COMPARE, engineFallback = 2, poolNonEmpty = true))
    }

    @Test
    fun `the engine source only shows YouTube's shelf at the last fallback step`() {
        assertFalse(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 0, poolNonEmpty = true))
        assertFalse(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 1, poolNonEmpty = true))
        assertTrue(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 2, poolNonEmpty = true))
    }

    @Test
    fun `an empty pool is never on screen, whatever the source says`() {
        assertFalse(ytRowOnScreenFor(QuickPicksSource.YOUTUBE, engineFallback = 0, poolNonEmpty = false))
        assertFalse(ytRowOnScreenFor(QuickPicksSource.ENGINE, engineFallback = 2, poolNonEmpty = false))
    }

    @Test
    fun `the row is off, never YouTube's`() {
        assertFalse(ytRowOnScreenFor(QuickPicksSource.OFF, engineFallback = 2, poolNonEmpty = true))
    }
}
