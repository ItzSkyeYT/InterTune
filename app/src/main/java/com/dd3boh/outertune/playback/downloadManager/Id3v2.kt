/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The tags of a song as an ID3v2.3 tag (Unreleased.DOWNLOAD_FORMATS): at the start of an MP3, and in
 * the "id3 " chunk of a WAV. Version 3 and not 4, because it is the one every player, car and
 * old phone reads; the only text it can hold other than Latin-1 is UTF-16, which they all read too.
 *
 * What goes in is what a song file carries (SongTags): the title (TIT2), the artists (TPE1), the
 * album (TALB), the year (TYER), the track (TRCK), the cover (APIC, type 3, the front cover), and,
 * unless the file is an exported copy, the song's link in a comment (COMM), which is how the app
 * knows the file again (SongFileIds). The link comes before the cover so that reading it back stops
 * long before the picture's bytes. No Android in here, so it is tested on the JVM.
 */
object Id3v2 {
    private const val LATIN1 = 0
    private const val UTF16 = 1

    /**
     * The whole tag, header first, with no padding, or with one byte of it where [evenSize] says the
     * tag must come to an even number of bytes (a WAV chunk, see WavFileEncoder).
     */
    fun tag(tags: SongTags, evenSize: Boolean = false): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(text("TIT2", tags.title))
        tags.artist?.let { frames.write(text("TPE1", it)) }
        tags.album?.takeIf { it.isNotBlank() }?.let { frames.write(text("TALB", it)) }
        tags.year?.takeIf { it in 1000..9999 }?.let { frames.write(text("TYER", it.toString())) }
        tags.track?.takeIf { it > 0 }?.let { frames.write(text("TRCK", it.toString())) }
        tags.linkComment?.let { frames.write(comment(it)) }
        tags.cover?.let { frames.write(picture(it)) }
        var body = frames.toByteArray()
        // Padding is part of an ID3 tag, and its size says so: the zero byte is no frame, and no reader goes on past it.
        if (evenSize && (10 + body.size) % 2 == 1) body += 0
        val out = ByteArrayOutputStream(10 + body.size)
        DataOutputStream(out).apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            writeByte(3); writeByte(0)   // ID3v2.3.0
            writeByte(0)                 // no flags
            write(synchsafe(body.size))
            write(body)
            flush()
        }
        return out.toByteArray()
    }

    /** A text frame: the encoding byte, then the text, in Latin-1 when it fits and in UTF-16 with a byte order mark when not. */
    private fun text(id: String, value: String): ByteArray {
        val (encoding, bytes) = encode(value)
        return frame(id, byteArrayOf(encoding.toByte()) + bytes)
    }

    /** A comment frame: encoding, language, an empty short description, the text. The link is ASCII, so Latin-1. */
    private fun comment(value: String): ByteArray {
        val (encoding, bytes) = encode(value)
        val description = if (encoding == UTF16) byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) else byteArrayOf(0)
        return frame("COMM", byteArrayOf(encoding.toByte()) + "eng".toByteArray(Charsets.ISO_8859_1) + description + bytes)
    }

    /** A picture frame: Latin-1 description (none), the picture's type, front cover, then its bytes. */
    private fun picture(cover: Cover): ByteArray {
        val mime = cover.mimeType.toByteArray(Charsets.ISO_8859_1)
        return frame("APIC", byteArrayOf(LATIN1.toByte()) + mime + byteArrayOf(0, 3, 0) + cover.bytes)
    }

    private fun frame(id: String, body: ByteArray): ByteArray {
        require(id.length == 4)
        val out = ByteArrayOutputStream(10 + body.size)
        DataOutputStream(out).apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            writeInt(body.size)          // version 3 sizes are plain, version 4's are not
            writeShort(0)                // no flags
            write(body)
            flush()
        }
        return out.toByteArray()
    }

    private fun encode(value: String): Pair<Int, ByteArray> {
        val clean = value.replace('\u0000', ' ')
        return if (clean.all { it.code <= 0xFF }) {
            LATIN1 to clean.toByteArray(Charsets.ISO_8859_1)
        } else {
            UTF16 to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + clean.toByteArray(Charsets.UTF_16LE)
        }
    }

    /** A size as four bytes of seven bits each, the form the tag's own header holds it in. */
    internal fun synchsafe(size: Int): ByteArray =
        byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
}
