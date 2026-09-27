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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * One server is one casting session. It listens on [bindAddress], which the app sets to the
 * phone's address on Wi-Fi, so nothing on mobile data can reach it. With [alsoLoopback] it also
 * listens on 127.0.0.1 at the same port, which debug builds use: `adb forward` arrives on the
 * device's loopback, and a server bound only to the Wi-Fi address refuses it, so a receiver on the
 * development machine could never fetch a song (design.md section 10). Urls always name
 * [bindAddress]; a receiver reached through adb is pointed at 127.0.0.1 itself.
 *
 * Every url carries [token], new for each server, and a request without it gets the same 404 as a
 * url that names nothing, as does a song that [isServable] says is not in the session. Only GET,
 * HEAD and OPTIONS are answered, one request per connection, at most [maxConnections] at once and
 * [maxPerPeer] from any one address, and a request head has 10 seconds ([headTimeoutMs]) and 8 KB
 * to arrive, its first byte 3 ([firstByteTimeoutMs]). So anything else on the Wi-Fi, without the
 * token, can hold only a few workers, and each only for seconds, however many connections it opens
 * and however slowly it talks; connections beyond those limits are told the server is busy, from a
 * thread of their own so that no burst of them holds up the next receiver's.
 *
 * An answer the receiver has stopped reading waits: a receiver that pauses stops reading, and so
 * does one that has all it needs for now. Such a connection is let go only when its worker, or its
 * place under [maxPerPeer], is wanted and it has waited [stalledAfterMs]; the receiver asks again
 * when it wants more, as it does after every seek. So a connection that never reads, token or not,
 * cannot keep a worker from a request that needs one.
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
    private val alsoLoopback: Boolean = false,
    private val maxPerPeer: Int = MAX_PER_PEER,
    private val firstByteTimeoutMs: Int = FIRST_BYTE_TIMEOUT_MS,
    private val stalledAfterMs: Long = STALLED_AFTER_MS,
) {
    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var loopbackSocket: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var refusals: ThreadPoolExecutor? = null
    private var used = false
    private val connections: MutableSet<Connection> = ConcurrentHashMap.newKeySet()

    /** Connections being told the server is busy, closed by [stop] if still waiting their turn. */
    private val refusing: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** How many connections each address has open. Guarded by itself. */
    private val perPeer = HashMap<InetAddress, Int>()

    /** What each song resolved to, by id, and apart from it what its AAC fetch resolved to. */
    private val songs = ConcurrentHashMap<String, Lazy<CastSong>>()
    private val aacSongs = ConcurrentHashMap<String, Lazy<CastSong>>()

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
            // One thread says Busy and waits for each refused connection to close, a queue's worth
            // at most; any more are closed at once.
            val refuser = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(REFUSALS_QUEUED)) { runnable ->
                Thread(runnable, "CastServer-refuse").apply { isDaemon = true }
            }
            refuser.allowCoreThreadTimeOut(true)
            serverSocket = socket
            workers = pool
            refusals = refuser
            Thread({ acceptLoop(socket, pool, refuser) }, "CastServer-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Serving on ${CastHttp.hostForUrl(bindAddress)}:${socket.localPort}")
            if (alsoLoopback && bindAddress != LOOPBACK && !bindAddress.isAnyLocalAddress) {
                val loopback = bindLoopback(socket.localPort) ?: return
                loopbackSocket = loopback
                Thread({ acceptLoop(loopback, pool, refuser) }, "CastServer-accept-loopback").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "Also serving on 127.0.0.1:${loopback.localPort}")
            }
        }
    }

    /**
     * The loopback listener, on [port] like the main one so that one forward reaches both. Only a
     * convenience for testing, so when that port is taken on loopback it goes without.
     */
    private fun bindLoopback(port: Int): ServerSocket? {
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, port), BACKLOG)
            socket
        } catch (e: IOException) {
            Log.w(TAG, "Could not also listen on 127.0.0.1:$port", e)
            runCatching { socket.close() }
            null
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
            loopbackSocket?.let { runCatching { it.close() } }
            loopbackSocket = null
            connections.forEach { runCatching { it.socket.close() } }
            connections.clear()
            workers?.shutdownNow()
            workers = null
            refusals?.shutdownNow()
            refusals = null
            refusing.forEach { runCatching { it.close() } }
            refusing.clear()
            songs.clear()
            aacSongs.clear()
            Log.i(TAG, "Stopped")
        }
    }

    /**
     * Resolves [mediaId] now and says where a receiver fetches it; with [aac], the song fetched
     * again as AAC for a receiver that takes no Opus. Blocking, and it may go to the network, so
     * never on the main thread. Throws [CastUnavailableException] with the reason when the song
     * cannot be had.
     */
    fun prepare(mediaId: String, aac: Boolean = false): CastOffer {
        val song = songFor(mediaId, aac)
        return CastOffer(urlFor(mediaId, song.mimeType, aac), song.mimeType, song.length)
    }

    /** The url for [mediaId], ending in the extension that goes with [mimeType]. */
    fun urlFor(mediaId: String, mimeType: String, aac: Boolean = false): String {
        val port = boundPort.takeIf { it > 0 } ?: error("The cast server is not running")
        return CastHttp.songUrl(bindAddress, port, token, mediaId, CastMime.extensionFor(mimeType), aac)
    }

    /** Resolves [mediaId] afresh on its next request, after the receiver said it failed. */
    fun forget(mediaId: String) {
        songs.remove(mediaId)
        aacSongs.remove(mediaId)
    }

    private fun songFor(mediaId: String, aac: Boolean = false): CastSong =
        if (aac) {
            aacSongs.getOrPut(mediaId) { lazy(LazyThreadSafetyMode.SYNCHRONIZED) { source.openAac(mediaId) } }.value
        } else {
            songs.getOrPut(mediaId) { lazy(LazyThreadSafetyMode.SYNCHRONIZED) { source.open(mediaId) } }.value
        }

    private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor, refuser: ThreadPoolExecutor) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!socket.isClosed) Log.w(TAG, "Accept failed", e)
                break
            }
            val connection = Connection(client)
            if (!admit(connection)) {
                // As many from this address as any one receiver needs. One of its own that has
                // stopped reading makes room for its next try.
                letGoOfStalled(connection.peer)
                refuse(client, refuser)
                continue
            }
            connections += connection
            try {
                pool.execute { serve(connection) }
            } catch (e: RejectedExecutionException) {
                // Every worker is busy sending, or the server is stopping. Said plainly, so a
                // receiver retries rather than waiting on a connection nobody will answer, and a
                // worker held by a receiver that stopped reading long ago is freed for that retry.
                release(connection)
                letGoOfStalled(null)
                refuse(client, refuser)
            }
        }
    }

    /** Counts [connection] against its address, or false when that address has all it may have. */
    private fun admit(connection: Connection): Boolean {
        val peer = connection.peer ?: return true
        synchronized(perPeer) {
            val open = perPeer[peer] ?: 0
            if (open >= maxPerPeer) return false
            perPeer[peer] = open + 1
        }
        return true
    }

    /** Undoes [admit], once. */
    private fun release(connection: Connection) {
        if (!connection.released.compareAndSet(false, true)) return
        connections -= connection
        val peer = connection.peer ?: return
        synchronized(perPeer) {
            val open = (perPeer[peer] ?: 1) - 1
            if (open <= 0) perPeer.remove(peer) else perPeer[peer] = open
        }
    }

    /**
     * Closes the connections, from [peer] alone when it is given, whose answer has waited more than
     * [stalledAfterMs] for the receiver to read it. The worker sending it is free as soon as its
     * write fails.
     */
    private fun letGoOfStalled(peer: InetAddress?) {
        val now = System.nanoTime()
        val limit = stalledAfterMs * 1_000_000L
        for (connection in connections) {
            if (peer != null && connection.peer != peer) continue
            val stalled = connection.stalledFor(now)
            if (stalled > limit) {
                Log.i(TAG, "Letting go of a connection that has not read for ${stalled / 1_000_000_000L} s")
                runCatching { connection.socket.close() }
            }
        }
    }

    /**
     * Says Busy and closes, on the refusing thread, so that the accept loop is never held up by a
     * refused connection; when that thread is backed up, the connection is simply closed.
     */
    private fun refuse(client: Socket, refuser: ThreadPoolExecutor) {
        refusing += client
        try {
            refuser.execute {
                try {
                    runCatching { sendText(client.getOutputStream(), "GET", 503, "Busy") }
                    closeQuietly(client)
                } finally {
                    refusing -= client
                }
            }
        } catch (e: RejectedExecutionException) {
            refusing -= client
            runCatching { client.close() }
        }
    }

    private fun serve(connection: Connection) {
        val client = connection.socket
        try {
            client.tcpNoDelay = true
            val now = System.nanoTime()
            val input = DeadlineInput(client, now + headTimeoutMs * 1_000_000L, firstByteBy = now + firstByteTimeoutMs * 1_000_000L)
            handle(BufferedInputStream(input), WatchedOutput(connection, client.getOutputStream()))
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "A request head took too long: ${e.message}")
        } catch (e: IOException) {
            // The receiver closed the connection, which it does after every seek, or the song
            // could not be read to the end. Either way the connection is done.
            Log.d(TAG, "Connection ended: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Request failed", e)
        } finally {
            closeQuietly(client)
            release(connection)
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
            songFor(path.mediaId, path.aac)
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
    }

    /** One open connection, and since when a write to it has waited for the receiver to read. */
    private class Connection(val socket: Socket) {
        val peer: InetAddress? = socket.inetAddress
        val released = AtomicBoolean()

        @Volatile
        var writing = false

        @Volatile
        var writeStartedAt = 0L

        /** For how long, in nanoseconds, the write under way has waited; 0 when none is. */
        fun stalledFor(now: Long): Long = if (writing) now - writeStartedAt else 0L
    }

    /** A connection's output, which notes when each write starts and ends, for [letGoOfStalled]. */
    private class WatchedOutput(private val connection: Connection, private val output: OutputStream) : OutputStream() {
        override fun write(byte: Int) = watched { output.write(byte) }

        override fun write(buffer: ByteArray, offset: Int, length: Int) = watched { output.write(buffer, offset, length) }

        override fun flush() = watched { output.flush() }

        private inline fun watched(write: () -> Unit) {
            connection.writeStartedAt = System.nanoTime()
            connection.writing = true
            try {
                write()
            } finally {
                connection.writing = false
            }
        }
    }

    companion object {
        private const val TAG = "CastServer"

        /** Debug builds listen here first, so one `adb forward` covers every test run. */
        const val DEBUG_PORT = 47800

        const val MAX_CONNECTIONS = 8

        /**
         * From one address at once. A receiver uses one or two, a third at most when it looks
         * something up at the end of a file, and a paused one keeps its own open.
         */
        const val MAX_PER_PEER = 4
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val BACKLOG = 16
        private const val HEAD_TIMEOUT_MS = 10_000

        /** A receiver sends its request as soon as it connects. */
        private const val FIRST_BYTE_TIMEOUT_MS = 3_000

        /** How long an answer may wait for the receiver to read before its worker can be taken back. */
        private const val STALLED_AFTER_MS = 30_000L
        private const val REFUSALS_QUEUED = 16
        private const val BUFFER_BYTES = 64 * 1024
        private const val DRAIN_TIMEOUT_MS = 250
        private const val MAX_DRAIN_BYTES = 64 * 1024
    }
}
