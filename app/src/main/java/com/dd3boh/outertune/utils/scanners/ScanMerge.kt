/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.db.entities.SongEntity

/**
 * The decisions a local media scan makes about rows the library already has, with no Android in
 * them, so they are tested.
 */
object ScanMerge {

    /**
     * What a scan writes for a song the library already has: the file's metadata from the scan,
     * and everything the listener did with the song from the stored row.
     *
     * The scanned entity knows only the file. Its liked is false, its likedDate null, its
     * inLibrary the moment of the scan and its dateDownload null. It used to be written over the
     * stored row whole, so a full rescan un-liked every local song and made each one's date added
     * today. Local likes are not kept anywhere else, so they were gone for good. The same write
     * brought back a song that had been disabled, and the like went with it there too.
     *
     * inLibrary falls back to the scan's time only for a disabled song, whose date added was
     * cleared when it was disabled.
     */
    fun intoExisting(scanned: SongEntity, existing: SongEntity): SongEntity = scanned.copy(
        id = existing.id,
        liked = existing.liked,
        likedDate = existing.likedDate,
        inLibrary = existing.inLibrary ?: scanned.inLibrary,
        dateDownload = existing.dateDownload,
    )

    /**
     * Whether a song missing from a scan has to stay as it is, because it sits under a scan folder
     * that could not be listed.
     *
     * A folder on an SD card that is not mounted, or one whose access grant is gone (a backup
     * restored on a new phone keeps the folder but not the grant), lists as empty, and every song
     * in it then looked deleted. Nothing was looked at there, so nothing there is disabled. A null
     * entry is a folder whose path is not known, which covers every song.
     */
    fun coveredByUnlistedRoot(localPath: String, unlistedRoots: List<String?>): Boolean =
        unlistedRoots.any { root -> root == null || localPath.startsWith(root.trimEnd('/') + "/") }
}
