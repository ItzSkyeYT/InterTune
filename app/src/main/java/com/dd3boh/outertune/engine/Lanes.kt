/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.random.Random

/** A candidate with everything the assembly needs to place it; [sources] are its [Provenance] bits. */
class Candidate(val songId: String, val lane: Lane, val x: DoubleArray, val z: Double, val seedId: String?, val artistId: String?, val group: String, val sources: Int = 0)

/**
 * How many cards each lane gets, by largest remainder over the row.
 *
 * With a [lean] (and not [newOnly], which already decides the whole row), the lean's lane gets
 * [leadCards] and the other four split what is left by largest remainder in their blend
 * proportions, so Adventurousness and Familiarity still shape the rest of the row.
 */
fun quotas(rowSize: Int, dial: Double, newOnly: Boolean, p: EngineParams = EngineParams.DEFAULT, lean: Lean = Lean.AUTO, leadCards: Int = p.leanCards): Map<Lane, Int> {
    val e = if (newOnly) 1.0 else (p.exploreBase + p.exploreSpan * dial.coerceIn(0.0, 1.0))
    val rest = 1 - e
    val shares = mapOf(
        Lane.EXPLORE to e,
        Lane.RELATED to rest * p.relatedShare,
        Lane.AGAIN to rest * p.againShare,
        Lane.ARTIST to rest * p.artistShare,
        Lane.REDISCOVER to rest * p.rediscoverShare,
    )
    val lead = lean.lane.takeIf { !newOnly }
    if (lead != null) {
        val leadCount = leadCards.coerceIn(0, rowSize)
        val others = shares.filterKeys { it != lead }
        val total = others.values.sum().takeIf { it > 0 } ?: 1.0
        val restCards = rowSize - leadCount
        val out = others.mapValues { (_, s) -> (s / total * restCards).toInt() }.toMutableMap()
        var left = restCards - out.values.sum()
        for (lane in others.keys.sortedByDescending { others[it]!! / total * restCards - out[it]!! }) { if (left <= 0) break; out[lane] = out[lane]!! + 1; left-- }
        out[lead] = leadCount
        return shares.keys.associateWith { out[it] ?: 0 }
    }
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
 *
 * With [lastFmShare], the related and explore places share one ledger: Last.fm is due a place
 * whenever floor(share * (places + 1) + U) is more than it has had, with U drawn once per row, so
 * over the first n places it holds floor(share * n + U) of them. A due source with nothing open
 * gives its place to what the lane has, so the split never makes the row shorter.
 *
 * With a [lead] lane (a lean), the first column is the lead lane's, so the first thing on screen
 * is what was asked for, and the rest of its cards spread evenly over the other columns. A lane
 * that runs short then hands its places to the others in proportion to their quotas, so the row
 * stays full and the usual mix fills the gap.
 */
class Assembly(
    private val lanes: Map<Lane, List<Candidate>>,
    private val quotas: Map<Lane, Int>,
    private val weights: Weights,
    private val p: EngineParams = EngineParams.DEFAULT,
    private val random: Random = Random.Default,
    private val lastFmShare: Double? = null,
    /** The lane a lean favours; null for Auto, which keeps every rule exactly as it was. */
    private val lead: Lane? = null,
    /** Cards one artist may hold in the lead lane; every other card keeps [EngineParams.maxPerArtist]. */
    private val leadArtistCap: Int = p.maxPerArtist,
) {
    private val takenGroups = HashSet<String>()
    private val perArtist = HashMap<String, Int>()
    private val perSeed = HashMap<String, Int>()
    private val placed = ArrayList<Pair<Candidate, Boolean>>()   // candidate, sampled
    private val used = HashMap<Lane, Int>()
    /** Lanes whose quota was handed on because they ran short. */
    private val effectiveQuotas = quotas.toMutableMap()
    /** The source each placed card's place was credited to, [Provenance.LASTFM] or YOUTUBE, or 0 outside the split. */
    val credited = ArrayList<Int>()

    // Drawn once and only with a split, so a row without one uses exactly the dice it always did.
    private val offset = if (lastFmShare != null) random.nextDouble() else 0.0
    private var edgeCards = 0
    private var lastFmCards = 0
    private var lastDue = 0
    private fun splits(l: Lane) = lastFmShare != null && (l == Lane.RELATED || l == Lane.EXPLORE)
    private fun due() = if (floor(lastFmShare!! * (edgeCards + 1) + offset) > lastFmCards) Provenance.LASTFM else Provenance.YOUTUBE

    private fun columnOf(slot: Int) = slot / p.columns
    private fun artistsInColumn(col: Int): Set<String> =
        placed.withIndex().filter { columnOf(it.index) == col }.mapNotNull { it.value.first.artistId }.toSet()

    private fun allowed(c: Candidate, slot: Int): Boolean {
        if (c.group in takenGroups) return false
        val artist = c.artistId
        if (artist != null && !(p.againIgnoresArtistCap && c.lane == Lane.AGAIN)) {
            val cap = if (lead != null && c.lane == lead) leadArtistCap else p.maxPerArtist
            if ((perArtist[artist] ?: 0) >= cap) return false
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
        val bits = c.sources and Provenance.SOURCES
        if (splits(c.lane) && bits != 0) {
            edgeCards++
            // A song both lists proposed fills whichever source was due, so agreement never costs
            // the source that was owed the place.
            val to = if (bits == Provenance.SOURCES) lastDue else bits
            if (to == Provenance.LASTFM) lastFmCards++
            credited += to
        } else credited += 0
    }

    /** The lane's next card for [slot]: best by score for the first half of its quota, then sampled. */
    private fun pick(lane: Lane, slot: Int): Pair<Candidate, Boolean>? {
        var open = lanes[lane].orEmpty().filter { allowed(it, slot) }
        if (open.isEmpty()) return null
        if (splits(lane)) {
            lastDue = due()
            // Cards no list proposed stay in, so the split never pushes a library song out, and
            // when the due source has nothing open the place goes to what the lane has.
            val narrowed = open.filter { it.sources and lastDue != 0 || it.sources and Provenance.SOURCES == 0 }
            if (narrowed.any { it.sources and lastDue != 0 }) open = narrowed
        }
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
                if (lead != null) {
                    // Under a lean the usual mix fills the gap, each lane in proportion to its quota.
                    repeat(spare) {
                        laneOrder.filter { it != lane && lanes[it].orEmpty().size > (effectiveQuotas[it] ?: 0) }
                            .maxByOrNull { handOffPull(it) }
                            ?.let { effectiveQuotas[it] = (effectiveQuotas[it] ?: 0) + 1 }
                    }
                    continue
                }
                val to = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST).firstOrNull { it != lane && lanes[it].orEmpty().size > (effectiveQuotas[it] ?: 0) } ?: continue
                effectiveQuotas[to] = (effectiveQuotas[to] ?: 0) + spare
            }
        }
        // Where the furthest-behind rule starts counting from: nothing for Auto, the first column under a lean.
        val base = HashMap<Lane, Int>()
        if (lead != null) {
            // The first column is the lead lane's, as far as it has cards it may place.
            repeat(p.columns) {
                if ((used[lead] ?: 0) < (effectiveQuotas[lead] ?: 0)) pick(lead, placed.size)?.let { place(it.first, it.second) }
            }
            base.putAll(used)
        } else {
            // The first column: one card each from related, again, artist and explore when they all have something.
            val firstColumn = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST, Lane.EXPLORE)
            if (firstColumn.all { lanes[it].orEmpty().isNotEmpty() && (effectiveQuotas[it] ?: 0) > 0 }) {
                for (lane in firstColumn) { val c = pick(lane, placed.size) ?: continue; place(c.first, c.second) }
            }
        }
        // Then the lane furthest behind its quota, as a fraction, takes the next slot. Under a lean
        // the fraction is of what is left after the first column, so the rest of the lead lane's
        // cards spread evenly over the columns instead of bunching at the end.
        var guard = 0
        while (placed.size < rowSize && guard++ < rowSize * 8) {
            val next = laneOrder
                .filter { (effectiveQuotas[it] ?: 0) > (used[it] ?: 0) }
                .maxByOrNull { val b = base[it] ?: 0; 1.0 - ((used[it] ?: 0) - b).toDouble() / maxOf(1, (effectiveQuotas[it] ?: 1) - b) } ?: break
            val c = pick(next, placed.size)
            if (c == null) {
                val spare = (effectiveQuotas[next] ?: 0) - (used[next] ?: 0)
                effectiveQuotas[next] = used[next] ?: 0
                // Under a lean a lane that runs out of cards it may place hands its places on, so the
                // row stays full; the heading says the lean ran short.
                if (lead != null) repeat(spare) {
                    laneOrder.filter { it != next && lanes[it].orEmpty().any { k -> allowed(k, placed.size) } }
                        .maxByOrNull { handOffPull(it) }
                        ?.let { effectiveQuotas[it] = (effectiveQuotas[it] ?: 0) + 1 }
                }
                continue
            }
            place(c.first, c.second)
        }
        return placed
    }

    /** How strongly a lane draws a handed-on place: its quota over one plus what it has been handed already. */
    private fun handOffPull(lane: Lane): Double = (quotas[lane] ?: 0).toDouble() / (1 + (effectiveQuotas[lane] ?: 0) - (quotas[lane] ?: 0))
}
