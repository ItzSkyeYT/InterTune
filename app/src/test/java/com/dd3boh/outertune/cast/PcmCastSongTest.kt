/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Raw audio served from a decode: its bytes, their order, its length, and every range of it,
 * against a fake decoder whose samples are worked out from their frame, so any byte can be checked.
 */
class PcmCastSongTest {
    /** Frame [frame]'s sample in [channel], the same wherever a decode starts. */
    private fun sampleAt(frame: Long, channel: Int): Short = (((frame * 37 + channel * 11_003) % 65_536) - 32_768).toShort()

    /** The song as it is, which the fake decoder never reads. */
    private val asIs = object : CastSong {
        override val mimeType = "audio/webm"
        override val length = 1_000_000L
        override fun openAt(position: Long): CastReader = error("The fake decoder reads nothing")
    }

    /**
     * A song of [frames] frames at [rate] in [channels] channels, given in pieces of [piece] frames
     * starting on a piece's edge at or before the time asked, as a seek to a sync sample does. It
     * can give [decodes] frames instead (a decode short or long of the duration), or fail at [failAt].
     */
    private inner class FakeDecoder(
        val rate: Int,
        val channels: Int,
        val frames: Long,
        val decodes: Long = frames,
        val piece: Int = 960,
        val failAt: Long = -1,
        val sample: (Long, Int) -> Short = ::sampleAt,
    ) : PcmDecoder {
        val opens = AtomicInteger()
        val closes = AtomicInteger()

        override fun probe(song: CastSong) = PcmSource(rate, channels, frames * 1_000_000L / rate)

        override fun open(song: CastSong, fromUs: Long): DecodedAudio {
            opens.incrementAndGet()
            val start = fromUs * rate / 1_000_000L / piece * piece
            return object : DecodedAudio {
                var at = start
                val samples = ShortArray(piece * channels)

                override fun next(): DecodedPiece? {
                    if (at >= decodes) return null
                    if (failAt in at until at + piece) throw IOException("The network went")
                    val count = minOf(piece.toLong(), decodes - at).toInt()
                    for (frame in 0 until count) for (channel in 0 until channels) samples[frame * channels + channel] = sample(at + frame, channel)
                    // Times in whole microseconds, as MediaCodec gives them.
                    val decoded = DecodedPiece(at * 1_000_000L / rate, rate, channels, samples, count)
                    at += count
                    return decoded
                }

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }
    }

    private fun song(decoder: PcmDecoder, form: PcmForm, parking: PcmParking? = null) =
        PcmCastSong(asIs, form, decoder.probe(asIs), decoder, parking)

    /**
     * Opus as YouTube's files decode on Android (the Pixel 5, 4 Nov 2026): packets of 960 samples at
     * 48 kHz, the first 312 samples of the first packet each decode reads trimmed off (the pre-skip),
     * the first packet stamped 0 and every later one at k x 20 ms + 1 ms. A packet's samples are the
     * song's frames from k x 960 - 312, so a decode from anywhere gives the same samples for a frame.
     */
    private inner class OpusLikeDecoder(val frames: Long) : PcmDecoder {
        override fun probe(song: CastSong) = PcmSource(48_000, 2, frames * 1_000_000L / 48_000, PcmAnchor.offsetUs(open(song, 0)))

        override fun open(song: CastSong, fromUs: Long): DecodedAudio = object : DecodedAudio {
            var packet = fromUs / 20_000
            var first = true
            val samples = ShortArray(960 * 2)

            override fun next(): DecodedPiece? {
                val skip = if (first) 312 else 0
                val from = packet * 960 - 312 + skip
                if (from >= frames) return null
                val count = minOf(960L - skip, frames - from).toInt()
                for (i in 0 until count) for (channel in 0 until 2) samples[i * 2 + channel] = sampleAt(from + i, channel)
                val stamp = if (packet == 0L) 0L else packet * 20_000 + 1_000
                packet++
                first = false
                return DecodedPiece(stamp, 48_000, 2, samples, count)
            }

            override fun close() = Unit
        }
    }

    @Test
    fun `a song's stamps are measured against its samples from its first two pieces`() {
        fun audio(vararg pieces: Pair<Long, Int>) = object : DecodedAudio {
            val left = pieces.toMutableList()
            override fun next() = left.removeFirstOrNull()?.let { (us, frames) -> DecodedPiece(us, 48_000, 2, ShortArray(frames * 2), frames) }
            override fun close() = Unit
        }
        // YouTube's Opus: a 648-frame first packet stamped 0, then 21 ms.
        assertEquals(7_500L, PcmAnchor.offsetUs(audio(0L to 648, 21_000L to 960, 41_000L to 960)))
        // Exact stamps, nothing trimmed.
        assertEquals(0L, PcmAnchor.offsetUs(audio(0L to 960, 20_000L to 960)))
        assertEquals(0L, PcmAnchor.offsetUs(audio(0L to 960)))
        assertEquals(0L, PcmAnchor.offsetUs(audio()))
    }

    @Test
    fun `a decode started mid-song is placed by its first whole packet, not by its first stamp`() {
        val decoder = OpusLikeDecoder(480_000)
        assertEquals(7_500L, decoder.probe(asIs).offsetUs)
        // From 4.8 s: a 648-frame first piece stamped 4.801 s, whose first sample is frame 230,400.
        val anchored = AnchoredAudio(decoder.open(asIs, 4_800_000), 7_500)
        val first = anchored.next()!!
        assertEquals(648, first.frames)
        assertEquals(4_800_000L, first.timeUs)
        assertEquals(sampleAt(230_400, 0), first.samples[0])
        assertEquals(4_821_000L, anchored.next()!!.timeUs)
    }

    @Test
    fun `every range is the bytes a decode from the start gives, though the decoder's stamps sit 1 ms off`() {
        // As cast to the Pixel's test speaker on 4 Nov 2026, at the song's own rate and resampled.
        for (form in listOf(PcmForm(PcmContainer.L16, 48_000, 2), cd)) {
            val song = song(OpusLikeDecoder(480_000), form)
            val whole = song.bytes(0)
            assertEquals(song.length, whole.size.toLong())
            for (position in listOf(4L, 384_000L, 768_002L, 1_000_000L, 1_536_001L)) {
                val part = song.bytes(position, 16_000)
                assertArrayEquals("${form.rate} Hz from $position", whole.copyOfRange(position.toInt(), position.toInt() + part.size), part)
            }
        }
        // At the song's own rate the samples are the song's own frames, whichever decode gave them.
        val direct = song(OpusLikeDecoder(480_000), PcmForm(PcmContainer.L16, 48_000, 2)).bytes(400_000, 8)
        assertEquals(sampleAt(100_000, 0), ((direct[0].toInt() shl 8) or (direct[1].toInt() and 0xFF)).toShort())
        assertEquals(sampleAt(100_000, 1), ((direct[2].toInt() shl 8) or (direct[3].toInt() and 0xFF)).toShort())
    }

    /** [count] bytes from [position], read as the server reads, in pieces; fewer if the song ends. */
    private fun CastSong.bytes(position: Long, count: Long = Long.MAX_VALUE, pieceBytes: Int = 65_536): ByteArray {
        val out = ByteArrayOutputStream()
        openAt(position).use { reader ->
            val buffer = ByteArray(pieceBytes)
            var left = count
            while (left > 0) {
                val read = reader.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                left -= read
            }
        }
        return out.toByteArray()
    }

    private val cd = PcmForm(PcmContainer.L16, 44_100, 2)

    @Test
    fun `L16 is each frame's samples, 16-bit and big-endian`() {
        val bytes = song(FakeDecoder(44_100, 2, 44_100), cd).bytes(0, 4_000)
        for (frame in 0 until 1_000) {
            for (channel in 0 until 2) {
                val at = frame * 4 + channel * 2
                val value = ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort()
                assertEquals("frame $frame channel $channel", sampleAt(frame.toLong(), channel), value)
            }
        }
    }

    @Test
    fun `WAV is its header, then the same samples little-endian`() {
        val form = PcmForm(PcmContainer.WAV, 44_100, 2)
        val song = song(FakeDecoder(44_100, 2, 44_100), form)
        assertEquals(44L + 44_100 * 4, song.length)
        val bytes = song.bytes(0)
        assertArrayEquals(form.wavHeader(44_100), bytes.copyOf(44))
        for (frame in 0 until 1_000) {
            val at = 44 + frame * 4
            assertEquals(sampleAt(frame.toLong(), 0), ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort())
        }
        // A range that starts inside the header gets the rest of it, then the samples.
        assertArrayEquals(bytes.copyOfRange(10, 5_000), song.bytes(10, 4_990))
    }

    @Test
    fun `the length is exact, and every range is the whole song's bytes from where it starts`() {
        val decoder = FakeDecoder(44_100, 2, 441_000)
        val song = song(decoder, cd)
        assertEquals(1_764_000L, song.length)
        assertEquals(10_000L, song.durationMs)
        val whole = song.bytes(0)
        assertEquals(song.length, whole.size.toLong())
        // Frames, the middle of frames, the start and the very end, as a renderer's seek asks.
        for (position in listOf(1L, 2L, 3L, 4L, 4_097L, 176_400L, 882_001L, 1_763_999L, 1_763_996L)) {
            val part = song.bytes(position, 20_000)
            assertArrayEquals("from $position", whole.copyOfRange(position.toInt(), minOf(whole.size, position.toInt() + 20_000)), part)
        }
        // Nothing past the end.
        assertEquals(0, song.bytes(song.length).size)
    }

    @Test
    fun `a decode short of the duration is made up with silence, and one that runs over is cut`() {
        val short = song(FakeDecoder(44_100, 2, 44_100, decodes = 40_000), cd)
        val bytes = short.bytes(0)
        assertEquals(176_400, bytes.size)
        assertTrue(bytes.copyOfRange(160_000, 176_400).all { it == 0.toByte() })
        assertFalse(bytes.copyOfRange(159_000, 160_000).all { it == 0.toByte() })
        val long = song(FakeDecoder(44_100, 2, 44_100, decodes = 50_000), cd)
        assertEquals(176_400, long.bytes(0).size)
    }

    @Test
    fun `a decode that fails is an error, never silence`() {
        val song = song(FakeDecoder(44_100, 2, 44_100, failAt = 20_000), cd)
        try {
            song.bytes(0)
            fail("A failed decode was served as if it had ended")
        } catch (e: IOException) {
            assertEquals("The network went", e.message)
        }
    }

    @Test
    fun `a rate the renderer does not list is changed, and every range still matches the whole`() {
        // Opus at 48 kHz to a speaker that lists only 44.1.
        val decoder = FakeDecoder(48_000, 2, 96_000) { frame, channel ->
            (kotlin.math.sin(frame * 0.05 + channel) * 12_000).toInt().toShort()
        }
        val song = song(decoder, cd)
        assertEquals(88_200L * 4, song.length)
        val whole = song.bytes(0)
        assertEquals(song.length, whole.size.toLong())
        for (position in listOf(2L, 40_000L, 176_402L, 300_001L)) {
            val part = song.bytes(position, 8_000)
            assertArrayEquals("from $position", whole.copyOfRange(position.toInt(), position.toInt() + part.size), part)
            assertEquals(minOf(8_000L, song.length - position), part.size.toLong())
        }
    }

    @Test
    fun `one channel is sent in both, and two are averaged into one`() {
        val mono = song(FakeDecoder(44_100, 1, 4_410), cd).bytes(0, 400)
        for (frame in 0 until 100) {
            val left = ((mono[frame * 4].toInt() shl 8) or (mono[frame * 4 + 1].toInt() and 0xFF)).toShort()
            val right = ((mono[frame * 4 + 2].toInt() shl 8) or (mono[frame * 4 + 3].toInt() and 0xFF)).toShort()
            assertEquals(sampleAt(frame.toLong(), 0), left)
            assertEquals(left, right)
        }
        val form = PcmForm(PcmContainer.L16, 44_100, 1)
        val down = song(FakeDecoder(44_100, 2, 4_410) { frame, channel -> (if (channel == 0) 1_000 + frame else 3_000 + frame).toShort() }, form).bytes(0, 200)
        for (frame in 0 until 100) {
            assertEquals((2_000 + frame).toShort(), ((down[frame * 2].toInt() shl 8) or (down[frame * 2 + 1].toInt() and 0xFF)).toShort())
        }
    }

    @Test
    fun `a request that starts where the last one stopped carries on with the same decode`() {
        val decoder = FakeDecoder(44_100, 2, 441_000)
        val parking = PcmParking()
        val song = song(decoder, cd, parking)
        val whole = song(FakeDecoder(44_100, 2, 441_000), cd).bytes(0)
        // In pieces of 100,000 bytes, as some renderers fetch.
        val pieces = ByteArrayOutputStream()
        var position = 0L
        while (position < song.length) {
            val part = song.bytes(position, 100_000, pieceBytes = 7_000)
            pieces.write(part)
            position += part.size
        }
        assertArrayEquals(whole, pieces.toByteArray())
        assertEquals("one decode for the whole song", 1, decoder.opens.get())
        // A jump elsewhere is a decode of its own, and the one kept for later stays kept.
        song.bytes(500_000, 10)
        assertEquals(2, decoder.opens.get())
    }

    @Test
    fun `decoders kept for later are few, and let go when the song or the cast is done`() {
        var clock = 0L
        val parking = PcmParking(capacity = 2, idleMs = 30_000, now = { clock })
        val decoder = FakeDecoder(44_100, 2, 441_000)
        val song = song(decoder, cd, parking)
        for (start in listOf(0L, 400_000L, 800_000L)) song.bytes(start, 1_000)
        assertEquals(3, decoder.opens.get())
        assertEquals("only two kept", 2, parking.size)
        assertEquals(1, decoder.closes.get())
        // Kept too long: let go at the next look.
        clock += 31_000
        song.bytes(1_200_000, 1_000)
        assertEquals(3, decoder.closes.get())
        song.close()
        assertEquals(0, parking.size)
        assertEquals(4, decoder.closes.get())
        // After the cast nothing is kept.
        parking.shut()
        song.bytes(0, 1_000)
        assertEquals(0, parking.size)
        assertEquals(5, decoder.closes.get())
    }

    @Test
    fun `a song that ended or failed is never kept`() {
        val parking = PcmParking()
        val ended = FakeDecoder(44_100, 2, 4_410)
        song(ended, cd, parking).bytes(0)
        assertEquals(0, parking.size)
        assertEquals(1, ended.closes.get())
        val failing = FakeDecoder(44_100, 2, 44_100, failAt = 1_000)
        runCatching { song(failing, cd, parking).bytes(0) }
        assertEquals(0, parking.size)
        assertEquals(1, failing.closes.get())
    }
}
