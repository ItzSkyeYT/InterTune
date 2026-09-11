/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * The engine's view of the world: plain rows, no Android and no Room, so the JVM tests run the
 * same code the phone runs. The loader on the Android side fills these from the database.
 */
data class ListenRow(
    val songId: String,
    val startedAt: Long,
    val endedAt: Long,
    val playedMs: Long,
    /** -1 when the length was never learned. */
    val durationMs: Long,
    /** A [com.dd3boh.outertune.constants.EndReason] code. */
    val endReason: Int,
    /** A [com.dd3boh.outertune.constants.PlayOrigin] code. */
    val origin: Int,
    val autoplayDepth: Int,
    val sessionId: Long,
    val tzOffsetMin: Int,
    val learn: Boolean = true,
    val runId: Long = 0,
    val queueId: Long = 0,
    /** The Quick picks impression this play came from, when it was a tap on a card. */
    val impressionId: Long? = null,
)

data class SongRow(
    val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String?,
    val liked: Boolean = false,
    /** The like's instant, already corrected for how Converters stores it; null when not liked. */
    val likedAt: Long? = null,
    val inLibrary: Boolean = false,
    val isLocal: Boolean = false,
    /** False for a local file that is gone, or anything that cannot play right now. */
    val playable: Boolean = true,
)

/** Seed to candidate, as YouTube (or later a second source) lists it. */
data class Edge(val seedId: String, val songId: String)

/** YouTube's word that two ids are performances of one song. */
data class VersionLink(val songId: String, val versionId: String)

/** A card that was seen and not played, for the impression penalty. */
data class SeenCard(val songId: String, val seenAt: Long)

/** An exclusion in force: kind 1 song, 2 artist. */
data class ExclusionRow(val kind: Int, val targetId: String)

/** The seeds of a row built recently, so the next build drifts away from them. */
data class PastSeeds(val builtAt: Long, val seedIds: List<String>)

data class EngineInput(
    val now: Long,
    val songs: Map<String, SongRow>,
    val listens: List<ListenRow>,
    val edges: List<Edge>,
    val versionLinks: List<VersionLink> = emptyList(),
    val seen: List<SeenCard> = emptyList(),
    val exclusions: List<ExclusionRow> = emptyList(),
    val pastSeeds: List<PastSeeds> = emptyList(),
    /** The day-part bucket the row is built for, 0 to 7; see [dayPartBucket]. */
    val bucket: Int = 0,
    /** The device's offset from UTC in minutes now, for local midnight. */
    val tzOffsetMin: Int = 0,
    /** Songs never to offer as candidates, whatever the lanes say (the Tidy pass handles the rest). */
    val banned: Set<String> = emptySet(),
    /** Songs the listener turned down as seeds ("Not this one"); they may still be cards. */
    val notSeeds: Set<String> = emptySet(),
)

/** How a card got into the row. */
enum class Lane { RELATED, ARTIST, REDISCOVER, EXPLORE }

data class Card(
    val songId: String,
    val lane: Lane,
    /** The ranking score, `b + Σ w x`, without position. */
    val z: Double,
    /** The predicted chance of a play in its slot. */
    val p: Double,
    val features: DoubleArray,
    /** Feature names of the two largest positive terms, or a placement reason. */
    val reasons: List<String>,
    /** The seed that referred it, when the related lane placed it. */
    val seedId: String? = null,
    /** True when temperature sampling rather than rank placed it. */
    val sampled: Boolean = false,
)

data class BuiltRow(
    val cards: List<Card>,
    val seeds: List<String>,
    /** Survivors beyond the row, best first, for replacements between builds. */
    val pool: List<Card>,
    val quotas: Map<Lane, Int>,
)

/** Weekday or weekend, times night 0 to 5, morning 6 to 11, afternoon 12 to 17, evening 18 to 23. */
fun dayPartBucket(startedAt: Long, tzOffsetMin: Int): Int {
    val localMs = startedAt + tzOffsetMin * 60_000L
    val days = Math.floorDiv(localMs, 86_400_000L)
    val hour = (Math.floorMod(localMs, 86_400_000L) / 3_600_000L).toInt()
    // 1970-01-01 was a Thursday: day 0 -> Thursday, so (days + 3) mod 7 gives Monday = 0.
    val weekday = Math.floorMod(days + 3, 7L).toInt()
    val weekend = weekday >= 5
    return (if (weekend) 4 else 0) + hour / 6
}
