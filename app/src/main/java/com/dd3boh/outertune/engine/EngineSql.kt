/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/** SQL the engine's loader runs, kept here so the JVM trial can run the same text over a real copy. */
object EngineSql {
    /**
     * Every song with its primary artist: the mapping at the lowest position, joined once. The
     * earlier form ran two correlated subqueries per song and took most of a two second build over
     * a library of thirty thousand songs.
     */
    const val SONGS = """
        SELECT s.id, s.title, s.liked, s.likedDate, s.inLibrary, s.isLocal, s.localPath, m.artistId AS artistId, a.name AS artistName
        FROM song s
        LEFT JOIN song_artist_map m ON m.songId = s.id
            AND m.position = (SELECT MIN(m2.position) FROM song_artist_map m2 WHERE m2.songId = s.id)
        LEFT JOIN artist a ON a.id = m.artistId
        GROUP BY s.id
    """

    /**
     * The impressions the Last.fm share is learned from: engine and Discover cards in a slot, in
     * the related or explore lane, that one source alone proposed from a song both have a list
     * for, pending or graded as played, played elsewhere or ignored. SourceMix.share applies the
     * same filter again, so the query can be widened without the share changing meaning.
     */
    const val SOURCE_EVIDENCE = """
        SELECT songId, team, lane, sources, visibleAt, outcome, y FROM impression
        WHERE team IN (1, 4) AND slot >= 0 AND lane IN (1, 4) AND (sources & 4) != 0 AND (sources & 3) IN (1, 2)
          AND outcome IN (0, 1, 2, 3) AND visibleAt >= :since
    """
}
