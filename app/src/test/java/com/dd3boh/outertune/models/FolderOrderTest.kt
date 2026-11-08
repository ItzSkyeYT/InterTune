/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Issue #39, second report: in the Folders tab the folders kept no order whatever the sort, and
 * a folder played from its menu came in the order the library holds its songs. Then they followed
 * the songs' sort and arrow, and its reporter asked for folders by name with an arrow of their own
 * (8 Nov 2026): sorted by track number with the arrow down, the albums went from Z.
 */
class FolderOrderTest {

    private val music = "/storage/emulated/0/Music"

    private fun song(path: String, track: Int? = null, disc: Int? = null, added: Int? = null) = Song(
        SongEntity(
            id = "LS" + path.hashCode(),
            title = path.substringAfterLast('/').substringBeforeLast('.'),
            isLocal = true,
            localPath = path,
            trackNumber = track,
            discNumber = disc,
            inLibrary = added?.let { LocalDateTime.of(2026, 10, it, 12, 0) },
            dateModified = added?.let { LocalDateTime.of(2026, 11, 29 - it, 12, 0) },
            date = added?.let { LocalDateTime.of(1990 + it, 1, 1, 0, 0) },
        ),
        artists = emptyList(),
    )

    /** The folder at [root] as the Folders tab builds it from the library: in no useful order. */
    private fun tree(root: String, vararg files: String): DirectoryTree {
        val top = DirectoryTree(root, CulmSongs(0))
        files.forEach { top.insert(it, song("$root/$it")) }
        return top
    }

    private fun names(folders: List<DirectoryTree>) = folders.map { FolderOrder.shownName(it) }

    // Shuffled, as the song ids the library hands them over by are random.
    private val music4 = tree(music, "CD 10/a.mp3", "Bonus/a.mp3", "cd 1/a.mp3", "CD 2/a.mp3")

    @Test
    fun `by name, numbers in a name read as numbers and letters in either case alike`() {
        assertEquals(listOf("Bonus", "cd 1", "CD 2", "CD 10"), names(FolderOrder.sorted(music4.subdirs, descending = false)))
    }

    @Test
    fun `their own arrow turns the folders round`() {
        assertEquals(listOf("CD 10", "CD 2", "cd 1", "Bonus"), names(FolderOrder.sorted(music4.subdirs, descending = true)))
    }

    @Test
    fun `a letter with an accent goes with the same letter without one`() {
        val top = tree(music, "Zazie/a.mp3", "Édith Piaf/a.mp3", "elvis/a.mp3", "Eagles/a.mp3", "Ángel/a.mp3", "Bach/a.mp3", "Ève 2/a.mp3", "Eve 10/a.mp3")
        assertEquals(
            listOf("Ángel", "Bach", "Eagles", "Édith Piaf", "elvis", "Ève 2", "Eve 10", "Zazie"),
            names(FolderOrder.sorted(top.subdirs, descending = false)),
        )
        assertEquals(
            listOf("Zazie", "Eve 10", "Ève 2", "elvis", "Édith Piaf", "Eagles", "Bach", "Ángel"),
            names(FolderOrder.sorted(top.subdirs, descending = true)),
        )
    }

    @Test
    fun `names that differ only by an accent or a capital still come out in one order`() {
        val top = tree(music, "étude/a.mp3", "Etude/a.mp3", "Étude/a.mp3", "etude/a.mp3")
        val once = names(FolderOrder.sorted(top.subdirs, descending = false))
        assertEquals(once, names(FolderOrder.sorted(top.subdirs.reversed(), descending = false)))
        assertEquals(once.reversed(), names(FolderOrder.sorted(top.subdirs, descending = true)))
        assertEquals(4, once.distinct().size)
    }

    @Test
    fun `japanese voicing marks are not taken for accents`() {
        // が is not か: the mark is combining, as an accent is, and a name keeps it.
        val top = tree(music, "がっこう/a.mp3", "かっこう/a.mp3", "かっこう 2/a.mp3")
        assertEquals(listOf("かっこう", "かっこう 2", "がっこう"), names(FolderOrder.sorted(top.subdirs, descending = false)))
    }

