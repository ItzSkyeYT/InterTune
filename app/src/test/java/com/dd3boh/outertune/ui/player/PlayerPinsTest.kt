/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.ui.player.PlayerPin.LIKE
import com.dd3boh.outertune.ui.player.PlayerPin.LYRICS
import com.dd3boh.outertune.ui.player.PlayerPin.SHARE
import com.dd3boh.outertune.ui.player.PlayerPin.TIMER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's pinned buttons chosen per place (issue #27): nothing moves for anybody who never
 * chooses, a button added goes further out from the menu, and a place can be left with the menu
 * alone.
 */
class PlayerPinsTest {

    @Test
    fun `never chosen, every place pins what it always has`() {
        val pins = PlayerPins.read("")
        assertEquals(listOf(TIMER, LIKE), pins[PinPlace.CLASSIC])
        assertEquals(listOf(SHARE, LIKE), pins[PinPlace.CONNECTED_TITLE])
        assertEquals(listOf(LYRICS, TIMER), pins[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `a button added goes further out, so the ones beside the menu stay where they were`() {
        val pins = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CLASSIC, SHARE)
        assertEquals(listOf(SHARE, TIMER, LIKE), pins[PinPlace.CLASSIC])
        val row = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_ROW, LIKE)
        assertEquals("like joins the row in front of lyrics and the timer", listOf(LIKE, LYRICS, TIMER), row[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `none pinned is the menu alone, and it is kept as chosen`() {
        var pins = PlayerPins.read("")
        pins = PlayerPins.toggle(pins, PinPlace.CLASSIC, TIMER)
        pins = PlayerPins.toggle(pins, PinPlace.CLASSIC, LIKE)
        assertEquals(emptyList<PlayerPin>(), pins[PinPlace.CLASSIC])
        val again = PlayerPins.read(PlayerPins.write(pins))
        assertEquals("an empty place stays empty, not back to its defaults", emptyList<PlayerPin>(), again[PinPlace.CLASSIC])
        assertEquals(listOf(SHARE, LIKE), again[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `a full place takes no more, and Classic has one fewer beside the queue's circle`() {
        val full = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CLASSIC, SHARE)
        assertEquals(full, PlayerPins.toggle(full, PinPlace.CLASSIC, LYRICS))
        val withQueue = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CLASSIC, SHARE, most = PlayerPins.classicMost(queueCircle = true))
        assertEquals(listOf(TIMER, LIKE), withQueue[PinPlace.CLASSIC])
        val pair = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_TITLE, TIMER)
        assertEquals("the pair by the title stays a pair", listOf(SHARE, LIKE), pair[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `in Connected a button is in one place at a time`() {
        var pins = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_TITLE, LIKE)
        assertEquals(listOf(SHARE), pins[PinPlace.CONNECTED_TITLE])
        pins = PlayerPins.toggle(pins, PinPlace.CONNECTED_TITLE, TIMER)
        assertEquals(listOf(TIMER, SHARE), pins[PinPlace.CONNECTED_TITLE])
        assertEquals("the timer left the row for the title", listOf(LYRICS), pins[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `reset puts back the shown style's buttons and leaves the other style's choice alone`() {
        var pins = PlayerPins.read("")
        pins = PlayerPins.toggle(pins, PinPlace.CLASSIC, SHARE)
        pins = PlayerPins.toggle(pins, PinPlace.CONNECTED_ROW, TIMER)
        assertTrue(PlayerPins.changed(pins, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)))
        val connectedBack = PlayerPins.reset(pins, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW))
        assertEquals(listOf(LYRICS, TIMER), connectedBack[PinPlace.CONNECTED_ROW])
        assertEquals(listOf(SHARE, LIKE), connectedBack[PinPlace.CONNECTED_TITLE])
        assertEquals("Classic keeps its own choice", listOf(SHARE, TIMER, LIKE), connectedBack[PinPlace.CLASSIC])
        assertFalse(PlayerPins.changed(connectedBack, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)))
        assertEquals("written and read back, still the defaults", PlayerPins.read(""), PlayerPins.read(PlayerPins.write(PlayerPins.reset(pins, PinPlace.entries))))
    }

    @Test
    fun `every entry of the menu and Listen together can be pinned, further out than the four`() {
        val classic = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CLASSIC, PlayerPin.RADIO)
        assertEquals(listOf(PlayerPin.RADIO, TIMER, LIKE), classic[PinPlace.CLASSIC])
        val row = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_ROW, PlayerPin.LISTEN_TOGETHER)
        assertEquals(listOf(PlayerPin.LISTEN_TOGETHER, LYRICS, TIMER), row[PinPlace.CONNECTED_ROW])
        // Every place can take every pin.
        PinPlace.entries.forEach { place -> assertEquals(PlayerPin.entries.toSet(), place.order.toSet()) }
    }

    @Test
    fun `the pins cover every entry of the player's menu`() {
        // His word of 18 Oct 2026: "plus add the listen together button, and all the other buttons in the three dot menu".
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt").readText()
        val actions = File("src/main/java/com/dd3boh/outertune/ui/player/PinActions.kt").readText()
        val titles = Regex("""title = R\.string\.(\w+)""").findAll(menu).map { it.groupValues[1] }.toSet()
        assertTrue("the menu read", titles.size >= 12)
        // Toggle lyrics is the Lyrics pin under its own short name.
        val missing = titles - "toggle_lyrics" - "grp_lyrics_format" - "grp_lyrics_source" - "grp_lyrics_parser"
        missing.forEach { title -> assertTrue("no pin for the menu's $title", actions.contains("R.string.$title")) }
        assertTrue("download, in both its states", actions.contains("R.string.action_download") && actions.contains("R.string.remove_download"))
        assertTrue("the sleep timer", actions.contains("R.string.sleep_timer"))
    }

    @Test
    fun `a pinned Listen together is the room's button, never a second one beside it`() {
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        assertTrue(player.contains("val roomApart = inRoom && PlayerPin.LISTEN_TOGETHER !in stylePins"))
        assertFalse("the room's own button follows roomApart everywhere", Regex("""if \(inRoom\)""").containsMatchIn(player))
    }

    @Test
    fun `a damaged setting loses only what cannot be read`() {
        val pins = PlayerPins.read("CLASSIC=LIKE+NOPE+SHARE;WHERE=LIKE;CONNECTED_ROW=;broken")
        assertEquals(listOf(SHARE, LIKE), pins[PinPlace.CLASSIC])
        assertEquals(emptyList<PlayerPin>(), pins[PinPlace.CONNECTED_ROW])
        assertEquals(listOf(SHARE, LIKE), pins[PinPlace.CONNECTED_TITLE])
        val tooMany = PlayerPins.read("CLASSIC=SHARE+LYRICS+TIMER+LIKE")
        assertEquals("the ones beside the menu are the ones kept", listOf(LYRICS, TIMER, LIKE), tooMany[PinPlace.CLASSIC])
    }

    @Test
    fun `the player draws each place from the pins, with the menu after them`() {
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        assertTrue(player.contains("classicPins(queueCircle = queueLayout.queueButton).forEach { pin ->"))
        assertTrue(player.contains("val pinned = classicPins(queueCircle = true)"))
        assertTrue(player.contains("pins[PinPlace.CONNECTED_TITLE].orEmpty()"))
        assertTrue(player.contains("pins[PinPlace.CONNECTED_ROW].orEmpty()"))
        assertTrue("the menu is round on its own when nothing is pinned", player.contains("connectedShape(first = rowPins.isEmpty() && !roomApart && !queueLayout.queueButton, last = true)"))
        assertFalse("no button by the title is drawn outside the pins", player.contains("shape = connectedShape(first = !canShare, last = true)"))
    }
}
