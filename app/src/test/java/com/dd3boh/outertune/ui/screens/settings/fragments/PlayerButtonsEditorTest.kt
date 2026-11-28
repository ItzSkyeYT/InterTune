/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.PlayerButtonsStyle
import com.dd3boh.outertune.ui.player.MenuPlace
import com.dd3boh.outertune.ui.player.MenuPlaces
import com.dd3boh.outertune.ui.player.PinPlace
import com.dd3boh.outertune.ui.player.PlayerPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the page shows the editor under the same tour mark and no chips, the three dots moved in it alone`() {
        assertTrue("PlayerButtonsEditor(" in page)
        assertTrue(page.indexOf("tourTarget(Tour.SETTING_PLAYER_PINS)") < page.indexOf("PlayerButtonsEditor("))
        // No chip is left: the buttons' went with the editor of 26 Nov 2026, the three-dots button's Top and Bottom on
        // 28 Nov 2026 (his "I forgot to remove the toggle"). The three dots are moved in the editor like the others.
        assertFalse("FilterChip(" in page)
        for (row in listOf("R.string.player_menu_place", "R.string.player_menu_top", "R.string.player_menu_bottom")) {
            assertFalse(row, row in page)
        }
        // Gone from the search with its row, its name finding Pinned buttons instead (SettingsSearchTest).
        val index = File(folder, "ui/screens/settings/SettingsIndex.kt").readText()
        assertFalse("SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_menu_place" in index)
        assertTrue("SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_pins, words = listOf(R.string.player_menu_place))," in index)
        // Its words stay with the translators.
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("player_menu_place", "player_menu_top", "player_menu_bottom")) {
            assertTrue(name, "<string name=\"$name\">" in english && "<string name=\"$name\">" in french)
        }
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
    fun `a full place refuses the drop and its Full shows it, and every move works without dragging`() {
        // Refused: red and a haptic; Full in the box's head turns red with it.
        assertTrue("refused = box" in editor)
        assertTrue("HapticFeedbackType.Reject" in editor)
        assertTrue("color = if (refusing) colors.error else colors.onSurfaceVariant," in editor)
        // A tap opens the moves, TalkBack has them as actions, and a full place's move is offered greyed, never taken.
        assertTrue(".clickable { if (lifted == null) menuOf = button }" in editor)
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
        assertTrue("if (box == NotShown) showWhereItLands(after, button)" in editor)
        assertTrue("if (to == null) showWhereItLands(it, pin)" in editor)
        // A button folded away leaves no spot behind that a long press would lift it from.
        assertTrue("DisposableEffect(button) { onDispose { buttonBounds.remove(button) } }" in editor)
        assertTrue("row.forEach { pin -> key(pin) { tile(pin, cell) } }" in editor)
        assertTrue("is Spot.Button -> key(spot.pin) {" in editor)
    }

    @Test
    fun `the three-dots button goes between its two places like a button, Classic's corner one of them, never Not shown`() {
        val classic = { at: MenuPlace -> MenuPlaces.barOf(PlayerButtonsStyle.CLASSIC, at) }
        val connected = { at: MenuPlace -> MenuPlaces.barOf(PlayerButtonsStyle.CONNECTED, at) }
        // Connected: the pair by the title at the top, the row under the controls at the bottom.
        assertEquals(MenuPlace.TOP, MenuMoves.placeOver(PinPlace.CONNECTED_TITLE, connected))
        assertEquals(MenuPlace.BOTTOM, MenuMoves.placeOver(PinPlace.CONNECTED_ROW, connected))
        assertFalse(MenuMoves.hasCorner(connected))
        // Classic: its circles at the top; at the bottom its corner, in no bar, which has a box of its own.
        assertEquals(MenuPlace.TOP, MenuMoves.placeOver(PinPlace.CLASSIC, classic))
        assertEquals(MenuPlace.BOTTOM, MenuMoves.placeOver(Corner, classic))
        assertEquals(Corner, MenuMoves.boxOf(MenuPlace.BOTTOM, classic))
        assertTrue(MenuMoves.hasCorner(classic))
        // Never Not shown, nor outside the boxes, nor a corner the style has not got.
        for (bar in listOf(classic, connected)) {
            assertNull(MenuMoves.placeOver(NotShown, bar))
            assertNull(MenuMoves.placeOver(null, bar))
        }
        assertNull(MenuMoves.placeOver(Corner, connected))
        // A tap or TalkBack offers its other place, with no count, as it takes no room, and never Remove.
        assertEquals(listOf(MenuPlace.BOTTOM), MenuMoves.targets(MenuPlace.TOP))
        assertEquals(listOf(MenuPlace.TOP), MenuMoves.targets(MenuPlace.BOTTOM))
        val menuMoves = editor.substringAfter("fun menuMoves(): List<Move>").substringBefore("@Composable")
        assertTrue(menuMoves, "MenuMoves.targets(menuAt)" in menuMoves && "null, true)" in menuMoves)
        assertFalse(menuMoves, "removeLabel" in menuMoves)
        // Lifted, tapped and read out as any button: the same Movable, in each bar and in the corner.
        assertEquals(2, Regex("""movableMenu = \{ content -> Movable\(MenuButton, menuMoves\(\), content\) \},""").findAll(editor).count())
        assertTrue("Spot.Menu -> movableMenu {" in editor)
    }

    @Test
    fun `its place is the one the Top and Bottom chips stored, read as they left it and written by the editor alone`() {
        assertTrue("menuAt = menuPlace," in page)
        assertTrue("menuBar = { MenuPlaces.barOf(buttonsStyle, it) }," in page)
        assertTrue("onMenuMove = { onMenuPlaceRawChange(MenuPlaces.write(menuPlaceRaw, buttonsStyle, it)) }," in page)
        // The one setting the chips wrote, still read for the editor and the style pictures.
        assertTrue("rememberPreference(PlayerMenuPlaceKey, defaultValue = \"\")" in page)
        assertTrue("val menuPlace = MenuPlaces.read(menuPlaceRaw, buttonsStyle)" in page)
        assertTrue("menuPlace = MenuPlaces.read(menuPlaceRaw, style)," in page)
        // Written by the editor's move only now, through the page; the editor never touches the setting itself.
        assertEquals(1, Regex("""onMenuPlaceRawChange\(MenuPlaces\.write\(menuPlaceRaw, buttonsStyle, """).findAll(page).count())
        assertFalse("PlayerMenuPlaceKey" in editor)
        assertFalse("MenuPlaces.write" in editor)
        // What the chips stored is read the same: an install that picked Bottom keeps Bottom, in Classic's corner box.
        val classic = { at: MenuPlace -> MenuPlaces.barOf(PlayerButtonsStyle.CLASSIC, at) }
        assertEquals(MenuPlace.BOTTOM, MenuPlaces.read("CLASSIC=BOTTOM", PlayerButtonsStyle.CLASSIC))
        assertEquals(Corner, MenuMoves.boxOf(MenuPlaces.read("CLASSIC=BOTTOM", PlayerButtonsStyle.CLASSIC), classic))
        assertEquals(MenuPlace.TOP, MenuPlaces.read("CLASSIC=BOTTOM;CONNECTED=TOP", PlayerButtonsStyle.CONNECTED))
    }

    @Test
    fun `a bar says Full once it is full and counts no free places, as its dotted spots show them`() {
        // Ti-Die on 28 Nov 2026, with his thumbs up: "the text 'full' is okay, but '1 left' is redundant as the dotted outlines exist".
        assertFalse("R.plurals.player_pins_free" in editor)
        val room = editor.substringAfter("private fun roomText(").substringBefore("\n\n")
        assertTrue(room, "if (free <= 0) stringResource(R.string.player_pins_full) else null" in room)
        // The same in each bar's head and beside a move in a tap's menu, where a full bar's move is greyed.
        assertTrue("room = roomText(free)," in editor)
        assertTrue("val roomTexts = places.associateWith { roomText(PinMoves.free(pins[it].orEmpty(), most(it))) }" in editor)
        // The free spots are still drawn, dotted.
        assertTrue("List(free) { Spot.Free }" in editor)
    }

    @Test
    fun `Classic's corner says why it refuses a button, in red beside its spot, and TalkBack's move says the same`() {
        // His answer of 28 Nov 2026, "Say why on a refused drop": while a button is held over the corner, and after its drop.
        val corner = editor.substringAfter("if (corner) {").substringBefore("val placed = ")
        assertTrue(corner, "text = if (refusing == Corner) stringResource(R.string.player_pins_classic_menu_only) else \"\"," in corner)
        assertTrue(corner, "color = MaterialTheme.colorScheme.error," in corner)
        // Beside the corner's spot, never under it.
        assertTrue(corner, ".padding(end = spotWidth(maxWidth, 1, spotGap(circles = true)) + spotGap(circles = true))" in corner)
        // Said by TalkBack as the words come: the line is always there, empty the rest of the time.
        assertTrue(corner, ".semantics { liveRegion = LiveRegionMode.Polite }" in corner)
        // Up long enough to read once the button is let go.
        assertTrue("delay(if (refused == Corner) 5000 else 1600)" in editor)
        // TalkBack's move under the controls in Classic, an action only: refused as the drop is, and the line comes.
        val moves = editor.substringAfter("fun movesOf(pin: PlayerPin): List<Move> {").substringBefore("\n    }\n")
        assertTrue(moves, "if (corner) Move(putUnderControls, null, enabled = true, inMenu = false) { refuseInCorner() }" in moves)
        val refuse = editor.substringAfter("fun refuseInCorner() {").substringBefore("\n    }\n")
        assertTrue(refuse, "refused = Corner" in refuse && "haptics.performHapticFeedback(HapticFeedbackType.Reject)" in refuse)
        assertTrue("moves.filter { it.inMenu }.forEach { move ->" in editor)
        // The words settled, in English and French.
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue("<string name=\"player_pins_classic_menu_only\">In Classic, only the menu goes here. Connected lets you pin buttons under the controls.</string>" in english)
        assertTrue("<string name=\"player_pins_classic_menu_only\">Avec les boutons Classiques, seul le menu va ici. Connectés permet d\\'épingler des boutons sous les commandes.</string>" in french)
    }

    @Test
    fun `Not shown refuses the three-dots button and says why, and Classic's corner refuses every other button`() {
        // Held over Not shown: red, the Reject haptic, and why in its head, only while it refuses.
        assertTrue("held == MenuButton && hover != null && menuPreview == null -> hover" in editor)
        assertTrue("room = if (refusing == NotShown) stringResource(R.string.player_pins_menu_stays) else null," in editor)
        // The corner says what it takes; a button dropped there is refused as by a full place.
        assertTrue("room = stringResource(R.string.player_pins_menu_only)," in editor)
        assertTrue("Corner -> null" in editor)
        assertEquals(2, Regex("""refused = box\n\s+haptics\.performHapticFeedback\(HapticFeedbackType\.Reject\)""").findAll(editor).count())
        // Only the boxes on the page take a drop, none of the other style's left from before a switch.
        assertTrue("boxesAtLift.putAll(boxBounds.filterKeys { it in currentBoxes })" in editor)
    }

    @Test
    fun `its words are in English and French`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf(
            "player_pins_not_shown", "player_pins_full", "player_pins_menu", "player_pins_put_by_title", "player_pins_put_under_controls", "player_pins_remove",
            "player_pins_menu_only", "player_pins_menu_stays", "player_pins_classic_menu_only",
        )) {
            assertTrue(name, "<string name=\"$name\">" in english)
            assertTrue(name, "<string name=\"$name\">" in french)
        }
        // No longer said, but kept for the translators who have it.
        assertTrue("<plurals name=\"player_pins_free\">" in english && "<plurals name=\"player_pins_free\">" in french)
    }
}
