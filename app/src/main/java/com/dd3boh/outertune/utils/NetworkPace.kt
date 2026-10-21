/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * How fast /player has been answering on this network lately, kept across walks. No Android in
 * here, so it is tested.
 *
 * Slowness used to be found anew by every walk: where /player takes 5 to 10 s, each song, pass
 * and quiet retry had its first two clients cut at the short limit, about 16 s before the first
 * answer, and with a silent check on top the song ran out of its 20 s. So a network found slow
 * stays slow while its answers do: for [RouteTimes.SLOW_REMEMBER_MS] after it was found or last
 * answered slowly, until a quick answer or a change of network.
 *
 * And the second try on a new connection waited a fixed 1.5 s: where every answer takes 2 s, it
 * went out for nearly every song and almost never won, a second handshake on a thin link. So it
 * waits about three times the usual answer once a few have been seen.
 */
object NetworkPace {
    private const val KEEP = 8

    /** Until when (System.nanoTime) the network is taken for slow; 0 when it is not. */
    @Volatile
    private var slowUntil = 0L

    /** The last answers' times, with when each came (System.nanoTime). */
    private val answers = ArrayDeque<Pair<Long, Long>>()

    fun isSlow(now: Long = System.nanoTime()): Boolean = slowUntil != 0L && now < slowUntil

    /** Two clients in a row ran out of the short limit. */
    fun foundSlow(now: Long = System.nanoTime()) {
        slowUntil = now + RouteTimes.SLOW_REMEMBER_MS * 1_000_000
    }

    /**
     * A /player answer came after [ms]. One under a third of the short limit ends a slow spell;
     * one over 60% of it starts one, or keeps it [RouteTimes.SLOW_REMEMBER_MS] more. A spell
     * used to end five minutes after it was found however slow the answers stayed, and the next
     * walk cut its first clients at the short limit again, about every other song.
     */
    @Synchronized
    fun answered(ms: Long, now: Long = System.nanoTime()) {
        answers.addLast(ms to now)
        while (answers.size > KEEP) answers.removeFirst()
        if (ms < RouteTimes.playerCapMs * 3 / 10) {
            slowUntil = 0L
        } else if (ms > RouteTimes.playerCapMs * 6 / 10) {
            val until = now + RouteTimes.SLOW_REMEMBER_MS * 1_000_000
            if (slowUntil == 0L || until > slowUntil) slowUntil = until
        }
    }

    /**
     * When a second try of /player is due: [base], or three times the usual answer of the last
     * few minutes when that is longer, and never past [cap] less a little, so a try is still made.
     */
    @Synchronized
    fun hedgeAfter(base: Long, cap: Long, now: Long = System.nanoTime()): Long {
        while (answers.isNotEmpty() && now - answers.first().second > RouteTimes.SLOW_REMEMBER_MS * 1_000_000) answers.removeFirst()
        if (answers.size < 3) return base
        val median = answers.map { it.first }.sorted()[answers.size / 2]
        return maxOf(base, minOf(median * 3, cap * 3 / 4))
    }

    /** Another network: nothing seen on the last one says anything of this one. */
    @Synchronized
    fun forget() {
        slowUntil = 0L
        answers.clear()
    }
}
