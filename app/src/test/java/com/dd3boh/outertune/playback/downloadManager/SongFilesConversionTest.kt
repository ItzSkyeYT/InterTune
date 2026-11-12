/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * What a song is saved as when it may be converted first (Unreleased.DOWNLOAD_FORMATS,
 * SongFiles.saveStream): the converted file is whole before the folder is asked for a file, a
 * conversion that fails or is refused by the folder saves the song as Original, an export is not
 * handed a format it did not ask for, a stop is not a failure, and the temporary file is gone in
 * every case. The folder is a function that records what it was given.
 */
class SongFilesConversionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tags = SongTags("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"))
    private val stream = SongFiles.Stream.Opus(ByteArray(19), packets = 10, trim = 0, payloadBytes = 16_200L, samples = 48_000L)
    private val source = CachedBytes(emptyList())
    private val original = byteArrayOf(1, 2, 3)

    /** What the folder was given: a name and the bytes written, or nothing. */
    private class Folder(val refuses: Boolean = false) {
        val saved = mutableListOf<Pair<String, ByteArray>>()

        fun save(name: String, write: (OutputStream) -> Unit): String? {
            if (refuses) throw IOException("the folder is full")
            val out = java.io.ByteArrayOutputStream()
            write(out)
            saved += name to out.toByteArray()
            return name
        }
    }

    private fun converted(bytes: ByteArray = byteArrayOf(9, 9, 9, 9), extension: String = "mp3"): Converted =
        Converted(folder.newFile("convert-${System.nanoTime()}.part").also { it.writeBytes(bytes) }, extension, 256, 1000)

    private fun converter(result: () -> Converted?) = object : SongFiles.Converter {
        var asked = 0
        val failures = mutableListOf<String?>()
        override fun convert(source: CachedBytes, stream: SongFiles.Stream, tags: SongTags): Converted? {
            asked++
            return result()
        }

        override fun failed(e: Exception) {
            failures += e.message
        }
    }

    private fun run(convert: SongFiles.Converter?, to: Folder, fallBack: Boolean = true): String? =
        SongFiles.saveStream(source, stream, tags, convert, fallBack, to::save) { out -> out.write(original) }

    @Test
    fun `a converted song goes into the folder whole, under its own extension, and the temporary file goes`() {
        val made = converted()
        val to = Folder()
        val name = run(converter { made }, to)
        assertEquals("Rick Astley - Never Gonna Give You Up.mp3", name)
        assertEquals(1, to.saved.size)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), to.saved.single().second)
        assertFalse("the temporary file is gone", made.file.exists())
    }

    @Test
    fun `without a converter, or when it says to keep the stream, the song is saved as Original`() {
        val to = Folder()
        assertEquals("Rick Astley - Never Gonna Give You Up.opus", run(null, to))
        assertArrayEquals(original, to.saved.single().second)
        val asIs = converter { null }
        val second = Folder()
        assertEquals("Rick Astley - Never Gonna Give You Up.opus", run(asIs, second))
        assertEquals(1, asIs.asked)
        assertArrayEquals(original, second.saved.single().second)
    }

    @Test
    fun `a conversion that fails leaves the folder nothing but the Original, and a download is saved as that`() {
        val to = Folder()
        val failing = converter { throw IOException("the decoder stopped") }
        val name = run(failing, to)
        assertEquals("Rick Astley - Never Gonna Give You Up.opus", name)
        assertEquals(1, to.saved.size)
        assertArrayEquals(original, to.saved.single().second)
        assertEquals("the conversion is told why, for the notice", listOf<String?>("the decoder stopped"), failing.failures)
    }

    @Test
    fun `an export is not handed another format than the one it asked for`() {
        val to = Folder()
        assertNull(run(converter { throw IOException("the decoder stopped") }, to, fallBack = false))
        assertTrue("nothing was written", to.saved.isEmpty())
    }

    @Test
    fun `a folder that refuses the converted file is not the end of the song`() {
        val made = converted()
        val refusing = Folder(refuses = true)
        // The folder refuses every file, so the Original is refused too and the failure comes out; the point is the order and the clean-up.
        val failure = runCatching { run(converter { made }, refusing) }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        assertFalse("the temporary file is gone", made.file.exists())
    }

    @Test
    fun `a folder that takes the Original but not the converted file saves the song as Original`() {
        val made = converted()
        var calls = 0
        val refused = converter { made }
        val name = SongFiles.saveStream(source, stream, tags, refused, true, { n, write ->
            if (++calls == 1) throw IOException("the folder is full") else n.also { write(java.io.ByteArrayOutputStream()) }
        }) { out -> out.write(original) }
        assertEquals("Rick Astley - Never Gonna Give You Up.opus", name)
        assertEquals(2, calls)
        assertFalse(made.file.exists())
        assertEquals("a folder that refuses the converted file counts as a song that was not converted", listOf<String?>("the folder is full"), refused.failures)
    }

    @Test
    fun `an export whose folder refuses the converted file is not saved as Original either`() {
        val made = converted()
        var calls = 0
        val name = SongFiles.saveStream(source, stream, tags, converter { made }, false, { _, _ ->
            calls++
            throw IOException("the folder is full")
        }) { out -> out.write(original) }
        assertNull(name)
        assertEquals("only the converted file was offered to the folder", 1, calls)
        assertFalse(made.file.exists())
    }

    @Test
    fun `a stop is thrown on, not taken for a failure and saved as Original`() {
        val to = Folder()
        val failure = runCatching { run(converter { throw CancellationException("stopped") }, to) }.exceptionOrNull()
        assertTrue("$failure", failure is CancellationException)
        assertTrue("nothing was written", to.saved.isEmpty())
    }
}
