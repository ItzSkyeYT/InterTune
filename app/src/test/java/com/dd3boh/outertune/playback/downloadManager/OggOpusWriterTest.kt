/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/**
 * Opus packets put into an Ogg file as RFC 7845 asks (OggOpusWriter): the same packets come out,
 * in pages whose checksums, numbers and granule positions any player trusts.
 */
class OggOpusWriterTest {

    @Test
    fun `a packet's length is read from its first byte`() {
        // CELT 20 ms, one frame: what YouTube's Opus is made of.
        assertEquals(960, OpusPackets.samples(byteArrayOf((31 shl 3).toByte(), 1, 2)))
        // CELT 2.5 ms, two frames.
        assertEquals(240, OpusPackets.samples(byteArrayOf(((16 shl 3) or 1).toByte(), 1)))
        // SILK 60 ms.
        assertEquals(2880, OpusPackets.samples(byteArrayOf((3 shl 3).toByte())))
        // Hybrid 10 and 20 ms.
        assertEquals(480, OpusPackets.samples(byteArrayOf((12 shl 3).toByte())))
        assertEquals(960, OpusPackets.samples(byteArrayOf((13 shl 3).toByte())))
        // Code 3: the count in the second byte, five 20 ms frames.
        assertEquals(4800, OpusPackets.samples(byteArrayOf(((31 shl 3) or 3).toByte(), 5)))
        for (bad in listOf(byteArrayOf(), byteArrayOf(((31 shl 3) or 3).toByte()), byteArrayOf(((31 shl 3) or 3).toByte(), 7))) {
            try {
                OpusPackets.samples(bad)
                fail("${bad.toList()} is no Opus packet")
            } catch (expected: IOException) {
            }
        }
    }

    @Test
    fun `the same packets come out, a second of them to a page`() {
        val random = Random(3)
        val packets = List(250) { random.nextBytes(200 + random.nextInt(400)).also { it[0] = (31 shl 3).toByte() } }
        val head = OggOpusWriter.opusHead(2, 312)
        val tags = OggOpusWriter.opusTags("InterTune", listOf("TITLE=Song"))
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, 0x1234).apply {
            writeHeaders(head, tags)
            for (p in packets) writeAudio(p, OpusPackets.samples(p))
            finish(trim = 100)
        }
        val ogg = OggRead(out.toByteArray())

