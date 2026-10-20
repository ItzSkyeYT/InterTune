/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

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

    fun set(mode: Mode) {
        if (!enabled) return
        this.mode = mode
        delivered.set(0)
        stalled.set(false)
        refused = null
        Log.w(TAG, "fault injection: ${mode.words}")
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
