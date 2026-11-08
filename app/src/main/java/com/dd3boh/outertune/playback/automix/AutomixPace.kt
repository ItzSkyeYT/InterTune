/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * How fast this phone holds an end, skips the next song to its entry and works a change out, as
 * measured on the changes it made, and the lead a change far from the end needs from them.
 *
 * A slower measurement counts at once and a faster one a quarter of the way, so one quick change
 * on a cool phone does not make the next one late. With no history it guesses as slow as the
 * Pixel 5 was on 6 Nov 2026: 105.8 s held in 9.4 s, 30.5 s of the next song skipped in 1.7 s, a
 * 19 s change worked out in 10 s, 7 of them rendering it.
 */
data class AutomixPace(
    /** Seconds of the end held per second. */
    val holdSpeed: Double = HOLD_SPEED_GUESS,
    /** Seconds of the next song skipped to its entry per second. */
    val skipSpeed: Double = SKIP_SPEED_GUESS,
    /** Milliseconds of work on the held end before the change is rendered, per second held. */
    val analyseMsPerS: Double = ANALYSE_MS_PER_S_GUESS,
    /** Milliseconds of rendering per second of the change. */
    val renderMsPerS: Double = RENDER_MS_PER_S_GUESS,
) {

    /** [seconds] of an end held in [ms]. */
    fun withHold(seconds: Double, ms: Long): AutomixPace =
        if (seconds < MIN_HOLD_S || ms <= 0) this else copy(holdSpeed = slower(holdSpeed, seconds * 1000 / ms, speed = true))

    /** [seconds] of the next song skipped to its entry in [ms]. */
    fun withSkip(seconds: Double, ms: Long): AutomixPace =
        if (seconds < MIN_SKIP_S || ms <= 0) this else copy(skipSpeed = slower(skipSpeed, seconds * 1000 / ms, speed = true))

    /** A change worked out: [analyseMs] on [tailSeconds] held before its rendering, [renderMs] for [spanSeconds] of it. */
    fun withWork(tailSeconds: Double, analyseMs: Long, spanSeconds: Double, renderMs: Long): AutomixPace {
        var p = this
        if (tailSeconds >= MIN_HOLD_S && analyseMs >= 0) p = p.copy(analyseMsPerS = slower(analyseMsPerS, analyseMs / tailSeconds, speed = false))
        if (spanSeconds >= MIN_SPAN_S && renderMs > 0) p = p.copy(renderMsPerS = slower(renderMsPerS, renderMs / spanSeconds, speed = false))
        return p
    }

    /**
     * Where the end of a change must be held from, in ms before the song's end: its first frame
     * [changeLeftMs] before the end, [spanMs] long, the next song coming in [entrySeconds] into
     * it. Between the arm and the first frame have to fit the hold, which takes longer the
     * earlier it starts, the work on the held end and the change's rendering, or the skip where
     * that is longer (it runs beside the work), the stage's second ahead, and a margin.
     */
    fun armFromMs(changeLeftMs: Long, spanMs: Long, entrySeconds: Double): Long {
        val change = changeLeftMs / 1000.0
        val k = 1 + MARGIN_SHARE
        val perHeld = k * (1 / holdSpeed + analyseMsPerS / 1000)
        if (perHeld >= 1) return Long.MAX_VALUE
        val withWork = (change + k * spanMs / 1000.0 * renderMsPerS / 1000 + FIXED_S) / (1 - perHeld)
        val withSkip = (change + k * entrySeconds / skipSpeed + FIXED_S) / (1 - k / holdSpeed)
        return ceil(max(withWork, withSkip) * 1000).toLong()
    }

    /**
     * Where the end of a change kept as rendered must be held from for it to be heard from its
     * first frame, [changeLeftMs] before the end, the next song coming in [entrySeconds] into it:
     * as [armFromMs], with no work on the held end and nothing to render, only the hold, the kept
     * change's few ms and the skip to the next song's entry. Held later, it is joined part-way.
     */
    fun keptArmFromMs(changeLeftMs: Long, entrySeconds: Double): Long {
        val change = changeLeftMs / 1000.0
        val k = 1 + MARGIN_SHARE
        val perHeld = k / holdSpeed
        if (perHeld >= 1) return Long.MAX_VALUE
        val fixed = AutomixSchedule.KEPT_WORK_MS / 1000.0 + FIXED_S
        val withSkip = (change + k * entrySeconds / skipSpeed + fixed) / (1 - perHeld)
        return ceil(withSkip * 1000).toLong()
    }

    /**
     * The furthest before the song's end, in ms, the first frame of a change [spanMs] long, the
     * next song coming in [entrySeconds] into it, can be and still be held in time ([armFromMs]
     * within [AutomixSchedule.ARM_FROM_LEFT_MAX_MS]); -1 where nowhere is.
     */
    fun reachMs(spanMs: Long, entrySeconds: Double): Long {
        val cap = AutomixSchedule.ARM_FROM_LEFT_MAX_MS
        if (armFromMs(0, spanMs, entrySeconds) > cap) return -1
        // The lead only grows with the first frame's distance from the end, and never falls short of it.
        var fits = 0L
        var past = cap + 1
        while (past - fits > 1) {
            val mid = (fits + past) / 2
            if (armFromMs(mid, spanMs, entrySeconds) <= cap) fits = mid else past = mid
        }
        return fits
    }

    /** One line a field, read back to the bit. */
    fun encode(): String = "holdSpeed=$holdSpeed\nskipSpeed=$skipSpeed\nanalyseMsPerS=$analyseMsPerS\nrenderMsPerS=$renderMsPerS\n"

    companion object {
        const val HOLD_SPEED_GUESS = 11.0
        const val SKIP_SPEED_GUESS = 17.0
        const val ANALYSE_MS_PER_S_GUESS = 30.0
        const val RENDER_MS_PER_S_GUESS = 370.0

        /** A tenth more than measured for the hold, the work and the rendering. */
        const val MARGIN_SHARE = 0.10

        /** The stage's second ahead of a plan, and one more. */
        const val FIXED_S = 2.0

        /** Holds, skips and changes shorter than this say too little about the phone to count. */
        const val MIN_HOLD_S = 20.0
        const val MIN_SKIP_S = 5.0
        const val MIN_SPAN_S = 2.0

        /** A faster measurement moves the estimate this far towards it. */
        private const val FASTER_STEP = 0.25

        private fun slower(old: Double, new: Double, speed: Boolean): Double {
            val worse = if (speed) new < old else new > old
            return if (worse) new else old + (new - old) * FASTER_STEP
        }

        /** The file this phone's pace is kept in, in the app's own files: written by playback, read by the editor too. */
        const val FILE_NAME = "mix-pace.txt"

        fun read(dir: File): AutomixPace? = runCatching { File(dir, FILE_NAME).takeIf { it.isFile }?.readText()?.let(::decode) }.getOrNull()

        fun write(dir: File, pace: AutomixPace) = File(dir, FILE_NAME).writeText(pace.encode())

        fun decode(text: String): AutomixPace? = runCatching {
            val fields = text.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().toDouble() }
            AutomixPace(fields.getValue("holdSpeed"), fields.getValue("skipSpeed"), fields.getValue("analyseMsPerS"), fields.getValue("renderMsPerS"))
                .takeIf { it.holdSpeed > 0 && it.skipSpeed > 0 && it.analyseMsPerS >= 0 && it.renderMsPerS >= 0 }
        }.getOrNull()

        /** What a change too far ahead for this phone is told, with [needMs] the arm point it would need. */
        fun tooFarWords(needMs: Long): String = String.format(
            Locale.ROOT, "needs %.1f s, the end can be held %.1f s", needMs / 1000.0, AutomixSchedule.ARM_FROM_LEFT_MAX_MS / 1000.0,
        )
    }
}
