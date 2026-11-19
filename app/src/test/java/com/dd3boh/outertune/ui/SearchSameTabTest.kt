/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tap on the tab that is showing clears what was typed in the search bar over it, as the search's own
 * close does there (#166, Ti-Die, 13 Nov 2026): the tap closed the search but left its words in the bar
 * with nothing found under them. A tap on another tab keeps them with the page they were typed over
 * (#143's rule: the same tab closes, another keeps; SearchKeptOnTabTest). Read from the source.
 */
class SearchSameTabTest {
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val search = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()

    @Test
    fun `both tab bars clear the words on a tap on the tab that is showing`() {
        val sameTab = """if (navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) {
                                                    searchQuery.value = TextFieldValue()"""
        assertEquals("the bottom bar and the side rail", 2, main.split(sameTab).size - 1)
        // The words the tabs clear are the bar's own: held beside searchActive and handed to it, and the
        // tap clears them itself, at once. The count the bar used to read for it was read again by every
        // new composition of the bar.
        assertTrue(main.contains("val searchQuery = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }"))
        assertTrue(main.contains("queryState = searchQuery"))
        assertTrue(search.contains("val (query, onQueryChange) = queryState"))
        assertTrue("no count any more", "searchCleared" !in main && "clearQuery" !in search)
    }

    @Test
    fun `the bar's own close clears its words on a tab, as the tap does`() {
        assertTrue(search.contains("if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {\n                onQueryChange(TextFieldValue())"))
    }
}
