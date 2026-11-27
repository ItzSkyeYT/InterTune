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
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * The clean-up of the extra copies (DownloadCopies) put to a skeptic before it is on for everyone in
 * 0.13 (27 Nov 2026): names of every kind, files that only look like copies, folders made at random,
 * and a Remove the app ended at every step, then the next start. Each must end with every song's
 * music there: the file the database has kept and found again, never a song left without a file,
 * nothing that is not a copy touched.
 */
class DownloadCopiesRobustTest {
    private val folder = "/storage/emulated/0/Music/InterTune"

    private fun file(name: String, size: Long? = 2_000_000) = File(name, size, "content://folder/$name")

    /**
     * A download folder as the phone's own storage keeps it: with [folds], a name is found whatever its
     * letter case and either way of writing an accent, and given back under the name asked (its cards
     * too); without, names are told apart. A rename onto a name taken is numbered before the extension,
     * as FileUtils.buildUniqueFile numbers it. [db]: each song's file as the database has it. [ops]:
     * how many deletes, renames and records are left before the app is ended (null: it never is).
     */
    private class Disk(start: Map<String, Long?>, val folds: Boolean = true) {
        val files = LinkedHashMap<String, Long?>()
        private val index = HashMap<String, String>()
        val db = LinkedHashMap<String, String?>()
        val deleted = mutableListOf<String>()
        var ops: Int? = null

        init {
            start.forEach { (name, size) -> add(name, size) }
        }

        private fun folded(name: String) = if (folds) Normalizer.normalize(name, Normalizer.Form.NFD).lowercase(Locale.ROOT) else name

        /** A file made under [name], or nothing when the storage takes the name for one it has. */
        fun add(name: String, size: Long?): Boolean {
            if (folded(name) in index) return false
            index[folded(name)] = name
            files[name] = size
            return true
        }

        fun onDisk(name: String): String? = index[folded(name)]

        /** Whether the storage takes [a] and [b] for one name. */
        fun same(a: String, b: String) = folded(a) == folded(b)
        fun key(name: String) = "content://storage/tree/primary:Music/InterTune/document/primary:Music/InterTune/$name"
        fun nameIn(key: String) = key.substringAfter("/document/primary:Music/InterTune/")
        fun lookUp(name: String): File? = onDisk(name)?.let { File(name, files[it], key(name)) }
        fun lookAt(key: String): File? = lookUp(nameIn(key))

        /** The folder as a listing gives it: each file of ours under its own name. */
        fun listing(): List<File> = files.keys.filter { DownloadCopies.parse(it) != null }.map { File(it, files[it], key(it)) }

        private fun spend() {
            val left = ops ?: return
            if (left <= 0) throw Ended()
            ops = left - 1
        }

        private fun take(name: String): Long? {
            index.remove(folded(name))
            return files.remove(name)
        }

        fun delete(key: String): Boolean {
            spend()
            val name = onDisk(nameIn(key)) ?: return false
            take(name)
            deleted += name
            return true
        }

        fun rename(key: String, to: String): String {
            spend()
            val size = take(onDisk(nameIn(key)) ?: error("nothing at $key"))
            val dot = to.lastIndexOf('.')
            var name = to
            var n = 0
            while (onDisk(name) != null) name = to.substring(0, dot) + " (${++n})" + to.substring(dot)
            add(name, size)
            return key(name)
        }

        fun record(id: String, path: String) {
            spend()
            db[id] = path
        }
    }

    /** The app ended. */
    private class Ended : RuntimeException()

    /** What DownloadCopiesCleanup.look takes and makes: the database's files, their names in the folder, the plan. */
    private class Look(val plan: DownloadCopies.Plan, val recorded: Map<String, String?>, val names: Map<String, String>)

    private fun names(folder: String, recorded: Map<String, String?>): Map<String, String> = recorded.mapNotNull { (id, path) ->
        path?.takeIf { it.substringBeforeLast('/') == folder }?.let { id to it.substringAfterLast('/') }
    }.toMap()

