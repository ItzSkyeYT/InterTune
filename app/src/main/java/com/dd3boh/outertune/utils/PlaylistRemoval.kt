/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.zionhuang.innertube.YouTube

/**
 * A row taken out of a playlist (PlaylistsDao.removeFromPlaylist), told to YouTube Music: only for
 * a playlist of the account's, and only a row YouTube knows by its set-video id. Remove from
 * playlist in the song menu sends it this way, and so does Saved twice. YouTube itself refuses the
 * write while nobody is signed in or sync is read only (App sets YouTube.accountWritesAllowed).
 */
suspend fun removeFromYouTubePlaylist(browseId: String?, map: PlaylistSongMap) {
    val playlistId = browseId ?: return
    val setVideoId = map.setVideoId ?: return
    YouTube.removeFromPlaylist(playlistId, map.songId, setVideoId)
}
