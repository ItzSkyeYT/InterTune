/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The stream check's call, against a server on this machine: one that takes the connection and
 * never answers, as the stream hosts did on 20 Oct 2026, and one that answers. The walk's tests
 * put a delay in its place, so this is where the real call is seen to stop when given up.
 */
class CallAwaitTest {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    private fun head(port: Int) = client.newCall(Request.Builder().url("http://127.0.0.1:$port/videoplayback").head().build())

    @Test
    fun `a check given up is cancelled for real, and nothing waits for its own 30 s`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            // Takes the connection and says nothing.
            val held = mutableListOf<java.net.Socket>()
            val accepting = thread(isDaemon = true) { runCatching { held += server.accept() } }
            val call = head(server.localPort)
            val started = System.nanoTime()
            val answer = runBlocking { withTimeoutOrNull(300) { call.await() } }
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertNull(answer)
            assertTrue("the call itself is cancelled, not left holding its connection", call.isCanceled())
            assertTrue("given up at its limit: took $tookMs ms", tookMs < 2_000)
            accepting.join(1_000)
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `an answer is read as it came`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().bufferedReader().let { reader -> while (reader.readLine()?.isNotEmpty() == true) Unit }
                        socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            }
            val status = runBlocking { head(server.localPort).await().use { it.code } }
            assertEquals(403, status)
        }
    }
}
