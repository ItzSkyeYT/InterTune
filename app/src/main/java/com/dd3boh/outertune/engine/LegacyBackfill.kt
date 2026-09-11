/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.db.entities.Listen
import java.time.ZoneId

/** One row of the legacy play log, with the song's length beside it. */
data class LegacyEventRow(
    val id: Long,
    val songId: String,
    /** As stored: the local wall clock at the end of the play, read as if it were UTC. */
    val timestamp: Long,
    val playTime: Long,
    /** Seconds, or -1 when the song's length was never learned. */
    val duration: Int,
)

/** The end and session of the newest listen the backfill has already written. */
data class BackfillCursor(val endedAt: Long, val sessionId: Long)

/** What the backfill needs from the database, so the same code runs against Room and against a plain JDBC copy in tests. */
interface BackfillIo {
    /** Events with no listen yet, oldest first, ids above [afterId], at most [limit]. */
    fun pendingEvents(afterId: Long, limit: Int): List<LegacyEventRow>
    fun lastBackfilled(): BackfillCursor?
    /** Inserts in one transaction, ignoring rows whose event is already there. */
    fun insertLegacyListens(rows: List<Listen>)
    /** Stamps undated related edges with their seed's first listen, and drops duplicate pairs. */
    fun tidyLegacyEdges()
}

/**
 * Turns the legacy `event` log into listens, once. A counted play used to leave only an end time
 * and a play length; each becomes a listen with an unknown end reason and origin, its clock
 * corrected for the way `Converters` stored it, and sessions cut by the same 30 minute rule the
 * live log uses. Idempotent: every row carries its event id under a unique index, so a second run
 * writes nothing, and a run killed halfway resumes at the first event without a listen.
 */
class LegacyBackfill(
    private val io: BackfillIo,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val batch: Int = 500,
) {
    /** Returns how many listens were written. */
    fun run(): Int {
        var previous = io.lastBackfilled()
        var after = 0L
        var written = 0
        while (true) {
            val events = io.pendingEvents(after, batch)
            if (events.isEmpty()) break
            val rows = events.map { e ->
                val endedAt = storedLocalToInstant(e.timestamp, zone)
                val startedAt = endedAt - e.playTime.coerceAtLeast(0L)
                val sessionId = previous?.takeIf { startedAt - it.endedAt <= SESSION_GAP_MS }?.sessionId ?: startedAt
                previous = BackfillCursor(endedAt, sessionId)
                val durationMs = if (e.duration > 0) e.duration * 1000L else -1L
                Listen(
                    songId = e.songId,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    tzOffsetMin = offsetMinutesAtStoredLocal(e.timestamp, zone),
                    playedMs = e.playTime,
                    durationMs = durationMs,
                    ratio = if (durationMs > 0) (e.playTime.toFloat() / durationMs).coerceAtMost(1f) else -1f,
                    endReason = EndReason.UNKNOWN,
                    origin = PlayOrigin.UNKNOWN.code,
                    originSlot = -1,
                    queueId = 0L,
                    autoplayDepth = 0,
                    sessionId = sessionId,
                    counted = true,
                    sourceEventId = e.id,
                )
            }
            io.insertLegacyListens(rows)
            written += rows.size
            after = events.last().id
        }
        io.tidyLegacyEdges()
        return written
    }

    companion object {
        /** The same boundary as the live log: silence longer than this starts a new session. */
        const val SESSION_GAP_MS = 30L * 60 * 1000
    }
}
