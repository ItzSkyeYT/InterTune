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
 * A locked build asks for the code the first time it opens, and for each new build a code kept from an
 * earlier build is checked with no typing. A yes stands for three days, then the kept code is checked
 * again, quietly, with the app open and the check behind it; a yes that gave this install the code is
 * looked at again after ten minutes. An outage of ours opens the app and is tried again at each launch,
 * for seven days from the first failed try on that build. A good code the door refuses a build with, as
 * out of date or in use on another device, is kept, and the gate stays in front of that build.
 */
class TesterLockRulesTest {

    private val minute = 60L * 1000
    private val day = 24L * 60 * 60 * 1000
    private val start = 1_800_000_000_000L
    private val code = "ABCD1234WXYZ"
    private val other = "WXYZ1234ABCD"
    private val install = "0123456789abcdef0123456789abcdef"
    private val first = "0.12.5-preview.20261029.1900"
    private val second = "0.12.5-preview.20261105.1900"

    /** What the memory is after a yes for [build] at [at], from a phone that has an install number. */
    private fun opened(build: String, at: Long, claimed: Boolean = false, install: String? = this.install) =
        TesterMemory(code = code, install = install, openedBuild = build, openedAt = at, claimed = claimed)

    /** A phone with a locked build: its memory, its clock, and what was sent to the door. */
    private inner class Phone(var memory: TesterMemory = TesterMemory(install = install), var now: Long = start) {
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

        fun decision(build: String) = TesterLockRules.atLaunch(true, build, memory, now)

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
    fun `a good code opens the build, and nothing more is sent for it within three days`() {
        val phone = Phone()
        assertFalse(phone.launch(first))
        assertTrue(phone.type(first, code, DoorAnswer.OPEN))
        assertEquals(opened(first, start), phone.memory)
        // Offline or not, any number of launches: the door's yes stands for three days.
        for (later in listOf(1L, day, 2 * day + 23 * 60 * minute, 3 * day - 1, 3 * day)) {
            phone.now = start + later
            assertEquals(AtLaunch.Open, phone.decision(first))
            assertTrue(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        }
        assertEquals("exactly three days is not yet more than three days", listOf(first to code), phone.asked)
        assertEquals("and nothing in the memory moved", opened(first, start), phone.memory)
    }

    @Test
    fun `after three days the kept code is checked again with no typing, and the app opens at once`() {
        val phone = Phone(opened(first, start))
        phone.now = start + 3 * day + 1
        assertEquals(AtLaunch.OpenAndCheck(code), phone.decision(first))
        assertTrue("open at once, the check behind it", phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(listOf(first to code), phone.asked)
        assertEquals("the new yes is the one that counts now", opened(first, phone.now), phone.memory)
        // And then three more days of nothing, and then again.
        phone.now += 3 * day
        assertTrue(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(1, phone.asked.size)
        phone.now += 1
        assertTrue(phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(2, phone.asked.size)
    }

    @Test
    fun `a memory saved before the re-check, with no time for the last yes, is due at once`() {
        val old = TesterMemory(code = code, openedBuild = first)
        assertEquals(0L, old.openedAt)
        assertNull("and no install number either", old.install)
        assertEquals(AtLaunch.OpenAndCheck(code), TesterLockRules.atLaunch(true, first, old, start))
        val phone = Phone(old)
        assertTrue(phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(listOf(first to code), phone.asked)
        assertEquals("from then on it has the time of the yes", start, phone.memory.openedAt)
        assertEquals(AtLaunch.Open, phone.decision(first))
    }

    @Test
    fun `a new build checks the kept code with no typing`() {
        val phone = Phone(opened(first, start))
        assertEquals(AtLaunch.OpenAndCheck(code), TesterLockRules.atLaunch(true, second, phone.memory, start))
        assertTrue(phone.launch(second, answer = DoorAnswer.OPEN))
        assertEquals(listOf(second to code), phone.asked)
        assertEquals(opened(second, start), phone.memory)
        assertTrue(phone.launch(second))
        assertEquals("asked once", 1, phone.asked.size)
    }

    @Test
    fun `the gate shows on a new build only when the door says no`() {
        val refusals = listOf(
            DoorAnswer.UNKNOWN to GateMessage.UNKNOWN,
            DoorAnswer.REVOKED to GateMessage.REVOKED,
            DoorAnswer.IN_USE to GateMessage.IN_USE,
            DoorAnswer.OUTDATED to GateMessage.OUTDATED,
        )
        for ((answer, message) in refusals) {
            val phone = Phone(opened(first, start))
            assertFalse("$answer", phone.launch(second, answer = answer))
            assertEquals(message, phone.gateMessage)
        }
        for (answer in listOf(DoorAnswer.UNKNOWN, DoorAnswer.REVOKED)) {
            val phone = Phone(opened(first, start))
            phone.launch(second, answer = answer)
            // The refused code is not kept: the next launch asks for one.
            assertNull(phone.memory.code)
            assertEquals(AtLaunch.Ask, TesterLockRules.atLaunch(true, second, phone.memory, start))
        }
    }

    @Test
    fun `a quiet re-check that the door refuses puts the gate in front`() {
        for ((answer, message) in listOf(DoorAnswer.UNKNOWN to GateMessage.UNKNOWN, DoorAnswer.REVOKED to GateMessage.REVOKED)) {
            val phone = Phone(opened(first, start))
            phone.now = start + 4 * day
            assertEquals(AtLaunch.OpenAndCheck(code), phone.decision(first))
            assertFalse("$answer: the app was open, and the gate comes in front", phone.launch(first, answer = answer))
            assertEquals(message, phone.gateMessage)
            // The code the door refused is gone: the next launch asks, whatever the memory still says of the build.
            assertNull(phone.memory.code)
            assertEquals(AtLaunch.Ask, phone.decision(first))
            assertEquals("and the install number stays", install, phone.memory.install)
        }
    }

    @Test
    fun `out of date shows its message and keeps the code`() {
        val phone = Phone(opened(first, start))
        phone.now = start + 4 * day
        assertFalse(phone.launch(first, answer = DoorAnswer.OUTDATED))
        assertEquals(GateMessage.OUTDATED, phone.gateMessage)
        assertEquals("the code is good: it stays, for the newest build to check with no typing",
            TesterMemory(code = code, install = install, refusedBuild = first), phone.memory)
        // The next launch of the same build: the gate at once, and a look behind it at whether that still holds.
        assertEquals(AtLaunch.AskAndCheck(code), phone.decision(first))
        assertFalse(phone.launch(first, answer = DoorAnswer.OUTDATED))
        assertEquals(GateMessage.OUTDATED, phone.gateMessage)
        assertEquals(2, phone.asked.size)
        // Typing the code again at the gate changes nothing.
        assertFalse(phone.type(first, code, DoorAnswer.OUTDATED))
        assertEquals(code, phone.memory.code)
        // The newest build is another name: the kept code opens it, with no typing.
        assertEquals(AtLaunch.OpenAndCheck(code), phone.decision(second))
        assertTrue(phone.launch(second, answer = DoorAnswer.OPEN))
        assertEquals(opened(second, phone.now), phone.memory)
    }

    @Test
    fun `an old build the door calls good again opens`() {
        // The newest build was taken down: the door's answer changed, and the look behind the gate finds out.
        val phone = Phone(TesterMemory(code = code, install = install, refusedBuild = first))
        assertTrue(phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(opened(first, start), phone.memory)
        assertNull(phone.memory.refusedBuild)
    }

    @Test
    fun `a code in use on another device shows its message and keeps the code`() {
        val phone = Phone()
        assertFalse(phone.launch(first))
        assertFalse(phone.type(first, code, DoorAnswer.IN_USE))
        assertEquals(GateMessage.IN_USE, phone.gateMessage)
        assertEquals(TesterMemory(code = code, install = install, refusedBuild = first), phone.memory)
        // The tester moved the code with /code and comes back: the launch checks at the gate, with no typing.
        assertEquals(AtLaunch.AskAndCheck(code), phone.decision(first))
        assertTrue(phone.launch(first, answer = DoorAnswer.CLAIMED))
        assertEquals(opened(first, start, claimed = true), phone.memory)
        assertEquals(listOf(first to code, first to code), phone.asked)
    }

    @Test
    fun `a quiet re-check that finds the code in use elsewhere puts the gate in front`() {
        // This install had the code, the tester moved it to another phone, and this one finds out.
        val phone = Phone(opened(first, start))
        phone.now = start + 3 * day + 1
        assertFalse(phone.launch(first, answer = DoorAnswer.IN_USE))
        assertEquals(GateMessage.IN_USE, phone.gateMessage)
        assertEquals(TesterMemory(code = code, install = install, refusedBuild = first), phone.memory)
        assertEquals(AtLaunch.AskAndCheck(code), phone.decision(first))
    }

    @Test
    fun `a build the door refused gets no days of grace when it cannot be reached`() {
        val phone = Phone(TesterMemory(code = code, install = install, refusedBuild = first))
        assertFalse(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(GateMessage.GRACE_OVER, phone.gateMessage)
        assertFalse("pressing Open offline is no better", phone.type(first, code, DoorAnswer.COULD_NOT_CHECK))
        assertEquals(TesterMemory(code = code, install = install, refusedBuild = first), phone.memory)
        // Back online, it is checked as it always is.
        assertTrue(phone.launch(first, answer = DoorAnswer.OPEN))
    }

    @Test
    fun `a refusal with no kept code asks for a code`() {
        // A code refused as unknown after the build was refused as out of date: nothing is left to check.
        val memory = TesterMemory(code = null, install = install, refusedBuild = first)
        assertEquals(AtLaunch.Ask, TesterLockRules.atLaunch(true, first, memory, start))
    }

    @Test
    fun `a yes that gave this install the code is looked at again in ten minutes, a plain yes in three days`() {
        val claimed = Phone()
        assertTrue(claimed.type(first, code, DoorAnswer.CLAIMED))
        assertEquals(opened(first, start, claimed = true), claimed.memory)
        val plain = Phone()
        assertTrue(plain.type(first, code, DoorAnswer.OPEN))
        assertEquals(opened(first, start, claimed = false), plain.memory)

        for (phone in listOf(claimed, plain)) phone.now = start + 10 * minute
        assertEquals("ten minutes exactly is not yet more than ten minutes", AtLaunch.Open, claimed.decision(first))
        assertEquals(AtLaunch.Open, plain.decision(first))
        for (phone in listOf(claimed, plain)) phone.now = start + 10 * minute + 1
        assertEquals("after a claim, the next launch checks", AtLaunch.OpenAndCheck(code), claimed.decision(first))
        assertEquals("after a plain yes, it does not", AtLaunch.Open, plain.decision(first))
        for (phone in listOf(claimed, plain)) phone.now = start + day
        assertEquals(AtLaunch.OpenAndCheck(code), claimed.decision(first))
        assertEquals(AtLaunch.Open, plain.decision(first))
        for (phone in listOf(claimed, plain)) phone.now = start + 3 * day + 1
        assertEquals("three days, then it does", AtLaunch.OpenAndCheck(code), plain.decision(first))
    }

    @Test
    fun `the check ten minutes after a claim is quiet, and the yes it gets is the plain one`() {
        val phone = Phone()
        assertTrue(phone.type(first, code, DoorAnswer.CLAIMED))
        phone.now = start + 11 * minute
        assertTrue("open at once, the check behind it", phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(opened(first, phone.now, claimed = false), phone.memory)
        // From here on it is three days.
        phone.now += 3 * day
        assertEquals(AtLaunch.Open, phone.decision(first))
        phone.now += 1
        assertEquals(AtLaunch.OpenAndCheck(code), phone.decision(first))
    }

    @Test
    fun `the check ten minutes after a claim can find another install took the code`() {
        // Two phones opened within the minute the door takes to agree with itself, and this one lost.
        val phone = Phone()
        assertTrue(phone.type(first, code, DoorAnswer.CLAIMED))
        phone.now = start + 11 * minute
        assertFalse(phone.launch(first, answer = DoorAnswer.IN_USE))
        assertEquals(GateMessage.IN_USE, phone.gateMessage)
    }

    @Test
    fun `only the door's four kinds of no keep anyone out`() {
        // A 400, a 405 or a 5xx is COULD_NOT_CHECK, and opens the app like no answer at all.
        val refusals = setOf(DoorAnswer.UNKNOWN, DoorAnswer.REVOKED, DoorAnswer.IN_USE, DoorAnswer.OUTDATED)
        for (answer in DoorAnswer.entries) {
            val after = TesterLockRules.afterCheck(first, TesterMemory(), code, answer, start)
            val refused = answer in refusals
            assertEquals("$answer", refused, !after.opens)
            assertEquals("$answer", refused, after.message != null)
        }
    }

    @Test
    fun `a wrong code then the right one`() {
        val phone = Phone()
        assertFalse(phone.type(first, other, DoorAnswer.UNKNOWN))
        assertEquals(GateMessage.UNKNOWN, phone.gateMessage)
        assertTrue(phone.type(first, code, DoorAnswer.CLAIMED))
        assertNull(phone.gateMessage)
        assertEquals(opened(first, start, claimed = true), phone.memory)
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
        assertEquals(TesterMemory(code = code, openedBuild = second, openedAt = phone.now), phone.memory)
        assertEquals(3, phone.asked.size)
        assertTrue(phone.launch(second))
        assertEquals("nothing more once it passed", 3, phone.asked.size)
    }

    @Test
    fun `an outage at a quiet re-check starts the seven days, counted from the first failed try`() {
        val phone = Phone(opened(first, start))
        phone.now = start + 4 * day
        assertTrue("the app opens: the door could not be reached", phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(opened(first, start).copy(graceBuild = first, graceSince = phone.now), phone.memory)
        val failed = phone.now
        // Tried again at each launch, since the yes is still old.
        phone.now = failed + 2 * day
        assertEquals(AtLaunch.OpenAndCheck(code), phone.decision(first))
        assertTrue(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals("not from the last try", failed, phone.memory.graceSince)
        // A moment before the seventh day, still open; at the seventh the gate asks to connect.
        phone.now = failed + 7 * day - 1
        assertTrue(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        phone.now = failed + 7 * day
        assertEquals(AtLaunch.AskAndCheck(code), phone.decision(first))
        assertFalse(phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        assertEquals(GateMessage.GRACE_OVER, phone.gateMessage)
        assertEquals("the code is still the one to try", code, phone.memory.code)
        // Connected again, the same check opens it, and the days are forgotten.
        assertTrue(phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(opened(first, phone.now), phone.memory)
    }

    @Test
    fun `a code typed while the door cannot be reached opens the app and is checked later`() {
        val phone = Phone()
        assertFalse(phone.launch(first))
        assertTrue(phone.type(first, code, DoorAnswer.COULD_NOT_CHECK))
        assertEquals(TesterMemory(code = code, install = install, graceBuild = first, graceSince = start), phone.memory)
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
        assertEquals(TesterMemory(code = code, openedBuild = second, openedAt = phone.now), phone.memory)
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
    fun `a clock put back past the last yes is checked quietly, never a lock`() {
        val phone = Phone(opened(first, start + 30 * day))
        // The yes is dated after now: that is no proof of anything, so the kept code is checked.
        assertEquals(AtLaunch.OpenAndCheck(code), phone.decision(first))
        assertTrue("the door cannot be reached: the app opens", phone.launch(first, answer = DoorAnswer.COULD_NOT_CHECK))
        assertTrue("and a yes written down with the right time ends it", phone.launch(first, answer = DoorAnswer.OPEN))
        assertEquals(start, phone.memory.openedAt)
        assertEquals(AtLaunch.Open, phone.decision(first))
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
    fun `a memory saved before the install number gets one, and one that has it keeps it`() {
        var made = 0
        val make = { made++; "feedfeedfeedfeedfeedfeedfeedfeed" }
        val old = TesterMemory(code = code, openedBuild = first)
        val given = TesterLockRules.withInstall(old, make)
        assertEquals("feedfeedfeedfeedfeedfeedfeedfeed", given.install)
        assertEquals("nothing else touched", old.copy(install = given.install), given)
        assertEquals(1, made)
        // The number it has is kept: the door holds the code for it.
        assertEquals(given, TesterLockRules.withInstall(given, make))
        assertEquals("and no new one was made", 1, made)
        // A number that is not what the door takes is not kept.
        for (broken in listOf("", "short", "0123456789ABCDEF0123456789ABCDEF", "0123456789abcdef0123456789abcdeg", install + "0")) {
            assertEquals(broken, "feedfeedfeedfeedfeedfeedfeedfeed", TesterLockRules.withInstall(TesterMemory(install = broken), make).install)
        }
    }

    @Test
    fun `the install number is kept through every answer, and the first check can send it`() {
        for (answer in DoorAnswer.entries) {
            for (memory in listOf(
                TesterMemory(install = install),
                opened(first, start),
                TesterMemory(code = code, install = install, graceBuild = first, graceSince = start),
                TesterMemory(code = code, install = install, refusedBuild = first),
            )) {
                for (typed in listOf(code, other)) {
                    val after = TesterLockRules.afterCheck(first, memory, typed, answer, start + day)
                    assertEquals("$answer after $memory with $typed", install, after.memory.install)
                }
            }
        }
    }

    @Test
    fun `the days are three, ten minutes and seven`() {
        assertEquals(3 * day, TesterLockRules.RECHECK_MILLIS)
        assertEquals(10 * minute, TesterLockRules.CLAIM_RECHECK_MILLIS)
        assertEquals(7 * day, TesterLockRules.GRACE_MILLIS)
    }
}
