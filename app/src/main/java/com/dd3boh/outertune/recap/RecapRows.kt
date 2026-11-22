/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

/*
 * The rows the Recap reads, one class per query in RecapSql, and what comes from outside the
 * database. Plain classes, so that Recaps and its tests never touch Room.
 */

/** One closed listen: [com.dd3boh.outertune.db.RecapSql.LISTENS]. The Stats page's row, with the chip it was started under. */
data class RecapListen(
    val id: Long,
    val songId: String,
    /** True UTC. 0 on the odd row closed before its start was known: dated back from its end. */
    val startedAt: Long,
    val endedAt: Long,
    /** The phone's offset from UTC when it was written, which is how its local day and hour are found. */
    val tzOffsetMin: Int,
    val playedMs: Long,
    /** An [com.dd3boh.outertune.constants.EndReason] code. */
    val endReason: Int,
    /** A [com.dd3boh.outertune.constants.PlayOrigin] code. */
    val origin: Int,
    val sessionId: Long,
    /** Passed the play threshold. */
    val counted: Boolean,
    /** The earlier fragment of the same play, when the listener paused and came back to it. */
    val continuesListenId: Long?,
    /** A [com.dd3boh.outertune.engine.ContextChip]: 0, Auto, unless Quick picks had another chosen. */
    val contextChip: Int = 0,
)

/** One artist credited on a song, at its place: [com.dd3boh.outertune.db.RecapSql.CREDITS]. */
data class RecapCredit(val songId: String, val artistId: String, val position: Int)

/** How much listening a stretch of time holds: [com.dd3boh.outertune.db.RecapSql.AMOUNT]. */
data class RecapAmount(val plays: Int, val playedMs: Long)

/** A song recognition named, from its history (RecognitionHistory), which lives outside the database. */
data class RecapHeard(
    /** When it was heard, true UTC. */
    val at: Long,
    /** The YouTube song it was matched to, when one was. */
    val videoId: String?,
    val title: String,
    val artist: String,
)

/**
 * A Listen together room this phone was in. Nothing keeps a log of rooms yet: rooms are on the
 * listen-together branch, which remembers nothing once a room ends. When it does, its rooms come
 * in here and the line on the Only on InterTune slide says how many and with how many people.
 */
data class RecapRoom(
    /** When the room began, true UTC. */
    val at: Long,
    /** Everyone else who was in it, by something that tells them apart from room to room. */
    val people: Set<String>,
)
