/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlin.random.Random

/**
 * Learning from a history that was never shown a row: a restored backup, or the play log of the
 * versions before the engine existed. Session by session, oldest first, the row is built from what
 * existed before the session began and graded against what was then played, as if it had been on
 * screen: a card whose song (by version group) was heard well in the session is a win at that
 * engagement, every other card a card passed over. The weights step from each, inside their
 * bounds, without the daily budget, since this is one pass over what already happened.
 *
 * The grade is a proxy: nothing was shown, so a song not played was not necessarily refused. That
 * is why it runs only while the weights are untouched, and why the real loop takes over from the
 * first real card.
 */
object WarmStart {
    data class Result(val weights: Map<String, Double>, val sessions: Int, val examples: Int)

    fun run(
        input: EngineInput,
        p: EngineParams = EngineParams.DEFAULT,
        maxSessions: Int = 60,
        initial: Map<String, Double> = emptyMap(),
        skipFirst: Int = 5,
        /** What a card not played weighs; 0 learns from the plays alone, since nothing was refused. */
        ignoredWeight: Double = 0.3,
        onProgress: ((done: Int, of: Int) -> Unit)? = null,
    ): Result {
        val learner = Learner(initial, p)
        val listens = input.listens.filter { it.learn }.sortedBy { it.startedAt }
        val sessions = listens.groupBy { it.sessionId }.values.sortedBy { it.first().startedAt }
        if (sessions.size <= skipFirst) return Result(learner.asMap(), 0, 0)
        val chosen = sessions.drop(skipFirst).takeLast(maxSessions)
        val groups = VersionGroups(input.songs.values, input.versionLinks)
        val byEnd = listens.sortedBy { it.endedAt }
        var examples = 0
        for ((i, session) in chosen.withIndex()) {
            val t = session.first().startedAt
            val before = byEnd.takeWhile { it.endedAt <= t }
            if (before.isEmpty()) continue
            val picks = HashMap<String, Double>()
            for (l in session) {
                val g = Signals.engagement(l, input.songs[l.songId]?.likedAt, p)
                if (g >= p.justPlayedEngagement) picks.merge(groups.groupOf(l.songId), g, ::maxOf)
            }
            if (picks.isEmpty()) continue
            val bucket = dayPartBucket(t, session.first().tzOffsetMin)
            val row = EngineRow.build(input.copy(now = t, listens = before, bucket = bucket, seen = emptyList(), pastSeeds = emptyList()), learner.weights, p, random = Random(t))
            for ((slot, card) in row.cards.withIndex()) {
                val g = picks[groups.groupOf(card.songId)]
                val example = if (g != null) Example(card.features, card.lane, slot, g, 1.0) else if (ignoredWeight > 0) Example(card.features, card.lane, slot, 0.0, ignoredWeight) else null
                if (example != null) { learner.apply(example); examples++ }
            }
            onProgress?.invoke(i + 1, chosen.size)
        }
        return Result(learner.asMap(), chosen.size, examples)
    }
}
