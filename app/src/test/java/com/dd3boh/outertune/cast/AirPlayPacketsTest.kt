/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Locale

/** AirPlay 1's packets, field by field, as the unofficial specification and the senders that work have them. */
class AirPlayPacketsTest {

    /** Reads bits most significant first, as an ALAC decoder does. */
    private class Bits(private val data: ByteArray) {
        var position = 0
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = data[position / 8].toInt() and 0xFF
                value = (value shl 1) or ((byte shr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
    }

    private fun tone(frames: Int, channels: Int = 2): ShortArray = ShortArray(frames * channels) { index ->
        val frame = index / channels
        val channel = index % channels
        (Math.sin(frame * 2 * Math.PI * 440 / 44_100 + channel) * 20_000).toInt().toShort()
    }

    @Test
    fun `an audio header is version 2, type 96, the marker on the first packet only`() {
        val out = ByteArray(12)
        AirPlayRtp.audioHeader(out, 0, seq = 0xBEEF, timestamp = 0xFEDCBA98L, ssrc = 0x01020304, first = true)
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0xE0.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x98.toByte(), 1, 2, 3, 4),
            out,
        )
        AirPlayRtp.audioHeader(out, 0, seq = 1, timestamp = 352, ssrc = 0, first = false)
        assertEquals(0x60, out[1].toInt() and 0xFF)
        assertEquals(1, AirPlayRtp.get16(out, 2))
        assertEquals(352L, AirPlayRtp.get32(out, 4))
    }

    @Test
    fun `an uncompressed ALAC frame of 352 stereo frames is 1412 bytes and starts 20 00 02`() {
        assertEquals(1412, AirPlayAudio.alacBytes(352, 2))
        val samples = ShortArray(352 * 2)
        samples[0] = 0x1234
        val out = ByteArray(1412)
        assertEquals(1412, AirPlayAudio.alac(samples, 0, 352, 2, out, 0))
        // The three header bytes every sender that sends ALAC uncompressed begins with: a channel
        // pair, no size, not compressed; the 24th bit is already the first sample's top bit.
        assertEquals(0x20, out[0].toInt() and 0xFF)
        assertEquals(0x00, out[1].toInt() and 0xFF)
        assertEquals(0x02, out[2].toInt() and 0xFF)
    }

    @Test
    fun `every sample comes back from the ALAC frame's bits, then the end tag and zeros`() {
        val samples = tone(352)
        samples[1] = Short.MIN_VALUE
        samples[2] = Short.MAX_VALUE
        samples[3] = -1
        val out = ByteArray(1500)
        val length = AirPlayAudio.alac(samples, 0, 352, 2, out, 0)
        val bits = Bits(out.copyOf(length))
        assertEquals("channel pair element", 1, bits.read(3))
        assertEquals("element instance", 0, bits.read(4))
        assertEquals("unused", 0, bits.read(12))
        assertEquals("has size", 0, bits.read(1))
        assertEquals("shift", 0, bits.read(2))
        assertEquals("not compressed", 1, bits.read(1))
        for (index in samples.indices) {
            assertEquals("sample $index", samples[index], bits.read(16).toShort())
        }
        assertEquals("end tag", 7, bits.read(3))
        while (bits.position < length * 8) assertEquals("padding", 0, bits.read(1))
    }

