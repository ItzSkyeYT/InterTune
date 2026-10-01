/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which buttons on Your data ask before they act, what they ask with, and what they say after. */
class YourDataTest {

    /** A day it counted: from local midnight to just after the tap. */
    private val day = 1_000L..5_000L

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
        assertEquals(nothing, forgetTodayStep(0, day))
        assertEquals(DataStep.Ask(DataAsk.ForgetToday(3, from = 1_000, to = 5_001)), forgetTodayStep(3, day))
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
        assertEquals(DataAction.FORGET_TODAY, DataAsk.ForgetToday(1, 0, 1).action)
        assertEquals(DataAction.RESET, DataAsk.Reset(1).action)
        assertEquals(DataAction.REBUILD, DataAsk.Rebuild(1).action)
        assertEquals(DataAction.LOAD, DataAsk.Load.action)
    }

    @Test
    fun `each step carries whether what it has now came from a loaded copy`() {
        assertEquals(DataStep.Ask(DataAsk.ForgetSession(7, 14, 1_000, copyLoaded = true)), forgetSessionStep(7, 14, 1_000, copyLoaded = true))
        assertEquals(DataStep.Ask(DataAsk.ForgetToday(3, 1_000, 5_001, copyLoaded = true)), forgetTodayStep(3, day, copyLoaded = true))
        assertEquals(DataStep.Ask(DataAsk.Reset(0, copyLoaded = true)), resetStep(learnedAnything = true, cards = 0, copyLoaded = true))
        assertEquals(DataStep.Ask(DataAsk.Rebuild(0, copyLoaded = true)), rebuildStep(0, copyLoaded = true))
    }

    @Test
    fun `a dialog says a loaded copy goes only when there is one`() {
        // "What it has now is replaced, a copy you loaded included." stood in every Rebuild dialog,
        // and read as if you had loaded one.
        assertEquals(emptyList<AskNote>(), askNotes(DataAsk.Rebuild(193)))
        assertEquals(listOf(AskNote.COPY_REPLACED), askNotes(DataAsk.Rebuild(193, copyLoaded = true)))
        assertEquals(emptyList<AskNote>(), askNotes(DataAsk.Reset(193)))
        assertEquals(listOf(AskNote.COPY_GOES), askNotes(DataAsk.Reset(193, copyLoaded = true)))
        assertEquals(emptyList<AskNote>(), askNotes(DataAsk.Load))
    }

    @Test
    fun `forgetting cannot be undone, and replaces a loaded copy as a rebuild does`() {
        // Forgetting rebuilds what it learned from this phone's cards, and the Forget dialogs used
        // to leave that out while the Rebuild dialog warned of it.
        assertEquals(listOf(AskNote.CANNOT_UNDO), askNotes(DataAsk.ForgetToday(3, 1_000, 5_001)))
        assertEquals(listOf(AskNote.COPY_REPLACED, AskNote.CANNOT_UNDO), askNotes(DataAsk.ForgetToday(3, 1_000, 5_001, copyLoaded = true)))
        assertEquals(listOf(AskNote.CANNOT_UNDO), askNotes(DataAsk.ForgetSession(7, 14, 1_000)))
        assertEquals(listOf(AskNote.COPY_REPLACED, AskNote.CANNOT_UNDO), askNotes(DataAsk.ForgetSession(7, 14, 1_000, copyLoaded = true)))
    }

    @Test
    fun `with nothing stored there is no loaded copy, whatever the mark says`() {
        assertTrue(copyLoaded(marked = true, weightsStored = true))
        assertFalse(copyLoaded(marked = true, weightsStored = false))
        assertFalse(copyLoaded(marked = false, weightsStored = true))
    }

    @Test
    fun `a new tap clears that button's last line and leaves the others`() {
        val before = mapOf(DataAction.FORGET_TODAY to DataResult.Forgot(0), DataAction.REBUILD to DataResult.Rebuilt(193))
        assertEquals(mapOf(DataAction.REBUILD to DataResult.Rebuilt(193)), clearedFor(before, DataAction.FORGET_TODAY))
        assertEquals(before, clearedFor(before, DataAction.RESET))
    }

    @Test
    fun `a change to what it has learned clears the lines that said what it had`() {
        // On the emulator, "Loaded." stayed under Load a copy after Forget today's listening had
        // rebuilt from this phone's cards, which replaced the copy.
        val before = mapOf(
            DataAction.SAVE to DataResult.Saved,
            DataAction.LOAD to DataResult.Loaded,
            DataAction.REBUILD to DataResult.Rebuilt(193),
            DataAction.FORGET_SESSION to DataResult.Forgot(0),
        )
        assertEquals(
            mapOf(DataAction.SAVE to DataResult.Saved, DataAction.FORGET_SESSION to DataResult.Forgot(0), DataAction.FORGET_TODAY to DataResult.Forgot(1)),
            afterResult(before, DataAction.FORGET_TODAY, DataResult.Forgot(1)),
        )
        assertEquals(
            mapOf(DataAction.SAVE to DataResult.Saved, DataAction.FORGET_SESSION to DataResult.Forgot(0), DataAction.RESET to DataResult.ResetDone),
            afterResult(before, DataAction.RESET, DataResult.ResetDone),
        )
        assertEquals(DataResult.Loaded, afterResult(mapOf(DataAction.REBUILD to DataResult.Rebuilt(5)), DataAction.LOAD, DataResult.Loaded)[DataAction.LOAD])
        assertEquals(null, afterResult(mapOf(DataAction.REBUILD to DataResult.Rebuilt(5)), DataAction.LOAD, DataResult.Loaded)[DataAction.REBUILD])
    }

    @Test
    fun `a tap that changed nothing leaves every other line as it was`() {
        val before = mapOf(DataAction.LOAD to DataResult.Loaded, DataAction.REBUILD to DataResult.Rebuilt(193))
        assertEquals(before + (DataAction.FORGET_TODAY to DataResult.Forgot(0)), afterResult(before, DataAction.FORGET_TODAY, DataResult.Forgot(0)))
        assertEquals(before + (DataAction.RESET to DataResult.Working), afterResult(before, DataAction.RESET, DataResult.Working))
        assertEquals(before + (DataAction.SAVE to DataResult.Saved), afterResult(before, DataAction.SAVE, DataResult.Saved))
        assertEquals(before + (DataAction.RESET to DataResult.Failed), afterResult(before, DataAction.RESET, DataResult.Failed))
    }

    @Test
    fun `a failed forget says it failed, and one that found nothing says nothing to forget`() {
        assertEquals(DataResult.Failed, forgotResult(null))
        assertEquals(DataResult.Forgot(0), forgotResult(0))
        assertEquals(DataResult.Forgot(14), forgotResult(14))
    }

    @Test
    fun `forget today forgets the day it counted, the tap's own moment included`() {
        // Worked out again at the yes, a yes given after midnight would forget the new day, which
        // the dialog had not counted.
        val counted = today(now = 20_000 * 86_400_000L - 60_000, offsetMs = 0)
        val ask = (forgetTodayStep(12, counted) as DataStep.Ask).ask as DataAsk.ForgetToday
        assertEquals(counted.first, ask.from)
        assertEquals(counted.last + 1, ask.to)
        assertEquals(12, ask.listens)
    }

    @Test
    fun `a yes is taken once, so two quick taps on Forget forget once`() {
        val pending = PendingAsk()
        val ask = DataAsk.ForgetToday(3, 1_000, 5_001)
        pending.put(ask)
        assertTrue(pending.take(ask))
        assertFalse(pending.take(ask))
        assertNull(pending.asking.value)
    }

    @Test
    fun `two quick taps on Choose a file open one picker`() {
        val pending = PendingAsk()
        pending.put(DataAsk.Load)
        assertTrue(pending.take(DataAsk.Load))
        assertFalse(pending.take(DataAsk.Load))
    }

    @Test
    fun `a yes to a question no longer waiting does nothing`() {
        val pending = PendingAsk()
        val forget = DataAsk.ForgetSession(7, 14, 1_000)
        pending.put(forget)
        pending.clear()
        assertFalse(pending.take(forget))
        // A newer question waits: an old dialog's yes does not answer it.
        val reset = DataAsk.Reset(193)
        pending.put(reset)
        assertFalse(pending.take(forget))
        assertEquals(reset, pending.asking.value)
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
