package com.zionhuang.innertube

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * How patient requests to YouTube are, for a connection that is weak rather than gone (a report of
 * 25 Nov 2026: on a very weak Wi-Fi signal one slow answer was called "YouTube can't be reached").
 *
 * On a weak or metered network the waits are longer, and a request that never got through is asked
 * again, twice, with a growing pause. Only what cannot have changed anything on YouTube's side is
 * asked again: a connection that was never made, and, for pages that only read, a first answer that
 * did not come.
 */
object NetworkPatience {
    /** Set by the app from the network in use. */
    @Volatile
    var weak: Boolean = false

    const val MAX_RETRIES = 2

    fun connectTimeoutMs(weak: Boolean = this.weak) = if (weak) 30_000L else 15_000L
    fun socketTimeoutMs(weak: Boolean = this.weak) = if (weak) 40_000L else 20_000L
    fun requestTimeoutMs(weak: Boolean = this.weak) = if (weak) 60_000L else 30_000L

    /** The pause before retry number [retry] (1 for the first): 1 s, then 2 s. */
    fun backoffMs(retry: Int): Long = 1_000L shl (retry - 1).coerceIn(0, 4)

    /**
     * Whether the network in use is weak: a slow link as the phone reports it, a Wi-Fi signal near
     * the edge, or a metered connection. [downstreamKbps] and [signalDbm] are null when unknown.
     */
    fun isWeak(downstreamKbps: Int?, signalDbm: Int?, metered: Boolean): Boolean =
        metered ||
            (downstreamKbps != null && downstreamKbps in 1 until 5_000) ||
            (signalDbm != null && signalDbm <= -80)

    /** Pages that only read: asking again after a first answer that did not come changes nothing. */
    private val readPaths = listOf("browse", "search", "next", "get_search_suggestions", "music/get_queue")

    fun readsOnly(path: String): Boolean = readPaths.any { path.trimEnd('/').endsWith(it) }

    /**
     * Whether to ask again after [failure] on [path], having already asked again [retriesDone]
     * times. A connection that was never made is asked again whatever the request; a wait that
     * ran out only for a read and only once, so a dead connection does not hold a page for minutes.
     */
    fun shouldRetry(failure: Throwable, path: String, retriesDone: Int): Boolean {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is ConnectTimeoutException || t is UnknownHostException || t is NoRouteToHostException) return true
            if (t is ConnectException) return true
            t = t.cause
        }
        t = failure
        depth = 0
        while (t != null && depth++ < 8) {
            if (t is SocketTimeoutException || t is HttpRequestTimeoutException || t is SocketException) {
                return retriesDone == 0 && readsOnly(path)
            }
            t = t.cause
        }
        return false
    }
}
