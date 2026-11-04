/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * A song file in a download folder is named "Artist - Title", so the app knows which song it is from
 * its tags (SongFileIds), reading as little of it as it can.
 */
class SongFileIdsTest {
    private val tags = SongTags("dQw4w9WgXcQ", "Song", listOf("Rick Astley"))

    private fun ogg(comments: List<String>, coverBytes: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val withCover = if (coverBytes > 0) comments + ("METADATA_BLOCK_PICTURE=" + base64(Random(1).nextBytes(coverBytes))) else comments
        OggOpusWriter(out, 9).apply {
            writeHeaders(OggOpusWriter.opusHead(2, 312), OggOpusWriter.opusTags("InterTune", withCover))
            repeat(200) { writeAudio(byteArrayOf((31 shl 3).toByte(), 1, 2, 3), 960) }
            finish()
        }
        return out.toByteArray()
    }

    /** Counts what was read, to show how little it is. */
    private class Counted(input: InputStream) : FilterInputStream(input) {
        var read = 0L
        override fun read(): Int = super.read().also { if (it >= 0) read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
    }

    @Test
    fun `an opus file's id is its link, read before the cover`() {
        val file = ogg(tags.copy(cover = null).vorbisComments(), coverBytes = 300_000)
        val counted = Counted(file.inputStream())
        assertEquals("dQw4w9WgXcQ", SongFileIds.read("Rick Astley - Song.opus", counted))
        assertTrue("read ${counted.read} of ${file.size} bytes", counted.read < 70_000)
    }

    @Test
    fun `a yt-dlp download keeps its link in PURL, and that is read too`() {
        val file = ogg(listOf("title=Song", "purl=https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", SongFileIds.read("Song.opus", file.inputStream()))
    }

    @Test
    fun `a file with no link is not a download`() {
        assertNull(SongFileIds.read("Song.opus", ogg(listOf("TITLE=Song", "COMMENT=lovely"), coverBytes = 1000).inputStream()))
        assertNull(SongFileIds.read("Song.opus", ByteArray(5000) { 7 }.inputStream()))
        assertNull(SongFileIds.read("Song.opus", ByteArray(0).inputStream()))
        assertNull(SongFileIds.read("Song.m4a", ByteArray(5000) { 7 }.inputStream()))
        assertNull("another extension is never read", SongFileIds.read("Song.mka", ogg(tags.vorbisComments()).inputStream()))
    }

    @Test
    fun `an m4a's id is read with the audio skipped, wherever its index is`() {
        val sizes = IntArray(2000) { 400 }
        val track = M4aTrack(byteArrayOf(0x12, 0x10), 44_100, 2, sizes, IntArray(2000) { 1024 })
        val out = ByteArrayOutputStream()
        M4aWriter.write(out, track, tags) { o -> repeat(2000) { o.write(ByteArray(400)) } }
        val file = out.toByteArray()
        val counted = Counted(file.inputStream())
        assertEquals("dQw4w9WgXcQ", SongFileIds.read("Rick Astley - Song.m4a", counted))
        assertTrue("read ${counted.read} of ${file.size}", counted.read < 20_000)

        // An index after the audio, as many writers put it: the audio is skipped over.
        val ftypSize = ByteBuffer.wrap(file, 0, 4).int
        val moovSize = ByteBuffer.wrap(file, ftypSize, 4).int
        val moov = file.copyOfRange(ftypSize, ftypSize + moovSize)
        val mdat = file.copyOfRange(ftypSize + moovSize, file.size)
        val late = file.copyOfRange(0, ftypSize) + mdat + moov
        val skipped = Counted(late.inputStream())
        assertEquals("dQw4w9WgXcQ", SongFileIds.read("x.m4a", skipped))
        assertTrue("read ${skipped.read} of ${late.size}", skipped.read < 20_000)
    }

    @Test
    fun `an m4a whose meta box is QuickTime's, with no version, is read as well`() {
        fun box(type: String, body: ByteArray): ByteArray =
            ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
        val data = box("data", ByteArray(8) + "https://music.youtube.com/watch?v=dQw4w9WgXcQ".toByteArray())
        val ilst = box("ilst", box("©nam", box("data", ByteArray(8) + "Song".toByteArray())) + box("©cmt", data))
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "mdir".toByteArray() + ByteArray(13))
        val file = box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + box("moov", box("udta", box("meta", hdlr + ilst)))
        assertEquals("dQw4w9WgXcQ", SongFileIds.read("x.m4a", file.inputStream()))
    }
}
