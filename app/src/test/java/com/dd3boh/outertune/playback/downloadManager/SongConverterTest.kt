/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.cast.CastReader
import com.dd3boh.outertune.cast.CastSong
import com.dd3boh.outertune.cast.DecodedAudio
import com.dd3boh.outertune.cast.DecodedPiece
import com.dd3boh.outertune.cast.PcmDecoder
import com.dd3boh.outertune.cast.PcmSource
import com.dd3boh.outertune.constants.DownloadBitrate
import com.dd3boh.outertune.constants.DownloadFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.sin

/**
 * The conversion of a song to another format (Unreleased.DOWNLOAD_FORMATS, SongConverter), with a
 * decoder that makes its audio up, as the cast server's tests do: a conversion is a whole file or it
 * is nothing, and the work folder is empty afterwards either way. The real decoder and the AAC
 * encoder are MediaCodec's, and are seen on the emulator.
 */
class SongConverterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tags = SongTags("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"))

    private val song = object : CastSong {
        override val mimeType = "audio/webm"
        override val length = 0L
        override fun openAt(position: Long): CastReader = throw UnsupportedOperationException()
    }

    /** A decoder whose song is [seconds] of a tone, in pieces of [piece] frames; [after] runs after the n-th piece. */
    private class FakeDecoder(
        private val seconds: Double,
        private val rate: Int = 48000,
        private val channels: Int = 2,
        private val piece: Int = 960,
        private val changeRateAt: Int = -1,
        private val failAt: Int = -1,
        private val onClose: () -> Unit = {},
    ) : PcmDecoder {
        override fun probe(song: CastSong) = PcmSource(rate, channels, (seconds * 1_000_000).toLong())

        override fun open(song: CastSong, fromUs: Long): DecodedAudio = object : DecodedAudio {
            private var sent = 0
            private var index = 0
            private val total = (seconds * rate).toInt()
            private val buffer = ShortArray(piece * channels)

            override fun next(): DecodedPiece? {
                if (sent >= total) return null
                if (index == failAt) throw IOException("the network went")
                val n = minOf(piece, total - sent)
                val r = if (index >= changeRateAt && changeRateAt >= 0) rate / 2 else rate
                for (f in 0 until n) for (c in 0 until channels) {
                    buffer[f * channels + c] = (sin(2 * PI * (440.0 + 110 * c) * (sent + f) / rate) * 9000).toInt().toShort()
                }
                index++
                sent += n
                return DecodedPiece(0, r, channels, buffer, n)
            }

            override fun close() = onClose()
        }
    }

    private fun converter(decoder: PcmDecoder, work: File = File(folder.root, "convert"), hold: ConvertHold? = null) = SongConverter(work, decoder, hold)

    /** A hold that writes down when the app is held and let go, and lets go as soon as it may. */
    private fun hold(events: MutableList<String>) =
        ConvertHold(start = { events += "held for $it"; true }, stop = { events += "let go" }, later = { _, block -> block() })

    @Test
    fun `the app is held from before a song is decoded until its conversion is over`() {
        val events = mutableListOf<String>()
        converter(FakeDecoder(1.0, onClose = { events += "decoded" }), hold = hold(events))
            .convert(song, tags, ConvertPlan.Encode(DownloadFormat.MP3, 128), 1_000_000L).close()
        assertEquals(listOf("held for MP3", "decoded", "let go"), events)
    }

    @Test
    fun `the log's line says how long a conversion stood still, and nothing when it never did`() {
        assertEquals("a piece that came in half a second is the decoder's own time", 0L, SongConverter.stoodStill(500_000_000L))
        assertEquals("one that took a minute and a half is not", 90_000_000_000L, SongConverter.stoodStill(90_000_000_000L))
        assertEquals("", SongConverter.stoodLine(0L))
        assertEquals(", 96 s of them standing still", SongConverter.stoodLine(96_000_000_000L))
        val lines = mutableListOf<String>()
        SongConverter(File(folder.root, "convert"), FakeDecoder(1.0)) { lines += it }
            .convert(song, tags, ConvertPlan.Encode(DownloadFormat.WAV, null), 1_000_000L).close()
        assertTrue("a conversion that ran through says how long it took and no more: $lines", lines.single().matches(Regex("""Converted to WAV: 1000 ms of audio .* in \d+\.\d s""")))
    }

    @Test
    fun `a conversion that fails gives the hold back`() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            converter(FakeDecoder(3.0, failAt = 20, onClose = { events += "decoded" }), hold = hold(events))
                .convert(song, tags, ConvertPlan.Encode(DownloadFormat.FLAC, null), 3_000_000L)
        }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertEquals(listOf("held for FLAC", "decoded", "let go"), events)
    }

    private fun left(work: File) = work.listFiles()?.toList().orEmpty()

    @Test
    fun `a song becomes a whole MP3 in the work folder, as long as the song, and it goes when closed`() {
        val work = File(folder.root, "convert")
        val converted = converter(FakeDecoder(3.0), work).convert(song, tags, ConvertPlan.Encode(DownloadFormat.MP3, 256), 3_000_000L)
        assertEquals("mp3", converted.extension)
        assertEquals(256, converted.kbps)
        assertEquals(3000L, converted.audioMs)
        assertTrue(converted.file.parentFile == work && converted.file.name.endsWith(".part") && converted.file.length() > 3 * 256_000 / 8 / 2)
        val copy = ByteArrayOutputStream().also { converted.copyTo(it) }.toByteArray()
        assertArrayEquals(converted.file.readBytes(), copy)
        assertEquals("ID3", String(copy, 0, 3))
        converted.close()
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `FLAC and WAV are made the same way, with no bitrate`() {
        for (format in listOf(DownloadFormat.FLAC, DownloadFormat.WAV)) {
            val work = File(folder.root, "convert-${format.name}")
            val converted = converter(FakeDecoder(1.0, rate = 44100), work).convert(song, tags, ConvertPlan.Encode(format, null), 1_000_000L)
            assertEquals(format.extension, converted.extension)
            assertNull(converted.kbps)
            assertTrue(converted.file.length() > 1000)
            converted.close()
            assertEquals(emptyList<File>(), left(work))
        }
    }

    @Test
    fun `a song that holds no audio is refused and leaves nothing`() {
        val work = File(folder.root, "convert")
        val failed = runCatching { converter(FakeDecoder(0.0), work).convert(song, tags, ConvertPlan.Encode(DownloadFormat.MP3, 128), 0L) }.exceptionOrNull()
        assertTrue(failed is IOException)
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `a decode that ends short of the song is no song, and leaves nothing`() {
        val work = File(folder.root, "convert")
        val failed = runCatching { converter(FakeDecoder(3.0), work).convert(song, tags, ConvertPlan.Encode(DownloadFormat.FLAC, null), 10_000_000L) }.exceptionOrNull()
        assertTrue("$failed", failed is IOException && failed.message!!.contains("3000 ms"))
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `a decode a little short of the container's length is the usual end of an Opus, and passes`() {
        val converted = converter(FakeDecoder(3.0)).convert(song, tags, ConvertPlan.Encode(DownloadFormat.WAV, null), 3_040_000L)
        assertEquals(3000L, converted.audioMs)
        converted.close()
    }

    @Test
    fun `a decoder that fails halfway fails the conversion, is closed, and leaves nothing`() {
        val work = File(folder.root, "convert")
        var closed = false
        val failed = runCatching {
            converter(FakeDecoder(3.0, failAt = 20, onClose = { closed = true }), work)
                .convert(song, tags, ConvertPlan.Encode(DownloadFormat.MP3, 192), 3_000_000L)
        }.exceptionOrNull()
        assertTrue("$failed", failed is IOException && failed.message == "the network went")
        assertTrue(closed)
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `a song that changes its rate halfway is refused, and leaves nothing`() {
        val work = File(folder.root, "convert")
        val failed = runCatching {
            converter(FakeDecoder(3.0, changeRateAt = 30), work).convert(song, tags, ConvertPlan.Encode(DownloadFormat.FLAC, null), 3_000_000L)
        }.exceptionOrNull()
        assertTrue("$failed", failed is IOException && failed.message!!.contains("changes"))
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `audio of more than two channels is refused by the encoder, and leaves nothing`() {
        val work = File(folder.root, "convert")
        val failed = runCatching {
            converter(FakeDecoder(1.0, channels = 3), work).convert(song, tags, ConvertPlan.Encode(DownloadFormat.MP3, 128), 1_000_000L)
        }.exceptionOrNull()
        assertTrue("$failed", failed is IllegalArgumentException)
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `a conversion that is stopped throws a cancellation, quickly, and leaves nothing`() {
        val work = File(folder.root, "convert")
        var pieces = 0
        val failed = runCatching {
            converter(FakeDecoder(30.0), work).convert(song, tags, ConvertPlan.Encode(DownloadFormat.MP3, 256), 30_000_000L, cancelled = { ++pieces > 5 })
        }.exceptionOrNull()
        assertTrue("$failed", failed is CancellationException)
        assertEquals("it stopped at the piece that was asked about", 6, pieces)
        assertEquals(emptyList<File>(), left(work))
    }

    @Test
    fun `the work folder is made if it is not there, and old leftovers are swept`() {
        val work = File(folder.root, "deep/convert")
        val converter = converter(FakeDecoder(0.5), work)
        converter.convert(song, tags, ConvertPlan.Encode(DownloadFormat.WAV, null), 0L).close()
        assertTrue(work.isDirectory)
        val old = File(work, "convert-old.part").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 7 * 3600 * 1000L) }
        val oldFrames = File(work, "convert-old.part.frames").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 7 * 3600 * 1000L) }
        val fresh = File(work, "convert-fresh.part").also { it.writeText("x") }
        val other = File(work, "keep.txt").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 7 * 3600 * 1000L) }
        converter.sweep()
        assertFalse(old.exists() || oldFrames.exists())
        assertTrue(fresh.exists() && other.exists())
    }

    // ---- what SongConversion makes of a stream

    private fun conversion(format: DownloadFormat, bitrate: DownloadBitrate = DownloadBitrate.AUTO, work: File = File(folder.root, "convert")) =
        SongConversion(format, bitrate, converter(FakeDecoder(1.0), work))

    // A second of Opus at the rate of Opus 251 on average, 129.6 kbps, as long as the fake decoder's song.
    private val opus = SongFiles.Stream.Opus(ByteArray(19), packets = 50, trim = 0, payloadBytes = 16_200L, samples = 48_000L)
    private val aac = SongFiles.Stream.Aac(M4aTrack(byteArrayOf(0x12, 0x10), 44100, 2, IntArray(100) { 400 }, IntArray(100) { 1024 }))
    private val none = CachedBytes(emptyList())

    @Test
    fun `Original, and AAC made M4A, are not converted at all`() {
        assertNull(conversion(DownloadFormat.ORIGINAL).convert(none, opus, tags))
        assertNull(conversion(DownloadFormat.ORIGINAL, DownloadBitrate.K320).convert(none, aac, tags))
        assertNull(conversion(DownloadFormat.M4A).convert(none, aac, tags))
        assertNull(conversion(DownloadFormat.M4A, DownloadBitrate.K256).convert(none, aac, tags))
    }

    @Test
    fun `Auto follows the stream that was read, not a setting`() {
        // 16,200 bytes in a second is 129.6 kbps, the usual stream: 256 as an MP3.
        assertEquals(129_600L, opus.bitsPerSecond)
        assertEquals(1_000_000L, opus.durationUs)
        conversion(DownloadFormat.MP3).convert(none, opus, tags)!!.use { assertEquals(256, it.kbps) }
        val small = SongFiles.Stream.Opus(ByteArray(19), 50, 0, payloadBytes = 6_075L, samples = 48_000L)
        assertEquals(48_600L, small.bitsPerSecond)
        conversion(DownloadFormat.MP3).convert(none, small, tags)!!.use { assertEquals(128, it.kbps) }
        conversion(DownloadFormat.MP3, DownloadBitrate.K320).convert(none, small, tags)!!.use { assertEquals(320, it.kbps) }
    }

    private fun <T : java.io.Closeable, R> T.use(block: (T) -> R): R = try { block(this) } finally { close() }

    @Test
    fun `a stream knows what it is, how long and how fast`() {
        assertEquals(SourceCodec.OPUS, opus.codec)
        assertEquals(SourceCodec.AAC, aac.codec)
        assertEquals("audio/webm", opus.mimeType)
        assertEquals("audio/mp4", aac.mimeType)
        // 100 frames of 1024 samples at 44.1 kHz, 40,000 bytes.
        assertEquals(2_321_995L, aac.durationUs)
        assertEquals(40_000L * 8 * 44100 / (100 * 1024), aac.bitsPerSecond)
        assertNull(SongFiles.Stream.Opus(ByteArray(19), 0, 0).bitsPerSecond)
    }
}
