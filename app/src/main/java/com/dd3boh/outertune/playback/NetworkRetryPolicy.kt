/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player

/**
 * When to try a stalled song again, and when to stop calling it stalled. No Android in here, so it
 * is tested.
 *
 * The service used to hold a failed song until the connectivity observer said a network had become
 * available. That is the right answer for a phone in a tunnel and the wrong answer for the common
 * case, which is one name resolution or connect miss on a link that never went down: no network
 * ever becomes available, because none was ever lost, so the song was held for good. Hence a
 * bounded ladder of retries, short at first because most of these clear within a second or two,
 * and finite because a phone that really is offline should be handed back to the observer rather
 * than retried in a pocket all afternoon.
 */
object NetworkRetryPolicy {
    /** Five tries over roughly fifty seconds. Past that it is not a blip and guessing again is noise. */
    const val MAX_ATTEMPTS = 5

    private const val FIRST_DELAY_MS = 2_000L
    private const val CAP_MS = 20_000L

    fun shouldRetry(attempt: Int): Boolean = attempt in 1..MAX_ATTEMPTS

    /** Doubling, capped, so the first try is quick and the last is not a battery drain. */
    fun delayMillis(attempt: Int): Long =
        if (attempt <= 1) FIRST_DELAY_MS
        else (FIRST_DELAY_MS shl (attempt - 1).coerceAtMost(10)).coerceAtMost(CAP_MS)

    /** Every delay added up, which is the longest the waiting state can last without a decision. */
    fun totalWaitMillis(): Long = (1..MAX_ATTEMPTS).sumOf { delayMillis(it) }

    /**
     * Whether waiting for the network is still true of this player.
     *
     * The old rule was to clear it when playWhenReady went false, which is a pause. A player error
     * does not pause, it goes idle with playWhenReady still set, so the flag outlived the fault in
     * both directions: it never cleared when the song came back on its own, leaving a spinner over
     * music that was playing, and it never cleared when the song did not, leaving a stale flag that
     * could start playback by itself at the next network change.
     */
    fun stillWaiting(waiting: Boolean, playbackState: Int, playWhenReady: Boolean): Boolean =
        waiting && playWhenReady &&
                (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING)
}
