/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.playback.downloadManager.DownloadCopies.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extra copies a name clash made in the download folder, and the one file each song keeps
 * (DownloadCopies). dvd's folder (23 Nov 2026): "4K CARPET [2YvDwY80qJl].mka" of 20 Nov, 1.12 MB, then
 * ".mka (1)" of 21 Nov to ".mka (5)" of that morning, 2.19 MB each, the same for each of his 397 liked
 * songs, 2,382 files.
 */
class DownloadCopiesTest {
    private val mb = 1024L * 1024
    private fun file(name: String, size: Long? = 2 * mb) = File(name, size, "content://folder/$name")

    private val carpet = "4K CARPET [2YvDwY80qJl]"
    private val saturday = "10_15 Saturday Night [xHg1rAhB_SE]"

    /** His folder as his file manager showed it, the two songs his picture has. */
    private val dvd = listOf(file("$carpet.mka", 1_174_405)) + (1..5).map { file("$carpet.mka ($it)", 2_296_381) } +
        listOf(file("$saturday.mka", 2_023_751)) + (1..5).map { file("$saturday.mka ($it)", 4_026_532) }

    @Test
    fun `his names are ours, with the song and the copy they are`() {
        assertEquals(DownloadCopies.Name(carpet, "2YvDwY80qJl", null), DownloadCopies.parse("$carpet.mka"))
        assertEquals(DownloadCopies.Name(carpet, "2YvDwY80qJl", 5), DownloadCopies.parse("$carpet.mka (5)"))
        assertEquals(DownloadCopies.Name(saturday, "xHg1rAhB_SE", 1), DownloadCopies.parse("$saturday.mka (1)"))
        // Made the 0.13 way, a part renamed onto a name already taken.
        assertEquals(DownloadCopies.Name(carpet, "2YvDwY80qJl", 2), DownloadCopies.parse("$carpet (2).mka"))
        // A title with brackets of its own, and a song with no title: " [id].mka".
        assertEquals("dQw4w9WgXcQ", DownloadCopies.parse("Song [Live] [dQw4w9WgXcQ].mka (1)")?.id)
        assertEquals(" [dQw4w9WgXcQ]", DownloadCopies.parse(" [dQw4w9WgXcQ].mka (3)")?.base)
        assertEquals("$carpet.mka", DownloadCopies.parse("$carpet.mka (4)")?.ownName)
    }

    @Test
    fun `no other file is ours`() {
        val others = listOf(
            "Notes.mka (1)", // no id
            "Song (1).mp3", // another app's
            "Song [abc].mka (1)", // a person's own, its brackets no YouTube id
            "Song [abc].mka",
            "Artist - Title.opus",
            "Artist - Title (1).opus",
            "Song [dQw4w9WgXcQ].MKA (1)", // not as the app writes it
            "Song [dQw4w9WgXcQ].mka (0)",
            "Song [dQw4w9WgXcQ].mka (33)", // past what the phone's storage numbers
            "Song [dQw4w9WgXcQ].mka(1)",
            "Song [dQw4w9WgXcQ].mka (1).intertune-part", // a part being written
            "Song [dQw4w9WgXcQ].mka.intertune-part",
            "Song [dQw4w9WgXcQ].opus",
            "Song [dQw4w9WgXcQ] (1).opus",
            "Song[dQw4w9WgXcQ].mka (1)",
            "Song [dQw4w9WgXcQ].mka (1) (1)",
        )
        for (name in others) assertNull(name, DownloadCopies.parse(name))
        assertEquals(32, DownloadCopies.parse("Song [dQw4w9WgXcQ].mka (32)")?.copy)
        val plan = DownloadCopies.plan(others.map { file(it) }, emptyMap())
        assertTrue(plan.songs.isEmpty())
    }

    @Test
    fun `his folder keeps the download of each song and every copy goes`() {
        val plan = DownloadCopies.plan(dvd, recorded = mapOf("2YvDwY80qJl" to "$carpet.mka"))
        assertEquals(listOf("2YvDwY80qJl", "xHg1rAhB_SE"), plan.songs.map { it.id })
        val carpetSong = plan.songs[0]
        assertEquals("$carpet.mka", carpetSong.keep.name)
        assertEquals((1..5).map { "$carpet.mka ($it)" }, carpetSong.remove.map { it.name })
        assertNull(carpetSong.renameTo)
        // The database had nothing of the second one: its download is kept all the same.
        val saturdaySong = plan.songs[1]
        assertEquals("$saturday.mka", saturdaySong.keep.name)
        assertEquals(5, saturdaySong.remove.size)
        assertEquals(10, plan.files)
        assertEquals(5 * 2_296_381L + 5 * 4_026_532L, plan.bytes)
    }

