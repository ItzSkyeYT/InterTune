/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * The video id a song file in a download folder carries, read from its tags: the link in its
 * comment (SongTags.link), or the PURL a yt-dlp download keeps. A download saved as a song file is
 * named "Artist - Title.opus", so its name no longer says which song it is; the app reads it here
 * when it meets a file it does not know yet. Only the first bytes are read: the Ogg comment header
 * up to the link, which comes before the cover, and an .m4a's boxes down to its comment, the audio
 * skipped. No Android in here, so it is tested on the JVM.
 */
object SongFileIds {
    /** Past this, a comment header is not one of ours, and nothing in it is looked for. */
    private const val MAX_TAGS = 16 * 1024 * 1024

    /** The id in the file called [name], whose bytes [input] gives from the start, or null. */
    fun read(name: String, input: InputStream): String? = try {
        when (name.substringAfterLast('.', "").lowercase()) {
            "opus" -> fromOgg(input)
            "m4a" -> fromM4a(input)
            else -> null
        }
    } catch (e: IOException) {
        null
    }

    /** From the Opus comment header, the stream's second packet. */
    fun fromOgg(input: InputStream): String? {
        var serial: Int? = null
        var packets = 0
        val tags = Grow()
        val header = ByteArray(27)
        while (true) {
            if (!readFully(input, header)) return null
            if (String(header, 0, 4, Charsets.US_ASCII) != "OggS") return null
            val pageSerial = le32(header, 14)
            val lacing = ByteArray(header[26].toInt() and 0xFF)
            if (!readFully(input, lacing)) return null
            val payload = ByteArray(lacing.sumOf { it.toInt() and 0xFF })
            if (!readFully(input, payload)) return null
            if (serial == null) serial = pageSerial
            if (pageSerial != serial) continue
            var at = 0
            for (l in lacing) {
                val n = l.toInt() and 0xFF
                if (packets == 0) {
                    if (at == 0 && (payload.size < 8 || String(payload, 0, 8, Charsets.US_ASCII) != "OpusHead")) return null
                } else {
                    tags.add(payload, at, n)
                    if (tags.size > MAX_TAGS) return null
                }
                at += n
                if (n < 255) {
                    packets++
                    if (packets == 2) return commentsLink(tags.bytes, tags.size, complete = true)
                }
            }
            if (packets == 1) commentsLink(tags.bytes, tags.size, complete = false)?.let { return it }
        }
    }

    /**
     * The id in the comments read so far: the first COMMENT or PURL that holds a YouTube link.
     * Null when none of the comments whole so far has one, the ones still to come included only
     * once [complete].
     */
    private fun commentsLink(b: ByteArray, size: Int, complete: Boolean): String? {
        if (size < 16) return null
        if (String(b, 0, 8, Charsets.US_ASCII) != "OpusTags") return null
        var at = 8
        val vendor = le32(b, at).toLong() and 0xFFFFFFFFL
        at += 4
        if (at + vendor + 4 > size) return null
        at += vendor.toInt()
        val count = le32(b, at).toLong() and 0xFFFFFFFFL
        at += 4
        var seen = 0L
        while (seen < count && at + 4 <= size) {
            val length = le32(b, at).toLong() and 0xFFFFFFFFL
            if (at + 4 + length > size) return null
            val start = at + 4
            val keyEnd = (start until start + length.toInt()).firstOrNull { b[it] == '='.code.toByte() }
            if (keyEnd != null) {
                val key = String(b, start, keyEnd - start, Charsets.US_ASCII).uppercase()
                if (key == "COMMENT" || key == "PURL") {
                    val value = String(b, keyEnd + 1, start + length.toInt() - keyEnd - 1, Charsets.UTF_8)
                    SongFileNames.videoIdIn(value)?.let { return it }
                }
            }
            at = start + length.toInt()
            seen++
        }
        return null
    }

    /** From an .m4a's iTunes comment, moov > udta > meta > ilst > ©cmt > data. */
    fun fromM4a(input: InputStream): String? {
        val boxes = Boxes(input)
        val moov = boxes.find(Long.MAX_VALUE, "moov") ?: return null
        val udta = boxes.find(moov, "udta") ?: return null
        val meta = boxes.find(udta, "meta") ?: return null
        // An iTunes meta box starts with a version and flags, a QuickTime one with its first box.
        val first = ByteArray(8)
        if (!readFully(input, first)) return null
        val left = if (String(first, 4, 4, Charsets.ISO_8859_1) == "hdlr") {
            val hdlr = be32(first, 0).toLong() and 0xFFFFFFFFL
            if (hdlr < 8) return null
            boxes.skip(hdlr - 8)
            meta - hdlr
        } else {
            // The version and flags were read, and the first box's size: its type comes next.
            val size = be32(first, 4).toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            if (!readFully(input, type)) return null
            if (size < 8) return null
            if (String(type, Charsets.ISO_8859_1) == "ilst") return ilstLink(boxes, size - 8)
            boxes.skip(size - 8)
            meta - 4 - size
        }
        val ilst = boxes.find(left, "ilst") ?: return null
        return ilstLink(boxes, ilst)
    }

    private fun ilstLink(boxes: Boxes, size: Long): String? {
        val comment = boxes.find(size, "©cmt") ?: return null
        val data = boxes.find(comment, "data") ?: return null
        if (data < 8 || data > 64 * 1024) return null
        val value = ByteArray(data.toInt())
        if (!readFully(boxes.input, value)) return null
        return SongFileNames.videoIdIn(String(value, 8, value.size - 8, Charsets.UTF_8))
    }

    /** Walks boxes, skipping the ones not looked for. */
    private class Boxes(val input: InputStream) {
        private val header = ByteArray(8)

        /**
         * Reads boxes until one called [type] within the next [within] bytes, and returns the
         * size of what it holds, its header read. Null when there is none.
         */
        fun find(within: Long, type: String): Long? {
            var left = within
            while (left >= 8) {
                if (!readFully(input, header)) return null
                var size = be32(header, 0).toLong() and 0xFFFFFFFFL
                var headerSize = 8L
                if (size == 1L) {
                    val large = ByteArray(8)
                    if (!readFully(input, large)) return null
                    size = (be32(large, 0).toLong() shl 32) or (be32(large, 4).toLong() and 0xFFFFFFFFL)
                    headerSize = 16
                } else if (size == 0L) {
                    size = left
                }
                if (size < headerSize) return null
                if (String(header, 4, 4, Charsets.ISO_8859_1) == type) return size - headerSize
                skip(size - headerSize)
                left -= size
            }
            return null
        }

        fun skip(count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() < 0) throw EOFException()
                    left--
                }
            }
        }
    }

    private class Grow {
        var bytes = ByteArray(4096)
        var size = 0
        fun add(from: ByteArray, offset: Int, count: Int) {
            if (size + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
            System.arraycopy(from, offset, bytes, size, count)
            size += count
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
}