    /**
     * Leaves a second of frames and the samples they came from in build/airplay, for
     * experiments/features/airplay/alac_check.py to hand to FFmpeg's own ALAC decoder: the check
     * that this is Apple Lossless as other software reads it, not only as these tests read it.
     */
    @Test
    fun `frames for FFmpeg's check are written out`() {
        val random = java.util.Random(5)
        val frames = 125 * 352
        val samples = ShortArray(frames * 2) { index ->
            when {
                index < 352 * 2 -> if (index % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE
                index < 352 * 20 -> (index * 7919).toShort()
                else -> random.nextInt(65536).toShort()
            }
        }
        val folder = java.io.File("build/airplay").apply { mkdirs() }
        val source = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            source[index * 2] = sample.toByte()
            source[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        java.io.File(folder, "alac-source.s16le").writeBytes(source)
        val out = java.io.ByteArrayOutputStream()
        val frame = ByteArray(AirPlayAudio.alacBytes(352, 2))
        for (packet in 0 until 125) {
            out.write(frame, 0, AirPlayAudio.alac(samples, packet * 352 * 2, 352, 2, frame, 0))
        }
        java.io.File(folder, "alac-frames.bin").writeBytes(out.toByteArray())
        assertEquals(125 * 1412, out.size())
    }

    @Test
    fun `a mono ALAC frame is a single channel element`() {
        val out = ByteArray(800)
        val length = AirPlayAudio.alac(tone(352, 1), 0, 352, 1, out, 0)
        assertEquals(AirPlayAudio.alacBytes(352, 1), length)
        assertEquals(0, Bits(out).read(3))
    }

    @Test
    fun `L16 is big-endian`() {
        val out = ByteArray(8)
        assertEquals(8, AirPlayAudio.pcm(shortArrayOf(0x1234, -2, 0, 0x7F00), 0, 2, 2, out, 0))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0xFE.toByte(), 0, 0, 0x7F, 0), out)
    }

    @Test
    fun `a sync packet says which frame plays at which NTP time`() {
        val first = AirPlayRtp.sync(now = 100_000, latency = 88_200, ntp = 0x0102030405060708L, first = true)
        assertEquals(20, first.size)
        assertEquals(0x90, first[0].toInt() and 0xFF)
        assertEquals(0xD4, first[1].toInt() and 0xFF)
        assertEquals(7, AirPlayRtp.get16(first, 2))
        assertEquals(100_000L - 88_200, AirPlayRtp.get32(first, 4))
        assertEquals(0x0102030405060708L, AirPlayRtp.get64(first, 8))
        assertEquals(100_000L, AirPlayRtp.get32(first, 16))
        assertEquals(0x80, AirPlayRtp.sync(5, 10, 0, first = false)[0].toInt() and 0xFF)
        // Across RTP time's wrap the frame being played is still 32 bits.
        assertEquals(0xFFFFFFFFL - 9, AirPlayRtp.get32(AirPlayRtp.sync(0, 10, 0, false), 4))
    }

    @Test
    fun `a timing answer carries the question's send time as its origin`() {
        val question = ByteArray(32)
        question[0] = 0x80.toByte()
        question[1] = 0xD2.toByte()
        question[3] = 7
        AirPlayRtp.put64(question, 24, 0x1111222233334444L)
        assertTrue(AirPlayRtp.isTimingRequest(question, 32))
        assertFalse(AirPlayRtp.isTimingRequest(question, 20))
        val reply = AirPlayRtp.timingReply(question, received = 0x5555L shl 32, sent = (0x5555L shl 32) + 1)
        assertEquals(0x80, reply[0].toInt() and 0xFF)
        assertEquals(0xD3, reply[1].toInt() and 0xFF)
        assertEquals(7, AirPlayRtp.get16(reply, 2))
        assertEquals(0L, AirPlayRtp.get32(reply, 4))
        assertEquals(0x1111222233334444L, AirPlayRtp.get64(reply, 8))
        assertEquals(0x5555L shl 32, AirPlayRtp.get64(reply, 16))
        assertEquals((0x5555L shl 32) + 1, AirPlayRtp.get64(reply, 24))
    }

    @Test
    fun `a resend request names the first lost packet and how many, and the answer is the packet whole`() {
        val request = byteArrayOf(0x80.toByte(), 0xD5.toByte(), 0, 1, 0xFF.toByte(), 0xFE.toByte(), 0, 3)
        assertEquals(0xFFFE to 3, AirPlayRtp.resendRequest(request, 8))
        assertNull(AirPlayRtp.resendRequest(request, 7))
        assertNull(AirPlayRtp.resendRequest(byteArrayOf(0x80.toByte(), 0xD4.toByte(), 0, 1, 0, 0, 0, 1), 8))
        val packet = ByteArray(20) { it.toByte() }
        val reply = AirPlayRtp.resendReply(packet, 16)
        assertEquals(20, reply.size)
        assertEquals(0xD6, reply[1].toInt() and 0xFF)
        // Never 0: a receiver takes a sequence of 0 there for a request of its own.
        assertEquals(1, AirPlayRtp.get16(reply, 2))
        assertArrayEquals(packet.copyOf(16), reply.copyOfRange(4, 20))
    }

