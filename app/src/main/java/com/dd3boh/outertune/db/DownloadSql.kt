/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Download queries kept as constants so the DAO and DownloadSqlTest run the same text, the way
 * PlaylistSql is.
 *
 * dateDownload means downloaded, and when: every list, count and filter of downloaded songs, in
 * songs, albums, artists, playlists and search, asks "dateDownload IS NOT NULL". A download scan
 * used to write two sentinels there as well, epoch 0 for a failed or stopped download and epoch 1
 * for a queued one, and media3 keeps failed downloads in its index for good, so after any scan a
 * failed download was listed as downloaded everywhere. The scan now writes only finished
 * downloads, as the download listener always has, and this clears what earlier scans stored.
 */
object DownloadSql {

    /** Run before the map of downloads is read from the database. See DownloadUtil.rescanDownloads. */
    const val CLEAR_SENTINELS = "UPDATE song SET dateDownload = NULL WHERE dateDownload IN (0, 1)"

    /** The Downloaded list as the media browser (Android Auto) gets it. */
    const val DOWNLOADED_BY_DATE = "SELECT * FROM song WHERE isLocal = 0 AND dateDownload IS NOT NULL ORDER BY dateDownload"

    /**
     * The songs that show the cover at :url, downloaded or not: an album's songs share one. Remove
     * download keeps a kept cover while one of them is still downloaded (DownloadUtil.removeDownload).
     */
    const val SONGS_WITH_COVER = "SELECT id FROM song WHERE thumbnailUrl = :url"

    /**
     * Every liked song, local ones too. The liked catch up keeps what it remembers only for these
     * (DownloadUtil.downloadLikedSongs, LikedCatchUpMemory.keepOnly).
     */
    const val LIKED_SONG_IDS = "SELECT id FROM song WHERE liked"
}
