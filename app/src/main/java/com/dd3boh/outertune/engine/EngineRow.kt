/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ceil
import kotlin.random.Random

/**
 * The session actually under way, by recency alone: the session id of the most recent listen in
 * the log, an open row (already given endedAt = now by the loader, but its startedAt is real)
 * included, whatever its engagement or whether its song has a resolvable artist. Unlike
 * [LibraryStats.latestSessionId], which only names a session once it has produced a good,
 * artist-credited listen, this always finds the session in progress, so a session made so far of
 * nothing but skips is still recognised as current.
 */
internal fun currentSessionId(listens: List<ListenRow>): Long = listens.maxByOrNull { it.startedAt }?.sessionId ?: -1L

/**
 * One build of the Quick picks row: fold the log, pick the seeds, gather four lanes of candidates,
 * score each, fill the row under the shared rules, and keep the survivors as the pool that
 * replaces a card the listener plays before the next build.
 */
object EngineRow {
    /**
     * Scores a list that came from somewhere else (YouTube's shelf, the classic query) with the
     * same features and weights the engine row uses, so "Rank with your listening" means one
     * thing everywhere. Songs the database has never seen score on novelty alone and keep their
     * source's order among themselves.
     */
    fun rank(
        input: EngineInput,
        ids: List<String>,
        weights: Weights = Weights.PRIORS,
        p: EngineParams = EngineParams.DEFAULT,
        random: Random = Random.Default,
    ): Map<String, Double> {
        if (ids.isEmpty()) return emptyMap()
        val stats = LibraryStats(input, p)
        val groups = VersionGroups(input.songs.values, input.versionLinks)
        val referrersAll = HashMap<String, MutableList<String>>()
        for (e in input.edges) referrersAll.getOrPut(e.songId) { ArrayList() }.add(e.seedId)
        val seeds = SeedSampler(input, stats, Features.Context(stats, groups, input, referrersAll, p), p, random).sample().toHashSet()
        val referrers = HashMap<String, MutableList<String>>()
        for (e in input.edges) if (e.seedId in seeds) referrers.getOrPut(e.songId) { ArrayList() }.add(e.seedId)
        val ctx = Features.Context(stats, groups, input, referrers, p)
        return ids.associateWith { Scorer.z(Features.of(it, ctx), weights) }
    }

    /**
     * The ids in an order drawn without replacement in proportion to exp(z / T): strong songs
     * near the top on average, a different order each time, so a refresh of a ranked row is a
     * refresh. Songs with no score keep their source's order at the end.
     */
    fun rankSampled(
        input: EngineInput,
        ids: List<String>,
        weights: Weights = Weights.PRIORS,
        p: EngineParams = EngineParams.DEFAULT,
        random: Random = Random.Default,
    ): List<String> {
        val z = rank(input, ids, weights, p, random)
        val remaining = ids.filter { z.containsKey(it) }.toMutableList()
        val out = ArrayList<String>(ids.size)
        while (remaining.isNotEmpty()) {
            val top = remaining.maxOf { z[it]!! }
            val scores = remaining.map { kotlin.math.exp((z[it]!! - top) / p.temperature) }
            var r = random.nextDouble() * scores.sum()
            var chosen = remaining.last()
            for (i in remaining.indices) { r -= scores[i]; if (r <= 0) { chosen = remaining[i]; break } }
            remaining.remove(chosen); out += chosen
        }
        out += ids.filter { !z.containsKey(it) }
        return out
    }

    /**
     * What a build under these settings is shaped like, before any song is looked at: the lean it
     * applies, the lane that leads, how many cards each lane gets, and how many an Auto build
     * would have given each under the same chip and dials, which is the yardstick the lead lane's
     * evidence is weighed against. Pure, so a row restored from the database gets the same
     * quotas the build had.
     */
    class Shape(
        val lean: Lean,
        /** The lean's lane; null under Auto, and under Never heard with New songs only, which is new throughout. */
        val lead: Lane?,
        val quotas: Map<Lane, Int>,
        val autoQuotas: Map<Lane, Int>,
        /** Never heard with New songs only: the whole row never played, not even started, and not liked or in the library. */
        val strictNew: Boolean,
        /** Never heard with Discover: more of the row, from new artists only. */
        val discoverNew: Boolean,
        rowSize: Int,
    ) {
        val leadWeight: Double = lead?.let { LeanWeighting.leadWeight(autoQuotas[it] ?: 0, quotas[it] ?: 0, rowSize) } ?: 1.0
    }

