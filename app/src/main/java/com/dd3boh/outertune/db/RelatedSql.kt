/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Every query over related_song_map that has to know which source an edge came from, kept as
 * constants so the DAOs and RelatedSqlTest run the same text, the way RecommendationSql is.
 *
 * The table has carried a source column since the listen log arrived, 0 for YouTube's related
 * shelf and 1 for Last.fm's similar tracks, but until the listener could choose Last.fm nothing
 * wrote a 1, so none of these filtered on it. Once both kinds live in one table, a query that
 * ignores the column quietly answers for both. The YouTube bookkeeping is the dangerous half: a
 * song with only Last.fm edges would read as "YouTube's list already fetched" and never get one,
 * a YouTube refresh would delete Last.fm's edges along with its own, and the duplicate sweep would
 * drop exactly the edges both sources agree on. So each query here says which source it means.
 *
 * Quick picks, the classic row, already reads source 0 only (RecommendationSql.QUICK_PICKS), and
 * keeps doing so: the choice between the two applies to the engine's row, which is where it was
 * measured.
 */
object RelatedSql {

    /** Whether YouTube's related list for this song has been written. */
    const val HAS_YOUTUBE_RELATED =
        "SELECT COUNT(1) FROM related_song_map WHERE songId = :songId AND source = 0 LIMIT 1"

    /** When YouTube's list was fetched (its oldest edge), null with none, 0 for a legacy list. */
    const val YOUTUBE_FETCHED_AT =
        "SELECT MIN(fetchedAt) FROM related_song_map WHERE songId = :songId AND source = 0"

    /** The same for Last.fm's similar tracks. */
    const val LASTFM_FETCHED_AT =
        "SELECT MIN(fetchedAt) FROM related_song_map WHERE songId = :songId AND source = 1"

    /** And a Last.fm refresh replaces Last.fm's and leaves YouTube's alone. */
    const val DELETE_LASTFM_SIMILAR =
        "DELETE FROM related_song_map WHERE songId = :songId AND source = 1"

    /**
     * One Last.fm edge, written only when both songs exist. Both ends are foreign keys, and an
     * insert that failed on one inside the transaction would not just lose that row: the failed
     * inner transaction marks the whole one failed, and the delete and every other insert roll
     * back with it. Checking first means nothing ever fails.
     */
    const val INSERT_LASTFM_EDGE = """
        INSERT INTO related_song_map(songId, relatedSongId, fetchedAt, source)
        SELECT :songId, :relatedSongId, :fetchedAt, 1
        WHERE EXISTS (SELECT 1 FROM song WHERE id = :songId)
          AND EXISTS (SELECT 1 FROM song WHERE id = :relatedSongId)
    """

    /** A YouTube refresh replaces YouTube's list and leaves Last.fm's alone. */
    const val DELETE_YOUTUBE_RELATED =
        "DELETE FROM related_song_map WHERE songId = :songId AND source = 0"

    /**
     * One edge per pair per source. Grouping by the pair alone would keep whichever source was
     * written first and delete the other, and the pairs both sources share are the ones they agree
     * on, which is the last thing to throw away.
     */
    const val DROP_DUPLICATE_EDGES = """
        DELETE FROM related_song_map WHERE id NOT IN
            (SELECT MIN(id) FROM related_song_map GROUP BY songId, relatedSongId, source)
    """

    /**
     * Songs a row is likely to be built around that have no Last.fm list yet: anything given half
     * a minute or more in the window, latest first, then liked songs, newest like first. What the
     * catch-up asks Last.fm about when it is switched on.
     */
    const val LASTFM_CATCH_UP = """
        SELECT s.id AS id, s.title AS title,
            (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId
             WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artist
        FROM song s
        LEFT JOIN (SELECT songId, MAX(startedAt) AS heardAt FROM listen
                   WHERE startedAt >= :since AND playedMs >= 30000 GROUP BY songId) h ON h.songId = s.id
        WHERE (h.songId IS NOT NULL OR s.liked)
          AND NOT EXISTS (SELECT 1 FROM related_song_map r WHERE r.songId = s.id AND r.source = 1)
        ORDER BY h.heardAt IS NULL, h.heardAt DESC, s.likedDate DESC
        LIMIT :limit
    """

    /**
     * The engine's graph: the chosen source's edges, and YouTube's for any seed the chosen source
     * has none for. With YouTube chosen that is simply YouTube's.
     *
     * Replayed over the 11 Sep history, five draws each: Last.fm alone found 59 new songs the
     * listener went on to play against YouTube's 45, but fewer of the songs they came back to (175
     * against 190). Falling back to YouTube for the seeds Last.fm does not know, which is most of
     * them, kept the 59 and raised the returns to 194, the best hit rate of the three. Mixing both
     * sources for every seed, measured earlier, found the fewest new songs of all. The fallback
     * also means switching to Last.fm changes nothing for the worse on the day: a seed has YouTube's
     * list until it has one of its own.
     */
    const val ENGINE_EDGES = """
        SELECT songId, relatedSongId FROM related_song_map r
        WHERE r.source = :source
           OR (r.source = 0 AND NOT EXISTS
                (SELECT 1 FROM related_song_map c WHERE c.songId = r.songId AND c.source = :source))
    """
}
