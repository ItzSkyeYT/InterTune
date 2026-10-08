/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture the search pill begins with. A back arrow on Home, with nothing being searched and
 * nowhere to go back to, read as a way out of a screen that has none, and a tap on it opened
 * search.
 */
class SearchPillTest {

    @Test
    fun `on a tab with nothing being searched it is a magnifier`() {
        listOf("home", "songs", "folders", "library", null).forEach {
            assertFalse("an arrow on $it", searchPillLeadsBack(searchActive = false, route = it))
        }
    }

    @Test
    fun `while search is open it is the arrow that closes it`() {
        assertTrue(searchPillLeadsBack(searchActive = true, route = "home"))
        assertTrue(searchPillLeadsBack(searchActive = true, route = null))
    }

    @Test
    fun `on a search screen it is the arrow that leaves the screen`() {
        // The page of results, and search as a destination of its own.
        assertTrue(searchPillLeadsBack(searchActive = false, route = "search/{query}"))
        assertTrue(searchPillLeadsBack(searchActive = false, route = "search"))
    }
}
