/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A long press on Back goes home, and never out of a setup that is not finished.
 *
 * It pops screens until a tab is on top. Setup is not a tab and sits over one, so from a page
 * opened in setup (the sign-in page, the import) a long press went past setup to the tab under
 * it: Home with no bar and no player, since those wait for setup to be finished, and nothing on
 * screen to finish it by. It now stops at setup as it stops at a tab.
 */
class LongBackTest {

    private val tabs = setOf("home", "songs", "library")

    @Test
    fun `it stops at a tab`() {
        assertTrue(longBackStopsAt("home", tabs))
        assertTrue(longBackStopsAt("library", tabs))
    }

    @Test
    fun `it stops at setup`() {
        assertTrue(longBackStopsAt(SETUP_ROUTE, tabs))
    }

    @Test
    fun `it goes on past any other screen`() {
        assertFalse(longBackStopsAt("login", tabs))
        assertFalse(longBackStopsAt("settings/backup/import", tabs))
        assertFalse(longBackStopsAt(null, tabs))
    }

    @Test
    fun `setup is still at the route this stops at`() {
        val graph = File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText()
        assertTrue("the nav graph has no screen at \"$SETUP_ROUTE\"", "screen(\"$SETUP_ROUTE\"" in graph)
        val util = File("src/main/java/com/dd3boh/outertune/ui/utils/NavControllerUtils.kt").readText()
        assertTrue("backToMain does not ask longBackStopsAt", "!longBackStopsAt(currentBackStackEntry?.destination?.route, tabs)" in util)
    }
}
