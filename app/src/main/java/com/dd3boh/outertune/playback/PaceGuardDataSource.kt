/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.net.URI

/**
 * How fast a song has to come in to be played without a gap, and how to tell a connection that
 * does not. No Android in here, so it is tested (StreamPaceTest).
 *
 * A connection to YouTube's stream servers can come in far slower than the song plays while the
 * network itself is fast: on a phone on 16 Oct 2026 two songs in half an hour came in at a third
 * and two thirds of their own pace, screen off, while the same phone fetched the same servers at
 * 20 to 30 MB/s. The player waited on them: a second of silence, a pause, a few seconds of music,
 * over and over. A read only times out after ten seconds of nothing, and a trickle is never
 * nothing, so one song stuttered until it was skipped. The other went quiet long enough to time
 * out, and the fresh connection the player asked on brought the rest of it in about a second.
 */
object StreamPace {
    /** What a song whose address names no length or duration is taken to need: about 190 kbps. */
    const val UNKNOWN_NEED = 24_000.0

    /** How long the player must have waited on a connection before its pace is judged. */
    const val WINDOW_NANOS = 2_000_000_000L

    /** Fresh connections in a row, each as slow as the last, after which the network is simply slow. */
    const val MAX_CUTS = 3

    /** Whether [url] is a song on one of YouTube's stream servers, the only connections watched. */
    fun isStream(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && host.endsWith(".googlevideo.com", ignoreCase = true)
    }

    /** The song's own pace, its length in bytes over its duration, from [url]'s clen and dur. */
    fun bytesPerSecond(url: String): Double? {
        val query = url.substringAfter('?', "").substringBefore('#').split('&')
        fun value(name: String) = query.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
        val length = value("clen")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val seconds = value("dur")?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        return length / seconds
    }

    fun need(url: String): Double = bytesPerSecond(url) ?: UNKNOWN_NEED

    /**
     * The pace of one connection, judged on the time the player spent waiting inside its reads.
     * Time the player spends away, its buffer full, is not the connection's: a read that comes
     * back at once with what had already arrived costs no time at all.
     *
     * Slow means slower than the song itself plays. A connection that only just keeps up is left
     * alone: a fresh one on a slow network would be no better, and every cut costs a reconnect.
     */
    class Meter(private val needBytesPerSecond: Double, private val keptPace: () -> Unit = {}) {
        private var bytes = 0L
        private var waited = 0L

        /** One read: [read] bytes in [nanos] spent waiting for them. True when the connection is too slow to keep. */
        fun slow(read: Int, nanos: Long): Boolean {
            bytes += read.coerceAtLeast(0)
            waited += nanos.coerceAtLeast(0)
            if (waited < WINDOW_NANOS) return false
            val pace = bytes * 1e9 / waited
            bytes = 0
            waited = 0
            if (pace < needBytesPerSecond) return true
            keptPace()
            return false
        }
    }

    /** The cuts made on one song's connections, so a network that is slow everywhere is not cut for ever. */
    class Cuts {
        private var inARow = 0

        fun mayCut(): Boolean = inARow < MAX_CUTS

        fun cut() {
            inARow++
        }

        /** A connection kept pace for a whole window: the cuts before it were worth it. */
        fun keptPace() {
            inARow = 0
        }
    }
}

/**
 * Every byte the player's stream reads brought in, all songs together: what the stall watch
 * reads to tell a slow stream, which is never cut, from a silent one. And the player's
 * connections still waiting for their first byte, whose making the watch does not count as
 * silence. See StallWatch.
 */
object StreamProgress {
    val bytes = java.util.concurrent.atomic.AtomicLong(0)

    /** The player's connections that have brought no byte yet: the song each is for, and when it was opened (System.nanoTime). */
    private val waiting = java.util.concurrent.ConcurrentHashMap<Any, Pair<String, Long>>()