    /**
     * The look at the start, as DownloadCopiesCleanup.look makes it: a folder that lists gives its files,
     * those of the songs with a copy looked at (ofSongsWithCopies); one that lists as empty ([byName]) is
     * asked for each song's files by name ([bases]: the titles the database has), the database's first.
     */
    private fun look(disk: Disk, byName: Boolean = false, bases: Collection<String> = emptyList()): Look {
        val recorded = HashMap(disk.db)
        val names = names(folder, recorded)
        val files = if (byName) {
            DownloadCopies.lookFor(bases + names.values.mapNotNull { DownloadCopies.parse(it)?.base }, names.values, disk::lookUp)
        } else {
            val listed = disk.listing()
            val wanted = DownloadCopies.ofSongsWithCopies(listed.map { it.name })
            listed.filter { it.name in wanted }
        }
        return Look(DownloadCopies.plan(files, names), recorded, names)
    }

    /** One song's copies removed in DownloadCopiesCleanup.removeFrom's order: the copies, then the name, then the record. */
    private fun removeFrom(song: DownloadCopies.Song, disk: Disk, look: Look) {
        val gone = DownloadCopies.remove(song, disk::lookAt, disk::delete) {} ?: return
        if (gone.keptLost) return
        val name = song.renameTo?.takeIf { gone.allGone && disk.lookUp(it) == null }
        val key = if (name != null) disk.rename(song.keep.key, name) else song.keep.key
        val path = "$folder/${disk.nameIn(key)}"
        if (song.id in look.recorded && DownloadCopies.repoints(song.id, look.recorded[song.id], path, folder)) disk.record(song.id, path)
    }

    private fun Disk.whole(name: String) = lookUp(name)?.let { it.size != 0L } == true

    /**
     * Whether the next start keeps the song's download on a phone whose folder lists as empty, as
     * DownloadDirectoryManagerOt.lookAgain looks: the file the database has, or, for one of its copies
     * that is not there, its download under its own name (namedBack).
     */
    private fun nextStartKeeps(disk: Disk, id: String): Boolean {
        val path = disk.db[id] ?: return false
        if (path.substringBeforeLast('/') != folder) return false
        val name = path.substringAfterLast('/')
        if (disk.whole(name)) return true
        val own = DownloadCopies.ownNameOf(name)?.takeIf { DownloadCopies.parse(name)?.id == id } ?: return false
        return disk.whole(own)
    }

    private val carpet = "4K CARPET [2YvDwY80qJl]"
    private val saturday = "10_15 Saturday Night [xHg1rAhB_SE]"

    /** His folder as the 0.13 line finds it: each song's download, five copies, the database at the newest copy as each move recorded it. */
    private fun dvd(): Disk = Disk(
        mapOf("$carpet.mka" to 1_174_405L) + (1..5).associate { "$carpet.mka ($it)" to 2_296_381L } +
            mapOf("$saturday.mka" to 2_023_751L) + (1..5).associate { "$saturday.mka ($it)" to 4_026_532L },
    ).apply {
        db["2YvDwY80qJl"] = "$folder/$carpet.mka (5)"
        db["xHg1rAhB_SE"] = "$folder/$saturday.mka (5)"
    }

    @Test
    fun `a Remove the app ended at any step leaves each song a file the next start keeps, and the next Remove ends the job`() {
        val ids = listOf("2YvDwY80qJl", "xHg1rAhB_SE")
        for (byName in listOf(false, true)) {
            // A song: five files deleted, its copy named back, recorded. Fourteen steps for the two.
            for (cut in 0..14) {
                val disk = dvd()
                val first = look(disk, byName, listOf(carpet, saturday))
                assertEquals(10, first.plan.files)
                disk.ops = cut
                try {
                    first.plan.songs.forEach { removeFrom(it, disk, first) }
                } catch (_: Ended) {
                }
                disk.ops = null
                val at = "listed as empty $byName, ended after $cut steps"
                for (id in ids) {
                    assertTrue("$at: $id has no file left", disk.files.any { (name, size) -> DownloadCopies.parse(name)?.id == id && size != 0L })
                    assertTrue("$at: the next start lets $id's download go (database ${disk.db[id]}, folder ${disk.files.keys})", nextStartKeeps(disk, id))
                }
                // The next start's card, and its Remove: one file a song, its download, that the start after keeps.
                val second = look(disk, byName, listOf(carpet, saturday))
                second.plan.songs.forEach { removeFrom(it, disk, second) }
                assertEquals(at, setOf("$carpet.mka", "$saturday.mka"), disk.files.keys)
                for (id in ids) assertTrue("$at: $id", nextStartKeeps(disk, id))
                assertEquals(at, 0, look(disk, byName, listOf(carpet, saturday)).plan.files)
            }
        }
    }

