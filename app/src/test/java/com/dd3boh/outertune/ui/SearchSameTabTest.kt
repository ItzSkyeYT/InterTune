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
 * with nothing found under them. A tap on another tab keeps them (#143's rule: the same tab closes,
 * another keeps). Read from the source.
 */
class SearchSameTabTest {
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val search = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()

    @Test
    fun `both tab bars ask the bar to clear on a tap on the tab that is showing, and only then`() {
        val sameTab = """if (navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) {
                                                    searchCleared++"""
        assertEquals("the bottom bar and the side rail", 2, main.split(sameTab).size - 1)
        assertEquals("nowhere else", 2, Regex("""searchCleared\+\+""").findAll(main).count())
        assertTrue(main.contains("clearQuery = searchCleared"))
    }

    @Test
    fun `the bar clears its words when asked, as its own close does on a tab`() {
        assertTrue(search.contains("LaunchedEffect(clearQuery) { if (clearQuery > 0) onQueryChange(TextFieldValue()) }"))
        // Its own close, which the tab tap now matches.
        assertTrue(search.contains("if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {\n                onQueryChange(TextFieldValue())"))
    }
}
