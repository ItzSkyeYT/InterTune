/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.constants.FolderSortType
import com.dd3boh.outertune.db.LocalFolderDates
import com.dd3boh.outertune.db.entities.Song
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The order of the folders in the Folders tab, and of a whole folder's songs when it is played
 * from its menu, with no Android in it, so it is tested.
 *
 * Folders have an order of their own, apart from what the songs are sorted by: by name, A to Z or
 * Z to A by an arrow of their own, which is where they start, or by a date, the newest of that
 * date among the songs under the folder (0.12.6 to 0.12.9 had the date orders on the songs' own
 * choice, and 0.13's first folder sort took them away: "Date added" over a list of folders did
 * nothing). They came in the order the library handed their songs over in, which follows the
 * songs' random ids, and only the top level was ever sorted (issue #39). Then they followed the
 * songs' sort and arrow, and an artist's albums sorted by track number with the arrow down went
 * from Z; #39's reporter asked for folders by name with their own arrow.
 */
object FolderOrder {

    /** A folder with its name made ready to compare (NaturalOrder.Key). */
    private class Named(val folder: DirectoryTree, val name: NaturalOrder.Key)

    /** By the name the list shows, read as people read it (NaturalOrder), then by path. */
    private val byName: Comparator<Named> =
        Comparator<Named> { a, b -> NaturalOrder.compare(a.name, b.name) }.thenBy { it.folder.getFullPath() }

    /** Whether the folders go by a date under [sortType], which [newestDates] gives them. */
    fun byDate(sortType: FolderSortType): Boolean = when (sortType) {
        FolderSortType.CREATE_DATE, FolderSortType.MODIFIED_DATE, FolderSortType.RELEASE_DATE -> true
        FolderSortType.NAME -> false
    }

    /**
     * [folders] by name, A to Z, or Z to A when [descending]; or, for a date [sortType], by the
     * date each has in [dates] (by its path, as [newestDates] makes them), oldest first or newest
     * first when [descending]. A folder with no date goes first, as a song with none does, and
     * last when turned round. Folders that come out equal go by name, whichever way it is turned.
     * Each name is made ready once, not at every comparison: 2000 folders with accented names took
     * about 18 ms on the main thread.
     */
    fun sorted(
        folders: List<DirectoryTree>,
        descending: Boolean,
        sortType: FolderSortType = FolderSortType.NAME,
        dates: Map<String, Long> = emptyMap(),
    ): List<DirectoryTree> {
        val ready = folders.map { Named(it, NaturalOrder.Key(shownName(it))) }
        if (!byDate(sortType)) {
            return ready.sortedWith(if (descending) byName.reversed() else byName).map { it.folder }
        }
        val byNewest: Comparator<Named> = compareBy { dates[it.folder.getFullPath()] ?: -1L }
        return ready.sortedWith((if (descending) byNewest.reversed() else byNewest).then(byName)).map { it.folder }
    }

    /**
     * Each folder's date under [sortType], by path: the newest of that date among the songs in the
     * folder and in every folder under it. [rows] are LocalSql.LOCAL_FOLDER_DATES's, one for each
     * folder that holds songs. A song with a release year and no full date counts from the 1st of
     * January, as it does when songs are sorted. A name has no date: nothing for it.
     */
    fun newestDates(rows: List<LocalFolderDates>, sortType: FolderSortType): Map<String, Long> {
        val newest = HashMap<String, Long>()
        for (row in rows) {
            val date = when (sortType) {
                FolderSortType.CREATE_DATE -> row.added
                FolderSortType.MODIFIED_DATE -> row.modified
                FolderSortType.RELEASE_DATE -> row.released ?: row.year?.let(::yearStart)
                FolderSortType.NAME -> null
            } ?: continue
            var path = row.dir.trimEnd('/')
            while (path.isNotEmpty()) {
                newest.merge(path, date) { old, new -> maxOf(old, new) }
                path = path.substringBeforeLast('/', "")
            }
        }
        return newest
    }

