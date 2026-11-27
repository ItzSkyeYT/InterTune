/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * The queries that put a song's credit, stored as one artist, back into its artists (ArtistCredit,
 * DatabaseDao.splitStoredCredit, ArtistCreditRepair), kept as constants so the DAO and CreditSqlTest run the same
 * text.
 */
object CreditSql {

    /** A song's artists in their credited order. */
    const val ARTISTS_OF_SONG = """
        SELECT artist.* FROM song_artist_map JOIN artist ON artist.id = song_artist_map.artistId
        WHERE song_artist_map.songId = :songId
        ORDER BY song_artist_map.position
    """

    /**
     * The songs from YouTube kept here (liked, in the library, downloaded or in a playlist) whose only artist has an
     * id made up here (ArtistEntity.generateArtistId, matched whole) and is not a file's: the songs whose credit
     * may have been stored as one name. A page at a time in id order, after :afterId. isLocal <> 1 rather than = 0:
     * on Android before 11 a row older than the column reads the text 'false' from its DEFAULT false.
     */
    const val CREDITS_TO_ASK = """
        SELECT s.id AS songId, a.name AS name FROM song s
        JOIN song_artist_map m ON m.songId = s.id
        JOIN artist a ON a.id = m.artistId
        WHERE s.id > :afterId AND s.isLocal <> 1 AND a.isLocal <> 1
            AND a.id GLOB 'LA[A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z]'
            AND (s.liked = 1 OR s.inLibrary IS NOT NULL OR s.dateDownload IS NOT NULL
                OR EXISTS (SELECT 1 FROM playlist_song_map p WHERE p.songId = s.id))
            AND (SELECT count(*) FROM song_artist_map x WHERE x.songId = s.id) = 1
        ORDER BY s.id
        LIMIT :limit
    """

    /** An artist row no song and no album names any more, and nobody saved, goes. */
    const val DELETE_IF_UNUSED = """
        DELETE FROM artist WHERE id = :artistId AND bookmarkedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM song_artist_map WHERE artistId = :artistId)
            AND NOT EXISTS (SELECT 1 FROM album_artist_map WHERE artistId = :artistId)
    """
}

/** A song of CreditSql.CREDITS_TO_ASK and the one name its artist has. */
data class StoredCredit(val songId: String, val name: String)
