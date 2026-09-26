/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.stats

/*
 * The rows the Stats page reads from the listen log, one class per query in StatsSql. Plain
 * classes rather than entities, so ListeningInsights and its tests never touch Room.
 */

/** One closed listen: [com.dd3boh.outertune.db.StatsSql.LISTENS]. */
data class StatsListen(
    val id: Long,
    val songId: String,
    /** True UTC. 0 on the odd row closed before its start was known; see [ListeningInsights]. */
    val startedAt: Long,
    val endedAt: Long,
    /** The phone's offset from UTC when it was written, which is how a local hour is recovered. */
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
)

/** A song as it stood before the period began: [com.dd3boh.outertune.db.StatsSql.SONGS_BEFORE]. */
data class StatsSongBefore(
    val songId: String,
    /** The start of its first listen of any kind. */
    val firstAt: Long,
    /** The start of its last counted listen, null when it was only ever skipped. */
    val lastPlayAt: Long?,
    /** Counted listens before the period. */
    val playsBefore: Int,
)

/** A song's first credited artist: [com.dd3boh.outertune.db.StatsSql.SONG_ARTISTS]. */
data class StatsSongArtist(val songId: String, val artistId: String)

/** All listening in a stretch of time: [com.dd3boh.outertune.db.StatsSql.TOTALS]. */
data class StatsTotals(
    val listens: Int,
    val playedMs: Long,
    /** The part of [playedMs] heard in counted listens, the only kind the backfilled history has. */
    val countedMs: Long,
    /** Listens backfilled from the old play log, which knows nothing about skips. */
    val legacyListens: Int,
)

/** Where the log begins: [com.dd3boh.outertune.db.StatsSql.BOUNDS]. Both null on an empty log. */
data class StatsBounds(
    /** The start of the first listen of any kind, backfilled or live. */
    val firstAt: Long?,
    /** The start of the first listen the live log wrote, which is where skips and sources begin. */
    val firstLiveAt: Long?,
)
