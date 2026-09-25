/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * How long Keep listening leaves Shazam alone once it stops answering.
 *
 * A run asks about every window and never stops for a failure, which is right for one lost request
 * and wrong for a run of them. Shazam refuses with HTTP 429 once it has had about a dozen requests
 * in quick succession and goes on refusing for a while, a network that wants a login first answers
 * every request with a page that is not Shazam's, and a dead one fails each at once. Asked again
 * every window, all three went on for as long as the phone was left, and the screen said it was
 * listening as happily as ever. Asking straight away again is also what keeps a refusal going.
 *
 * So after a failure the windows that follow are not sent: for 12 s, then 24, 48 and 96, then two
 * minutes at a time, or for as long as Shazam's Retry-After asks when it sends one. The first real
 * answer, a match or a no-match, puts it back to every window. Silence and a failed fingerprint are
 * neither, since nothing was asked. A pause always runs to the end of a window, because that is the
 * next moment there is anything to send.
 *
 * Pure and fed by the engine, like [MixWatch], so it can be tested without a microphone or a network.
 */
internal class ShazamBackoff {

    /** Requests in a row that Shazam did not answer. */
    var failures = 0
        private set

    /** When the first window that may be sent again ends. Zero while Shazam answers. */
    var retryAtMs = 0L
        private set

    /** When the last failure came back. */
    private var failedAtMs = 0L

    /**
     * Whether the window that ended at [endMs] may go to Shazam.
     *
     * Half a window of slack, because each window is timed from the clock as it is cut, and the one
     * the pause runs to can end a few milliseconds before [retryAtMs]. A window that ended well
     * before the failure it follows can only mean the clock was put back since, and the pause is
     * not waited out: counted on the new clock it could last for hours.
     */
    fun allows(endMs: Long, windowMs: Long): Boolean =
        endMs >= retryAtMs - windowMs / 2 || endMs < failedAtMs - 2 * windowMs

    /**
     * Shazam gave no answer, at [nowMs], for the window that ended at [endMs], and asked to be left
     * alone for [retryAfterMs] if it said. Nothing more is sent until the first window to end once
     * the pause is over.
     */
    fun failed(nowMs: Long, endMs: Long, windowMs: Long, retryAfterMs: Long? = null) {
        failures++
        // Never before the window it was about ended, whatever the clock did in between.
        failedAtMs = maxOf(nowMs, endMs)
        val resume = failedAtMs + pauseMs(failures, retryAfterMs)
        // The windows follow one another without a gap, so each one to come ends a whole number of
        // windows after this one did.
        val windows = ((resume - endMs + windowMs - 1) / windowMs).coerceAtLeast(1)
        retryAtMs = endMs + windows * windowMs
    }

    /** Shazam answered, with a match or without one: every window goes to it again. */
    fun answered() {
        failures = 0
        retryAtMs = 0L
        failedAtMs = 0L
    }

    /**
     * When the next window will be sent, once Shazam has failed [NOTICE_AFTER] times in a row, and
     * null before that. One lost request is not worth a word to anybody; a minute of them is.
     */
    val notice: Long?
        get() = retryAtMs.takeIf { failures >= NOTICE_AFTER }

    companion object {
        const val FIRST_PAUSE_MS = 12_000L
        const val LONGEST_PAUSE_MS = 120_000L

        /**
         * The most of a Retry-After that is honoured. Shazam can ask for more than [LONGEST_PAUSE_MS]
         * and gets it, but a day would be a header gone wrong rather than a plan.
         */
        const val LONGEST_RETRY_AFTER_MS = 600_000L

        /** Failures in a row before the listener is told. About a minute of them at the usual window. */
        const val NOTICE_AFTER = 3

        /** How long to leave Shazam alone after [failures] in a row, the last of them asking [retryAfterMs]. */
        fun pauseMs(failures: Int, retryAfterMs: Long?): Long =
            retryAfterMs?.coerceIn(0L, LONGEST_RETRY_AFTER_MS)
                ?: (FIRST_PAUSE_MS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(LONGEST_PAUSE_MS)

        /**
         * Whether a failure with this reason was Shazam not answering. ShazamClient reports silence
         * without asking anything, and a fingerprint that could not be made never got as far as
         * asking, so neither says anything about Shazam.
         */
        fun asked(reason: String): Boolean = reason != "silence" && reason != "fingerprint"

        /**
         * What a Retry-After header asks for, in milliseconds from [nowMs]: a number of seconds, or
         * an HTTP date. Null when there is none, or it cannot be read.
         */
        fun retryAfterMs(header: String?, nowMs: Long): Long? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { seconds ->
                return if (seconds < 0) null else seconds.coerceAtMost(LONGEST_RETRY_AFTER_MS / 1000) * 1000
            }
            return runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()?.let { (it - nowMs).coerceAtLeast(0L) }
        }

        /** Whole seconds from [nowMs] to [atMs], rounded up so a countdown reaches zero on time. */
        fun secondsLeft(atMs: Long, nowMs: Long): Int =
            ((atMs - nowMs + 999) / 1000).coerceAtLeast(0L).toInt()
    }
}