        assertEquals(listOf(head.toList(), tags.toList()) + packets.map { it.toList() }, ogg.packets.map { it.toList() })
        val pages = ogg.pages
        assertTrue("all one stream", pages.all { it.serial == 0x1234 })
        assertEquals((pages.indices).toList(), pages.map { it.sequence })
        // The ID header alone on the first page, the stream's start, at 0; the tags on the next, at 0.
        assertEquals(OggPages.FIRST, pages[0].flags)
        assertEquals(0L, pages[0].granule)
        assertEquals(1, pages[0].packetsEnding)
        assertEquals(0L, pages[1].granule)
        assertEquals(0, pages[1].flags)
        // Each audio page at the samples decoded by its end; 250 packets of 20 ms: five pages.
        val audio = pages.drop(2)
        assertEquals(5, audio.size)
        assertEquals(listOf(48_000L, 96_000L, 144_000L, 192_000L, 240_000L - 100), audio.map { it.granule })
        assertEquals(OggPages.LAST, audio.last().flags)
        assertTrue(audio.dropLast(1).all { it.flags == 0 })
    }

    @Test
    fun `a comment header with a large cover spans pages, ended and marked as the RFC says`() {
        val cover = Random(5).nextBytes(150_000)
        val tags = OggOpusWriter.opusTags("InterTune", listOf("TITLE=Song", "METADATA_BLOCK_PICTURE=" + base64(cover)))
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, 7).apply {
            writeHeaders(OggOpusWriter.opusHead(2, 312), tags)
            writeAudio(byteArrayOf((31 shl 3).toByte(), 1, 2, 3), 960)
            finish()
        }
        val ogg = OggRead(out.toByteArray())
        assertArrayEquals(tags, ogg.packets[1])
        val header = ogg.pages.drop(1).takeWhile { it.packetsEnding == 0 } + ogg.pages.drop(1).first { it.packetsEnding > 0 }
        assertTrue("150 KB of picture take more than one page", header.size >= 4)
        // -1 on the pages it goes on across, 0 on the one it ends on; continued from the second.
        assertTrue(header.dropLast(1).all { it.granule == -1L })
        assertEquals(0L, header.last().granule)
        assertTrue(header.drop(1).all { it.flags and OggPages.CONTINUED != 0 })
        // The audio starts on a page of its own.
        val audio = ogg.pages[header.size + 1]
        assertEquals(0, audio.flags and OggPages.CONTINUED)
        assertEquals(960L, audio.granule)
        assertEquals(OggPages.LAST, audio.flags)
    }

    @Test
    fun `a packet of exactly 255 bytes ends with a lacing value of 0`() {
        val out = ByteArrayOutputStream()
        val packet = ByteArray(255).also { it[0] = (31 shl 3).toByte() }
        OggOpusWriter(out, 1).apply {
            writeHeaders(OggOpusWriter.opusHead(1, 312), OggOpusWriter.opusTags("x", emptyList()))
            writeAudio(packet, 960)
            writeAudio(packet, 960)
            finish()
        }
        val ogg = OggRead(out.toByteArray())
        assertEquals(4, ogg.packets.size)
        assertArrayEquals(packet, ogg.packets[2])
        assertEquals(listOf(255, 0, 255, 0), ogg.pages.last().lacing)
    }

    @Test
    fun `the trim never takes more than the last packet holds`() {
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, 1).apply {
            writeHeaders(OggOpusWriter.opusHead(2, 312), OggOpusWriter.opusTags("x", emptyList()))
            repeat(60) { writeAudio(byteArrayOf((31 shl 3).toByte()), 960) }
            finish(trim = 1_000_000)
        }
        // 50 packets (48 000 samples) on the first audio page, 10 on the last, the last of them trimmed.
        assertEquals(listOf(48_000L, 57_600L - 960), OggRead(out.toByteArray()).pages.drop(2).map { it.granule })
    }

    @Test
    fun `the ID header is the RFC's`() {
        val head = OggOpusWriter.opusHead(2, 312)
        assertEquals("OpusHead", String(head, 0, 8))
        assertEquals(19, head.size)
        assertEquals(1, head[8].toInt())
        assertEquals(2, head[9].toInt())
        assertEquals(312, OggOpusWriter.preSkip(head))
        assertEquals(48_000, (head[12].toInt() and 0xFF) or ((head[13].toInt() and 0xFF) shl 8) or ((head[14].toInt() and 0xFF) shl 16))
        assertTrue(OggOpusWriter.isOpusHead(head))
        val tags = OggOpusWriter.opusTags("InterTune", listOf("TITLE=Été"))
        assertEquals("OpusTags", String(tags, 0, 8))
        assertEquals(9, tags[8].toInt())
        assertEquals("InterTune", String(tags, 12, 9))
        assertEquals(1, tags[21].toInt())
        assertEquals("TITLE=Été".toByteArray().size, tags[25].toInt())
        assertEquals("TITLE=Été", String(tags, 29, tags.size - 29))
    }

    /** An Ogg reader of the test's own, its checksum worked out bit by bit, not from the writer's table. */
    class OggRead(bytes: ByteArray) {
        class Page(val flags: Int, val granule: Long, val serial: Int, val sequence: Int, val lacing: List<Int>, val packetsEnding: Int)

        val pages = ArrayList<Page>()
        val packets = ArrayList<ByteArray>()

        init {
            var at = 0
            val packet = ByteArrayOutputStream()
            while (at < bytes.size) {
                assertEquals("OggS", String(bytes, at, 4))
                assertEquals(0, bytes[at + 4].toInt())
                val flags = bytes[at + 5].toInt() and 0xFF
                var granule = 0L
                for (i in 7 downTo 0) granule = (granule shl 8) or (bytes[at + 6 + i].toLong() and 0xFF)
                val serial = le32(bytes, at + 14)
                val sequence = le32(bytes, at + 18)
                val crc = le32(bytes, at + 22)
                val count = bytes[at + 26].toInt() and 0xFF
                val lacing = (0 until count).map { bytes[at + 27 + it].toInt() and 0xFF }
                val length = 27 + count + lacing.sum()
                val page = bytes.copyOfRange(at, at + length)
                for (i in 22..25) page[i] = 0
                assertEquals("page $sequence's checksum", crc, crcOf(page))
                var data = at + 27 + count
                var ending = 0
                for (l in lacing) {
                    packet.write(bytes, data, l)
                    data += l
                    if (l < 255) {
                        packets += packet.toByteArray()
                        packet.reset()
                        ending++
                    }
                }
                pages += Page(flags, granule, serial, sequence, lacing, ending)
                at += length
            }
            assertEquals("nothing left over", 0, packet.size())
        }

        private fun le32(b: ByteArray, i: Int) =
            (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

        private fun crcOf(b: ByteArray): Int {
            var crc = 0
            for (byte in b) {
                crc = crc xor ((byte.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}
