/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every playback stop, whatever fraction of the song was heard.
 *
 * [Event] is the counted play, written only past the listener's threshold (30% by default), and
 * it stays that way because History, play counts and Last.fm all read it as "a play". This table
 * is the complete record underneath: the twenty second skip, the song abandoned at the chorus,
 * the one played to the end, each with how it ended and where it was started from. It is what the
 * recommendation engine learns from, and nothing else reads it.
 *
 * Times are true UTC epoch milliseconds, unlike [Event.timestamp], which is the wall clock stored
 * as if it were UTC. [tzOffsetMin] is kept so time-of-day can still be recovered.
 */
@Immutable
@Entity(
    tableName = "listen",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("songId"), Index("endedAt"), Index("sessionId"), Index("queueId")]
)
data class Listen(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val startedAt: Long,
    val endedAt: Long,
    val tzOffsetMin: Int,
    val playedMs: Long,
    /** -1 when the length was never learned. */
    val durationMs: Long,
    /** playedMs over durationMs, or -1 when the length is unknown. Not clamped: a repeat exceeds 1. */
    val ratio: Float,
    /** An [com.dd3boh.outertune.constants.EndReason] code. */
    val endReason: Int,
    /** A [com.dd3boh.outertune.constants.PlayOrigin] code. */
    val origin: Int,
    /** Position in the row it was started from, or -1. */
    val originSlot: Int,
    val queueId: Long,
    /** How many songs autoplayed in a row before this one, in the same queue. 0 when chosen. */
    val autoplayDepth: Int,
    /** startedAt of the first listen in the session; a new session begins after 30 minutes of silence. */
    val sessionId: Long,
    /** True when this stop also wrote an [Event], that is, it passed the play threshold. */
    val counted: Boolean,
    /** False when the listener has said this queue should not teach the engine. */
    @ColumnInfo(defaultValue = "1") val learn: Boolean = true,
)
