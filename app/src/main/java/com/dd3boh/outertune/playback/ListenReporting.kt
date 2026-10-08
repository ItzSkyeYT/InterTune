/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.zionhuang.innertube.models.YouTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

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

        /** Asked as nobody, by VISIONOS. The only request there was until October 2026. */
        VISITOR("the visitor's request"),
    }

    /**
     * The client that asks for the address as the account. It has to take the cookie, and with it
     * onBehalfOfUser, which is what names a brand account's channel: the report that follows is a
     * plain GET with the cookie, and nothing in it says which channel of the account played.
     *
     * WEB_REMIX, because it is the one seen to work. AsterTune asks it for every play (its commit
     * 6ea96c90, 12 Jul 2026, checked on a phone by its author), ArchiveTune does, and Metrolist
     * did from 31 May to 26 Aug 2026. All three send it signed in with the signature timestamp
     * and a player po token. Without the token it answers UNPLAYABLE and no address, which is
     * what this app's own probe saw in August and why the address was moved to a visitor's
     * request then. Nobody asks the TV client for this, which a first cut here did, on a guess.
     * What each fork does is written up in experiments/bugs/yt-history-not-syncing/other-forks.md.
     */
    val ACCOUNT_ADDRESS_CLIENT: YouTubeClient = YouTubeClient.WEB_REMIX

    /** What the account's request waits for, in the order it does. */
    enum class AccountStep(val needed: String?, val awaited: String) {
        /** Read from the player's script, which is downloaded once and kept. */
        SIGNATURE_TIMESTAMP("a signature timestamp", "the signature timestamp"),

        /** Made in a WebView, which takes a page, two requests and BotGuard the first time. */
        PO_TOKEN("a po token", "the po token"),

        /** The /player request itself. */
        ANSWER(null, "YouTube's answer"),
    }

    /**
     * How long the account's request may take, all of it: the signature timestamp, the po token
     * and the answer. After that the visitor's address is used.
     *
     * The first one after the app starts has the player's script to download and a WebView to
     * load and run BotGuard in, and the later ones find both ready. On a Pixel 5 on Wi-Fi the
     * first took 2.1 s and the next 0.4 s (8 Oct 2026). A play is reported within a few seconds
     * of ending, with the visitor's address when this one is late, and is never left waiting on
     * a WebView: see [answerWithin].
     */
    const val ACCOUNT_ADDRESS_LIMIT_MS = 8_000L

    /**
     * The requests to make for the address, in order, each only when the one before gave none.
     *
     * The account's goes first for somebody signed in, and not when playback as the account is
     * set to never: that setting says the account stays out of /player, and this is a /player
     * request. The visitor's is last in every case, so a play is never worse off than it was
     * when that was the only one.
     */
    fun addressRequests(loggedIn: Boolean, authMode: PlaybackAuthMode): List<AddressFrom> =
        if (loggedIn && authMode != PlaybackAuthMode.NEVER) listOf(AddressFrom.ACCOUNT, AddressFrom.VISITOR)
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

    /** Where work goes on that nobody waits for any longer. See [answerWithin]. */
    private val unwaited = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * What [work] came to, or null when it has not answered after [limitMs]. A failure of its own
     * is an answer: it is handed back, not thrown.
     *
     * Out of time, the work is no longer waited for, and it is not stopped. Part of what the
     * account's request waits for is a thread parked until a WebView calls back, which a
     * cancelled coroutine does not wake, so waiting for it to stop would be the very wait this
     * is here to end. And what it was fetching, the player's script and the WebView that makes
     * tokens, is kept once it arrives, so the next play finds it ready. It ends by itself: the
     * po token gives up after its own limit and every request under it has a timeout.
     */
    suspend fun <T : Any> answerWithin(limitMs: Long, work: suspend () -> T): Result<T>? {
        val answer = unwaited.async { work() }
        return withTimeoutOrNull(limitMs) {
            try {
                Result.success(answer.await())
            } catch (e: CancellationException) {
                // The caller's own, the limit included, goes on its way. Anything else is the
                // work having been cancelled under its own feet, which is a failure like another.
                ensureActive()
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    /**
     * What a /player request for the address came to, as much of it as a log may say: the status
     * YouTube gave or the kind of failure. A status is YouTube's to write, so anything that is not
     * a plain word of capitals is not repeated.
     *
     * [httpStatus] is for a request YouTube turned away outright, which reaches the app as a
     * failure: a 401 says the cookie was not taken, and "no answer" would have hidden that.
     * [without] is what the account's request had to be asked without. The po token is the one
     * that matters: without it WEB_REMIX answers UNPLAYABLE, and the log has to tell that apart
     * from a song YouTube will not play. [outOfTimeAt] is what that request was waiting for when
     * [limitMs] ran out.
     */
    fun requestAnswer(
        status: String?,
        failure: Throwable?,
        httpStatus: Int? = null,
        without: Set<AccountStep> = emptySet(),
        outOfTimeAt: AccountStep? = null,
        limitMs: Long = ACCOUNT_ADDRESS_LIMIT_MS,
    ): String {
        if (outOfTimeAt != null) return "no answer within ${seconds(limitMs)}, waiting for ${outOfTimeAt.awaited}"
        val answer = when {
            httpStatus != null -> "answered HTTP $httpStatus"
            failure != null -> "no answer, ${failureKind(failure)}, ${failure.javaClass.simpleName}"
            status == null -> "answered without a status"
            status.length in 1..40 && status.all { it in 'A'..'Z' || it == '_' } -> "answered $status"
            else -> "answered with a status not known here"
        }
        val missing = AccountStep.entries.filter { it in without }.mapNotNull { it.needed }
        return if (missing.isEmpty()) answer else "$answer, asked without ${missing.joinToString(" or ")}"
    }

    /**
     * The line for where the address came from when the account was asked first: what its request
     * came to, in the words of [requestAnswer], and whether the visitor's had to be used.
     */
    fun addressSourceLine(
        used: AddressFrom?,
        accountStatus: String?,
        accountFailure: Throwable?,
        httpStatus: Int? = null,
        without: Set<AccountStep> = emptySet(),
        outOfTimeAt: AccountStep? = null,
        limitMs: Long = ACCOUNT_ADDRESS_LIMIT_MS,
    ): String {
        val answer = requestAnswer(accountStatus, accountFailure, httpStatus, without, outOfTimeAt, limitMs)
        return when (used) {
            AddressFrom.ACCOUNT -> "Remote history: address from the account's request ($answer)"
            AddressFrom.VISITOR -> "Remote history: no address from the account's request ($answer), the visitor's is used"
            null -> "Remote history: no address from the account's request ($answer) nor from the visitor's"
        }
    }

    /**
     * One line of "Check the history address" in developer options: whose request, what it came
     * to in the words of [requestAnswer], whether the answer carried an address, and how long it
     * took, with what each part of the account's request took when [steps] has them.
     *
     * [reason] is what YouTube says to a person about a refusal. Only its plain words are
     * repeated, up to the first character that is not one of a sentence's, so a reason that ever
     * quoted an address would stop short of it.
     */
    fun addressCheckLine(
        from: AddressFrom,
        client: String,
        answer: String,
        hasAddress: Boolean,
        tookMs: Long,
        reason: String? = null,
        steps: Map<AccountStep, Long> = emptyMap(),
    ): String {
        val parts = AccountStep.entries.mapNotNull { step -> steps[step]?.let { "${step.awaited} ${seconds(it, exact = true)}" } }
        val said = reason?.takeWhile { it.isLetterOrDigit() || it in " .,'\u2019!-" }?.take(120)?.trim().orEmpty()
        return from.label.replaceFirstChar { it.uppercase() } + " ($client): $answer, " +
            (if (hasAddress) "address present" else "no address") + ", " + seconds(tookMs, exact = true) +
            (if (parts.isEmpty()) "" else parts.joinToString(", ", " (", ")")) +
            (if (said.isEmpty()) "" else ", YouTube says: $said")
    }

    /**
     * The line of the check for an account's request that is not made, or null when it is: the
     * check asks what a play would ask, and no more. See [addressRequests].
     */
    fun accountNotAskedLine(loggedIn: Boolean, authMode: PlaybackAuthMode): String? = when {
        AddressFrom.ACCOUNT in addressRequests(loggedIn, authMode) -> null
        !loggedIn -> "The account's request: not made, nobody is signed in"
        else -> "The account's request: not made, playback as the account is set to never"
    }

    /**
     * The last line of the check: what a counted play would do with what the requests gave.
     * [found] is the first of them that gave an address. The conditions are the service's own:
     * [historyPaused] keeps a play from being counted at all, and the rest are those of
     * [pingsYouTubeHistory], so the check cannot say a play is reported when the service would
     * not report it. Signed out there is no account for a play to go to, so no address is named.
     */
    fun checkVerdictLine(
        found: AddressFrom?,
        loggedIn: Boolean,
        historyPaused: Boolean,
        remoteHistoryPaused: Boolean,
        throttled: Boolean,
    ): String {
        val held = when {
            !loggedIn -> "nobody is signed in"
            historyPaused -> "listen history is paused"
            remoteHistoryPaused -> "sharing listen history with YouTube Music is paused"
            throttled -> "YouTube is refusing this network for now"
            else -> null
        }
        val address = found?.let { "the address from ${it.label}" }
        return when {
            held != null && address != null && loggedIn -> "A play is not reported: $held. It would go to $address. Nothing was reported."
            held != null -> "A play is not reported: $held. Nothing was reported."
            address != null -> "A play is reported to $address. Nothing was reported now."
            else -> "A play would not be reported: neither request gave an address."
        }
    }

    /** A time for a line: whole seconds as "8 s", and to a tenth when [exact] or when it is not whole. */
    private fun seconds(ms: Long, exact: Boolean = false): String =
        if (!exact && ms % 1000 == 0L) "${ms / 1000} s" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

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
