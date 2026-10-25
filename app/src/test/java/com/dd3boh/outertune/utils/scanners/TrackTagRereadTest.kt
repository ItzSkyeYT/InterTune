/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import com.dd3boh.outertune.db.LocalSongFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time pass that gives local songs scanned before the fix for issue #39 their track
 * numbers. The library, the files and the marks kept across launches are stand-ins here.
 */
class TrackTagRereadTest {

    /** A song row: no track number is what the pass looks for. */
    private class Row(val id: String, val path: String, var track: Int?, var disc: Int? = null)

    private class Library(list: List<Row>) {
        val rows = list.associateBy { it.id }
        fun page(after: String, limit: Int) = rows.values
            .filter { (it.track ?: 0) <= 0 && it.id > after }
            .sortedBy { it.id }
            .take(limit)
            .map { LocalSongFile(it.id, it.path) }

        fun save(id: String, track: Int?, disc: Int?) {
            val row = rows.getValue(id)
            row.track = track ?: row.track
            row.disc = disc ?: row.disc
        }
    }

    private class Marks : TrackTagReread.Marks {
        var mark: String? = null
        var done = false
        override fun at() = mark
        override fun setAt(id: String) { mark = id }
        override suspend fun done() = done
        override suspend fun setDone() { done = true }
    }

    /** What the files say, as TagLib reads them. */
    private val files = mapOf(
        "/m/a.mp3" to Pair(1, 1),
        "/m/b.m4a" to Pair(2, null),
        "/m/c.mp3" to Pair(null, null),
        "/m/d.flac" to Pair(8, null),
    )

    private fun library() = Library(
        listOf(
            Row("LSa", "/m/a.mp3", null),
            Row("LSb", "/m/b.m4a", null),
            Row("LSc", "/m/c.mp3", null),
            Row("LSd", "/m/d.flac", 0),
            Row("LSkept", "/m/kept.mp3", 5),
        )
    )

    private fun pass(library: Library, marks: Marks, canRead: Boolean = true, read: (String) -> Pair<Int?, Int?>? = { files[it] }) =
        runBlocking { TrackTagReread.run(marks, canRead, library::page, read, library::save, pauseMs = 0) }

    @Test
    fun `songs with no track number get theirs from the file, and the rest are not read`() {
        val library = library()
        val read = mutableListOf<String>()
        val numbered = pass(library, Marks()) { read += it; files[it] }
        assertEquals(3, numbered)
        assertEquals(listOf("/m/a.mp3", "/m/b.m4a", "/m/c.mp3", "/m/d.flac"), read)
        assertEquals(1, library.rows.getValue("LSa").track)
        assertEquals(1, library.rows.getValue("LSa").disc)
        assertEquals(2, library.rows.getValue("LSb").track)
        assertNull(library.rows.getValue("LSc").track)
        assertEquals(8, library.rows.getValue("LSd").track)
        assertEquals(5, library.rows.getValue("LSkept").track)
    }

    @Test
    fun `it runs once`() {
        val library = library()
        val marks = Marks()
        pass(library, marks)
        assertTrue(marks.done)
        val read = mutableListOf<String>()
        assertEquals(0, pass(library, marks) { read += it; files[it] })
        assertEquals(emptyList<String>(), read)
    }

    @Test
    fun `stopped part way, the next launch carries on after the song it was reading`() {
        val library = library()
        val marks = Marks()
        // The app goes down while b is read: killed, or the file brought it down.
        runCatching { pass(library, marks) { if (it == "/m/b.m4a") throw Killed() else files[it] } }
        assertEquals("LSb", marks.mark)
        assertFalse(marks.done)
        assertEquals(1, library.rows.getValue("LSa").track)

        val read = mutableListOf<String>()
        pass(library, marks) { read += it; files[it] }
        // b is passed over, so a file that takes the app down cannot do it at every launch.
        assertEquals(listOf("/m/c.mp3", "/m/d.flac"), read)
        assertNull(library.rows.getValue("LSb").track)
        assertEquals(8, library.rows.getValue("LSd").track)
        assertTrue(marks.done)
    }

    @Test
    fun `while the files cannot be read it waits, unless no song needs it`() {
        val library = library()
        val marks = Marks()
        assertEquals(0, pass(library, marks, canRead = false) { error("nothing may be read") })
        assertFalse(marks.done)

        val nothingToDo = Library(listOf(Row("LSkept", "/m/kept.mp3", 5)))
        pass(nothingToDo, marks, canRead = false) { error("nothing may be read") }
        assertTrue(marks.done)
    }

    @Test
    fun `a file that cannot be read is passed over`() {
        val library = library()
        val marks = Marks()
        val numbered = pass(library, marks) { if (it == "/m/a.mp3") null else files[it] }
        assertEquals(2, numbered)
        assertNull(library.rows.getValue("LSa").track)
        assertTrue(marks.done)
    }

    @Test
    fun `a big library goes through page after page`() {
        val rows = (1..(TrackTagReread.PAGE * 2 + 7)).map { Row("LS%04d".format(it), "/m/$it.mp3", null) }
        val library = Library(rows)
        val numbered = pass(library, Marks()) { path -> Pair(path.substringAfter("/m/").substringBefore('.').toInt(), null) }
        assertEquals(rows.size, numbered)
        assertTrue(library.rows.values.all { it.track != null })
    }

    private class Killed : RuntimeException()
}
