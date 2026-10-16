/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * The order of Familiar first's songs when [EngineParams.familiarRanker] is on: six signals of a
 * song's own listening, weighed the way a ranker fitted on 474 Last.fm listeners weighed them.
 * Scored on 467 others at the start of each session, it held the first song more often than the
 * steeper recency alone, 23.9 against 22.3 in 100, better for 55 percent of them and worse for 24
 * (experiments/features/recs-bakeoff/bk_ablation.py, 16 Oct 2026).
 *
 * The signals, all over listens heard well, as a scrobble is one: how recent its listens are
 * (ACT-R at decay 0.5), how many listens, in how many sessions, a week's half-life, the hours
 * since the last, and how often it came straight after the song heard last, inside one session.
 * That last one is most of the gain: what someone plays next is often what they always play after
 * the song they stopped on. With more listens of the same weight the score goes down; a song
 * heard in many sessions beats one played over and over in a few.
 */
object FamiliarRanker {
    /** The signals' means and spreads on the listeners it was fitted on, and their weights. */
    private val MU = doubleArrayOf(-0.936429, 2.370260, 2.103156, 0.720397, 4.968673, 0.008793)
    private val SD = doubleArrayOf(1.017728, 1.152079, 1.002115, 0.645959, 1.970167, 0.074007)
    private val W = doubleArrayOf(0.715601, -0.739194, 0.035684, 0.340665, -0.085213, 0.265907)
    private const val BIAS = -3.407359
    private const val HOUR = 3_600_000.0
    private const val WEEK = 7 * 86_400_000.0

    private class Tally {
        var act = 0.0; var plays = 0; var sessions = 0; var week = 0.0; var lastEnd = Long.MIN_VALUE; var lastSession = Long.MIN_VALUE
    }

    /** Every song heard well before [EngineInput.now], with its score; higher first. */
    fun scores(input: EngineInput, p: EngineParams = EngineParams.DEFAULT): Map<String, Double> {
        val now = input.now
        val good = input.listens
            .filter { it.learn && it.endedAt <= now && Signals.engagement(it, input.songs[it.songId]?.likedAt, p) >= p.justPlayedEngagement }
            .sortedBy { it.startedAt }
        if (good.isEmpty()) return emptyMap()
        val last = good.last().songId
        val next = HashMap<String, Int>()
        val tally = HashMap<String, Tally>()
        for ((i, l) in good.withIndex()) {
            val t = tally.getOrPut(l.songId) { Tally() }
            val age = (now - l.endedAt).toDouble()
            t.plays++
            t.act += (age / HOUR + 1.0).pow(-0.5)
            t.week += 2.0.pow(-age / WEEK)
            if (l.endedAt > t.lastEnd) t.lastEnd = l.endedAt
            // A song's listens of one session sit together in time, so a change of session id is a new session.
            if (l.sessionId != t.lastSession) { t.sessions++; t.lastSession = l.sessionId }
            if (i > 0) {
                val prev = good[i - 1]
                if (prev.sessionId == l.sessionId && prev.songId == last && l.songId != last) next.merge(l.songId, 1, Int::plus)
            }
        }
        val nextTotal = next.values.sum().toDouble()
        return tally.mapValues { (id, t) ->
            val x = doubleArrayOf(
                ln(t.act + 1e-4),
                ln1p(t.plays.toDouble()),
                ln1p(t.sessions.toDouble()),
                ln1p(t.week),
                ln1p((now - t.lastEnd) / HOUR),
                if (nextTotal > 0) (next[id] ?: 0) / nextTotal else 0.0,
            )
            var s = BIAS
            for (k in x.indices) s += W[k] * (x[k] - MU[k]) / SD[k]
            s
        }
    }
}
