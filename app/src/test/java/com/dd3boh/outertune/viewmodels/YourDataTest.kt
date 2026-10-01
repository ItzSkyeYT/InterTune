/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which buttons on Your data ask before they act, what they ask with, and what they say after. */
class YourDataTest {

    @Test
    fun `everything that changes what it has learned asks first, and saving a copy does not`() {
        assertFalse(asksFirst(DataAction.SAVE))
        DataAction.entries.filter { it != DataAction.SAVE }.forEach { assertTrue(it.name, asksFirst(it)) }
    }

    @Test
    fun `forgetting a session asks with how many listens and when it began`() {
        assertEquals(
            DataStep.Ask(DataAsk.ForgetSession(sessionId = 7, listens = 14, began = 1_000)),
            forgetSessionStep(sessionId = 7, listens = 14, began = 1_000),
        )
    }

    @Test
    fun `with nothing left to forget it says so at once, without a dialog`() {
        val nothing = DataStep.Tell(DataResult.Forgot(0))
        assertEquals(nothing, forgetSessionStep(sessionId = 7, listens = 0, began = 1_000))
        assertEquals(nothing, forgetSessionStep(sessionId = null, listens = 0, began = 0))
        assertEquals(nothing, forgetTodayStep(0))
        assertEquals(DataStep.Ask(DataAsk.ForgetToday(3)), forgetTodayStep(3))
    }

    @Test
    fun `a reset asks with the cards it keeps, unless it is already where it started`() {
        assertEquals(DataStep.Ask(DataAsk.Reset(193)), resetStep(learnedAnything = true, cards = 193))
        assertEquals(DataStep.Tell(DataResult.AlreadyAtStart), resetStep(learnedAnything = false, cards = 193))
    }

    @Test
    fun `a rebuild and a load always ask, a rebuild with the cards it will learn from`() {
        assertEquals(DataStep.Ask(DataAsk.Rebuild(0)), rebuildStep(0))
        assertEquals(DataStep.Ask(DataAsk.Rebuild(193)), rebuildStep(193))
        assertEquals(DataStep.Ask(DataAsk.Load), loadStep())
    }

    @Test
    fun `each question belongs to its button, so its answer lands on the right line`() {
        assertEquals(DataAction.FORGET_SESSION, DataAsk.ForgetSession(1, 1, 0).action)
        assertEquals(DataAction.FORGET_TODAY, DataAsk.ForgetToday(1).action)
        assertEquals(DataAction.RESET, DataAsk.Reset(1).action)
        assertEquals(DataAction.REBUILD, DataAsk.Rebuild(1).action)
        assertEquals(DataAction.LOAD, DataAsk.Load.action)
    }

    @Test
    fun `a new tap clears that button's last line and leaves the others`() {
        val before = mapOf(DataAction.FORGET_TODAY to DataResult.Forgot(0), DataAction.REBUILD to DataResult.Rebuilt(193))
        assertEquals(mapOf(DataAction.REBUILD to DataResult.Rebuilt(193)), clearedFor(before, DataAction.FORGET_TODAY))
        assertEquals(before, clearedFor(before, DataAction.RESET))
    }

    @Test
    fun `a failed forget says it failed, and one that found nothing says nothing to forget`() {
        assertEquals(DataResult.Failed, forgotResult(null))
        assertEquals(DataResult.Forgot(0), forgotResult(0))
        assertEquals(DataResult.Forgot(14), forgotResult(14))
    }

    @Test
    fun `today runs from local midnight to now, in the zone the phone is in`() {
        val day = 86_400_000L
        val noonUtc = 20_000 * day + day / 2
        assertEquals(20_000 * day..noonUtc, today(noonUtc, offsetMs = 0))
        // Two hours ahead of UTC, midnight there was 22:00 UTC the day before.
        assertEquals(20_000 * day - 2 * 3_600_000L..noonUtc, today(noonUtc, offsetMs = 2 * 3_600_000))
        // Five hours behind, at 03:00 UTC it is still yesterday there: midnight was 05:00 UTC the day before.
        val earlyUtc = 20_000 * day + 3 * 3_600_000L
        assertEquals(19_999 * day + 5 * 3_600_000L..earlyUtc, today(earlyUtc, offsetMs = -5 * 3_600_000))
    }
}
