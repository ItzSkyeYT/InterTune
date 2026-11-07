/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.BuildConfig
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Faults made on purpose, for debug builds only: the developer options' "DEBUG: Make playback
 * fail" row. How a phone shows that the player keeps the music going through a server that does
 * not answer, without waiting for a bad day like 20 Oct 2026.
 *
 * In memory only, off at every start, and never in a release: [enabled] is the build's own flag,
 * and nothing is added to any client when it is off.
 */
object FaultInjection {
    enum class Mode(val words: String) {
        NONE("Off"),
        HEAD_HANGS("The stream check hangs"),
        PLAYER_HANGS_VISIONOS("/player hangs for VISIONOS"),
        STREAM_STOPS("The stream stops after 1 MB, once"),
        STREAM_403("The stream gets 403 after 1 MB"),
        SONG_GONE("The next song fetched is gone from YouTube"),
        NOT_AUDIO("The stream answers with a page, not audio"),
    }

    /** Where an interceptor sits. */
    enum class Target { CHECK, STREAM }

    private const val TAG = "FaultInjection"
    private const val ONE_MB = 1024L * 1024

    /** How long a /player request made to hang waits before it fails, as ktor's own limit once did. */
    const val PLAYER_HANG_MS = 30_000L

    /** Only a debug build has the row, and only a debug build obeys it. */
    @Volatile
    internal var enabled: Boolean = BuildConfig.DEBUG

    @Volatile
    var mode: Mode = Mode.NONE
        private set

    private val delivered = AtomicLong(0)
    private val stalled = AtomicBoolean(false)

    @Volatile
    private var refused: String? = null

    /** The song [Mode.SONG_GONE] fell on: see [callsGone]. */
    @Volatile
    private var gone: String? = null

    fun set(mode: Mode) {
        if (!enabled) return
        this.mode = mode
        delivered.set(0)
        stalled.set(false)
        refused = null
        gone = null
        Log.w(TAG, "fault injection: ${mode.words}")
    }

    /**
     * Whether the player's fetch of [videoId] fails as a song every client calls gone
     * ([Mode.SONG_GONE]): the first song whose stream is fetched once the mode is on, and from
     * then on that one only, so that a queue can be played up to it and past it, as past a real
     * one (issue #82, GoneSkip). Put a song next with Play next after choosing the mode.
     */
    fun callsGone(videoId: String): Boolean {
        if (!enabled || mode != Mode.SONG_GONE) return false
        val first = synchronized(this) { gone ?: videoId.also { gone = it } }
        if (first == videoId) Log.w(TAG, "fault injection: $videoId is called gone")
        return first == videoId
    }

    /**
     * A cold start from a media button, slowed or emptied (issue #98). The row above is lost with
     * the process, so this one is a file in the app's own files, written from the shell while the
     * app is closed: `adb shell run-as <debug package> sh -c 'echo "slow 12000" > files/fault-resume'`.
     * "slow <ms>" holds the resumption that long, "empty" finds no saved queue, and "nohold" with
     * either leaves out the placeholder notification of MediaButtonStart, as before it. Removed
     * with `rm files/fault-resume`. Read only by a debug build.
     */
    class ResumeFault(val delayMs: Long, val empty: Boolean, val noHold: Boolean)

    fun resumeFault(context: Context): ResumeFault? {
        if (!enabled) return null
        val words = runCatching { File(context.filesDir, "fault-resume").takeIf { it.isFile }?.readText() }
            .getOrNull()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (words.isEmpty()) return null
        val slow = words.indexOf("slow").takeIf { it >= 0 }?.let { words.getOrNull(it + 1)?.toLongOrNull() } ?: 0L
        return ResumeFault(delayMs = slow, empty = "empty" in words, noHold = "nohold" in words).also {
            Log.w(TAG, "fault injection: resumption ${words.joinToString(" ")}")
        }
    }

    /** Whether a /player request of [client] is to hang. */
    fun hangsPlayer(clientName: String, isVisionosItself: Boolean): Boolean =
        enabled && mode == Mode.PLAYER_HANGS_VISIONOS && clientName == "VISIONOS" && isVisionosItself

    /** Added to a client only when [enabled]. */
    fun interceptor(target: Target): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val now = mode
        when {
            !enabled || now == Mode.NONE -> chain.proceed(request)
            target == Target.CHECK && now == Mode.HEAD_HANGS && request.method == "HEAD" -> {
                // A server that takes the request and never answers: given up on only by a
                // cancel or the read limit, as a real one is.
                hang(chain)
                throw SocketTimeoutException("fault injection: the check hung")
            }
            target == Target.STREAM && now == Mode.STREAM_403 && request.url.toString() == refused ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden (fault injection)")
                    .body("".toResponseBody(null))
                    .build()
            // What a network, a VPN or an ad blocker answering in YouTube's place sends: a page with a
            // 200, which no extractor reads (a Discord report of 7 Nov 2026, "Source error (3003)").
            target == Target.STREAM && now == Mode.NOT_AUDIO ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html")
                    .body("<!doctype html><html><body><h1>This site is blocked</h1><p>fault injection</p></body></html>".toResponseBody(null))
                    .build()
            target == Target.STREAM && (now == Mode.STREAM_STOPS || now == Mode.STREAM_403) -> {
                val response = chain.proceed(request)
                val body = response.body ?: return@Interceptor response
                response.newBuilder().body(Counted(body, chain, request.url.toString(), now)).build()
            }
            else -> chain.proceed(request)
        }
    }

    private fun hang(chain: Interceptor.Chain) {
        val until = System.nanoTime() + chain.readTimeoutMillis().coerceAtLeast(1_000) * 1_000_000L
        while (System.nanoTime() < until) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            Thread.sleep(100)
        }
    }

    private class Counted(
        private val body: ResponseBody,
        private val chain: Interceptor.Chain,
        private val url: String,
        private val mode: Mode,
    ) : ResponseBody() {
        private val source: BufferedSource by lazy {
            object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (delivered.get() >= ONE_MB) {
                        if (mode == Mode.STREAM_STOPS && stalled.compareAndSet(false, true)) {
                            Log.w(TAG, "fault injection: the stream stops coming in")
                            hang(chain)
                            throw SocketTimeoutException("fault injection: the stream stopped")
                        }
                        if (mode == Mode.STREAM_403 && refused == null) {
                            refused = url
                            Log.w(TAG, "fault injection: this address is refused from now on")
                            throw IOException("fault injection: connection reset before the 403")
                        }
                    }
                    val read = super.read(sink, byteCount)
                    if (read > 0) delivered.addAndGet(read)
                    return read
                }
            }.buffer()
        }

        override fun contentType() = body.contentType()

        override fun contentLength() = body.contentLength()

        override fun source(): BufferedSource = source

        override fun close() = body.close()
    }
}