    @Test
    fun `NTP time counts seconds from 1900 with fractions below`() {
        assertEquals(2_208_988_800L shl 32, AirPlayClock.ntpOfMillis(0))
        assertEquals((2_208_988_801L shl 32) + (1L shl 31), AirPlayClock.ntpOfMillis(1_500))
        assertEquals(1L shl 32, AirPlayClock.nanosToNtp(1_000_000_000L))
        assertEquals(1L shl 30, AirPlayClock.nanosToNtp(250_000_000L))
        var nanos = 5_000_000_000L
        val clock = AirPlayClock(nanoTime = { nanos }, wallMillis = 0)
        nanos += 2_000_000_000L
        assertEquals((2_208_988_802L shl 32), clock.ntp())
    }

    @Test
    fun `the SDP announces Apple Lossless with its parameters, or L16`() {
        val local = InetAddress.getByName("192.168.1.10")
        val remote = InetAddress.getByName("192.168.1.20")
        assertEquals(
            "v=0\r\no=iTunes 1234 0 IN IP4 192.168.1.10\r\ns=iTunes\r\nc=IN IP4 192.168.1.20\r\nt=0 0\r\nm=audio 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 AppleLossless\r\na=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n",
            AirPlaySdp.announce(1234, local, remote, AirPlayCodec.ALAC),
        )
        val pcm = AirPlaySdp.announce(1, local, remote, AirPlayCodec.PCM, rsaKey = "KEY", iv = "IV")
        assertTrue("a=rtpmap:96 L16/44100/2\r\n" in pcm)
        assertTrue(pcm.endsWith("a=rsaaeskey:KEY\r\na=aesiv:IV\r\n"))
        // Java writes IPv6 in full; the zone (%1) never goes in.
        assertTrue("IN IP6 fe80:0:0:0:0:0:0:1\r\n" in AirPlaySdp.announce(1, InetAddress.getByName("fe80::1%1"), remote, AirPlayCodec.ALAC))
    }

    @Test
    fun `the volume goes from -30 dB to 0, -144 for silence, written with a point in any language`() {
        assertEquals(-144.0, AirPlaySdp.volumeDb(0f), 0.0)
        assertEquals(-29.7, AirPlaySdp.volumeDb(0.01f), 1e-4)
        assertEquals(-15.0, AirPlaySdp.volumeDb(0.5f), 1e-6)
        assertEquals(0.0, AirPlaySdp.volumeDb(1f), 0.0)
        assertEquals(0.0, AirPlaySdp.volumeDb(3f), 0.0)
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("volume: -22.500000\r\n", AirPlaySdp.volumeBody(-22.5))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `the song's details go as DAAP tags in an item, and its progress as three RTP times`() {
        val daap = AirPlayMetadata.daap("Hé", "Ab", null)
        assertArrayEquals(
            "mlit".toByteArray() + byteArrayOf(0, 0, 0, 21) +
                "minm".toByteArray() + byteArrayOf(0, 0, 0, 3) + "Hé".toByteArray(Charsets.UTF_8) +
                "asar".toByteArray() + byteArrayOf(0, 0, 0, 2) + "Ab".toByteArray(),
            daap,
        )
        assertEquals("progress: 4294967295/0/44099\r\n", AirPlayMetadata.progress(-1, 0x100000000L, 44_099))
    }

    @Test
    fun `base64 for the SDP drops the padding`() {
        assertEquals("", AirPlayLink.base64(ByteArray(0)))
        assertEquals("Zg", AirPlayLink.base64("f".toByteArray()))
        assertEquals("Zm8", AirPlayLink.base64("fo".toByteArray()))
        assertEquals("Zm9v", AirPlayLink.base64("foo".toByteArray()))
        assertEquals("Zm9vYmFy", AirPlayLink.base64("foobar".toByteArray()))
        assertEquals("/+8", AirPlayLink.base64(byteArrayOf(0xFF.toByte(), 0xEF.toByte())))
    }
}
