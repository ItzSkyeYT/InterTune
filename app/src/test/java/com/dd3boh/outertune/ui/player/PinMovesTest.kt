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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player buttons editor's moves (Ti-Die's ask of 26 Nov 2026): the room of each place, the cast button and the
 * menu taking none, a drop that does not fit refused, and every move stored as the chips stored it, so nobody's choice
 * changes and whatever the chips could choose the editor can.
 */
class PinMovesTest {
    private val connected = listOf(PinPlace.CONNECTED_TITLE, PinPlace.CONNECTED_ROW)
    private val classic = listOf(PinPlace.CLASSIC)
    private val most: (PinPlace) -> Int = { it.most }

    @Test
    fun `Classic takes 3, by the title 2, the row 3, and Classic one fewer beside the queue's circle`() {
        assertEquals(3, PinMoves.free(emptyList(), PinPlace.CLASSIC.most))
        assertEquals(2, PinMoves.free(emptyList(), PinPlace.CONNECTED_TITLE.most))
        assertEquals(3, PinMoves.free(emptyList(), PinPlace.CONNECTED_ROW.most))
        assertEquals(2, PinMoves.free(emptyList(), PlayerPins.classicMost(queueCircle = true)))
        // Never chosen: Classic has like (2 free), by the title share and like (full), the row lyrics (2 free).
        val pins = PlayerPins.read("")
        assertEquals(2, PinMoves.free(pins.getValue(PinPlace.CLASSIC), PinPlace.CLASSIC.most))
        assertEquals(0, PinMoves.free(pins.getValue(PinPlace.CONNECTED_TITLE), PinPlace.CONNECTED_TITLE.most))
        assertEquals(2, PinMoves.free(pins.getValue(PinPlace.CONNECTED_ROW), PinPlace.CONNECTED_ROW.most))
    }

    @Test
    fun `the cast button takes no room and always fits, a full place too`() {
        assertEquals(0, PinMoves.free(listOf(CAST, SHARE, LIKE), 2))
        assertEquals(1, PinMoves.free(listOf(CAST, LIKE), 2))
        val full = PlayerPins.read("CONNECTED_TITLE=SHARE+LIKE;CONNECTED_ROW=CAST+LYRICS")
        val moved = PinMoves.move(full, connected, CAST, PinPlace.CONNECTED_TITLE, most)!!
        assertEquals(listOf(CAST, SHARE, LIKE), moved[PinPlace.CONNECTED_TITLE])
        assertEquals("it leaves the row it was in", listOf(LYRICS), moved[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `the menu takes no room, the editor draws it after the free spots, outside the count`() {
        val editor = java.io.File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/PlayerButtonsEditor.kt").readText()
        assertTrue("val free = PinMoves.free(chosen, most(place))" in editor)
        assertTrue("val spots: List<Spot> = queue + List(free) { Spot.Free } + chosen.map { Spot.Button(it) } + menu" in editor)
    }

    @Test
    fun `a drop that does not fit is refused, and nothing changes`() {
        val pins = PlayerPins.read("")
        // By the title is full with share and like: lyrics from the row cannot go there.
        assertNull(PinMoves.move(pins, connected, LYRICS, PinPlace.CONNECTED_TITLE, most))
        assertFalse(PinMoves.fits(pins, PinPlace.CONNECTED_TITLE, LYRICS, 2))
        assertTrue("the one already there fits where it is", PinMoves.fits(pins, PinPlace.CONNECTED_TITLE, SHARE, 2))
        // Classic full beside the queue's circle: two fit, not three.
        val classicTwo = PlayerPins.read("CLASSIC=TIMER+LIKE")
        assertNull(PinMoves.move(classicTwo, classic, SHARE, PinPlace.CLASSIC) { PlayerPins.classicMost(queueCircle = true) })
        assertEquals(listOf(SHARE, TIMER, LIKE), PinMoves.move(classicTwo, classic, SHARE, PinPlace.CLASSIC, most)!![PinPlace.CLASSIC])
    }

    @Test
    fun `a button moves between places, takes its own spot in the order, and goes back to Not shown`() {
        val pins = PlayerPins.read("CONNECTED_TITLE=CAST+LIKE;CONNECTED_ROW=LYRICS+SHARE")
        val up = PinMoves.move(pins, connected, SHARE, PinPlace.CONNECTED_TITLE, most)!!
        assertEquals("share stands where the player has always put it, beside like", listOf(CAST, SHARE, LIKE), up[PinPlace.CONNECTED_TITLE])
        assertEquals(listOf(LYRICS), up[PinPlace.CONNECTED_ROW])
        val gone = PinMoves.move(up, connected, LYRICS, null, most)!!
        assertEquals(emptyList<PlayerPin>(), gone[PinPlace.CONNECTED_ROW])
        assertEquals(null, PinMoves.placeOf(gone, connected, LYRICS))
        val back = PinMoves.move(gone, connected, LYRICS, PinPlace.CONNECTED_ROW, most)!!
        assertEquals(listOf(LYRICS), back[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `dropped back where it is, or a button not shown dropped in Not shown, nothing changes`() {
        val pins = PlayerPins.read("")
        assertEquals("not taken off, as a second tap on a chip would", pins, PinMoves.move(pins, connected, SHARE, PinPlace.CONNECTED_TITLE, most))
        assertEquals(pins, PinMoves.move(pins, connected, TIMER, null, most))
        // Connected's moves leave Classic's choice alone, and Classic's leave Connected's.
        val moved = PinMoves.move(pins, connected, LIKE, null, most)!!
        assertEquals(pins[PinPlace.CLASSIC], moved[PinPlace.CLASSIC])
        val classicMoved = PinMoves.move(pins, classic, LIKE, null, most)!!
        assertEquals(pins[PinPlace.CONNECTED_TITLE], classicMoved[PinPlace.CONNECTED_TITLE])
    }

    @Test
    fun `stored as the chips stored it, so the same choice writes the same setting`() {
        val start = PlayerPins.read("")
        // Putting the sleep timer under the controls: the chip's tap and the editor's drop write the same words.
        val chip = PlayerPins.write(PlayerPins.toggle(start, PinPlace.CONNECTED_ROW, TIMER))
        val drop = PlayerPins.write(PinMoves.move(start, connected, TIMER, PinPlace.CONNECTED_ROW, most)!!)
        assertEquals(chip, drop)
        assertEquals("CLASSIC=CAST+LIKE;CONNECTED_TITLE=CAST+SHARE+LIKE;CONNECTED_ROW=LYRICS+TIMER", drop)
        // And what is read back is what was chosen.
        assertEquals(listOf(LYRICS, TIMER), PlayerPins.read(drop)[PinPlace.CONNECTED_ROW])
    }

    @Test
    fun `whatever the chips could choose, the editor can`() {
        // Every set of buttons a place could hold with the chips, built button by button with the editor from none.
        val buttons = listOf(SHARE, LYRICS, TIMER, LIKE)
        for (place in PinPlace.entries) {
            val places = if (place == PinPlace.CLASSIC) classic else connected
            for (mask in 0 until (1 shl buttons.size)) {
                val wanted = buttons.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                if (wanted.size > place.most) continue
                for (withCast in listOf(false, true)) {
                    var pins = PlayerPins.read(places.joinToString(";") { "${it.name}=" })
                    for (pin in wanted + listOfNotNull(CAST.takeIf { withCast })) pins = PinMoves.move(pins, places, pin, place, most)!!
                    val expected = place.order.filter { it in wanted || (withCast && it == CAST) }
                    assertEquals("$place $wanted cast=$withCast", expected, pins[place])
                }
            }
        }
    }
}
