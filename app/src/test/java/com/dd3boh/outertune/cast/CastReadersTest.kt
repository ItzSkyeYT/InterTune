/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress

class CastReadersTest {

    private val song = ByteArray(10_000) { (it * 7 + it / 3).toByte() }

    /** A source over [song] from [from] that gives at most [limit] bytes, and notes it was closed. */
    private inner class Piece(from: Long, limit: Long, private val closed: MutableList<Long>) : CastReader {
        private var at = from.toInt()
        private val stop = minOf(from + limit, song.size.toLong()).toInt()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= stop) return -1
            val count = minOf(length, stop - at)
            System.arraycopy(song, at, buffer, offset, count)
            at += count
            return count
        }

        override fun close() {
            closed += at.toLong()
        }
    }

    private fun readAll(reader: CastReader, step: Int = 777): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(step)
        reader.use {
            while (true) {
                val count = it.read(buffer, 0, buffer.size)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    // ChunkedReader

    @Test
    fun `short sources are joined into the whole range`() {
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        val reader = ChunkedReader(1500, 9000) { from -> opened += from; Piece(from, 2000, closed) }
        assertArrayEquals(song.copyOfRange(1500, 9000), readAll(reader))
        assertEquals(listOf(1500L, 3500L, 5500L, 7500L), opened)
        assertEquals(listOf(3500L, 5500L, 7500L, 9000L), closed)
    }

    @Test
    fun `nothing past the end is asked for`() {
        val asked = mutableListOf<Int>()
        val reader = ChunkedReader(0, 100) { from ->
            object : CastReader {
                val piece = Piece(from, 10_000, mutableListOf())
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    asked += length
                    return piece.read(buffer, offset, length)
                }

                override fun close() = Unit
            }
        }
        assertEquals(100, readAll(reader, step = 64).size)
        assertTrue(asked.toString(), asked.all { it <= 64 } && asked.sum() <= 100)
    }

    @Test
    fun `a source that ends without giving anything is an error, not a loop`() {
        val reader = ChunkedReader(0, 10_000) { from -> Piece(from, if (from < 5000) 5000 else 0, mutableListOf()) }
        val buffer = ByteArray(1000)
        var read = 0
        try {
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count < 0) break
                read += count
            }
            fail("Read to the end of a song that stops at 5000")
        } catch (expected: IOException) {
        }
        assertEquals(5000, read)
    }

    @Test
    fun `a source stuck at zero bytes is an error`() {
        val reader = ChunkedReader(0, 100) {
            object : CastReader {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
                override fun close() = Unit
            }
        }
        try {
            reader.read(ByteArray(10), 0, 10)
            fail("Spun on a source that gives nothing")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun `with the end unknown the first end is the end`() {
        val opened = mutableListOf<Long>()
        val reader = ChunkedReader(0, -1) { from -> opened += from; Piece(from, 4000, mutableListOf()) }
        assertEquals(4000, readAll(reader).size)
        assertEquals(listOf(0L), opened)
    }

    @Test
    fun `an empty range opens nothing`() {
        val reader = ChunkedReader(500, 500) { fail("Opened"); error("") }
        assertEquals(-1, reader.read(ByteArray(10), 0, 10))
    }

    @Test
    fun `closing mid-song closes the source it is on`() {
        val closed = mutableListOf<Long>()
        val reader = ChunkedReader(0, 10_000) { from -> Piece(from, 3000, closed) }
        reader.read(ByteArray(100), 0, 100)
        reader.close()
        assertEquals(listOf(100L), closed)
        reader.close()
        assertEquals(1, closed.size)
    }

    // RefreshingOpener

    @Test
    fun `a refused url is resolved once more and the new one used from then on`() {
        val tried = mutableListOf<String>()
        val opener = RefreshingOpener(
            "old",
            open = { url, from ->
                tried += url
                if (url == "old") throw IOException("403") else Piece(from, 10, mutableListOf())
            },
            refresh = { "new" },
        )
        opener.openAt(0).close()
        opener.openAt(500).close()
        assertEquals(listOf("old", "new", "new"), tried)
        assertEquals("new", opener.url)
    }

    @Test
    fun `when nothing better comes back the first failure stands`() {
        for (refresh in listOf<(Long) -> String?>({ null }, { "same" }, { throw IOException("offline") })) {
            val opener = RefreshingOpener("same", open = { _, _ -> throw IOException("403") }, refresh = refresh)
            try {
                opener.openAt(0)
                fail("Opened a url that was refused")
            } catch (e: IOException) {
                assertEquals("403", e.message)
            }
        }
    }

    @Test
    fun `a new url refused as well is not tried again and again`() {
        var opens = 0
        var refreshes = 0
        val opener = RefreshingOpener(
            "a",
            open = { _, _ -> opens++; throw IOException("403") },
            refresh = { refreshes++; "b$refreshes" },
        )
        try {
            opener.openAt(0)
            fail("Opened")
        } catch (expected: IOException) {
        }
        assertEquals(2, opens)
        assertEquals(1, refreshes)
    }

    // FileCastSong

    @Test
    fun `a file is served from any byte`() {
        val file = File.createTempFile("cast", ".flac").apply { deleteOnExit() }
        file.writeBytes(song)
        val cast = FileCastSong(file, "audio/flac")
        assertEquals(10_000L, cast.length)
        assertArrayEquals(song, readAll(cast.openAt(0)))
        assertArrayEquals(song.copyOfRange(9_990, 10_000), readAll(cast.openAt(9_990)))
        assertEquals(-1, cast.openAt(10_000).use { it.read(ByteArray(10), 0, 10) })
        file.delete()
    }

    // CachedCopy

    @Test
    fun `the cache's own length comes first`() {
        assertEquals("audio/webm" to 5000L, CachedCopy.describe("audio/webm", "audio/webm; codecs=\"opus\"", 5000, 9999))
    }

    @Test
    fun `the row's length is taken when its type agrees with the bytes`() {
        assertEquals("audio/webm" to 9999L, CachedCopy.describe("audio/webm", "audio/webm; codecs=\"opus\"", -1, 9999))
        assertEquals("audio/mp4" to 9999L, CachedCopy.describe(null, "audio/mp4", -1, 9999))
    }

    @Test
    fun `a row that describes another encoding is not believed about the length`() {
        assertEquals("audio/webm" to -1L, CachedCopy.describe("audio/webm", "audio/mp4", -1, 9999))
    }

    @Test
    fun `with nothing known the copy is served whole as an unknown type`() {
        assertEquals(CastMime.UNKNOWN to -1L, CachedCopy.describe(null, null, -1, null))
        assertEquals("audio/mpeg" to -1L, CachedCopy.describe("audio/mpeg", null, -1, null))
        assertEquals("audio/mp4" to -1L, CachedCopy.describe(null, "audio/mp4", 0, 0))
    }

    // CastNetwork

    private fun addresses(vararg literals: String) = literals.map { InetAddress.getByName(it) }

    @Test
    fun `the Wi-Fi address picked is IPv4 first`() {
        assertEquals(
            InetAddress.getByName("192.168.1.20"),
            CastNetwork.pickAddress(addresses("fe80::1", "2a01:e0a::5", "192.168.1.20")),
        )
    }

    @Test
    fun `a unique local IPv6 address does when there is no IPv4`() {
        assertEquals(InetAddress.getByName("fd12:3456::5"), CastNetwork.pickAddress(addresses("fe80::1", "2a01:e0a::5", "fd12:3456::5")))
        assertEquals(InetAddress.getByName("192.168.1.20"), CastNetwork.pickAddress(addresses("fd12:3456::5", "192.168.1.20")))
    }

    @Test
    fun `a global IPv6 address is never picked, since the internet could reach it`() {
        assertNull(CastNetwork.pickAddress(addresses("fe80::1", "2a01:e0a::5")))
        assertNull(CastNetwork.pickAddress(addresses("2001:4860:4860::8888")))
    }

    @Test
    fun `loopback, link-local and wildcard addresses are never picked`() {
        assertNull(CastNetwork.pickAddress(addresses("127.0.0.1", "::1", "fe80::1", "169.254.3.4", "0.0.0.0", "224.0.0.1")))
        assertNull(CastNetwork.pickAddress(emptyList()))
    }
}
