/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * What the Stats page reads from the listen log for its insights, and its Most played artists row,
 * kept as constants so the DAOs and StatsSqlTest run the same text, the way [RecommendationSql] is.
 *
 * The insights' reads are plain reads, run once for each period the listener picks and never
 * observed: the page must not work everything out again on every write while music plays. Each
 * one is either a range read on the endedAt index or a single pass over the log.
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

    /**
     * Most played artists, the row below the songs: the artists credited on the songs played
     * since :fromTimeStamp, by plays and then by time played. Unlike the reads above it is a Room
     * flow over the play events, the table the songs and albums lists beside it read, dated the
     * same way, by the wall clock stored as if it were UTC.
     *
     * It used to add up the monthly play counts from the first of the month the period began in,
     * so 1 week meant the month so far, and the whole of the month before as well once the week
     * reached back into it; every other period ran up to a month longer than it said. An artist
     * with no play in the period sorted last rather than dropping out, so a quiet week filled the
     * row with artists nobody had played. Every play counts now, as it does for the songs above.
     * Only songs saved to the library used to, which on a phone that plays mostly from search and
     * radio is a few hundred songs out of tens of thousands. For the same reason the count under
     * each artist is of their songs played in the period, and of those downloaded: the library's
     * count, which other artist rows show, would put 0 songs under most of the artists listed.
     *
     * Plays are added up per song first and then shared out to each song's artists, and the CROSS
     * JOINs keep the events as the outer loop: left to choose, SQLite walked every artist credit
     * in the database and looked each one up in the events, tens of thousands of lookups to find
     * a week's few hundred plays. The + in the GROUP BY reads the events in table order rather than
     * through the songId index, as in [SONGS_BEFORE].
     */
    const val MOST_PLAYED_ARTISTS = """
        SELECT artist.*, played.songCount AS songCount, played.downloadCount AS downloadCount
        FROM (
            SELECT sam.artistId,
                SUM(e.plays) AS plays,
                SUM(e.playTime) AS playTime,
                COUNT(*) AS songCount,
                SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
            FROM (
                SELECT songId, COUNT(*) AS plays, SUM(playTime) AS playTime
                FROM event
                WHERE timestamp > :fromTimeStamp
                GROUP BY +songId
            ) AS e
                CROSS JOIN song s ON s.id = e.songId
                CROSS JOIN song_artist_map sam ON sam.songId = e.songId
            -- Local artists have no YouTube page for the row to open, so they are dropped here,
            -- before the LIMIT below picks the top artists: filtering them out afterwards could
            -- turn a full row into a short or empty one while more played YouTube artists sit
            -- just outside it. GLOB rather than LIKE matches ArtistEntity.isYouTubeArtist
            -- exactly: it is case-sensitive and does not treat _ as a wildcard.
            WHERE sam.artistId GLOB 'UC*' OR sam.artistId GLOB 'FEmusic_library_privately_owned_artist*'
            GROUP BY sam.artistId
            ORDER BY plays DESC, playTime DESC, sam.artistId
            LIMIT :limit
        ) AS played
            JOIN artist ON artist.id = played.artistId
        ORDER BY played.plays DESC, played.playTime DESC, artist.id
    """
}
