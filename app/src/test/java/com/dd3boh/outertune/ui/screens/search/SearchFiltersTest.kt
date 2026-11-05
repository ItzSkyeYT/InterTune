/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.search

import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A search edited on a page of results keeps the filter that page was on. His note of 30 Oct 2026:
 * "Search -> artist -> edit search -> filter resets".
 */
class SearchFiltersTest {

    private val chips = listOf(FILTER_SONG, FILTER_VIDEO, FILTER_ALBUM, FILTER_ARTIST, FILTER_COMMUNITY_PLAYLIST, FILTER_FEATURED_PLAYLIST)

    @Test
    fun `every chip but All goes into a route by a plain name and comes back from it`() {
        for (filter in chips) {
            val name = SearchFilters.nameOf(filter)
            assertNotNull(filter.value, name)
            // Nothing a route decodes: YouTube's own values hold "%3D", which came back as "=".
            assertTrue(name, name!!.matches(Regex("[a-z_]+")))
            assertEquals(filter, SearchFilters.of(name))
        }
        assertEquals(chips.size, chips.mapNotNull(SearchFilters::nameOf).toSet().size)
    }

    @Test
    fun `All, no name and an unknown name are no filter`() {
        assertNull(SearchFilters.nameOf(null))
        assertNull(SearchFilters.of(null))
        assertNull(SearchFilters.of("everything"))
        assertEquals("", SearchFilters.routeSuffix(null))
        assertEquals("?filter=artists", SearchFilters.routeSuffix(FILTER_ARTIST))
        assertEquals("search/{query}?filter={filter}", SearchFilters.ROUTE)
    }

    @Test
    fun `the page of results starts on the filter its route names, and on All without one`() {
        val graph = File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText()
        val results = graph.substring(graph.indexOf("route = SearchFilters.ROUTE"), graph.indexOf("OnlineSearchResult(navController)"))
        val filterArg = results.substring(results.indexOf("navArgument(\"filter\")"))
        assertTrue("nullable = true" in filterArg && "defaultValue = null" in filterArg)
        val model = File("src/main/java/com/dd3boh/outertune/viewmodels/OnlineSearchViewModel.kt").readText()
        assertTrue("MutableStateFlow(SearchFilters.of(savedStateHandle.get<String>(\"filter\")))" in model)
    }

    @Test
    fun `the bar hands an edited search the filter of the page it was edited on`() {
        val bar = File("src/main/java/com/dd3boh/outertune/ui/screens/search/SearchScreen.kt").readText()
        // Read from the page's own model, the one its chips show, and only over a page of results.
        assertTrue("?.takeIf { it.destination.route == SearchFilters.ROUTE }" in bar)
        assertTrue("hiltViewModel<OnlineSearchViewModel>(entry).filter.collectAsState().value" in bar)
        // Both ways to search from the bar: the keyboard's search key and a suggestion tapped.
        assertEquals(2, Regex("""navController\.navigate\("search/\$\{it\.urlEncode\(\)\}" \+ SearchFilters\.routeSuffix\(resultsFilter\)\)""").findAll(bar).count())
        assertTrue("navBackStackEntry?.destination?.route == SearchFilters.ROUTE" in bar)
    }

    @Test
    fun `a search from anywhere else still starts on All`() {
        // What's playing?'s "Search for it", its history, and a link: words, and no filter.
        for (path in listOf(
            "src/main/java/com/dd3boh/outertune/ui/screens/RecognitionScreen.kt",
            "src/main/java/com/dd3boh/outertune/ui/screens/RecognitionHistoryScreen.kt",
            "src/main/java/com/dd3boh/outertune/MainActivity.kt",
        )) {
            val source = File(path).readText()
            assertTrue(path, "navigate(\"search/\${" in source)
            assertTrue(path, "routeSuffix" !in source)
        }
    }
}
