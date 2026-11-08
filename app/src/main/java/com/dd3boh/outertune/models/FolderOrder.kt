/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import java.text.Normalizer

/**
 * The order of the folders in the Folders tab, and of a whole folder's songs when it is played
 * from its menu, with no Android in it, so it is tested.
 *
 * Folders always go by name, A to Z or Z to A by an arrow of their own, whatever the songs are
 * sorted by. They came in the order the library handed their songs over in, which follows the
 * songs' random ids, and only the top level was ever sorted (issue #39). Then they followed the
 * songs' sort and arrow, and an artist's albums sorted by track number with the arrow down went
 * from Z; #39's reporter asked for them by name with their own arrow.
 */
object FolderOrder {

    /** By the name the list shows, read as people read it (NaturalOrder), then by path. */
    private val byName: Comparator<DirectoryTree> =
        compareBy<DirectoryTree, String>(NaturalOrder) { shownName(it) }.thenBy { it.getFullPath() }

    /** [folders] by name, A to Z, or Z to A when [descending]. */
    fun sorted(folders: List<DirectoryTree>, descending: Boolean): List<DirectoryTree> =
        folders.sortedWith(if (descending) byName.reversed() else byName)

    /** The name a folder's row shows, as FolderScreen hands it to SongFolderItem. */
    fun shownName(folder: DirectoryTree): String =
        if (folder.files.isEmpty()) folder.getSquashedDir() else folder.currentDir

    /**
     * The songs of the folder at [root] and of every folder under it, in the order its screen
     * shows them: the folder's own songs in the order of the songs' sort, then each folder in it by
     * name with the folders' arrow, the same way down. Played from the folder's menu, they were
     * queued in the order the library held them, whatever the sort (issue #39).
     */
    fun playOrder(
        root: String,
        songs: List<Song>,
        sortType: FolderSongSortType,
        descending: Boolean,
        foldersDescending: Boolean,
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
            sorted(folder.subdirs, foldersDescending).forEach(::walk)
        }
        walk(tree)
        return order
    }
}

/**
 * Names compared as people read them: a run of digits as a number, so "CD 2" comes before
 * "CD 10", and letters without regard to case or accent, so "Édith" comes among the E's and not
 * after Z. Names that still tie are told apart by their plain text, so the order is a total one.
 */
internal object NaturalOrder : Comparator<String> {
    private fun Char.isDigit09() = this in '0'..'9'

    /**
     * [name] with its letters and their accents apart and the accents left out. Latin accents
     * only, as SimilarMatch takes them off: Japanese voicing marks are combining too, and が is
     * not か. Most names are plain ASCII and have none, and this runs at every comparison.
     */
    private fun withoutAccents(name: String): String {
        if (name.all { it < '\u0080' }) return name
        val apart = Normalizer.normalize(name, Normalizer.Form.NFD)
        return buildString(apart.length) { for (c in apart) if (c !in '\u0300'..'\u036F') append(c) }
    }

    override fun compare(a: String, b: String): Int {
        val read = compareRead(withoutAccents(a), withoutAccents(b))
        return if (read != 0) read else a.compareTo(b)
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
