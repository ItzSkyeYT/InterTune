/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.dd3boh.outertune.utils.RouteTimes
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.YTPlayerUtils
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * What the player does on its own when a song stops or its loading fails, decided here without
 * Android so that it is tested. MusicService acts on it.
 *
 * On 20 Oct 2026 the song stopped on a timeout, and a fresh try a second later would have played
 * it: the next request on a new connection answered at once, every time. Only "cannot connect"
 * was ever retried; a timeout, a refused address mid-song and a stream that stopped all ended the
 * music when the buffer ran out. So a passing fault is tried again quietly, a route that keeps
 * failing is changed, and all of it within a budget, so a song that cannot be had still ends with
 * a message and never in a loop.
 */
@OptIn(UnstableApi::class)
object Recovery {
    enum class Kind {
        /** Worth a quiet try a moment later: the connection's doing, or a route's. */
        PASSING,

        /** YouTube's word on the song, or the file's or decoder's fault: skip or stop as set. */
        FINAL,
    }

    private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }.take(10)

    /** The HTTP status a load got, wherever media3 put it among the causes. */
    fun status(error: Throwable): Int? =
        causes(error).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    /**
     * Whether a stopped song is worth a quiet try. Final are YouTube's own words on the song
     * (gone, the bot check, the age gate, every route refused) and a fault of the file or the
     * decoder. The cached part of another stream is MusicService's to retry, once. Passing are timeouts of any kind, ours
     * included, a refused or failed stream (403, 410, 5xx), a resolve that failed for want of an
     * answer, and a connection that failed while the phone is online.
     */
    fun kind(error: PlaybackException): Kind {
        val all = causes(error).toList()
        if (YTPlayerUtils.isUnavailableSong(error) || Throttle.isBlock(error) || Throttle.isAgeGated(error)) return Kind.FINAL
        val codes = all.filterIsInstance<PlaybackException>().map { it.errorCode }
        if (codes.any { NetworkRetryPolicy.isLocalFault(it) }) return Kind.FINAL
        status(error)?.let { return if (it == 403 || it == 410 || it in 500..599) Kind.PASSING else Kind.FINAL }
        if (all.any { it is SocketTimeoutException }) return Kind.PASSING
        if (all.any { it.javaClass.simpleName == "HttpRequestTimeoutException" }) return Kind.PASSING
        if (codes.any {
                it == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    it == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            }
        ) return Kind.PASSING
        // The resolver's failure for want of an answer: an IOException behind its remote error.
        // A remote error with no cause is a refusal YouTube gave, every route's.
        val resolver = all.drop(1).filterIsInstance<PlaybackException>().firstOrNull()
        if (resolver?.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR && resolver.cause is IOException) return Kind.PASSING
        if (all.any { it is HttpDataSource.HttpDataSourceException }) return Kind.PASSING
        return Kind.FINAL
    }

    /** What failed, in a word or two for the log: never the error's own text, which can quote an address. */
    fun what(error: PlaybackException): String = when {
        status(error) != null -> "HTTP ${status(error)}"
        causes(error).any { it is SocketTimeoutException || it.javaClass.simpleName == "HttpRequestTimeoutException" } -> "Timeout"
        else -> error.errorCodeName
    }

    /** What a failed load calls for. */
    enum class Decision {
        /** Media3's own retry, at once, on a fresh connection: the first failure usually mends. */
        RETRY,

        /** The address is forgotten and the route avoided for the song; the next retry resolves anew. */
        CHANGE_ROUTE,

        /** Not a failure of the route: the resolver's own, which the player stops on, or the pace guard's cut. */
        IGNORE,
    }

    /**
     * Failed loads, per song: the second within [RouteTimes.LOAD_FAILURE_WINDOW_MS], or any 403
     * or 410, changes the route. Media3 retries a progressive stream on the address it has, a
     * 403 three times over, and every retry got the same refused address until the buffer ran
     * out (FAILURE-MAP P and Q).
     */
    class LoadFailures(private val windowMs: Long = RouteTimes.LOAD_FAILURE_WINDOW_MS) {
        private val last = HashMap<String, Long>()

        @Synchronized
        fun decide(id: String?, error: IOException, now: Long): Decision {
            if (id == null) return Decision.IGNORE
            val all = causes(error).toList()
            if (all.any { it is PlaybackException }) return Decision.IGNORE
            if (all.any { it is SlowStreamException }) return Decision.IGNORE
            val code = status(error)
            if (code == 403 || code == 410) {
                last.remove(id)
                return Decision.CHANGE_ROUTE
            }
            val before = last[id]
            if (before != null && now - before in 0 until windowMs) {
                last.remove(id)
                return Decision.CHANGE_ROUTE
            }
            last[id] = now
            return Decision.RETRY
        }
    }

    /**
     * How many recoveries may still be made: [RouteTimes.RECOVERIES_PER_SONG] a song and
     * [RouteTimes.RECOVERIES_IN_ALL] in all, within [RouteTimes.RECOVERY_WINDOW_MS]. A changed
     * route, a quiet retry and a stall's new route each take one; a cut connection is free, it is
     * what media3 does anyway. Finished well inside the ten minutes Android keeps a stopped media
     * service in the foreground.
     */
    class Budget(
        private val perSong: Int = RouteTimes.RECOVERIES_PER_SONG,
        private val inAll: Int = RouteTimes.RECOVERIES_IN_ALL,
        private val windowMs: Long = RouteTimes.RECOVERY_WINDOW_MS,
    ) {
        private val taken = ArrayDeque<Pair<String, Long>>()

        private fun prune(now: Long) {
            while (taken.isNotEmpty() && now - taken.first().second >= windowMs) taken.removeFirst()
        }

        /** Takes one for [id] at [now] and returns which one it is for the song (1, 2, 3), or null when none is left. */
        @Synchronized
        fun take(id: String, now: Long): Int? {
            prune(now)
            val forSong = taken.count { it.first == id }
            if (forSong >= perSong || taken.size >= inAll) return null
            taken.addLast(id to now)
            return forSong + 1
        }

        /** How many [id] has had, for the log's "n of 3". */
        @Synchronized
        fun takenFor(id: String, now: Long): Int {
            prune(now)
            return taken.count { it.first == id }
        }

        val ofSong: Int get() = perSong
    }
}
