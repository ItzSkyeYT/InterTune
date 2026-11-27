/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import java.text.Normalizer
import java.util.Locale

/**
 * The extra copies of downloads that a name clash in the download folder made, told by their names
 * alone, and what becomes of each song's files: one kept, the copies removed, and the one kept given
 * its download's name again when it is a copy itself. No Android in here: tested on the JVM
 * (DownloadCopiesTest), and run by DownloadCopiesCleanup, which never removes a file a [Plan] does
 * not name.
 *
 * How they came about. 0.12.11 saved a download as "<title> [<id>].mka" with the type "audio/mka",
 * which Android does not know, and the phone's storage numbers a name already taken after the whole
 * name when the extension does not match the type: "Title [id].mka (1)", which no player takes for
 * audio. On a phone whose download folder lists as empty, every scan forgot every song, every liked
 * catch up fetched them all again and the move wrote one more copy beside each (dvd, OnePlus 13R on
 * Android 17: "(1)" to "(5)" of all 397 of his liked songs in three days, 2,382 files, 23 Nov 2026).
 * The 0.13 line writes a part and renames it (DownloadDirectoryManagerOt.writeWhole), and a rename
 * that finds the name taken numbers it before the extension: "Title [id] (1).mka". It made those
 * only beside an empty file of the same name, which the move now clears first, but the Tester
 * builds of 20 and 22 Nov 2026 could: both kinds are found.
 *
 * What is ours, and nothing else is ever touched: "<anything> [<11-character id>].mka" (a download),
 * "<that>.mka (<n>)" and "<that without .mka> (<n>).mka" (its copies), n from 1 to 32, as far as
 * the phone's storage counts (FileUtils.buildUniqueFile). Not "Song.mka (1)" (no id), not
 * "Song [abc].mka (1)" (no YouTube id), not "Song (1).mp3", not a name in capitals.
 *
 * The phone's own storage and its cards find a name whatever its letter case, and give it back
 * under the name asked: once Edit song has put "hello" right as "Hello", "Hello [id].mka (5)" is
 * the file "hello [id].mka (5)". Two names that [fold] alike may be one file, so such a file is
 * found once, a song holding two such names is left as it is, and a removal never deletes the file
 * a song keeps under another of its names (26 Nov 2026). So in a folder that lists as empty, where
 * each name is asked, "Song [id].MKA (1)" is the copy "(1)": one name to the phone, that song's
 * file. A listing gives each name as it is, and there it is not ours (27 Nov 2026).
 */
object DownloadCopies {
    /**
     * A file in the download folder: its [name], its [size] in bytes (null when the folder does not
     * say), and [key], how the folder reaches it again (its address).
     */
    data class File(val name: String, val size: Long?, val key: String)

    /** A listing of the folder: how many files it gave ([listed], 0 for one that lists as empty), and [ours] of them. */
    data class Listed(val listed: Int, val ours: List<File>)

    /**
     * What the name of a file of ours says: the song's [id], the name its download has, [base] + ".mka",
     * and [copy], the n of its " (n)", or null for the download itself.
     */
    data class Name(val base: String, val id: String, val copy: Int?) {
        /** The name of the song's download: what a copy is called again when it is the one kept. */
        val ownName get() = "$base.mka"
    }

    private const val ID = "[A-Za-z0-9_-]{11}"

    /** 1 to 32: the phone's storage gives up after the 32nd. */
    private const val N = "([1-9]|[12][0-9]|3[0-2])"

    private val DOWNLOAD = Regex("""^(.* \[($ID)])\.mka$""")
    private val COPY_AFTER = Regex("""^(.* \[($ID)])\.mka \($N\)$""")
    private val COPY_BEFORE = Regex("""^(.* \[($ID)]) \($N\)\.mka$""")

    /** What [name] says, or null for a file that is not ours. */
    fun parse(name: String): Name? {
        DOWNLOAD.matchEntire(name)?.let { return Name(it.groupValues[1], it.groupValues[2], null) }
        val copy = COPY_AFTER.matchEntire(name) ?: COPY_BEFORE.matchEntire(name) ?: return null
        return Name(copy.groupValues[1], copy.groupValues[2], copy.groupValues[3].toInt())
    }

    /** The names the [n]th copy of [base]'s download has, each way a clash made one. */
    fun copyNames(base: String, n: Int): List<String> = listOf("$base.mka ($n)", "$base ($n).mka")

    /**
     * The name a copy has once a song keeps it: its download's own, "<title> [<id>].mka" ([Name.ownName]).
     * The clean-up names the copy it keeps so, then records it (DownloadCopiesCleanup), and an app ended
     * between the two leaves the database the copy's old name: the scan then looks under this one
     * (DownloadDirectoryManagerOt.lookAgain). Null for a name that is no copy.
     */
    fun ownNameOf(name: String): String? = parse(name)?.takeIf { it.copy != null }?.ownName

