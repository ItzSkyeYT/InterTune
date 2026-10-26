/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * SQL the engine's loader runs, and the counts How it's doing and Your data show, kept here so the
 * JVM tests can run the same text over the exported schema.
 */
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
     * [SONGS], cut down to the songs the adaptive queue can need to know about: those the listen
     * log names, the liked ones and the songs an exclusion names (to match a ban's other uploads
     * by title and artist). EngineLoader.loadHeard reads this in the service, where reading every
     * song, a few tens of thousands of rows in a long-used library, on each pass would be waste.
     * The listen.songId index serves the first subquery.
     */
    const val SONGS_HEARD_OR_LIKED = """
        SELECT s.id, s.title, s.liked, s.likedDate, s.inLibrary, s.isLocal, s.localPath, m.artistId AS artistId, a.name AS artistName
        FROM song s
        LEFT JOIN song_artist_map m ON m.songId = s.id
            AND m.position = (SELECT MIN(m2.position) FROM song_artist_map m2 WHERE m2.songId = s.id)
        LEFT JOIN artist a ON a.id = m.artistId
        WHERE s.liked = 1
            OR s.id IN (SELECT songId FROM listen)
            OR s.id IN (SELECT targetId FROM recommendation_exclusion WHERE kind = 1)
        GROUP BY s.id
    """

    /**
     * The impressions the Last.fm share is learned from: engine and Discover cards in a slot, in
     * the related or explore lane, that one source alone proposed from a song both have a list
     * for, pending or graded as played, played elsewhere or ignored. A card waiting on a resume of
     * its failed play (outcome 8, Outcome.WAITING) is not among them: it decides nothing until it
     * is graded. SourceMix.share applies the same filter again, so the query can be widened
     * without the share changing meaning.
     */
    const val SOURCE_EVIDENCE = """
        SELECT songId, team, lane, sources, visibleAt, outcome, y FROM impression
        WHERE team IN (1, 4) AND slot >= 0 AND lane IN (1, 4) AND (sources & 4) != 0 AND (sources & 3) IN (1, 2)
          AND outcome IN (0, 1, 2, 3) AND visibleAt >= :since
    """

    /**
     * Marks a card [Outcome.WAITING], leaving it pending: no grade, so the next run reads it again.
     * A card already waiting is not matched, so marking it again changes no row and tells no
     * observer of the table that anything changed.
     */
    const val MARK_WAITING = """
        UPDATE impression SET outcome = 8 WHERE id = :id AND gradedAt IS NULL AND outcome != 8
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
     *
     * Only cards of rows built under :lean, the choice under Quick picks leans toward as a Lean
     * code, are compared. A choice changes what kind of song fills most of the row, and some kinds
     * are played less however well they are picked, so a fortnight under one choice against a
     * fortnight under another would read as the engine doing better or worse when only the
     * question changed. It is the choice as stored on the build, so an evening on a chip does not
     * take its cards out. recentAny and earlierAny count each window's cards under any choice,
     * so the page can tell too few cards from too few since the choice changed.
     */
    const val CARD_TREND = """
        SELECT
            COUNT(CASE WHEN i.visibleAt >= :mid AND b.lean = :lean THEN 1 END) AS recentSeen,
            COUNT(CASE WHEN i.visibleAt >= :mid AND b.lean = :lean AND i.y >= 0.5 THEN 1 END) AS recentPlayed,
            COUNT(CASE WHEN i.visibleAt < :mid AND b.lean = :lean THEN 1 END) AS earlierSeen,
            COUNT(CASE WHEN i.visibleAt < :mid AND b.lean = :lean AND i.y >= 0.5 THEN 1 END) AS earlierPlayed,
            COUNT(CASE WHEN i.visibleAt >= :mid THEN 1 END) AS recentAny,
            COUNT(CASE WHEN i.visibleAt < :mid THEN 1 END) AS earlierAny
        FROM impression i JOIN row_build b ON b.id = i.buildId
        WHERE i.team = 1 AND i.slot >= 0 AND i.gradedAt IS NOT NULL AND i.outcome IN (1, 2, 3)
          AND i.visibleAt >= :from AND i.visibleAt < :to
    """

    /**
     * Every source's cards that were on screen, for Cards you saw on How it's doing: judged, still
     * to be judged, and judged but left out (its play forgotten, or tapped and never heard), with
     * how many were tapped. Only cards in a slot, as in [GRADED_BY_TEAM], so judged here is the
     * same number as seen there and the page never gives two counts for one thing.
     */
    const val CARDS_SEEN = """
        SELECT team AS team,
            COUNT(CASE WHEN gradedAt IS NOT NULL AND outcome IN (1, 2, 3) THEN 1 END) AS judged,
            COUNT(CASE WHEN gradedAt IS NULL THEN 1 END) AS waiting,
            COUNT(CASE WHEN gradedAt IS NOT NULL AND outcome NOT IN (1, 2, 3) THEN 1 END) AS leftOut,
            COUNT(CASE WHEN tappedAt IS NOT NULL THEN 1 END) AS tapped
        FROM impression WHERE slot >= 0 GROUP BY team ORDER BY team
    """

    /**
     * Listens by how they ended, with a song that reached its end after less than [ENDED_EARLY_BELOW]
     * of it was heard counted apart, as Recent listens shows it. The share is written out here
     * because Room needs the text whole; DoingCountsSqlTest holds the two to the same line.
     */
    const val LISTENS_BY_END = """
        SELECT endReason AS code, (endReason = 1 AND ratio >= 0 AND ratio < 0.8) AS early, COUNT(*) AS n
        FROM listen GROUP BY code, early
    """

    /**
     * Where you started playing: how many times you started something from each place, not how
     * many songs played. The place is noted on the queue, so every song a queue plays carries it,
     * and counted by song, one tapped card that started a radio gave every song after it: "quick
     * picks 4" stood above "2 tapped" under Cards you saw. A start is a run, one play of one queue
     * (see MultiQueueObject.runId), so going back within it is not a second start. A listen from
     * before runs were kept has none, and counts when it was chosen rather than played on, as
     * every listen from the old play log was.
     */
    const val STARTS_BY_ORIGIN = """
        SELECT origin AS code,
            COUNT(DISTINCT CASE WHEN runId != 0 THEN runId END) + COUNT(CASE WHEN runId = 0 AND autoplayDepth = 0 THEN 1 END) AS n
        FROM listen GROUP BY origin
    """

    /** Everything applied so far, in the order it was applied: a rebuild replays exactly this. */
    const val APPLIED_EXAMPLES = "SELECT * FROM impression WHERE appliedAt IS NOT NULL AND features IS NOT NULL AND u > 0 ORDER BY appliedAt, id"

    /**
     * The cards among [APPLIED_EXAMPLES]: what Your data says it has learned from, and rebuilt from.
     * A pool pick is an example too, a song you played from the spare pool behind the row, but it
     * was never on screen, so it is not counted as a card, as nowhere on How it's doing counts it.
     */
    const val APPLIED_CARDS = "SELECT COUNT(*) FROM impression WHERE appliedAt IS NOT NULL AND features IS NOT NULL AND u > 0 AND slot >= 0"

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

    /**
     * A listen's columns in the engine's shape, [ListenRow], in its order. Every query that reads
     * one takes them from here, so a column added to the shape is added to them all.
     */
    const val LISTEN_COLUMNS = "songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, autoplayDepth, sessionId, tzOffsetMin, learn, runId, queueId, impressionId, contextChip, id, continuesListenId"

    /** Every listen, in the engine's shape: what a build, the grading and the scoring of builds read. */
    const val LISTENS = "SELECT $LISTEN_COLUMNS FROM listen"

    /** The listens started after :since, in the engine's shape: what Playing now counts before following the session. */
    const val LISTENS_SINCE = "SELECT $LISTEN_COLUMNS FROM listen WHERE startedAt > :since"

    /*
     * What the adaptive queue's taste reads of the log, a little at a time (ListenLog): every row
     * in id order the first time, and after that only the rows added since (ids only grow: the
     * table is AUTOINCREMENT) and the rows that were still open, in one statement, with
     * [LISTEN_MARK] to tell whether any of the others changed or went. ListenLogTest runs these
     * over the exported schema.
     */
    const val LISTENS_IN_ORDER = "SELECT $LISTEN_COLUMNS FROM listen ORDER BY id"
    const val LISTENS_NEW_OR_OPEN = "SELECT $LISTEN_COLUMNS FROM listen WHERE id > :after OR id IN (:open) ORDER BY id"

    /**
     * A summary of the rows with an id up to :upTo, those in :except left out, that changes when
     * any of them changes or is gone: their count and two sums over every column of
     * [LISTEN_COLUMNS] but the song, which no write changes, each reduced first so that no sum can
     * overflow, the second weighing each row by its id. ListenLog.markOf works out the very same
     * numbers from rows in memory, and the two are compared at each read.
     */
    const val LISTEN_MARK = """
        SELECT COUNT(*) AS count,
            COALESCE(SUM((id % 1000003) * 31 + (startedAt % 999961) + (endedAt % 1000033) * 3 + (playedMs % 999983) * 5
                + (durationMs % 999979) * 7 + endReason * 104729 + origin * 7919 + autoplayDepth * 7907
                + (sessionId % 99929) * 11 + (tzOffsetMin % 9973) * 13 + learn * 104723 + (runId % 99991) * 17
                + (queueId % 99989) * 19 + (COALESCE(impressionId, 0) % 99971) * 23 + contextChip * 104717
                + (COALESCE(continuesListenId, 0) % 99961) * 29), 0) AS sumA,
            COALESCE(SUM(((id % 65521) + 1) * ((startedAt % 65519) + (endedAt % 65497) * 2 + (playedMs % 65479) * 3
                + (durationMs % 65449) * 5 + endReason * 7 + origin * 11 + (autoplayDepth % 65437) * 13
                + (sessionId % 65423) * 17 + (tzOffsetMin % 65413) * 19 + learn * 23 + (runId % 65393) * 29
                + (queueId % 65381) * 31 + (COALESCE(impressionId, 0) % 65371) * 37 + contextChip * 41
                + (COALESCE(continuesListenId, 0) % 65357) * 43)), 0) AS sumB
        FROM listen WHERE id <= :upTo AND id NOT IN (:except)
    """

    /**
     * When the latest listen started, still-open ones included; null with none. A play that failed
     * is left out, as the engine's input leaves it out (EngineListens.forBuild), so the two can be
     * compared.
     */
    const val LATEST_LISTEN_START = "SELECT MAX(startedAt) FROM listen WHERE endReason != 5"

    /** The lean each build followed and what its lead lane weighs, for grading the build's impressions. */
    const val BUILD_LEANS = "SELECT id, leanApplied, leadWeight FROM row_build WHERE id IN (:ids)"
}
