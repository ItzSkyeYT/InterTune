/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * How many second tries may be made: [RouteTimes.HEDGES_PER_MINUTE] a minute in all, and per
 * song (the key) [RouteTimes.EXTRA_PLAYER_PER_SONG] of /player and [RouteTimes.EXTRA_HEAD_PER_SONG]
 * of the stream check, within the same minute. A second try is cheap only when it is rare: on a
 * healthy network none is made at all, and on a bad one this keeps them from doubling what the
 * phone asks of YouTube, which counts every request against the address.
 */
class HedgeBudget(
    private val perMinute: Int = RouteTimes.HEDGES_PER_MINUTE,
    private val windowMs: Long = 60_000L,
) {
    enum class Kind(val perSong: Int) {
        PLAYER(RouteTimes.EXTRA_PLAYER_PER_SONG),
        HEAD(RouteTimes.EXTRA_HEAD_PER_SONG),
    }

    private class Taken(val kind: Kind, val key: String, val at: Long)

    private val taken = ArrayDeque<Taken>()

    /** Takes one second try of [kind] for [key] at [now], or says none is left. */
    @Synchronized
    fun take(kind: Kind, key: String, now: Long): Boolean {
        while (taken.isNotEmpty() && now - taken.first().at >= windowMs) taken.removeFirst()
        if (taken.size >= perMinute) return false
        if (taken.count { it.kind == kind && it.key == key } >= kind.perSong) return false
        taken.addLast(Taken(kind, key, now))
        return true
    }

    companion object {
        /** The app's own; a new one in each test that walks the chain. */
        @Volatile
        internal var shared = HedgeBudget()

        /**
         * Whether a second try may start now: the remote switch on, YouTube not refusing this
         * network (a second request then only adds to what it counts), and a try left.
         */
        fun mayHedge(kind: Kind, key: String, now: Long = System.currentTimeMillis()): Boolean =
            RemoteRoutes.hedge && !Throttle.isBlocked && shared.take(kind, key, now)
    }
}
