/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Relation

/**
 * One row of History, as [com.dd3boh.outertune.db.HistorySql.PLAYS] reads it: a play from the
 * listen log, a paused and resumed one counted once, or an event the backfill has not yet turned
 * into a listen.
 */
@Immutable
data class HistoryPlay(
    /** The play's first piece in the listen log; null for an event with no listen. */
    val listenId: Long?,
    /** The event, on a row that is one; null for a listen. */
    val eventId: Long?,
    val songId: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val tzOffsetMin: Int?,
    /** event.timestamp as stored: the wall clock at the end of the play, read as if it were UTC. */
    val timestamp: Long?,
    /** Heard, over every piece of the play. */
    val playedMs: Long,
    /** Whether any piece passed Minimum playback duration. */
    val counted: Boolean,
    /** Newest first in SQL; close enough for an event, and History orders by the exact instant itself. */
    val sortAt: Long,
)

@Immutable
data class HistoryPlayWithSong(
    @Embedded
    val play: HistoryPlay,
    @Relation(
        entity = SongEntity::class,
        parentColumn = "songId",
        entityColumn = "id"
    )
    val song: Song,
)

/** One piece of a play in the listen log, as Remove from history needs it. */
data class HistoryPiece(
    val id: Long,
    val songId: String,
    val sourceEventId: Long?,
)
