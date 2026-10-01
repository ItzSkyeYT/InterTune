/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import com.dd3boh.outertune.db.entities.HistoryPlay
import com.dd3boh.outertune.db.entities.HistoryPlayWithSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Which start time and instant a History row takes, from a listen or from an event. */
class HistoryEntryTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val song = Song(SongEntity(id = "a", title = "a", localPath = null), artists = emptyList())

    private fun utc(local: LocalDateTime) = local.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun listenRow(startedAt: Long, endedAt: Long, playedMs: Long, tzOffsetMin: Int) = HistoryPlayWithSong(
        HistoryPlay(
            listenId = 7, eventId = null, songId = "a", startedAt = startedAt, endedAt = endedAt,
            tzOffsetMin = tzOffsetMin, timestamp = null, playedMs = playedMs, counted = false, sortAt = startedAt,
        ),
        song,
    )

    @Test
    fun `a listen starts at its own time, on the clock where it was played`() {
        // 12:05 UTC, heard in New York (UTC-4), looked at from Paris.
        val startedAt = utc(LocalDateTime.of(2026, 9, 28, 12, 5))
        val entry = HistoryEntry.of(listenRow(startedAt, startedAt + 200_000, 200_000, tzOffsetMin = -240), paris)
        assertEquals(LocalDateTime.of(2026, 9, 28, 8, 5), entry.start)
        assertEquals(startedAt, entry.at)
        assertEquals(7L, entry.key)
    }

    @Test
    fun `a listen with no start is dated back from its end`() {
        val endedAt = utc(LocalDateTime.of(2026, 9, 28, 12, 8))
        val entry = HistoryEntry.of(listenRow(0L, endedAt, 180_000, tzOffsetMin = 120), paris)
        assertEquals(LocalDateTime.of(2026, 9, 28, 14, 5), entry.start)
        assertEquals(endedAt - 180_000, entry.at)
    }

    @Test
    fun `an event with no listen starts its play time before the wall clock it stored`() {
        // Stored as the wall clock at the end, 14:08 in Paris, read as if it were UTC.
        val stored = utc(LocalDateTime.of(2026, 9, 28, 14, 8))
        val row = HistoryPlayWithSong(
            HistoryPlay(
                listenId = null, eventId = 3, songId = "a", startedAt = null, endedAt = null,
                tzOffsetMin = null, timestamp = stored, playedMs = 180_000, counted = true, sortAt = stored - 180_000,
            ),
            song,
        )
        val entry = HistoryEntry.of(row, paris)
        assertEquals(LocalDateTime.of(2026, 9, 28, 14, 5), entry.start)
        // 14:05 in Paris in summer is 12:05 UTC.
        assertEquals(utc(LocalDateTime.of(2026, 9, 28, 12, 5)), entry.at)
        assertEquals(-3L, entry.key)
    }
}
