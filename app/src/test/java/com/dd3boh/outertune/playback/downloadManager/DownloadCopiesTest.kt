/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.playback.downloadManager.DownloadCopies.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

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

    /**
     * The phone's own storage asked by name, as a download folder that lists as empty is
     * (DownloadDirectoryManagerOt.lookUp, lookAt, removeCopy): a name is found whatever its letter
     * case and either way of writing an accent, but given back under the name asked, not the one on
     * disk (FileSystemProvider.queryDocument names the path it was asked), and a delete by that
     * address deletes the file on disk (deleteDocument, File.delete). [disk]: its files, name to size.
     */
    private class PhoneStorage(files: Map<String, Long>) {
        val disk = LinkedHashMap(files)
        private fun same(a: String, b: String) =
            Normalizer.normalize(a, Normalizer.Form.NFD).equals(Normalizer.normalize(b, Normalizer.Form.NFD), ignoreCase = true)
        private fun onDisk(name: String) = disk.keys.firstOrNull { same(it, name) }
        private fun key(name: String) = "content://storage/document/primary:Music/InterTune/$name"
        fun lookUp(name: String): File? = onDisk(name)?.let { File(name, disk.getValue(it), key(name)) }
        fun lookAt(key: String): File? = lookUp(key.substringAfterLast('/'))
        fun delete(key: String): Boolean = onDisk(key.substringAfterLast('/'))?.let { disk.remove(it) } != null
    }

    /** His song's title put right with Edit song since its files were written, which leaves their names as they were. */
    private val carpetEdited = "4K Carpet [2YvDwY80qJl]"

    @Test
    fun `a title put right in letter case never has a file found twice, nor the file kept removed`() {
        val storage = PhoneStorage(mapOf("$carpet.mka" to 1_174_405L) + (1..5).associate { "$carpet.mka ($it)" to 2_296_381L })
        val recorded = mapOf("2YvDwY80qJl" to "$carpet.mka (5)")
        // Asked as DownloadCopiesCleanup asks: the bases of the titles the database has, then of the files it has.
        val found = DownloadCopies.lookFor(listOf(fatName(carpetEdited), carpet), recorded.values, storage::lookUp)
        // Each file once, under the name it has on the phone.
        assertEquals(listOf("$carpet.mka (5)", "$carpet.mka") + (1..4).map { "$carpet.mka ($it)" }, found.map { it.name })
        val plan = DownloadCopies.plan(found, recorded)
        assertEquals(5, plan.files)
        val song = plan.songs.single()
        assertEquals("$carpet.mka (5)", song.keep.name)
        assertEquals(listOf("$carpet.mka") + (1..4).map { "$carpet.mka ($it)" }, song.remove.map { it.name })
        assertEquals("$carpet.mka", song.renameTo)
        // Removed the way the card's Remove removes: the file kept is all that is left, whole.
        val gone = DownloadCopies.remove(song, storage::lookAt, storage::delete) {}
        assertEquals(DownloadCopies.Removal(5, 1_174_405L + 4 * 2_296_381L, allGone = true), gone)
        assertEquals(mapOf("$carpet.mka (5)" to 2_296_381L), storage.disk)
    }

    @Test
    fun `nor when only copies are whole and the largest is kept`() {
        // Its download empty, a write that never began, and its title put right since.
        val storage = PhoneStorage(mapOf("$carpet.mka" to 0L) + (1..3).associate { "$carpet.mka ($it)" to 2_296_381L })
        val recorded = mapOf("2YvDwY80qJl" to "$carpet.mka")
        val found = DownloadCopies.lookFor(listOf(fatName(carpetEdited), carpet), recorded.values, storage::lookUp)
        assertEquals(listOf("$carpet.mka") + (1..3).map { "$carpet.mka ($it)" }, found.map { it.name })
        val song = DownloadCopies.plan(found, recorded).songs.single()
        assertEquals("$carpet.mka (1)", song.keep.name)
        assertEquals(listOf("$carpet.mka", "$carpet.mka (2)", "$carpet.mka (3)"), song.remove.map { it.name })
        assertEquals("$carpet.mka", song.renameTo)
        assertEquals(DownloadCopies.Removal(3, 2 * 2_296_381L, allGone = true), DownloadCopies.remove(song, storage::lookAt, storage::delete) {})
        assertEquals(mapOf("$carpet.mka (1)" to 2_296_381L), storage.disk)
    }

    @Test
    fun `the removal never deletes the file kept, not under another name the storage has for it either`() {
        val storage = PhoneStorage(mapOf("$carpet.mka" to 1_174_405L) + (1..5).associate { "$carpet.mka ($it)" to 2_296_381L })
        // The plan the look made of his folder before: the kept file's other name among the copies.
        val keep = storage.lookUp("$carpet.mka (5)")!!
        val remove = listOf(storage.lookUp("$carpetEdited.mka (5)")!!, keep) + (1..4).map { storage.lookUp("$carpet.mka ($it)")!! }
        var steps = 0
        val gone = DownloadCopies.remove(DownloadCopies.Song("2YvDwY80qJl", keep, remove, "$carpet.mka"), storage::lookAt, storage::delete) { steps++ }
        // The other copies go; not every one did, so the one kept is not named over its download.
        assertEquals(DownloadCopies.Removal(4, 4 * 2_296_381L, allGone = false), gone)
        assertEquals(6, steps)
        assertEquals(setOf("$carpet.mka", "$carpet.mka (5)"), storage.disk.keys)
    }

    @Test
    fun `a delete that takes the file kept all the same stops the removal, and the song's other copies stay`() {
        // A storage that matches names in some way the app does not know: the first delete takes the file kept too.
        val storage = PhoneStorage((1..3).associate { "$carpet.mka ($it)" to 2_296_381L } + ("$carpet.mka (5)" to 2_296_381L))
        val keep = storage.lookUp("$carpet.mka (5)")!!
        val song = DownloadCopies.Song("2YvDwY80qJl", keep, (1..3).map { storage.lookUp("$carpet.mka ($it)")!! }, "$carpet.mka")
        val delete = { key: String -> storage.delete(key).also { storage.disk.remove(keep.name) } }
        var steps = 0
        val gone = DownloadCopies.remove(song, storage::lookAt, delete) { steps++ }
        assertEquals(DownloadCopies.Removal(1, 2_296_381L, allGone = false, keptLost = true), gone)
        assertEquals(3, steps)
        // The highest number went first.
        assertEquals(setOf("$carpet.mka (1)", "$carpet.mka (2)"), storage.disk.keys)
    }

    @Test
    fun `a Remove the app ends leaves the copies that a folder listing as empty finds again`() {
        // His folder, the database at the newest copy, the removal ended after a few deletions, and the next start
        // asks the folder for each name again. Deleted from (1) up, three missing in a row hid the copies left past
        // them from every look after it: they stayed for good, and no card ever said so (27 Nov 2026).
        for (deletions in 0..5) {
            val storage = PhoneStorage(mapOf("$carpet.mka" to 1_174_405L) + (1..5).associate { "$carpet.mka ($it)" to 2_296_381L })
            val recorded = mapOf("2YvDwY80qJl" to "$carpet.mka (5)")
            val song = DownloadCopies.plan(DownloadCopies.lookFor(listOf(carpet), recorded.values, storage::lookUp), recorded).songs.single()
            var left = deletions
            val delete = { key: String -> if (left-- <= 0) throw IllegalStateException("the app ended") else storage.delete(key) }
            runCatching { DownloadCopies.remove(song, storage::lookAt, delete) {} }
            val found = DownloadCopies.lookFor(listOf(carpet), recorded.values, storage::lookUp).map { it.name }.toSet()
            assertEquals("ended after $deletions deletions", storage.disk.keys, found)
        }
    }

    @Test
    fun `nothing of a song is touched when the file it keeps is not there as it was found`() {
        val files = mapOf("$carpet.mka (5)" to 2_296_381L, "$carpet.mka (1)" to 2_296_381L)
        for (since in listOf<(PhoneStorage) -> Unit>({ it.disk.remove("$carpet.mka (5)") }, { it.disk["$carpet.mka (5)"] = 0L })) {
            val storage = PhoneStorage(files)
            val song = DownloadCopies.Song("2YvDwY80qJl", storage.lookUp("$carpet.mka (5)")!!, listOf(storage.lookUp("$carpet.mka (1)")!!), "$carpet.mka")
            since(storage)
            var steps = 0
            assertNull(DownloadCopies.remove(song, storage::lookAt, storage::delete) { steps++ })
            assertTrue(storage.disk.containsKey("$carpet.mka (1)"))
            assertEquals(1, steps)
        }
    }

    @Test
    fun `a copy that is not the file found any more is left as it is`() {
        // A folder whose addresses follow a file through a rename, as not every storage's do.
        val now = mutableMapOf(
            "a" to File("$carpet.mka", 1_174_405, "a"),
            "b" to File("Renamed by hand.mka", 2_296_381, "b"),
            "c" to File("$saturday.mka (1)", 2_296_381, "c"),
            "d" to File("$carpet.mka (2)", 2_296_381, "d"),
        )
        val song = DownloadCopies.Song(
            "2YvDwY80qJl", now.getValue("a"),
            listOf(File("$carpet.mka (1)", 2_296_381, "b"), File("$carpet.mka (3)", 2_296_381, "c"), now.getValue("d")), null,
        )
        val gone = DownloadCopies.remove(song, { now[it] }, { now.remove(it) != null }) {}
        assertEquals(DownloadCopies.Removal(1, 2_296_381L, allGone = false), gone)
        assertEquals(setOf("a", "b", "c"), now.keys)
    }

    @Test
    fun `names the phone's storage may take for one file`() {
        assertTrue(DownloadCopies.mayBeOneFile("$carpet.mka (5)", "$carpetEdited.mka (5)"))
        assertTrue(DownloadCopies.mayBeOneFile("Café [dQw4w9WgXcQ].mka", "CAFE\u0301 [dQw4w9WgXcQ].mka"))
        assertTrue(DownloadCopies.mayBeOneFile("Straße [dQw4w9WgXcQ].mka", "STRASSE [dQw4w9WgXcQ].mka"))
        assertFalse(DownloadCopies.mayBeOneFile("$carpet.mka (5)", "$carpet.mka (1)"))
        assertFalse(DownloadCopies.mayBeOneFile("$carpet.mka", "$carpet (1).mka"))
        assertFalse(DownloadCopies.mayBeOneFile("$carpet.mka", "$saturday.mka"))
        assertFalse(DownloadCopies.mayBeOneFile("Cafe [dQw4w9WgXcQ].mka", "Café [dQw4w9WgXcQ].mka"))
    }

    @Test
    fun `two names the phone's storage may take for one file leave their song as it is`() {
        // A card that tells letter case apart can hold both; the phone's own storage gives one file under both.
        val twins = listOf(file("$carpet.mka (5)"), file("$carpetEdited.mka (5)"), file("$carpet.mka (1)"))
        assertTrue(DownloadCopies.plan(twins, mapOf("2YvDwY80qJl" to "$carpet.mka (5)")).songs.isEmpty())
        assertTrue(DownloadCopies.plan(twins, emptyMap()).songs.isEmpty())
        // An accent written as one letter or as a letter and its accent is one name too.
        val accents = listOf(file("Café [dQw4w9WgXcQ].mka"), file("Cafe\u0301 [dQw4w9WgXcQ].mka (1)"), file("Café [dQw4w9WgXcQ].mka (1)"))
        assertTrue(DownloadCopies.plan(accents, emptyMap()).songs.isEmpty())
        // Another song of the folder is not held back by them.
        val withSaturday = twins + file("$saturday.mka") + file("$saturday.mka (1)")
        assertEquals(listOf("xHg1rAhB_SE"), DownloadCopies.plan(withSaturday, emptyMap()).songs.map { it.id })
    }
}
