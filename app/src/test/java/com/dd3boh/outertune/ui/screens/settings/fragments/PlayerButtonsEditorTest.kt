/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player buttons editor on the Look and feel page (Ti-Die's ask of 26 Nov 2026, mock 2 his pick): it replaces the
 * chips, keeps their storage and the way back to the default, moves buttons only through PinMoves, and can be used
 * without dragging, by a tap or by TalkBack's actions.
 */
class PlayerButtonsEditorTest {
    private val folder = File("src/main/java/com/dd3boh/outertune")
    private val page = File(folder, "ui/screens/settings/fragments/PlayerButtonsFrag.kt").readText()
    private val editor = File(folder, "ui/screens/settings/fragments/PlayerButtonsEditor.kt").readText()

    @Test
    fun `the page shows the editor in place of the chips, under the same tour mark`() {
        assertTrue("PlayerButtonsEditor(" in page)
        // No chip is left for a button: the page's only chips are the three-dots button's Top and Bottom, under the
        // editor (MenuPlaceTest), and they write its place, never the buttons.
        val menuRow = page.indexOf("Modifier.settingRow(R.string.player_menu_place)")
        assertTrue(page.indexOf("PlayerButtonsEditor(") in 0 until menuRow)
        val chips = Regex("""FilterChip\(""").findAll(page).map { it.range.first }.toList()
        assertEquals(1, chips.size)
        assertTrue(chips.single() > menuRow)
        val chip = page.substring(chips.single()).substringBefore("SwitchPreference(")
        assertTrue(chip, "onMenuPlaceRawChange(MenuPlaces.write(" in chip)
        assertFalse(chip, "onPinsRawChange" in chip)
        assertTrue(page.indexOf("tourTarget(Tour.SETTING_PLAYER_PINS)") < page.indexOf("PlayerButtonsEditor("))
    }

    @Test
    fun `the choice is read and written as before, by the page alone`() {
        assertTrue("val pins = remember(pinsRaw) { PlayerPins.read(pinsRaw) }" in page)
        assertTrue("onPinsChange = { onPinsRawChange(PlayerPins.write(it)) }," in page)
        // The editor never touches the setting itself, and moves only through PinMoves, which goes through toggle.
        assertFalse("PlayerPinsKey" in editor)
        assertFalse("PlayerPins.toggle" in editor)
        assertEquals(3, Regex("""PinMoves\.move\(""").findAll(editor).count())
        val moves = File(folder, "ui/player/PinMoves.kt").readText()
        assertTrue("PlayerPins.toggle(" in moves)
    }

    @Test
    fun `the limits are the player's, Classic one fewer beside the queue's circle, the others their own`() {
        assertTrue("most = { place -> if (place == PinPlace.CLASSIC) PlayerPins.classicMost(queueAsButton) else place.most }," in page)
        // Cast only where the app casts, as the chips had it: on the preview line through pinOffered, which also offers
        // every entry of the player's menu, and Listen together where rooms are.
        assertTrue("available = PlayerPin.entries.filter { pinOffered(it) }," in page)
        val actions = File(folder, "ui/player/PinActions.kt").readText()
        assertTrue("PlayerPin.CAST -> Unreleased.CASTING" in actions.substringAfter("fun pinOffered(").substringBefore("\n}"))
    }

    @Test
    fun `reset to default stays, greyed out while nothing differs`() {
        assertTrue("enabled = PlayerPins.changed(pins, places)," in page)
        assertTrue("onClick = { onPinsRawChange(PlayerPins.write(PlayerPins.reset(pins, places))) }," in page)
    }

    @Test
    fun `a full place refuses the drop and its count shows it, and every move works without dragging`() {
        // Refused: red and a haptic; the count in the box's head turns red with it.
        assertTrue("refused = box as PinPlace" in editor)
        assertTrue("HapticFeedbackType.Reject" in editor)
        assertTrue("color = if (refusing) colors.error else colors.onSurfaceVariant," in editor)
        // A tap opens the moves, TalkBack has them as actions, and a full place's move is offered greyed, never taken.
        assertTrue(".clickable { if (lifted == null) menuOf = pin }" in editor)
        assertTrue("customActions = moves.filter { it.enabled }" in editor)
        assertTrue("enabled = move.enabled," in editor)
    }

    @Test
    fun `its words are in English and French`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("player_pins_not_shown", "player_pins_full", "player_pins_menu", "player_pins_put_by_title", "player_pins_put_under_controls", "player_pins_remove")) {
            assertTrue(name, "<string name=\"$name\">" in english)
            assertTrue(name, "<string name=\"$name\">" in french)
        }
        assertTrue("<plurals name=\"player_pins_free\">" in english && "<plurals name=\"player_pins_free\">" in french)
    }
}
