/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import androidx.media3.common.Player

/**
 * Ends a cast that has had nothing playing for [timeoutMs]: paused, at the end of its queue, or
 * stopped by a failure. The phone stays awake and on full-power Wi-Fi for as long as a cast lasts,
 * so one forgotten on pause would hold both until somebody noticed. Playing again before the time
 * is up calls it off, and the next pause gets the whole time again.
 *
 * The timer comes from outside ([schedule] runs an action after a delay and returns a way to call
 * it off), so the rules are tested without a clock. Main thread.
 */
class IdleCountdown(
    private val timeoutMs: Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> () -> Unit,
    private val onTimeout: () -> Unit,
) {
    private var callOff: (() -> Unit)? = null

    /** The receiver's state now: counts down while nothing plays, and stops counting when it does. */
    fun update(playWhenReady: Boolean, playbackState: Int) {
        if (!isIdle(playWhenReady, playbackState)) {
            stop()
        } else if (callOff == null) {
            callOff = schedule(timeoutMs) {
                callOff = null
                onTimeout()
            }
        }
    }

    /** Stops counting, for a cast that ends some other way. */
    fun stop() {
        callOff?.invoke()
        callOff = null
    }

    companion object {
        const val TIMEOUT_MS = 10L * 60 * 1000

        /** Nothing is playing and nothing is about to: loading the next song is not idle. */
        fun isIdle(playWhenReady: Boolean, playbackState: Int): Boolean =
            !playWhenReady || playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE
    }
}