    fun shape(
        p: EngineParams = EngineParams.DEFAULT,
        dial: Double = 0.5,
        newOnly: Boolean = false,
        neverPlayed: Boolean = false,
        chip: Int = ContextChip.AUTO,
        stored: Lean = Lean.AUTO,
    ): Shape {
        val lean = Lean.applied(stored, chip, newOnly, neverPlayed)
        val strictNew = lean == Lean.NEW && newOnly
        val discoverNew = lean == Lean.NEW && chip == ContextChip.DISCOVER && !strictNew
        // The chip's shape: Discover half new, Favourites nothing new and the lanes turned toward the familiar.
        val chipParams = when (chip) {
            ContextChip.FAVOURITES -> p.copy(exploreBase = 0.0, exploreSpan = 0.0, relatedShare = 0.20, againShare = 0.20, artistShare = 0.30, rediscoverShare = 0.30)
            else -> p
        }
        val chipDial = if (chip == ContextChip.DISCOVER) 1.5 else dial   // past the dial's end: explore share 0.50
        val onlyNew = newOnly || neverPlayed
        val autoQuotas = quotas(p.rowSize, chipDial.coerceIn(0.0, 1.5), onlyNew, chipParams).let { q ->
            if (chip == ContextChip.DISCOVER) quotas(p.rowSize, 1.0, onlyNew, p.copy(exploreBase = 0.50, exploreSpan = 0.0)) else q
        }
        val lead = lean.lane.takeIf { !strictNew }
        // With a lead lane the Discover line above is bypassed on purpose: a lean reaches here
        // with Discover only under Never heard, where Discover means more of the lead lane, from
        // new artists only, rather than ten explore cards. A mood keeps the dial, as under Auto.
        val now = if (lead == null) autoQuotas
            else quotas(p.rowSize, dial.coerceIn(0.0, 1.0), false, p, lean, leadCards = if (discoverNew) p.leanDiscoverCards else p.leanCards)
        return Shape(lean, lead, now, autoQuotas, strictNew, discoverNew, p.rowSize)
    }

    fun build(
        input: EngineInput,
        weights: Weights = Weights.PRIORS,
        p: EngineParams = EngineParams.DEFAULT,
        /**
         * Adventurousness in [0, 1]; the explore share follows it. The default is the slider's
         * default, the middle, two explore cards of twenty.
         */
        dial: Double = 0.5,
        newOnly: Boolean = false,
        /**
         * Only songs with no listen at all, the Discover something new row. Stricter than
         * [newOnly], which counts a song as new until it is heard well, so a song skipped ten
         * seconds in stays new there and would be offered here as something never heard.
         */
        neverPlayed: Boolean = false,
        random: Random = Random.Default,
        /**
         * The share of the similar-song places Last.fm is aimed at, in Both; null draws the row
         * as it always was, which is what YouTube only and Last.fm only do.
         */
        lastFmShare: Double? = null,
        /** The stored Quick picks lean; [Lean.applied] decides whether this build follows it. */
        stored: Lean = Lean.AUTO,
    ): BuiltRow {
        val stats = LibraryStats(input, p)
        val groups = VersionGroups(input.songs.values, input.versionLinks)
        val shape = shape(p, dial, newOnly, neverPlayed, input.chip, stored)
        // No lean to follow: the ordinary row, from the caller's dice as they come.
        if (shape.lean == Lean.AUTO) return checkNotNull(row(input, stats, groups, weights, p, shape, newOnly, neverPlayed, random, lastFmShare))

        // A lean never leaves the listener with less than Auto would. When its lane has nothing to
        // give, the row is Auto's, whole: built from the same dice, so it is the very row a build
        // with no lean would have been, and the heading's "the usual mix for now" is true. When the
        // leaned row comes out too short to be shown and Auto's does not, Auto's is the row as well.
        // A leaned row long enough to show stands, even where Auto's would have been longer.
        val dice = RememberedDice(random)
        val leaned = row(input, stats, groups, weights, p, shape, newOnly, neverPlayed, dice, lastFmShare)
        if (leaned != null && leaned.cards.size >= p.minCards) return leaned
        val auto = checkNotNull(row(input, stats, groups, weights, p, shape(p, dial, newOnly, neverPlayed, input.chip), newOnly, neverPlayed, dice.again(), lastFmShare))
        return when {
            leaned == null -> auto.copy(gaveWay = LeanGaveWay(shape.lean, nothing = true))
            auto.cards.size >= p.minCards -> auto.copy(gaveWay = LeanGaveWay(shape.lean, nothing = leaned.cards.isEmpty()))
            else -> leaned
        }
    }