    /**
     * Of a folder's [names], the files of the songs that have a copy among them: all that [plan] needs,
     * each then looked at for its size. A folder of thousands of downloads and no copy, as nearly
     * everyone's is, needs no look at all (a look was about a millisecond a file, at every start).
     */
    fun ofSongsWithCopies(names: Collection<String>): Set<String> {
        val ours = names.mapNotNull { name -> parse(name)?.let { name to it } }
        val copied = ours.filter { it.second.copy != null }.mapTo(HashSet()) { it.second.id }
        return ours.filter { it.second.id in copied }.mapTo(LinkedHashSet()) { it.first }
    }

    /**
     * [name] as the phone's storage tells names apart, or a little coarser: letter case aside, "ß" as
     * "ss", and an accent written as one letter or as a letter and its accent alike. Never finer than
     * the storage: two names it tells apart are two files there.
     */
    fun fold(name: String): String {
        val one = Normalizer.normalize(name, Normalizer.Form.NFKC)
        return Normalizer.normalize(one.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFKC)
    }

    /** Whether the phone's storage may take [a] and [b] for one file ([fold]). */
    fun mayBeOneFile(a: String, b: String): Boolean = a == b || fold(a) == fold(b)

    /** The largest n a copy can have. */
    const val MOST_COPIES = 32

    /**
     * Copies looked for one after the other ([lookFor]) are looked for no further after this many
     * missing in a row: a clash takes the first number free, so copies run 1, 2, 3..., and a gap of
     * three is what someone deleting a few by hand leaves at most.
     */
    const val MISSES = 3

    /**
     * The files of ours in a folder that cannot be listed (dvd's lists as empty), found one name at a
     * time with [find], which says whether a file of that name is there: [names] as they are (the
     * files the database has), then for each of their bases and of [bases], its download and its
     * copies each way. Never more than 1 + 2 x [MOST_COPIES] looks a base.
     *
     * [find] is the phone's storage, which answers for a name in another letter case too, under the
     * name it was asked: a name that [fold]s as one asked already is that file again, not asked a
     * second time. So each file is found once, under the database's spelling, asked first.
     */
    fun lookFor(bases: Collection<String>, names: Collection<String>, find: (String) -> File?): List<File> {
        val found = LinkedHashMap<String, File>()
        val missing = HashSet<String>()
        fun there(name: String): Boolean {
            val folded = fold(name)
            if (folded in found) return true
            if (folded in missing) return false
            val file = find(name)
            if (file == null) missing += folded else found[folded] = file
            return file != null
        }
        names.forEach { there(it) }
        for (base in (names.mapNotNull { parse(it)?.base } + bases).distinctBy { fold(it) }) {
            there("$base.mka")
            for (way in 0..1) {
                var misses = 0
                for (n in 1..MOST_COPIES) {
                    if (there(copyNames(base, n)[way])) misses = 0 else if (++misses >= MISSES) break
                }
            }
        }
        return found.values.filter { parse(it.name) != null }
    }

    /** What becomes of one song's files: [keep] stays, [remove] goes, and [keep] is named [renameTo] once they have gone. */
    data class Song(val id: String, val keep: File, val remove: List<File>, val renameTo: String?)

    /** Every song with copies, in the order its files were found. */
    data class Plan(val songs: List<Song>) {
        /** How many files go. */
        val files: Int get() = songs.sumOf { it.remove.size }

        /** The room they take, as far as the folder said. */
        val bytes: Long get() = songs.sumOf { song -> song.remove.sumOf { it.size ?: 0L } }
    }

    /**
     * What to do with [files], a download folder's, given [recorded]: the name of each song's file as
     * the app's database has it, for the songs whose file it has in this folder.
     *
     * Only a song with at least one copy is touched, and it keeps exactly one of its files, never an
     * empty one (a write that never began is no song):
     * 1. the one the database has, if it is one of them, so the player and the scan go on finding it;
     * 2. otherwise its download, the name without " (n)" (the one the copies were made beside first);
     * 3. otherwise, only copies being left, the largest (a copy cut short is smaller), the first made
     *    of equals; it is then renamed to its download's name, so that other players see it again.
     *
     * Every copy goes but the one kept, and so does its download when a copy of the same name is kept
     * (1.): the copy takes its name. A download of another title (the song renamed since) is not a
     * copy and stays. A song whose files are all empty is left as it is, and so is a song with two
     * names the phone's storage may take for one file ([mayBeOneFile]): which is which is in doubt.
     */
    fun plan(files: List<File>, recorded: Map<String, String>): Plan {
        val ours = files.mapNotNull { file -> parse(file.name)?.let { Mine(file, it) } }
        val songs = ours.groupBy { it.name.id }.mapNotNull { (id, mine) -> song(id, mine, recorded[id]) }
        return Plan(songs)
    }

