/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.history.HistoryRule

/**
 * What the History screen reads, kept as constants so the DAO and HistorySqlTest run the same
 * text, the way [StatsSql] is.
 *
 * History is the listen log, not the event table: every closed play heard for
 * [HistoryRule.MIN_HEARD_MS] or more, or counted, whatever its share of the song. The event table
 * keeps meaning a counted play, for everything that counts plays.
 *
 * A piece of a play is out of History when the listener took it out: Remove from history marks
 * it with a [SignalKind.REMOVED_FROM_HISTORY] signal, and a play taken out before History read
 * this log lost its event, which leaves the listen pointing at an event that is gone. Either way
 * the listen itself stays, because the engine learns from it, as it always did.
 */
object HistorySql {
    private const val L_REMOVED = """((l.sourceEventId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event e WHERE e.id = l.sourceEventId))
        OR EXISTS (SELECT 1 FROM listen_signal s WHERE s.listenId = l.id AND s.kind = ${SignalKind.REMOVED_FROM_HISTORY}))"""

    private const val P_REMOVED = """((p.sourceEventId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event e WHERE e.id = p.sourceEventId))
        OR EXISTS (SELECT 1 FROM listen_signal s WHERE s.listenId = p.id AND s.kind = ${SignalKind.REMOVED_FROM_HISTORY}))"""

    /** A piece History can show: closed (an open row is a song still playing) and not taken out. */
    private const val P_VISIBLE = "p.endReason != 6 AND NOT $P_REMOVED"

    /**
     * Every play History shows, newest first.
     *
     * A song paused, left and resumed where it stopped is one play in several pieces, each
     * pointing at the one before by continuesListenId. Each visible piece walks back to the first
     * visible piece of its chain (a primary key lookup per step, as the column has no index), and
     * the chain is one row: dated by its first piece, heard for all of them together. A piece
     * taken out of History breaks the chain there, so a later resume of a removed play is a play
     * of its own. `p.id < up.cur` holds because a resume always points back at an older row, and
     * it means the walk ends even on a database that says otherwise.
     *
     * An event no listen points at is a counted play the backfill has not reached yet (it runs on
     * the first start after an update from before the listen log); it is shown from the event, so
     * History is never empty while that runs.
     */
    const val PLAYS = """
        WITH RECURSIVE up(id, cur, prev) AS (
            SELECT l.id, l.id, l.continuesListenId FROM listen l
            WHERE l.endReason != 6 AND NOT $L_REMOVED
            UNION ALL
            SELECT up.id, p.id, p.continuesListenId FROM up JOIN listen p ON p.id = up.prev
            WHERE p.id < up.cur AND $P_VISIBLE
        )
        SELECT h.id AS listenId, NULL AS eventId, h.songId AS songId, h.startedAt AS startedAt, h.endedAt AS endedAt,
            h.tzOffsetMin AS tzOffsetMin, NULL AS timestamp, SUM(l.playedMs) AS playedMs, MAX(l.counted) AS counted,
            CASE WHEN h.startedAt > 0 THEN h.startedAt ELSE h.endedAt - h.playedMs END AS sortAt
        FROM up
            JOIN listen l ON l.id = up.id
            JOIN listen h ON h.id = up.cur
        WHERE NOT EXISTS (SELECT 1 FROM listen p WHERE p.id = up.prev AND p.id < up.cur AND $P_VISIBLE)
        GROUP BY up.cur
        HAVING SUM(l.playedMs) >= ${HistoryRule.MIN_HEARD_MS} OR MAX(l.counted) = 1
        UNION ALL
        SELECT NULL, e.id, e.songId, NULL, NULL, NULL, e.timestamp, e.playTime, 1, e.timestamp - e.playTime
        FROM event e
        WHERE NOT EXISTS (SELECT 1 FROM listen l WHERE l.sourceEventId = e.id)
        ORDER BY sortAt DESC
    """

    /**
     * The pieces of the play that starts at listen :head, first piece first: the head and every
     * resume after it that is not already out of History, the one still playing included, so that
     * it stays out once it closes.
     */
    const val CHAIN = """
        WITH RECURSIVE down(id) AS (
            SELECT :head
            UNION ALL
            SELECT l.id FROM down JOIN listen l ON l.continuesListenId = down.id
            WHERE l.id > down.id AND NOT $L_REMOVED
        )
        SELECT l.id AS id, l.songId AS songId, l.sourceEventId AS sourceEventId
        FROM down JOIN listen l ON l.id = down.id
        ORDER BY l.id
    """

    /** What Remove from history writes against each piece: a signal, so the listen itself is kept. */
    const val MARK_REMOVED = """
        INSERT INTO listen_signal (listenId, songId, kind, positionMs, value, at)
        VALUES (:listenId, :songId, ${SignalKind.REMOVED_FROM_HISTORY}, -1, 0, :at)
    """

    const val DELETE_EVENT = "DELETE FROM event WHERE id = :id"
}