    private fun yearStart(year: Int): Long? =
        runCatching { LocalDateTime.of(year, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    /** The name a folder's row shows, as FolderScreen hands it to SongFolderItem. */
    fun shownName(folder: DirectoryTree): String =
        if (folder.files.isEmpty()) folder.getSquashedDir() else folder.currentDir

    /**
     * The songs of the folder at [root] and of every folder under it, in the order its screen
     * shows them: the folder's own songs in the order of the songs' sort, then each folder in it in
     * the folders' own order ([foldersSortType], with [dates] for a date, and their arrow), the
     * same way down. Played from the folder's menu, they were queued in the order the library held
     * them, whatever the sort (issue #39).
     */
    fun playOrder(
        root: String,
        songs: List<Song>,
        sortType: FolderSongSortType,
        descending: Boolean,
        foldersDescending: Boolean,
        foldersSortType: FolderSortType = FolderSortType.NAME,
        dates: Map<String, Long> = emptyMap(),
    ): List<Song> {
        val top = root.trimEnd('/')
        val tree = DirectoryTree(top, CulmSongs(0))
        for (song in songs) {
            val path = song.song.localPath ?: continue
            tree.insert(path.removePrefix(top).trimStart('/'), song)
        }
        val order = ArrayList<Song>(songs.size)
        fun walk(folder: DirectoryTree) {
            order += FolderSongOrder.sorted(folder.files, sortType, descending)
            sorted(folder.subdirs, foldersDescending, foldersSortType, dates).forEach(::walk)
        }
        walk(tree)
        return order
    }
}

/**
 * Names compared as people read them: a run of digits as a number, so "CD 2" comes before
 * "CD 10", and letters without regard to case or to the accent on a Latin letter, so "Édith"
 * comes among the E's and not after Z. Names that still tie are told apart by their plain text,
 * so the order is a total one.
 */
internal object NaturalOrder : Comparator<String> {
    private fun Char.isDigit09() = this in '0'..'9'

    /**
     * [name] with the accents on its Latin letters left out: its letters and their marks apart,
     * a mark dropped when the letter it sits on is Latin, and the rest put back together. The
     * Japanese voicing mark and the Cyrillic breve are marks too, but ガ is not カ and Й is not И:
     * each is a letter of its own that comes after. Most names are plain ASCII and have none; a
     * sort makes each name ready once (Key) rather than at every comparison.
     */
    private fun withoutAccents(name: String): String {
        if (name.all { it < '\u0080' }) return name
        val apart = Normalizer.normalize(name, Normalizer.Form.NFD)
        val kept = StringBuilder(apart.length)
        // A mark has no script of its own (INHERITED): it takes that of the letter before it.
        var onLatin = false
        var i = 0
        while (i < apart.length) {
            val c = apart.codePointAt(i)
            i += Character.charCount(c)
            val script = Character.UnicodeScript.of(c)
            if (script != Character.UnicodeScript.INHERITED) {
                onLatin = script == Character.UnicodeScript.LATIN
            } else if (onLatin && c in 0x300..0x36F) {
                continue
            }
            kept.appendCodePoint(c)
        }
        return Normalizer.normalize(kept, Normalizer.Form.NFC)
    }

    override fun compare(a: String, b: String): Int {
        val read = compareRead(withoutAccents(a), withoutAccents(b))
        return if (read != 0) read else a.compareTo(b)
    }

    /** A name with its accents set aside once, for a sort that compares it many times. */
    class Key(val name: String) {
        internal val read: String = NaturalOrder.withoutAccents(name)
    }

    /** Two names made ready, in the order [compare] gives the names themselves. */
    fun compare(a: Key, b: Key): Int {
        val read = compareRead(a.read, b.read)
        return if (read != 0) read else a.name.compareTo(b.name)
    }

    private fun compareRead(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit09() && b[j].isDigit09()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit09()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit09()) endB++
                // Compared as numbers of any length: the longer one without its leading zeros is
                // the bigger one, and two as long compare digit by digit.
                val numberA = a.substring(i, endA).trimStart('0')
                val numberB = b.substring(j, endB).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                numberA.compareTo(numberB).let { if (it != 0) return it }
                i = endA
                j = endB
            } else {
                a[i].lowercaseChar().compareTo(b[j].lowercaseChar()).let { if (it != 0) return it }
                i++
                j++
            }
        }
        val left = (a.length - i) - (b.length - j)
        return if (left != 0) left else a.compareTo(b)
    }
}
