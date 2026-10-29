/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.util.Locale

/**
 * How playback has gone this run, in one line for Report a problem: the songs started, the waits
 * for data, how fast the streams came in beside what their songs need, the connections cut for
 * being slow or silent, the songs that failed, and where the sound goes, by kind only. In memory,
 * never a song, a name or an address: the service logs it at each change of song, so the log of a
 * run that ended carries it, and the report reads it as it opens.
 *
 * Why: a report of 26 Oct 2026 showed streams cut as slower than their songs, and nothing in it
 * said how the music had gone around them. No Android in here, so it is tested (PlayHealthTest).
 */
class PlayHealth {
    private var songs = 0
    private var waits = 0
    private var waitedMs = 0L
    private var longestMs = 0L
    private var waitingSince: Long? = null
    private var paceBytes = 0L
    private var paceNanos = 0L
    private var slowestPace = 0.0
    private var slowestNeed = 0.0
    private var slowCuts = 0
    private var silenceCuts = 0
    private var failed = 0
    private var sound: String? = null

    /** A song starts, its sound going to [soundTo] ([SoundRoute]), when that is known. */
    @Synchronized
    fun song(soundTo: String? = null) {
        songs++
        if (soundTo != null) sound = soundTo
    }

    /** The player waits for data to play ([waiting]) or stops waiting, at [now] in milliseconds. */
    @Synchronized
    fun waiting(waiting: Boolean, now: Long) {
        val since = waitingSince
        if (waiting && since == null) {
            waitingSince = now
            waits++
        } else if (!waiting && since != null) {
            val ms = (now - since).coerceAtLeast(0)
            waitedMs += ms
            longestMs = maxOf(longestMs, ms)
            waitingSince = null
        }
    }

    /** One window of a stream judged ([StreamPace.Meter]): [bytes] in [nanos] of waiting, for a song that needs [need] bytes a second. */
    @Synchronized
    fun paced(bytes: Long, nanos: Long, need: Double) {
        if (nanos <= 0 || need <= 0.0) return
        paceBytes += bytes
        paceNanos += nanos
        val pace = bytes * 1e9 / nanos
        if (slowestNeed == 0.0 || pace / need < slowestPace / slowestNeed) {
            slowestPace = pace
            slowestNeed = need
        }
    }

    /** A connection given up for coming in slower than its song: see PaceGuardDataSource. */
    @Synchronized
    fun slowCut() {
        slowCuts++
    }

    /** A connection given up, or a route changed, for bringing nothing: see StallWatch. */
    @Synchronized
    fun silenceCut() {
        silenceCuts++
    }

    /** A song stopped on an error, or was given up. */
    @Synchronized
    fun failed() {
        failed++
    }

    /** In a line, at [now] in milliseconds: "4 songs; waited for data 6 times, 14 s in all, the longest 7.2 s; ...". */
    @Synchronized
    fun describe(now: Long): String = buildString {
        append(
            when (songs) {
                0 -> "no song started yet"
                1 -> "1 song"
                else -> "$songs songs"
            }
        )
        val ongoing = waitingSince?.let { (now - it).coerceAtLeast(0) }
        if (waits > 0) {
            append("; waited for data ").append(if (waits == 1) "once" else "$waits times")
            val total = waitedMs + (ongoing ?: 0)
            append(", ").append(duration(total)).append(" in all")
            if (waits > 1) append(", the longest ").append(duration(maxOf(longestMs, ongoing ?: 0)))
            ongoing?.let { append(", waiting now for ").append(duration(it)) }
        }
        if (paceNanos > 0) {
            append("; streams came in at ").append(rate(paceBytes * 1e9 / paceNanos)).append(" while the player waited on them")
            append(", the slowest at ").append(rate(slowestPace)).append(" where its song needs ").append(rate(slowestNeed))
        }
        if (slowCuts > 0) append("; ").append(count(slowCuts, "slow connection")).append(" cut")
        if (silenceCuts > 0) append("; ").append(count(silenceCuts, "silent connection")).append(" cut")
        if (failed > 0) append("; ").append(count(failed, "song")).append(" failed")
        sound?.let { append("; sound to ").append(it) }
    }

    companion object {
        /** This run's: the service writes it, Report a problem reads it (one process). */
        val thisRun = PlayHealth()

        private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

        /** "5.8 KB/s" under a hundred, "310 KB/s" under a thousand, "2.3 MB/s" from there. */
        fun rate(bytesPerSecond: Double): String {
            val kb = bytesPerSecond / 1000
            return when {
                kb < 100 -> String.format(Locale.ROOT, "%.1f KB/s", kb)
                kb < 1000 -> String.format(Locale.ROOT, "%.0f KB/s", kb)
                else -> String.format(Locale.ROOT, "%.1f MB/s", kb / 1000)
            }
        }

        /** "0.6 s" under ten seconds, "14 s" under a minute, "2 min 5 s" from there. */
        fun duration(ms: Long): String = when {
            ms < 10_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
            ms < 60_000 -> "${ms / 1000} s"
            else -> "${ms / 60_000} min ${ms % 60_000 / 1000} s"
        }
    }
}
