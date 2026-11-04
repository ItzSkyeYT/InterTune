/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * One AAC track as it is saved in an .m4a: its AudioSpecificConfig and, for every frame, its size
 * and its length in [timescale] units, in order.
 *
 * [priming]: the samples at the start the encoder added, which an edit list tells players to skip,
 * as the stream they came from did.
 */
class M4aTrack(
    val audioSpecificConfig: ByteArray,
    val timescale: Int,
    val channels: Int,
    val sizes: IntArray,
    val durations: IntArray,
    val priming: Long = 0,
) {
    init {
        require(sizes.isNotEmpty() && sizes.size == durations.size) { "a track with no frames" }
        require(timescale > 0 && channels > 0)
    }

    val totalDuration: Long get() = durations.sumOf { it.toLong() }
    val totalBytes: Long get() = sizes.sumOf { it.toLong() }
}

/**
 * AAC frames put into a plain .m4a, the way iTunes and ffmpeg make them, so that a song YouTube
 * sends as AAC in a fragmented MP4 (what DASH streams are, and what many players and cars do not
 * open) is saved as an ordinary file with the very same frames: nothing is decoded or encoded again.
 * No Android in here, so it is tested on the JVM.
 *
 * The index (moov) goes before the audio (mdat), so a player, a cast receiver or the app reading the
 * song's id back finds it in the first bytes. The tags are iTunes': ©nam, ©ART, ©alb, ©day, trkn,
 * ©cmt holding the song's link, and covr.
 */
object M4aWriter {
    /** About one second of frames to a chunk. Any count is valid; this is what ffmpeg's look like. */
    private const val CHUNK_FRAMES = 43

    /**
     * Writes the file to [out]: the header and index, then [frames], which must write every frame's
     * bytes in order and nothing else. Their count is checked, and a stream that wrote other than
     * the sizes said throws rather than leaving a file whose index is wrong.
     */
    fun write(out: OutputStream, track: M4aTrack, tags: SongTags, frames: (OutputStream) -> Unit) {
        val payload = track.totalBytes
        if (payload + 8 > 0xFFFFFFFFL) throw IOException("an m4a of $payload bytes")
        val ftyp = ftyp()
        val moovSize = moov(track, tags, 0).size
        val moov = moov(track, tags, (ftyp.size + moovSize + 8).toLong())
        check(moov.size == moovSize)
        out.write(ftyp)
        out.write(moov)
        DataOutputStream(out).apply { writeInt((payload + 8).toInt()); write(type("mdat")); flush() }
        val counted = CountingStream(out)
        frames(counted)
        counted.flush()
        if (counted.count != payload) throw IOException("the frames came to ${counted.count} bytes, the index says $payload")
    }

    private fun ftyp() = box("ftyp") {
        write(type("M4A "))
        writeInt(0x200)
        write(type("M4A ")); write(type("isom")); write(type("iso2"))
    }

    /** The index, with the frames' offsets counted from [dataStart], where the first frame's byte is. */
    internal fun moov(track: M4aTrack, tags: SongTags, dataStart: Long): ByteArray {
        val total = track.totalDuration
        val shown = (total - track.priming).coerceAtLeast(0)
        val movieDuration = shown * 1000 / track.timescale
        return box("moov") {
            write(fullBox("mvhd", 0, 0) {
                writeInt(0); writeInt(0)
                writeInt(1000); writeInt(movieDuration.toInt())
                writeInt(0x00010000); writeShort(0x0100)
                write(ByteArray(10))
                matrix()
                write(ByteArray(24))
                writeInt(2)
            })
            write(box("trak") {
                write(fullBox("tkhd", 0, 3) {
                    writeInt(0); writeInt(0)
                    writeInt(1); writeInt(0)
                    writeInt(movieDuration.toInt())
                    write(ByteArray(8))
                    writeShort(0); writeShort(1)
                    writeShort(0x0100); writeShort(0)
                    matrix()
                    writeInt(0); writeInt(0)
                })
                if (track.priming > 0) write(box("edts") {
                    write(fullBox("elst", 0, 0) {
                        writeInt(1)
                        writeInt(movieDuration.toInt())
                        writeInt(track.priming.toInt())
                        writeShort(1); writeShort(0)
                    })
                })
                write(box("mdia") {
                    write(fullBox("mdhd", 0, 0) {
                        writeInt(0); writeInt(0)
                        writeInt(track.timescale); writeInt(total.toInt())
                        writeShort(0x55C4) // "und"
                        writeShort(0)
                    })
                    write(fullBox("hdlr", 0, 0) {
                        writeInt(0); write(type("soun")); write(ByteArray(12))
                        write("SoundHandler".toByteArray(Charsets.US_ASCII)); writeByte(0)
                    })
                    write(box("minf") {
                        write(fullBox("smhd", 0, 0) { writeShort(0); writeShort(0) })
                        write(box("dinf") {
                            write(fullBox("dref", 0, 0) { writeInt(1); write(fullBox("url ", 0, 1) {}) })
                        })
                        write(stbl(track, dataStart))
                    })
                })
            })
            write(udta(tags))
        }
    }

