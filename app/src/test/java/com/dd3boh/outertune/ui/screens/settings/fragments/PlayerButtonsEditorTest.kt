/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.player.PlayerPin
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
        // Cast only where the app casts, as the chips had it.
        assertTrue("available = PlayerPin.entries.filter { it != PlayerPin.CAST || Unreleased.CASTING }," in page)
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
    fun `Not shown stands four to a row, as wide and as far apart as a bar's four, filling the box as they do`() {
        // His phone's box on 27 Nov 2026 (about 287dp inside): the bar under the controls fills it with four, a row too.
        for (circles in listOf(false, true)) {
            val gap = spotGap(circles)
            val cell = spotWidth(287.dp, NotShownRows.ROW, gap)
            assertEquals(287f, (cell * NotShownRows.ROW + gap * (NotShownRows.ROW - 1)).value, 0.01f)
        }
        assertEquals(4, NotShownRows.ROW)
        // No wider than a thumb needs on a wide screen, where the row is centred as the bar of four is.
        assertEquals(72.dp, spotWidth(400.dp, NotShownRows.ROW, spotGap(circles = false)))
        // The bars and the rows size their spots by the same rule, with the style's own gap.
        assertTrue("val cell = spotWidth(maxWidth, spots.size, gap)" in editor)
        assertTrue("val cell = spotWidth(maxWidth, NotShownRows.ROW, gap)" in editor)
        assertEquals(2, Regex("""val gap = spotGap\(circles\)""").findAll(editor).count())
        assertTrue("circles = PinPlace.CLASSIC in places," in editor)
        // A short last row keeps the columns; a long name has a third line rather than being cut.
        assertTrue("repeat(NotShownRows.ROW - row.size) { Spacer(Modifier.width(cell)) }" in editor)
        assertTrue("Labelled(width, name, dim = ghost, lines = 3) {" in editor)
    }

    @Test
    fun `Not shown shows its first row until Show more, folded again each time the page opens`() {
        val rest = listOf(PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE, PlayerPin.CAST)
        assertEquals(rest.take(4), NotShownRows.drawn(rest, open = false))
        assertEquals(rest, NotShownRows.drawn(rest, open = true))
        assertEquals(1, NotShownRows.folded(rest))
        assertEquals("four or fewer: nothing to fold, no Show more", 0, NotShownRows.folded(rest.take(4)))
        assertTrue("NotShownRows.drawn(rest, open).chunked(NotShownRows.ROW)" in editor)
        assertTrue("if (folded > 0) {" in editor)
        // Not saved: remembered for the page's life only.
        assertTrue("var unfolded by remember { mutableStateOf(false) }" in editor)
        assertFalse("rememberSaveable" in editor)
        // The app's own words for it, Stats' Show N more and Show fewer, already in English and French.
        assertTrue("pluralStringResource(R.plurals.stats_show_more, folded," in editor)
        assertTrue("stringResource(R.string.stats_show_fewer)" in editor)
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (words in listOf(english, french)) {
            assertTrue("<plurals name=\"stats_show_more\">" in words && "<string name=\"stats_show_fewer\">" in words)
        }
    }

    @Test
    fun `a button put back in folded Not shown is seen where it lands, and one folded away is under no finger`() {
        val rest = listOf(PlayerPin.SHARE, PlayerPin.LYRICS, PlayerPin.TIMER, PlayerPin.LIKE, PlayerPin.CAST)
        assertFalse("in the first row it shows", NotShownRows.hides(rest, PlayerPin.SHARE))
        assertTrue("past it the box opens", NotShownRows.hides(rest, PlayerPin.CAST))
        // The folded box takes the drop as one box, and opens for it, by a drop or by Remove, the menu's or TalkBack's.
        assertTrue("modifier = Modifier.onGloballyPositioned { boxBounds[NotShown] = it.boundsInRoot() }," in editor)
        assertTrue("if (box == NotShown) showWhereItLands(after, pin)" in editor)
        assertTrue("if (to == null) showWhereItLands(it, pin)" in editor)
        // A button folded away leaves no spot behind that a long press would lift it from.
        assertTrue("DisposableEffect(pin) { onDispose { buttonBounds.remove(pin) } }" in editor)
        assertTrue("row.forEach { pin -> key(pin) { tile(pin, cell) } }" in editor)
        assertTrue("is Spot.Button -> key(spot.pin) {" in editor)
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
