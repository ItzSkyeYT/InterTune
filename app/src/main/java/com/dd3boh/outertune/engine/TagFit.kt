/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

/**
 * Whether a candidate is the same kind of thing as what is playing now.
 *
 * People listen in runs. A slowed edit is followed by another slowed edit, and an ordinary
 * recording by another ordinary one, and that holds far more strongly than any preference for
 * either. Measured over this maintainer's 6,156 consecutive within-session pairs: after a tagged
 * song the next is tagged 66.2 percent of the time, after an untagged one 22.4 percent, against a
 * base rate of 39.2. Three to one, on a library where 28 percent of titles carry a qualifier.
 *
 * What was tried first and thrown away: a global affinity per tag, "which treatments does this
 * listener finish". It measures almost nothing. His engagement with slowed edits, 2,113 listens,
 * is 0.776 against an overall 0.758, a difference of two hundredths on a spread of a third. The
 * tags with an apparent effect all had thirty listens or fewer. Taste in treatments, if it exists,
 * is far weaker than the run. So this asks the question that pays instead: not what does he like,
 * but what is he doing right now.
 *
 * None of the engine's other features can see this. Time of day and session artists are the
 * closest, and neither knows that this hour is a slowed hour.
 */
object TagFit {

    /** How many recent listens count as "now". Long enough to survive one odd song, short enough to turn. */
    const val WINDOW = 5

    /**
     * What the session sounds like.
     *
     * [treatedShare] is carried separately because an untreated run and an empty history produce
     * the same empty tag map, and they are not the same thing at all: one says "this listener is
     * on plain recordings right now" and the other says "no idea yet". A test caught that before
     * it shipped, and telling them apart is what makes the negative arm below possible.
     */
    data class Context(val tags: Map<String, Double>, val treatedShare: Double, val known: Boolean)

    /**
     * Weighted, most recent first, so a run that is turning is followed rather than averaged away.
     */
    fun context(recentTagsNewestFirst: List<Set<String>>): Context {
        val window = recentTagsNewestFirst.take(WINDOW)
        if (window.isEmpty()) return Context(emptyMap(), 0.0, known = false)
        val weights = DoubleArray(window.size) { 1.0 / (it + 1.0) }
        val total = weights.sum()
        val out = HashMap<String, Double>()
        var treated = 0.0
        window.forEachIndexed { i, tags ->
            val w = weights[i] / total
            if (tags.isNotEmpty()) treated += w
            for (t in tags) out[t] = (out[t] ?: 0.0) + w
        }
        return Context(out, treated, known = true)
    }

    /**
     * How well this candidate fits, from minus one to one.
     *
     * Positive when it carries what the session carries, or when it is plain and so is the
     * session. Negative when it would break the run either way. The second direction matters as
     * much as the first: dropping a slowed edit into a run of ordinary recordings is the same
     * mistake backwards, and it is the one nothing in the engine currently notices.
     */
    fun score(candidate: Set<String>, context: Context): Double {
        if (!context.known) return 0.0
        if (candidate.isEmpty()) {
            // Plain candidate: welcome in a plain run, jarring in a treated one.
            return (1.0 - 2.0 * context.treatedShare).coerceIn(-1.0, 1.0)
        }
        val shared = candidate.sumOf { context.tags[it] ?: 0.0 }
        if (shared > 0.0) return shared.coerceAtMost(1.0)
        // Treated, but not the way this session is treated. Mildly wrong, and more so the more
        // settled the run is.
        return -(context.treatedShare * 0.5).coerceIn(0.0, 1.0)
    }
}
