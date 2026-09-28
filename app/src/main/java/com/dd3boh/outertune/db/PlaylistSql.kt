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

    /** Whether a playlist row is still there, for a write queued before it could be deleted. */
    const val EXISTS = "SELECT EXISTS(SELECT 1 FROM playlist WHERE id = :playlistId)"

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

    /**
     * The playlists a song sits in, for offering to take it back out again. Local playlists and
     * synced playlists the app can actually edit only, since a followed playlist would offer a
     * remove that YouTube refused and the next sync undid. PlayerMenu also leaves out synced ones
     * while sync is Read only.
     *
     * The song match is an EXISTS rather than a JOIN so a song sitting in a playlist's map more
     * than once (Add anyway in the duplicates prompt puts one there) cannot multiply songCount,
     * which is counted from its own unfiltered join over every row of the playlist.
     */
    const val CONTAINING_SONG = """
        SELECT
            p.*,
            COUNT(psm2.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm2 ON p.id = psm2.playlistId
            LEFT JOIN song s ON psm2.songId = s.id
        WHERE (p.isLocal = 1 OR p.isEditable = 1)
            AND EXISTS (SELECT 1 FROM playlist_song_map WHERE playlistId = p.id AND songId = :songId)
        GROUP BY p.id
        ORDER BY p.name
    """
}
