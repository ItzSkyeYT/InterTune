/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Library queries kept as constants so the DAOs and LibrarySqlTest run the same text, the way
 * RecommendationSql is.
 */
object LibrarySql {

    /**
     * Liked songs by play count, least played first.
     *
     * liked is a non-null boolean, so the WHERE liked IS NOT NULL this had matched every row of
     * the song table: tens of thousands on a phone that has been used for a while, counted in the
     * header and all queued by a tap. The other liked queries all read WHERE liked.
     */
    const val LIKED_SONGS_BY_PLAY_COUNT = """
        SELECT song.*, (SELECT SUM(playCount.count)
            FROM playCount
            WHERE playCount.song = song.id) AS pc
        FROM song
        WHERE liked
        ORDER BY pc ASC
    """
}
