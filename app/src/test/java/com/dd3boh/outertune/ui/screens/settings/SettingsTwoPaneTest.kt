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
}
