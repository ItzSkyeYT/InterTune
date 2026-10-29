/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/** Local media queries kept as constants so the DAO and LocalSqlTest run the same text. */
object LocalSql {

    /**
     * Local songs that share a file with another local song, grouped by path. The scan keeps the
     * one played most and deletes the rest, so a YouTube song must never be among them: a download
     * saved into a scan folder has the same path as the local song the scan makes of it, and the
     * sweep could delete the YouTube row instead.
     */
    const val DUPLICATED_LOCAL_SONGS = """
        SELECT * FROM song
        WHERE isLocal = 1 AND localPath IN (
            SELECT localPath
            FROM song
            WHERE isLocal = 1
            GROUP BY localPath
            HAVING COUNT(*) > 1
        )
        ORDER BY localPath
    """

    /**
     * Local songs in the library with no track number or no disc number, a page at a time in id
     * order, for TrackTagReread, with the numbers stored for them. 0 is no number: older MediaStore
     * scans stored it for a missing tag.
     *
     * The disc counts too: before 0.12.5 a disc tag written "1/2" was read as no number, like the
     * track tag "4/12", and the songs whose track tag was a plain "4" kept their number but lost
     * their disc. The pass in 0.12.5 looked only for songs with no track number and passed over
     * them, so the two discs of an album still came out mixed, track 1, track 1, track 2...
     */
    const val SONGS_TO_REREAD = """
        SELECT id, localPath, trackNumber, discNumber FROM song
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localPath IS NOT NULL
            AND (trackNumber IS NULL OR trackNumber <= 0 OR discNumber IS NULL OR discNumber <= 0)
            AND id > :afterId
        ORDER BY id
        LIMIT :limit
    """

    /** Track and disc read again from the file. A number the file does not give keeps what is stored. */
    const val SET_TRACK_TAGS = """
        UPDATE song SET trackNumber = COALESCE(:track, trackNumber), discNumber = COALESCE(:disc, discNumber)
        WHERE id = :id
    """
}

/**
 * A local song's id and file, and the numbers stored for it, as SONGS_TO_REREAD gives them. 0 or
 * null is no number.
 */
data class LocalSongFile(
    val id: String,
    val localPath: String,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
)
