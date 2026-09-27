/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom

/** What a Range header asks for, once checked against the length of the song. */
sealed interface RangeAnswer {
    /** The whole song: no Range at all, one that could not be read, or several at once. */
    data object Whole : RangeAnswer

    /** One span, both ends inclusive, already cut to the song. */
    data class Part(val first: Long, val last: Long) : RangeAnswer {
        val length: Long get() = last - first + 1
    }

    /** A span that starts past the end, answered 416. */
    data object Unsatisfiable : RangeAnswer
}

/** A request line and its headers. Header names are kept in lower case. */
class CastRequest(val method: String, val target: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * A song url taken apart: the session's token, the song's id, and whether it is the song fetched
 * again as AAC for a receiver that takes no Opus (`/aac/` rather than `/s/`).
 */
data class CastPath(val token: String, val mediaId: String, val aac: Boolean = false)

/** What reading a request head off a connection came to. */
sealed interface HeadRead {
    data class Head(val text: String) : HeadRead

    /** More than the cap before the blank line, answered 431 rather than read on. */
    data object TooLarge : HeadRead

    /** The peer went away, or sent nothing, before the head was complete. */
    data object Closed : HeadRead
}

/**
 * What the server answers to one song request: the status, the headers, and which bytes follow.
 * [bodyLength] is -1 when the song's length is not known and the body runs to its end.
 */
data class SongReply(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val bodyStart: Long,
    val bodyLength: Long,
)

/**
 * The rules of the cast server's HTTP, kept apart from its sockets so they can be tested without
 * any.
 *
 * Receivers are more varied than browsers. The FCast receivers play through ExoPlayer or
 * GStreamer and seek with Range requests; DLNA renderers probe with HEAD, want the DLNA headers,
 * and some want the url to end in an extension. What is served is one song per url, under a token
 * that is new each session.
 */
object CastHttp {
    /** Longest request head read before the connection is answered 431 and closed. */
    const val MAX_HEAD_BYTES = 8 * 1024

    /** The byte-range flags DLNA renderers look for before they offer seeking. */
    const val DLNA_FLAGS = "DLNA.ORG_FLAGS=01700000000000000000000000000000"

    // Range

    /**
     * Reads a Range header against a song of [total] bytes, following RFC 9110 section 14.
     *
     * One range is served, in any of its three forms: `bytes=a-b`, `bytes=a-` and the suffix
     * `bytes=-n`. More than one is answered with the whole song, which the RFC allows and which
     * saves writing multipart bodies no receiver asks for. A header that cannot be read is
     * ignored, as the RFC says, rather than refused. With the length unknown ([total] below 0) no
     * Content-Range can be written, so the whole song is sent.
     */
    fun parseRange(header: String?, total: Long): RangeAnswer {
        if (header.isNullOrBlank() || total < 0) return RangeAnswer.Whole
        val trimmed = header.trim()
        val equals = trimmed.indexOf('=')
        if (equals < 0 || !trimmed.substring(0, equals).trim().equals("bytes", ignoreCase = true)) {
            return RangeAnswer.Whole
        }
        val specs = trimmed.substring(equals + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (specs.size != 1) return RangeAnswer.Whole
        val spec = specs.single()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeAnswer.Whole
        val from = spec.substring(0, dash)
        val to = spec.substring(dash + 1)

        if (from.isEmpty()) {
            // The last n bytes. A number too long for a Long is simply more than the song holds.
            if (!to.isDigits()) return RangeAnswer.Whole
            val count = to.toLongOrNull() ?: Long.MAX_VALUE
            if (count == 0L || total == 0L) return RangeAnswer.Unsatisfiable
            return RangeAnswer.Part(maxOf(0L, total - count), total - 1)
        }
        if (!from.isDigits()) return RangeAnswer.Whole
        val first = from.toLongOrNull() ?: return RangeAnswer.Unsatisfiable
        // An end before the start makes the header invalid, so it is ignored; an end past the song
        // is only cut to it.
        val last = when {
            to.isEmpty() -> Long.MAX_VALUE
            !to.isDigits() -> return RangeAnswer.Whole
            else -> (to.toLongOrNull() ?: Long.MAX_VALUE).also { if (it < first) return RangeAnswer.Whole }
        }
        if (first >= total) return RangeAnswer.Unsatisfiable
        return RangeAnswer.Part(first, minOf(last, total - 1))
    }

    private fun String.isDigits() = isNotEmpty() && all { it in '0'..'9' }

    // The token

    /**
     * A new session token: 128 bits from SecureRandom, as hex so it is safe in any url and any
     * log without escaping.
     */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return bytes.joinToString("") { hex(it).lowercase() }
    }

    /**
     * Whether [given] is the session's token, compared in constant time so that how long a wrong
     * guess takes to refuse says nothing about how close it was.
     */
    fun tokenMatches(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

    // Urls

    /**
     * The url a receiver fetches a song from: `http://host:port/c/{token}/s/{id}.{ext}`, or with
     * [aac] `/c/{token}/aac/{id}.m4a` for the same song fetched as AAC.
     *
     * The extension is for the renderers that will not play a url without one; the server itself
     * goes by the id. The id is percent-encoded, dots included, so the last dot is always the one
     * before the extension.
     */
    fun songUrl(host: InetAddress, port: Int, token: String, mediaId: String, extension: String, aac: Boolean = false): String =
        "http://${hostForUrl(host)}:$port/c/$token/${if (aac) "aac" else "s"}/${encodeSegment(mediaId)}.$extension"

    /** An address as it goes in a url: IPv6 in brackets, with a zone's % escaped. */
    fun hostForUrl(host: InetAddress): String {
        val literal = host.hostAddress ?: error("No address")
        return if (host is Inet6Address) "[${literal.replace("%", "%25")}]" else literal
    }

    /** Everything but letters, digits, `-`, `_` and `~` as %XX of its UTF-8 bytes. */
    fun encodeSegment(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' || char == '~') {
                append(char)
            } else {
                append('%').append(hex(byte))
            }
        }
    }

    /** Two upper-case hex digits, written out by hand so no locale can change them. */
    private fun hex(byte: Byte): String {
        val value = byte.toInt() and 0xFF
        return "${HEX_DIGITS[value shr 4]}${HEX_DIGITS[value and 0x0F]}"
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** The reverse of [encodeSegment], or null for a malformed escape. A plus stays a plus. */
    fun decodeSegment(text: String): String? {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%') {
                if (index + 3 > text.length) return null
                val hex = text.substring(index + 1, index + 3)
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                out.write(hex.toInt(16))
                index += 3
            } else {
                out.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Takes a request target apart into token and song id, or null for anything that is not a
     * song url. Takes the absolute form some clients send (`http://host/c/...`) as well as a
     * path, and ignores a query.
     */
    fun parsePath(target: String): CastPath? {
        var path = target.substringBefore('#').substringBefore('?')
        val scheme = path.indexOf("://")
        if (scheme >= 0) {
            val slash = path.indexOf('/', scheme + 3)
            path = if (slash < 0) "/" else path.substring(slash)
        }
        val parts = path.split('/')
        if (parts.size != 5 || parts[0].isNotEmpty() || parts[1] != "c" || (parts[3] != "s" && parts[3] != "aac")) return null
        val token = parts[2].takeIf { it.isNotEmpty() } ?: return null
        val file = parts[4]
        val dot = file.lastIndexOf('.')
        val encodedId = if (dot >= 0) file.substring(0, dot) else file
        val mediaId = decodeSegment(encodedId)?.takeIf { it.isNotEmpty() } ?: return null
        return CastPath(token, mediaId, aac = parts[3] == "aac")
    }

    // Requests

    /**
     * Reads a request head, up to and including its blank line, and not a byte further than
     * [max]. Lines may end in CRLF or, from sloppier clients, a bare LF.
     */
    fun readHead(input: InputStream, max: Int = MAX_HEAD_BYTES): HeadRead {
        val bytes = ByteArrayOutputStream()
        var newlines = 0
        while (true) {
            val value = input.read()
            if (value < 0) return HeadRead.Closed
            bytes.write(value)
            when (value) {
                '\n'.code -> newlines++
                '\r'.code -> Unit
                else -> newlines = 0
            }
            if (newlines == 2) return HeadRead.Head(bytes.toString(Charsets.ISO_8859_1.name()))
            if (bytes.size() >= max) return HeadRead.TooLarge
        }
    }

    /** The request line and headers of [head], or null when the request line is not HTTP/1. */
    fun parseRequest(head: String): CastRequest? {
        val lines = head.split('\n').map { it.removeSuffix("\r") }
        val requestLine = lines.firstOrNull()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        return CastRequest(requestLine[0], requestLine[1], headers)
    }

    // Responses

    /**
     * The reply to a GET or HEAD for a song of type [mimeType] and [total] bytes (below 0 when
     * not known).
     *
     * Every reply says Accept-Ranges and carries the DLNA headers: renderers that do not know
     * them ignore them, and some that do will not seek without them. CORS is open because a
     * browser-based receiver fetches from another origin and needs to read Content-Range; the
     * token, not the origin, is what guards the url.
     */
    fun songReply(request: CastRequest, mimeType: String, total: Long): SongReply {
        val headers = mutableListOf(
            "Content-Type" to mimeType,
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
            "transferMode.dlna.org" to "Streaming",
            "Connection" to "close",
        )
        if (total < 0) {
            // A copy whose length nothing recorded. Served whole and ended by closing, since no
            // Content-Range or Content-Length can be written for it.
            headers += "Accept-Ranges" to "none"
            headers += "contentFeatures.dlna.org" to "DLNA.ORG_OP=00;DLNA.ORG_CI=0;$DLNA_FLAGS"
            return SongReply(200, headers, 0, -1)
        }
        headers += "Accept-Ranges" to "bytes"
        headers += "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;$DLNA_FLAGS"
        return when (val range = parseRange(request.header("range"), total)) {
            RangeAnswer.Whole -> {
                headers += "Content-Length" to total.toString()
                SongReply(200, headers, 0, total)
            }

            is RangeAnswer.Part -> {
                headers += "Content-Range" to "bytes ${range.first}-${range.last}/$total"
                headers += "Content-Length" to range.length.toString()
                SongReply(206, headers, range.first, range.length)
            }

            RangeAnswer.Unsatisfiable -> {
                headers += "Content-Range" to "bytes */$total"
                headers += "Content-Length" to "0"
                SongReply(416, headers, 0, 0)
            }
        }
    }

    /** The answer to a CORS preflight. */
    fun optionsReply(): List<Pair<String, String>> = listOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "Range",
        "Access-Control-Max-Age" to "600",
        "Content-Length" to "0",
        "Connection" to "close",
    )

    /** A short plain-text answer, for refusals and errors. */
    fun textReply(message: String, vararg extra: Pair<String, String>): Pair<List<Pair<String, String>>, ByteArray> {
        val body = "$message\n".toByteArray(Charsets.UTF_8)
        val headers = listOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*",
            "Connection" to "close",
        ) + extra
        return headers to body
    }

    /** The status line and headers, ready to write. Header values are sent as Latin-1. */
    fun head(status: Int, headers: List<Pair<String, String>>): ByteArray = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        for ((name, value) in headers) {
            // A value is never allowed to end the line early and start a header of its own.
            append(name).append(": ").append(value.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
        }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }
}
