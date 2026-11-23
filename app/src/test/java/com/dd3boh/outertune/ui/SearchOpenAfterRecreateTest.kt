/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search bar wiped its own restored words when the app was recreated (18 Nov 2026): a dark mode switch, a language
 * or font change, the app back after Android closed it in the background. Two things did it: a count of tab taps that the bar read again at each new composition (gone with the words held beside
 * searchActive, SearchSameTabTest), and the bar closing the search as the first page arrived under it, which cleared
 * the words typed over a tab. The search now closes only when the page under it changes: open at a recreation, it
 * stays open with its words and what they find. Read from the source.
 */
class SearchOpenAfterRecreateTest {
    private val search = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()
    private val effect = search.substringAfter("LaunchedEffect(navBackStackEntry) {").substringBefore("\n    }\n")

    @Test
    fun `the search closes when the page under it changes, not when the bar is composed again`() {
        // Remembered, not saved: a new composition starts with no page, and the first that arrives is not a change.
        assertTrue("var lastPage by remember { mutableStateOf<String?>(null) }" in search)
        assertTrue("val moved = page != null && lastPage != null && page.id != lastPage" in effect)
        assertTrue("if (searchActive && moved) {\n            onSearchActiveChange(false)" in effect)
        assertFalse("not closed for every page that arrives", "if (searchActive) {\n            onSearchActiveChange(false)" in effect)
    }

    @Test
    fun `a page of results puts its words in the bar on the way in, not over a search still open`() {
        assertTrue("if (navBackStackEntry?.destination?.route == SearchFilters.ROUTE && (moved || !searchActive)) {" in effect)
    }
}
