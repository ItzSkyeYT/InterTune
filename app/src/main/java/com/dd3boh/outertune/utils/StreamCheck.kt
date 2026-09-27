/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * What the HEAD check on a stream url decides, kept out of YTPlayerUtils so it can be tested
 * without a network.
 *
 * A HEAD request is what separates a url that plays to the end from one that does not. Probed on
 * 27 Sep 2026 against the same song: VISIONOS answers 200 and serves every byte, while IOS and
 * ANDROID, without the proof of origin token YouTube began requiring from them in August 2026,
 * answer 403, serve the first 512 KB of a ranged GET (about 30 seconds of audio) and refuse
 * everything after it.
 */
object StreamCheck {
    /**
     * Whether a url whose HEAD check got [status] goes to the player. Null means the check itself
     * failed: no connection, a timeout.
     *
     * The last client in the chain used to be taken without a check at all, and the last client is
     * IOS. So whenever VISIONOS was refused, the listener got half a minute of every song and then
     * "Source error (2004): Response code: 403", instead of the reason YouTube had actually given;
     * and because a url had been found, the refusal was recorded as a success and the back off
     * never heard about it. Reproduced on the emulator with 0.10.9.5 and a visitorData YouTube did
     * not recognise, which VISIONOS refuses the way it refuses a distrusted network (issue #17).
     *
     * A check that could not be made is only grounds to try the next client. With none left, the
     * url is still the best there is, so it goes to the player.
     */
    fun accept(status: Int?, isLast: Boolean): Boolean = when (status) {
        null -> isLast
        in 200..299 -> true
        else -> false
    }

    /**
     * The message when the chain produced urls and every one of them was refused.
     *
     * [reason] is what a fallback client said when it turned the request down outright, such as
     * VISIONOS's "Sign in to confirm you're not a bot". That is the cause, the 403 only its
     * symptom, and it is also the wording Throttle and the error screen recognise.
     */
    fun refusalMessage(reason: String?, status: Int): String =
        reason?.takeIf { it.isNotBlank() } ?: "YouTube refused the stream (HTTP $status)"

    /** One client's part of the error report's trail, as in "VISIONOS OK, HEAD 200". */
    fun trailStep(client: String, playability: String?, head: Int?, checked: Boolean): String =
        buildString {
            append(client).append(' ').append(playability ?: "no answer")
            if (checked) append(", HEAD ").append(head?.toString() ?: "failed")
        }
}
