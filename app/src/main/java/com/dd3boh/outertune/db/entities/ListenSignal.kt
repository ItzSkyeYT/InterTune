/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Something the listener did during or about a song, beyond playing it: seeking back to hear a
 * part again, turning it up, putting it on repeat, adding it to a playlist, taking it out of the
 * queue. Each is a stronger statement than a play, and until now none of them left a trace.
 */
@Immutable
@Entity(tableName = "listen_signal", indices = [Index("listenId"), Index("songId"), Index("at")])
data class ListenSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The open listen it happened during, when it happened during one. */
    val listenId: Long?,
    val songId: String,
    /** A [com.dd3boh.outertune.constants.SignalKind] code. */
    val kind: Int,
    /** Position in the song, where that means something (a seek's target); else -1. */
    val positionMs: Long = -1,
    /** Kind-specific: seconds jumped for a seek, steps for a volume change. */
    val value: Float = 0f,
    val at: Long,
)
