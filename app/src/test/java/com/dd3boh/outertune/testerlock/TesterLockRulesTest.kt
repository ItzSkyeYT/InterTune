/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tester lock, launch after launch, against a door that answers what it is told to.
 *
 * A locked build asks once for each build; a code kept from an earlier build is checked with no
 * typing; one good check for each build and then nothing is sent; an outage of ours opens the app
 * and is tried again at each launch, for seven days from the first failed try on that build.
 */
class TesterLockRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val start = 1_800_000_000_000L
    private val code = "ABCD1234WXYZ"
    private val other = "WXYZ1234ABCD"
    private val first = "0.12.5-preview.20261029.1900"
    private val second = "0.12.5-preview.20261105.1900"

    /** A phone with a locked build: its memory, its clock, and what was sent to the door. */
    private inner class Phone(var memory: TesterMemory = TesterMemory(), var now: Long = start) {
        val asked = mutableListOf<Pair<String, String>>()
        var gateMessage: GateMessage? = null

        /** A launch, the door answering [answer] if it is asked. Whether the app ends up open. */
        fun launch(build: String, answer: DoorAnswer = DoorAnswer.OPEN, lockOn: Boolean = true): Boolean {
            gateMessage = null
            return when (val launch = TesterLockRules.atLaunch(lockOn, build, memory, now)) {
                AtLaunch.Open -> true
                AtLaunch.Ask -> false
                is AtLaunch.OpenAndCheck -> check(build, launch.code, answer)
                is AtLaunch.AskAndCheck -> check(build, launch.code, answer)
            }
        }

        /** The Open button, with a code typed. */
        fun type(build: String, typed: String, answer: DoorAnswer): Boolean = check(build, typed, answer)

        private fun check(build: String, code: String, answer: DoorAnswer): Boolean {
            asked += build to code
            val after = TesterLockRules.afterCheck(build, memory, code, answer, now)
            memory = after.memory
            gateMessage = after.message
            return after.opens
        }
    }

    @Test
    fun `without the lock nothing is asked and nothing is sent`() {
        val phone = Phone()
        assertEquals(AtLaunch.Open, TesterLockRules.atLaunch(false, first, TesterMemory(), start))
        assertTrue(phone.launch(first, lockOn = false))
        assertTrue(phone.asked.isEmpty())
    }

    @Test
    fun `a locked build asks for a code the first time it opens`() {
        val phone = Phone()
        assertEquals(AtLaunch.Ask, TesterLockRules.atLaunch(true, first, phone.memory, start))
        assertFalse(phone.launch(first))
        assertTrue("nothing to check yet", phone.asked.isEmpty())
    }

    @Test
    fun `a good code opens the build, and that build never asks again`() {
        val phone = Phone()
        assertFalse(phone.launch(first))
        assertTrue(phone.type(first, code, DoorAnswer.OPEN))
        assertEquals(TesterMemory(code = code, openedBuild = first), phone.memory)
        // Days later, offline or not: one good check for each build, then nothing more is sent.
        phone.now += 30 * day
        repeat(5) { assertTrue(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK)) }
        assertEquals(listOf(first to code), phone.asked)
    }

    @Test
    fun `a new build checks the kept code with no typing`() {
        val phone = Phone(TesterMemory(code = code, openedBuild = first))
        assertEquals(AtLaunch.OpenAndCheck(code), TesterLockRules.atLaunch(true, second, phone.memory, start))
        assertTrue(phone.launch(second, answer = DoorAnswer.OPEN))
        assertEquals(listOf(second to code), phone.asked)
        assertEquals(TesterMemory(code = code, openedBuild = second), phone.memory)
        assertTrue(phone.launch(second))
        assertEquals("asked once", 1, phone.asked.size)
    }

    @Test
    fun `the gate shows on a new build only when the door says unknown or revoked`() {
        for ((answer, message) in listOf(DoorAnswer.UNKNOWN to GateMessage.UNKNOWN, DoorAnswer.REVOKED to GateMessage.REVOKED)) {
            val phone = Phone(TesterMemory(code = code, openedBuild = first))
            assertFalse("$answer", phone.launch(second, answer = answer))
            assertEquals(message, phone.gateMessage)
            // The refused code is not kept: the next launch asks for one.
            assertNull(phone.memory.code)
            assertEquals(AtLaunch.Ask, TesterLockRules.atLaunch(true, second, phone.memory, start))
        }
    }

    @Test
    fun `only the door's two kinds of no keep anyone out`() {
        // A 400, a 405 or a 5xx is COULD_NOT_CHECK, and opens the app like no answer at all.
        for (answer in DoorAnswer.entries) {
            val after = TesterLockRules.afterCheck(first, TesterMemory(), code, answer, start)
            val refused = answer == DoorAnswer.UNKNOWN || answer == DoorAnswer.REVOKED
            assertEquals("$answer", refused, !after.opens)
            assertEquals("$answer", refused, after.message != null)
        }
    }

    @Test
    fun `a wrong code then the right one`() {
        val phone = Phone()
        assertFalse(phone.type(first, other, DoorAnswer.UNKNOWN))
        assertEquals(GateMessage.UNKNOWN, phone.gateMessage)
        assertTrue(phone.type(first, code, DoorAnswer.OPEN))
        assertNull(phone.gateMessage)
        assertEquals(TesterMemory(code = code, openedBuild = first), phone.memory)
    }

    @Test
    fun `an outage of ours opens the app, and the next launch tries again`() {
        val phone = Phone(TesterMemory(code = code, openedBuild = first))
        assertTrue(phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(TesterMemory(code = code, openedBuild = first, graceBuild = second, graceSince = start), phone.memory)
        phone.now += day
        assertTrue(phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        // The days count from the first failed try, not from the last.
        assertEquals(start, phone.memory.graceSince)
        phone.now += day
        assertTrue(phone.launch(second, answer = DoorAnswer.OPEN))
        assertEquals(TesterMemory(code = code, openedBuild = second), phone.memory)
        assertEquals(3, phone.asked.size)
        assertTrue(phone.launch(second))
        assertEquals("nothing more once it passed", 3, phone.asked.size)
    }

    @Test
    fun `a code typed while the door cannot be reached opens the app and is checked later`() {
        val phone = Phone()
        assertFalse(phone.launch(first))
        assertTrue(phone.type(first, code, DoorAnswer.COULD_NOT_CHECK))
        assertEquals(TesterMemory(code = code, graceBuild = first, graceSince = start), phone.memory)
        // Made up, it is found out at the next launch the door answers.
        phone.now += day
        assertFalse(phone.launch(first, answer = DoorAnswer.UNKNOWN))
        assertEquals(GateMessage.UNKNOWN, phone.gateMessage)
        assertEquals(AtLaunch.Ask, TesterLockRules.atLaunch(true, first, phone.memory, phone.now))
    }

    @Test
    fun `seven days without a check and the gate asks to connect`() {
        val phone = Phone(TesterMemory(code = code, openedBuild = first))
        assertTrue(phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        phone.now = start + 7 * day - 1
        assertTrue("a moment before the seventh day ends", phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        phone.now = start + 7 * day
        assertEquals(AtLaunch.AskAndCheck(code), TesterLockRules.atLaunch(true, second, phone.memory, phone.now))
        assertFalse(phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(GateMessage.GRACE_OVER, phone.gateMessage)
        // The code is still the one to try: back online, the same check opens it.
        assertEquals(code, phone.memory.code)
        assertTrue(phone.launch(second, answer = DoorAnswer.OPEN))
        assertEquals(TesterMemory(code = code, openedBuild = second), phone.memory)
    }

    @Test
    fun `typing again after the seven days is checked, never let in unchecked`() {
        val phone = Phone(TesterMemory(code = code, graceBuild = second, graceSince = start))
        phone.now = start + 8 * day
        assertFalse(phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        assertFalse(phone.type(second, code, DoorAnswer.COULD_NOT_CHECK))
        assertEquals(GateMessage.GRACE_OVER, phone.gateMessage)
        assertTrue(phone.type(second, code, DoorAnswer.OPEN))
    }

    @Test
    fun `each build has its own seven days`() {
        val phone = Phone(TesterMemory(code = code, graceBuild = first, graceSince = start))
        phone.now = start + 10 * day
        assertTrue("a new build starts its own days", phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(second, phone.memory.graceBuild)
        assertEquals(start + 10 * day, phone.memory.graceSince)
    }

    @Test
    fun `a clock put back never locks anyone out`() {
        val phone = Phone(TesterMemory(code = code, graceBuild = second, graceSince = start))
        phone.now = start - 30 * day
        assertTrue(phone.launch(second, answer = DoorAnswer.COULD_NOT_CHECK))
    }

    @Test
    fun `a refused new code leaves the kept one alone`() {
        // After the seven days, with the field holding the kept code, another code was typed and refused.
        val kept = TesterMemory(code = code, graceBuild = second, graceSince = start)
        val after = TesterLockRules.afterCheck(second, kept, other, DoorAnswer.UNKNOWN, start + 8 * day)
        assertEquals(kept, after.memory)
        assertFalse(after.opens)
        // And the kept one refused is dropped, whichever answer said so.
        for (answer in listOf(DoorAnswer.UNKNOWN, DoorAnswer.REVOKED)) {
            assertNull(TesterLockRules.afterCheck(second, kept, code, answer, start).memory.code)
        }
    }

    @Test
    fun `the days are seven`() {
        assertEquals(7 * day, TesterLockRules.GRACE_MILLIS)
    }
}
