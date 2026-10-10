/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.client.plugins.ResponseException

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

    /** A playable answer whose address came in a cipher that could not be undone. */
    const val NOT_DECIPHERED = "address not deciphered"

    /** A playable answer with no address in it at all, which is a client that streams over SABR only. */
    const val NO_ADDRESS = "no address"

    /**
     * One client's part of the error report's trail, as in "VISIONOS OK, HEAD 200".
     *
     * [lacking] is why a playable answer gave the player nothing to fetch, [NOT_DECIPHERED] or
     * [NO_ADDRESS]. Without it such a step read "WEB_REMIX OK", which is what a client that
     * served reads like before its check.
     *
     * [with] is the po token the checked address carried, in words, for the one client whose
     * address is tried with more than one: "WEB_REMIX OK, HEAD 200 with the video's token".
     */
    fun trailStep(
        client: String,
        playability: String?,
        head: Int?,
        checked: Boolean,
        lacking: String? = null,
        with: String? = null,
    ): String =
        buildString {
            append(client).append(' ').append(playability ?: "no answer")
            if (lacking != null) append(", ").append(lacking)
            if (checked) append(", HEAD ").append(head?.toString() ?: "failed")
            if (checked && with != null) append(" with ").append(with)
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

    /**
     * YouTube's words for a song nobody asking this way is served: taken down, made private, not
     * licensed in this country, for paying members only.
     *
     * Matched on text, like the bot check, and for the same reason. The status does not tell "this
     * song is gone" from "this client is no longer welcome": YouTube answers a client it has
     * retired with UNPLAYABLE as well, for every song at once, and reading that as the song being
     * gone would have the app looking for another copy of everything it is asked to play.
     */
    private val GONE = listOf(
        "video is not available", "video unavailable", "video is unavailable",
        "video is private", "private video",
        "has been removed", "no longer available", "terminated",
        "available in your country", "blocked it in your country",
        "requires payment", "only available to",
    )

    /** Words for the client being turned away, whatever else the sentence says of the video. */
    private val NOT_THE_SONG = listOf("on this app", "on this device", "latest version", "update")

    fun saysGone(reason: String?): Boolean {
        val r = reason?.lowercase() ?: return false
        return GONE.any { it in r } && NOT_THE_SONG.none { it in r }
    }

    /**
     * What a walk that found no stream says of the song itself: the first fallback client's
     * answer, when every fallback client asked did answer, none with a stream to try, and each in
     * words that mean the song is gone. Null for everything else: a client the network did not
     * reach ([unreached]), one that answered OK and had its address refused, the bot check, the
     * age gate, words not known. A song is called gone on YouTube's say alone, never on a guess.
     */
    fun unavailableSong(
        fallbacks: List<PlayerResponse.PlayabilityStatus>,
        unreached: Boolean,
    ): PlayerResponse.PlayabilityStatus? =
        fallbacks.firstOrNull()?.takeIf { !unreached && fallbacks.all { it.status != "OK" && saysGone(it.reason) } }

    /**
     * Whether a fallback client's failed request leaves it open what has become of the song, for
     * [unavailableSong]'s unreached.
     *
     * A request the network did not carry does, whoever asked: nothing was heard. So does an
     * error YouTube answered to a client that asks as any visitor would, which might have been
     * the one to serve the song. An error answered to the request made as the account does not.
     * It used to, and on a phone signed in that is what kept a song that was gone from being
     * looked for at all: the three clients before it each said "This video is not available",
     * the account's got an error within a tenth of a second, and the song failed as it always
     * had. That request had nothing to play either way, so the song's other copy costs nothing.
     */
    fun leavesSongOpen(networkDidNotAnswer: Boolean, asAccount: Boolean): Boolean = networkDidNotAnswer || !asAccount

    /**
     * A failed request for the log: what kind of failure, and the status YouTube gave when it
     * gave one. Never the failure's own words, which quote the address asked and its answer.
     */
    fun failureForLog(failure: Throwable): String =
        failure.javaClass.simpleName + ((failure as? ResponseException)?.response?.status?.value?.let { " $it" } ?: "")

    private val HOST_NAME = Regex("""[A-Za-z0-9.-]{1,253}""")
    private val NUMBER = Regex("""\d{1,12}""")
    private val MIME_TYPE = Regex("""[A-Za-z]{1,12}/[A-Za-z0-9-]{1,16}""")

    /**
     * What the log may say about a stream url: its host, the itag, mime and expire it names, and
     * how many parameters it carries. Never the url itself. Logcat is pasted whole into issues and
     * chats, Log.d is not stripped from a release build here, and the query says who is listening:
     * ip is the address the url was issued to, beside the signature that makes it play.
     *
     * The three values are repeated only as a number, a type and a number, so nothing else gets
     * into the log under their names.
     */
    fun urlForLog(url: String): String {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        val parameters = url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }
        fun value(name: String) = parameters.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
        return listOfNotNull(
            host.takeIf { HOST_NAME.matches(it) } ?: "no host",
            value("itag")?.takeIf { NUMBER.matches(it) }?.let { "itag $it" },
            value("mime")?.replace("%2F", "/", ignoreCase = true)?.takeIf { MIME_TYPE.matches(it) }?.let { "mime $it" },
            value("expire")?.takeIf { NUMBER.matches(it) }?.let { "expire $it" },
            "parameters: ${parameters.size}",
        ).joinToString(", ")
    }
}
