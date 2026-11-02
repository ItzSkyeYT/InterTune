/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import java.io.IOException
import java.io.OutputStream

/**
 * Opus packets put into an Ogg file, the way RFC 7845 says, so that a song YouTube sends as Opus
 * in WebM is saved as an .opus file every player opens, with the very same packets: nothing is
 * decoded or encoded again. No Android in here, so it is tested on the JVM.
 *
 * The ID header alone on the first page; the comment header (the tags and the cover) on the pages
 * after it, the last of them ending with it; then the audio, about one second to a page, each page
 * marked with the number of samples decoded by its end. The last page says where the song really
 * ends, when the WebM said how much of the last packet is padding.
 */
class OggOpusWriter(out: OutputStream, serial: Int) {
    private val pages = OggPages(out, serial)
    private val pagePackets = ArrayList<ByteArray>()
    private var pageSegments = 0
    private var pageStart = 0L
    private var granule = 0L
    private var lastPacketSamples = 0
    private var headersWritten = false

    /** Samples (at 48 kHz) written so far, the pre-skip included. */
    val samples: Long get() = granule

    fun writeHeaders(opusHead: ByteArray, opusTags: ByteArray) {
        check(!headersWritten) { "headers twice" }
        pages.packetAlone(opusHead, first = true)
        pages.packetAlone(opusTags, first = false)
        headersWritten = true
    }

    /** One Opus packet, [samples] long at 48 kHz (OpusPackets.samples). */
    fun writeAudio(packet: ByteArray, samples: Int) {
        check(headersWritten) { "audio before the headers" }
        if (packet.size > MAX_PACKET) throw IOException("an Opus packet of ${packet.size} bytes")
        val segments = packet.size / 255 + 1
        if (pagePackets.isNotEmpty() && (pageSegments + segments > 255 || granule - pageStart >= PAGE_SAMPLES)) {
            flush(granule, last = false)
        }
        pagePackets += packet
        pageSegments += segments
        granule += samples
        lastPacketSamples = samples
    }

    /**
     * The last page, marked as the end. [trim]: samples of padding at the end of the last packet,
     * from the WebM's DiscardPadding, left out by the page's granule position. Never more than the
     * last packet holds, as the RFC asks.
     */
    fun finish(trim: Long = 0) {
        if (pagePackets.isEmpty()) throw IOException("no audio")
        flush(granule - trim.coerceIn(0, lastPacketSamples.toLong()), last = true)
    }

    private fun flush(pageGranule: Long, last: Boolean) {
        val lacing = ByteArray(pageSegments)
        var at = 0
        for (packet in pagePackets) {
            repeat(packet.size / 255) { lacing[at++] = 255.toByte() }
            lacing[at++] = (packet.size % 255).toByte()
        }
        pages.page(lacing, pagePackets, pageGranule, if (last) OggPages.LAST else 0)
        pagePackets.clear()
        pageSegments = 0
        pageStart = granule
    }

    companion object {
        /** About one second of audio to a page, as opusenc and ffmpeg make them. */
        const val PAGE_SAMPLES = 48_000L

        /** No Opus packet is near this, and a page cannot hold more. */
        private const val MAX_PACKET = 255 * 255 - 1

        /**
         * The ID header for a stream whose container gave none: version 1, the channel count, the
         * pre-skip, 48 kHz, no gain, mapping family 0 (mono or stereo).
         */
        fun opusHead(channels: Int, preSkip: Int): ByteArray {
            require(channels in 1..2) { "$channels channels need a channel mapping table" }
            val head = "OpusHead".toByteArray(Charsets.US_ASCII) + ByteArray(11)
            head[8] = 1
            head[9] = channels.toByte()
            head[10] = (preSkip and 0xFF).toByte()
            head[11] = (preSkip shr 8 and 0xFF).toByte()
            val rate = 48_000
            for (i in 0..3) head[12 + i] = (rate shr (8 * i) and 0xFF).toByte()
            return head
        }

        /** Whether [head] is an Opus ID header ("OpusHead", version 1 or a compatible one). */
        fun isOpusHead(head: ByteArray?): Boolean =
            head != null && head.size >= 19 && String(head, 0, 8, Charsets.US_ASCII) == "OpusHead" &&
                (head[8].toInt() and 0xF0) == 0

        /** The pre-skip an ID header names, in samples at 48 kHz. */
        fun preSkip(head: ByteArray): Int = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)

