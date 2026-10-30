/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the gate shows for each answer of the door: the code field or not, the big button, the line under
 * it, the text button, and Paste. The gate itself is Compose and only draws this.
 */
class TesterGateScreenTest {

    @Test
    fun `the first screen, asking for a code, has the field and Open, and says where a code comes from`() {
        val screen = TesterGateScreen.of(null)
        assertEquals(GateScreen(field = true, main = GateMain.OPEN, message = null, codeIsFine = false, anotherCode = false, help = true), screen)
    }

    @Test
    fun `a code the door refused, or could not check, keeps today's shape and the field stays`() {
        val plain = TesterGateScreen.of(null)
        for (message in listOf(GateMessage.UNKNOWN, GateMessage.REVOKED, GateMessage.GRACE_OVER)) {
            val screen = TesterGateScreen.of(message)
            assertTrue("$message keeps the field", screen.field)
            assertEquals("$message keeps Open", GateMain.OPEN, screen.main)
            assertEquals("$message is said under the field", message, screen.message)
            assertTrue("$message keeps the line that says where a code comes from", screen.help)
            assertFalse(screen.codeIsFine)
            assertFalse(screen.anotherCode)
            assertEquals("nothing else differs from the first screen", plain, screen.copy(message = null))
        }
    }

    @Test
    fun `out of date has no field, a button to testers, and says the code is fine`() {
        val screen = TesterGateScreen.of(GateMessage.OUTDATED)
        assertEquals(
            GateScreen(field = false, main = GateMain.OPEN_TESTERS, message = GateMessage.OUTDATED, codeIsFine = true, anotherCode = false, help = false),
            screen,
        )
    }

    @Test
    fun `in use has no field, a button to testers, and a text button that brings the field back`() {
        val screen = TesterGateScreen.of(GateMessage.IN_USE)
        assertEquals(
            GateScreen(field = false, main = GateMain.OPEN_TESTERS, message = GateMessage.IN_USE, codeIsFine = false, anotherCode = true, help = false),
            screen,
        )
    }

    @Test
    fun `only the two refusals of a good code change the shape, and each says what is left to do`() {
        val shaped = GateMessage.entries.filter { TesterGateScreen.of(it).field.not() }
        assertEquals(setOf(GateMessage.OUTDATED, GateMessage.IN_USE), shaped.toSet())
        for (message in shaped) {
            val screen = TesterGateScreen.of(message)
            assertEquals(GateMain.OPEN_TESTERS, screen.main)
            assertFalse("no field, so no line about where a code comes from", screen.help)
            assertEquals("the answer is in the body, as it is given", message, screen.message)
            assertTrue("one thing to say or do beside the button", screen.codeIsFine != screen.anotherCode)
        }
        assertNotEquals(TesterGateScreen.of(GateMessage.OUTDATED), TesterGateScreen.of(GateMessage.IN_USE))
    }

    @Test
    fun `paste is at the end of the field while it is empty, and nowhere else`() {
        val table = listOf(
            // message, field empty, busy, shown
            Triple(null, true, false) to true,
            Triple(null, false, false) to false,
            Triple(null, true, true) to false,
            Triple(GateMessage.UNKNOWN, true, false) to true,
            Triple(GateMessage.REVOKED, true, false) to true,
            Triple(GateMessage.GRACE_OVER, true, false) to true,
            Triple(GateMessage.UNKNOWN, false, false) to false,
            // No field, no Paste.
            Triple(GateMessage.OUTDATED, true, false) to false,
            Triple(GateMessage.IN_USE, true, false) to false,
        )
        for ((case, shown) in table) {
            val (message, empty, busy) = case
            assertEquals("$message, empty=$empty, busy=$busy", shown, TesterGateScreen.pasteShown(TesterGateScreen.of(message), empty, busy))
        }
    }
}
