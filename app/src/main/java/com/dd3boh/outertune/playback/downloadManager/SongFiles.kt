/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A downloaded song saved as a file other players open (Unreleased.DOWNLOAD_FILES), from the
 * stream media3 cached: Opus from a WebM into an .opus (OggOpusWriter), AAC from an MP4 into an
 * .m4a (M4aWriter), the packets as they came, never decoded. They are read with the same extractors
 * the player plays these streams with, once to learn what the stream is and check every packet,
 * before any file is made, and once more to write it. Anything else, or anything that fails,
 * returns null, and the caller saves the stream as it is, the way it always was.
 */
@OptIn(UnstableApi::class)
object SongFiles {
    private const val VENDOR = "InterTune"

    /** A stream read through once: what it is, and what the second read has to match. */
    sealed class Stream(val extension: String) {
        class Opus(val head: ByteArray, val packets: Int, val trim: Long) : Stream("opus")
        class Aac(val track: M4aTrack) : Stream("m4a")
    }

    /**
     * Writes the song cached in [files] through [save], given the file's name and what to write
     * into it, after reading it through once. Null when the stream is not one this can save, and
     * then [save] was never called.
     */
    fun <T> save(files: List<File>, tags: SongTags, save: (name: String, write: (OutputStream) -> Unit) -> T?): T? {
        val source = CachedBytes(files)
        val stream = probe(source) ?: return null
        return save(SongFileNames.of(tags, stream.extension)) { out -> write(source, stream, tags, out) }
    }

    /** What [source] holds, every packet read and checked, or null when it is not Opus or AAC. */
    internal fun probe(source: CachedBytes): Stream? {
        var format: Format? = null
        var packets = 0
        var trimNs = 0L
        val sizes = Ints()
        val times = Longs()
        val read = runCatching {
            extract(source, { if (format == null) format = it }) { timeUs, flags, data, offset, size ->
                val f = format ?: throw IOException("a packet before the stream's format")
                when (f.sampleMimeType) {
                    MimeTypes.AUDIO_OPUS -> {
                        val packet = opusPacket(flags, data, offset, size)
                        OpusPackets.samples(data, packet.first, packet.second)
                        trimNs = packet.third
                    }
                    MimeTypes.AUDIO_AAC -> {
                        if (size <= 0) throw IOException("an empty AAC frame")
                        sizes.add(size)
                        times.add(timeUs)
                    }
                    else -> throw IOException("a ${f.sampleMimeType} stream")
                }
                packets++
            }
        }
        val f = format
        if (read.isFailure || f == null || packets == 0) return null
        return runCatching {
            when (f.sampleMimeType) {
                MimeTypes.AUDIO_OPUS -> Stream.Opus(opusHead(f), packets, (trimNs * 48_000 / 1_000_000_000L).coerceAtLeast(0))
                MimeTypes.AUDIO_AAC -> Stream.Aac(aacTrack(f, sizes.toArray(), times.toArray()))
                else -> null
            }
        }.getOrNull()
    }

    internal fun write(source: CachedBytes, stream: Stream, tags: SongTags, out: OutputStream) {
        when (stream) {
            is Stream.Opus -> {
                val ogg = OggOpusWriter(out, Random.nextInt())
                ogg.writeHeaders(stream.head, OggOpusWriter.opusTags(VENDOR, tags.vorbisComments()))
                var packets = 0
                extract(source, {}) { _, flags, data, offset, size ->
                    val (start, length, _) = opusPacket(flags, data, offset, size)
                    ogg.writeAudio(data.copyOfRange(start, start + length), OpusPackets.samples(data, start, length))
                    packets++
                }
                if (packets != stream.packets) throw IOException("$packets packets the second time, ${stream.packets} the first")
                ogg.finish(stream.trim)
            }
            is Stream.Aac -> {
                val sizes = stream.track.sizes
                M4aWriter.write(out, stream.track, tags) { frames ->
                    var i = 0
                    extract(source, {}) { _, _, data, offset, size ->
                        if (i >= sizes.size || sizes[i] != size) throw IOException("the frames changed between two reads")
                        frames.write(data, offset, size)
                        i++
                    }
                    if (i != sizes.size) throw IOException("$i frames the second time, ${sizes.size} the first")
                }
            }
        }
    }

    /**
     * Where the Opus packet is in a sample and how much of it is padding, in nanoseconds: the
     * Matroska extractor puts a block's DiscardPadding after the packet, the packet's size first.
     */
    private fun opusPacket(flags: Int, data: ByteArray, offset: Int, size: Int): Triple<Int, Int, Long> {
        if (flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA == 0) return Triple(offset, size, 0L)
        if (size < 4) throw IOException("a sample too short for its own size")
        val main = ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        if (main < 0 || 4 + main > size) throw IOException("a sample of $size bytes saying it holds $main")
        var padding = 0L
        if (size - 4 - main >= 8) {
            val at = offset + 4 + main
            for (i in 7 downTo 0) padding = (padding shl 8) or (data[at + i].toLong() and 0xFF)
        }
        return Triple(offset + 4, main, padding)
    }

    /** The ID header the WebM carries, or one made from its channels and its codec delay. */
    private fun opusHead(format: Format): ByteArray {
        format.initializationData.firstOrNull()?.takeIf(OggOpusWriter::isOpusHead)?.let { return it }
        val delayNs = format.initializationData.getOrNull(1)?.takeIf { it.size == 8 }?.let { b ->
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            v
        } ?: 0L
        return OggOpusWriter.opusHead(format.channelCount, (delayNs * 48_000 / 1_000_000_000L).toInt())
    }

