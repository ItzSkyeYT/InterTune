/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryRuleTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun utc(local: LocalDateTime) = local.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `two seconds heard is enough, whatever share of the song`() {
        assertTrue(HistoryRule.shows(playedMs = 2_000, counted = false))
        assertTrue(HistoryRule.shows(playedMs = 60_000, counted = false))
        assertFalse(HistoryRule.shows(playedMs = 1_999, counted = false))
        assertFalse(HistoryRule.shows(playedMs = 0, counted = false))
    }

    @Test
    fun `a counted play stays in History even under two seconds`() {
        // Most of a two second jingle passes 30%; it was in History before and still is.
        assertTrue(HistoryRule.shows(playedMs = 1_500, counted = true))
    }

    @Test
    fun `a song played again straight after itself is dated back from its end`() {
        // As in one real library (listen 8425): opened when the play before it ended, closed 38 ms later
        // with the 123 seconds that play had heard, which began at 18:48:40.
        val end = utc(LocalDateTime.of(2026, 9, 23, 16, 50, 44))
        assertEquals(end - 123_466, HistoryRule.listenAt(end - 38, end, 123_466))
        assertEquals(LocalDateTime.of(2026, 9, 23, 18, 48, 40, 534_000_000), HistoryRule.listenStart(end - 38, end, 123_466, 120))
    }

    @Test
    fun `a span short of what was heard by under a second is clock jitter`() {
        val start = utc(LocalDateTime.of(2026, 9, 28, 12, 5))
        assertEquals(start, HistoryRule.listenAt(start, start + 199_001, 200_000))
        assertEquals(start + 198_999 - 200_000, HistoryRule.listenAt(start, start + 198_999, 200_000))
        // Paused along the way: the span is longer than what was heard, as usual.
        assertEquals(start, HistoryRule.listenAt(start, start + 600_000, 200_000))
    }

    @Test
    fun `a listen is dated at the offset it was played at`() {
        val startedAt = utc(LocalDateTime.of(2026, 9, 28, 12, 5))
        assertEquals(LocalDateTime.of(2026, 9, 28, 14, 5), HistoryRule.listenStart(startedAt, startedAt + 200_000, 200_000, 120))
        // The same instant, played in New York.
        assertEquals(LocalDateTime.of(2026, 9, 28, 8, 5), HistoryRule.listenStart(startedAt, startedAt + 200_000, 200_000, -240))
    }

    @Test
    fun `a listen with no start is dated back from its end`() {
        val endedAt = utc(LocalDateTime.of(2026, 9, 19, 21, 0))
        assertEquals(LocalDateTime.of(2026, 9, 19, 21, 57), HistoryRule.listenStart(0, endedAt, 180_000, 60))
        assertEquals(endedAt - 180_000, HistoryRule.listenAt(0, endedAt, 180_000))
    }

    @Test
    fun `either side of the spring change each listen keeps its own offset`() {
        // Paris went from +1 to +2 at 01:00 UTC on 29 March 2026.
        val before = utc(LocalDateTime.of(2026, 3, 29, 0, 30))
        val after = utc(LocalDateTime.of(2026, 3, 29, 1, 30))
        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), HistoryRule.listenStart(before, before, 0, 60))
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), HistoryRule.listenStart(after, after, 0, 120))
    }

    @Test
    fun `an event is dated by its play time back from the stored end`() {
        val stored = utc(LocalDateTime.of(2026, 9, 28, 0, 2))
        // Started before midnight: it belongs to the day before the one it ended on.
        assertEquals(LocalDateTime.of(2026, 9, 27, 23, 59), HistoryRule.eventStart(stored, 180_000))
        assertEquals(LocalDateTime.of(2026, 9, 28, 0, 2), HistoryRule.eventStart(stored, 0))
    }

    @Test
    fun `an event is ordered by the true instant it started`() {
        val stored = utc(LocalDateTime.of(2026, 7, 14, 15, 30))
        val trueEnd = LocalDateTime.of(2026, 7, 14, 15, 30).atZone(paris).toInstant().toEpochMilli()
        assertEquals(trueEnd - 60_000, HistoryRule.eventAt(stored, 60_000, paris))
    }
}
