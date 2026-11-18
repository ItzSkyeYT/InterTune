/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * The recommendation queries, kept as constants so the DAO and RecommendationSqlTest run the same
 * text. Both were inherited broken, and both failures were invisible in use because a
 * recommendation row that is subtly wrong still looks like a recommendation row.
 */
object RecommendationSql {

    /**
     * The songs the listener chose not to play, each with its title and first artist as the library has them, so that
     * its other uploads are known by them (DontPlay): Don't play this song (reason 5) and Don't play set over Not this
     * song (6, ExclusionsViewModel.REASON_DONT_PLAY_BANNED). Not this song alone (a ban, 1) keeps to Home's rows.
     */
    const val DONT_PLAY_SONGS = "SELECT x.targetId AS id, COALESCE(song.title, x.label) AS title, " +
            "(SELECT artist.name FROM song_artist_map JOIN artist ON artist.id = song_artist_map.artistId " +
            "WHERE song_artist_map.songId = x.targetId ORDER BY song_artist_map.position LIMIT 1) AS artist " +
            "FROM recommendation_exclusion x LEFT JOIN song ON song.id = x.targetId " +
            "WHERE x.kind = 1 AND x.reason IN (5, 6)"

    /** Lifting Don't play this song from a song: a plain one goes, a row of any other reason stays as it is. */
    const val LIFT_DONT_PLAY = "DELETE FROM recommendation_exclusion WHERE kind = 1 AND reason = 5 AND targetId = :songId"

    /**
     * And one set over Not this song is that ban again: the song may play anywhere, and stays out of the recommendation
     * rows as the listener had asked before. Don't play used to write over the ban, and lifting it lost it.
     */
    const val LIFT_DONT_PLAY_BANNED = "UPDATE recommendation_exclusion SET reason = 1 WHERE kind = 1 AND reason = 6 AND targetId = :songId"

    /**
     * Songs related to your seed songs, ranked by how many of the seeds point at each one.
     *
     * The inherited version grouped every related row in the whole history before filtering, then
     * filtered on songId, which in a GROUP BY without an aggregate is a bare column: SQLite fills it
     * from an arbitrary row in each group. So referredCount counted referrals from every song ever
     * played rather than from the seeds, and a candidate related to several seeds was kept or thrown
     * away depending on which of them SQLite happened to pick. Reproduced with the verbatim query:
     * the same data gave two different rows depending only on the order the edges were inserted,
     * once dropping a song related to a current seed and once ranking it first on a count of four
     * when only one seed pointed at it.
     *
     * Now it filters to the seeds first and counts distinct seeds, so the ranking means what the
     * name says. DISTINCT because related_song_map can hold the same pair twice, which a plain COUNT
     * would read as two seeds agreeing.
     */
    const val QUICK_PICKS = """
        SELECT song.*
        FROM (SELECT relatedSongId, COUNT(DISTINCT songId) AS referredCount
              FROM related_song_map
              WHERE source = 0 AND songId IN (SELECT songId
                               FROM (SELECT songId
                                     FROM event
                                     ORDER BY ROWID DESC
                                     LIMIT 5)
                               UNION
                               SELECT songId
                               FROM (SELECT songId
                                     FROM event
                                     WHERE timestamp > :now - 86400000 * 7
                                     GROUP BY songId
                                     ORDER BY SUM(playTime) DESC
                                     LIMIT 5)
                               UNION
                               SELECT id
                               FROM (SELECT id
                                     FROM song
                                     WHERE liked
                                     ORDER BY likedDate DESC
                                     LIMIT 10))
              GROUP BY relatedSongId) map
                 JOIN song ON song.id = map.relatedSongId
        ORDER BY referredCount DESC
        LIMIT 100
    """

    /**
     * Songs you played a lot more than thirty days ago and have mostly or entirely stopped playing.
     *
     * The inherited version inner joined the old plays against songs played in the last thirty
     * days, so a song had to have been played recently to appear at all. A favourite you had stopped
     * playing entirely, the one case the row exists for, could never show up; only songs you had
     * merely cut back on could. A LEFT JOIN alone is not the fix either: every song ever heard once
     * would then qualify, down to a forty second skip two months ago. Requiring three counted plays
     * before the window is what makes it a favourite, the same floor Flow uses for its rediscover
     * shelf. Ordered most loved first so the LIMIT keeps the strongest hundred rather than an
     * arbitrary hundred; the Home screen shuffles within them.
     */
    const val FORGOTTEN_FAVORITES = """
        SELECT song.*
        FROM (SELECT songId AS eid, SUM(playTime) AS oldPlayTime
              FROM event
              WHERE timestamp < (:now - 86400000 * 30)
              GROUP BY songId
              HAVING COUNT(*) >= 3) AS old
                 LEFT JOIN (SELECT songId, SUM(playTime) AS newPlayTime
                            FROM event
                            WHERE timestamp > (:now - 86400000 * 30)
                            GROUP BY songId) AS recent
                           ON recent.songId = old.eid
                 JOIN song ON song.id = old.eid
        WHERE 0.2 * old.oldPlayTime > COALESCE(recent.newPlayTime, 0)
        ORDER BY old.oldPlayTime DESC
        LIMIT 100
    """
}
