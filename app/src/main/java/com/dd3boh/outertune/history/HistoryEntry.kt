/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import com.dd3boh.outertune.db.entities.HistoryPlay
import com.dd3boh.outertune.db.entities.HistoryPlayWithSong
import com.dd3boh.outertune.db.entities.Song
import java.time.LocalDateTime
import java.time.ZoneId

/** One row of the History screen: the play, its song, and when it started. */
data class HistoryEntry(
    val play: HistoryPlay,
    val song: Song,
    /** The wall clock when the play started, shown on the row and used for its day. */
    val start: LocalDateTime,
    /** The same moment as a true instant, for the order within a day. */
    val at: Long,
) {
    /** Unique across both kinds of row: a listen's id, or an event's id negated. */
    val key: Long = historyKey(play)

    companion object {
        fun of(row: HistoryPlayWithSong, zone: ZoneId = ZoneId.systemDefault()): HistoryEntry {
            val p = row.play
            val listenStarted = p.startedAt
            return if (p.listenId != null && listenStarted != null) {
                val ended = p.endedAt ?: 0L
                HistoryEntry(
                    play = p, song = row.song,
                    start = HistoryRule.listenStart(listenStarted, ended, p.playedMs, p.tzOffsetMin ?: 0),
                    at = HistoryRule.listenAt(listenStarted, ended, p.playedMs),
                )
            } else {
                val stored = p.timestamp ?: 0L
                HistoryEntry(
                    play = p, song = row.song,
                    start = HistoryRule.eventStart(stored, p.playedMs),
                    at = HistoryRule.eventAt(stored, p.playedMs, zone),
                )
            }
        }
    }
}

fun historyKey(play: HistoryPlay): Long = play.listenId ?: -(play.eventId ?: 0L)
