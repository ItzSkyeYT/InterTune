/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.time.LocalDateTime

/**
 * A download is kept once, where downloads live: in the download cache, or as a file in the
 * person's download folder. The song cache is for songs streamed.
 *
 * Every download used to be written into the song cache as well: its read went through that cache,
 * and media3 writes all it reads into a cache given no writer of its own. Each download took twice
 * its space, for good with the song cache on Unlimited; with a size, a large batch pushed out every
 * song kept from streaming. Now:
 *
 * - a download reads the song cache without writing it (DownloadUtil's data source);
 * - a song the song cache held before its download, the download copied from it, leaves the song
 *   cache once its download holds it whole, and the copies made before this version go at the next
 *   start ([copiesToDrop]);
 * - a download in a folder is played under a key of its own (downloadedKey), so playing it neither
 *   reads nor writes the song cache.
 *
 * What the song cache's copy quietly did, and what does it now:
 * - a song moved into the folder after the queue was made played from it: the player asks the folder
 *   whenever the download cache misses (FolderMoveTest);
 * - as the app starts, before the folders are listed, a song with its file in a folder played from
 *   it when it was there: the player waits for the listing (DownloadUtil.folderFileOf);
 * - offline, the adaptive queue counted a folder download as playable through it: it asks the
 *   downloads themselves (DownloadUtil.isDownloaded).
 */
object KeptOnce {
    /**
     * Which of the song cache's [keys] are songs the downloads hold whole: landed, as [downloads]
     * says, and in the download cache whole ([wholeInApp]) or with a file in a download folder
     * ([inFolders]). A song on its way, or one the map counts as downloaded but whose bytes cannot
     * be found, keeps its copy.
     */
    fun copiesToDrop(
        keys: Collection<String>,
        downloads: Map<String, LocalDateTime>,
        inFolders: Set<String>,
        wholeInApp: (String) -> Boolean,
    ): List<String> = keys.filter { key -> hasLanded(downloads[key]) && (key in inFolders || wholeInApp(key)) }
}
