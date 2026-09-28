/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse

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
    /** How long after a failed try with a new visitorData before trying one again. */
    const val NEW_VISITOR_RETRY_MS = 10 * 60 * 1000L

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

    /**
     * The visitorData to take from a /player answer, or null to keep the one the app has.
     *
     * VISIONOS clears the bot check only with a visitorData YouTube issued: none at all, or one it
     * does not recognise, gets "Sign in to confirm you're not a bot", and the chain then falls
     * through to IOS. The app fetches its one from sw.js_data at launch and never again, and in
     * issue #17 that fetch failed at every launch ("Failed to get visitorData."), so there was
     * none and every song ended in IOS's 403. Every /player answer carries a fresh one in
     * responseContext, refusals included, and VISIONOS accepts it (probed 27 Sep 2026).
     *
     * Only replaces what is not a visitorData at all: nothing, blank, and the "null" and
     * "undefined" that an old bug and a failed sign-in capture leave behind. Every one YouTube
     * issues starts Cgs or Cgt (a protobuf whose first field is the 11 character visitor id), so a
     * value that does is kept, even if it has stopped working.
     */
    fun visitorDataToAdopt(current: String?, offered: String?): String? {
        if (current != null && looksLikeVisitorData(current)) return null
        return offered?.takeIf { looksLikeVisitorData(it) }
    }

    fun looksLikeVisitorData(value: String) = value.startsWith("Cgt") || value.startsWith("Cgs")

    /**
     * Whether a chain VISIONOS turned down is worth one more try with a visitorData YouTube has only
     * just issued. [msSinceFailedSwap] is how long ago the last such try failed too, null if none has.
     *
     * A visitorData issued while YouTube distrusted the network stays distrusted after the network
     * is fine again: VISIONOS answers OK and its urls fail the check, every time (6 of 6 on
     * 27 Sep 2026, over IPv4 and IPv6 alike), while a new one passes. 0.10.9.5 kept its
     * visitorData forever, so a phone that got a bad one stayed on IOS's 403 for good.
     *
     * After a failed try, not again for [NEW_VISITOR_RETRY_MS]: a network YouTube is refusing
     * outright refuses every visitorData, and each try is two more requests to it.
     */
    fun mayRetryWithNewVisitor(visionosRefused: Boolean, msSinceFailedSwap: Long?): Boolean =
        visionosRefused && (msSinceFailedSwap == null || msSinceFailedSwap >= NEW_VISITOR_RETRY_MS)

    /** One client's part of the error report's trail, as in "VISIONOS OK, HEAD 200". */
    fun trailStep(client: String, playability: String?, head: Int?, checked: Boolean): String =
        buildString {
            append(client).append(' ').append(playability ?: "no answer")
            if (checked) append(", HEAD ").append(head?.toString() ?: "failed")
        }

    /** What [resolveOnceFailure] decided to do about a chain that produced no usable stream. */
    sealed interface ChainFailure {
        /** A fallback client's own explanation: its reason, or its bare status without one. */
        data class Explained(val message: String) : ChainFailure

        /** The last fallback client's own failure (dropped connection, timeout): rethrow as is. */
        data class LastFailure(val cause: Throwable) : ChainFailure

        /** Nothing explained anything and nothing failed either. */
        data object Unknown : ChainFailure
    }

    /**
     * Decides what to throw when no client produced a response the player could use at all (the
     * separate case of a client answering and its url then being refused is
     * [refusalMessage]'s).
     *
     * Order matters. A fallback client's explanation is what a person can act on, so it wins:
     * Five Hours (4f963e5f4) had VISIONOS and IOS both say "This video is not available", then
     * ANDROID's /player call failed, and the reason the first two gave is the one to show. The
     * last client's own failure is thrown only when nothing explained anything, so a chain whose
     * fallback calls all failed reaches MusicService's no-connection and timeout mapping instead
     * of "Bad stream player response".
     */
    fun resolveOnceFailure(
        explained: PlayerResponse.PlayabilityStatus?,
        lastFallbackFailure: Throwable?,
    ): ChainFailure = when {
        // status is a non-null String on the model itself; reason is the friendlier one when a
        // client gave one, and status is still something rather than nothing when it did not.
        explained != null -> ChainFailure.Explained(explained.reason ?: explained.status)
        lastFallbackFailure != null -> ChainFailure.LastFailure(lastFallbackFailure)
        else -> ChainFailure.Unknown
    }
}