    /**
     * Dice that remember every throw, so that a second row can be built from the very same ones.
     * The engine only ever asks its dice for a double.
     */
    private class RememberedDice(private val from: Random, private val first: List<Double> = emptyList()) : Random() {
        private val thrown = ArrayList<Double>()
        private var at = 0
        override fun nextBits(bitCount: Int): Int = from.nextBits(bitCount)
        override fun nextDouble(): Double = (if (at < first.size) first[at++] else from.nextDouble()).also { thrown += it }

        /** The throws so far once more from the first, and then on from where the dice stand. */
        fun again() = RememberedDice(from, thrown.toList())
    }

    /**
     * The row for one [shape]. Null when the shape has a lead lane and that lane has no song to
     * give, which is found out before anything is placed; never null for a shape with no lead lane.
     */
    private fun row(
        input: EngineInput,
        stats: LibraryStats,
        groups: VersionGroups,
        weights: Weights,
        p: EngineParams,
        shape: Shape,
        newOnly: Boolean,
        neverPlayed: Boolean,
        random: Random,
        lastFmShare: Double?,
    ): BuiltRow? {
        val day = 86_400_000.0
        val lean = shape.lean

        // Referrers first, since seeds are scored with the same features as everything else.
        val edgesBySeed = input.edges.groupBy { it.seedId }
        val referrersAll = HashMap<String, MutableList<String>>()
        for (e in input.edges) referrersAll.getOrPut(e.songId) { ArrayList() }.add(e.seedId)
        val ctxAll = Features.Context(stats, groups, input, referrersAll, p)
        val sampler = SeedSampler(input, stats, ctxAll, p, random, lean)
        val seeds = sampler.sample()
        val nowSeeds: Set<String> = sampler.nowSeeds
        val seedSet = seeds.toHashSet()
        val lightlyPlayed = stats.songs.entries.filter { it.value.goodListens in 1..2 }.map { it.key }

        // Which lists proposed each related and explore candidate. A seed with lists from both
        // sources is where the two compete for the same places, which is the only kind of card
        // that says anything about which one the listener prefers. In YouTube only and Last.fm
        // only no seed has both, so no card is ever marked contested.
        val listsOf = HashMap<String, Int>()
        for (e in input.edges) listsOf[e.seedId] = (listsOf[e.seedId] ?: 0) or (e.sources and Provenance.SOURCES)
        fun proposedBy(from: Iterable<String>): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (s in from) {
                val c = if (listsOf[s] == Provenance.SOURCES) Provenance.CONTESTED else 0
                for (e in edgesBySeed[s].orEmpty()) out[e.songId] = (out[e.songId] ?: 0) or (e.sources and Provenance.SOURCES) or c
            }
            return out
        }
        val relatedBits = proposedBy(seeds)
        val exploreBits = proposedBy(seeds + lightlyPlayed)

