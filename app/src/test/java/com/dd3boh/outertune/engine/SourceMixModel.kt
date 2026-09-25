/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/*
 * The adaptive Last.fm and YouTube mix from the source-mix spec, written for the replay alone so
 * it can be measured on a real history before any of it goes into the app. No app code calls it.
 *
 * MixRow.build is EngineRow.build and Assembly line for line with the spec's three additions:
 * provenance bits on the RELATED and EXPLORE candidates, a ledger that gives Last.fm
 * floor(s * n + U) of the first n similar-song places whenever it has an open candidate, and the
 * share the build used. With no share it has to draw exactly as EngineRow.build does, and both
 * SourceMixModelTest and the replay check that, so a later engine change that is not copied here
 * fails loudly instead of quietly measuring an older engine. If the mix goes into the app, this
 * file should go and the replay should call the real thing.
 */

/** Which similar-song lists proposed a card, as bits. */
object MixBits {
    const val YOUTUBE = 1
    const val LASTFM = 2
    const val SOURCES = 3
    /** At least one song that proposed it has lists from both sources, so the two competed for it. */
    const val CONTESTED = 4
    fun isEvidence(bits: Int) = bits and CONTESTED != 0 && (bits and SOURCES == YOUTUBE || bits and SOURCES == LASTFM)
}

/** One graded impression, as the share reads it. */
data class MixEvidence(val songId: String, val team: Int, val lane: Int, val sources: Int, val visibleAt: Long, val outcome: Int, val y: Float?)

object MixShare {
    const val FLOOR = 0.2
    const val HALF_LIFE_DAYS = 14.0
    const val PRIOR = 6.0
    const val POWER = 2.0
    const val WINDOW_MS = 120L * 86_400_000L

    data class Side(val cards: Double, val heard: Double, val expected: Double) {
        val per100 get() = if (cards > 0) 100 * heard / cards else 0.0
    }

    data class State(val share: Double, val lastFm: Side, val youTube: Side, val days: Int) {
        val compared get() = lastFm.cards > 0 && youTube.cards > 0
    }

    private class Kept(val cell: Int, val side: Int, val day: Long, val visibleAt: Long, val y: Double)

    fun share(rows: List<MixEvidence>, tzOffsetMin: Int): State {
        // A card seen in several builds on one day is one decision by the listener, so it counts
        // once, at its best grade.
        val best = HashMap<Pair<Long, String>, Kept>()
        for (r in rows) {
            if (r.team != 1 && r.team != 4) continue
            if (r.lane != 1 && r.lane != 4) continue
            if (!MixBits.isEvidence(r.sources) || r.outcome !in 0..3) continue
            // A pending card is seen and not yet played, so a fresh play cannot lift its source
            // before the cards ignored beside it are graded.
            val y = if (r.outcome == Outcome.PENDING) 0.0 else (r.y ?: 0f).toDouble()
            val day = Math.floorDiv(r.visibleAt + tzOffsetMin * 60_000L, 86_400_000L)
            val k = Kept(r.team * 8 + r.lane, r.sources and MixBits.SOURCES, day, r.visibleAt, y)
            val had = best[day to r.songId]
            if (had == null || k.y > had.y || (k.y == had.y && k.visibleAt > had.visibleAt)) best[day to r.songId] = k
        }
        val none = Side(0.0, 0.0, 0.0)
        if (best.isEmpty()) return State(0.5, none, none, 0)
        // Age counts days with listening, so a week away does not wipe out what came before it.
        val age = best.values.map { it.day }.distinct().sortedDescending().withIndex().associate { it.value to it.index }
        fun w(k: Kept) = 0.5.pow(age[k.day]!! / HALF_LIFE_DAYS)
        // Each team and lane is its own baseline, so a source is not credited for landing in the
        // lane where plays come easier.
        val n = HashMap<Int, Double>(); val yy = HashMap<Int, Double>()
        for (k in best.values) { n[k.cell] = (n[k.cell] ?: 0.0) + w(k); yy[k.cell] = (yy[k.cell] ?: 0.0) + w(k) * k.y }
        fun side(s: Int): Side {
            var c = 0.0; var h = 0.0; var e = 0.0
            for (k in best.values) if (k.side == s) { val wk = w(k); c += wk; h += wk * k.y; e += wk * yy[k.cell]!! / n[k.cell]!! }
            return Side(c, h, e)
        }
        val l = side(MixBits.LASTFM); val yt = side(MixBits.YOUTUBE)
        val rhoL = (l.heard + PRIOR) / (l.expected + PRIOR); val rhoY = (yt.heard + PRIOR) / (yt.expected + PRIOR)
        val raw = rhoL.pow(POWER) / (rhoL.pow(POWER) + rhoY.pow(POWER))
        return State(raw.coerceIn(FLOOR, 1 - FLOOR), l, yt, age.size)
    }
}