    @Test
    fun `the file the database has is the one kept, even a copy, and it takes the download's name`() {
        val plan = DownloadCopies.plan(dvd, recorded = mapOf("2YvDwY80qJl" to "$carpet.mka (5)"))
        val song = plan.songs.first { it.id == "2YvDwY80qJl" }
        assertEquals("$carpet.mka (5)", song.keep.name)
        // Its download goes too: the copy kept takes its name, one file left.
        assertEquals(listOf("$carpet.mka") + (1..4).map { "$carpet.mka ($it)" }, song.remove.map { it.name })
        assertEquals("$carpet.mka", song.renameTo)
        assertEquals(6, song.remove.size + 1)
    }

    @Test
    fun `a song left with copies only keeps the largest, the first made of equals, under the download's name`() {
        val copiesOnly = (1..3).map { file("$carpet.mka ($it)", 2_296_381) }
        val song = DownloadCopies.plan(copiesOnly, emptyMap()).songs.single()
        assertEquals("$carpet.mka (1)", song.keep.name)
        assertEquals(listOf("$carpet.mka (2)", "$carpet.mka (3)"), song.remove.map { it.name })
        assertEquals("$carpet.mka", song.renameTo)

        // One cut short, the app ended while it wrote it: never the one kept.
        val cut = listOf(file("$carpet.mka (1)", 300_000), file("$carpet.mka (2)", 2_296_381))
        assertEquals("$carpet.mka (2)", DownloadCopies.plan(cut, emptyMap()).songs.single().keep.name)
    }

    @Test
    fun `copies made the 0_13 way go as well, and a copy kept is renamed whichever way it was made`() {
        val files = listOf(file("$carpet.mka", 1_174_405), file("$carpet (1).mka"), file("$carpet (2).mka"))
        val song = DownloadCopies.plan(files, emptyMap()).songs.single()
        assertEquals("$carpet.mka", song.keep.name)
        assertEquals(listOf("$carpet (1).mka", "$carpet (2).mka"), song.remove.map { it.name })

        val only = DownloadCopies.plan(listOf(file("$carpet (1).mka")), emptyMap()).songs.single()
        assertEquals("$carpet (1).mka", only.keep.name)
        assertTrue(only.remove.isEmpty())
        assertEquals("$carpet.mka", only.renameTo)
    }

    @Test
    fun `a song with no copy is left as it is`() {
        val files = listOf(file("$carpet.mka"), file("Old title [2YvDwY80qJl].mka"), file("$saturday.mka"))
        assertTrue(DownloadCopies.plan(files, emptyMap()).songs.isEmpty())
    }

    @Test
    fun `an empty file is never the one kept`() {
        // The download empty, a write that never began: the largest copy is kept and takes its name.
        val files = listOf(file("$carpet.mka", 0), file("$carpet.mka (1)"), file("$carpet.mka (2)"))
        val song = DownloadCopies.plan(files, emptyMap()).songs.single()
        assertEquals("$carpet.mka (1)", song.keep.name)
        assertEquals(listOf("$carpet.mka", "$carpet.mka (2)"), song.remove.map { it.name })
        assertEquals("$carpet.mka", song.renameTo)
        // Not even when the database has it.
        assertEquals("$carpet.mka (1)", DownloadCopies.plan(files, mapOf("2YvDwY80qJl" to "$carpet.mka")).songs.single().keep.name)
        // Nothing but empty files: nothing of the song is touched.
        assertTrue(DownloadCopies.plan(listOf(file("$carpet.mka", 0), file("$carpet.mka (1)", 0)), emptyMap()).songs.isEmpty())
    }

