/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.constants.EndReason
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
 *
 * The fragments come once per table alias (l for the row in hand, p or d for the piece it
 * continues), as a Room query has to be a constant.
 */
object HistorySql {
    private const val L_REMOVED = """((l.sourceEventId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event e WHERE e.id = l.sourceEventId))
        OR EXISTS (SELECT 1 FROM listen_signal s WHERE s.listenId = l.id AND s.kind = ${SignalKind.REMOVED_FROM_HISTORY}))"""

    private const val P_REMOVED = """((p.sourceEventId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM event e WHERE e.id = p.sourceEventId))
        OR EXISTS (SELECT 1 FROM listen_signal s WHERE s.listenId = p.id AND s.kind = ${SignalKind.REMOVED_FROM_HISTORY}))"""

    /**
     * A play the service wrote twice. When MusicService.logListen loses track of the row it opened
     * for a play, it writes the whole play again as a new row, and the open one is closed as
     * stopped at the next launch, from its last checkpoint, with no event. The two start within a
     * few milliseconds of each other (16 and 29 ms on his phone); the later row is the play.
     * Otherwise a later row of the same song starts after the stopped piece ends, so within a
     * second of its start only when the piece lasted under a second, too short for History anyway.
     * A song repeated back to back also leaves two rows that start milliseconds apart, but the
     * first of those ended rather than stopped, and both are plays.
     */
    private const val L_REWRITTEN = """(l.endReason = ${EndReason.STOPPED} AND l.sourceEventId IS NULL
        AND EXISTS (SELECT 1 FROM listen o WHERE o.songId = l.songId AND o.id > l.id
            AND o.startedAt BETWEEN l.startedAt - 1000 AND l.startedAt + 1000))"""

    private const val P_REWRITTEN = """(p.endReason = ${EndReason.STOPPED} AND p.sourceEventId IS NULL
        AND EXISTS (SELECT 1 FROM listen o WHERE o.songId = p.songId AND o.id > p.id
            AND o.startedAt BETWEEN p.startedAt - 1000 AND p.startedAt + 1000))"""

    /** A piece History can show: closed (an open row is a song still playing), not taken out, not a copy. */
    private const val L_VISIBLE = "l.endReason != ${EndReason.OPEN} AND NOT $L_REMOVED AND NOT $L_REWRITTEN"
    private const val P_VISIBLE = "p.endReason != ${EndReason.OPEN} AND NOT $P_REMOVED AND NOT $P_REWRITTEN"

    /** MusicService's RESUME_TOLERANCE_MS: a resume may start this far from where the piece stopped. */
    private const val RESUME_EARLY_MS = 5_000

    /**
     * Whether up.cur resumes p, the piece it says it continues.
     *
     * Only a piece of the same song does. The service only ever links a song to itself; asking it
     * here as well keeps this walk the mirror of CHAIN's, which finds a resume by its song.
     *
     * Only the first piece that continues p does. The service links a play to the newest stopped
     * piece of its song that ended where the play starts, even when the song has been played
     * through since, so a piece stopped near the start can be continued by every play of the song
     * from the top for a day; each after the first is a play of its own. They lie between the two
     * ids, so this reads a day's rows at most, by primary key.
     *
     * And only if it heard no more than what was left of the song, from up to five seconds before
     * where p stopped (the service's own tolerance for a resume). A play linked to a stopped piece
     * that then went on to hear the whole song, back from the top, is a play of its own: in one
     * real library, a play four hours later that heard 201 seconds of a song stopped at 2:44 of
     * 3:20. A song of unknown length is not judged.
     */
    private const val CUR_RESUMES_P = """p.songId = up.songId
        AND NOT EXISTS (SELECT 1 FROM listen x WHERE x.id > p.id AND x.id < up.cur AND x.continuesListenId = p.id)
        AND NOT EXISTS (SELECT 1 FROM listen c WHERE c.id = up.cur
            AND c.durationMs > 0 AND c.playedMs > c.durationMs - p.endPositionMs + $RESUME_EARLY_MS)"""

    /**
     * The same as [CUR_RESUMES_P] for CHAIN's walk the other way: whether l resumes d. CHAIN's join
     * has already asked for the same song, which is what lets it find l by the songId index.
     */
    private const val L_RESUMES_D = """NOT EXISTS (SELECT 1 FROM listen x WHERE x.id > d.id AND x.id < l.id AND x.continuesListenId = d.id)
        AND NOT (l.durationMs > 0 AND l.playedMs > l.durationMs - d.endPositionMs + $RESUME_EARLY_MS)"""

