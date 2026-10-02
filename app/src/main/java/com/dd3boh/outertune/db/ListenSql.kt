/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Reads of the listen log that decide what a play is, kept as constants so ListenDao and
 * ListenSqlTest run the same text, the way [StatsSql] is.
 */
object ListenSql {

    /**
     * The latest play of :songId that was cut off where it stood (stopped, or stopped on a playback
     * error) or is still open, for linking a resume to it. An open row is included so that a newer
     * play still waiting for its close is found before an older stop, and then turned down by
     * ListenProgress.continues, which links only to a stop or an error. Only the song's latest play
     * counts, and only if nothing carries it on yet: a stop that a later play already continued, or
     * one behind a play that finished, is not where a new play picks up.
     */
    const val LAST_RESUMABLE = """
        SELECT * FROM listen l
        WHERE l.id = (SELECT MAX(id) FROM listen WHERE songId = :songId)
            AND l.endReason IN (4, 5, 6)
            AND NOT EXISTS (SELECT 1 FROM listen c WHERE c.songId = l.songId AND c.continuesListenId = l.id)
    """

    /**
     * What the listener has just had, for the Tidy pass to keep out of Quick picks: heard at
     * engagement 0.5 or more (45% of a known length, or two minutes of an unknown one) in the last
     * day, or started at all in the given session. A play that failed (endReason 5) is neither: it
     * says nothing about the song, so the recommendations read it as if it had not happened.
     */
    const val JUST_PLAYED = """
        SELECT DISTINCT s.id AS id, s.title AS title,
        (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artist
        FROM listen l JOIN song s ON s.id = l.songId
        WHERE l.endReason != 5
          AND ((l.startedAt >= :dayAgo AND l.playedMs >= 30000 AND ((l.durationMs > 0 AND l.playedMs * 20 >= l.durationMs * 9) OR (l.durationMs <= 0 AND l.playedMs >= 120000)))
            OR l.sessionId = :sessionId)
    """
}
