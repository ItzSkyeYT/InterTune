/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.PlayOrigin
import kotlin.random.Random

/**
 * Which songs the row is built around. Up to sixteen, drawn without replacement from five pools
 * so that no single thread of the day owns the row: what is playing now, what played earlier
 * today, what is strongest over time, what was searched for, and what was liked. A worn-out song
 * seeds weakly rather than being barred, and a song that seeded a row in the last few hours
 * seeds weakly again, so the related lane drifts to another theme by itself.
 */
class SeedSampler(
    private val input: EngineInput,
    private val stats: LibraryStats,
    private val features: Features.Context,
    private val p: EngineParams = EngineParams.DEFAULT,
    private val random: Random = Random.Default,
) {
    private val day = 86_400_000.0

    /** A song's willingness to seed: worn out or recently used, it steps back. */
    private fun damping(songId: String): Double {
        val x = Features.of(songId, features)
        var w = 1 - x[Features.SAT]
        val recent = input.now - p.seedDampHours * 3_600_000.0
        for (past in input.pastSeeds) if (past.builtAt >= recent && songId in past.seedIds) w *= p.seedDamp
        return w
    }

    /** Draws without replacement, proportional to weight, from what is not taken yet. */
    private fun draw(pool: Map<String, Double>, count: Int, taken: MutableSet<String>): List<String> {
        val out = ArrayList<String>()
        val remaining = pool.filterKeys { it !in taken }.filterValues { it > 0 }.toMutableMap()
        repeat(count) {
            if (remaining.isEmpty()) return@repeat
            val total = remaining.values.sum()
            var r = random.nextDouble() * total
            var chosen = remaining.keys.first()
            for ((id, w) in remaining) { r -= w; if (r <= 0) { chosen = id; break } }
            remaining.remove(chosen); taken.add(chosen); out.add(chosen)
        }
        return out
    }

    fun sample(): List<String> {
        val taken = HashSet<String>(input.notSeeds)
        val goodListens = input.listens.filter { l ->
            val liked = input.songs[l.songId]?.likedAt?.takeIf { l.songId !in stats.bulkLikeSongIds }
            l.learn && Signals.engagement(l, liked, p) >= p.justPlayedEngagement
        }
        val latestSession = stats.latestSessionId
        val localMidnight = run {
            val off = input.tzOffsetMin * 60_000L
            Math.floorDiv(input.now + off, 86_400_000L) * 86_400_000L - off
        }
        // Now: the current or latest session, weighted by recency.
        val now = HashMap<String, Double>()
        for (l in goodListens) if (l.sessionId == latestSession) now.merge(l.songId, Signals.recency(input.now, l.startedAt), ::maxOf)
        // Today: since local midnight, outside that session, so a lunch break does not erase the morning.
        val today = HashMap<String, Double>()
        for (l in goodListens) if (l.sessionId != latestSession && l.startedAt >= localMidnight) today.merge(l.songId, Signals.recency(input.now, l.startedAt), ::maxOf)
        // Strong: the highest activation, half judged over this day part when it has enough listens.
        val byActivation = stats.songs.entries.filter { it.value.activation > 0 }.sortedByDescending { it.value.activation }
        val strongAll = byActivation.take(p.strongPool).associate { it.key to it.value.activation }
        val bucketRich = stats.goodByBucket[input.bucket] >= p.contextMinListens
        val strongBucket = if (bucketRich) stats.songs.entries.filter { it.value.bucketActivation > 0 }
            .sortedByDescending { it.value.bucketActivation }.take(p.strongPool).associate { it.key to it.value.bucketActivation } else strongAll
        // Searched: an unordered set, so the newest query never dominates.
        val searchedCut = input.now - p.searchedDays * day
        val searched = HashMap<String, Double>()
        for (l in input.listens) {
            if (l.autoplayDepth != 0 || l.startedAt < searchedCut || !l.learn) continue
            val origin = PlayOrigin.fromCode(l.origin)
            if (origin != PlayOrigin.SEARCH && origin != PlayOrigin.RECOGNISED) continue
            val liked = input.songs[l.songId]?.likedAt?.takeIf { l.songId !in stats.bulkLikeSongIds }
            if (Signals.engagement(l, liked, p) >= p.searchedMinEngagement) searched[l.songId] = 1.0
        }
        // Liked: the latest usable likes, and one from long ago.
        val usableLikes = input.songs.values.filter { it.liked && it.likedAt != null && it.id !in stats.bulkLikeSongIds }.sortedByDescending { it.likedAt }
        val likedRecent = usableLikes.take(p.likedRecentPool).associate { it.id to 1.0 }
        val likedOld = usableLikes.filter { it.likedAt!! < input.now - p.likedOldAfterDays * day }.associate { it.id to 1.0 }

        fun damped(pool: Map<String, Double>) = pool.mapValues { (id, w) -> w * damping(id) }
        val out = ArrayList<String>()
        var carry = 0
        fun take(pool: Map<String, Double>, want: Int) {
            val got = draw(damped(pool), want, taken)
            out += got
            carry += want - got.size
        }
        take(now, p.seedsNow)
        take(today, p.seedsToday)
        take(searched, p.seedsSearched)
        take(likedRecent, p.seedsLikedRecent)
        take(likedOld, p.seedsLikedOld)
        // Strong gets its own share plus whatever the others could not fill, half from this day part.
        val strongWant = p.seedsStrong + carry
        val half = strongWant / 2
        out += draw(damped(strongBucket), half, taken)
        out += draw(damped(strongAll), strongWant - half, taken)
        if (out.size < strongWant + (p.seedsTotal - strongWant - carry)) out += draw(damped(strongAll), p.seedsTotal - out.size, taken)
        return out.take(p.seedsTotal)
    }
}
