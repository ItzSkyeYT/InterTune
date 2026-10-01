/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import com.dd3boh.outertune.engine.storedLocalToInstant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * What History shows, and when it says each play started.
 *
 * History used to be the event table, which holds only plays past Minimum playback duration (30%
 * of the song by default), so a song skipped after a minute never showed up. It now reads the
 * listen log, which records every play, and lists any play heard for [MIN_HEARD_MS] or more:
 * skips included, whatever share of the song that was, and whether or not its length is known.
 *
 * What counts as a play is not decided here and has not changed. Play counts, Most played, the
 * Stats page, YouTube's history and Last.fm still go by Minimum playback duration, through the
 * event table and the listen log's counted flag, so ten quick skips never make a song most played.
 *
 * HistorySql.PLAYS applies the same rule in SQL; HistoryRuleTest and HistorySqlTest hold both to it.
 */
object HistoryRule {
    const val MIN_HEARD_MS = 5_000L

    /**
     * Whether a play goes in History. A counted play always does, even under five seconds (most of
     * a short jingle): every counted play was in History before, and none drops out of it now.
     */
    fun shows(playedMs: Long, counted: Boolean): Boolean = counted || playedMs >= MIN_HEARD_MS

    /** When a listen started, as a true instant. One real row carries a start of 0 and is dated back from its end. */
    fun listenAt(startedAt: Long, endedAt: Long, playedMs: Long): Long =
        if (startedAt > 0) startedAt else endedAt - playedMs.coerceAtLeast(0)

    /** The wall clock when a listen started, at the offset it was played at rather than today's. */
    fun listenStart(startedAt: Long, endedAt: Long, playedMs: Long, tzOffsetMin: Int): LocalDateTime {
        val at = listenAt(startedAt, endedAt, playedMs)
        val offset = ZoneOffset.ofTotalSeconds(tzOffsetMin.coerceIn(-18 * 60, 18 * 60) * 60)
        return LocalDateTime.ofEpochSecond(Math.floorDiv(at, 1000L), (Math.floorMod(at, 1000L) * 1_000_000L).toInt(), offset)
    }

    /**
     * The wall clock when an event's play started. An event stores the wall clock at the end, read
     * as if it were UTC, so the start is that less the play time, or the end itself when the play
     * time is unknown. The same as the listen the backfill later makes of it.
     */
    fun eventStart(storedMs: Long, playTime: Long): LocalDateTime {
        val end = LocalDateTime.ofEpochSecond(Math.floorDiv(storedMs, 1000L), (Math.floorMod(storedMs, 1000L) * 1_000_000L).toInt(), ZoneOffset.UTC)
        return if (playTime > 0) end.minus(playTime, ChronoUnit.MILLIS) else end
    }

    /** When an event's play started, as a true instant, for ordering it among listens. */
    fun eventAt(storedMs: Long, playTime: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        storedLocalToInstant(storedMs, zone) - playTime.coerceAtLeast(0)
}