    /** [connection], for [songId], was opened at [at] and waits for its first byte. */
    fun opening(connection: Any, songId: String?, at: Long) {
        if (songId == null) waiting.remove(connection) else waiting[connection] = songId to at
    }

    /** [connection] brought its first byte, failed or was closed. */
    fun answered(connection: Any) {
        waiting.remove(connection)
    }

    /**
     * How long, at [now], [songId]'s newest connection has waited for its first byte, or null
     * when none waits. The newest: a connection just cut is let go a moment after the one the
     * player opens in its place.
     */
    fun waitingMs(songId: String?, now: Long): Long? {
        if (songId == null) return null
        val newest = waiting.values.filter { it.first == songId }.maxOfOrNull { it.second } ?: return null
        return ((now - newest) / 1_000_000).coerceAtLeast(0)
    }
}

/**
 * A connection to YouTube's stream servers that came in slower than the song plays, given up so
 * that the player asks again on a fresh one. An HTTP read failure as far as anything else can tell,
 * which is what it is: the player tries again at once from where it was, and a cached part of the
 * song is not taken for a broken copy (BadCachedCopy counts it as the network's).
 */
@OptIn(UnstableApi::class)
class SlowStreamException(message: String, dataSpec: DataSpec) : HttpDataSource.HttpDataSourceException(
    message, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, TYPE_READ,
)

/**
 * The player's fetch of a song from YouTube, watched for pace: see [StreamPace]. Anything that is
 * not a stream server's address goes through untouched. One of these for each song the player
 * loads, kept across its tries, so the cuts are counted per song.
 */
@OptIn(UnstableApi::class)
class PaceGuardDataSource(
    private val upstream: DataSource,
    private val clock: () -> Long = System::nanoTime,
    /** The player's own: how long each of its connections waits for a first byte is told to the stall watch, see StreamProgress. */
    private val firstBytes: Boolean = false,
) : DataSource {
    private val cuts = StreamPace.Cuts()
    private var meter: StreamPace.Meter? = null
    private var need = 0.0
    private var opened: DataSpec? = null

    /** The connection opened last has brought no byte yet. */
    private var awaitingFirstByte = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        opened = dataSpec
        meter = null
        if (StreamPace.isStream(url) && cuts.mayCut()) {
            need = StreamPace.need(url)
            meter = StreamPace.Meter(need) { cuts.keptPace() }
        }
        if (firstBytes) {
            awaitingFirstByte = true
            StreamProgress.opening(this, dataSpec.key, clock())
        }
        return try {
            upstream.open(dataSpec)
        } catch (e: Throwable) {
            firstByteCame()
            throw e
        }
    }

    /** Not waiting for a first byte any more: one came, the end of the stream did, or the connection failed. */
    private fun firstByteCame() {
        if (!awaitingFirstByte) return
        awaitingFirstByte = false
        StreamProgress.answered(this)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val watching = meter ?: return upstream.read(buffer, offset, length).also {
            if (it > 0) StreamProgress.bytes.addAndGet(it.toLong())
            if (it != 0) firstByteCame()
        }
        val start = clock()
        val read = upstream.read(buffer, offset, length)
        if (read > 0) StreamProgress.bytes.addAndGet(read.toLong())
        if (read != 0) firstByteCame()
        if (watching.slow(read, clock() - start)) {
            cuts.cut()
            meter = null
            val after = if (cuts.mayCut()) "" else "; the next one is kept however slow"
            val message = "a stream slower than its song (under ${"%.1f".format(need / 1000)} KB/s), asked again on a fresh connection$after"
            Log.i(TAG, message)
            throw SlowStreamException(message, opened ?: DataSpec(Uri.EMPTY))
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        meter = null
        awaitingFirstByte = false
        StreamProgress.answered(this)
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val firstBytes: Boolean = false) : DataSource.Factory {
        override fun createDataSource(): DataSource = PaceGuardDataSource(upstream.createDataSource(), firstBytes = firstBytes)
    }

    private companion object {
        const val TAG = "PaceGuard"
    }
}