    @Test
    fun `a second Remove right after the first changes nothing`() {
        val disk = dvd()
        val first = look(disk)
        first.plan.songs.forEach { removeFrom(it, disk, first) }
        val files = LinkedHashMap(disk.files)
        val db = HashMap(disk.db)
        // The same card's Remove again, with the plan it had.
        first.plan.songs.forEach { removeFrom(it, disk, first) }
        assertEquals(files, disk.files)
        assertEquals(db, disk.db)
        assertEquals(mapOf("2YvDwY80qJl" to "$folder/$carpet.mka", "xHg1rAhB_SE" to "$folder/$saturday.mka"), disk.db)
    }

    /** Files that look like copies and are not: another type, written otherwise, a part, no id, a number the storage never gives. */
    private val lookAlikes: List<(String) -> String> = listOf(
        { "$it.opus" }, { "$it (1).opus" }, { "$it.m4a (1)" }, { "$it (2).webm" }, { "$it.mp3 (3)" },
        { "$it.mka (0)" }, { "$it.mka (33)" }, { "$it.mka (01)" }, { "$it.mka ( 1)" }, { "$it.mka (1)x" }, { "$it.mka(1)" },
        { "$it.MKA (1)" }, { "$it.Mka (2)" }, { "$it.mka (1) (1)" }, { "$it (1) (2).mka" }, { "$it (1).MKA" },
        { "$it.mka.intertune-part" }, { "$it.mka (1).intertune-part" }, { "$it (1).mka.intertune-part" },
        { it.substringBefore(" [") + ".mka (1)" }, { it.substringBefore(" [") + " (1).mka" }, { it.replace(" [", "[") + ".mka (1)" },
        { it.dropLast(2) + "].mka (1)" }, { it.dropLast(1) + "x].mka (1)" }, { it.replace("]", ") ") + ".mka (1)" },
    )

    /** Files of no song of the app's: the person's own, another app's. */
    private val others = listOf(
        "Notes.mka (1)", "Artist - Title.opus", "Artist - Title (1).opus", "Song [abc].mka (1)", "Song [abc].mka", "cover.jpg",
        ".nomedia", "Live [dQw4w9WgXcQ].MKA (1)", "Voice memo (1).m4a",
    )

    @Test
    fun `files that only look like copies are never ours`() {
        for (base in listOf("4K CARPET [2YvDwY80qJl]", "Song (1) [dQw4w9WgXcQ]", " [dQw4w9WgXcQ]", "Mix [abcdefghijk] [xHg1rAhB_SE]")) {
            for (lookAlike in lookAlikes) assertNull(lookAlike(base), DownloadCopies.parse(lookAlike(base)))
            // Beside its song's download and its real copies, only the copies go.
            val files = listOf(file("$base.mka"), file("$base.mka (1)"), file("$base (2).mka")) + lookAlikes.map { file(it(base)) } + others.map { file(it) }
            val song = DownloadCopies.plan(files, emptyMap()).songs.single()
            assertEquals("$base.mka", song.keep.name)
            assertEquals(listOf("$base.mka (1)", "$base (2).mka"), song.remove.map { it.name })
        }
        for (name in others) assertNull(name, DownloadCopies.parse(name))
    }

    /** A name as the phone's storage cuts one past 255 bytes (FileUtils.trimFilename): characters out of its middle, "..." in their place. */
    private fun trimmed(name: String): String {
        val res = StringBuilder(name)
        if (res.toString().toByteArray().size > 255) {
            while (res.toString().toByteArray().size > 252) res.deleteCharAt(res.length / 2)
            res.insert(res.length / 2, "...")
        }
        return res.toString()
    }

