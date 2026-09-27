/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a receiver is told about a song: where to fetch it, what it is, and how long. */
data class CastOffer(val url: String, val mimeType: String, val length: Long)

/**
 * The HTTP server a receiver fetches the queue's songs from while the phone is casting.
 *
 * A receiver cannot reach what the player plays: local files are paths on the phone, downloads and
 * cached songs live only in the app's caches, and a googlevideo url is tied to the phone and
 * expires. So the phone serves every song itself, through [source], which in the app is the
 * player's own resolver (experiments/casting/design.md, sections 3 and 4).
 *
 * One server is one casting session. It listens on [bindAddress] alone, which the app sets to the
 * phone's address on Wi-Fi, so nothing on mobile data can reach it. Every url carries [token], new
 * for each server, and a request without it gets the same 404 as a url that names nothing, as does
 * a song that [isServable] says is not in the session. Only GET, HEAD and OPTIONS are answered, one
 * request per connection, at most [maxConnections] at once, and a request head has 10 seconds
 * ([headTimeoutMs]) and 8 KB to arrive.
 *
 * Songs are streamed in 64 KB pieces, never held whole, and a song is resolved once per session:
 * the HEAD, the first GET and every seek after it reuse what [source] said the first time.
 */
class CastServer(
    private val source: CastSource,
    private val bindAddress: InetAddress,
    private val isServable: (mediaId: String) -> Boolean,
    private val port: Int = 0,
    val token: String = CastHttp.newToken(),
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val headTimeoutMs: Int = HEAD_TIMEOUT_MS,
) {
    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var used = false
    private val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val songs = ConcurrentHashMap<String, Lazy<CastSong>>()

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** The port it listens on, or -1 when it is not running. */
    val boundPort: Int get() = serverSocket?.takeIf { !it.isClosed }?.localPort ?: -1

    /**
     * Opens the socket and starts answering. [port] is tried first; if something else holds it
     * the system picks one, so a second session, or another app, never stops casting. A server is
     * started once: a new session makes a new server, and so a new token.
     */
    fun start() {
        synchronized(lock) {
            check(!used) { "A cast server is started once" }
            used = true
            val socket = bind()
            val threadCount = AtomicInteger()
            val pool = ThreadPoolExecutor(
                maxConnections, maxConnections, 30, TimeUnit.SECONDS, SynchronousQueue(),
            ) { runnable ->
                Thread(runnable, "CastServer-${threadCount.incrementAndGet()}").apply { isDaemon = true }
            }
            pool.allowCoreThreadTimeOut(true)
            serverSocket = socket
            workers = pool
            Thread({ acceptLoop(socket, pool) }, "CastServer-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Serving on ${CastHttp.hostForUrl(bindAddress)}:${socket.localPort}")
        }
    }

    private fun bind(): ServerSocket {
        fun on(port: Int) = ServerSocket().apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(bindAddress, port), BACKLOG)
            } catch (e: IOException) {
                close()
                throw e
            }
        }
        if (port == 0) return on(0)
        return try {
            on(port)
        } catch (e: BindException) {
            Log.w(TAG, "Port $port is taken, letting the system pick one", e)
            on(0)
        }
    }

    /**
     * Stops answering, closes every open connection, which ends any song being sent, and forgets
     * what was resolved. Safe to call more than once, and from any thread.
     */
    fun stop() {
        synchronized(lock) {
            val socket = serverSocket ?: return
            serverSocket = null
            runCatching { socket.close() }
            connections.forEach { runCatching { it.close() } }
            connections.clear()
            workers?.shutdownNow()
            workers = null
            songs.clear()
            Log.i(TAG, "Stopped")
        }
    }

    /**
     * Resolves [mediaId] now and says where a receiver fetches it. Blocking, and it may go to the
     * network, so never on the main thread. Throws [CastUnavailableException] with the reason when
     * the song cannot be had.
     */
    fun prepare(mediaId: String): CastOffer {
        val song = songFor(mediaId)
        return CastOffer(urlFor(mediaId, song.mimeType), song.mimeType, song.length)
    }

    /** The url for [mediaId], ending in the extension that goes with [mimeType]. */
    fun urlFor(mediaId: String, mimeType: String): String {
        val port = boundPort.takeIf { it > 0 } ?: error("The cast server is not running")
        return CastHttp.songUrl(bindAddress, port, token, mediaId, CastMime.extensionFor(mimeType))
    }

    /** Resolves [mediaId] afresh on its next request, after the receiver said it failed. */
    fun forget(mediaId: String) {
        songs.remove(mediaId)
    }

    private fun songFor(mediaId: String): CastSong =
        songs.getOrPut(mediaId) { lazy(LazyThreadSafetyMode.SYNCHRONIZED) { source.open(mediaId) } }.value

    private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!socket.isClosed) Log.w(TAG, "Accept failed", e)
                break
            }
            connections += client
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                // Every worker is busy sending, or the server is stopping. Said plainly, so a
                // receiver retries rather than waiting on a connection nobody will answer.
                runCatching {
                    client.soTimeout = HEAD_TIMEOUT_MS
                    sendText(client.getOutputStream(), "GET", 503, "Busy")
                }
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DeadlineInput(client, System.nanoTime() + headTimeoutMs * 1_000_000L)
            handle(BufferedInputStream(input), client.getOutputStream())
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "A request head took too long")
        } catch (e: IOException) {
            // The receiver closed the connection, which it does after every seek, or the song
            // could not be read to the end. Either way the connection is done.
            Log.d(TAG, "Connection ended: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Request failed", e)
        } finally {
            closeQuietly(client)
        }
    }

    private fun handle(input: InputStream, output: OutputStream) {
        val head = when (val read = CastHttp.readHead(input)) {
            is HeadRead.Head -> read.text
            HeadRead.TooLarge -> return sendText(output, "GET", 431, "Request head too large")
            HeadRead.Closed -> return
        }
        val request = CastHttp.parseRequest(head) ?: return sendText(output, "GET", 400, "Bad request")
        val method = request.method
        if (method != "GET" && method != "HEAD" && method != "OPTIONS") {
            return sendText(output, method, 405, "Method not allowed", "Allow" to "GET, HEAD, OPTIONS")
        }
        val path = CastHttp.parsePath(request.target)
        if (path == null || !CastHttp.tokenMatches(token, path.token)) {
            return sendText(output, method, 404, "Not found")
        }
        if (method == "OPTIONS") {
            output.write(CastHttp.head(204, CastHttp.optionsReply()))
            output.flush()
            return
        }
        if (!isServable(path.mediaId)) return sendText(output, method, 404, "Not found")

        val song = try {
            songFor(path.mediaId)
        } catch (e: CastUnavailableException) {
            Log.i(TAG, "Cannot serve ${path.mediaId}: ${e.message}")
            return sendText(output, method, 503, e.message ?: "Unavailable")
        } catch (e: Exception) {
            Log.w(TAG, "Resolving ${path.mediaId} failed", e)
            return sendText(output, method, 503, "The song could not be found")
        }

        val reply = CastHttp.songReply(request, song.mimeType, song.length)
        output.write(CastHttp.head(reply.status, reply.headers))
        if (method == "HEAD" || reply.bodyLength == 0L) {
            output.flush()
            return
        }
        song.openAt(reply.bodyStart).use { reader ->
            val buffer = ByteArray(BUFFER_BYTES)
            var left = if (reply.bodyLength >= 0) reply.bodyLength else Long.MAX_VALUE
            while (left > 0) {
                val count = reader.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (count < 0) break
                output.write(buffer, 0, count)
                left -= count
            }
            output.flush()
            if (reply.bodyLength >= 0 && left > 0) {
                // Closing now tells the receiver the body is short, and it asks again from where
                // it stopped.
                Log.w(TAG, "${path.mediaId} ended $left bytes early")
            }
        }
    }

    private fun sendText(output: OutputStream, method: String, status: Int, message: String, vararg extra: Pair<String, String>) {
        val (headers, body) = CastHttp.textReply(message, *extra)
        output.write(CastHttp.head(status, headers))
        if (method != "HEAD") output.write(body)
        output.flush()
    }

    /**
     * Closes a connection without losing the answer on it. A socket closed with request bytes
     * still unread (a refused body, a request never read because every worker was busy) makes the
     * system reset the connection, and a reset can discard the answer before the receiver reads
     * it. So the answer is ended first, and whatever the peer still sends is read off for a
     * moment.
     */
    private fun closeQuietly(client: Socket) {
        runCatching {
            if (!client.isClosed && !client.isOutputShutdown) {
                client.shutdownOutput()
                val input = DeadlineInput(client, System.nanoTime() + DRAIN_TIMEOUT_MS * 1_000_000L)
                val sink = ByteArray(4096)
                var drained = 0
                while (drained < MAX_DRAIN_BYTES) {
                    val count = input.read(sink)
                    if (count < 0) break
                    drained += count
                }
            }
        }
        runCatching { client.close() }
        connections -= client
    }

    /**
     * A socket's input with one deadline for everything read from it, in [System.nanoTime] time.
     * A read timeout alone is per read, so a peer sending a byte every few seconds could hold a
     * worker for hours; this shortens the timeout of each read to what is left of the deadline.
     */
    private class DeadlineInput(private val socket: Socket, private val deadline: Long) : InputStream() {
        private val input = socket.getInputStream()

        private fun arm() {
            val leftMs = (deadline - System.nanoTime()) / 1_000_000L
            if (leftMs <= 0) throw SocketTimeoutException("Past the deadline")
            socket.soTimeout = leftMs.toInt()
        }

        override fun read(): Int {
            arm()
            return input.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            arm()
            return input.read(buffer, offset, length)
        }
    }

    companion object {
        private const val TAG = "CastServer"

        /** Debug builds listen here first, so one `adb forward` covers every test run. */
        const val DEBUG_PORT = 47800

        const val MAX_CONNECTIONS = 8
        private const val BACKLOG = 16
        private const val HEAD_TIMEOUT_MS = 10_000
        private const val BUFFER_BYTES = 64 * 1024
        private const val DRAIN_TIMEOUT_MS = 250
        private const val MAX_DRAIN_BYTES = 64 * 1024
    }
}