    @Test
    fun `the flat list goes by the names it shows, and folders of the same name by their path`() {
        val top = tree(music, "Zed/Album/CD1/a.mp3", "Abba/Gold/CD1/a.mp3", "Abba/Gold/CD2/a.mp3", "Mono/a.mp3")
        val sorted = FolderOrder.sorted(top.getFlattenedSubdirs(), descending = false)
        assertEquals(
            listOf("$music/Abba/Gold/CD1", "$music/Zed/Album/CD1", "$music/Abba/Gold/CD2", "$music/Mono"),
            sorted.map { it.getFullPath() },
        )
    }

    @Test
    fun `a squashed folder goes by the path its row shows`() {
        // Artist folders that each hold one album folder show as "Artist/Album".
        val top = tree(music, "Beta/Second/a.mp3", "Alpha/First/a.mp3", "Alpha2/a.mp3")
        assertEquals(listOf("Alpha/First", "Alpha2", "Beta/Second"), names(FolderOrder.sorted(top.subdirs, descending = false)))
    }

    private val artist = "$music/Artist"

    /**
     * In the order the library holds them. The second album was added later, its songs changed
     * earlier and released later, so a date sort that moved folders would put it first.
     */
    private val artistSongs = listOf(
        song("$artist/Second album/2 Bee.mp3", track = 2, added = 20),
        song("$artist/First album/CD 2/1 Opener two.mp3", track = 1, disc = 2, added = 3),
        song("$artist/Single.mp3", added = 9),
        song("$artist/First album/CD 1/2 Second.mp3", track = 2, disc = 1, added = 2),
        song("$artist/Second album/1 Ay.mp3", track = 1, added = 21),
        song("$artist/First album/CD 1/1 First.mp3", track = 1, disc = 1, added = 1),
    )

    private fun played(sort: FolderSongSortType, songsDescending: Boolean, foldersDescending: Boolean) =
        FolderOrder.playOrder("$artist/", artistSongs, sort, songsDescending, foldersDescending)

    @Test
    fun `a folder played whole goes its own songs first, then folder by folder by name, each folder's songs in their sort`() {
        fun titles(songs: List<Song>) = songs.map { it.song.title }
        assertEquals(
            listOf("Single", "1 First", "2 Second", "1 Opener two", "1 Ay", "2 Bee"),
            titles(played(FolderSongSortType.TRACK_NUMBER, songsDescending = false, foldersDescending = false)),
        )
        // The folders from Z, each album still from its first track.
        assertEquals(
            listOf("Single", "1 Ay", "2 Bee", "1 Opener two", "1 First", "2 Second"),
            titles(played(FolderSongSortType.TRACK_NUMBER, songsDescending = false, foldersDescending = true)),
        )
        // Each album from its last track, the folders still from A.
        assertEquals(
            listOf("Single", "2 Second", "1 First", "1 Opener two", "2 Bee", "1 Ay"),
            titles(played(FolderSongSortType.TRACK_NUMBER, songsDescending = true, foldersDescending = false)),
        )
    }

