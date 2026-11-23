/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

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
     * time with [find], which says whether a file of that name is there: for each of [bases], its
     * download and its copies each way, and [names] as they are (the files the database has).
     * Never more than 1 + 2 x [MOST_COPIES] looks a base.
     */
    fun lookFor(bases: Collection<String>, names: Collection<String>, find: (String) -> File?): List<File> {
        val found = LinkedHashMap<String, File>()
        val missing = HashSet<String>()
        fun there(name: String): Boolean {
            if (name in found) return true
            if (name in missing) return false
            val file = find(name)
            if (file == null) missing += name else found[name] = file
            return file != null
        }
        names.forEach { there(it) }
        for (base in bases.distinct()) {
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
     * copy and stays. A song whose files are all empty is left as it is.
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
