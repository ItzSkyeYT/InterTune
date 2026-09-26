/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * What the Stats page reads from the listen log for its insights, kept as constants so the DAO
 * and StatsSqlTest run the same text, the way [RecommendationSql] is.
 *
 * Plain reads, run once for each period the listener picks and never observed: the page must not
 * work everything out again on every write while music plays. Each one is either a range read on
 * the endedAt index or a single pass over the log.
 *
 * An open row (endReason 6) is a song still playing or a play the app died in; it has no end yet
 * and is left out everywhere. Where a start is needed and a row has none (one real row carries 0),
 * it is dated back from its end, the same as ListeningInsights does.
 */
object StatsSql {

    /**
     * Every closed listen that ended at or after :from. Its song's artist comes from
     * [SONG_ARTISTS], once per song: looked up here for every row, it more than doubled the time
     * this took over a long log.
     */
    const val LISTENS = """
        SELECT l.id, l.songId, l.startedAt, l.endedAt, l.tzOffsetMin, l.playedMs, l.endReason, l.origin,
            l.sessionId, l.counted, l.continuesListenId
        FROM listen l
        WHERE l.endedAt >= :from AND l.endReason != 6
    """

    /** The first credited artist of every song in the log. A song with no artist has no row. */
    const val SONG_ARTISTS = """
        SELECT m.songId, m.artistId
        FROM song_artist_map m
        WHERE m.songId IN (SELECT songId FROM listen)
            AND m.position = (SELECT MIN(m2.position) FROM song_artist_map m2 WHERE m2.songId = m.songId)
    """

    /**
     * Each song heard before :before, once: when it was first heard, when it last had a counted
     * play, and how many it had. This is all the page needs of the history before a period, for
     * what is new, what came back and when the top song was found, without reading that history
     * row by row.
     *
     * The + in the GROUP BY is deliberate. Without it SQLite walks the songId index and fetches
     * the table a row at a time in song order; with it, it reads the endedAt range and groups in
     * a temporary tree, which took half the time over a log of 150,000 listens.
     */
    const val SONGS_BEFORE = """
        SELECT l.songId,
            MIN(CASE WHEN l.startedAt > 0 THEN l.startedAt ELSE l.endedAt - l.playedMs END) AS firstAt,
            MAX(CASE WHEN l.counted THEN (CASE WHEN l.startedAt > 0 THEN l.startedAt ELSE l.endedAt - l.playedMs END) END) AS lastPlayAt,
            SUM(CASE WHEN l.counted THEN 1 ELSE 0 END) AS playsBefore
        FROM listen l
        WHERE l.endedAt < :before AND l.endReason != 6
        GROUP BY +l.songId
    """

    /** Listening that ended in [:from, :to), for the comparison with the period before. */
    const val TOTALS = """
        SELECT COUNT(*) AS listens,
            COALESCE(SUM(playedMs), 0) AS playedMs,
            COALESCE(SUM(CASE WHEN counted THEN playedMs ELSE 0 END), 0) AS countedMs,
            COALESCE(SUM(CASE WHEN endReason = 0 THEN 1 ELSE 0 END), 0) AS legacyListens
        FROM listen
        WHERE endedAt >= :from AND endedAt < :to AND endReason != 6
    """

    /**
     * Where the log begins, and where the live log begins. Backfilled rows are the only ones with
     * an unknown ending (0), so the first row with any other is the first the live log wrote.
     */
    const val BOUNDS = """
        SELECT MIN(CASE WHEN startedAt > 0 THEN startedAt ELSE endedAt - playedMs END) AS firstAt,
            MIN(CASE WHEN endReason != 0 THEN (CASE WHEN startedAt > 0 THEN startedAt ELSE endedAt - playedMs END) END) AS firstLiveAt
        FROM listen
        WHERE endReason != 6
    """
}
