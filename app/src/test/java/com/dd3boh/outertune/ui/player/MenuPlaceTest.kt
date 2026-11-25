/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.constants.PlayerButtonsStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three-dots button is placed at the top or the bottom of the player, never removed (Ti-Die and dvd, 24 Nov 2026;
 * "it should always be in either top or bottom"). Nobody who never chooses sees it move.
 */
class MenuPlaceTest {

    @Test
    fun `never chosen, it stays where it has always been, Classic at the top and Connected at the bottom`() {
        assertEquals(MenuPlace.TOP, MenuPlaces.read("", PlayerButtonsStyle.CLASSIC))
        assertEquals(MenuPlace.BOTTOM, MenuPlaces.read("", PlayerButtonsStyle.CONNECTED))
    }

    @Test
    fun `each style keeps its own choice`() {
        val raw = MenuPlaces.write("", PlayerButtonsStyle.CLASSIC, MenuPlace.BOTTOM)
        assertEquals(MenuPlace.BOTTOM, MenuPlaces.read(raw, PlayerButtonsStyle.CLASSIC))
        assertEquals("Connected is not touched", MenuPlace.BOTTOM, MenuPlaces.read(raw, PlayerButtonsStyle.CONNECTED))
        val both = MenuPlaces.write(raw, PlayerButtonsStyle.CONNECTED, MenuPlace.TOP)
        assertEquals(MenuPlace.TOP, MenuPlaces.read(both, PlayerButtonsStyle.CONNECTED))
        assertEquals(MenuPlace.BOTTOM, MenuPlaces.read(both, PlayerButtonsStyle.CLASSIC))
    }

    @Test
    fun `a broken setting is the old place, not no place`() {
        assertEquals(MenuPlace.TOP, MenuPlaces.read("CLASSIC=NOWHERE;;junk", PlayerButtonsStyle.CLASSIC))
        assertEquals(MenuPlace.BOTTOM, MenuPlaces.read("CONNECTED=", PlayerButtonsStyle.CONNECTED))
    }

    @Test
    fun `the picker has two places and no hidden choice`() {
        assertEquals(listOf(MenuPlace.TOP, MenuPlace.BOTTOM), MenuPlace.entries)
    }

    private fun src(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the player draws the menu in exactly the place chosen, and the settings and picture follow it`() {
        val player = src("ui/player/Player.kt")
        assertTrue("MenuPlaces.read(" in player)
        assertTrue("menuAtTop" in player && "menuAtBottom" in player)
        val frag = src("ui/screens/settings/fragments/PlayerButtonsFrag.kt")
        assertTrue("PlayerMenuPlaceKey" in frag && "MenuPlaces.write(" in frag)
        val sample = src("ui/screens/settings/fragments/PlayerStyleSample.kt")
        assertTrue("menuPlace" in sample)
    }
}
