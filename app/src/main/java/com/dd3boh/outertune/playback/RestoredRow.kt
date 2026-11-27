/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

/**
 * The row of the song the saved queue came back to, as it was read with the queue, standing in for the
 * database's own answer until that comes (PlayerConnection.currentSong).
 *
 * After a start the player is empty and the song on screen is read from the saved queue, row and all.
 * The heart asked the database for the same row again only once that read was done, and on a phone
 * busy starting it stood empty meanwhile over a liked song (his report of 27 Nov 2026). Until play
 * loads the song ([clear]), the row leads every look at that song: the one read with the queue, then
 * whatever the database last said of it, so a like changed meanwhile is never shown as it was.
 */
internal class RestoredRow<T : Any>(private val idOf: (T) -> String) {
    @Volatile
    private var row: T? = null

    /** The row read with the saved queue, null when it read none or the player holds a song. */
    fun set(row: T?) {
        this.row = row
    }

    /** The player holds a song: its rows come from the database alone from now on. */
    fun clear() {
        row = null
    }

    /** [live], the database's rows of the song [id], led by the restored row while that is the song. */
    fun lead(id: String?, live: Flow<T?>): Flow<T?> {
        val kept = row?.takeIf { idOf(it) == id } ?: return live
        return live
            .onEach { answer -> if (answer != null && idOf(answer) == id && row != null) row = answer }
            .onStart { emit(row?.takeIf { idOf(it) == id } ?: kept) }
    }
}
