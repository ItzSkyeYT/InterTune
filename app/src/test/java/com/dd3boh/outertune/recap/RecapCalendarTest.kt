/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecapCalendarTest {
    private fun d(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    @Test
    fun `the reveal day of 2026 is the first Wednesday of December`() {
        assertEquals(d(2026, 12, 2), RecapCalendar.revealDay(2026))
        assertEquals(DayOfWeek.WEDNESDAY, RecapCalendar.revealDay(2026).dayOfWeek)
    }

    @Test
    fun `nothing is on show before the first reveal day, then that year's until the next one`() {
        assertNull(RecapCalendar.yearOnShow(d(2026, 10, 23)))
        assertNull(RecapCalendar.yearOnShow(d(2026, 12, 1)))
        assertEquals(2026, RecapCalendar.yearOnShow(d(2026, 12, 2)))
        assertEquals(2026, RecapCalendar.yearOnShow(d(2027, 6, 1)))
        assertEquals(2026, RecapCalendar.yearOnShow(d(2027, 12, 1)))
        assertEquals(2027, RecapCalendar.yearOnShow(d(2027, 12, 2)))
    }

    @Test
    fun `the developer's early reveal puts this year's on show`() {
        assertEquals(2026, RecapCalendar.yearOnShow(d(2026, 10, 23), revealedEarly = true))
    }

    @Test
    fun `the pop-up comes once, from the reveal day to the end of January`() {
        assertFalse(RecapCalendar.popUpOwed(d(2026, 12, 1), shownYear = 0))
        assertTrue(RecapCalendar.popUpOwed(d(2026, 12, 2), shownYear = 0))
        assertTrue(RecapCalendar.popUpOwed(d(2027, 1, 31), shownYear = 0))
        // Shown once: not again, on any day.
        assertFalse(RecapCalendar.popUpOwed(d(2026, 12, 3), shownYear = 2026))
        // Too late to spring up; the banner is still there.
        assertFalse(RecapCalendar.popUpOwed(d(2027, 2, 1), shownYear = 0))
        // The next year's comes on its own day, after last year's was shown.
        assertTrue(RecapCalendar.popUpOwed(d(2027, 12, 2), shownYear = 2026))
    }

    @Test
    fun `an early reveal owes the pop-up until it has been shown`() {
        assertTrue(RecapCalendar.popUpOwed(d(2026, 10, 23), shownYear = 0, revealedEarly = true))
        assertFalse(RecapCalendar.popUpOwed(d(2026, 10, 23), shownYear = 2026, revealedEarly = true))
    }
}