        // What no card may share a group with: a seed, anything started this session, anything
        // heard well in the last few hours. Beyond those hours a song heard well is what the
        // Again lane is for: what the listener comes back to, with satiation as the brake.
        val excludedGroups = HashSet<String>()
        seeds.forEach { excludedGroups += groups.groupOf(it) }
        val justPlayedCut = input.now - p.engineFreshHours * 3_600_000.0
        val session = currentSessionId(input.listens)
        for (l in input.listens) {
            if (l.sessionId == session && input.now - l.endedAt <= p.sessionGapMs) { excludedGroups += groups.groupOf(l.songId); continue }
            if (l.startedAt >= justPlayedCut) {
                val liked = input.songs[l.songId]?.likedAt?.takeIf { l.songId !in stats.bulkLikeSongIds }
                if (Signals.engagement(l, liked, p) >= p.justPlayedEngagement) excludedGroups += groups.groupOf(l.songId)
            }
        }
        val bannedArtists = input.exclusions.filter { it.kind == 2 }.mapTo(HashSet()) { it.targetId }

        // A permanent ban is matched by title AND artist, not by version group.
        //
        // The exclusions above are all about this build: a seed, something just played, something
        // in the current session. For those, VersionGroups ignoring the artist is right, and it
        // says why: a false merge costs one card for one build, a false split puts two versions of
        // a song side by side. But a kind-1 exclusion is "not this song", ever, and it never
        // expires. At that lifetime a false merge costs a song the listener never banned, hidden
        // from every lane of every future row, with one entry in the Exclusions screen naming the
        // other song and no way to tell what else went with it.
        //
        // Groups union on equal base titles with no artist term, and titles recur across artists
        // constantly: Alone, Home, Angel, Runaway, Faded, Paradise, Sunflower. Banning one
        // Sunflower banned the other. TidyPass has always used versionKey, which carries the
        // artist, for exactly this, so the two halves of Home disagreed about what one tap meant.
        // They agree now.
        val bannedKeys = (input.exclusions.filter { it.kind == 1 }.map { it.targetId } + input.banned)
            .mapNotNullTo(HashSet()) { id ->
                input.songs[id]?.let { versionKey(it.title, it.artistName) }
            }
        val bannedIds = (input.exclusions.filter { it.kind == 1 }.map { it.targetId } + input.banned)
            .toHashSet()

        fun eligible(id: String): Boolean {
            val s = input.songs[id] ?: return false
            if (!s.playable) return false
            if (s.artistId != null && s.artistId in bannedArtists) return false
            // By id as well as by key, so a ban still holds on a song whose row has since gone.
            if (id in bannedIds || versionKey(s.title, s.artistName) in bannedKeys) return false
            return groups.groupOf(id) !in excludedGroups
        }

        // Referrers restricted to this build's seeds, which is what x_seed means.
        val referrers = HashMap<String, MutableList<String>>()
        for (seed in seeds) for (e in edgesBySeed[seed].orEmpty()) referrers.getOrPut(e.songId) { ArrayList() }.add(seed)
        val ctx = Features.Context(stats, groups, input, referrers, p)
        val cache = HashMap<String, DoubleArray>()
        fun x(id: String) = cache.getOrPut(id) { Features.of(id, ctx) }

        fun candidate(id: String, lane: Lane): Candidate? {
            if (!eligible(id)) return null
            val f = x(id)
            // Under Playing now a related card names a seed from the session under way, which is
            // what its caption says and what the per-seed cap counts.
            val refs = referrers[id].orEmpty().let { r -> if (lean == Lean.SIMILAR && lane == Lane.RELATED) r.filter { it in nowSeeds } else r }
            val seed = refs.maxByOrNull { stats.seedWeight(it) }
            val sources = when (lane) {
                Lane.RELATED -> relatedBits[id] ?: 0
                Lane.EXPLORE -> exploreBits[id] ?: 0
                else -> 0
            }
            return Candidate(id, lane, f, Scorer.z(f, weights), seed, input.songs[id]?.artistId, groups.groupOf(id), sources)
        }

