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
}
