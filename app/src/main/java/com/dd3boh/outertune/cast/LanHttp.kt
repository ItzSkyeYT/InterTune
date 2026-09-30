/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import javax.net.SocketFactory

/** A device's answer: the status, the headers with their names in lower case, and the body. */
class LanResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** The body as text, in the charset the Content-Type names, UTF-8 when it names none. */
    val text: String
        get() {
            val charset = header("content-type")
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', '\'', ' ')
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charsets.UTF_8
            return String(body, charset)
        }
}

/**
 * Plain HTTP/1.1 to devices on the local network, over a plain socket: a DLNA renderer's
 * description and its SOAP control calls.
 *
 * Not OkHttp, for two reasons. The app targets API 36 with no network security config, so
 * cleartext is refused, and a config can name domains but not an address range, which is all a
 * renderer has. And the app's OkHttp clients carry the YouTube proxy setting, which LAN traffic
 * must never go through. The requests here are few and small: one request per connection, closed
 * after, with one deadline for the whole answer.
 *
 * [socketFactory] is where the app binds the socket to the Wi-Fi network (Network.socketFactory),
 * so that a VPN does not carry it off the LAN.
 */
class LanHttp(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = 4_000,
    private val answerTimeoutMs: Int = 8_000,
    private val maxBody: Int = MAX_BODY,
    private val userAgent: String = USER_AGENT,
) {
    fun get(url: String, headers: List<Pair<String, String>> = emptyList()): LanResponse =
        request("GET", url, headers, null)

    fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray): LanResponse =
        request("POST", url, headers, body)

    fun request(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?): LanResponse {
        val uri = parseUrl(url)
        val port = if (uri.port > 0) uri.port else 80
        val socket = socketFactory.createSocket()
        try {
            socket.connect(InetSocketAddress(uri.host.trim('[', ']'), port), connectTimeoutMs)
            socket.tcpNoDelay = true
            val output = socket.getOutputStream()
            output.write(requestBytes(method, uri, listOf("User-Agent" to userAgent) + headers, body))
            output.flush()
            val input = BufferedInputStream(DeadlineInput(socket, System.nanoTime() + answerTimeoutMs * 1_000_000L))
            return readResponse(input, method, maxBody)
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val USER_AGENT = "InterTune UPnP/1.0 DLNADOC/1.50"

        /** Descriptions and SOAP answers are a few kilobytes; nothing a renderer says needs a megabyte. */
        const val MAX_BODY = 1 shl 20

        private const val MAX_HEAD = 64 * 1024

        /** An http url with a host, or an IOException saying what is wrong with it. */
        fun parseUrl(url: String): URI {
            val uri = try {
                URI(url.trim())
            } catch (e: URISyntaxException) {
                throw IOException("Not a url: $url", e)
            }
            if (!uri.scheme.equals("http", ignoreCase = true)) throw IOException("Only http is spoken to devices: $url")
            if (uri.host.isNullOrEmpty()) throw IOException("No host in $url")
            return uri
        }

        /** The request line, headers and body, ready to write. */
        fun requestBytes(method: String, uri: URI, headers: List<Pair<String, String>>, body: ByteArray?): ByteArray {
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val port = if (uri.port > 0 && uri.port != 80) ":${uri.port}" else ""
            val head = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(uri.host).append(port).append("\r\n")
                for ((name, value) in headers) {
                    append(name).append(": ").append(value.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
                }
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            return if (body == null) head else head + body
        }

        /**
         * Reads one answer: a status line, headers, and a body sized by Content-Length, sent in
         * chunks, or running to the close. A 100 Continue before it is skipped. More than
         * [maxBody] bytes of body is an error rather than a memory problem.
         */
        fun readResponse(input: InputStream, method: String, maxBody: Int = MAX_BODY): LanResponse {
            while (true) {
                val statusLine = readLine(input) ?: throw IOException("The device closed without answering")
                val parts = statusLine.split(' ', limit = 3)
                if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Not an HTTP answer: $statusLine")
                val status = parts[1].toIntOrNull() ?: throw IOException("Not an HTTP status: $statusLine")
                val headers = LinkedHashMap<String, String>()
                var headBytes = statusLine.length
                while (true) {
                    val line = readLine(input) ?: throw IOException("The device closed inside the headers")
                    if (line.isEmpty()) break
                    headBytes += line.length
                    if (headBytes > MAX_HEAD) throw IOException("The headers are too long")
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val name = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[name] = headers[name]?.let { "$it, $value" } ?: value
                }
                if (status in 100..199) continue

                val body = when {
                    method == "HEAD" || status == 204 || status == 304 -> ByteArray(0)
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input, maxBody)
                    headers["content-length"] != null -> {
                        val length = headers["content-length"]!!.substringBefore(',').trim().toLongOrNull()
                            ?: throw IOException("A Content-Length that is not a number")
                        if (length > maxBody) throw IOException("An answer of $length bytes")
                        readExactly(input, length.toInt())
                    }
                    else -> readToEnd(input, maxBody)
                }
                return LanResponse(status, headers, body)
            }
        }

        /** A line ending in CRLF or a bare LF, without its ending, or null at the end of input. */
        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
                if (value == '\n'.code) break
                bytes.write(value)
                if (bytes.size() > MAX_HEAD) throw IOException("A line too long")
            }
            return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray {
            val body = ByteArray(length)
            var done = 0
            while (done < length) {
                val count = input.read(body, done, length - done)
                if (count < 0) throw IOException("The answer ended after $done of $length bytes")
                done += count
            }
            return body
        }

        private fun readToEnd(input: InputStream, maxBody: Int): ByteArray {
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                body.write(buffer, 0, count)
                if (body.size() > maxBody) throw IOException("An answer over $maxBody bytes")
            }
            return body.toByteArray()
        }

        private fun readChunked(input: InputStream, maxBody: Int): ByteArray {
            val body = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: throw IOException("The answer ended inside a chunk")
                val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("A chunk size that is not a number: $sizeLine")
                if (size == 0L) {
                    // Trailers, if any, up to the blank line.
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                    }
                    return body.toByteArray()
                }
                if (body.size() + size > maxBody) throw IOException("An answer over $maxBody bytes")
                body.write(readExactly(input, size.toInt()))
                readLine(input)
            }
        }
    }
}