    @Test
    fun `a download of another title is no copy and stays`() {
        val files = listOf(file("Old title [2YvDwY80qJl].mka"), file("$carpet.mka"), file("$carpet.mka (1)"))
        val song = DownloadCopies.plan(files, emptyMap()).songs.single()
        // The one the copy was made beside first.
        assertEquals("$carpet.mka", song.keep.name)
        assertEquals(listOf("$carpet.mka (1)"), song.remove.map { it.name })

        // The database has a copy of the new title: the old title's download stays all the same.
        val copyKept = DownloadCopies.plan(files, mapOf("2YvDwY80qJl" to "$carpet.mka (1)")).songs.single()
        assertEquals(listOf("$carpet.mka"), copyKept.remove.map { it.name })
        assertEquals("$carpet.mka", copyKept.renameTo)
    }

    @Test
    fun `a file the database has that is not one of them changes nothing`() {
        val song = DownloadCopies.plan(dvd, mapOf("2YvDwY80qJl" to "4K CARPET.opus")).songs.first()
        assertEquals("$carpet.mka", song.keep.name)
    }

    @Test
    fun `the database ends pointing at the file kept, unless it has a file of its own elsewhere`() {
        val music = "/storage/emulated/0/Music/InterTune"
        val kept = "$music/$carpet.mka"
        // It had nothing: the file kept is the song's download from now on.
        assertTrue(DownloadCopies.repoints("2YvDwY80qJl", null, kept, music))
        // It had a copy that went, or the copy that was renamed.
        assertTrue(DownloadCopies.repoints("2YvDwY80qJl", "$music/$carpet.mka (5)", kept, music))
        assertTrue(DownloadCopies.repoints("2YvDwY80qJl", "$music/$carpet (2).mka", kept, music))
        // It has that very file already.
        assertEquals(false, DownloadCopies.repoints("2YvDwY80qJl", kept, kept, music))
        // A song file, another folder's file, or another song's: left as it is.
        assertEquals(false, DownloadCopies.repoints("2YvDwY80qJl", "$music/4K CARPET.opus", kept, music))
        assertEquals(false, DownloadCopies.repoints("2YvDwY80qJl", "/storage/emulated/0/Download/$carpet.mka (1)", kept, music))
        assertEquals(false, DownloadCopies.repoints("2YvDwY80qJl", "$music/$saturday.mka (1)", kept, music))
        assertEquals(false, DownloadCopies.repoints("2YvDwY80qJl", null, null, music))
    }

    /** A folder that cannot be listed, asked one name at a time: the names it was asked, in order. */
    private class Folder(names: List<String>) {
        val files = names.associateWith { File(it, 2_000_000, "content://folder/$it") }
        val asked = mutableListOf<String>()
        fun find(name: String): File? = files[name].also { asked += name }
    }

    @Test
    fun `a folder that lists as empty gives up its copies one name at a time`() {
        val folder = Folder(listOf("$carpet.mka") + (1..5).map { "$carpet.mka ($it)" } + "Unrelated.mp3")
        val found = DownloadCopies.lookFor(listOf(carpet), emptyList(), folder::find)
        assertEquals(listOf("$carpet.mka") + (1..5).map { "$carpet.mka ($it)" }, found.map { it.name })
        // Its download, five copies and three misses after them, three misses the other way.
        assertEquals(1 + 5 + 3 + 3, folder.asked.size)
        assertTrue(folder.asked.none { it == "Unrelated.mp3" })
    }

    @Test
    fun `a few copies deleted by hand leave a gap the look goes past`() {
        val folder = Folder(listOf("$carpet.mka (1)", "$carpet.mka (4)", "$carpet (2).mka"))
        val found = DownloadCopies.lookFor(listOf(carpet), emptyList(), folder::find).map { it.name }
        assertEquals(listOf("$carpet.mka (1)", "$carpet.mka (4)", "$carpet (2).mka"), found)
        assertTrue("never past the 32nd", folder.asked.size <= 1 + 2 * DownloadCopies.MOST_COPIES)
    }

    @Test
    fun `the file the database has is looked at by its name, wherever its number is`() {
        val folder = Folder(listOf("$carpet.mka (9)", "Artist - Song.opus"))
        val found = DownloadCopies.lookFor(listOf(carpet), listOf("$carpet.mka (9)", "Artist - Song.opus"), folder::find)
        // The song file is not ours to touch: asked, never kept.
        assertEquals(listOf("$carpet.mka (9)"), found.map { it.name })
        // Each name is asked once.
        assertEquals(folder.asked.size, folder.asked.toSet().size)
    }
}
