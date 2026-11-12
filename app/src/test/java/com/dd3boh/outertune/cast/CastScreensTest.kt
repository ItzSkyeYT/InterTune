/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random

/** What the phone works out for the screens of InterTune's own receiver, before it sends it. */
class CastScreensTest {
    // The phone's name

    @Test
    fun `the TV is told the name the phone's owner gave it, else its model`() {
        assertEquals("Mel's Pixel", PhoneName.of("Mel's Pixel", "Pixel 5"))
        assertEquals("Pixel 5", PhoneName.of(null, "Pixel 5"))
        assertEquals("Pixel 5", PhoneName.of("   ", "Pixel 5"))
        assertEquals("Phone", PhoneName.of(null, null))
        assertEquals("Phone", PhoneName.of("", " "))
    }

    @Test
    fun `a long name is cut for a pill on a screen, and not left with a space at its end`() {
        val cut = PhoneName.of("A".repeat(30) + " " + "B".repeat(30), null)
        assertTrue(cut.length <= PhoneName.MAX_LENGTH)
        assertFalse(cut.endsWith(" "))
        assertEquals("  Mel's Pixel ".trim(), PhoneName.of("  Mel's Pixel ", null))
    }

    // Up next

    @Test
    fun `the next three songs are the three after the playing one`() {
        assertEquals(listOf(1, 2, 3), CastQueue.upcoming(0, 10, repeatAll = false))
        assertEquals(listOf(5, 6, 7), CastQueue.upcoming(4, 10, repeatAll = false))
    }

    @Test
    fun `fewer are listed near the end of a queue that does not repeat, and none after its last song`() {
        assertEquals(listOf(4), CastQueue.upcoming(3, 5, repeatAll = false))
        assertEquals(emptyList<Int>(), CastQueue.upcoming(4, 5, repeatAll = false))
        assertEquals(emptyList<Int>(), CastQueue.upcoming(0, 1, repeatAll = false))
    }

    @Test
    fun `a queue that repeats goes on from its first song, as Next does, and never lists the playing song`() {
        assertEquals(listOf(0, 1, 2), CastQueue.upcoming(4, 5, repeatAll = true))
        assertEquals(listOf(2, 0), CastQueue.upcoming(1, 3, repeatAll = true))
        assertEquals(listOf(1), CastQueue.upcoming(0, 2, repeatAll = true))
        assertEquals(emptyList<Int>(), CastQueue.upcoming(0, 1, repeatAll = true))
    }

    @Test
    fun `no playing song, or one outside the queue, lists nothing`() {
        assertEquals(emptyList<Int>(), CastQueue.upcoming(-1, 5, repeatAll = false))
        assertEquals(emptyList<Int>(), CastQueue.upcoming(5, 5, repeatAll = true))
        assertEquals(emptyList<Int>(), CastQueue.upcoming(0, 0, repeatAll = true))
        assertEquals(listOf(1), CastQueue.upcoming(0, 5, repeatAll = false, count = 1))
    }

    // A Listen together room

    @Test
    fun `a room counts everyone in it and shows the first letters of the first few`() {
        val names = listOf("mel", "Ann", "élodie", "Jo", "Kim", "Lou", "Max", "Zed")
        val room = CastRoom.of("Mel's room", names, "data:image/png;base64,AAAA")
        assertEquals(8, room.count)
        assertEquals(listOf("M", "A", "É", "J", "K", "L"), room.people)
        assertEquals("Mel's room", room.name)
    }

    @Test
    fun `a first letter is a whole emoji when the name starts with one, and a question mark for no name`() {
        assertEquals("M", CastRoom.initialOf("  mel"))
        assertEquals("😀", CastRoom.initialOf("😀 party"))
        assertEquals("?", CastRoom.initialOf("   "))
    }

    // The room's QR code as a PNG

