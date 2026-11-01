/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.db.entities.SongEntity

/**
 * Liked songs kept in sync with their downloads (issue #64): with "Liked songs can download
 * themselves" on and its switch "Remove the download with the like" on, taking back a like removes the
 * download the setting made for that song, and nothing else. A song downloaded by hand stays.
 *
 * The setting's own downloads are the ones it sent to the downloader itself, from a like or from
 * Download all liked songs now, remembered by id (LikedAutoDownloadedKey). A song whose download is
 * removed by any means is forgotten, so that downloading it again by hand later is never taken for
 * one of the setting's.
 */
object LikedDownloads {
    /** The ids whose download an unlike takes away: only songs no longer liked that the setting downloaded. */
    fun toRemove(syncOn: Boolean, made: Set<String>, songs: List<SongEntity>): List<String> =
        if (!syncOn) emptyList() else songs.filter { !it.liked && it.id in made }.map { it.id }
}
