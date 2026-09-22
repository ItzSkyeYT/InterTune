/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlin.random.Random

/**
 * A running order across several artists that is actually a mix.
 *
 * A plain shuffle over every song by every bookmarked artist is not what anybody means by "play my
 * favourites". Libraries are lopsided: one artist has two hundred songs in it and another has
 * three, so a fair coin over the songs is a heavily weighted coin over the artists, and the result
 * is one artist with occasional visitors. The request this exists for asked for the variety the
 * bookmarks already represent, which is variety over artists, not over songs.
 *
 * So the unit is a round rather than a song. Every artist still holding songs contributes exactly
 * one per round, which makes representation equal regardless of how much of each artist is in the
 * library, and an artist drops out of later rounds once emptied. The order within a round is
 * reshuffled every time, so equal representation does not turn into a fixed rotation somebody can
 * predict after a minute of listening.
 *
 * The one seam a round structure leaves is its boundary: an artist last in one round and first in
 * the next is heard twice in a row, which is the single thing this is supposed to prevent. So a
 * round that would open on the artist that closed the previous one swaps them, whenever there is
 * anybody else to swap with.
 *
 * Each artist's own songs are shuffled here rather than by the caller, because a caller that
 * forgets leaves every artist playing in database order, which sounds like a bug and is hard to
 * see in a list.
 *
 * @param byArtist one list per artist. Empty lists are ignored, so callers need not filter.
 * @return every song from every list exactly once.
 */
fun <T> interleaveByArtist(byArtist: List<List<T>>, random: Random = Random.Default): List<T> {
    val queues = byArtist
        .filter { it.isNotEmpty() }
        .map { ArrayDeque(it.shuffled(random)) }
    if (queues.isEmpty()) return emptyList()

    val out = ArrayList<T>(queues.sumOf { it.size })
    var closedLastRound: ArrayDeque<T>? = null

    while (true) {
        val round = queues.filter { it.isNotEmpty() }.shuffled(random).toMutableList()
        if (round.isEmpty()) break

        // Only worth doing when somebody else is available to go first; with one artist left
        // there is no arrangement that avoids playing them twice running.
        if (round.size > 1 && round.first() === closedLastRound) {
            val other = 1 + random.nextInt(round.size - 1)
            round[0] = round[other].also { round[other] = round[0] }
        }

        for (queue in round) {
            out += queue.removeFirst()
            closedLastRound = queue
        }
    }

    return out
}

/**
 * Groups by [key], then interleaves. The whole of a favourites mix except the query that feeds it.
 *
 * Separated from the caller so that the grouping is testable without building Room relation
 * objects: what goes in is a flat list and a way to name the artist, which is as true of strings in
 * a test as it is of songs from the database.
 *
 * An item whose key is null is dropped rather than pooled into an "everything else" group. Null
 * here means the song arrived with no bookmarked artist on it, which the query is supposed to make
 * impossible, so pooling those would quietly play things the person never asked for and hide the
 * fault that let them in.
 */
fun <T, K> interleaveBy(
    items: List<T>,
    random: Random = Random.Default,
    key: (T) -> K?,
): List<T> {
    val grouped = LinkedHashMap<K, MutableList<T>>()
    for (item in items) {
        val group = key(item) ?: continue
        grouped.getOrPut(group) { mutableListOf() } += item
    }
    return interleaveByArtist(grouped.values.toList(), random)
}
