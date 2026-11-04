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
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.random.Random

/**
 * What a download saved as a song file says inside, and what it is called (Unreleased.DOWNLOAD_FILES).
 */
class SongTagsTest {
    private val song = SongTags(
        videoId = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artists = listOf("Rick Astley"),
        album = "Whenever You Need Somebody",
        year = 1987,
        track = 1,
    )

    @Test
    fun `a song file is called Artist - Title`() {
        assertEquals("Rick Astley - Never Gonna Give You Up.opus", SongFileNames.of(song, "opus"))
        assertEquals("Rick Astley - Never Gonna Give You Up.m4a", SongFileNames.of(song, "m4a"))
    }

    @Test
    fun `every artist is named, the way the app shows them`() {
        val two = song.copy(artists = listOf("Daft Punk", " Pharrell Williams ", ""))
        assertEquals("Daft Punk, Pharrell Williams", two.artist)
        assertEquals("Daft Punk, Pharrell Williams - Never Gonna Give You Up.opus", SongFileNames.of(two, "opus"))
    }

    @Test
    fun `no artist, or a title that names it already, gives the title alone`() {
        assertEquals("Never Gonna Give You Up.opus", SongFileNames.of(song.copy(artists = emptyList()), "opus"))
        val video = song.copy(title = "Rick Astley - Never Gonna Give You Up (Official Video)")
        assertEquals("Rick Astley - Never Gonna Give You Up (Official Video).opus", SongFileNames.of(video, "opus"))
    }

    @Test
    fun `a name every file system takes`() {
        val awkward = song.copy(title = "...Why? / Part 2: \"Live\" <Remix> *|*\t", artists = listOf("AC/DC"))
        assertEquals("AC-DC - Why - Part 2 - 'Live' Remix -.opus", SongFileNames.of(awkward, "opus"))
        assertEquals("dQw4w9WgXcQ.opus", SongFileNames.of(song.copy(title = "???", artists = emptyList()), "opus"))
        val name = SongFileNames.of(song.copy(title = "Ünïcödé ".repeat(60) + "😀".repeat(40)), "m4a")
        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name, name.endsWith(".m4a") && !name.contains("�"))
        // Cut between characters, never inside one.
        assertTrue(name.dropLast(4).toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == name.dropLast(4))
    }

    @Test
    fun `the comments carry the tags, then the link, then the cover`() {
        val comments = song.copy(cover = cover("jpg")).vorbisComments()
        assertEquals("TITLE=Never Gonna Give You Up", comments[0])
        assertEquals("ARTIST=Rick Astley", comments[1])
        assertEquals("ALBUM=Whenever You Need Somebody", comments[2])
        assertEquals("DATE=1987", comments[3])
        assertEquals("the media store's own", "YEAR=1987", comments[4])
        assertEquals("TRACKNUMBER=1", comments[5])
        assertEquals("COMMENT=https://music.youtube.com/watch?v=dQw4w9WgXcQ", comments[6])
        assertTrue(comments[7].startsWith("METADATA_BLOCK_PICTURE="))
        assertEquals(8, comments.size)
        val bare = SongTags("dQw4w9WgXcQ", "Song").vorbisComments()
        assertEquals(listOf("TITLE=Song", "COMMENT=https://music.youtube.com/watch?v=dQw4w9WgXcQ"), bare)
    }

    @Test
    fun `the picture block is the FLAC one, front cover, its size read from the image`() {
        val jpeg = cover("jpg")
        assertEquals("image/jpeg", jpeg.mimeType)
        assertEquals(64, jpeg.width)
        assertEquals(48, jpeg.height)
        assertEquals(24, jpeg.depth)
        val block = ByteBuffer.wrap(jpeg.flacPictureBlock())
        assertEquals(3, block.int)
        val mime = ByteArray(block.int).also { block.get(it) }
        assertEquals("image/jpeg", String(mime))
        assertEquals(0, block.int)
        assertEquals(64, block.int)
        assertEquals(48, block.int)
        assertEquals(24, block.int)
        assertEquals(0, block.int)
        val data = ByteArray(block.int).also { block.get(it) }
        assertArrayEquals(jpeg.bytes, data)
        val png = cover("png")
        assertEquals("image/png", png.mimeType)
        assertEquals(64, png.width)
        assertEquals(48, png.height)
        assertNull("WebP and the rest are made JPEG first", Cover.of("RIFF....WEBPVP8 ".toByteArray()))
    }

    @Test
    fun `base64 is the standard one`() {
        val random = Random(7)
        for (size in listOf(0, 1, 2, 3, 4, 5, 255, 1000, 4097)) {
            val bytes = random.nextBytes(size)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), base64(bytes))
        }
    }

    @Test
    fun `the id is read back from a link, and from a name the old way`() {
        assertEquals("dQw4w9WgXcQ", SongFileNames.videoIdIn(song.link))
        assertEquals("dQw4w9WgXcQ", SongFileNames.videoIdIn("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
        assertEquals("dQw4w9WgXcQ", SongFileNames.videoIdIn("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(SongFileNames.videoIdIn("https://music.youtube.com/watch?v=short"))
        assertNull(SongFileNames.videoIdIn("Recorded live in 1987"))
        assertEquals("dQw4w9WgXcQ", SongFileNames.idInName("Never Gonna Give You Up [dQw4w9WgXcQ].opus"))
        assertNull("a title in brackets is no id", SongFileNames.idInName("Rick Astley - Song [Live].opus"))
        assertTrue(SongFileNames.isSongFile("A - B.OPUS"))
        assertTrue(SongFileNames.isSongFile("A - B.m4a"))
        assertTrue(!SongFileNames.isSongFile("Song [dQw4w9WgXcQ].mka"))
    }

    private fun cover(format: String): Cover = Cover.of(if (format == "png") png(64, 48) else jpeg(64, 48))!!

    /** A JPEG's start as encoders write it: SOI, a JFIF APP0, a quantisation table, then the frame header. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort(0xFFD8)
            writeShort(0xFFE0); writeShort(16); write("JFIF".toByteArray()); writeByte(0); writeShort(0x0101); writeByte(0)
            writeShort(1); writeShort(1); writeByte(0); writeByte(0)
            writeShort(0xFFDB); writeShort(67); writeByte(0); write(ByteArray(64) { 1 })
            writeShort(0xFFC0); writeShort(17); writeByte(8); writeShort(height); writeShort(width); writeByte(3)
            for (c in 1..3) { writeByte(c); writeByte(0x11); writeByte(0) }
            writeShort(0xFFD9)
        }
        return out.toByteArray()
    }

    /** A real PNG, RGB, 8 bits a channel. */
    private fun png(width: Int, height: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        repeat(height) { y -> raw.write(0); repeat(width) { x -> raw.write(x * 4); raw.write(y * 5); raw.write(0x40) } }
        val deflater = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val buffer = ByteArray(64 * 1024)
        val packed = buffer.copyOf(deflater.deflate(buffer))
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", packed)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
