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

    /**
     * Every source's graded cards by outcome, with how many were played: a grade of one half or
     * more. Only cards in a slot. A pool pick (slot -1) is a song from the spare pool behind the
     * row that was played; it is stored as a played card so the weights learn from it, but it was
     * never on screen, so counting it would add a play nobody made from a card.
     */
    const val GRADED_BY_TEAM = """
        SELECT team AS team, outcome AS outcome, COUNT(*) AS n, SUM(CASE WHEN y >= 0.5 THEN 1 ELSE 0 END) AS wins
        FROM impression WHERE gradedAt IS NOT NULL AND slot >= 0 GROUP BY team, outcome
    """

    /**
     * The engine's own Quick picks cards, graded played, played elsewhere or ignored, seen and
     * played in two back to back windows: from :from to :mid, and from :mid to :to. How it's
     * doing compares the two to say whether the share played is going up. The same cards and
     * the same played as [GRADED_BY_TEAM].
     */
    const val CARD_TREND = """
        SELECT
            COUNT(CASE WHEN visibleAt >= :mid THEN 1 END) AS recentSeen,
            COUNT(CASE WHEN visibleAt >= :mid AND y >= 0.5 THEN 1 END) AS recentPlayed,
            COUNT(CASE WHEN visibleAt < :mid THEN 1 END) AS earlierSeen,
            COUNT(CASE WHEN visibleAt < :mid AND y >= 0.5 THEN 1 END) AS earlierPlayed
        FROM impression
        WHERE team = 1 AND slot >= 0 AND gradedAt IS NOT NULL AND outcome IN (1, 2, 3)
          AND visibleAt >= :from AND visibleAt < :to
    """

    /*
     * Forget the last session, and forget today's listening: the listens stop teaching. Only rows
     * that still teach are changed, so the count Room hands back is what this tap forgot. Without
     * that, a second tap marked the same rows again and said it had forgotten them all over again.
     * The two counts are what Your data asks about before it forgets, on the same rows.
     */
    const val FORGET_SESSION = "UPDATE listen SET learn = 0 WHERE sessionId = :sessionId AND learn = 1"
    const val FORGET_BETWEEN = "UPDATE listen SET learn = 0 WHERE startedAt >= :from AND startedAt < :to AND learn = 1"
    const val FORGETTABLE_IN_SESSION = "SELECT COUNT(*) FROM listen WHERE sessionId = :sessionId AND learn = 1"
    const val FORGETTABLE_BETWEEN = "SELECT COUNT(*) FROM listen WHERE startedAt >= :from AND startedAt < :to AND learn = 1"
}
