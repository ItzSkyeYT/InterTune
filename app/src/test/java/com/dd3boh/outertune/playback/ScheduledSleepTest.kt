/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.SleepTimerDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When the sleep timer on a schedule starts by itself, and when it leaves the music alone. */
class ScheduledSleepTest {
    private val elevenPm = 23 * 60
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val oct14 = LocalDate.of(2026, 10, 14)
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `the window opens at the chosen time and runs three hours, past midnight`() {
        assertNull(ScheduledSleep.nightOf(at(14, 22, 59), elevenPm))
        assertEquals(oct14, ScheduledSleep.nightOf(at(14, 23, 0), elevenPm))
        assertEquals(oct14, ScheduledSleep.nightOf(at(15, 1, 59), elevenPm))
        assertNull(ScheduledSleep.nightOf(at(15, 2, 0), elevenPm))
        assertNull(ScheduledSleep.nightOf(at(15, 12, 0), elevenPm))
        // An afternoon nap is a day like any other.
        assertEquals(oct14, ScheduledSleep.nightOf(at(14, 14, 30), 14 * 60))
        // And a window that opens just after midnight belongs to the day it opens on.
        assertEquals(LocalDate.of(2026, 10, 15), ScheduledSleep.nightOf(at(15, 0, 45), 30))
    }

    @Test
    fun `music in the window gets the timer, once a day`() {
        val first = ScheduledSleep.look(at(14, 23, 5), elevenPm, served = null, timerRunning = false)
        assertEquals(ScheduledSleep.Look(oct14, startTimer = true), first)
        // Served: music put on again after the timer stopped it plays on.
        assertNull(ScheduledSleep.look(at(14, 23, 50), elevenPm, served = oct14, timerRunning = false))
        assertNull(ScheduledSleep.look(at(15, 1, 30), elevenPm, served = oct14, timerRunning = false))
        // The next night is a new one.
        assertEquals(ScheduledSleep.Look(LocalDate.of(2026, 10, 15), startTimer = true),
            ScheduledSleep.look(at(15, 23, 0), elevenPm, served = oct14, timerRunning = false))
        // Outside the window nothing happens at all.
        assertNull(ScheduledSleep.look(at(15, 9, 0), elevenPm, served = null, timerRunning = false))
        assertNull(ScheduledSleep.look(at(14, 22, 59), elevenPm, served = null, timerRunning = false))
    }

    @Test
    fun `a timer already running is never replaced, and it counts as the day's`() {
        val look = ScheduledSleep.look(at(14, 23, 5), elevenPm, served = null, timerRunning = true)
        assertEquals(ScheduledSleep.Look(oct14, startTimer = false), look)
        // Written down as served, so the music put on after that timer plays on as well.
        assertNull(ScheduledSleep.look(at(14, 23, 40), elevenPm, served = look!!.night, timerRunning = false))
    }

    @Test
    fun `music on before the time is looked at again when the window opens`() {
        val sevenTo = ZonedDateTime.of(2026, 10, 14, 22, 53, 0, 0, paris)
        assertEquals(Duration.ofMinutes(7), ScheduledSleep.untilNextLook(sevenTo, elevenPm))
        // Further off, it looks again within a quarter of an hour, which sees a change of clock.
        assertEquals(ScheduledSleep.LONGEST_WAIT, ScheduledSleep.untilNextLook(sevenTo.minusHours(3), elevenPm))
        assertEquals(ScheduledSleep.LONGEST_WAIT, ScheduledSleep.untilNextLook(sevenTo.plusHours(1), elevenPm))
        // A wait that ended a moment early does not turn into a loop that never sleeps.
        assertEquals(Duration.ofSeconds(1), ScheduledSleep.untilNextLook(sevenTo.plusSeconds(419).plusNanos(999_000_000), elevenPm))
    }

    @Test
    fun `the wait is real time on the night the clocks change`() {
        // 29 March 2026 in Paris: 02:00 becomes 03:00. From 01:55 to 03:00 is five minutes.
        val beforeTheJump = ZonedDateTime.of(2026, 3, 29, 1, 55, 0, 0, paris)
        assertEquals(Duration.ofMinutes(5), ScheduledSleep.untilNextLook(beforeTheJump, 3 * 60))
    }

    @Test
    fun `a stored setting out of range reaches the timer as one it takes`() {
        assertEquals(ScheduledSleep.DEFAULT_START, ScheduledSleep.startOf(null))
        assertEquals(30, ScheduledSleep.startOf(24 * 60 + 30))
        assertEquals(23 * 60 + 30, ScheduledSleep.startOf(-30))
        assertEquals(ScheduledSleep.DEFAULT_LENGTH, ScheduledSleep.lengthOf(null))
        // 0 would stop the music at once and -1 means the end of the song.
        assertEquals(1, ScheduledSleep.lengthOf(0))
        assertEquals(1, ScheduledSleep.lengthOf(-1))
        assertEquals(SleepTimerDefaults.MINUTES_RANGE.last, ScheduledSleep.lengthOf(100_000))
    }

    @Test
    fun `the lengths offered are ones the timer takes, the default among them`() {
        assertTrue(ScheduledSleep.DEFAULT_LENGTH in ScheduledSleep.LENGTHS)
        assertTrue(ScheduledSleep.LENGTHS.all { it in SleepTimerDefaults.MINUTES_RANGE })
        assertEquals(ScheduledSleep.LENGTHS.sorted(), ScheduledSleep.LENGTHS)
        assertFalse(ScheduledSleep.LENGTHS.isEmpty())
    }
}