        // Related: the seeds' candidates, distinct. Under Playing now only what the session under
        // way refers is related, since that is the promise.
        val relatedIds = if (lean == Lean.SIMILAR) referrers.filterValues { r -> r.any { it in nowSeeds } }.keys else referrers.keys
        val related = relatedIds.mapNotNull { candidate(it, Lane.RELATED) }.sortedByDescending { it.z }

        // Artist: the strongest artists, sampled by the square root of their strength, then their
        // library, liked or cached songs not heard for a while. Under Your artists only artists
        // the listener keeps coming back to: heard well in several sessions on more than one day
        // inside the context window, so one night of a single album does not make one.
        val returning: Set<String>? = if (lean == Lean.ARTIST) returningArtists(input, stats, p) else null
        val artistPool = stats.artists.entries.filter { it.value.strength > 0 && it.key !in bannedArtists && (returning == null || it.key in returning) }
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
        // Under Your artists the source is every song of theirs the table holds, heard or not,
        // since "more from" is the promise. It is written out as a clause of its own so that
        // tightening the clause after it can never empty the lean.
        val artistLane = input.songs.values
            .filter { it.artistId != null && it.artistId in artistRank && (lean == Lean.ARTIST || it.inLibrary || it.liked || true) }
            .filter { (stats.songs[it.id]?.lastStartedAt ?: 0L) < quietCut }
            .mapNotNull { candidate(it.id, Lane.ARTIST) }
            .sortedWith(compareByDescending<Candidate> { it.z }.thenBy { artistRank[it.artistId] ?: Int.MAX_VALUE })
        // Someone who keeps coming back to only a few artists still gets the lean's cards: up to
        // [EngineParams.leanLeadArtistCapMax] apiece instead of two, still one an artist a column.
        val leadArtistCap = if (lean != Lean.ARTIST || shape.lead == null) p.maxPerArtist else leadArtistCap(artistLane.mapNotNullTo(HashSet()) { it.artistId }.size, p)

        // Again: heard well inside the window but not inside the fresh hours, strongest first.
        val againCut = input.now - p.againWindowDays * day
        val again = stats.songs.entries
            .filter { it.value.lastGoodAt in againCut.toLong()..(justPlayedCut.toLong()) }
            .sortedByDescending { it.value.activation }.take(200)
            .mapNotNull { candidate(it.key, Lane.AGAIN) }
            .sortedByDescending { it.z }

        // Rediscover: dormant songs with something left in them, the strongest two hundred by old
        // activation. Under Forgotten the dormancy is checked before the two hundred are taken,
        // and a song must have been loved (liked, or heard well a few times), not heard well for
        // the dormancy window and not even started for a fortnight, so "forgot about" is true.
        val dormantCut = input.now - p.dormantAfterDays * day
        val forgotten = lean == Lean.FORGOTTEN
        val rediscover = stats.songs.entries.filter { it.value.oldActivation > 0 && (!forgotten || (it.value.lastGoodAt < dormantCut && it.value.lastStartedAt < quietCut)) }
            .sortedByDescending { it.value.oldActivation }.take(200)
            .mapNotNull { candidate(it.key, Lane.REDISCOVER) }
            .filter { it.x[Features.DORM] > 0 }
            .filter { !forgotten || input.songs[it.songId]?.liked == true || (stats.songs[it.songId]?.goodListens ?: 0) >= p.leanLovedListens }
            .sortedByDescending { it.z }

        // Explore: new to the listener, from the related rows of the seeds and of lightly played
        // songs, and library songs never played; topped up with new songs by known artists.
        val exploreSource = HashSet<String>()
        (seeds + lightlyPlayed).forEach { s -> edgesBySeed[s]?.forEach { exploreSource += it.songId } }
        input.songs.values.filter { it.inLibrary && stats.songs[it.id] == null }.forEach { exploreSource += it.id }
        val exploreAll = exploreSource.mapNotNull { candidate(it, Lane.EXPLORE) }
        val quotasNow = shape.quotas
        val lead = shape.lead

