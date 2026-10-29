/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import kotlin.math.max
import kotlin.math.min

/**
 * Where the song is at any moment, as near as the player's readings of it tell, for the lyrics.
 *
 * The player knows where it is only when its thread wakes, and since it sleeps until half of what
 * the output holds has played (MusicService, dynamic scheduling) that is about every 125 ms, at
 * most every 375 ms. In between, its position stands still. The lyrics read it every 125 ms, or
 * every 33 ms word by word, so a line came on screen up to a quarter of a second after it was
 * sung, and the lit words trailed by another tenth of a second, eased in over 100 ms. He moved
 * songs 0.1 s earlier by hand to make up for it (25 Oct 2026).
 *
 * Here each reading is kept with the moment it was taken, and the song is taken to run on from it
 * in real time at the player's speed. A reading can only be late, never early: the song has moved
 * on since the player last said, by however long ago that was. So of all the readings, run on to
 * now, the one furthest on is the nearest to the truth, and the clock goes by that one ([at]).
 * Read once a frame, a reading is soon caught within a frame of the player's own wake ([sure]).
 * The one kept counts for a little less as it ages ([LATE_PER_SECOND]), so that a clock that runs a
 * hair fast against the music's own is brought back by the readings, and never runs ahead.
 *
 * It starts again when the song jumps or stops running on in real time ([restart], [Why]): a seek
 * or another song, from where the player says the song is; a pause, from where the clock had it;
 * the player waiting for data, from where the player says it stopped. Until the player has been
 * seen to move after that, the clock stands still: the sound starts a moment after the player is
 * told to play, longer still over Bluetooth, and the clock does not run ahead of it. A change of
 * speed while the song runs only goes on from where the clock had it, at the new speed.
 *
 * Song ms throughout, and moments in [System.nanoTime]'s nanoseconds. No Android in it, for the
 * tests (LyricsClockTest).
 */
class LyricsClock {
    /** Whether the song runs on between readings: playing, and not waiting for data. */
    var running = false
        private set

    /** How fast the song runs against the clock: the player's speed. */
    var speed = 1f
        private set

    /** Where the song stands while it does not run, or has not yet been seen to since the start. */
    private var standing = 0L
    private var moved = false

    /** What [ahead] is measured from, so the numbers stay small. */
    private var origin = 0L

    /**
     * The best reading so far, as how far the song stands from the clock: its ms less the ms since
     * [origin] times [speed]. The song is no less than this, run on to the moment, give or take
     * what [LATE_PER_SECOND] has taken off since [raisedAt].
     */
    private var ahead = 0.0
    private var raisedAt = 0L

    /** The last reading, to tell a new one from the same one read again. */
    private var lastValue = 0L
    private var lastAt = 0L

    /** When a reading last caught the player within [CAUGHT_WITHIN_NANOS] of its moving, or 0. */
    private var caughtAt = 0L

    /** Why the clock starts again: what the player has just said, at [restart]. */
    enum class Why {
        /** The song is somewhere else: a seek, another song. Where the player says it is, it is. */
        JUMP,

        /**
         * Paused from the app. The player says so at once, and where the song stopped only at its
         * next wake, so until then the clock stands where it had the song at that moment.
         */
        PAUSE,

        /**
         * Anything else: the player waiting for data, or going on after it, or another speed. A
         * song that runs on at another speed runs on from where the clock had it.
         */
        CHANGE,
    }

    /** Starts again from [positionMs], read at [atNanos], with the player [running] at [speed] or not, for [why]. */
    fun restart(positionMs: Long, atNanos: Long, running: Boolean, speed: Float, why: Why = Why.JUMP) {
        val newSpeed = if (speed > 0f && speed.isFinite()) speed else 1f
        val wasRunning = this.running && moved
        if (why == Why.CHANGE && running && wasRunning) {
            // Still running, at another speed: on from where it is, by the new speed.
            val now = at(atNanos)
            this.speed = newSpeed
            origin = atNanos
            ahead = now.toDouble()
            raisedAt = atNanos
            standing = min(standing, now)
            lastValue = positionMs
            lastAt = atNanos
            return
        }
        val stoppedAt = if (why == Why.PAUSE && wasRunning) max(positionMs, at(atNanos)) else positionMs
        this.running = running
        this.speed = newSpeed
        standing = stoppedAt
        moved = false
        origin = atNanos
        ahead = 0.0
        raisedAt = atNanos
        lastValue = positionMs
        lastAt = atNanos
        caughtAt = 0L
    }