    /**
     * Each visible piece (id) walked back to every piece it continues, one row a step: cur is the
     * piece reached, prev the one cur says it continues, songId the song of them all. The rows
     * where cur can go no further ([UP_AT_HEAD]) give each piece the first piece of its play.
     * `p.id < up.cur` holds because a resume always points back at an older row, and it means the
     * walk ends even on a database that says otherwise.
     */
    internal const val UP = """
        WITH RECURSIVE up(id, cur, prev, songId) AS (
            SELECT l.id, l.id, l.continuesListenId, l.songId FROM listen l
            WHERE $L_VISIBLE
            UNION ALL
            SELECT up.id, p.id, p.continuesListenId, p.songId FROM up JOIN listen p ON p.id = up.prev
            WHERE p.id < up.cur AND $P_VISIBLE AND $CUR_RESUMES_P
        )
    """

    /** An [UP] row whose cur is the first piece of the play: the piece it continues, if any, does not take it. */
    internal const val UP_AT_HEAD = """NOT EXISTS (SELECT 1 FROM listen p WHERE p.id = up.prev AND p.id < up.cur AND $P_VISIBLE AND $CUR_RESUMES_P)"""

    /**
     * Every play History shows, newest first.
     *
     * A song paused, left and resumed where it stopped is one play in several pieces, each
     * pointing at the one before by continuesListenId. Each visible piece walks back to the first
     * visible piece of its chain ([UP], a primary key lookup per step), and the chain is one row:
     * dated by its first piece (sortAt is HistoryRule.listenAt), heard for all of them together.
     * A piece taken out of History breaks the chain there, so a later resume of a removed play is
     * a play of its own.
     *
     * An event no listen points at is a counted play the backfill has not reached yet (it runs on
     * the first start after an update from before the listen log); it is shown from the event, so
     * History is never empty while that runs.
     */
    const val PLAYS = """
        $UP
        SELECT h.id AS listenId, NULL AS eventId, h.songId AS songId, h.startedAt AS startedAt, h.endedAt AS endedAt,
            h.tzOffsetMin AS tzOffsetMin, NULL AS timestamp, SUM(l.playedMs) AS playedMs, MAX(l.counted) AS counted,
            h.playedMs AS headPlayedMs,
            CASE WHEN h.startedAt > 0 AND (h.endedAt <= 0 OR h.endedAt - h.startedAt >= h.playedMs - ${HistoryRule.SPAN_SLACK_MS})
                THEN h.startedAt ELSE h.endedAt - h.playedMs END AS sortAt
        FROM up
            JOIN listen l ON l.id = up.id
            JOIN listen h ON h.id = up.cur
        WHERE $UP_AT_HEAD
        GROUP BY up.cur
        HAVING SUM(l.playedMs) >= ${HistoryRule.MIN_HEARD_MS} OR MAX(l.counted) = 1
        UNION ALL
        SELECT NULL, e.id, e.songId, NULL, NULL, NULL, e.timestamp, e.playTime, 1, e.playTime, e.timestamp - e.playTime
        FROM event e
        WHERE NOT EXISTS (SELECT 1 FROM listen l WHERE l.sourceEventId = e.id)
        ORDER BY sortAt DESC
    """

    /**
     * The pieces of the play that starts at listen :head, first piece first: the head and each
     * piece PLAYS joins to it, the same links walked the other way, so Remove from history takes
     * out exactly what the row showed. A resume still playing is not one of them: it is not on the
     * row yet, and once it ends it is listed as a play of its own, since the piece it continues is
     * out of History.
     *
     * A resume is looked for among the later rows of the same song, by the songId index.
     * continuesListenId has none, and joining on it alone had SQLite build a temporary index on
     * every call: about 7 ms a play, a minute for Select all on a library of 8,600 plays, all in
     * one write transaction. By song it reads that song's later rows, 89 at most there.
     */
    const val CHAIN = """
        WITH RECURSIVE down(id) AS (
            SELECT :head
            UNION ALL
            SELECT l.id FROM down JOIN listen d ON d.id = down.id
                JOIN listen l ON l.songId = d.songId AND l.continuesListenId = d.id
            WHERE l.id > d.id AND $L_VISIBLE AND $L_RESUMES_D
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

    /** What the listener did beyond playing, for the Recommendations page; History's removal marks are not that. */
    const val SIGNAL_COUNT = "SELECT COUNT(*) FROM listen_signal WHERE kind != ${SignalKind.REMOVED_FROM_HISTORY}"
}