    private fun stbl(track: M4aTrack, dataStart: Long) = box("stbl") {
        write(fullBox("stsd", 0, 0) {
            writeInt(1)
            write(box("mp4a") {
                write(ByteArray(6)); writeShort(1)
                writeShort(0); writeShort(0); writeInt(0)
                writeShort(track.channels); writeShort(16)
                writeShort(0); writeShort(0)
                writeInt(if (track.timescale <= 0xFFFF) track.timescale shl 16 else 0)
                write(esds(track))
            })
        })
        // Lengths, as runs of equal ones.
        val runs = ArrayList<IntArray>()
        for (d in track.durations) {
            val last = runs.lastOrNull()
            if (last != null && last[1] == d) last[0]++ else runs += intArrayOf(1, d)
        }
        write(fullBox("stts", 0, 0) { writeInt(runs.size); for (r in runs) { writeInt(r[0]); writeInt(r[1]) } })
        val frames = track.sizes.size
        val chunks = (frames + CHUNK_FRAMES - 1) / CHUNK_FRAMES
        // Frames in the last chunk, 1 to CHUNK_FRAMES: every chunk before it is full.
        val rest = frames - (chunks - 1) * CHUNK_FRAMES
        write(fullBox("stsc", 0, 0) {
            if (chunks == 1 || rest == CHUNK_FRAMES) {
                writeInt(1)
                writeInt(1); writeInt(if (chunks == 1) frames else CHUNK_FRAMES); writeInt(1)
            } else {
                writeInt(2)
                writeInt(1); writeInt(CHUNK_FRAMES); writeInt(1)
                writeInt(chunks); writeInt(rest); writeInt(1)
            }
        })
        write(fullBox("stsz", 0, 0) { writeInt(0); writeInt(frames); for (s in track.sizes) writeInt(s) })
        write(fullBox("stco", 0, 0) {
            writeInt(chunks)
            var offset = dataStart
            for (i in 0 until frames) {
                if (i % CHUNK_FRAMES == 0) writeInt(offset.toInt())
                offset += track.sizes[i]
            }
        })
    }

    /** The decoder's description: MPEG-4 audio (0x40), an audio stream, its config, as ffmpeg writes it. */
    private fun esds(track: M4aTrack): ByteArray {
        val seconds = track.totalDuration.toDouble() / track.timescale
        val average = if (seconds > 0) (track.totalBytes * 8 / seconds).toLong() else 0L
        // The most bits in any one second, as the descriptor's maximum.
        var peak = 0L
        var windowBytes = 0L
        var windowTime = 0L
        var start = 0
        for (i in track.sizes.indices) {
            windowBytes += track.sizes[i]
            windowTime += track.durations[i]
            while (windowTime > track.timescale && start < i) {
                windowBytes -= track.sizes[start]
                windowTime -= track.durations[start]
                start++
            }
            peak = maxOf(peak, windowBytes * 8)
        }
        val asc = track.audioSpecificConfig
        val decoderSpecific = descriptor(0x05, asc)
        val decoderConfig = descriptor(0x04, bytes {
            writeByte(0x40); writeByte(0x15)
            val buffer = track.sizes.max()
            writeByte(buffer shr 16 and 0xFF); writeShort(buffer and 0xFFFF)
            writeInt(maxOf(peak, average).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            writeInt(average.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            write(decoderSpecific)
        })
        val es = descriptor(0x03, bytes {
            writeShort(1); writeByte(0)
            write(decoderConfig)
            write(descriptor(0x06, byteArrayOf(0x02)))
        })
        return fullBox("esds", 0, 0) { write(es) }
    }

    /** A descriptor with its length in the four byte form. */
    private fun descriptor(tag: Int, body: ByteArray): ByteArray = bytes {
        val n = body.size
        writeByte(tag)
        writeByte(0x80 or (n shr 21 and 0x7F)); writeByte(0x80 or (n shr 14 and 0x7F))
        writeByte(0x80 or (n shr 7 and 0x7F)); writeByte(n and 0x7F)
        write(body)
    }

    private fun udta(tags: SongTags) = box("udta") {
        write(fullBox("meta", 0, 0) {
            write(fullBox("hdlr", 0, 0) {
                writeInt(0); write(type("mdir")); write(type("appl")); writeInt(0); writeInt(0); writeByte(0)
            })
            write(box("ilst") {
                write(text("©nam", tags.title))
                tags.artist?.let { write(text("©ART", it)) }
                tags.album?.takeIf { it.isNotBlank() }?.let { write(text("©alb", it)) }
                tags.year?.takeIf { it > 0 }?.let { write(text("©day", it.toString())) }
                tags.track?.takeIf { it in 1..0xFFFF }?.let { number ->
                    write(item("trkn", 0, bytes { writeShort(0); writeShort(number); writeShort(0); writeShort(0) }))
                }
                write(text("©cmt", tags.link))
                tags.cover?.let { write(item("covr", if (it.mimeType == "image/png") 14 else 13, it.bytes)) }
            })
        })
    }

    private fun text(name: String, value: String) = item(name, 1, value.toByteArray(Charsets.UTF_8))

    private fun item(name: String, dataType: Int, value: ByteArray) = box(name) {
        write(box("data") { writeInt(dataType); writeInt(0); write(value) })
    }

    /** A box's four letters: Latin-1, so that © is the single byte 0xA9 iTunes uses. */
    private fun type(name: String): ByteArray = name.toByteArray(Charsets.ISO_8859_1).also { require(it.size == 4) }

    private fun DataOutputStream.matrix() {
        for (v in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) writeInt(v)
    }

    private fun bytes(body: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).apply { body(); flush() }
        return buffer.toByteArray()
    }

    private fun box(name: String, body: DataOutputStream.() -> Unit): ByteArray {
        val content = bytes(body)
        return bytes { writeInt(8 + content.size); write(type(name)); write(content) }
    }

    private fun fullBox(name: String, version: Int, flags: Int, body: DataOutputStream.() -> Unit): ByteArray =
        box(name) { writeInt((version shl 24) or (flags and 0xFFFFFF)); body() }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
