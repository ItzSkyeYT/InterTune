/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Playlist queries kept as constants so the DAO and PlaylistSqlTest run the same text, the way
 * RecommendationSql is. Taking a song out of a playlist needs both: the row is found by its id,
 * then moved to the end by its position before it is deleted.
 */
object PlaylistSql {

    /**
     * Puts the song at one position at another and shifts the songs between them by one to close
     * up. It matches on the position alone, so a caller holding a position that is out of date
     * moves whichever song holds it now.
     */
    const val MOVE = """
        UPDATE playlist_song_map SET position =
            CASE
                WHEN position < :fromPosition THEN position + 1
                WHEN position > :fromPosition THEN position - 1
                ELSE :toPosition
            END
        WHERE playlistId = :playlistId AND position BETWEEN MIN(:fromPosition, :toPosition) AND MAX(:fromPosition, :toPosition)
    """

    /** One map row as it is now, found by its id, which unlike its position never changes. */
    const val MAP_BY_ID = "SELECT * FROM playlist_song_map WHERE id = :id"
}
