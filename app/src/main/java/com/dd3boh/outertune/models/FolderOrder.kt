/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.LocalFolderDates
import com.dd3boh.outertune.db.entities.Song
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The order of the folders in the Folders tab, and of a whole folder's songs when it is played
 * from its menu, with no Android in it, so it is tested.
 *
 * Folders follow the sort picked for the songs where it means something for a folder: the date
 * sorts by the newest of that date among the songs under the folder, every other sort by the
 * folder's name. The arrow turns them round as it does the songs. They came in the order the
 * library handed their songs over in, which follows the songs' random ids, and only the top level
 * was ever sorted, by name and only with the arrow down (issue #39).
 */
object FolderOrder {

    /** Whether folders go by a date under [sortType], which [newestDates] gives them. */
    fun byDate(sortType: FolderSongSortType): Boolean = when (sortType) {
        FolderSongSortType.CREATE_DATE, FolderSongSortType.MODIFIED_DATE, FolderSongSortType.RELEASE_DATE -> true
        FolderSongSortType.NAME, FolderSongSortType.ARTIST, FolderSongSortType.PLAY_COUNT, FolderSongSortType.TRACK_NUMBER -> false
    }

    /** By the name the list shows, numbers in it read as numbers, then by path. */
    private val byName: Comparator<DirectoryTree> =
        compareBy<DirectoryTree, String>(NaturalOrder) { shownName(it) }.thenBy { it.getFullPath() }

    /**
     * [folders] in the order of [sortType]. [dates] holds each folder's date by its path, as
     * [newestDates] makes them. A folder with no date goes first, as a song with none does, and
     * last when turned round. Folders that come out equal go by name.
     */
    fun sorted(
        folders: List<DirectoryTree>,
        sortType: FolderSongSortType,
        descending: Boolean,
        dates: Map<String, Long> = emptyMap(),
    ): List<DirectoryTree> {
        val key: Comparator<DirectoryTree> =
            if (byDate(sortType)) compareBy { dates[it.getFullPath()] ?: -1L } else byName
        return folders.sortedWith((if (descending) key.reversed() else key).then(byName))
    }

    /** The name a folder's row shows, as FolderScreen hands it to SongFolderItem. */
    fun shownName(folder: DirectoryTree): String =
        if (folder.files.isEmpty()) folder.getSquashedDir() else folder.currentDir

    /**
     * Each folder's date under [sortType], by path: the newest of that date among the songs in the
     * folder and in every folder under it. [rows] are LocalSql.LOCAL_FOLDER_DATES's, one for each
     * folder that holds songs. A song with a release year and no full date counts from the 1st of
     * January, as it does when songs are sorted.
     */
    fun newestDates(rows: List<LocalFolderDates>, sortType: FolderSongSortType): Map<String, Long> {
        val newest = HashMap<String, Long>()
        for (row in rows) {
            val date = when (sortType) {
                FolderSongSortType.CREATE_DATE -> row.added
                FolderSongSortType.MODIFIED_DATE -> row.modified
                FolderSongSortType.RELEASE_DATE -> row.released ?: row.year?.let(::yearStart)
                else -> null
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

    /**
     * The songs of the folder at [root] and of every folder under it, in the order its screen
     * shows them: the folder's own songs in the order of the sort, then each folder in it in the
     * folder order, the same way down. Played from the folder's menu, they were queued in the
     * order the library held them, whatever the sort (issue #39).
     */
    fun playOrder(
        root: String,
        songs: List<Song>,
        sortType: FolderSongSortType,
        descending: Boolean,
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
            sorted(folder.subdirs, sortType, descending, dates).forEach(::walk)
        }
        walk(tree)
        return order
    }
}

/**
 * Names compared as people read them: a run of digits as a number, so "CD 2" comes before
 * "CD 10", and letters without regard to case. Names that still tie are told apart by their plain
 * text, so the order is a total one.
 */
internal object NaturalOrder : Comparator<String> {
    private fun Char.isDigit09() = this in '0'..'9'

    override fun compare(a: String, b: String): Int {
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
