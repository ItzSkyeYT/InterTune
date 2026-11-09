/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * Songs like the ones skipped, read from YouTube's related lists (Unreleased.ADAPTIVE_SIMILAR, an idea of
 * 9 Nov 2026). A radio queue is made of songs related to each other, so nearly all of it is on the list of
 * any song in it: what counts is being closer to a song skipped than to any song kept or liked in the same
 * run. A song on both is like what the listener skips and like what they keep, and it stays. Closeness is
 * by place, since YouTube puts the closest first ([closeness]).
 *
 * No Android in here, so it is tested.
 */
object SkipSimilar {

    /** The place on a list at which a song counts half as close as the first. */
    const val HALF_AT = 10.0

    /** How close a song at [place] of a list (0 the first) stands to that list's song: 1 at the top, 0 when absent. */
    fun closeness(place: Int?): Double = if (place == null || place < 0) 0.0 else 1.0 / (1.0 + place / HALF_AT)

    class Read(
        /** Closer to the song just skipped than to any song kept. */
        val likeLast: Set<String>,
        /** Closer to two or more of the run's skipped songs than to any song kept: two skips are a pattern. */
        val likeTwo: Set<String>,
    )

    /**
     * [skipped]: the related lists of the run's skipped songs, newest first, each in YouTube's order;
     * [kept]: those of its songs kept and liked. [lastJustSkipped]: the newest of [skipped] was skipped just
     * now, on the pass after the skip; only then is anything like the last one, as before.
     */
    fun read(skipped: List<List<String>>, kept: List<List<String>>, lastJustSkipped: Boolean): Read {
        if (skipped.isEmpty()) return Read(emptySet(), emptySet())
        val keptBest = HashMap<String, Double>()
        for (list in kept) {
            list.forEachIndexed { place, id ->
                val c = closeness(place)
                if (c > (keptBest[id] ?: 0.0)) keptBest[id] = c
            }
        }
        val closerTo = HashMap<String, Int>()
        val likeLast = LinkedHashSet<String>()
        skipped.forEachIndexed { which, list ->
            val counted = HashSet<String>()
            list.forEachIndexed { place, id ->
                // A song twice on one list counts once, at its first and closest place.
                if (!counted.add(id)) return@forEachIndexed
                if (closeness(place) > (keptBest[id] ?: 0.0)) {
                    closerTo[id] = (closerTo[id] ?: 0) + 1
                    if (which == 0 && lastJustSkipped) likeLast += id
                }
            }
        }
        return Read(likeLast, closerTo.filterValues { it >= 2 }.keys)
    }
}
