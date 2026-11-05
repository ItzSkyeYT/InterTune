/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * AAC frames put into a plain .m4a (M4aWriter): the index first, every frame where the index says,
 * and the tags where iTunes, Android and TagLib read them.
 */
class M4aWriterTest {
    private val asc = byteArrayOf(0x12, 0x10) // AAC LC, 44.1 kHz, stereo
    private val tags = SongTags("dQw4w9WgXcQ", "Été", listOf("Rick Astley"), "Album", 1987, 3, null)

    private fun frames(count: Int, seed: Int = 1): List<ByteArray> {
        val random = Random(seed)
        return List(count) { random.nextBytes(100 + random.nextInt(500)) }
    }

    private fun written(frames: List<ByteArray>, priming: Long = 0, tags: SongTags = this.tags): ByteArray {
        val track = M4aTrack(asc, 44_100, 2, frames.map { it.size }.toIntArray(), IntArray(frames.size) { 1024 }, priming)
        val out = ByteArrayOutputStream()
        M4aWriter.write(out, track, tags) { o -> frames.forEach(o::write) }
        return out.toByteArray()
    }

    @Test
    fun `the index comes first and every frame is where it says`() {
        val frames = frames(100)
        val file = Mp4Read(written(frames))
        assertEquals(listOf("ftyp", "moov", "mdat"), file.top.map { it.type })
        assertEquals("M4A ", String(file.top[0].body, 0, 4, Charsets.ISO_8859_1))
        val stbl = file.path("moov", "trak", "mdia", "minf", "stbl")
        val sizes = stbl.child("stsz").let { b -> ByteBuffer.wrap(b.body, 12, b.body.size - 12).let { buf -> IntArray(frames.size) { buf.int } } }
        assertArrayEquals(frames.map { it.size }.toIntArray(), sizes)
        // 43 frames to a chunk: chunks of 43, 43 and 14.
        val stsc = ByteBuffer.wrap(stbl.child("stsc").body, 4, stbl.child("stsc").body.size - 4)
        assertEquals(2, stsc.int)
        assertEquals(listOf(1, 43, 1, 3, 14, 1), List(6) { stsc.int })
        val stco = ByteBuffer.wrap(stbl.child("stco").body, 4, stbl.child("stco").body.size - 4)
        val offsets = IntArray(stco.int) { stco.int }
        assertEquals(3, offsets.size)
        val bytes = file.bytes
        var frame = 0
        for (chunk in offsets.indices) {
            var at = offsets[chunk]
            repeat(if (chunk < 2) 43 else 14) {
                assertArrayEquals("frame $frame", frames[frame], bytes.copyOfRange(at, at + frames[frame].size))
                at += frames[frame].size
                frame++
            }
        }
        val stts = ByteBuffer.wrap(stbl.child("stts").body, 4, stbl.child("stts").body.size - 4)
        assertEquals(listOf(1, 100, 1024), List(3) { stts.int })
        val mdhd = ByteBuffer.wrap(file.path("moov", "trak", "mdia", "mdhd").body, 12, 8)
        assertEquals(44_100, mdhd.int)
        assertEquals(102_400, mdhd.int)
        assertNull("no edit list without priming", file.path("moov", "trak").children.find { it.type == "edts" })
    }

    @Test
    fun `the decoder's config and the channels are the stream's`() {
        val mp4a = Mp4Read(written(frames(5))).path("moov", "trak", "mdia", "minf", "stbl", "stsd")
        val entry = Mp4Read.boxes(mp4a.body, 8).single()
        assertEquals("mp4a", entry.type)
        val body = ByteBuffer.wrap(entry.body)
        body.position(16)
        assertEquals(2, body.short.toInt())
        assertEquals(16, body.short.toInt())
        body.position(24)
        assertEquals(44_100, body.int ushr 16)
        val esds = Mp4Read.boxes(entry.body, 28).single()
        assertEquals("esds", esds.type)
        // The DecoderSpecificInfo (tag 5) holds the AudioSpecificConfig as it came.
        val es = esds.body
        val at = (0 until es.size - 6).first { es[it] == 0x05.toByte() && es[it + 1] == 0x80.toByte() }
        assertEquals(2, es[at + 4].toInt())
        assertArrayEquals(asc, es.copyOfRange(at + 5, at + 7))
        assertEquals("audio object type 0x40", 0x40, es[(0 until es.size).first { es[it] == 0x04.toByte() } + 5].toInt())
    }

