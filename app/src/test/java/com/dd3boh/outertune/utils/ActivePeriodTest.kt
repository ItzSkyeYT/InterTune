/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** How often a device says it is here, and how long it keeps the same name while doing so. */
class ActivePeriodTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** A counter that proves whether a new name was minted, and hands back a different one each time. */
    private class Mint {
        var calls = 0
        fun next(): String = "fresh-${++calls}"
    }

    @Test
    fun `a first run mints a name and counts the day`() {
        val mint = Mint()
        val ping = ActivePeriod.decide(
            nowMillis = at(2026, 9, 17),
            zone = paris,
            storedId = "",
            storedPeriod = "",
            lastDay = "",
            freshId = mint::next,
        )

        assertEquals("fresh-1", ping?.id)
        assertEquals("2026-09", ping?.period)
        assertEquals("2026-09-17", ping?.day)
        assertTrue(ping!!.rotated)
        assertEquals(1, mint.calls)
    }

    @Test
    fun `opening the app again the same day sends nothing`() {
        val mint = Mint()
        val ping = ActivePeriod.decide(
            nowMillis = at(2026, 9, 17, h = 20),
            zone = paris,
            storedId = "old",
            storedPeriod = "2026-09",
            lastDay = "2026-09-17",
            freshId = mint::next,
        )

        assertNull(ping)
        // Nothing minted either: a name is only ever replaced by a month, never by a second look.
        assertEquals(0, mint.calls)
    }

    @Test
    fun `the next day keeps the same name`() {
        val mint = Mint()
        val ping = ActivePeriod.decide(
            nowMillis = at(2026, 9, 18),
            zone = paris,
            storedId = "old",
            storedPeriod = "2026-09",
            lastDay = "2026-09-17",
            freshId = mint::next,
        )

        // This is what makes a month's distinct count a count of devices rather than of days.
        assertEquals("old", ping?.id)
        assertFalse(ping!!.rotated)
        assertEquals(0, mint.calls)
    }

    @Test
    fun `a new month mints an unrelated name`() {
        val mint = Mint()
        val ping = ActivePeriod.decide(
            nowMillis = at(2026, 10, 1),
            zone = paris,
            storedId = "old",
            storedPeriod = "2026-09",
            lastDay = "2026-09-30",
            freshId = mint::next,
        )

        assertNotEquals("old", ping?.id)
        assertEquals("2026-10", ping?.period)
        assertTrue(ping!!.rotated)
    }

    @Test
    fun `a gap of months still only mints one name`() {
        val mint = Mint()
        val ping = ActivePeriod.decide(
            nowMillis = at(2027, 2, 14),
            zone = paris,
            storedId = "old",
            storedPeriod = "2026-09",
            lastDay = "2026-09-30",
            freshId = mint::next,
        )

        assertNotEquals("old", ping?.id)
        assertEquals("2027-02", ping?.period)
        assertEquals(1, mint.calls)
    }

    @Test
    fun `the day is the listener's own, not UTC`() {
        // Half past eleven at night in London on the 16th is already the 17th in Paris. Somebody
        // there who last opened the app on the 17th should not be counted twice for one evening.
        val lateEvening = at(2026, 9, 16, h = 23, mi = 30)

        assertEquals("2026-09-16", ActivePeriod.day(lateEvening, ZoneOffset.UTC))
        assertEquals("2026-09-17", ActivePeriod.day(lateEvening, paris))

        assertNull(
            ActivePeriod.decide(
                nowMillis = lateEvening,
                zone = paris,
                storedId = "old",
                storedPeriod = "2026-09",
                lastDay = "2026-09-17",
                freshId = { "unexpected" },
            )
        )
    }

    @Test
    fun `the month turns everywhere at once`() {
        // The same instant is October in Paris and still September in UTC. The period follows UTC
        // so that two devices either side of midnight do not land in different months and inflate
        // both of them.
        val turnover = at(2026, 9, 30, h = 23, mi = 30)

        assertEquals("2026-09", ActivePeriod.period(turnover))
        assertEquals("2026-10-01", ActivePeriod.day(turnover, paris))
    }
}
