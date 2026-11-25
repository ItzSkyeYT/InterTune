/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.ui.player.PlayerPin.CAST
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
    fun `never chosen, every place pins its defaults, Connected's row and Classic without the sleep timer since 0_13`() {
        val pins = PlayerPins.read("")
        // His pick of 20 Nov 2026: Classic as Connected, the timer in the menu; an install set up before keeps it (PinsBefore013Test).
        // The cast button by the title in both since 0.13, at the far end (CastPinTest).
        assertEquals(listOf(CAST, LIKE), pins[PinPlace.CLASSIC])
        assertEquals(listOf(CAST, SHARE, LIKE), pins[PinPlace.CONNECTED_TITLE])
        // His word of 19 Nov 2026: the sleep timer is in the menu by default; an install set up before keeps it (PinsBefore013Test).
        assertEquals(listOf(LYRICS), pins[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `a button added goes further out, so the ones beside the menu stay where they were`() {
        val pins = PlayerPins.toggle(PlayerPins.read("CLASSIC=TIMER+LIKE"), PinPlace.CLASSIC, SHARE)
        assertEquals(listOf(SHARE, TIMER, LIKE), pins[PinPlace.CLASSIC])
        val row = PlayerPins.toggle(PlayerPins.read("CONNECTED_ROW=LYRICS+TIMER"), PinPlace.CONNECTED_ROW, LIKE)
        assertEquals("like joins the row in front of lyrics and the timer", listOf(LIKE, LYRICS, TIMER), row[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `none pinned is the menu alone, and it is kept as chosen`() {
        var pins = PlayerPins.read("")
        pins = PlayerPins.toggle(pins, PinPlace.CLASSIC, LIKE)
        pins = PlayerPins.toggle(pins, PinPlace.CLASSIC, CAST)
        assertEquals(emptyList<PlayerPin>(), pins[PinPlace.CLASSIC])
        val again = PlayerPins.read(PlayerPins.write(pins))
        assertEquals("an empty place stays empty, not back to its defaults", emptyList<PlayerPin>(), again[PinPlace.CLASSIC])
        assertEquals(listOf(CAST, SHARE, LIKE), again[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `a full place takes no more, and Classic has one fewer beside the queue's circle`() {
        val full = PlayerPins.toggle(PlayerPins.read("CLASSIC=TIMER+LIKE"), PinPlace.CLASSIC, SHARE)
        assertEquals(full, PlayerPins.toggle(full, PinPlace.CLASSIC, LYRICS))
        val withQueue = PlayerPins.toggle(PlayerPins.read("CLASSIC=TIMER+LIKE"), PinPlace.CLASSIC, SHARE, most = PlayerPins.classicMost(queueCircle = true))
        assertEquals(listOf(TIMER, LIKE), withQueue[PinPlace.CLASSIC])
        val pair = PlayerPins.toggle(PlayerPins.read(""), PinPlace.CONNECTED_TITLE, TIMER)
        assertEquals("the pair by the title stays a pair, the cast button beside it", listOf(CAST, SHARE, LIKE), pair[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `in Connected a button is in one place at a time`() {
        var pins = PlayerPins.toggle(PlayerPins.read("CONNECTED_ROW=LYRICS+TIMER"), PinPlace.CONNECTED_TITLE, LIKE)
        assertEquals(listOf(CAST, SHARE), pins[PinPlace.CONNECTED_TITLE])
        pins = PlayerPins.toggle(pins, PinPlace.CONNECTED_TITLE, TIMER)
        assertEquals(listOf(CAST, TIMER, SHARE), pins[PinPlace.CONNECTED_TITLE])
        assertEquals("the timer left the row for the title", listOf(LYRICS), pins[PinPlace.CONNECTED_ROW])
        pins = PlayerPins.toggle(pins, PinPlace.CONNECTED_ROW, CAST)
        assertEquals("the cast button left the title for the row", listOf(TIMER, SHARE), pins[PinPlace.CONNECTED_TITLE])
        assertEquals(listOf(CAST, LYRICS), pins[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `reset puts back the shown style's buttons and leaves the other style's choice alone`() {
        var pins = PlayerPins.read("")
        pins = PlayerPins.toggle(pins, PinPlace.CLASSIC, SHARE)
        pins = PlayerPins.toggle(pins, PinPlace.CONNECTED_ROW, TIMER)
        assertTrue(PlayerPins.changed(pins, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)))
        val connectedBack = PlayerPins.reset(pins, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW))
        assertEquals("reset is today's default: the timer back in the menu", listOf(LYRICS), connectedBack[PinPlace.CONNECTED_ROW])
        assertEquals(listOf(CAST, SHARE, LIKE), connectedBack[PinPlace.CONNECTED_TITLE])
        assertEquals("Classic keeps its own choice", listOf(CAST, SHARE, LIKE), connectedBack[PinPlace.CLASSIC])
        assertFalse(PlayerPins.changed(connectedBack, listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)))
        assertEquals("written and read back, still the defaults", PlayerPins.read(""), PlayerPins.read(PlayerPins.write(PlayerPins.reset(pins, PinPlace.entries))))
    }

    @Test
    fun `a damaged setting loses only what cannot be read`() {
        val pins = PlayerPins.read("CLASSIC=LIKE+NOPE+SHARE;WHERE=LIKE;CONNECTED_ROW=;broken")
        assertEquals(listOf(SHARE, LIKE), pins[PinPlace.CLASSIC])
        assertEquals(emptyList<PlayerPin>(), pins[PinPlace.CONNECTED_ROW])
        assertEquals(listOf(CAST, SHARE, LIKE), pins[PinPlace.CONNECTED_TITLE])
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
        // A listening room's button, while one is on, sits by the title in the normal player and in a small player's row,
        // first there with ROOM_AT_FREE_END and right before the menu without it (RoomButtonStaysTest); the menu stays last.
        // The row's buttons are shaped by their place in the row, the menu's being the last unless it was put at the top (MenuPlaceTest).
        assertTrue("the menu is round on its own when nothing is pinned", player.contains("connectedShape(first = index == 0, last = index == rowTotal - 1)"))
        assertFalse("no button by the title is drawn outside the pins but a room's", player.contains("shape = connectedShape(first = !canShare, last = true)"))
    }
}