        /** The comment header: "OpusTags", the vendor, then each "KEY=value" (RFC 7845 5.2). */
        fun opusTags(vendor: String, comments: List<String>): ByteArray {
            val encoded = comments.map { it.toByteArray(Charsets.UTF_8) }
            val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
            val buffer = java.nio.ByteBuffer.allocate(8 + 4 + vendorBytes.size + 4 + encoded.sumOf { 4 + it.size })
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.put("OpusTags".toByteArray(Charsets.US_ASCII))
            buffer.putInt(vendorBytes.size).put(vendorBytes)
            buffer.putInt(encoded.size)
            for (comment in encoded) buffer.putInt(comment.size).put(comment)
            return buffer.array()
        }
    }
}

/** How long Opus packets are, from their first byte (RFC 6716 3.1). */
object OpusPackets {
    /** The samples at 48 kHz [packet] decodes to. Throws for a packet that is not Opus. */
    fun samples(packet: ByteArray): Int = samples(packet, 0, packet.size)

    /** The same for the [length] bytes of a packet at [offset] in [data]. */
    fun samples(data: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) throw IOException("an empty Opus packet")
        val toc = data[offset].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> if (config and 1 == 0) 480 else 960
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> {
                if (length < 2) throw IOException("an Opus packet with no frame count")
                data[offset + 1].toInt() and 0x3F
            }
        }
        val total = frames * frameSamples
        // 120 ms at most (RFC 6716 3.2.5).
        if (frames == 0 || total > 5760) throw IOException("an Opus packet of $frames frames")
        return total
    }
}

/** Ogg pages of one logical stream (RFC 3533), written as they come. */
internal class OggPages(private val out: OutputStream, private val serial: Int) {
    private var sequence = 0

    /**
     * A header packet on pages of its own: as many as it needs, the ones it does not end on
     * marked -1, the one it ends on 0 (RFC 7845 4).
     */
    fun packetAlone(packet: ByteArray, first: Boolean) {
        val total = packet.size / 255 + 1
        var segment = 0
        var offset = 0
        var flags = if (first) FIRST else 0
        while (segment < total) {
            val count = minOf(255, total - segment)
            val lacing = ByteArray(count) { i -> if (segment + i < total - 1) 255.toByte() else (packet.size % 255).toByte() }
            val bytes = lacing.sumOf { it.toInt() and 0xFF }
            val ends = segment + count == total
            page(lacing, listOf(packet.copyOfRange(offset, offset + bytes)), if (ends) 0 else -1, flags)
            flags = CONTINUED
            offset += bytes
            segment += count
        }
    }

    fun page(lacing: ByteArray, payload: List<ByteArray>, granule: Long, flags: Int) {
        val header = ByteArray(27 + lacing.size)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = 0
        header[5] = flags.toByte()
        for (i in 0..7) header[6 + i] = (granule shr (8 * i) and 0xFF).toByte()
        for (i in 0..3) header[14 + i] = (serial shr (8 * i) and 0xFF).toByte()
        for (i in 0..3) header[18 + i] = (sequence shr (8 * i) and 0xFF).toByte()
        header[26] = lacing.size.toByte()
        lacing.copyInto(header, 27)
        var crc = OggCrc.update(0, header, 0, header.size)
        for (part in payload) crc = OggCrc.update(crc, part, 0, part.size)
        for (i in 0..3) header[22 + i] = (crc shr (8 * i) and 0xFF).toByte()
        out.write(header)
        for (part in payload) out.write(part)
        sequence++
    }

    companion object {
        const val CONTINUED = 0x01
        const val FIRST = 0x02
        const val LAST = 0x04
    }
}

/** Ogg's page checksum: CRC-32, polynomial 0x04C11DB7, not reflected, starting at 0. */
internal object OggCrc {
    private val table = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun update(crc: Int, bytes: ByteArray, offset: Int, length: Int): Int {
        var c = crc
        for (i in offset until offset + length) {
            c = (c shl 8) xor table[((c ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF]
        }
        return c
    }
}
