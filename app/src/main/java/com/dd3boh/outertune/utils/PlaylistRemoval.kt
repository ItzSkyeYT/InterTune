/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap

/**
 * A row taken out of a playlist (PlaylistsDao.removeFromPlaylist), told to YouTube Music: only for a
 * playlist of the account's, and kept for the account until it has it (PendingChanges). A row whose
 * entry YouTube has not named yet, a song added from the phone, is found there when it is sent: it
 * used to be skipped, and the next sync put the song back (scan of 4 Nov 2026, finding 6). Remove
 * from playlist in the song menu sends it this way, and so does Saved twice. Nothing is kept while
 * nobody is signed in or sync is read only.
 */
fun removeFromYouTubePlaylist(playlist: PlaylistEntity?, map: PlaylistSongMap) {
    PendingChanges.removed(playlist ?: return, listOf(map))
}
