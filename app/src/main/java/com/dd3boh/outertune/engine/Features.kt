/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.exp

/**
 * The twelve things the engine knows about a candidate, each in [-1, 1], plus the bias, the lane
 * biases and position. The names are the keys of `engine_weight`; the order is the order of the
 * feature vector stored on every impression.
 */
object Features {
    const val ACT = 0; const val SAT = 1; const val GAP = 2; const val DORM = 3; const val LIKE = 4; const val SEED = 5
    const val ART = 6; const val NOVEL = 7; const val IMP = 8; const val CO = 9; const val CTX = 10; const val OVER = 11
    const val COUNT = 12

    val names = listOf("x_act", "x_sat", "x_gap", "x_dorm", "x_like", "x_seed", "x_art", "x_novel", "x_imp", "x_co", "x_ctx", "x_over")

    /** The weight names beyond the twelve: the bias, one bias per lane, and position. */
    const val BIAS = "b"
    const val POS = "w_pos"
    fun laneBias(lane: Lane) = "b_" + lane.name.lowercase()

    /** Priors, bounds and the sign rule: a penalty may learn how strong it is, never that it should promote. */
    data class Prior(val value: Double, val lo: Double, val hi: Double)

    val priors: Map<String, Prior> = buildMap {
        fun free(name: String, v: Double) = put(name, Prior(v, v - 3, v + 3))
        fun penalty(name: String, v: Double) = put(name, Prior(v, v - 3, 0.0))
        free("x_act", 1.0); penalty("x_sat", -0.8); penalty("x_gap", -0.4); free("x_dorm", 0.5); free("x_like", 0.3)
        free("x_seed", 1.2); free("x_art", 0.8); free("x_novel", -0.3); penalty("x_imp", -1.0); free("x_co", 0.4)
        free("x_ctx", 0.3); penalty("x_over", -1.0); penalty(POS, -0.3); free(BIAS, -3.0)
        Lane.entries.forEach { free(laneBias(it), 0.0) }
    }

    /** What the row's other cards let a candidate know: its referring seeds and the bucket it is built for. */
    class Context(
        val stats: LibraryStats,
        val groups: VersionGroups,
        val input: EngineInput,
        /** Seed ids with an edge to each candidate. */
        val referrers: Map<String, List<String>>,
        val p: EngineParams = EngineParams.DEFAULT,
    ) {
        private val day = 86_400_000.0
        /** Seen, ignored impressions per version group inside the window. */
        val ignoredByGroup: Map<String, Int> = input.seen
            .filter { it.seenAt >= input.now - p.impressionWindowDays * day }
            .groupingBy { groups.groupOf(it.songId) }.eachCount()
        val currentSessionArtists: Set<String> = stats.sessionArtists.firstOrNull() ?: emptySet()
        /** A mood chip with enough tagged listens: x_ctx is fit to the mood rather than the day part. */
        val moodActive: Boolean = input.chip in ContextChip.MOODS && stats.goodTaggedAll >= ContextChip.MIN_TAGGED
        val bucketShare: Double = when {
            moodActive -> if (stats.goodInContextWindow > 0) stats.goodTaggedInWindow.toDouble() / stats.goodInContextWindow else 0.0
            stats.goodInContextWindow > 0 -> stats.goodByBucket[input.bucket].toDouble() / stats.goodInContextWindow
            else -> 0.0
        }
    }

    fun of(songId: String, c: Context): DoubleArray {
        val p = c.p
        val song = c.input.songs[songId]
        val st = c.stats.songs[songId]
        val artistId = song?.artistId
        val art = artistId?.let { c.stats.artists[it] }
        val x = DoubleArray(COUNT)
        val a = st?.activation ?: 0.0
        x[ACT] = LibraryStats.squash(a)
        x[SAT] = (((st?.exposure ?: 0.0) - p.satiationStart) / p.satiationRamp).coerceIn(0.0, 1.0)
        x[GAP] = gap(st, c)
        x[DORM] = dormancy(song, st, c)
        x[LIKE] = if (song?.liked == true) 1.0 else 0.0
        val r = c.referrers[songId].orEmpty().sumOf { c.stats.seedWeight(it) }
        x[SEED] = r / (1 + r)
        val b = art?.strength ?: 0.0
        x[ART] = b / (b + p.artistHalf)
        val songNew = (st?.goodListens ?: 0) == 0
        val artistNew = (art?.goodListens ?: 0) == 0
        x[NOVEL] = when { songNew && artistNew -> 1.0; songNew -> 0.5; else -> 0.0 }
        val n = c.ignoredByGroup[c.groups.groupOf(songId)] ?: 0
        x[IMP] = 1 - exp(-n / p.impressionScale)
        x[CO] = if (artistId == null || c.currentSessionArtists.isEmpty()) 0.0 else {
            val co = c.stats.sessionArtists.count { s -> artistId in s && s.any { it in c.currentSessionArtists && it != artistId } }
            (co / p.coSaturation).coerceAtMost(1.0)
        }
        x[CTX] = contextLift(art, c)
        x[OVER] = if (art == null || art.positiveLong <= 0) 0.0 else {
            val totalShort = c.stats.artists.values.sumOf { it.positiveShort }
            val totalLong = c.stats.artists.values.sumOf { it.positiveLong }
            val share7 = if (totalShort > 0) art.positiveShort / totalShort else 0.0
            val share90 = if (totalLong > 0) art.positiveLong / totalLong else 0.0
            maxOf(0.0, share7 - share90)
        }
        return x
    }

    private fun gap(st: SongStats?, c: Context): Double {
        if (st == null || st.goodStarts.size < c.p.gapMinListens) return 0.0
        val starts = st.goodStarts   // newest first
        val gaps = (0 until starts.size - 1).map { (starts[it] - starts[it + 1]).toDouble() }.sorted()
        val median = if (gaps.size % 2 == 1) gaps[gaps.size / 2] else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2
        val hour = 3_600_000.0
        val gapNow = (c.input.now - starts.first()).toDouble()
        return (LibraryStats.log2(gapNow / maxOf(median, hour)) / c.p.gapOctaves).coerceIn(0.0, 1.0)
    }

    private fun dormancy(song: SongRow?, st: SongStats?, c: Context): Double {
        val day = 86_400_000.0
        val quietSince = st?.lastGoodAt ?: 0L
        if (quietSince > c.input.now - c.p.dormantAfterDays * day) return 0.0
        val old = maxOf(0.0, st?.oldActivation ?: 0.0)
        var x = if (old > 0) old / (old + c.p.dormancyHalf) else 0.0
        if (song?.liked == true && (song.likedAt ?: 0L) < c.input.now - c.p.likedQuietDays * day && old > 0) x = maxOf(x, c.p.dormantFloorForLiked)
        return x
    }

    private fun contextLift(art: ArtistStats?, c: Context): Double {
        val p = c.p
        val inBucket = if (c.moodActive) c.stats.goodTaggedInWindow else c.stats.goodByBucket[c.input.bucket]
        if (art == null || inBucket < p.contextMinListens || c.bucketShare <= 0) return 0.0
        val nA = art.goodListens.toDouble()
        val nAb = (if (c.moodActive) art.goodTagged else art.goodByBucket[c.input.bucket]).toDouble()
        val lift = ((nAb + p.contextPrior * c.bucketShare) / (nA + p.contextPrior)) / c.bucketShare
        return LibraryStats.log2(lift).coerceIn(-1.0, 1.0)
    }
}
