/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * Every tunable the engine has, with the value it ships with. Each one marked (T) in the spec is a
 * starting point rather than a measured optimum; they live here, in one place, so the developer
 * page can show and change them and the replay can try alternatives.
 */
data class EngineParams(
    // ---- What a listen is worth
    /** Below this a stop is not a positive: nobody has heard enough to have an opinion. */
    val minPositiveMs: Long = 30_000L,
    /** Engagement ramps from [rampFrom] to [rampTo] of the song's length. */
    val rampFrom: Double = 0.10,
    val rampTo: Double = 0.80,
    /** With no known length: how much of the played time past the floor counts, and the ceiling. */
    val unknownLengthFullAtMs: Long = 180_000L,
    val unknownLengthCap: Double = 0.7,
    /** A like during the listen or within this after it makes the listen a full positive. */
    val likeCountsWithinMs: Long = 10L * 60_000,
    /** A skip counts against the song only before this fraction; leaving in the fade-out is not a verdict. */
    val skipIgnoredFrom: Double = 0.90,
    /** How much a counted skip takes off engagement. */
    val skipWeight: Double = 0.5,

    // ---- Intent, by how the play started
    val intentSearch: Double = 1.0,
    val intentChosen: Double = 0.8,
    val intentRow: Double = 0.7,
    val intentInsideContainer: Double = 0.8,
    val intentRadioBase: Double = 0.6,
    val intentRadioDecay: Double = 0.85,
    val intentRadioFloor: Double = 0.2,

    // ---- Activation and its neighbours
    /** ACT-R's relistening decay. */
    val activationDecay: Double = 0.5,
    /** A song counts at most this many listens per session in its activation, so a loop is not a thousand votes. */
    val activationPerSession: Int = 3,
    /** A usable like is a pseudo-listen worth this at its instant. */
    val likePseudoListen: Double = 0.6,
    /** More than this many likes stamped inside one minute is an import, and those dates mean nothing. */
    val bulkLikeThreshold: Int = 20,

    // ---- Satiation
    val satiationWindowDays: Int = 90,
    /** Exposure at which satiation starts and the width of its ramp. */
    val satiationStart: Double = 10.0,
    val satiationRamp: Double = 20.0,
    /** Gap growth: listens considered, the minimum needed, and the octaves of growth that saturate. */
    val gapListens: Int = 20,
    val gapMinListens: Int = 3,
    val gapOctaves: Double = 3.0,

    // ---- Dormancy and novelty
    val dormantAfterDays: Int = 45,
    val likedQuietDays: Int = 60,
    val dormantFloorForLiked: Double = 0.3,
    /** Half-saturation of old activation for the dormancy feature. */
    val dormancyHalf: Double = 0.25,

    // ---- Impressions, co-occurrence, context, over-play
    val impressionWindowDays: Int = 14,
    val impressionScale: Double = 3.0,
    val coSessions: Int = 30,
    val coSaturation: Double = 5.0,
    val contextWindowDays: Int = 90,
    val contextMinListens: Int = 20,
    val contextPrior: Double = 4.0,
    val overplayShortDays: Int = 7,
    val overplayLongDays: Int = 90,
    /** Artist strength's half-saturation. */
    val artistHalf: Double = 2.0,

    // ---- The row
    val rowSize: Int = 20,
    val columns: Int = 4,
    /** Explore share is [exploreBase] + [exploreSpan] times the dial in [0, 1]. */
    val exploreBase: Double = 0.05,
    val exploreSpan: Double = 0.30,
    /** The rest of the row after explore, split between the four other lanes. */
    val relatedShare: Double = 0.40,
    val againShare: Double = 0.25,
    val artistShare: Double = 0.20,
    val rediscoverShare: Double = 0.15,
    /** Again: songs heard well inside this many days, but not inside [engineFreshHours]. */
    val againWindowDays: Int = 14,
    /**
     * The engine row's own freshness rule: nothing heard well this recently, nor anything from the
     * current session. One hour rather than four: on the maintainer's history the shorter rule
     * lifted the familiar row's hits by a fifth, and the current session is excluded either way.
     */
    val engineFreshHours: Int = 1,
    val maxPerArtist: Int = 2,
    /** Whether the Again lane may exceed the per-artist cap: a listener's favourites often share an artist. */
    val againIgnoresArtistCap: Boolean = false,
    val maxPerSeed: Int = 3,
    val temperature: Double = 1.0,
    /** A lane samples from this many times its quota, after its best picks. */
    val sampleBreadth: Int = 3,
    /** Below this many cards the visit falls through to the classic row, then YouTube's shelf. */
    val minCards: Int = 8,
    /** Artists considered for the artist lane, and how long a song must rest before that lane offers it again. */
    val artistLaneArtists: Int = 30,
    val artistLaneQuietDays: Int = 14,

    // ---- Seeds
    val seedsTotal: Int = 16,
    val seedsNow: Int = 4,
    val seedsToday: Int = 3,
    val seedsStrong: Int = 4,
    val seedsSearched: Int = 2,
    val seedsLikedRecent: Int = 2,
    val seedsLikedOld: Int = 1,
    val strongPool: Int = 30,
    val searchedDays: Int = 14,
    val searchedMinEngagement: Double = 0.8,
    val likedRecentPool: Int = 20,
    val likedOldAfterDays: Int = 60,
    /** A seed of a row shown in the last [seedDampHours] hours has its weight multiplied by [seedDamp] per such build. */
    val seedDampHours: Int = 3,
    val seedDamp: Double = 0.3,

    // ---- Just played
    val justPlayedHours: Int = 24,
    val justPlayedEngagement: Double = 0.5,
    val sessionGapMs: Long = 30L * 60_000,
) {
    /** The Again lane's share of the row after explore, the other three lanes scaled to the rest. */
    fun withFamiliarity(share: Double): EngineParams {
        val again = share.coerceIn(0.0, 0.6)
        val others = relatedShare + artistShare + rediscoverShare
        val scale = (1 - again) / others
        return copy(againShare = again, relatedShare = relatedShare * scale, artistShare = artistShare * scale, rediscoverShare = rediscoverShare * scale)
    }

    companion object {
        val DEFAULT = EngineParams()
    }
}