    /**
     * The track as an .m4a holds it: each frame's length from the times the extractor gave, in
     * the stream's own sample rate, and the priming, the frames before time zero, kept for the
     * edit list as the stream's own edit list had it.
     */
    private fun aacTrack(format: Format, sizes: IntArray, timesUs: LongArray): M4aTrack {
        val timescale = format.sampleRate
        val config = format.initializationData.firstOrNull() ?: throw IOException("AAC with no AudioSpecificConfig")
        if (timescale <= 0 || format.channelCount <= 0) throw IOException("AAC at $timescale Hz, ${format.channelCount} channels")
        val ticks = LongArray(timesUs.size) { (timesUs[it] * timescale / 1_000_000.0).roundToLong() }
        val start = ticks[0]
        val durations = IntArray(ticks.size)
        for (i in 0 until ticks.size - 1) {
            val d = ticks[i + 1] - ticks[i]
            if (d <= 0 || d > Int.MAX_VALUE) throw IOException("frame $i lasts $d")
            durations[i] = d.toInt()
        }
        durations[ticks.size - 1] = if (ticks.size > 1) durations[ticks.size - 2] else 1024
        return M4aTrack(config, timescale, format.channelCount, sizes, durations, priming = if (start < 0) -start else 0)
    }

    /** Runs the extractor for [source]'s container over the whole of it, a packet at a time. */
    private fun extract(source: CachedBytes, onFormat: (Format) -> Unit, onPacket: (Long, Int, ByteArray, Int, Int) -> Unit) {
        val extractor = extractorFor(source) ?: throw IOException("neither WebM nor MP4")
        val output = Packets(onFormat, onPacket)
        extractor.init(output)
        val holder = PositionHolder()
        var reader = source.open(0)
        try {
            var input = DefaultExtractorInput(reader, 0, source.length)
            while (true) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> return
                    Extractor.RESULT_SEEK -> {
                        reader.close()
                        reader = source.open(holder.position)
                        input = DefaultExtractorInput(reader, holder.position, source.length)
                    }
                }
            }
        } finally {
            reader.close()
            extractor.release()
        }
    }

    private fun extractorFor(source: CachedBytes): Extractor? {
        val head = source.head(12)
        if (head.size < 12) return null
        val ebml = (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
            (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
        if (ebml) return MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        if (String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
        // A DASH stream is a fragmented MP4; anything else in an MP4 is read as a plain one.
        val fragmented = source.open(0).use { reader ->
            runCatching { FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0).sniff(DefaultExtractorInput(reader, 0, source.length)) }
                .getOrDefault(false)
        }
        return if (fragmented) FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        else Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
    }

    /** The first audio track's packets, each handed on whole with its time and flags. */
    private class Packets(
        private val onFormat: (Format) -> Unit,
        private val onPacket: (Long, Int, ByteArray, Int, Int) -> Unit,
    ) : ExtractorOutput, TrackOutput {
        private var audioTrack = -1
        private val ignored = DiscardingTrackOutput()
        private var bytes = ByteArray(64 * 1024)
        private var size = 0

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_AUDIO || (audioTrack != -1 && audioTrack != id)) return ignored
            audioTrack = id
            return this
        }

        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
        override fun format(format: Format) = onFormat(format)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            room(length)
            val n = input.read(bytes, size, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            size += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            room(length)
            data.readBytes(bytes, size, length)
            size += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (cryptoData != null) throw IOException("an encrypted stream")
            val end = this.size - offset
            val start = end - size
            if (start < 0) throw IOException("a packet before its bytes")
            onPacket(timeUs, flags, bytes, start, size)
            // What is left belongs to the next packet.
            System.arraycopy(bytes, end, bytes, 0, this.size - end)
            this.size -= end
        }

        private fun room(more: Int) {
            if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more))
        }
    }

    private class Ints {
        private var a = IntArray(4096)
        private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray(): IntArray = a.copyOf(n)
    }

    private class Longs {
        private var a = LongArray(4096)
        private var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray(): LongArray = a.copyOf(n)
    }
}

/**
 * The files media3 cached a song in, read as one run of bytes from any position, the way an
 * extractor that seeks needs them. Nothing is held in memory but what a read asks for (#67).
 */
internal class CachedBytes(private val files: List<File>) {
    private val starts = LongArray(files.size + 1).also { s ->
        for (i in files.indices) s[i + 1] = s[i] + files[i].length()
    }
    val length: Long get() = starts[files.size]

    fun head(count: Int): ByteArray = open(0).use { reader ->
        val out = ByteArray(count)
        var at = 0
        while (at < count) {
            val n = reader.read(out, at, count - at)
            if (n == C.RESULT_END_OF_INPUT) break
            at += n
        }
        out.copyOf(at)
    }

    fun open(position: Long): Reader = Reader(position)

    inner class Reader(position: Long) : DataReader, Closeable {
        private var index = 0
        private var file: RandomAccessFile? = null

        init {
            while (index < files.size && starts[index + 1] <= position) index++
            if (index < files.size) {
                file = RandomAccessFile(files[index], "r").also { it.seek(position - starts[index]) }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                val current = file ?: return C.RESULT_END_OF_INPUT
                val n = current.read(buffer, offset, length)
                if (n > 0) return n
                current.close()
                index++
                file = if (index < files.size) RandomAccessFile(files[index], "r") else null
            }
        }

        override fun close() {
            file?.close()
            file = null
        }
    }
}
