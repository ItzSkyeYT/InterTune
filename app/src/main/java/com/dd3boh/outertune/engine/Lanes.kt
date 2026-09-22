/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.random.Random

/** A candidate with everything the assembly needs to place it. */
class Candidate(val songId: String, val lane: Lane, val x: DoubleArray, val z: Double, val seedId: String?, val artistId: String?, val group: String)

/** How many cards each lane gets, by largest remainder over the row. */
fun quotas(rowSize: Int, dial: Double, newOnly: Boolean, p: EngineParams = EngineParams.DEFAULT): Map<Lane, Int> {
    val e = if (newOnly) 1.0 else (p.exploreBase + p.exploreSpan * dial.coerceIn(0.0, 1.0))
    val rest = 1 - e
    val shares = mapOf(
        Lane.EXPLORE to e,
        Lane.RELATED to rest * p.relatedShare,
        Lane.AGAIN to rest * p.againShare,
        Lane.ARTIST to rest * p.artistShare,
        Lane.REDISCOVER to rest * p.rediscoverShare,
    )
    val floors = shares.mapValues { (_, s) -> (s * rowSize).toInt() }
    var left = rowSize - floors.values.sum()
    val byRemainder = shares.keys.sortedByDescending { shares[it]!! * rowSize - floors[it]!! }
    val out = floors.toMutableMap()
    for (lane in byRemainder) { if (left <= 0) break; out[lane] = out[lane]!! + 1; left-- }
    return out
}

/**
 * Fills the row from four lanes under one shared set of rules: the first column takes one card
 * from each lane, then the lane furthest behind its quota takes the next slot. Inside a lane the
 * first half of its quota is its best by score and the rest is drawn at temperature one from the
 * next few times that many, so the row is different each time without being random. Never two
 * versions of a song, never more than two cards from one artist or two in one column, never more
 * than three related cards from one seed.
 */
class Assembly(
    private val lanes: Map<Lane, List<Candidate>>,
    private val quotas: Map<Lane, Int>,
    private val weights: Weights,
    private val p: EngineParams = EngineParams.DEFAULT,
    private val random: Random = Random.Default,
) {
    private val takenGroups = HashSet<String>()
    private val perArtist = HashMap<String, Int>()
    private val perSeed = HashMap<String, Int>()
    private val placed = ArrayList<Pair<Candidate, Boolean>>()   // candidate, sampled
    private val used = HashMap<Lane, Int>()
    /** Lanes whose quota was handed on because they ran short. */
    private val effectiveQuotas = quotas.toMutableMap()

    private fun columnOf(slot: Int) = slot / p.columns
    private fun artistsInColumn(col: Int): Set<String> =
        placed.withIndex().filter { columnOf(it.index) == col }.mapNotNull { it.value.first.artistId }.toSet()

    private fun allowed(c: Candidate, slot: Int): Boolean {
        if (c.group in takenGroups) return false
        val artist = c.artistId
        if (artist != null && !(p.againIgnoresArtistCap && c.lane == Lane.AGAIN)) {
            if ((perArtist[artist] ?: 0) >= p.maxPerArtist) return false
            if (artist in artistsInColumn(columnOf(slot))) return false
        }
        if (c.lane == Lane.RELATED && c.seedId != null && (perSeed[c.seedId] ?: 0) >= p.maxPerSeed) return false
        return true
    }

    private fun place(c: Candidate, sampled: Boolean) {
        placed += c to sampled
        takenGroups += c.group
        c.artistId?.let { perArtist[it] = (perArtist[it] ?: 0) + 1 }
        if (c.lane == Lane.RELATED && c.seedId != null) perSeed[c.seedId] = (perSeed[c.seedId] ?: 0) + 1
        used[c.lane] = (used[c.lane] ?: 0) + 1
    }

    /** The lane's next card for [slot]: best by score for the first half of its quota, then sampled. */
    private fun pick(lane: Lane, slot: Int): Pair<Candidate, Boolean>? {
        val open = lanes[lane].orEmpty().filter { allowed(it, slot) }
        if (open.isEmpty()) return null
        val quota = effectiveQuotas[lane] ?: 0
        val soFar = used[lane] ?: 0
        val bestFirst = ceil(quota / 2.0).toInt()
        if (soFar < bestFirst) return open.first() to false
        val breadth = open.take(maxOf(1, p.sampleBreadth * quota))
        val scores = breadth.map { exp((it.z - breadth.first().z) / p.temperature) }
        var r = random.nextDouble() * scores.sum()
        for (i in breadth.indices) { r -= scores[i]; if (r <= 0) return breadth[i] to true }
        return breadth.last() to true
    }

    fun run(): List<Pair<Candidate, Boolean>> {
        val rowSize = quotas.values.sum()
        // A lane with nothing to give hands its quota to related, then again, then artist.
        val laneOrder = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST, Lane.REDISCOVER, Lane.EXPLORE)
        for (lane in laneOrder.reversed()) {
            val have = lanes[lane].orEmpty().size
            val want = effectiveQuotas[lane] ?: 0
            if (have < want) {
                val spare = want - have
                effectiveQuotas[lane] = have
                val to = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST).firstOrNull { it != lane && lanes[it].orEmpty().size > (effectiveQuotas[it] ?: 0) } ?: continue
                effectiveQuotas[to] = (effectiveQuotas[to] ?: 0) + spare
            }
        }
        // The first column: one card each from related, again, artist and explore when they all have something.
        val firstColumn = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST, Lane.EXPLORE)
        if (firstColumn.all { lanes[it].orEmpty().isNotEmpty() && (effectiveQuotas[it] ?: 0) > 0 }) {
            for (lane in firstColumn) { val c = pick(lane, placed.size) ?: continue; place(c.first, c.second) }
        }
        // Then the lane furthest behind its quota, as a fraction, takes the next slot.
        var guard = 0
        while (placed.size < rowSize && guard++ < rowSize * 8) {
            val next = laneOrder
                .filter { (effectiveQuotas[it] ?: 0) > (used[it] ?: 0) }
                .maxByOrNull { 1.0 - (used[it] ?: 0).toDouble() / (effectiveQuotas[it] ?: 1) } ?: break
            val c = pick(next, placed.size)
            if (c == null) { effectiveQuotas[next] = used[next] ?: 0; continue }
            place(c.first, c.second)
        }
        return placed
    }
}