/** A built row with each card's provenance bits and the source its edge place was credited to (0 for none). */
class MixedRow(val row: BuiltRow, val bits: List<Int>, val credited: List<Int>, val lastFmShare: Double?)

object MixRow {
    /** EngineRow.build with provenance and the split; [sources] gives each edge's bits, YouTube when absent. */
    fun build(
        input: EngineInput,
        sources: Map<Edge, Int> = emptyMap(),
        weights: Weights = Weights.PRIORS,
        p: EngineParams = EngineParams.DEFAULT,
        dial: Double = 0.15,
        newOnly: Boolean = false,
        neverPlayed: Boolean = false,
        random: Random = Random.Default,
        lastFmShare: Double? = null,
    ): MixedRow {
        val stats = LibraryStats(input, p)
        val groups = VersionGroups(input.songs.values, input.versionLinks)
        val day = 86_400_000.0

        val edgesBySeed = input.edges.groupBy { it.seedId }
        fun bitsOf(e: Edge) = sources[e] ?: MixBits.YOUTUBE
        // A seed whose lists come from both sources is where the two compete for the same places,
        // which is the only kind of card that says anything about which one the listener prefers.
        val listsOf = HashMap<String, Int>()
        for (e in input.edges) listsOf[e.seedId] = (listsOf[e.seedId] ?: 0) or (bitsOf(e) and MixBits.SOURCES)
        fun proposedBy(from: Iterable<String>): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (s in from) {
                val c = if (listsOf[s] == MixBits.SOURCES) MixBits.CONTESTED else 0
                for (e in edgesBySeed[s].orEmpty()) out[e.songId] = (out[e.songId] ?: 0) or (bitsOf(e) and MixBits.SOURCES) or c
            }
            return out
        }
        val referrersAll = HashMap<String, MutableList<String>>()
        for (e in input.edges) referrersAll.getOrPut(e.songId) { ArrayList() }.add(e.seedId)
        val ctxAll = Features.Context(stats, groups, input, referrersAll, p)
        val seeds = SeedSampler(input, stats, ctxAll, p, random).sample()
        val lightlyPlayed = stats.songs.entries.filter { it.value.goodListens in 1..2 }.map { it.key }
        val relatedBits = proposedBy(seeds)
        val exploreBits = proposedBy(seeds + lightlyPlayed)
        fun bitsOf(c: Candidate) = when (c.lane) {
            Lane.RELATED -> relatedBits[c.songId] ?: 0
            Lane.EXPLORE -> exploreBits[c.songId] ?: 0
            else -> 0
        }

        val excludedGroups = HashSet<String>()
        seeds.forEach { excludedGroups += groups.groupOf(it) }
        val justPlayedCut = input.now - p.engineFreshHours * 3_600_000.0
        for (l in input.listens) {
            if (l.sessionId == stats.latestSessionId && input.now - l.endedAt <= p.sessionGapMs) { excludedGroups += groups.groupOf(l.songId); continue }
            if (l.startedAt >= justPlayedCut) {
                val liked = input.songs[l.songId]?.likedAt?.takeIf { l.songId !in stats.bulkLikeSongIds }
                if (Signals.engagement(l, liked, p) >= p.justPlayedEngagement) excludedGroups += groups.groupOf(l.songId)
            }
        }
        val bannedArtists = input.exclusions.filter { it.kind == 2 }.mapTo(HashSet()) { it.targetId }
        val bannedKeys = (input.exclusions.filter { it.kind == 1 }.map { it.targetId } + input.banned)
            .mapNotNullTo(HashSet()) { id -> input.songs[id]?.let { versionKey(it.title, it.artistName) } }
        val bannedIds = (input.exclusions.filter { it.kind == 1 }.map { it.targetId } + input.banned).toHashSet()

        fun eligible(id: String): Boolean {
            val s = input.songs[id] ?: return false
            if (!s.playable) return false
            if (s.artistId != null && s.artistId in bannedArtists) return false
            if (id in bannedIds || versionKey(s.title, s.artistName) in bannedKeys) return false
            return groups.groupOf(id) !in excludedGroups
        }

        val referrers = HashMap<String, MutableList<String>>()
        for (seed in seeds) for (e in edgesBySeed[seed].orEmpty()) referrers.getOrPut(e.songId) { ArrayList() }.add(seed)
        val ctx = Features.Context(stats, groups, input, referrers, p)
        val cache = HashMap<String, DoubleArray>()
        fun x(id: String) = cache.getOrPut(id) { Features.of(id, ctx) }

