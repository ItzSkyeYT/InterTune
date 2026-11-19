/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #166 (Ti-Die, 13 Nov 2026): "If switching to a different menu and back, keep the search input AND the
 * results." The words stayed in the bar when he came back to the tab, and what they had found did not.
 * Now a search left open over a page when another tab is tapped is kept on that page, in its saved
 * state, which its tab keeps while another tab is shown, and comes back open, with its words and what
 * they find, when the page is back. The bar over the other tab starts clear. A tap on the tab that is
 * showing clears it instead (SearchSameTabTest). Read from the source.
 */
class SearchKeptOnTabTest {
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val search = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()
    private val bar = File("src/main/java/com/dd3boh/outertune/ui/component/SearchBar.kt").readText()

    private val goes = main.substringAfter("fun searchGoesWithPage(wasOpen: Boolean) {").substringBefore("\n                }\n")

    @Test
    fun `a tap on another tab keeps an open search on the page it was over, and the bar starts clear`() {
        val keep = "if (wasOpen && words.isNotEmpty()) navBackStackEntry?.savedStateHandle?.set(KEPT_SEARCH, words)"
        assertTrue(keep in goes)
        assertTrue("then the bar is cleared", goes.indexOf(keep) < goes.indexOf("searchQuery.value = TextFieldValue()"))
        // Both tab bars, with whether the search was open read before the tap closes it.
        val tap = """val searchWasOpen = searchActive
                                                if (searchActive) {
                                                    searchActive = false
                                                }"""
        assertEquals(2, main.split(tap).size - 1)
        val otherTab = """} else {
                                                    searchGoesWithPage(searchWasOpen)
                                                    navigateToNavTab(navController, screen.route, navigationItems, navBackStackEntry)"""
        assertEquals("the bottom bar and the side rail, before the tab is navigated to", 2, main.split(otherTab).size - 1)
        assertTrue("import com.dd3boh.outertune.ui.screens.search.KEPT_SEARCH" in main)
    }

    @Test
    fun `the page back on screen opens its search again, once, with its words`() {
        val effect = search.substringAfter("LaunchedEffect(navBackStackEntry) {").substringBefore("\n    }\n")
        val take = "val kept = page.savedStateHandle.remove<String>(KEPT_SEARCH)"
        assertTrue("taken back, so it opens once", take in effect)
        // After the bar has closed itself for the new page and a page of results has put its own words in.
        assertTrue(effect.indexOf("onSearchActiveChange(false)") < effect.indexOf(take))
        assertTrue(effect.indexOf("navBackStackEntry?.destination?.route == SearchFilters.ROUTE") < effect.indexOf(take))
        val open = effect.substringAfter(take)
        assertTrue("onQueryChange(TextFieldValue(kept, TextRange(kept.length)))" in open)
        assertTrue(open.indexOf("onQueryChange(TextFieldValue(kept") < open.indexOf("onSearchActiveChange(true)"))
        // Only where the bar is, the pages a search can be left open on, and never on a page already gone.
        assertTrue("page.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)" in effect)
        assertTrue("(navigationItems.fastAny { it.route == page.destination.route } || page.destination.route == SearchFilters.ROUTE)" in effect)
    }

    @Test
    fun `the results come back without the keyboard over them`() {
        // Opening the search never asks for the field's focus by itself: only a tap on the field brings up the
        // keyboard, so the search that comes back shows what it found.
        assertFalse("requestFocus(" in search)
        assertFalse("requestFocus(" in bar)
    }
}
