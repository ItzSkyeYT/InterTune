/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * What the InterTune Recap reads from the listen log, kept as constants so that the DAO and
 * RecapSqlTest run the same text, the way [StatsSql] is. Plain reads, run once when the Recap is
 * worked out and never observed. With StatsSql.SONGS_BEFORE, SONG_ARTISTS and BOUNDS, which it
 * reads as the Stats page does.
 *
 * An open row (endReason 6) is a song still playing or a play the app died in, and is left out
 * everywhere, as on the Stats page.
 */
object RecapSql {

    /** Every closed listen that ended at or after :from: the Stats page's row, with the Quick picks chip. */
    const val LISTENS = """
        SELECT l.id, l.songId, l.startedAt, l.endedAt, l.tzOffsetMin, l.playedMs, l.endReason, l.origin,
            l.sessionId, l.counted, l.continuesListenId, l.contextChip
        FROM listen l
        WHERE l.endedAt >= :from AND l.endReason != 6
    """

    /**
     * Every artist credited on every song heard since :from, each at its place. Top artists count
     * every credit, as the Stats page's Most played artists row does: a song by two artists is a
     * play for each of them.
     */
    const val CREDITS = """
        SELECT m.songId, m.artistId, m.position
        FROM song_artist_map m
        WHERE m.songId IN (SELECT songId FROM listen WHERE endedAt >= :from AND endReason != 6)
    """

    /** The songs heard since :from that are liked now: Auto's finds that were liked are ringed. */
    const val LIKED = """
        SELECT s.id
        FROM song s
        WHERE s.liked AND s.id IN (SELECT songId FROM listen WHERE endedAt >= :from AND endReason != 6)
    """

    /**
     * Counted listens that ended in [:from, :to) and the time heard in all of them: whether a
     * period has enough for a Recap, read before working one out. Not the plays of the Recap
     * itself, which leave out the second half of a paused play; near enough to decide on.
     */
    const val AMOUNT = """
        SELECT COALESCE(SUM(CASE WHEN counted THEN 1 ELSE 0 END), 0) AS plays,
            COALESCE(SUM(playedMs), 0) AS playedMs
        FROM listen
        WHERE endedAt >= :from AND endedAt < :to AND endReason != 6
    """
}
