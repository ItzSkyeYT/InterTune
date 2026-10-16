/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.min
import kotlin.math.pow

/**
 * The order of Familiar first's songs when [EngineParams.familiarRanker] is on: eighteen signals of
 * a song's own listening, weighed the way a ranker fitted on 474 Last.fm listeners weighed them.
 * Scored on 467 others at the start of each session it held the first song more often than the
 * steeper recency alone, 24.4 against 22.3 in 100, better for 58 percent of them and worse for 21,
 * and on his own history too, where smaller versions of it lost (experiments/features/recs-bakeoff,
 * bk_ablation.py and bk_mel_ablation.py, 16 Oct 2026).
 *
 * Every signal is read from listens heard well, as a scrobble is one: how many, at four half-lives
 * and at ACT-R's decay 0.5, the hours since the last and the days since the first, in how many
 * sessions, whether it was in the last session, what usually comes after the song heard last and
 * after the last session's songs, inside a session and at the start of the next, how often it was
 * heard at this hour and on this kind of day, its artist's weight and its share of that artist.
 * Only the songs of the pool the ranker was fitted on get a score: the strongest hundred by ACT-R
 * and the top twenty by recency, by count, by the six-hour half-life, by hour and day, and by each
 * kind of what-comes-next, as bk_core.pool_of takes them.
 */
object FamiliarRanker {
    /** The signals' means and spreads on the listeners it was fitted on, their weights, and the bias (lr_cross.npz). */
    private val MU = doubleArrayOf(2.3702596914918983, 0.08138188289869357, 0.23614181725338587, 0.7203969003129003, 1.3132196036906636,
        -0.9364288539761304, 4.968673483101638, 4.073967816716761, 0.13220761376563486, 0.008792509061282327, 0.009663784903863918,
        0.009754412862076872, 0.6000024189346124, 0.6319168147516181, 1.6307606008269677, 0.25887800573922365, 2.1031556640058846,
        3.7223950624648303)
    private val SD = doubleArrayOf(1.1520789087579715, 0.2077596771096541, 0.37043176065352046, 0.6459593206470803, 0.8082946620893707,
        1.017728016834342, 1.9701670725522304, 1.7914344642644264, 0.33871734242729734, 0.07400688892426847, 0.046687983595389566,
        0.06022347517754223, 0.22659638803234047, 0.3235597872458719, 1.12946664287638, 0.3266723463886069, 1.0021151765358278,
        0.9866237010789236)
    private val W = doubleArrayOf(-0.8832590788349375, 0.015143302624869367, 0.13114033312345616, 0.3176865447553969, 0.11591779444137046,
        0.5541512566974756, 0.514323934762698, -0.012581918817683776, 0.08016178945383709, 0.2579106074943972, 0.0076163630977200344,
        0.19828525207159522, 0.0641565617107948, 0.01741695942613655, -0.22938455733472632, -0.02717884996415243, 0.3858227024504717,
        -0.506371094523406)
    private const val BIAS = -3.4543192376950183

    private const val HOUR = 3_600_000.0
    private const val DAY = 86_400_000L
    private val HALF_LIVES = doubleArrayOf(6 * HOUR, 24 * HOUR, 7 * 24 * HOUR, 30 * 24 * HOUR)

    private class Song(val artist: String) {
        var plays = 0; var act = 0.0; val decay = DoubleArray(4); var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
        var sessions = 0; var hourFit = 0.0; var dayFit = 0.0; var hourDay = 0.0
    }

    /** Local hour (fractional) and whether it is a weekend, as bk_core.Plays reads them. */
    private fun hourOf(at: Long, tzOffsetMin: Int): Double = Math.floorMod(at + tzOffsetMin * 60_000L, DAY) / HOUR
    private fun weekendOf(at: Long, tzOffsetMin: Int): Boolean = Math.floorMod(Math.floorDiv(at + tzOffsetMin * 60_000L, DAY) + 3, 7L) >= 5

