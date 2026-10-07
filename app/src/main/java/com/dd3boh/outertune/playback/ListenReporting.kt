/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.zionhuang.innertube.models.YouTubeClient

/**
 * What a play is allowed to tell services outside the phone, kept apart from the service so the
 * rules can be tested.
 */
object ListenReporting {

    /**
     * Last.fm's "now playing". Paused listen history keeps what is playing to the phone, and
     * Last.fm shows now playing on the profile for anyone to see; scrobbles were already held
     * back, now playing went out regardless.
     */
    fun sendsNowPlaying(listenHistoryPaused: Boolean): Boolean = !listenHistoryPaused

    /**
     * Whether a counted play is also registered in YouTube's watch history. Not for local files,
     * not while remote history is paused or YouTube is throttling us, and not signed out: the ping
     * then had no account to land in, and cost a whole extra /player request per song.
     */
    fun pingsYouTubeHistory(isLocal: Boolean, loggedIn: Boolean, remoteHistoryPaused: Boolean, throttled: Boolean): Boolean =
        !isLocal && loggedIn && !remoteHistoryPaused && !throttled

    /** Whose /player request gives the address a play is reported to. */
    enum class AddressFrom(val label: String) {
        /** Asked with the cookie and the channel, by [ACCOUNT_ADDRESS_CLIENT]. */
        ACCOUNT("the account's request"),

        /** Asked as nobody, by VISIONOS. The only request there was before the trial. */
        VISITOR("the visitor's request"),
    }

    /**
     * The client that asks for the address as the account, while Unreleased.HISTORY_AS_ACCOUNT is
     * tried. It has to take the cookie, and with it onBehalfOfUser, which is what names a brand
     * account's channel. Of the clients that do: WEB_REMIX is turned away from /player without a
     * po token, YouTube is known to ignore the cookie on ANDROID, WEB_CREATOR is the studio, and
     * the embedded TV player is what people use to walk past an age gate. That leaves the TV
     * client. A guess from reading, not a finding: nothing here has asked YouTube.
     */
    val ACCOUNT_ADDRESS_CLIENT: YouTubeClient = YouTubeClient.TVHTML5

    /**
     * The requests to make for the address, in order, each only when the one before gave none.
     *
     * The visitor's alone unless the trial is on. With it, the account's goes first for somebody
     * signed in, and not when playback as the account is set to never: that setting says the
     * account stays out of /player, and this is a /player request. The visitor's stays last
     * either way, so a play is never worse off than it was.
     */
    fun addressRequests(asAccountTrial: Boolean, loggedIn: Boolean, authMode: PlaybackAuthMode): List<AddressFrom> =
        if (asAccountTrial && loggedIn && authMode != PlaybackAuthMode.NEVER) listOf(AddressFrom.ACCOUNT, AddressFrom.VISITOR)
        else listOf(AddressFrom.VISITOR)

    /**
     * The first address any of [requests] gives, and whose request gave it, or null when none did.
     * A request that failed, answered without an address or answered an empty one gave none, and
     * the next is asked. Inline so that [ask] can be the suspending request itself.
     */
    inline fun firstAddress(requests: List<AddressFrom>, ask: (AddressFrom) -> String?): Pair<AddressFrom, String>? {
        for (from in requests) {
            val address = ask(from)
            if (!address.isNullOrBlank()) return from to address
        }
        return null
    }

    /**
     * The line for where the address came from when the account was asked first: what its request
     * answered, as the status YouTube gave or the kind of failure, and whether the visitor's had to
     * be used. A status is YouTube's to write, so anything that is not a plain word of capitals is
     * not repeated.
     *
     * [httpStatus] is for a request YouTube turned away outright, which reaches the app as a
     * failure: a 401 says the cookie was not taken, and "no answer" would have hidden that.
     */
    fun addressSourceLine(
        used: AddressFrom?,
        accountStatus: String?,
        accountFailure: Throwable?,
        httpStatus: Int? = null,
    ): String {
        val answer = when {
            httpStatus != null -> "answered HTTP $httpStatus"
            accountFailure != null -> "no answer, ${failureKind(accountFailure)}, ${accountFailure.javaClass.simpleName}"
            accountStatus == null -> "answered without a status"
            accountStatus.length in 1..40 && accountStatus.all { it in 'A'..'Z' || it == '_' } -> "answered $accountStatus"
            else -> "answered with a status not known here"
        }
        return when (used) {
            AddressFrom.ACCOUNT -> "Remote history: address from the account's request ($answer)"
            AddressFrom.VISITOR -> "Remote history: no address from the account's request ($answer), the visitor's is used"
            null -> "Remote history: no address from the account's request ($answer) nor from the visitor's"
        }
    }

    /** Whether YouTube took the report. A 2xx says it was well formed, not that the play is in the history. */
    fun historyReportTaken(status: Int): Boolean = status in 200..299

    /**
     * The line the log gets for a play reported to YouTube's history: the status YouTube answered
     * with and nothing else. The report used to leave no trace when it was refused, so "my
     * history does not sync" could not be told from a log.
     *
     * [from] adds whose request the address came from, for a play that asked as the account first:
     * the status alone cannot say which of the two addresses YouTube was answering.
     */
    fun historyAnswerLine(status: Int, from: AddressFrom? = null): String {
        val line = if (historyReportTaken(status)) "Remote history: YouTube answered $status"
        else "Remote history: YouTube answered $status, the play was refused"
        return if (from == null) line else "$line (address from ${from.label})"
    }

    /**
     * The line for a report that got no answer: the kind of failure and its class, never its
     * message. A timeout's message quotes the address the report went to, and that address names
     * the video, the session and the player.
     */
    fun historyFailureLine(failure: Throwable): String =
        "Remote history: the report got no answer (${failureKind(failure)}, ${failure.javaClass.simpleName})"

    private fun failureKind(failure: Throwable): String = when (failure) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "no connection"
        is java.net.SocketTimeoutException -> "timed out"
        is java.io.IOException -> "network error"
        else -> "failed"
    }

    /**
     * What the log may say about the address a play is reported to: its host and how many
     * parameters it carries, or that there is none. Its parameters are the identifiers.
     */
    fun trackingAddressForLog(address: String?): String {
        if (address == null) return "none"
        val host = address.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        val parameters = address.substringAfter('?', "").substringBefore('#').split('&').count { it.isNotEmpty() }
        return "${host.ifEmpty { "no host" }}, parameters: $parameters"
    }
}
