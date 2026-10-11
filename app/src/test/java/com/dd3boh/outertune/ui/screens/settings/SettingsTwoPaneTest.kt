/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTwoPaneTest {

    @Test
    fun `the list has the four groups of the upright cards, fourteen screens in all`() {
        val groups = settingsGroups(engine = true)
        assertEquals(listOf(3, 4, 3, 4), groups.map { it.size })
        assertEquals("settings/account_sync", groups.first().first().route)
        assertEquals("settings/about", groups.last().last().route)
    }

    @Test
    fun `no screen is in the list twice`() {
        val routes = settingsGroups(engine = true).flatten().map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.startsWith("settings/") })
    }

    @Test
    fun `without the engine its screen is not offered, and nothing else goes`() {
        val with = settingsGroups(engine = true).flatten().map { it.route }
        val without = settingsGroups(engine = false).flatten().map { it.route }
        assertFalse("settings/recommendations" in without)
        assertEquals(with - "settings/recommendations", without)
    }

    @Test
    fun `the list takes a good third of the window, within what a name needs and a list is worth`() {
        assertEquals(264.dp, settingsListWidth(700.dp))
        assertEquals(289.dp.value, settingsListWidth(850.dp).value, 0.5f)
        assertEquals(340.dp, settingsListWidth(1280.dp))
        var last = 0.dp
        for (width in 600..1600 step 20) {
            val list = settingsListWidth(width.dp)
            assertTrue(list >= last && list in 264.dp..340.dp)
            last = list
        }
    }

    @Test
    fun `the other pane is always the wider one`() {
        for (width in 700..1600 step 50) assertTrue(width.dp - settingsListWidth(width.dp) > settingsListWidth(width.dp))
    }

    @Test
    fun `a phone held upright is too narrow for two panes, one on its side is not`() {
        assertTrue(448.dp < SettingsTwoPaneMinWidth)
        assertTrue(412.dp < SettingsTwoPaneMinWidth)
        assertTrue(851.dp >= SettingsTwoPaneMinWidth)
        assertTrue(800.dp >= SettingsTwoPaneMinWidth)
    }

    // What is open on the right.

    private val listed = settingsGroups(engine = true).flatten().map { it.route }
    private val further = settingsFurtherIn(import = true, engine = true)

    private fun asked(open: List<String>, route: String, over: Boolean = false) = settingsOpened(open, route, listed, further, over)

    @Test
    fun `every screen further in belongs to an entry of the list, and none is in the list itself`() {
        for (route in further) {
            assertFalse(route, route in listed)
            assertTrue(route, listed.any { route.startsWith("$it/") })
        }
        assertEquals(further.size, further.toSet().size)
        assertEquals(listOf("settings/about/attribution", "settings/about/oss_licenses"), settingsFurtherIn(import = false, engine = false))
    }

    @Test
    fun `an entry of the list replaces whatever was open`() {
        assertEquals(listOf("settings/player"), asked(listOf("settings/about", "settings/about/oss_licenses"), "settings/player"))
        assertEquals(listOf("settings/about"), asked(listOf("settings/about", "settings/about/oss_licenses"), "settings/about"))
        // Lyrics is an entry of its own although its route sits under Library's.
        assertEquals(listOf("settings/library/lyrics"), asked(listOf("settings/library"), "settings/library/lyrics"))
    }

    @Test
    fun `a screen further in goes on top of the one that led to it`() {
        assertEquals(listOf("settings/about", "settings/about/oss_licenses"), asked(listOf("settings/about"), "settings/about/oss_licenses"))
        assertEquals(
            listOf("settings/recommendations", "settings/recommendations/doing", "settings/recommendations/developer"),
            asked(listOf("settings/recommendations", "settings/recommendations/doing"), "settings/recommendations/developer"),
        )
    }

    @Test
    fun `asked for from somewhere else, it starts from the entry it belongs to`() {
        assertEquals(listOf("settings/recommendations", "settings/recommendations/exclusions"), asked(listOf("settings/player"), "settings/recommendations/exclusions"))
    }

    @Test
    fun `asking for what is already open goes back to it and opens nothing twice`() {
        val open = listOf("settings/recommendations", "settings/recommendations/doing", "settings/recommendations/developer")
        assertEquals(open, asked(open, "settings/recommendations/developer"))
        assertEquals(open.take(2), asked(open, "settings/recommendations/doing"))
        assertEquals(open.take(2), asked(open, "settings/recommendations/doing", over = true))
    }

    @Test
    fun `a link opens its screen over the one it was on, to come back to`() {
        assertEquals(listOf("settings/player", "settings/appearance"), asked(listOf("settings/player"), "settings/appearance", over = true))
        assertEquals(listOf("settings/player"), asked(listOf("settings/player"), "settings/player", over = true))
        assertEquals(
            listOf("settings/about", "settings/about/oss_licenses", "settings/recommendations/exclusions"),
            asked(listOf("settings/about", "settings/about/oss_licenses"), "settings/recommendations/exclusions", over = true),
        )
    }

    @Test
    fun `what is not a screen of settings is not the pane's to open`() {
        assertEquals(null, asked(listOf("settings/account_sync"), "login"))
        assertEquals(null, asked(listOf("settings/about"), "walkthrough", over = true))
        assertEquals(null, asked(listOf("settings/about"), "settings"))
        // Nor one that this build does not have.
        assertEquals(null, settingsOpened(listOf("settings/backup"), "settings/backup/import", listed, settingsFurtherIn(import = false, engine = true)))
    }

    @Test
    fun `the entry shown as open is the one the screen in sight belongs to`() {
        assertEquals("settings/about", settingsChosen(listOf("settings/about", "settings/about/oss_licenses"), listed))
        assertEquals("settings/appearance", settingsChosen(listOf("settings/player", "settings/appearance"), listed))
        assertEquals("settings/player", settingsChosen(listOf("settings/player"), listed))
        assertEquals("settings/recommendations", settingsChosen(listOf("settings/player", "settings/recommendations", "settings/recommendations/data"), listed))
    }

    @Test
    fun `what the pane holds can be put away and brought back`() {
        // It is kept across a rotation, which takes something that can be written out. A view
        // onto another list cannot, and going back to a screen underneath used to hand one over.
        val open = listOf("settings/recommendations", "settings/recommendations/doing", "settings/recommendations/developer")
        for (kept in listOf(asked(open, "settings/recommendations/doing"), asked(open, "settings/recommendations/doing", over = true), asked(open, "settings/recommendations"))) {
            assertTrue(kept is java.io.Serializable)
            val out = java.io.ByteArrayOutputStream()
            java.io.ObjectOutputStream(out).use { it.writeObject(kept) }
            assertTrue(out.size() > 0)
        }
    }
}

