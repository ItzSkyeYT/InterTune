/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.utils.parseCookieString
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Why search, a radio, a mix or Home stopped loading, when the reason is one the listener can do
 * something about. Six people in three weeks met one of these with a raw error or an empty page
 * and nothing else (Discord digest of 2 Nov 2026): a sign-in that had lapsed for a week, a radio of
 * one song, a VPN that left InterTune out, South Korea.
 *
 * What the app receives decides it, and only a clear case is named (measured 2 Nov 2026, see
 * experiments/features/why-stopped/NOTES.md):
 *  - A sign-in that no longer works: YouTube does not refuse a dead cookie by itself, it serves the
 *    request as a visitor's. What it refuses, with 401 UNAUTHENTICATED, is the account every
 *    signed-in request names (the dataSyncId kept since sign-in) once the cookie no longer proves
 *    it. Every radio start and every look at the account's own playlists carries it, and search
 *    and Home do too while "Use login for browse" is on.
 *  - A connection that does not reach YouTube: no answer at all. The name not found, the
 *    connection refused, reset or timed out, the TLS handshake cut: what a blocked network or a VPN
 *    that leaves InterTune out (its split tunnelling, or a kill switch) gives. Only on a phone with
 *    a network; offline the pages already say so.
 *  - A closed country: YouTubeRegion's, for any other failure while YouTube sees the connection
 *    there.
 * Anything else stays as it was: the page's own "Unknown error" and Retry, the radio's own toast.
 */
object WhyStopped {
    enum class Cause { SIGN_IN, REGION, UNREACHABLE }

    /** What one failed request came to, as far as the causes go. */
    enum class Kind {
        /** YouTube answered 401 UNAUTHENTICATED: the account named is not proven by the cookie. */
        SIGN_IN_REFUSED,

        /** No answer at all: the name, the connection or the wait failed before YouTube spoke. */
        NO_ANSWER,

        /** Anything else: another answer from YouTube, or the app's own failure. */
        OTHER,
    }

    private const val UNAUTHENTICATED = "UNAUTHENTICATED"

    /** What [failure] came to, looked for down its causes: the app wraps some of them. */
    fun kindOf(failure: Throwable): Kind {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is ResponseException) {
                // ktor's message carries YouTube's answer: "... invalid: 401 Unauthorized. Text: ...".
                val refused = t.response.status.value == 401 || UNAUTHENTICATED in t.message.orEmpty()
                return if (refused) Kind.SIGN_IN_REFUSED else Kind.OTHER
            }
            if (noAnswer(t)) return Kind.NO_ANSWER
            t = t.cause
        }
        return Kind.OTHER
    }

    /** Whether YouTube answered [failure] at all, whatever it said. */
    fun hadAnswer(failure: Throwable): Boolean {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is ResponseException) return true
            t = t.cause
        }
        return false
    }

    /**
     * Failures from below HTTP. A ConnectException covers ktor's connect timeout, which is one; a
     * SocketException covers a refused or reset connection, no route, and the EPERM of a VPN's
     * kill switch with the app left out (GH #33).
     */
    private fun noAnswer(t: Throwable) =
        t is UnknownHostException || t is SocketException || t is SocketTimeoutException ||
            t is HttpRequestTimeoutException || t is SSLHandshakeException

    /**
     * The cause to name for a failure of [kind], or null for none: [signedIn] as the Account page
     * reads it, [online] when the phone has a network, [regionClosed] while YouTube sees the
     * connection in a country with no free plan.
     */
    fun cause(kind: Kind?, signedIn: Boolean, online: Boolean, regionClosed: Boolean): Cause? = when {
        kind == null -> null
        kind == Kind.SIGN_IN_REFUSED && signedIn -> Cause.SIGN_IN
        kind == Kind.NO_ANSWER -> if (online) Cause.UNREACHABLE else null
        regionClosed -> Cause.REGION
        else -> null
    }

    /** Signed in as the Account page has it: a cookie with SAPISID. */
    fun signedIn(cookie: String?): Boolean =
        !cookie.isNullOrEmpty() && runCatching { "SAPISID" in parseCookieString(cookie) }.getOrDefault(false)
}

/**
 * The last clear failure of a request to YouTube, for the note on Home, which most of these
 * failures never reach by themselves: on the engine's Quick picks Home opens from the phone and
 * asks YouTube nothing until a pull, so a dead sign-in was met first in a radio's toast. Search,
 * Home, a radio or mix start, an album or playlist page and the account sync report here.
 *
 * In memory only, and forgotten as soon as anything says otherwise: an answer to the account for a
 * refused sign-in, any answer for a connection that did not reach YouTube, a new sign-in, another
 * network.
 */
object YouTubeTrouble {
    private val _kind = MutableStateFlow<WhyStopped.Kind?>(null)

    /** [WhyStopped.Kind.SIGN_IN_REFUSED], [WhyStopped.Kind.NO_ANSWER] or null. */
    val kind: StateFlow<WhyStopped.Kind?> = _kind.asStateFlow()

    /** A request to YouTube failed with [failure]. One with no clear cause changes nothing. */
    fun failed(failure: Throwable) {
        when (WhyStopped.kindOf(failure)) {
            WhyStopped.Kind.SIGN_IN_REFUSED -> _kind.value = WhyStopped.Kind.SIGN_IN_REFUSED
            WhyStopped.Kind.NO_ANSWER -> _kind.value = WhyStopped.Kind.NO_ANSWER
            // YouTube said something, so it can be reached, whatever else is wrong.
            WhyStopped.Kind.OTHER -> if (WhyStopped.hadAnswer(failure)) reached()
        }
    }

    /** YouTube answered a request; [asAccount] when it carried the sign-in. */
    fun answered(asAccount: Boolean) {
        reached()
        if (asAccount) _kind.update { if (it == WhyStopped.Kind.SIGN_IN_REFUSED) null else it }
    }

    /** Signed in again, or out: what YouTube said of the old sign-in is no longer true. */
    fun signInChanged() = _kind.update { if (it == WhyStopped.Kind.SIGN_IN_REFUSED) null else it }

    /** Another network: what this one could not reach may well be reachable from there. */
    fun networkChanged() = reached()

    private fun reached() = _kind.update { if (it == WhyStopped.Kind.NO_ANSWER) null else it }

    /** For tests. */
    internal fun reset() {
        _kind.value = null
    }
}
