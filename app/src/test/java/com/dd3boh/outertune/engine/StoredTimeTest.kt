/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class StoredTimeTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val newYork = ZoneId.of("America/New_York")

    /** What Converters writes for a LocalDateTime: the wall clock read as if it were UTC. */
    private fun stored(local: LocalDateTime) = local.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `a summer like in Paris comes back two hours earlier`() {
        val local = LocalDateTime.of(2026, 7, 14, 15, 30)
        val trueInstant = local.atZone(paris).toInstant().toEpochMilli()
        assertEquals(trueInstant, storedLocalToInstant(stored(local), paris))
        assertEquals(120, offsetMinutesAtStoredLocal(stored(local), paris))
    }

    @Test
    fun `a winter like in Paris comes back one hour earlier`() {
        val local = LocalDateTime.of(2026, 1, 14, 15, 30)
        assertEquals(local.atZone(paris).toInstant().toEpochMilli(), storedLocalToInstant(stored(local), paris))
        assertEquals(60, offsetMinutesAtStoredLocal(stored(local), paris))
    }

    @Test
    fun `west of Greenwich the stored value is behind the instant`() {
        val local = LocalDateTime.of(2026, 3, 1, 9, 0)
        assertEquals(local.atZone(newYork).toInstant().toEpochMilli(), storedLocalToInstant(stored(local), newYork))
        assertEquals(-300, offsetMinutesAtStoredLocal(stored(local), newYork))
    }

    @Test
    fun `utc is the identity`() {
        val ms = 1_757_570_000_000L
        assertEquals(ms, storedLocalToInstant(ms, ZoneOffset.UTC))
    }

    @Test
    fun `an age is never negative`() {
        assertEquals(0.0, ageHours(nowMs = 1_000, thenMs = 5_000), 0.0)
        assertEquals(2.0, ageHours(nowMs = 7_200_000, thenMs = 0), 1e-9)
    }
}