    @Test
    fun `whatever the songs are sorted by and whichever way, the folders keep to their name and their own arrow`() {
        val aToZ = listOf(artist, "$artist/First album/CD 1", "$artist/First album/CD 2", "$artist/Second album")
        for (sort in FolderSongSortType.entries) for (songsDescending in listOf(false, true)) for (foldersDescending in listOf(false, true)) {
            val case = "$sort, songs descending $songsDescending, folders descending $foldersDescending"
            val order = played(sort, songsDescending, foldersDescending)
            val folders = order.map { it.song.localPath!!.substringBeforeLast('/') }.distinct()
            // The folder's own songs first, then the folders under it.
            assertEquals(case, listOf(artist) + (if (foldersDescending) aToZ.drop(1).reversed() else aToZ.drop(1)), folders)
            // Each folder's songs are in the songs' sort, as the folder's own screen shows them.
            for (folder in folders) {
                val inIt = artistSongs.filter { it.song.localPath!!.substringBeforeLast('/') == folder }
                assertEquals(case, FolderSongOrder.sorted(inIt, sort, songsDescending), order.filter { it in inIt })
            }
        }
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(sources, path).readText()

    @Test
    fun `the Folders tab lists its folders by their own arrow and its songs by theirs, and plays a folder in that order`() {
        val screen = source("ui/screens/library/FolderScreen.kt")
        assertTrue(screen.contains("FolderSongOrder.sorted(block.library(currDir.files), sortType, sortDescending)"))
        assertTrue(screen.contains("LaunchedEffect(folderSortDescending, currDir, flatSubfolders) {"))
        assertTrue(screen.contains("addAll(FolderOrder.sorted(folders, folderSortDescending))"))
        assertTrue(screen.contains("items = shownFolders,"))
        val menu = source("ui/menu/FolderMenu.kt")
        assertTrue(menu.contains("FolderOrder.playOrder(dir, dbSongs, sortType, sortDescending, folderSortDescending)"))
    }

    @Test
    fun `the folders' arrow is kept in a preference of its own, A to Z until it is turned`() {
        val keys = source("constants/PreferenceKeys.kt")
        assertTrue(keys.contains("val FolderSortDescendingKey = booleanPreferencesKey(\"folderSortDescending\")"))
        assertEquals(1, Regex("\"folderSortDescending\"").findAll(keys).count())
        // The screen and the menu read it alike, or a folder would play in another order than it shows.
        val read = "val (folderSortDescending) = rememberPreference(FolderSortDescendingKey, false)"
        assertTrue(source("ui/menu/FolderMenu.kt").contains(read))
        val screen = source("ui/screens/library/FolderScreen.kt")
        assertTrue(screen.contains("val (folderSortDescending, onFolderSortDescendingChange) = rememberPreference(FolderSortDescendingKey, false)"))
        // A tap writes it, turned round.
        assertTrue(screen.contains("FolderOrderArrow(folderSortDescending, onFolderSortDescendingChange)"))
        assertTrue(screen.contains("onClick = { onDescendingChange(!descending) }"))
        // Nothing reads the songs' arrow for the folders any more.
        assertFalse(screen.contains("FolderOrder.sorted(folders, sortType"))
    }

    @Test
    fun `the folders' arrow sits after the songs' sort, which is left as it was, and before the song count`() {
        val screen = source("ui/screens/library/FolderScreen.kt")
        val header = screen.indexOf("SortHeader(")
        val arrow = screen.indexOf("FolderOrderArrow(folderSortDescending")
        val count = screen.indexOf("pluralStringResource(R.plurals.n_song, subDirSongCount, subDirSongCount)")
        assertTrue(header in 1 until arrow)
        assertTrue(arrow < count)
        val songsSort = screen.substring(header, arrow)
        for (argument in listOf("sortType = sortType,", "sortDescending = sortDescending,", "onSortTypeChange = onSortTypeChange,", "onSortDescendingChange = onSortDescendingChange,")) {
            assertTrue(argument, songsSort.contains(argument))
        }
        // Only where there are folders to turn round, and not over search results, which have none.
        assertTrue(Regex("""if \(!isSearching && currDir\.subdirs\.isNotEmpty\(\)\) \{\s*FolderOrderArrow\(""").containsMatchIn(screen))
    }

    @Test
    fun `the folders' arrow says which way they go, in English and in French`() {
        val screen = source("ui/screens/library/FolderScreen.kt")
        assertTrue(screen.contains("stringResource(if (descending) R.string.folder_order_z_to_a else R.string.folder_order_a_to_z)"))
        assertTrue(screen.contains(".semantics { contentDescription = name }"))
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for ((file, said) in listOf(
            english to listOf("folder_order_a_to_z" to "Folders from A to Z", "folder_order_z_to_a" to "Folders from Z to A"),
            french to listOf("folder_order_a_to_z" to "Dossiers de A à Z", "folder_order_z_to_a" to "Dossiers de Z à A"),
        )) {
            for ((name, words) in said) assertTrue("$name: $words", file.contains("<string name=\"$name\">$words</string>"))
        }
    }
}