        // Never heard, for the lean: no listen of any length, and not liked or in the library,
        // which say the listener knows the song whatever InterTune has seen of it.
        fun neverHeard(c: Candidate) = stats.songs[c.songId] == null && input.songs[c.songId]?.let { !it.liked && !it.inLibrary } == true
        var explore = exploreAll.filter { it.x[Features.NOVEL] >= 1.0 }.sortedByDescending { it.z }
        if (lean == Lean.NEW) {
            // New artists first, as the explore lane always did, and songs by artists already
            // heard only to top it up, never under Discover. Ranked by score inside each tier, so
            // x_art cannot pull a known artist ahead of a new one. Trimmed, since the lane can hold
            // ten thousand songs and the assembly reads it whole on every pick.
            val tier1 = explore.filter(::neverHeard)
            val want = quotasNow[Lane.EXPLORE] ?: 0
            explore = (if (shape.discoverNew || tier1.size >= 2 * want) tier1
                else tier1 + exploreAll.filter { it.x[Features.NOVEL] == 0.5 && neverHeard(it) }.sortedByDescending { it.z }).take(p.leanLaneTrim)
        } else if (explore.size < 2 * (quotasNow[Lane.EXPLORE] ?: 0)) {
            explore = (explore + exploreAll.filter { it.x[Features.NOVEL] == 0.5 }.sortedByDescending { it.z })
        }

