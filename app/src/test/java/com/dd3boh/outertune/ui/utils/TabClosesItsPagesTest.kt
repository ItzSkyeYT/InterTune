/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tab keeps the page it holds while another tab is shown, and a tap on the tab from that page
 * closes it for good (GitHub #143: in Library, "View artist" on a song, and the artist was still
 * there after Library was tapped, another tab, and Library again; "pressing the same tab should
 * permanently close it").
 *
 * The tab bar parks the pages above the start page (popUpTo with saveState) and puts a tab's
 * back when it is come to again. A tap on Library from the artist used to park Library with the
 * artist over it and make Library a second time: the artist was hidden, not closed, and was back
 * at the next change of tab. Now the tab that holds the page showing is not made again: its pages
 * are popped, the way Back pops them, and nothing of them is parked.
 *
 * What the navigation does with them needs a device (walked on the emulator, 12 Nov 2026); what
 * is held here is the rule: which tab holds the page showing, which pages it holds, and that a
 * tap on that tab closes them before anything is parked, from the bar and from the rail.
 */
class TabClosesItsPagesTest {

    private val tabs = setOf("home", "songs", "folders", "library")
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val tabFunction = main.substringAfter("private fun navigateToNavTab(").substringBefore("\n}\n")

    @Test
    fun `the tab that holds a page is the one it was opened in`() {
        // The report: Library, then "View artist" on a song.
        assertEquals("library", tabHolding(listOf("home", "library", "artist/{artistId}"), tabs))
        assertEquals("library", tabHolding(listOf("home", "library", "artist/{artistId}", "album/{albumId}?song={song}"), tabs))
        assertEquals("songs", tabHolding(listOf("home", "songs", "search/{query}"), tabs))
        // A page opened in the tab the app opens on.
        assertEquals("home", tabHolding(listOf("home", "history"), tabs))
        assertEquals("home", tabHolding(listOf("home", "artist/{artistId}", "artist/{artistId}/albums"), tabs))
    }

    @Test
    fun `a tab on its own page holds itself`() {
        assertEquals("home", tabHolding(listOf("home"), tabs))
        assertEquals("library", tabHolding(listOf("home", "library"), tabs))
        // The app opens on Library, and Home is a tab over it.
        assertEquals("home", tabHolding(listOf("library", "home"), tabs))
        assertEquals("home", tabHolding(listOf("library", "home", "stats"), tabs))
    }

    @Test
    fun `a history with no tab in it is held by none`() {
        // The app opens on a tab that was since taken off the bar.
        assertNull(tabHolding(listOf("albums", "album/{albumId}?song={song}"), tabs))
        assertNull(tabHolding(emptyList(), tabs))
        assertNull(tabHolding(listOf(null, null), tabs))
    }

    @Test
    fun `a tab holds the page opened in it and every page opened from that page`() {
        assertEquals(1, pagesOverTab(listOf("home", "library", "artist/{artistId}"), tabs))
        assertEquals(2, pagesOverTab(listOf("home", "library", "artist/{artistId}", "album/{albumId}?song={song}"), tabs))
        assertEquals(3, pagesOverTab(listOf("home", "folders", "local_playlist/{playlistId}", "artist/{artistId}", "artist/{artistId}/songs"), tabs))
        assertEquals(1, pagesOverTab(listOf("home", "history"), tabs))
        assertEquals(2, pagesOverTab(listOf("library", "auto_playlist/{playlistId}", "artist/{artistId}"), tabs))
        assertEquals(1, pagesOverTab(listOf("library", "home", "stats"), tabs))
    }

    @Test
    fun `a tab on its own page holds nothing`() {
        assertEquals(0, pagesOverTab(listOf("home"), tabs))
        assertEquals(0, pagesOverTab(listOf("home", "library"), tabs))
        assertEquals(0, pagesOverTab(listOf("library", "home"), tabs))
    }

    @Test
    fun `closing never takes the first page of a history`() {
        assertEquals(1, pagesOverTab(listOf("albums", "album/{albumId}?song={song}"), tabs))
        assertEquals(0, pagesOverTab(listOf("albums"), tabs))
        assertEquals(0, pagesOverTab(emptyList(), tabs))
        assertEquals(1, pagesOverTab(listOf(null, null), tabs))
    }

    @Test
    fun `a page that Settings was opened over is held by its tab once Settings is left`() {
        // Library, an album, Settings from a link on the album: #135 leaves Settings and what was
        // opened from it. The album is then a page Library holds like any other.
        val history = listOf("home", "library", "album/{albumId}?song={song}", "settings/account_sync", "login")
        val afterSettings = history.dropLast(pagesLeftWithSettings(history))
        assertEquals(listOf("home", "library", "album/{albumId}?song={song}"), afterSettings)
        assertEquals("library", tabHolding(afterSettings, tabs))
        assertEquals(1, pagesOverTab(afterSettings, tabs))
    }

    @Test
    fun `a tap on a tab closes what that tab holds before anything is parked`() {
        val closes = tabFunction.indexOf("closeTabPages(route, ")
        val parks = tabFunction.indexOf("saveState = true")
        assertTrue("navigateToNavTab does not close the pages of the tab tapped", closes >= 0)
        assertTrue("navigateToNavTab does not park the pages above the start", parks >= 0)
        assertTrue("the tab's pages are closed after they are parked", closes < parks)
    }

    @Test
    fun `only the tab that holds the page showing closes its pages`() {
        val utils = File("src/main/java/com/dd3boh/outertune/ui/utils/NavControllerUtils.kt").readText()
        val closing = utils.substringAfter("fun NavController.closeTabPages(").substringBefore("\n}\n")
        val asks = closing.indexOf("if (tabHolding(pages, tabs) != tab) return false")
        val pops = closing.indexOf("popBackStack()")
        assertTrue("closeTabPages does not ask which tab holds the page", asks >= 0)
        assertTrue("closeTabPages pops before it asks", pops > asks)
    }

    @Test
    fun `the tab whose pages were closed is not made again`() {
        // Library, an artist, then Library: closing the artist is all that is asked. Making the
        // tab again would park the page that was just come back to and put a new one over it.
        val stops = tabFunction.indexOf("if (left && ")
        assertTrue(stops >= 0)
        val before = tabFunction.substring(0, stops)
        assertTrue("closing a tab's pages does not count as having left", "val left = leftSettings || closed" in before)
        assertTrue(tabFunction.substring(stops).substringBefore("\n").trimEnd().endsWith("return"))
    }

    @Test
    fun `any other tab comes back with the page it holds, whatever page it is come to from`() {
        // It used to be restored only when a tab's own page was left: come to from a page, a tab
        // was made again, and the page it held stayed parked to come back some other day.
        assertTrue("restoreState = true" in tabFunction)
        assertFalse(Regex("""restoreState = (?!true)""").containsMatchIn(tabFunction))
    }

    @Test
    fun `the tabs are those of the bar`() {
        // A tab that is not on the bar cannot be tapped, so it is no place to stop at.
        assertTrue("closeTabPages(route, navigationItems.mapTo(HashSet()) { it.route })" in tabFunction)
    }

    @Test
    fun `the bar and the rail change tab the same way`() {
        val call = "navigateToNavTab(navController, screen.route, navigationItems, navBackStackEntry)"
        val bar = main.substringAfter("val navbar: @Composable").substringBefore("fun navRail(")
        val rail = main.substringAfter("fun navRail(").substringBefore("val bottomSheetMenu")
        assertTrue("the bar does not change tab through navigateToNavTab", call in bar)
        assertTrue("the rail does not change tab through navigateToNavTab", call in rail)
    }
}
