/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `the buttons editor draws the three-dots button in the bar where the player has it`() {
        // Never chosen, the bars the editor has always ended with it: Classic's circles, Connected's row.
        assertEquals(PinPlace.CLASSIC, MenuPlaces.barOf(PlayerButtonsStyle.CLASSIC, MenuPlaces.defaultFor(PlayerButtonsStyle.CLASSIC)))
        assertEquals(PinPlace.CONNECTED_ROW, MenuPlaces.barOf(PlayerButtonsStyle.CONNECTED, MenuPlaces.defaultFor(PlayerButtonsStyle.CONNECTED)))
        // Moved: Classic's stands under the controls in no bar (the editor's corner box), Connected's ends the pair by
        // the title. The editor is given the choice and the bar of each place, and draws it where the choice puts it.
        assertNull(MenuPlaces.barOf(PlayerButtonsStyle.CLASSIC, MenuPlace.BOTTOM))
        assertEquals(PinPlace.CONNECTED_TITLE, MenuPlaces.barOf(PlayerButtonsStyle.CONNECTED, MenuPlace.TOP))
        val frag = src("ui/screens/settings/fragments/PlayerButtonsFrag.kt")
        assertTrue("menuAt = menuPlace," in frag && "menuBar = { MenuPlaces.barOf(buttonsStyle, it) }," in frag)
        val editor = src("ui/screens/settings/fragments/PlayerButtonsEditor.kt")
        assertTrue("val menuIn = MenuMoves.boxOf(menuPreview ?: menuAt, menuBar)" in editor)
        assertTrue("val menu: List<Spot> = if (place == menuIn) listOf(Spot.Menu) else emptyList()" in editor)
        assertTrue("spots = listOf(if (menuIn == Corner) Spot.Menu else Spot.Free)," in editor)
    }

    @Test
    fun `Classic at the bottom stands over the queue's strip, which lifts nothing`() {
        // A row of its own under Classic's controls lifted the controls, the seek bar, the title and the cover by 60dp
        // (seen on the emulator, 25 Nov 2026: pause at y 2542 at the top, 2362 at the bottom). His rule: a setting may
        // move its own row, the play controls never move. Since his pick of 27 Nov 2026 the controls stand in the half of
        // the queue's strip that the button had (CastAtTopTest), so it is laid over the strip's right corner, the Output
        // button then in the left one, after the queue's sheet so that its taps are its own, and it opens the player's menu.
        val player = src("ui/player/Player.kt")
        val corner = player.substringAfter("val menuInCorner = ").substringBefore("// Where the music is coming out, on a phone on its side")
        assertTrue(corner, corner.startsWith("buttonsStyle == PlayerButtonsStyle.CLASSIC && menuAtBottom\n"))
        assertTrue(corner, "if (menuInCorner) Box(" in corner && "Box(Modifier.offset(y = (-5).dp)) {" in corner && "onClick = showPlayerMenu," in corner)
        assertTrue(player.indexOf("val menuInCorner = ") > player.indexOf("QueueSheet("))
        // One opener for the menu, wherever the button stands.
        assertEquals(1, Regex("""val showPlayerMenu: \(\) -> Unit = \{""").findAll(player).count())
        // A row under the controls only where there is no strip: a small window, the two-pane layouts, a phone on its side.
        assertTrue("else if (menuAtBottom && queueLayout.outputRoom == 0.dp) {" in player)
        // The style's picture puts it at the strip's right too.
        assertTrue("Spot(u, SAMPLE_WIDTH - 24f - d, 175f, d, d)" in src("ui/screens/settings/fragments/PlayerStyleSample.kt"))
    }

    @Test
    fun `Classic's three-dots keeps the right corner in a slot as tall as the pill, and the pill takes the left one`() {
        // Round four's pictures (experiments/agents-1127/round-four, 27 Nov 2026): in one row with the Output button, which
        // centred the two, the button rose 4dp each time the pill came on (Options at y 2800 with it off, 2788 with it on,
        // on the emulator's 1344x2992), and the pill, left with what the button did not take of the half, said "This p...".
        val player = src("ui/player/Player.kt")
        val corner = player.substringAfter("val menuInCorner = ").substringBefore("// Where the music is coming out, on a phone on its side")
        assertFalse("no row holds the two any more", "Row(" in corner)
        // The button alone in the right corner, in a slot as tall as the pill, so the two are level and the pill moves it
        // by nothing.
        val slot = corner.substring(corner.indexOf("if (menuInCorner) Box("), corner.indexOf("PlayerCircleButton("))
        assertTrue(slot, "contentAlignment = Alignment.Center," in slot)
        assertTrue(slot, ".align(Alignment.BottomEnd)" in slot && ".padding(end = 24.dp, bottom = 10.dp)" in slot)
        assertTrue(slot, ".height(OutputPillHeight)" in slot)
        assertEquals(44.dp, OutputPillHeight)
        assertEquals("both pills, this phone's and a cast's", 2, Regex("""\.requiredHeight\(OutputPillHeight\)""").findAll(src("ui/player/CastPicker.kt")).count())
        // The pill in the left corner, mirrored: 24dp in from the side, 10dp up, and never as far as the queue's arrow.
        val pill = corner.substring(corner.indexOf("OutputButton("), corner.indexOf("if (menuInCorner) Box("))
        assertTrue(pill, ".align(if (menuInCorner) Alignment.BottomStart else Alignment.BottomEnd)" in pill)
        assertTrue(pill, "WindowInsetsSides.Bottom + if (menuInCorner) WindowInsetsSides.Start else WindowInsetsSides.End" in pill)
        assertTrue(pill, ".padding(start = if (menuInCorner) 24.dp else 0.dp, end = if (menuInCorner) 0.dp else 24.dp, bottom = 10.dp)" in pill)
        assertTrue(pill, ".widthIn(max = windowWidth() / 2 - 24.dp - 32.dp)" in pill)
    }
}