        // Never played: no listen of any length, which the stats hold for every listen there is,
        // the event log backfilled from earlier versions included.
        fun unheard(c: Candidate) = stats.songs[c.songId] == null
        val lanes = when {
            neverPlayed -> mapOf(Lane.EXPLORE to explore.filter(::unheard), Lane.RELATED to related.filter(::unheard))
            // New songs only under Never heard: its stricter rule for the whole row.
            shape.strictNew -> mapOf(Lane.EXPLORE to explore, Lane.RELATED to related.filter(::neverHeard))
            newOnly -> mapOf(Lane.EXPLORE to explore.filter { it.x[Features.NOVEL] >= 0.5 }, Lane.RELATED to related.filter { it.x[Features.NOVEL] >= 0.5 })
            else -> mapOf(Lane.RELATED to related, Lane.AGAIN to again, Lane.ARTIST to artistLane, Lane.REDISCOVER to rediscover, Lane.EXPLORE to explore)
        }.let { m -> if (lead == null || lead == Lane.EXPLORE) m else m + (lead to m[lead].orEmpty().take(p.leanLaneTrim)) }
        // The lean has nothing to give: the caller builds Auto's row instead.
        if (lead != null && lanes[lead].isNullOrEmpty()) return null
        // A graph with nothing from Last.fm in the similar-song lanes draws exactly as it would
        // without a split: no ledger and no extra draw.
        val split = lastFmShare?.takeIf { (lanes[Lane.RELATED].orEmpty() + lanes[Lane.EXPLORE].orEmpty()).any { it.sources and Provenance.LASTFM != 0 } }
        // Under Playing now a seed may refer more than three, so twelve cards can come from a short session.
        val ap = if (lean == Lean.SIMILAR && lead != null) p.copy(maxPerSeed = maxOf(p.leanSimilarMaxPerSeed, ceil((quotasNow[Lane.RELATED] ?: 0).toDouble() / maxOf(1, nowSeeds.size)).toInt())) else p
        val placed = Assembly(lanes, quotasNow, weights, ap, random, split, lead, leadArtistCap).run()
        val inRow = placed.mapTo(HashSet()) { it.first.songId }
        fun reasonsOf(c: Candidate, sampled: Boolean): List<String> {
            // A lead card says first why it is the lean's, in its own lane's words.
            val own = when (c.lane) { Lane.ARTIST -> "x_art"; Lane.REDISCOVER -> "x_dorm"; Lane.RELATED -> "x_seed"; else -> null }
            return when {
                c.lane == Lane.EXPLORE -> listOf("new_to_you")
                lead != null && c.lane == lead && own != null -> listOf(own) + Scorer.reasons(c.x, weights).filter { it != own }.take(1)
                sampled -> listOf("wildcard") + Scorer.reasons(c.x, weights).take(1)
                else -> Scorer.reasons(c.x, weights)
            }
        }
        val cards = placed.mapIndexed { slot, (c, sampled) ->
            Card(c.songId, c.lane, c.z, Scorer.p(c.x, weights, c.lane, slot, p.columns), c.x, reasonsOf(c, sampled), c.seedId, sampled, c.sources)
        }
        // Survivors, best first, one per version group, for replacements between builds.
        val poolGroups = HashSet<String>(placed.map { it.first.group })
        val pool40 = lanes.values.flatten().filter { it.songId !in inRow }.sortedByDescending { it.z }
            .filter { poolGroups.add(it.group) }.take(40)
        // Under a lean, a few more of the lead lane after the forty, for the tidy pass to put in
        // place of a lead card it drops. Never pool picks: those are only ever the first forty.
        // A set of its own, since the filter above has run over every lane, not only the forty.
        val spares = if (lead == null) emptyList() else {
            val taken = HashSet<String>().apply { placed.forEach { add(it.first.group) }; pool40.forEach { add(it.group) } }
            lanes[lead].orEmpty().filter { it.songId !in inRow && taken.add(it.group) }.take(p.leanSpares)
        }
        fun poolCard(c: Candidate) = Card(c.songId, c.lane, c.z, Scorer.p(c.x, weights, c.lane, p.rowSize, p.columns), c.x,
            if (lead != null && c.lane == lead) reasonsOf(c, false) else Scorer.reasons(c.x, weights), c.seedId, false, c.sources)
        val pool = (pool40 + spares).map(::poolCard)
        return BuiltRow(
            cards, seeds, pool, quotasNow, split,
            lean = if (lead == null) Lean.AUTO else lean,
            leanPlaced = if (lead == null) 0 else placed.count { it.first.lane == lead },
            leadArtistCap = leadArtistCap,
            leadWeight = shape.leadWeight,
        )
    }

    /**
     * Artists the listener keeps coming back to: heard well in at least [EngineParams.leanReturnSessions]
     * sessions on at least [EngineParams.leanReturnDays] local days inside the context window.
     */
    fun returningArtists(input: EngineInput, stats: LibraryStats, p: EngineParams = EngineParams.DEFAULT): Set<String> {
        val cut = input.now - p.contextWindowDays * 86_400_000.0
        val sessions = HashMap<String, HashSet<Long>>()
        val days = HashMap<String, HashSet<Long>>()
        for (l in input.listens) {
            if (l.startedAt < cut || !l.learn) continue
            val artist = input.songs[l.songId]?.artistId ?: continue
            val liked = input.songs[l.songId]?.likedAt?.takeIf { l.songId !in stats.bulkLikeSongIds }
            if (Signals.engagement(l, liked, p) < p.justPlayedEngagement) continue
            sessions.getOrPut(artist) { HashSet() } += l.sessionId
            days.getOrPut(artist) { HashSet() } += Math.floorDiv(l.startedAt + l.tzOffsetMin * 60_000L, 86_400_000L)
        }
        return sessions.keys.filterTo(HashSet()) { (sessions[it]?.size ?: 0) >= p.leanReturnSessions && (days[it]?.size ?: 0) >= p.leanReturnDays }
    }

    /** Cards one returning artist may hold in the Your artists lane: enough for the lean's twelve, between two and the maximum. */
    fun leadArtistCap(artists: Int, p: EngineParams = EngineParams.DEFAULT): Int =
        if (artists <= 0) p.maxPerArtist else ceil(p.leanCards.toDouble() / artists).toInt().coerceIn(p.maxPerArtist, maxOf(p.maxPerArtist, p.leanLeadArtistCapMax))
}
