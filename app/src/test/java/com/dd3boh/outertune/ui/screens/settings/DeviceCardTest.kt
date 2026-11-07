/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The card under About's version line (his ask of 7 Nov 2026, "unique for each device"): drawn
 * from a hash of the device's id alone, the same every time on one device and never the same on
 * two.
 */
class DeviceCardTest {
    private val ids = (0 until 40).map { "device-$it" } + listOf("", "9774d56d682e549c", "f0e1d2c3b4a59687", "a".repeat(64))

    @Test
    fun `the same id always gives the same card`() {
        for (id in ids) assertEquals(id, DeviceCard.design(id), DeviceCard.design(id))
    }

    @Test
    fun `two ids never give the same card`() {
        val designs = ids.map(DeviceCard::design)
        assertEquals("two ids drew the same card", designs.size, designs.toSet().size)
        assertEquals("two ids got the same serial", designs.size, designs.map { it.serial }.toSet().size)
        // Not only the serial: the picture differs too.
        assertEquals("two ids drew the same circles", designs.size, designs.map { it.circles }.toSet().size)
        // and the palettes all come up
        assertEquals(DeviceCard.PALETTES.size, designs.map { it.palette }.toSet().size)
    }

    @Test
    fun `the serial is No and eight hexadecimal digits, from the hash, never the id`() {
        for (id in ids) {
            val serial = DeviceCard.design(id).serial
            assertTrue(serial, Regex("""No\. [0-9A-F]{4}-[0-9A-F]{4}""").matches(serial))
            val hash = DeviceCard.digest(id).take(4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            assertEquals("No. " + hash.take(4) + "-" + hash.drop(4), serial)
        }
        // salted: not the plain SHA-256 of the id
        val plain = java.security.MessageDigest.getInstance("SHA-256").digest("device-1".toByteArray())
        assertFalse(plain.contentEquals(DeviceCard.digest("device-1")))
        // an id of hexadecimal digits does not show through
        assertFalse(DeviceCard.design("f0e1d2c3b4a59687").serial.replace("-", "").contains("F0E1D2C3"))
    }

    @Test
    fun `every card has circles of three or four sizes and two or three arcs, in its palette's colours`() {
        for (id in ids) {
            val card = DeviceCard.design(id)
            val palette = card.palette
            assertTrue(id, card.circles.size in 4..11)
            assertTrue(id, card.arcs.size in 2..3)
            val sizes = card.circles.map { circle ->
                when {
                    circle.r >= 0.34 -> 0
                    circle.r >= 0.19 -> 1
                    circle.r >= 0.10 -> 2
                    else -> 3
                }
            }.toSet()
            assertTrue("$id: ${sizes.size} sizes", sizes.size in 3..4)
            assertTrue("$id: a largest one first", card.circles.first().r >= 0.34)
            for (circle in card.circles) assertTrue(id, circle.colour in palette.shapes)
            for (arc in card.arcs) assertTrue(id, arc.colour in palette.shapes || arc.colour in palette.lines)
            // each arc passes through the art, so none is wholly off the card
            for (arc in card.arcs) {
                val mid = Math.toRadians(arc.start + arc.sweep / 2)
                val x = arc.cx + arc.radius * Math.cos(mid)
                val y = arc.cy + arc.radius * Math.sin(mid)
                assertTrue("$id: an arc off the card", x in 0.0..1.0 && y in 0.0..DeviceCard.ART)
            }
            // the small ones whole on the art
            for (circle in card.circles.filter { it.r < 0.19 }) {
                assertTrue(id, circle.x - circle.r >= -1e-9 && circle.x + circle.r <= 1 + 1e-9)
                assertTrue(id, circle.y - circle.r >= -1e-9 && circle.y + circle.r <= DeviceCard.ART + 1e-9)
            }
        }
    }

    /**
     * The cards of three ids, number for number, as experiments/features/version-card/proto.py
     * worked them out on its own: a change to the draws would change every card in every hand.
     */
    @Test
    fun `the cards already drawn never change`() {
        DeviceCard.design("test-device-1").let { card ->
            assertEquals("No. 02B0-FADC", card.serial)
            assertEquals(DeviceCard.PALETTES[2], card.palette)
            assertEquals(0xEEEDDA, card.tint and 0xFFFFFF)
            assertFalse(card.diagonal)
            assertEquals(6, card.circles.size)
            assertEquals(2, card.arcs.size)
            card.circles.first().let {
                assertEquals(0.6993396221538268, it.x, 0.0)
                assertEquals(0.49871024135131775, it.y, 0.0)
                assertEquals(0.414715419015485, it.r, 0.0)
                assertEquals(0x86B06A, it.colour and 0xFFFFFF)
            }
            card.circles.last().let {
                assertEquals(0.19844997908832473, it.x, 0.0)
                assertEquals(0.4794428091304964, it.y, 0.0)
                assertEquals(0.05196961354941254, it.r, 0.0)
                assertEquals(0xEBB04F, it.colour and 0xFFFFFF)
            }
            card.arcs.first().let {
                assertEquals(-0.4314746156953437, it.cx, 0.0)
                assertEquals(0.9892136060488574, it.cy, 0.0)
                assertEquals(0.783195228249989, it.radius, 0.0)
                assertEquals(243.43077127591374, it.start, 0.0)
                assertEquals(188.13845744817255, it.sweep, 0.0)
                assertEquals(0.008, it.stroke, 0.0)
                assertEquals(0xFFFDF5, it.colour and 0xFFFFFF)
            }
        }
        DeviceCard.design("f0e1d2c3b4a59687").let { card ->
            assertEquals("No. 6086-7C77", card.serial)
            assertEquals(DeviceCard.PALETTES[4], card.palette)
            assertEquals(0xEDEADA, card.tint and 0xFFFFFF)
            assertEquals(8, card.circles.size)
            assertEquals(0.673224114275284, card.circles.first().x, 0.0)
            assertEquals(0.06286406512294757, card.circles.last().r, 0.0)
            assertEquals(111.1324612817263, card.arcs.first().start, 0.0)
        }
        DeviceCard.design("").let { card ->
            assertEquals("No. D3FB-22C8", card.serial)
            assertEquals(DeviceCard.PALETTES[5], card.palette)
            assertTrue(card.palette.dark)
            assertTrue(card.diagonal)
            assertEquals(0x333A3F, card.tint and 0xFFFFFF)
            assertEquals(0.7788449573657642, card.circles.first().y, 0.0)
            assertEquals(0.4375746914481276, card.arcs.first().radius, 0.0)
        }
    }

    @Test
    fun `the day it was found counts only on the device that found it`() {
        val mine = DeviceCard.design("device-1").code
        val theirs = DeviceCard.design("device-2").code
        val kept = DeviceCard.foundValue(mine, LocalDate.of(2026, 11, 7))
        assertEquals("$mine 2026-11-07", kept)
        assertEquals(LocalDate.of(2026, 11, 7), DeviceCard.foundOn(kept, mine))
        // a backup from another device restored here
        assertNull(DeviceCard.foundOn(kept, theirs))
        assertNull(DeviceCard.foundOn("", mine))
        assertNull(DeviceCard.foundOn(null, mine))
        assertNull(DeviceCard.foundOn("$mine not-a-day", mine))
        assertNotEquals(mine, theirs)
    }
}
