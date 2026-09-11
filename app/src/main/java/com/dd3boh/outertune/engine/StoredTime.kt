/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `Converters` stores a `LocalDateTime` as though the wall clock were UTC, so every such column
 * (`event.timestamp`, `song.likedDate`, `inLibrary`, `dateDownload`) holds the true instant plus
 * the zone offset in force at the time. Reading one as an instant without undoing that puts a
 * fresh like two hours in the future in France, and an age that comes out negative turns into NaN
 * the moment it meets a power. These undo it, using the offset the zone had at that local time.
 */
fun storedLocalToInstant(storedMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    storedMs - offsetMinutesAtStoredLocal(storedMs, zone) * 60_000L

/** The zone's offset from UTC, in minutes, at the local time a stored value spells out. */
fun offsetMinutesAtStoredLocal(storedMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(storedMs), ZoneOffset.UTC)
    return zone.rules.getOffset(local).totalSeconds / 60
}

/** Hours since [thenMs] as of [nowMs], never negative: clocks and offsets can put "then" after "now". */
fun ageHours(nowMs: Long, thenMs: Long): Double = ((nowMs - thenMs) / 3_600_000.0).coerceAtLeast(0.0)