    private class Mine(val file: File, val name: Name) {
        val isCopy get() = name.copy != null
    }

    private fun song(id: String, mine: List<Mine>, recordedName: String?): Song? {
        val copies = mine.filter { it.isCopy }
        if (copies.isEmpty()) return null
        if (mine.distinctBy { fold(it.file.name) }.size < mine.size) return null
        val whole = mine.filter { it.file.size != 0L }
        val copiedBases = copies.mapTo(HashSet()) { it.name.base }
        val keep = whole.firstOrNull { it.file.name == recordedName }
            ?: whole.filter { !it.isCopy }.minWithOrNull(
                compareBy<Mine> { if (it.name.base in copiedBases) 0 else 1 }.thenBy { it.file.name }
            )
            ?: whole.minWithOrNull(
                compareByDescending<Mine> { it.file.size ?: -1L }.thenBy { it.name.copy }.thenBy { it.file.name }
            )
            ?: return null
        val remove = mine.filter { it !== keep && (it.isCopy || (keep.isCopy && it.name.base == keep.name.base)) }
        val left = mine.filter { it === keep || it !in remove }
        val renameTo = keep.name.ownName.takeIf { keep.isCopy && left.none { other -> other !== keep && other.file.name == it } }
        return Song(id, keep.file, remove.map { it.file }, renameTo)
    }

    /**
     * What came of removing one song's copies ([remove]): how many went and the room they took, whether
     * every one of them is gone, and [keptLost], its file kept gone with one of them after all, which
     * stopped the removal there.
     */
    data class Removal(val files: Int, val bytes: Long, val allGone: Boolean, val keptLost: Boolean = false)

    /**
     * [song]'s copies removed with [delete], the folder's own, each looked at again with [lookAt]
     * first and deleted only if it is still the file found, [step] once for each copy. Null, nothing
     * of the song touched, when its file kept is not there as it was found, whole.
     *
     * The file kept is never deleted: not at its own address, and not as a copy under a name the
     * phone's storage may take for its own ([mayBeOneFile]: there, deleting "Hello [id].mka (5)"
     * deletes "hello [id].mka (5)"). It is looked at again after each copy deleted, and should one have
     * taken it all the same, the removal stops there: the song's other copies stay.
     *
     * The highest numbers go first, its download last: a removal the app ends leaves each song's copies
     * from (1) up, which a folder that lists as empty gives up again by name ([lookFor] stops [MISSES]
     * numbers past the last one there). Deleted from (1) up, the copies left past a gap of three were
     * never found again (27 Nov 2026).
     */
    fun remove(song: Song, lookAt: (String) -> File?, delete: (String) -> Boolean, step: () -> Unit): Removal? {
        fun keptThere() = lookAt(song.keep.key).let { it != null && it.name == song.keep.name && it.size != 0L }
        if (!keptThere()) {
            song.remove.forEach { _ -> step() }
            return null
        }
        var files = 0
        var bytes = 0L
        var allGone = true
        for ((i, copy) in song.remove.sortedByDescending { parse(it.name)?.copy ?: 0 }.withIndex()) {
            if (copy.key == song.keep.key || mayBeOneFile(copy.name, song.keep.name)) {
                // The file kept itself: never.
                allGone = false
            } else {
                val now = lookAt(copy.key)
                when {
                    // Gone already.
                    now == null -> Unit
                    // Not the file found any more: left as it is.
                    now.name != copy.name || parse(now.name)?.id != song.id -> allGone = false
                    delete(copy.key) -> {
                        files++
                        bytes += now.size ?: 0L
                        if (!keptThere()) {
                            repeat(song.remove.size - i) { step() }
                            return Removal(files, bytes, allGone = false, keptLost = true)
                        }
                    }
                    else -> allGone = false
                }
            }
            step()
        }
        return Removal(files, bytes, allGone)
    }

    /**
     * Whether the database is to point at [kept], the path of the file a song kept, once its copies
     * have gone: when it had no file for the song ([had] null), or had one of this song's own in this
     * folder ([folder]) under another path, a copy gone or the copy renamed. A file of the song it has
     * elsewhere, or a song file, is the database's own business, and stays.
     */
    fun repoints(id: String, had: String?, kept: String?, folder: String?): Boolean {
        if (kept == null || had == kept) return false
        if (had == null) return true
        return folder != null && had.substringBeforeLast('/') == folder && parse(had.substringAfterLast('/'))?.id == id
    }
}