    @Test
    fun `priming goes into an edit list that skips it`() {
        val file = Mp4Read(written(frames(50), priming = 2112))
        val elst = file.path("moov", "trak", "edts", "elst")
        val b = ByteBuffer.wrap(elst.body, 4, 16)
        assertEquals(1, b.int)
        assertEquals((50 * 1024 - 2112) * 1000L / 44_100, b.int.toLong())
        assertEquals(2112, b.int)
        assertEquals(1, b.short.toInt())
    }

    @Test
    fun `the tags are iTunes', where every reader looks`() {
        val cover = Cover(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3), "image/jpeg", 1, 1, 24)
        val ilst = Mp4Read(written(frames(3), tags = tags.copy(cover = cover))).path("moov", "udta", "meta", "ilst")
        fun value(name: String): Pair<Int, ByteArray> {
            val data = ilst.children.single { it.type == name }.children.single()
            assertEquals("data", data.type)
            val b = ByteBuffer.wrap(data.body)
            return b.int to data.body.copyOfRange(8, data.body.size)
        }
        assertEquals(1 to "Été", value("©nam").let { it.first to String(it.second) })
        assertEquals(1 to "Rick Astley", value("©ART").let { it.first to String(it.second) })
        assertEquals(1 to "Album", value("©alb").let { it.first to String(it.second) })
        assertEquals(1 to "1987", value("©day").let { it.first to String(it.second) })
        assertEquals(1 to "https://music.youtube.com/watch?v=dQw4w9WgXcQ", value("©cmt").let { it.first to String(it.second) })
        val trkn = value("trkn")
        assertEquals(0, trkn.first)
        assertEquals(listOf<Byte>(0, 0, 0, 3, 0, 0, 0, 0), trkn.second.toList())
        val covr = value("covr")
        assertEquals(13, covr.first)
        assertArrayEquals(cover.bytes, covr.second)
        val meta = Mp4Read(written(frames(3))).path("moov", "udta").children.single()
        // The meta box an iTunes reader wants: versioned, with its mdir handler first.
        assertEquals(0, ByteBuffer.wrap(meta.body).int)
        assertEquals("mdir", String(Mp4Read.boxes(meta.body, 4).first().body, 8, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `frames that do not match the index throw rather than leave a broken file`() {
        val frames = frames(10)
        val track = M4aTrack(asc, 44_100, 2, frames.map { it.size }.toIntArray(), IntArray(10) { 1024 })
        try {
            M4aWriter.write(ByteArrayOutputStream(), track, tags) { o -> frames.drop(1).forEach(o::write) }
            fail("one frame short")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun `the id is read back from the file's comment`() {
        assertEquals("dQw4w9WgXcQ", SongFileIds.read("Rick Astley - Été.m4a", written(frames(200)).inputStream()))
    }

    /** An MP4 reader of the test's own. */
    class Mp4Read(val bytes: ByteArray) {
        class Box(val type: String, val body: ByteArray, val bodyStart: Int) {
            val children: List<Box> by lazy {
                when (type) {
                    "meta" -> boxes(body, 4)
                    else -> boxes(body, 0)
                }
            }

            fun child(type: String) = children.single { it.type == type }
        }

        val top = boxes(bytes, 0)

        fun path(vararg types: String): Box {
            var box = top.single { it.type == types[0] }
            for (t in types.drop(1)) box = box.child(t)
            return box
        }

        companion object {
            fun boxes(b: ByteArray, from: Int): List<Box> {
                val list = ArrayList<Box>()
                var at = from
                while (at + 8 <= b.size) {
                    val size = ByteBuffer.wrap(b, at, 4).int
                    if (size < 8 || at + size > b.size) return list.also { if (list.isEmpty()) assertTrue("box at $at of $size", false) }
                    list += Box(String(b, at + 4, 4, Charsets.ISO_8859_1), b.copyOfRange(at + 8, at + size), at + 8)
                    at += size
                }
                return list
            }
        }
    }
}
