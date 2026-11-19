/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.search

import com.dd3boh.outertune.ui.screens.Screens
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search pill is over every tab of the bar. On his Folders tab (15 Nov 2026) there was an empty
 * band where it goes: the pill kept the list of tabs the app had when it was opened, so a tab that
 * came into the bar afterwards (Folders, which turning local media on brings back, or any tab added
 * under Arrange tabs) never had it until the app was started again.
 */
class SearchPillOnTabsTest {

    private fun bar(tabs: String) = Screens.getScreens(tabs)

    @Test
    fun `every tab of the bar has the pill, whichever tabs the bar holds`() {
        for (tabs in listOf("HSFM", "HSM", "FSAM", "HSFABLM", "F")) {
            for (tab in bar(tabs)) {
                assertTrue("$tab in $tabs", searchPillShown(searchActive = false, route = tab.route, tabs = bar(tabs)))
            }
        }
    }

    @Test
    fun `a tab put in the bar later has it as soon as the bar is told`() {
        // Folders gone from the bar (local media off), then back (turned on).
        assertFalse(searchPillShown(searchActive = false, route = "folders", tabs = bar("HSM")))
        assertTrue(searchPillShown(searchActive = false, route = "folders", tabs = bar("HSFM")))
    }

    @Test
    fun `it is not over a page that is not a tab, and is over search and its results`() {
        for (route in listOf("settings", "history", "folders/{path}", "album/{albumId}?song={song}", null)) {
            assertFalse("over $route", searchPillShown(searchActive = false, route = route, tabs = bar("HSFM")))
        }
        assertTrue(searchPillShown(searchActive = false, route = "search/{query}", tabs = bar("HSFM")))
        // While search is open it is on whatever is under it.
        assertTrue(searchPillShown(searchActive = true, route = "settings", tabs = bar("HSFM")))
        assertTrue(searchPillShown(searchActive = true, route = null, tabs = emptyList()))
    }

    @Test
    fun `the pill reads the bar as it is now, not as it was when the app was opened`() {
        val container = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()
        // The list was `remember { Screens.getScreens(enabledTabs) }`: kept for as long as the app was open.
        assertTrue(container.contains("val navigationItems = remember(enabledTabs) { Screens.getScreens(enabledTabs) }"))
        assertFalse(container.contains("remember { Screens.getScreens(enabledTabs) }"))
        val shown = container.substringAfter("val shouldShowSearchBar =").substringBefore("LaunchedEffect(navBackStackEntry)")
        assertTrue(shown.contains("remember(searchActive, navBackStackEntry, navigationItems)"))
        assertTrue(shown.contains("searchPillShown(searchActive, navBackStackEntry?.destination?.route, navigationItems)"))
    }

    @Test
    fun `the bar comes back with the list when a tab is tapped again and scrolls to its top`() {
        // The scroll to the top is in code, which the bar's scroll behaviour (fingers and flings) never feels.
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val effect = main.substringAfter("A tab tapped again is scrolled to its top by the screen itself").substringBefore("// The Search shortcut.")
        assertTrue(effect.contains("getStateFlow(\"scrollToTop\", false)"))
        assertTrue(effect.contains("appBarState.contentOffset = 0f"))
        assertTrue(effect.contains("appBarState.resetHeightOffset()"))
    }
}