        fun candidate(id: String, lane: Lane): Candidate? {
            if (!eligible(id)) return null
            val f = x(id)
            val seed = referrers[id]?.maxByOrNull { stats.seedWeight(it) }
            return Candidate(id, lane, f, Scorer.z(f, weights), seed, input.songs[id]?.artistId, groups.groupOf(id))
        }

        val related = referrers.keys.mapNotNull { candidate(it, Lane.RELATED) }.sortedByDescending { it.z }

        val artistPool = stats.artists.entries.filter { it.value.strength > 0 && it.key !in bannedArtists }
            .sortedByDescending { it.value.strength }.take(p.artistLaneArtists)
            .associate { it.key to LibraryStats.sqrtWeight(it.value.strength) }.toMutableMap()
        val sampledArtists = ArrayList<String>()
        while (artistPool.isNotEmpty()) {
            val total = artistPool.values.sum(); var r = random.nextDouble() * total
            var chosen = artistPool.keys.first()
            for ((id, w) in artistPool) { r -= w; if (r <= 0) { chosen = id; break } }
            artistPool.remove(chosen); sampledArtists += chosen
        }
        val artistRank = sampledArtists.withIndex().associate { it.value to it.index }
        val quietCut = input.now - p.artistLaneQuietDays * day
        val artistLane = input.songs.values
            .filter { it.artistId != null && it.artistId in artistRank && (it.inLibrary || it.liked || true) }
            .filter { (stats.songs[it.id]?.lastStartedAt ?: 0L) < quietCut }
            .mapNotNull { candidate(it.id, Lane.ARTIST) }
            .sortedWith(compareByDescending<Candidate> { it.z }.thenBy { artistRank[it.artistId] ?: Int.MAX_VALUE })

        val againCut = input.now - p.againWindowDays * day
        val again = stats.songs.entries
            .filter { it.value.lastGoodAt in againCut.toLong()..(justPlayedCut.toLong()) }
            .sortedByDescending { it.value.activation }.take(200)
            .mapNotNull { candidate(it.key, Lane.AGAIN) }
            .sortedByDescending { it.z }

        val rediscover = stats.songs.entries.filter { it.value.oldActivation > 0 }
            .sortedByDescending { it.value.oldActivation }.take(200)
            .mapNotNull { candidate(it.key, Lane.REDISCOVER) }
            .filter { it.x[Features.DORM] > 0 }
            .sortedByDescending { it.z }

        val exploreSource = HashSet<String>()
        (seeds + lightlyPlayed).forEach { s -> edgesBySeed[s]?.forEach { exploreSource += it.songId } }
        input.songs.values.filter { it.inLibrary && stats.songs[it.id] == null }.forEach { exploreSource += it.id }
        val exploreAll = exploreSource.mapNotNull { candidate(it, Lane.EXPLORE) }
        val chipParams = when (input.chip) {
            ContextChip.FAVOURITES -> p.copy(exploreBase = 0.0, exploreSpan = 0.0, relatedShare = 0.20, againShare = 0.20, artistShare = 0.30, rediscoverShare = 0.30)
            else -> p
        }
        val chipDial = if (input.chip == ContextChip.DISCOVER) 1.5 else dial
        val onlyNew = newOnly || neverPlayed
        val quotasNow = quotas(p.rowSize, chipDial.coerceIn(0.0, 1.5), onlyNew, chipParams).let { q ->
            if (input.chip == ContextChip.DISCOVER) quotas(p.rowSize, 1.0, onlyNew, p.copy(exploreBase = 0.50, exploreSpan = 0.0)) else q
        }
        var explore = exploreAll.filter { it.x[Features.NOVEL] >= 1.0 }.sortedByDescending { it.z }
        if (explore.size < 2 * (quotasNow[Lane.EXPLORE] ?: 0)) {
            explore = (explore + exploreAll.filter { it.x[Features.NOVEL] == 0.5 }.sortedByDescending { it.z })
        }

