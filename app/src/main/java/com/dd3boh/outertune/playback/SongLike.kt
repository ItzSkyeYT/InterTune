/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity

/**
 * A song's like turned over, the way the player's heart does it: the row stored with the like given
 * or taken back, the like sent to YouTube by the row's own toggle, and a liked song downloaded when
 * the setting asks for that. The player's service goes through here, and so does the widget's heart
 * when no service is alive, so the two never do different things. Null when the song is not stored.
 * It reads and writes the database, so never on the main thread.
 */
fun MusicDatabase.turnLike(id: String, downloadUtil: DownloadUtil): SongEntity? {
    // toggleLike sends the like to YouTube itself. A likeSong here sent it again.
    val song = songRow(id)?.toggleLike() ?: return null
    update(song)
    downloadUtil.autoDownloadOnLike(song)
    return song
}