    @Test
    fun `titles with brackets, unicode, a number of their own and the longest names the phone keeps are read as the app wrote them`() {
        val id = "dQw4w9WgXcQ"
        val titles = listOf(
            "Track [Live] [Remix]", "Mix [abcdefghijk]", "Song (1)", "Song (2) (3)", "Song (1).mka", "Café", "Café", "夜に駆ける",
            "أغنية الحب", "🎵🎶", "", fatName("AC/DC: T.N.T?"), "x".repeat(230),
        )
        for (title in titles) {
            val base = "$title [$id]"
            assertEquals(title, DownloadCopies.Name(base, id, null), DownloadCopies.parse("$base.mka"))
            for (n in 1..DownloadCopies.MOST_COPIES) {
                assertEquals(DownloadCopies.Name(base, id, n), DownloadCopies.parse("$base.mka ($n)"))
                assertEquals(DownloadCopies.Name(base, id, n), DownloadCopies.parse("$base ($n).mka"))
            }
            // Its download and copies (2) to (9), made either way: the copies go, the download stays.
            val files = listOf(file("$base.mka", 1_174_405)) + (2..9).map { file(DownloadCopies.copyNames(base, it)[it % 2]) }
            val song = DownloadCopies.plan(files, emptyMap()).songs.single()
            assertEquals(title, "$base.mka", song.keep.name)
            assertEquals(title, files.drop(1), song.remove)
        }
        // A name past 255 bytes is cut in its middle by the storage, and its end, " [id].mka", stays: read as it was written.
        val long = trimmed("Très long ".repeat(40) + "[$id].mka")
        assertTrue(long.toByteArray().size <= 255)
        assertEquals(id, DownloadCopies.parse(long)?.id)
        // The longest that can have a copy: " (1)" after it still fits.
        val near = "Très long ".repeat(21) + "[$id].mka"
        assertTrue("$near (1)".toByteArray().size <= 255)
        assertEquals(DownloadCopies.Name(near.removeSuffix(".mka"), id, 1), DownloadCopies.parse("$near (1)"))
        // Asked by name with its whole title, as a folder that lists as empty is, it is not found: its copies stay, never lost.
        val disk = Disk(mapOf(long to 2_000_000L))
        assertTrue(DownloadCopies.lookFor(listOf(fatName("Très long ".repeat(40).trimEnd() + " [$id]")), emptyList(), disk::lookUp).isEmpty())
    }

    @Test
    fun `a folder that lists as empty finds copies past the gaps a few deletions by hand leave, and leaves the rest`() {
        val disk = Disk(mapOf("$carpet.mka" to 1_174_405L) + listOf(2, 4, 7, 9).associate { "$carpet.mka ($it)" to 2_296_381L } + ("$carpet (3).mka" to 2_296_381L))
        val found = DownloadCopies.lookFor(listOf(carpet), emptyList(), disk::lookUp).map { it.name }.toSet()
        assertEquals(disk.files.keys, found)
        // Four missing in a row: what is past them is not found, so it stays.
        val far = Disk(mapOf("$carpet.mka" to 1_174_405L, "$carpet.mka (5)" to 2_296_381L))
        assertEquals(setOf("$carpet.mka"), DownloadCopies.lookFor(listOf(carpet), emptyList(), far::lookUp).map { it.name }.toSet())
    }

    @Test
    fun `a song whose only file is a numbered one keeps it, under its download's name`() {
        val disk = Disk(mapOf("$carpet.mka (3)" to 2_296_381L, "$saturday.mka" to 2_023_751L))
        disk.db["2YvDwY80qJl"] = "$folder/$carpet.mka (3)"
        val look = look(disk)
        assertEquals(0, look.plan.files)
        look.plan.songs.forEach { removeFrom(it, disk, look) }
        assertEquals(setOf("$carpet.mka", "$saturday.mka"), disk.files.keys)
        assertEquals("$folder/$carpet.mka", disk.db["2YvDwY80qJl"])
    }

    @Test
    fun `two songs whose ids differ in letter case alone are two songs, and neither loses its file`() {
        // One name to the phone's storage: the second song's download came out numbered beside the first's.
        val disk = Disk(mapOf("Intro [dQw4w9WgXcQ].mka" to 2_000_000L, "Intro [DQW4W9WGXCQ].mka (1)" to 3_000_000L))
        disk.db["dQw4w9WgXcQ"] = "$folder/Intro [dQw4w9WgXcQ].mka"
        disk.db["DQW4W9WGXCQ"] = "$folder/Intro [DQW4W9WGXCQ].mka (1)"
        for (byName in listOf(false, true)) {
            val look = look(disk, byName, listOf("Intro [dQw4w9WgXcQ]", "Intro [DQW4W9WGXCQ]"))
            assertEquals(0, look.plan.files)
            look.plan.songs.forEach { removeFrom(it, disk, look) }
            // Not named over the other song's file, which the storage takes for that name.
            assertEquals(setOf("Intro [dQw4w9WgXcQ].mka", "Intro [DQW4W9WGXCQ].mka (1)"), disk.files.keys)
            assertTrue(nextStartKeeps(disk, "dQw4w9WgXcQ") && nextStartKeeps(disk, "DQW4W9WGXCQ"))
        }
    }

