/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.db.MusicDatabase

/**
 * Something the listener did about a song from the interface: added it to a playlist, downloaded
 * it, shared it, opened its lyrics or its artist, took it out of the queue. Each lands in
 * `listen_signal`, against the song's open listen when it is the one playing, under the same
 * privacy switch as the listen log itself.
 */
object ActivityLog {
    fun note(context: Context, database: MusicDatabase, songId: String, kind: Int, value: Float = 0f) {
        if (context.dataStore.get(PauseListenHistoryKey, false)) return
        database.query {
            runCatching { noteSignal(songId, kind, value) }
                .onFailure { Log.w("ActivityLog", "Could not record activity", it) }
        }
    }

    /**
     * Takes back what [note] wrote of [kind] about [songId] at [since] or later, the newest only: the
     * queue sheet's Undo of a removal, a tap on the message the removal showed, so the note has long been
     * written by then.
     */
    fun takeBack(database: MusicDatabase, songId: String, kind: Int, since: Long) {
        database.query {
            runCatching { dropLastSignal(songId, kind, since) }
                .onFailure { Log.w("ActivityLog", "Could not take back activity", it) }
        }
    }
}