    /** Every song of the pool, with its score; higher first. */
    fun scores(input: EngineInput, p: EngineParams = EngineParams.DEFAULT): Map<String, Double> {
        val now = input.now
        val good = input.listens
            .filter { it.learn && it.endedAt < now && Signals.engagement(it, input.songs[it.songId]?.likedAt, p) >= p.justPlayedEngagement }
            .sortedWith(compareBy<ListenRow> { it.endedAt }.thenBy { it.startedAt })
        if (good.isEmpty()) return emptyMap()
        val hourNow = hourOf(now, input.tzOffsetMin)
        val weekendNow = weekendOf(now, input.tzOffsetMin)

        // The last session's songs, most recent first, and the weight each gives what comes after it.
        val lastSession = good.last().sessionId
        val order = good.filter { it.sessionId == lastSession }.map { it.songId }.asReversed().distinct()
        val weight = order.withIndex().associate { (r, id) -> id to 1.0 / (1 + r) }

        val songs = HashMap<String, Song>()
        val artistAct = HashMap<String, Double>()
        val artistPlays = HashMap<String, Int>()
        val within = HashMap<String, HashMap<String, Double>>()   // from a song of the last session to what came straight after it
        val across = HashMap<String, HashMap<String, Double>>()   // from a song of a session to the first song of the next
        var sessionSet = HashSet<String>()
        for ((i, l) in good.withIndex()) {
            val id = l.songId
            val artist = input.songs[id]?.artistId ?: "song:$id"
            val s = songs.getOrPut(id) { Song(artist) }
            val age = (now - l.endedAt).toDouble()
            val pw = (age / HOUR + 1.0).pow(-0.5)
            s.plays++; s.act += pw
            for (k in HALF_LIVES.indices) s.decay[k] += 2.0.pow(-age / HALF_LIVES[k])
            s.first = min(s.first, l.endedAt); s.last = maxOf(s.last, l.endedAt)
            var dh = kotlin.math.abs(hourOf(l.endedAt, l.tzOffsetMin) - hourNow)
            dh = min(dh, 24 - dh)
            val k = (1 + cos(2 * PI * dh / 24)) / 2
            val same = if (weekendOf(l.endedAt, l.tzOffsetMin) == weekendNow) 1.0 else 0.0
            s.hourFit += k; s.dayFit += same
            s.hourDay += pw * (0.1 + 0.9 * k) * (0.5 + 0.5 * same)
            artistAct.merge(artist, pw, Double::plus); artistPlays.merge(artist, 1, Int::plus)
            val newSession = i == 0 || good[i - 1].sessionId != l.sessionId
            if (newSession) {
                for (a in sessionSet) if (a in weight) across.getOrPut(a) { HashMap() }.merge(id, 1.0, Double::plus)
                sessionSet = HashSet()
            } else {
                val y = good[i - 1].songId
                if (y != id && y in weight) within.getOrPut(y) { HashMap() }.merge(id, 1.0, Double::plus)
            }
            if (sessionSet.add(id)) s.sessions++
        }

        fun spread(table: Map<String, Map<String, Double>>, sources: List<String>): Map<String, Double> {
            val out = HashMap<String, Double>()
            var total = 0.0
            for (a in sources) {
                val row = table[a] ?: continue
                if (row.isEmpty()) continue
                val w = if (sources.size == 1) 1.0 else weight.getValue(a)
                val sum = row.values.sum()
                total += w
                for ((b, c) in row) out.merge(b, w * c / sum, Double::plus)
            }
            return if (total > 0) out.mapValues { it.value / total } else out
        }
        val next = spread(within, order.take(1))
        val afterSession = spread(within, order)
        val nextSession = spread(across, order)

        fun recent(s: Song): Double = 1.0 + (s.last - now) / (1e3 * 365 * DAY)
        fun top(n: Int, score: (String, Song) -> Double): List<String> =
            songs.entries.map { it.key to score(it.key, it.value) }.filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(n).map { it.first }
        val pool = (top(100) { _, s -> s.act } + top(20) { _, s -> recent(s) } + top(20) { _, s -> s.plays + 0.5 * recent(s) } +
            top(20) { _, s -> s.decay[0] } + top(20) { _, s -> s.hourDay } + top(20) { id, _ -> next[id] ?: 0.0 } +
            top(20) { id, _ -> afterSession[id] ?: 0.0 } + top(20) { id, _ -> nextSession[id] ?: 0.0 }).toSortedSet().toList()
        val rank = pool.sortedWith(compareByDescending<String> { recent(songs.getValue(it)) }).withIndex().associate { (r, id) -> id to r }

        return pool.associateWith { id ->
            val s = songs.getValue(id)
            val x = doubleArrayOf(
                ln1p(s.plays.toDouble()),
                ln1p(s.decay[0]), ln1p(s.decay[1]), ln1p(s.decay[2]), ln1p(s.decay[3]),
                ln(s.act + 1e-4),
                ln1p((now - s.last) / HOUR),
                ln1p((now - s.first).toDouble() / DAY),
                if (id in weight) 1.0 else 0.0,
                next[id] ?: 0.0, afterSession[id] ?: 0.0, nextSession[id] ?: 0.0,
                s.hourFit / maxOf(s.plays, 1), s.dayFit / maxOf(s.plays, 1),
                ln1p(artistAct.getValue(s.artist)),
                s.plays.toDouble() / maxOf(artistPlays.getValue(s.artist), 1),
                ln1p(s.sessions.toDouble()),
                ln1p(rank.getValue(id).toDouble()),
            )
            var score = BIAS
            for (k in x.indices) score += W[k] * (x[k] - MU[k]) / SD[k]
            score
        }
    }
}
