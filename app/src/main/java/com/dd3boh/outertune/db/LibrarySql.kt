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

    /**
     * The local album with this exact title, the first one made. The scanner gives each song it
     * reads a new album id, so this is how a file's song finds the album the others went into.
     * Only local albums: a YouTube album with the same title is a different album.
     */
    /**
     * An album's songs in the album's order, as its page shows them. With no ORDER BY they came in
     * whatever order SQLite found the rows, and writing an album's page stores the songs new to
     * the database before the ones it already had, so the album menu's Play next, Add to queue and
     * Add to playlist, Android Auto and I'm feeling lucky could all start part way through.
     */
    const val ALBUM_SONGS = """
        SELECT song.* FROM song
            JOIN song_album_map ON song.id = song_album_map.songId
        WHERE song_album_map.albumId = :albumId
        ORDER BY song_album_map.`index`
    """

    /*
     * Search library, below: what Library shows under each chip, found by name. All three used to
     * ask for a song in the library as well, so a saved album or artist with none, a synced
     * playlist (its songs are stored outside the library), an empty playlist or one filled from
     * search could never be found, though Library listed them.
     */

    /** The playlists Library lists: saved ones and the app's own. */
    const val SEARCH_PLAYLISTS = """
        SELECT
            p.*,
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE p.name LIKE '%' || :query || '%'
            AND (p.bookmarkedAt IS NOT NULL OR p.isLocal = 1)
        GROUP BY p.id
        LIMIT :previewSize
    """

    /** Albums with a song in the library or downloaded, and saved albums. */
    const val SEARCH_ALBUMS = """
        SELECT album.*, count(song.dateDownload) downloadCount
        FROM album
            LEFT JOIN song ON song.albumId = album.id
        WHERE album.title LIKE '%' || :query || '%'
            AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL OR album.bookmarkedAt IS NOT NULL)
        GROUP BY album.id
        LIMIT :previewSize
    """

    /**
     * Artists with a song in the library or downloaded, and saved artists. The count is of those
     * songs only, as before, which is why the condition sits in the join.
     */
    const val SEARCH_ARTISTS = """
        SELECT
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        WHERE artist.name LIKE '%' || :query || '%'
        GROUP BY artist.id
        HAVING songCount > 0 OR artist.bookmarkedAt IS NOT NULL
        ORDER BY artist.bookmarkedAt ASC
        LIMIT :previewSize
    """

    const val LOCAL_ALBUM_BY_TITLE = "SELECT * FROM album WHERE isLocal = 1 AND title = :title ORDER BY rowid LIMIT 1"
}
