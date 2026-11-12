/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings is left for good when a tab is tapped (GitHub #135: "the only way to truly exit
 * settings is to press those arrows").
 *
 * The tab bar parks the pages above the start page (popUpTo with saveState) and puts a tab's
 * back when it is tapped again. Settings was parked with them: Settings opened over Library, then
 * Home, and the next tap on Library put Settings back over it; Settings opened over Home, then
 * Library and Home again, and Home was Settings. So Settings is popped off first, and what is
 * parked is what a tab keeps: its page, an album opened from it.
 *
 * What the navigation does with them needs a device (walked on the emulator, 11 Nov 2026); what
 * is held here is the rule: which routes are Settings, how much of a history goes with it, and
 * that nothing is parked before it is left.
 */
class SettingsLeftByTabTest {

    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
    private val tabFunction = main.substringAfter("private fun navigateToNavTab(").substringBefore("\n}\n")

    @Test
    fun `the list of settings and every screen under it is Settings`() {
        assertTrue(isSettingsRoute("settings"))
        assertTrue(isSettingsRoute("settings/player"))
        assertTrue(isSettingsRoute("settings/player/equalizer"))
        assertTrue(isSettingsRoute("settings/recommendations/doing"))
    }

    @Test
    fun `a tab, a page opened from one and anything else is not`() {
        for (route in listOf("home", "songs", "folders", "library", "history", "account", "recognition", "login", "search")) {
            assertFalse("$route is not Settings", isSettingsRoute(route))
        }
        assertFalse(isSettingsRoute(null))
        // Not a screen: it pops itself at once (AppNavGraph) and has no list to leave.
        assertFalse(isSettingsRoute("settings_tour"))
        assertFalse(isSettingsRoute("settingsfoo"))
    }

    @Test
    fun `every screen the nav graph has under settings is Settings to this`() {
        val graph = File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText()
        val routes = Regex("""screen\(\s*"(settings[^"]*)"""").findAll(graph).map { it.groupValues[1] }.toList()
        assertTrue("found only ${routes.size} settings screens in the nav graph", routes.size > 20)
        for (route in routes) {
            // settings_tour is the one that is not a screen to stay on.
            assertEquals(route, route != "settings_tour", isSettingsRoute(route))
        }
    }

    @Test
    fun `leaving Settings takes every screen of it, however deep`() {
        assertEquals(1, pagesLeftWithSettings(listOf("home", "settings")))
        assertEquals(3, pagesLeftWithSettings(listOf("home", "settings", "settings/player", "settings/player/equalizer")))
        // Opened over a tab other than Home, and over a page of that tab.
        assertEquals(1, pagesLeftWithSettings(listOf("home", "library", "settings")))
        assertEquals(1, pagesLeftWithSettings(listOf("home", "library", "album/x", "settings")))
        // A screen of Settings opened straight from a page, with no list under it.
        assertEquals(1, pagesLeftWithSettings(listOf("home", "settings/local")))
    }

    @Test
    fun `what was opened from Settings goes with it, and what was under it stays`() {
        // An artist opened from the player while Settings was open is not a page of the tab.
        assertEquals(2, pagesLeftWithSettings(listOf("home", "settings", "artist/x")))
        assertEquals(2, pagesLeftWithSettings(listOf("home", "library", "settings", "login")))
        // The lowest screen of Settings is where it ends, not the one nearest the top.
        assertEquals(3, pagesLeftWithSettings(listOf("home", "settings/local", "album/x", "settings/player")))
    }

    @Test
    fun `a history without Settings loses nothing, and one is never emptied`() {
        assertEquals(0, pagesLeftWithSettings(listOf("home")))
        assertEquals(0, pagesLeftWithSettings(listOf("home", "library", "album/x")))
        assertEquals(0, pagesLeftWithSettings(emptyList()))
        assertEquals(0, pagesLeftWithSettings(listOf("settings")))
        assertEquals(1, pagesLeftWithSettings(listOf("settings", "settings/player")))
    }

    @Test
    fun `a tab tap leaves Settings before it parks anything`() {
        val leaves = tabFunction.indexOf("leaveSettings()")
        val parks = tabFunction.indexOf("saveState = true")
        assertTrue("navigateToNavTab does not leave Settings", leaves >= 0)
        assertTrue("navigateToNavTab does not park the pages above the start", parks >= 0)
        assertTrue("Settings is left after the pages are parked", leaves < parks)
    }

    @Test
    fun `no other place parks pages, which would park Settings with them`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val parking = sources.filter { "saveState = true" in it.readText() }.map { it.name }.toList()
        assertEquals("pages are parked in $parking: leave Settings first there too", listOf("MainActivity.kt"), parking)
    }

    @Test
    fun `the tab that Settings was opened from is not navigated to again`() {
        // Library, Settings, then Library: leaving Settings is all that is asked. Making the tab
        // again would park the page that was just left and put a new one over it.
        assertTrue(tabFunction.contains("if (left && "))
    }
}
