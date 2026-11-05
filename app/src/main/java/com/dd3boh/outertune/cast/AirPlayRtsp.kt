/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One answer from the speaker: its status, its headers with their names in lower case, and its body. */
class RtspResponse(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray = ByteArray(0)) {
    fun header(name: String): String? = headers[name.lowercase()]

    override fun toString() = "$status $reason"
}

/** The speaker refused a request, with the status it gave. */
class RtspRefused(val method: String, val status: Int, val reason: String) :
    CastLinkException("$method refused: $status $reason", problem = LinkProblem.NOT_A_RECEIVER)

/** Writing requests and reading answers, apart from any socket. */
object RtspMessages {
    /** A request: its line, its headers in order, Content-Length when there is a body, then the body. */
    fun request(method: String, uri: String, headers: List<Pair<String, String>>, body: ByteArray?, version: String = "RTSP/1.0"): ByteArray {
        val head = buildString {
            append(method).append(' ').append(uri).append(' ').append(version).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return if (body == null) head else head + body
    }

    /** The next answer on [input], or null when the speaker closed the connection between answers. */
    fun readResponse(input: InputStream): RtspResponse? {
        val statusLine = readLine(input) ?: return null
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !(parts[0].startsWith("RTSP/") || parts[0].startsWith("HTTP/"))) {
            throw IOException("Not an RTSP answer: ${statusLine.take(80)}")
        }
        val status = parts[1].toIntOrNull() ?: throw IOException("Not an RTSP status: ${statusLine.take(80)}")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("The answer ended in its headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) throw IOException("An answer of $length bytes")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) throw IOException("The answer ended in its body")
            read += count
        }
        return RtspResponse(status, parts.getOrElse(2) { "" }, headers, body)
    }

    /** A line without its CRLF; null at the end of the stream before any byte. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (out.size() == 0) return null
                break
            }
            if (byte == '\n'.code) break
            if (byte != '\r'.code) out.write(byte)
            if (out.size() > MAX_LINE) throw IOException("A line too long in an answer")
        }
        return out.toString("UTF-8")
    }

    /** The parameters of a Transport header, `server_port=6000;control_port=6001`, by name in lower case. */
    fun transport(header: String?): Map<String, String> =
        header.orEmpty().split(';').mapNotNull { part ->
            val equals = part.indexOf('=')
            if (equals <= 0) null else part.substring(0, equals).trim().lowercase() to part.substring(equals + 1).trim()
        }.toMap()

    private const val MAX_LINE = 8 * 1024
    private const val MAX_BODY = 1024 * 1024
}

/**
 * The RTSP connection to one speaker: requests one at a time, each numbered (CSeq), with the
 * headers every AirPlay sender sends, and the answers read on a thread of their own, so that the
 * speaker closing the connection between requests is noticed at once ([onClosed]): that is how a
 * speaker says another phone or computer has taken it over.
 */
class AirPlayRtsp(
    private val socket: Socket,
    private val userAgent: String,
    private val onClosed: (IOException?) -> Unit,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
    random: SecureRandom = SecureRandom(),
) : Closeable {
    val local: InetAddress get() = socket.localAddress
    val remote: InetAddress get() = socket.inetAddress

    /** The session's number in the SDP and the url, and the client's ids a speaker may use to call back (DACP). */
    val sessionId: Long = random.nextInt().toLong() and 0xFFFFFFFFL
    private val clientId: String = String.format(java.util.Locale.ROOT, "%016X", random.nextLong())
    private val activeRemote: String = (random.nextInt().toLong() and 0xFFFFFFFFL).toString()

    /** What the speaker named the session in its answer to SETUP. */
    @Volatile
    var session: String? = null

    val uri: String get() = "rtsp://${(local.hostAddress ?: "0.0.0.0").substringBefore('%').let { if (':' in it) "[$it]" else it }}/$sessionId"

    private val output: OutputStream = socket.getOutputStream()
    private val answers = LinkedBlockingQueue<Any>()
    private val lock = Any()
    private var cseq = 0

    @Volatile
    private var closed = false

    private val reader = Thread({ readLoop(BufferedInputStream(socket.getInputStream())) }, "AirPlay-rtsp").apply {
        isDaemon = true
        start()
    }

    /**
     * Sends a request and waits for its answer. Throws [RtspRefused] for anything but 200, and an
     * IOException when the speaker cannot be reached or does not answer in time.
     */
    fun request(
        method: String,
        uri: String = this.uri,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
        version: String = "RTSP/1.0",
    ): RtspResponse {
        val answer = exchange(method, uri, headers, body, contentType, version)
        if (answer.status != 200) throw RtspRefused(method, answer.status, answer.reason)
        return answer
    }

    /** The same, returning whatever status the speaker gave. */
    fun exchange(
        method: String,
        uri: String = this.uri,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
        version: String = "RTSP/1.0",
    ): RtspResponse = synchronized(lock) {
        if (closed) throw IOException("The connection to the speaker is closed")
        answers.clear()
        val all = buildList {
            add("CSeq" to (++cseq).toString())
            add("User-Agent" to userAgent)
            add("Client-Instance" to clientId)
            add("DACP-ID" to clientId)
            add("Active-Remote" to activeRemote)
            session?.let { add("Session" to it) }
            if (contentType != null) add("Content-Type" to contentType)
            addAll(headers)
        }
        output.write(RtspMessages.request(method, uri, all, body, version))
        output.flush()
        awaitAnswer(method, cseq)
    }

    /** The answer numbered [number]; one to a request that timed out before is not this one's. */
    private fun awaitAnswer(method: String, number: Int): RtspResponse {
        val deadline = System.nanoTime() + answerTimeoutMs * 1_000_000L
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            val answer = (if (left > 0) answers.poll(left, TimeUnit.MILLISECONDS) else null)
                ?: throw SocketTimeoutException("$method: no answer in ${answerTimeoutMs / 1000} s")
            when (answer) {
                is RtspResponse -> if (answer.header("cseq")?.toIntOrNull()?.let { it != number } != true) return answer
                is IOException -> throw answer
                else -> throw IOException("The connection to the speaker closed")
            }
        }
    }

    private fun readLoop(input: InputStream) {
        var failure: IOException? = null
        try {
            while (true) {
                val answer = RtspMessages.readResponse(input) ?: break
                answers.put(answer)
            }
        } catch (e: IOException) {
            failure = e
        }
        answers.put(failure ?: CLOSED)
        if (!closed) {
            closed = true
            onClosed(failure)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    private companion object {
        val CLOSED = Any()
        const val ANSWER_TIMEOUT_MS = 10_000L
    }
}
