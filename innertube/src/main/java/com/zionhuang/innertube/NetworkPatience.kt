package com.zionhuang.innertube

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * How patient requests to YouTube are, for a connection that is weak rather than gone (a report of
 * 25 Nov 2026: on a very weak Wi-Fi signal one slow answer was called "YouTube can't be reached").
 *
 * On a weak network the waits are longer, and a request whose connection was refused or whose name
 * was not found is asked again, twice, with a growing pause: what cannot have reached YouTube, and
 * what a weak signal does now and then. A wait that ran out is not asked again (its time is already
 * spent, and a dead link would hold a page twice as long), nor is anything while the phone has no
 * network, nor the request that starts a song, whose own walk of the clients retries and stops.
 */
object NetworkPatience {
    /** Set by the app from the network in use. */
    @Volatile
    var weak: Boolean = false

    /** Set by the app: whether the phone has a network with internet at all. Offline, nothing is asked again. */
    @Volatile
    var online: Boolean = true

    const val MAX_RETRIES = 2

    fun connectTimeoutMs(weak: Boolean = this.weak) = if (weak) 30_000L else 15_000L
    fun socketTimeoutMs(weak: Boolean = this.weak) = if (weak) 40_000L else 20_000L
    fun requestTimeoutMs(weak: Boolean = this.weak) = if (weak) 60_000L else 30_000L

    /** The pause before retry number [retry] (1 for the first): 1 s, then 2 s. */
    fun backoffMs(retry: Int): Long = 1_000L shl (retry - 1).coerceIn(0, 4)

    /**
     * Whether the network in use is weak: a slow link as the phone reports it, or a Wi-Fi signal near
     * the edge. [downstreamKbps] and [signalDbm] are null when unknown. Mobile data as such is not
     * weak: it counted until 26 Nov 2026, and a dead mobile link then held Home for about 93 s.
     */
    fun isWeak(downstreamKbps: Int?, signalDbm: Int?): Boolean =
        (downstreamKbps != null && downstreamKbps in 1 until 5_000) ||
            (signalDbm != null && signalDbm <= -80)

    /** The request that starts a song: the player's own walk of the clients asks again and stops at once offline. */
    private fun startsASong(path: String): Boolean = path.trimEnd('/').endsWith("/player")

    /**
     * Whether to ask again after [failure] on [path]: only a connection that was refused, a name that
     * was not found or a route that was missing, while the phone has a network, and never for the
     * request that starts a song. A wait that ran out, a connect timeout included, is not.
     */
    fun shouldRetry(failure: Throwable, path: String): Boolean {
        if (!online || startsASong(path)) return false
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            // ktor's ConnectTimeoutException is a ConnectException: a wait that ran out, not a refusal.
            if (t is ConnectTimeoutException) return false
            if (t is UnknownHostException || t is NoRouteToHostException || t is ConnectException) return true
            t = t.cause
        }
        return false
    }
}