        fun unheard(c: Candidate) = stats.songs[c.songId] == null
        val lanes = when {
            neverPlayed -> mapOf(Lane.EXPLORE to explore.filter(::unheard), Lane.RELATED to related.filter(::unheard))
            newOnly -> mapOf(Lane.EXPLORE to explore.filter { it.x[Features.NOVEL] >= 0.5 }, Lane.RELATED to related.filter { it.x[Features.NOVEL] >= 0.5 })
            else -> mapOf(Lane.RELATED to related, Lane.AGAIN to again, Lane.ARTIST to artistLane, Lane.REDISCOVER to rediscover, Lane.EXPLORE to explore)
        }
        // A graph with nothing from Last.fm in the similar-song lanes draws exactly as today: no
        // split and no extra draw.
        val split = lastFmShare?.takeIf { (lanes[Lane.RELATED].orEmpty() + lanes[Lane.EXPLORE].orEmpty()).any { bitsOf(it) and MixBits.LASTFM != 0 } }
        val assembly = MixAssembly(lanes, quotasNow, weights, p, random, split, ::bitsOf)
        val placed = assembly.run()
        val inRow = placed.mapTo(HashSet()) { it.first.songId }
        val cards = placed.mapIndexed { slot, (c, sampled) ->
            val reasons = when {
                c.lane == Lane.EXPLORE -> listOf("new_to_you")
                sampled -> listOf("wildcard") + Scorer.reasons(c.x, weights).take(1)
                else -> Scorer.reasons(c.x, weights)
            }
            Card(c.songId, c.lane, c.z, Scorer.p(c.x, weights, c.lane, slot, p.columns), c.x, reasons, c.seedId, sampled)
        }
        val poolGroups = HashSet<String>(placed.map { it.first.group })
        val pool = lanes.values.flatten().filter { it.songId !in inRow }.sortedByDescending { it.z }
            .filter { poolGroups.add(it.group) }.take(40)
            .map { c -> Card(c.songId, c.lane, c.z, Scorer.p(c.x, weights, c.lane, p.rowSize, p.columns), c.x, Scorer.reasons(c.x, weights), c.seedId, false) }
        return MixedRow(BuiltRow(cards, seeds, pool, quotasNow), placed.map { bitsOf(it.first) }, assembly.credited, split)
    }
}

/** Assembly with the spec's ledger over the RELATED and EXPLORE places. */
internal class MixAssembly(
    private val lanes: Map<Lane, List<Candidate>>,
    private val quotas: Map<Lane, Int>,
    private val weights: Weights,
    private val p: EngineParams,
    private val random: Random,
    private val lastFmShare: Double?,
    private val bitsOf: (Candidate) -> Int,
) {
    private val takenGroups = HashSet<String>()
    private val perArtist = HashMap<String, Int>()
    private val perSeed = HashMap<String, Int>()
    private val placed = ArrayList<Pair<Candidate, Boolean>>()
    private val used = HashMap<Lane, Int>()
    private val effectiveQuotas = quotas.toMutableMap()
    /** The source each placed card was credited to, 0 when it was not an edge place. */
    val credited = ArrayList<Int>()

    // Drawn once and only with a split, so a row without one uses the same dice as today.
    private val offset = if (lastFmShare != null) random.nextDouble() else 0.0
    private var edgeCards = 0
    private var lastFmCards = 0
    private var lastDue = 0
    private fun splits(l: Lane) = lastFmShare != null && (l == Lane.RELATED || l == Lane.EXPLORE)
    private fun due() = if (floor(lastFmShare!! * (edgeCards + 1) + offset) > lastFmCards) MixBits.LASTFM else MixBits.YOUTUBE

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
        val bits = bitsOf(c) and MixBits.SOURCES
        if (splits(c.lane) && bits != 0) {
            edgeCards++
            // A song both lists proposed fills whichever source was due, so agreement never costs
            // the source that was owed the place.
            val to = if (bits == MixBits.SOURCES) lastDue else bits
            if (to == MixBits.LASTFM) lastFmCards++
            credited += to
        } else credited += 0
    }

    private fun pick(lane: Lane, slot: Int): Pair<Candidate, Boolean>? {
        var open = lanes[lane].orEmpty().filter { allowed(it, slot) }
        if (open.isEmpty()) return null
        if (splits(lane)) {
            lastDue = due()
            // Cards no list proposed stay in, so the split never pushes a library song out, and
            // when the due source has nothing open the place goes to what the lane has.
            val narrowed = open.filter { bitsOf(it) and lastDue != 0 || bitsOf(it) and MixBits.SOURCES == 0 }
            if (narrowed.any { bitsOf(it) and lastDue != 0 }) open = narrowed
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
        val firstColumn = listOf(Lane.RELATED, Lane.AGAIN, Lane.ARTIST, Lane.EXPLORE)
        if (firstColumn.all { lanes[it].orEmpty().isNotEmpty() && (effectiveQuotas[it] ?: 0) > 0 }) {
            for (lane in firstColumn) { val c = pick(lane, placed.size) ?: continue; place(c.first, c.second) }
        }
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
