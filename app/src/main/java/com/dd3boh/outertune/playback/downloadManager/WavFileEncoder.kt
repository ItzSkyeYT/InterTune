/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.constants.DownloadFormat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A WAV file (Unreleased.DOWNLOAD_FORMATS): 16-bit PCM in a RIFF file, the tags before the audio.
 *
 * What WAV can hold of the tags is little: Android's own scanner reads none of it, Windows reads the
 * LIST INFO chunk (title, artist, album, year, track, comment) and foobar2000, ffmpeg and the app's
 * TagLib an "id3 " chunk, so both are written; the cover, which only the ID3 tag can carry, is in
 * that one. Most players show no cover of a WAV at all. The tags come before the audio so that the
 * app reads the id back without walking the audio.
 *
 * Written with the sizes empty and filled in at the end, as the file is the app's own temporary one.
 * Every chunk before the audio has an even size by construction (the ID3 tag and the INFO texts are padded
 * inside their sizes), because the pad byte RIFF writes after an odd chunk is one Android's own WAV reader,
 * MediaExtractor's, does not skip: a file with one is no WAV to it, and cannot be decoded for an export or by an
 * app that asks the phone to play it.
 * RIFF counts sizes in 32 bits, which is 6 hours of stereo at 48 kHz; past 4 GB the writer refuses.
 * No Android in here, so it is tested on the JVM.
 */
internal class WavFileEncoder(override val file: File, private val tags: SongTags) : FileEncoder {
    override val format = DownloadFormat.WAV

    private var out: OutputStream? = null
    private var rate = 0
    private var channels = 0
    private var dataBytes = 0L
    private var headerSize = 0
    private var bytes = ByteArray(0)

    override fun start(rate: Int, channels: Int) {
        require(channels in 1..2) { "a WAV of $channels channels" }
        require(rate in 1000..192_000) { "a WAV at $rate Hz" }
        this.rate = rate
        this.channels = channels
        val header = header(0)
        headerSize = header.size
        out = BufferedOutputStream(FileOutputStream(file), 64 * 1024).also { it.write(header) }
    }

    override fun add(samples: ShortArray, frames: Int) {
        val count = frames * channels
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(samples[i])
        dataBytes += count * 2L
        if (dataBytes > MAX_DATA) throw IOException("a WAV of $dataBytes bytes is more than RIFF holds")
        out!!.write(bytes, 0, count * 2)
    }

    override fun finish() {
        out!!.flush()
        out!!.close()
        out = null
        // The same header again, with the sizes: it is as long as the first, so the audio stays where it is.
        RandomAccessFile(file, "rw").use { it.write(header(dataBytes)) }
    }

    override fun close() {
        runCatching { out?.close() }
        out = null
    }

    /** Everything before the first sample: RIFF, fmt, LIST INFO, the "id3 " chunk, the start of data. */
    private fun header(dataBytes: Long): ByteArray {
        val info = infoChunk()
        val id3 = chunk("id3 ", Id3v2.tag(tags, evenSize = true))
        val fmt = chunk("fmt ", ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)                                  // PCM
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
        }.array())
        val riffBody = 4 + fmt.size + info.size + id3.size + 8 + dataBytes + (dataBytes and 1)
        if (riffBody > 0xFFFFFFFFL) throw IOException("a WAV of $dataBytes bytes is more than RIFF holds")
        return ByteBuffer.allocate(12 + fmt.size + info.size + id3.size + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(riffBody.toInt()); put("WAVE".toByteArray())
            put(fmt); put(info); put(id3)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }

    /** The text fields Windows and the ordinary players read: INAM, IART, IPRD, ICRD, ITRK, ICMT. */
    private fun infoChunk(): ByteArray {
        val fields = ByteArrayOutputStream()
        fun field(id: String, value: String?) {
            if (value.isNullOrBlank()) return
            var text = value.replace('\u0000', ' ').toByteArray(Charsets.UTF_8) + 0
            // An even size, so that no chunk is left to the pad byte RIFF writes after an odd one: Android's own WAV
            // reader (MediaExtractor) does not skip it, and reads the next chunk a byte off.
            if (text.size % 2 == 1) text += 0
            fields.write(chunk(id, text))
        }
        field("INAM", tags.title)
        field("IART", tags.artist)
        field("IPRD", tags.album)
        field("ICRD", tags.year?.takeIf { it > 0 }?.toString())
        field("ITRK", tags.track?.takeIf { it > 0 }?.toString())
        field("ICMT", tags.linkComment)
        return chunk("LIST", "INFO".toByteArray() + fields.toByteArray())
    }

    /** A chunk: its name, its size, its bytes, and one more when their number is odd, as RIFF wants. */
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val padded = body.size + (body.size and 1)
        return ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray(Charsets.ISO_8859_1)); putInt(body.size); put(body)
        }.array()
    }

    private companion object {
        /** What RIFF's 32-bit sizes leave for the audio, with room for the header. */
        const val MAX_DATA = 0xFFFFFFFFL - 4 * 1024 * 1024
    }
}
