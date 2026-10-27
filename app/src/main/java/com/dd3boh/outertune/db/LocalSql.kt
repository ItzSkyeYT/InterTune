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
     * Local songs in the library with no track number, a page at a time in id order, for
     * TrackTagReread. 0 is no number: older MediaStore scans stored it for a missing tag.
     */
    const val SONGS_WITHOUT_TRACK_NUMBER = """
        SELECT id, localPath FROM song
        WHERE isLocal = 1 AND inLibrary IS NOT NULL AND localPath IS NOT NULL
            AND (trackNumber IS NULL OR trackNumber <= 0)
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

/** A local song's id and file, as SONGS_WITHOUT_TRACK_NUMBER gives them. */
data class LocalSongFile(val id: String, val localPath: String)