    @Test
    fun `a name that is a copy's but for its letter case stays where the folder lists, and goes as that copy where it is asked by name`() {
        // The phone's storage takes "X [id].MKA (1)" for "X [id].mka (1)": one name to it. Listed, the file shows its own
        // name, which is no copy's, and stays. In a folder that lists as empty, asked for "X [id].mka (1)", the storage
        // answers with it under the name asked: it goes as the song's copy, and the song keeps its download.
        val files = mapOf("$carpet.mka" to 1_174_405L, "$carpet.MKA (1)" to 2_296_381L)
        val listed = Disk(files)
        val l = look(listed)
        assertEquals(0, l.plan.files)
        val asked = Disk(files)
        val a = look(asked, byName = true, bases = listOf(carpet))
        assertEquals(listOf("$carpet.mka (1)"), a.plan.songs.single().remove.map { it.name })
        a.plan.songs.forEach { removeFrom(it, asked, a) }
        assertEquals(setOf("$carpet.mka"), asked.files.keys)
    }

    @Test
    fun `a folder of thousands of downloads and not one copy has no file looked at one by one`() {
        val names = (1..5000).map { "Song $it [${"%011d".format(it)}].mka" } + others
        assertTrue(DownloadCopies.ofSongsWithCopies(names).isEmpty())
        val one = names + "Song 7 [00000000007] (1).mka"
        assertEquals(setOf("Song 7 [00000000007].mka", "Song 7 [00000000007] (1).mka"), DownloadCopies.ofSongsWithCopies(one))
        assertNull(DownloadCopies.ownNameOf("Song 7 [00000000007].mka"))
        assertEquals("Song 7 [00000000007].mka", DownloadCopies.ownNameOf("Song 7 [00000000007] (1).mka"))
        assertEquals("$carpet.mka", DownloadCopies.ownNameOf("$carpet.mka (5)"))
        assertNull(DownloadCopies.ownNameOf("Artist - Title (1).opus"))
    }

    private val ids = listOf("dQw4w9WgXcQ", "2YvDwY80qJl", "xHg1rAhB_SE", "a-b_c-d_e-f")
    private val titles = listOf(
        "4K CARPET", "4K Carpet", "Song (1)", "Café [Live]", "Café [Live]", "夜に駆ける", "Straße", "STRASSE", "x".repeat(200),
        "Mix [abcdefghijk]", "",
    )
    private val sizes = listOf<Long?>(0L, null, 300_000L, 1_174_405L, 2_296_381L, 2_296_381L, 4_026_532L)

    /** A folder made at random: up to three songs, each under one or two titles, its download or not, copies either way, look-alikes, the person's files, and what the database has. */
    private fun randomFolder(random: Random, folds: Boolean): Pair<Disk, List<String>> {
        val disk = Disk(emptyMap(), folds)
        val bases = mutableListOf<String>()
        fun put(name: String) {
            disk.add(name, sizes.random(random))
        }
        for (id in ids.shuffled(random).take(random.nextInt(1, 4))) {
            val its = List(random.nextInt(1, 3)) { "${titles.random(random)} [$id]" }.distinct()
            bases += its
            for (base in its) {
                if (random.nextInt(10) < 7) put("$base.mka")
                for (n in 1..9) if (random.nextInt(3) == 0) put(DownloadCopies.copyNames(base, n)[random.nextInt(2)])
                if (random.nextInt(6) == 0) put(DownloadCopies.copyNames(base, random.nextInt(10, 33))[random.nextInt(2)])
                for (lookAlike in lookAlikes) if (random.nextInt(6) == 0) put(lookAlike(base))
            }
            val mine = disk.files.keys.filter { DownloadCopies.parse(it)?.id == id }
            disk.db[id] = when (random.nextInt(6)) {
                0 -> null
                1 -> "$folder/${its.first()}.mka (31)"
                2 -> "$folder/Artist - Title.opus"
                3 -> "/storage/emulated/0/Download/${its.first()}.mka (1)"
                else -> mine.randomOrNull(random)?.let { "$folder/$it" }
            }
        }
        for (other in others) if (random.nextInt(3) == 0) put(other)
        return disk to bases
    }