    /** A reading of the player: the song at [positionMs], as it said at [atNanos]. */
    fun read(positionMs: Long, atNanos: Long) {
        if (!running) {
            // Nothing runs on, so a new reading is where the song is. The same one read again is
            // left as it was: after a pause it is the player's last word from before it.
            if (positionMs != lastValue) standing = positionMs
            lastValue = positionMs
            lastAt = atNanos
            return
        }
        if (positionMs == lastValue) {
            lastAt = atNanos
            return
        }
        val caught = atNanos - lastAt <= CAUGHT_WITHIN_NANOS
        val implied = positionMs - ms(atNanos) * speed
        if (!moved) {
            if (positionMs < standing) {
                // Back before where it started: a seek the clock was not told of.
                restart(positionMs, atNanos, running = true, speed = speed)
                return
            }
            moved = true
            ahead = implied
            raisedAt = atNanos
        } else {
            val kept = ahead - aged(atNanos)
            if (implied > kept) {
                ahead = implied
                raisedAt = atNanos
            } else if (kept - implied > BEHIND_MS) {
                // Far behind where the clock has it: the song went back, a seek it was not told of.
                restart(positionMs, atNanos, running = true, speed = speed)
                return
            }
        }
        if (caught) caughtAt = atNanos
        lastValue = positionMs
        lastAt = atNanos
    }

    /** Where the song is at [nanos], in its ms. */
    fun at(nanos: Long): Long {
        if (!running || !moved) return standing
        return max(standing, (ahead - aged(nanos) + ms(nanos) * speed).toLong())
    }

    /**
     * True once a reading has caught the player within a frame of its moving, not long ago: the
     * clock is then right to within a frame. Until then, the lyrics read it at every frame.
     */
    fun sure(nanos: Long): Boolean = running && moved && caughtAt != 0L && nanos - caughtAt < SURE_FOR_NANOS

    /**
     * How long the lyrics can wait before they look again, in ms of the clock, 0 meaning at the
     * next frame. [untilNextMs] is how far the lyrics are from the next moment anything about them
     * changes ([LyricsMoments.untilNext]), [refreshMs] how often they look at the most. While the
     * seek bar is dragged ([seeking]) or nothing runs, every [refreshMs]. Otherwise at every frame
     * for the last [NEAR_MS] before such a moment, and while the clock is not yet [sure], so that a
     * line comes on the frame it is sung on, or the next.
     */
    fun wait(nanos: Long, untilNextMs: Long, seeking: Boolean, refreshMs: Long): Long {
        if (seeking || !running) return refreshMs
        if (!sure(nanos)) return 0L
        if (untilNextMs == Long.MAX_VALUE) return refreshMs
        val real = (untilNextMs / speed).toLong()
        return if (real <= NEAR_MS) 0L else min(refreshMs, real - NEAR_MS)
    }

    private fun ms(nanos: Long): Double = (nanos - origin) / 1_000_000.0

    private fun aged(nanos: Long): Double = LATE_PER_SECOND * max(0L, nanos - raisedAt) / 1_000_000_000.0

    companion object {
        /**
         * How much the reading kept counts for less each second, in ms: more than any clock is out
         * against the music's (a hundred parts in a million would be 0.1 ms a second), so the
         * clock never runs ahead of the song, and little enough that one good reading serves for
         * many seconds.
         */
        const val LATE_PER_SECOND = 0.5

        /** A reading this soon after the last one, with the song moved, caught the player's wake: two frames' worth. */
        const val CAUGHT_WITHIN_NANOS = 25_000_000L

        /** How long one such reading keeps the clock [sure]. */
        const val SURE_FOR_NANOS = 10_000_000_000L

        /** A reading this far behind the clock is not a late one but a song gone back: the longest the player sleeps, and more. */
        const val BEHIND_MS = 600.0

        /** For this long before a moment the lyrics change at, they look at every frame. */
        const val NEAR_MS = 60L
    }
}

/**
 * The moments, in the lyrics' own ms, at which anything about how [lines] look changes as the song
 * goes: a line starting, and a line done with, 100 ms after its end, when it dims and its words
 * stop being lit one by one. Between two of them every line looks the same, wherever the song is.
 */
class LyricsMoments(lines: List<LyricLine>) {
    private val moments: LongArray = LongArray(lines.size * 2).also { all ->
        lines.forEachIndexed { i, line ->
            all[2 * i] = line.start.toLong()
            // Done with while the song is past its end by more than 100 ms: from 101 ms on.
            all[2 * i + 1] = line.end.toLong() + DONE_AFTER_MS + 1
        }
        all.sort()
    }

    /** How many of the moments have come by [positionMs]. The same number, the same look. */
    fun passed(positionMs: Long): Int {
        var low = 0
        var high = moments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (moments[middle] <= positionMs) low = middle + 1 else high = middle
        }
        return low
    }

    /** How far [positionMs] is from the next moment, in ms, or [Long.MAX_VALUE] when none is left. */
    fun untilNext(positionMs: Long): Long {
        val next = passed(positionMs)
        return if (next < moments.size) moments[next] - positionMs else Long.MAX_VALUE
    }

    companion object {
        /** How long after its end a line is still lit as being sung: Lyrics' `item.end + 100`. */
        const val DONE_AFTER_MS = 100L
    }
}
