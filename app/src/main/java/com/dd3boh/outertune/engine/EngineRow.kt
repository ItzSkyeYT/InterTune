/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.random.Random

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

    fun build(
        input: EngineInput,
        weights: Weights = Weights.PRIORS,
        p: EngineParams = EngineParams.DEFAULT,
        /** Adventurousness in [0, 1]; the explore share follows it. */
        dial: Double = 0.15,
        newOnly: Boolean = false,
        random: Random = Random.Default,
    ): BuiltRow {
        val stats = LibraryStats(input, p)
        val groups = VersionGroups(input.songs.values, input.versionLinks)
        val day = 86_400_000.0

        // Referrers first, since seeds are scored with the same features as everything else.
        val edgesBySeed = input.edges.groupBy { it.seedId }
        val referrersAll = HashMap<String, MutableList<String>>()
        for (e in input.edges) referrersAll.getOrPut(e.songId) { ArrayList() }.add(e.seedId)
        val ctxAll = Features.Context(stats, groups, input, referrersAll, p)
        val seeds = SeedSampler(input, stats, ctxAll, p, random).sample()
        val seedSet = seeds.toHashSet()

        // What no card may share a group with: a seed, anything started this session, anything heard well in the last day.
        val excludedGroups = HashSet<String>()
        seeds.forEach { excludedGroups += groups.groupOf(it) }
        val justPlayedCut = input.now - p.justPlayedHours * 3_600_000.0
        for (l in input.listens) {
            if (l.sessionId == stats.latestSessionId && input.now - l.endedAt <= p.sessionGapMs) { excludedGroups += groups.groupOf(l.songId); continue }
            if (l.startedAt >= justPlayedCut) {
                val liked = input.songs[l.songId]?.likedAt?.takeIf { l.songId !in stats.bulkLikeSongIds }
                if (Signals.engagement(l, liked, p) >= p.justPlayedEngagement) excludedGroups += groups.groupOf(l.songId)
            }
        }
        val bannedArtists = input.exclusions.filter { it.kind == 2 }.mapTo(HashSet()) { it.targetId }
        input.exclusions.filter { it.kind == 1 }.forEach { excludedGroups += groups.groupOf(it.targetId) }
        input.banned.forEach { excludedGroups += groups.groupOf(it) }

        fun eligible(id: String): Boolean {
            val s = input.songs[id] ?: return false
            if (!s.playable) return false
            if (s.artistId != null && s.artistId in bannedArtists) return false
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
            val seed = referrers[id]?.maxByOrNull { stats.seedWeight(it) }
            return Candidate(id, lane, f, Scorer.z(f, weights), seed, input.songs[id]?.artistId, groups.groupOf(id))
        }

        // Related: the seeds' candidates, distinct.
        val related = referrers.keys.mapNotNull { candidate(it, Lane.RELATED) }.sortedByDescending { it.z }

        // Artist: the strongest artists, sampled by the square root of their strength, then their
        // library, liked or cached songs not heard for a while.
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

        // Rediscover: dormant songs with something left in them, the strongest two hundred by old activation.
        val rediscover = stats.songs.entries.filter { it.value.oldActivation > 0 }
            .sortedByDescending { it.value.oldActivation }.take(200)
            .mapNotNull { candidate(it.key, Lane.REDISCOVER) }
            .filter { it.x[Features.DORM] > 0 }
            .sortedByDescending { it.z }

        // Explore: new to the listener, from the related rows of the seeds and of lightly played
        // songs, and library songs never played; topped up with new songs by known artists.
        val lightlyPlayed = stats.songs.entries.filter { it.value.goodListens in 1..2 }.map { it.key }
        val exploreSource = HashSet<String>()
        (seeds + lightlyPlayed).forEach { s -> edgesBySeed[s]?.forEach { exploreSource += it.songId } }
        input.songs.values.filter { it.inLibrary && stats.songs[it.id] == null }.forEach { exploreSource += it.id }
        val exploreAll = exploreSource.mapNotNull { candidate(it, Lane.EXPLORE) }
        val quotasNow = quotas(p.rowSize, dial, newOnly, p)
        var explore = exploreAll.filter { it.x[Features.NOVEL] >= 1.0 }.sortedByDescending { it.z }
        if (explore.size < 2 * (quotasNow[Lane.EXPLORE] ?: 0)) {
            explore = (explore + exploreAll.filter { it.x[Features.NOVEL] == 0.5 }.sortedByDescending { it.z })
        }

        val lanes = if (newOnly) mapOf(Lane.EXPLORE to explore.filter { it.x[Features.NOVEL] >= 0.5 }, Lane.RELATED to related.filter { it.x[Features.NOVEL] >= 0.5 })
        else mapOf(Lane.RELATED to related, Lane.ARTIST to artistLane, Lane.REDISCOVER to rediscover, Lane.EXPLORE to explore)
        val placed = Assembly(lanes, quotasNow, weights, p, random).run()
        val inRow = placed.mapTo(HashSet()) { it.first.songId }
        val cards = placed.mapIndexed { slot, (c, sampled) ->
            val reasons = when {
                c.lane == Lane.EXPLORE -> listOf("new_to_you")
                sampled -> listOf("wildcard") + Scorer.reasons(c.x, weights).take(1)
                else -> Scorer.reasons(c.x, weights)
            }
            Card(c.songId, c.lane, c.z, Scorer.p(c.x, weights, c.lane, slot, p.columns), c.x, reasons, c.seedId, sampled)
        }
        // Survivors, best first, one per version group, for replacements between builds.
        val poolGroups = HashSet<String>(placed.map { it.first.group })
        val pool = lanes.values.flatten().filter { it.songId !in inRow }.sortedByDescending { it.z }
            .filter { poolGroups.add(it.group) }.take(40)
            .map { c -> Card(c.songId, c.lane, c.z, Scorer.p(c.x, weights, c.lane, p.rowSize, p.columns), c.x, Scorer.reasons(c.x, weights), c.seedId, false) }
        return BuiltRow(cards, seeds, pool, quotasNow)
    }
}