    private fun int(png: ByteArray, at: Int): Int =
        ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)

    /**
     * The PNG's pixels back, as dark (true) or light, read as any decoder would: its chunks and
     * their checksums, the header a one-bit grey image must have, and the deflated rows.
     */
    private fun pixels(png: ByteArray): Array<BooleanArray> {
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), png.copyOf(8))
        var at = 8
        var side = 0
        val packed = ByteArrayOutputStream()
        val seen = mutableListOf<String>()
        while (at < png.size) {
            val length = int(png, at)
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().also { it.update(png, at + 4, 4 + length) }.value.toInt()
            assertEquals("checksum of $type", crc, int(png, at + 8 + length))
            if (type == "IHDR") {
                assertEquals(13, length)
                side = int(png, at + 8)
                assertEquals(side, int(png, at + 12))
                // One bit a pixel, grey, deflate, adaptive filtering, no interlace.
                assertEquals(listOf(1, 0, 0, 0, 0), (0 until 5).map { png[at + 16 + it].toInt() })
            }
            if (type == "IDAT") packed.write(png, at + 8, length)
            seen += type
            at += 12 + length
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), seen)
        val rowBytes = (side + 7) / 8
        val raw = ByteArray((rowBytes + 1) * side)
        val inflater = Inflater()
        inflater.setInput(packed.toByteArray())
        assertEquals(raw.size, inflater.inflate(raw))
        assertTrue(inflater.finished())
        inflater.end()
        return Array(side) { y ->
            assertEquals("filter of row $y", 0, raw[y * (rowBytes + 1)].toInt())
            BooleanArray(side) { x ->
                val byte = raw[y * (rowBytes + 1) + 1 + x / 8].toInt()
                // A clear bit is black.
                (byte and (0x80 shr (x % 8))) == 0
            }
        }
    }

    @Test
    fun `a QR code's PNG is an image of its modules, each as big as asked, with a white margin round it`() {
        val modules = arrayOf(
            booleanArrayOf(true, false, true),
            booleanArrayOf(false, true, false),
            booleanArrayOf(true, true, false),
        )
        val drawn = pixels(QrPng.encode(modules, scale = 3, quiet = 2))
        assertEquals((3 + 2 * 2) * 3, drawn.size)
        assertTrue(drawn.all { it.size == drawn.size })
        for (y in drawn.indices) for (x in drawn.indices) {
            val mx = x / 3 - 2
            val my = y / 3 - 2
            val dark = my in modules.indices && mx in modules.indices && modules[my][mx]
            assertEquals("pixel $x,$y", dark, drawn[y][x])
        }
    }

    @Test
    fun `a full-size code with a row length that is not a multiple of eight comes back pixel for pixel`() {
        val random = Random(7)
        val modules = Array(33) { BooleanArray(33) { random.nextBoolean() } }
        val drawn = pixels(QrPng.encode(modules, scale = 5))
        val quiet = QrPng.QUIET_MODULES
        assertEquals((33 + 2 * quiet) * 5, drawn.size)
        for (y in drawn.indices) for (x in drawn.indices) {
            val mx = x / 5 - quiet
            val my = y / 5 - quiet
            assertEquals("pixel $x,$y", my in 0 until 33 && mx in 0 until 33 && modules[my][mx], drawn[y][x])
        }
    }

    @Test
    fun `the data url is the PNG in base64, small enough for one Cast message`() {
        val random = Random(11)
        val modules = Array(41) { BooleanArray(41) { random.nextBoolean() } }
        val url = QrPng.dataUrl(modules)
        assertTrue(url.startsWith("data:image/png;base64,"))
        val back = java.util.Base64.getDecoder().decode(url.removePrefix("data:image/png;base64,"))
        assertArrayEquals(QrPng.encode(modules), back)
        // Even noise, which deflates badly, fits with room to spare.
        assertTrue("${url.length} characters", url.length < Chromecast.MAX_QR_CHARS)
    }

    @Test
    fun `a code that is not square is refused`() {
        try {
            QrPng.encode(arrayOf(booleanArrayOf(true, false), booleanArrayOf(true)))
            fail("A ragged code was drawn")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    // The room feed

    @Test
    fun `the room feed holds the room until it is closed`() {
        val room = CastRoom("Mel's room", 2, listOf("M", "A"), null)
        CastRoomFeed.set(room)
        assertEquals(room, CastRoomFeed.room.value)
        CastRoomFeed.set(null)
        assertEquals(null, CastRoomFeed.room.value)
    }

    // The goodbye

    private class FakeLink(private val tells: Boolean, private val sayFails: Boolean = false, private val pauseFails: Boolean = false) : ReceiverLink {
        val calls = CopyOnWriteArrayList<String>()
        override val device = CastDevice(CastProtocol.CHROMECAST, "chromecast:test", "Test TV", "127.0.0.1", 8009)
        override val caps = LinkCaps()
        override val status = MutableStateFlow(ReceiverStatus())

        override suspend fun connect() {}

        override suspend fun load(item: CastItem, startMs: Long, play: Boolean) {
            calls += "load"
        }

        override suspend fun play() {
            calls += "play"
        }

        override suspend fun pause() {
            calls += "pause"
            if (pauseFails) throw CastLinkException("gone")
        }

        override suspend fun seek(positionMs: Long) {}

        override suspend fun stop() {
            calls += "stop"
        }

        override suspend fun setVolume(level: Float) {}

        override suspend fun sayBye(): Boolean {
            calls += "bye"
            if (sayFails) throw CastLinkException("gone")
            return tells
        }

        override fun close() {}
    }

    @Test
    fun `a receiver that shows the goodbye is held quiet and given its moment before anything stops it`() {
        val link = FakeLink(tells = true)
        val started = System.nanoTime()
        assertTrue(runBlocking { CastFarewell.to(link, shownMs = 150) })
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started >= 140_000_000L)
        assertEquals(listOf("bye", "pause"), link.calls.toList())
    }

    @Test
    fun `a receiver with no screen for it is not waited for, and not paused`() {
        val link = FakeLink(tells = false)
        assertFalse(runBlocking { withTimeout(2_000) { CastFarewell.to(link, shownMs = 600_000) } })
        assertEquals(listOf("bye"), link.calls.toList())
    }

    @Test
    fun `a goodbye that cannot be sent is not waited for either, and throws nothing`() {
        val link = FakeLink(tells = true, sayFails = true)
        assertFalse(runBlocking { withTimeout(2_000) { CastFarewell.to(link, shownMs = 600_000) } })
        assertEquals(listOf("bye"), link.calls.toList())
    }

    @Test
    fun `a pause that fails does not spoil the goodbye`() {
        val link = FakeLink(tells = true, pauseFails = true)
        assertTrue(runBlocking { CastFarewell.to(link, shownMs = 20) })
        assertEquals(listOf("bye", "pause"), link.calls.toList())
    }

    @Test
    fun `the goodbye lasts about three seconds`() {
        assertEquals(3_000L, CastFarewell.SHOWN_MS)
    }
}