    @Test
    fun `folders made at random keep each song's music, the database's file and every file that is no copy, the app ended or not`() {
        val random = Random(20261127)
        var touched = 0
        var ended = 0
        repeat(6000) { round ->
            val folds = round % 3 != 0
            val byName = folds && round % 2 == 0
            val (disk, bases) = randomFolder(random, folds)
            val before = LinkedHashMap(disk.files)
            val dbBefore = HashMap(disk.db)
            val look = look(disk, byName, bases)
            // The app ended at a step of the Remove, in half the rounds: each copy, name and record a step.
            val steps = look.plan.songs.sumOf { it.remove.size + 2 }
            val cut = if (round % 2 == 1 && steps > 0) random.nextInt(0, steps) else null
            val at = "round $round (folds $folds, listed as empty $byName, ended after ${cut ?: "no"} steps): $before, database $dbBefore"
            // A listing looked at only for the songs with a copy plans as the whole listing would.
            if (!byName) assertEquals(at, DownloadCopies.plan(disk.listing(), look.names), look.plan)
            disk.ops = cut
            try {
                look.plan.songs.forEach { removeFrom(it, disk, look) }
            } catch (_: Ended) {
                ended++
            }
            disk.ops = null
            if (look.plan.files > 0) touched++
            if (cut == null) assertEquals(at, look.plan.files, disk.deleted.size)
            // Each file deleted is a copy the plan named, or its song's download beside the copy kept. Listed, by its very
            // name; asked by name, it may be one the storage takes for that name ("X [id].MKA (1)" for "X [id].mka (1)").
            val planned = look.plan.songs.flatMap { it.remove }
            fun asOne(name: String) = planned.firstOrNull { if (byName) disk.same(it.name, name) else it.name == name }
            for (name in disk.deleted) {
                val asked = asOne(name)
                assertTrue("$at: $name was not to go", asked != null)
                val parsed = DownloadCopies.parse(asked!!.name)!!
                val kept = DownloadCopies.parse(look.plan.songs.single { it.id == parsed.id }.keep.name)!!
                assertTrue("$at: $name is no copy", parsed.copy != null || (kept.copy != null && parsed.base == kept.base))
            }
            // Every file that is no copy of ours is as it was, but for one the storage answered for a name the plan has (a
            // copy, or the copy kept and named back), and only a name given back is new.
            val named = look.plan.songs.flatMap { it.remove + it.keep }
            fun notOurs(files: Map<String, Long?>) = files.filterKeys { name ->
                DownloadCopies.parse(name) == null && (!byName || named.none { disk.same(it.name, name) })
            }
            assertEquals(at, notOurs(before), notOurs(disk.files))
            if (!byName) assertEquals(at, before.filterKeys { DownloadCopies.parse(it) == null }, disk.files.filterKeys { DownloadCopies.parse(it) == null })
            val renamed = look.plan.songs.mapNotNull { it.renameTo }.toSet()
            assertTrue(at, (disk.files.keys - before.keys).all { it in renamed })
            fun everySongHasMusic(what: String) {
                for ((name, size) in before) {
                    val id = DownloadCopies.parse(name)?.id ?: continue
                    if (size != 0L) assertTrue("$at, $what: $id has no file left", disk.files.any { (n, s) -> s != 0L && DownloadCopies.parse(n)?.id == id })
                }
                // The file the database had: kept, and found again at the next start.
                for ((id, path) in dbBefore) {
                    val name = path?.takeIf { it.substringBeforeLast('/') == folder }?.substringAfterLast('/') ?: continue
                    if (name in before && before[name] != 0L && DownloadCopies.parse(name)?.id == id) {
                        assertTrue("$at, $what: the next start lets $id's download go (database ${disk.db[id]}, folder ${disk.files.keys})", nextStartKeeps(disk, id))
                    }
                }
            }
            for (song in look.plan.songs) {
                val name = look.names[song.id] ?: continue
                if (name in before && before[name] != 0L && DownloadCopies.parse(name)?.id == song.id) assertEquals(at, name, song.keep.name)
            }
            everySongHasMusic("after the Remove")
            // The next start's look and Remove end the job, and the one after has nothing more to remove.
            val again = look(disk, byName, bases)
            again.plan.songs.forEach { removeFrom(it, disk, again) }
            everySongHasMusic("after the next Remove")
            assertEquals(at, 0, look(disk, byName, bases).plan.files)
        }
        assertTrue("too few folders had copies to remove: $touched", touched > 1500)
        assertTrue("too few Removes were ended: $ended", ended > 500)
    }
}
