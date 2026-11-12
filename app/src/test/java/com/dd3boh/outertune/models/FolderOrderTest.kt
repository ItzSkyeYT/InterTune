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
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Issue #39, second report: in the Folders tab the folders kept no order whatever the sort, and
 * a folder played from its menu came in the order the library holds its songs. Then they followed
 * the songs' sort and arrow, and its reporter asked for folders by name with an arrow of their own
 * (8 Nov 2026): sorted by track number with the arrow down, the albums went from Z. That left the
 * date orders, which 0.12.6 to 0.12.9 gave folders, with nothing to sort (a tester, 11 Nov 2026:
 * "date added ... in folders doesnt work"): folders now have an order of their own, name or a date.
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
        // Katakana the same way: カ's names, then ガ's. Left apart from its letter, ガ's mark comes
        // before every katakana letter and put each ガ name before the カ ones.
        val katakana = tree(music, "ガルニデリア/a.mp3", "キリンジ/a.mp3", "カメラ/a.mp3", "ガム/a.mp3", "カネコアヤノ/a.mp3")
        assertEquals(
            listOf("カネコアヤノ", "カメラ", "ガム", "ガルニデリア", "キリンジ"),
            names(FolderOrder.sorted(katakana.subdirs, descending = false)),
        )
    }

    @Test
    fun `only a Latin letter's accent is set aside, and Й stays a letter of its own after И`() {
        // Й is И with a breve, as É is E with an accent. É goes among the E's, whether the name
        // holds it whole or as an E and its accent apart, as a Mac writes it; Й goes after И.
        val top = tree(music, "Йога/a.mp3", "Zazie/a.mp3", "Ия/a.mp3", "E\u0301tienne/a.mp3", "Иван/a.mp3", "Elvis/a.mp3", "Édith Piaf/a.mp3", "Eve 10/a.mp3")
        assertEquals(
            listOf("Édith Piaf", "Elvis", "E\u0301tienne", "Eve 10", "Zazie", "Иван", "Ия", "Йога"),
            names(FolderOrder.sorted(top.subdirs, descending = false)),
        )
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
    fun `names made ready once sort exactly as names compared afresh each time`() {
        // the way before: every comparison set the accents aside again
        val afresh = compareBy<DirectoryTree, String>(NaturalOrder) { FolderOrder.shownName(it) }.thenBy { it.getFullPath() }
        val words = listOf(
            "Édith", "edith", "Zoë", "Ångström", "CD 2", "CD 10", "cd 1", "カ", "ガ", "Ия", "Йога",
            "Ölfass", "Oslo", "Abba", "abba", "Ça", "Ca", "Ćevapi", "Ü", "U", "Ñandú", "Nada",
        )
        val random = java.util.Random(7)
        val files = (0 until 2000).map { i -> "${words[random.nextInt(words.size)]} ${random.nextInt(40)}/x$i/a.mp3" }
        val folders = tree(music, *files.toTypedArray()).getFlattenedSubdirs()
        for (descending in listOf(false, true)) {
            val before = folders.sortedWith(if (descending) afresh.reversed() else afresh).map { it.getFullPath() }
            assertEquals(before, FolderOrder.sorted(folders, descending).map { it.getFullPath() })
        }
        // how long each way takes, for the log (not a pass mark: a test machine's time says little)
        val accented = tree(music, *(0 until 2000).map { i -> "${words[i % words.size]} ${i / words.size}/a.mp3" }.toTypedArray()).subdirs
        assertEquals(accented.sortedWith(afresh), FolderOrder.sorted(accented, false))
        repeat(3) { accented.sortedWith(afresh); FolderOrder.sorted(accented, false) }
        val t0 = System.nanoTime(); accented.sortedWith(afresh)
        val t1 = System.nanoTime(); FolderOrder.sorted(accented, false)
        val t2 = System.nanoTime()
        println("FolderOrder ${accented.size} folders named with accents: afresh ${(t1 - t0) / 1000} us, made ready once ${(t2 - t1) / 1000} us")
    }

    @Test
    fun `a squashed folder goes by the path its row shows`() {
        // Artist folders that each hold one album folder show as "Artist/Album".
        val top = tree(music, "Beta/Second/a.mp3", "Alpha/First/a.mp3", "Alpha2/a.mp3")
        assertEquals(listOf("Alpha/First", "Alpha2", "Beta/Second"), names(FolderOrder.sorted(top.subdirs, descending = false)))
    }

    private fun day(day: Int) = LocalDateTime.of(2026, 10, day, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val dateRows = listOf(
        LocalFolderDates("$music/Old/", added = day(1), modified = day(20), released = null, year = 1999),
        LocalFolderDates("$music/New/CD 1/", added = day(3), modified = day(2), released = day(5), year = 2026),
        LocalFolderDates("$music/New/CD 2/", added = day(9), modified = null, released = null, year = null),
        LocalFolderDates("$music/Middle/", added = day(5), modified = day(10), released = null, year = 2010),
    )

    @Test
    fun `a folder's date is the newest among the songs in it and in the folders under it`() {
        val added = FolderOrder.newestDates(dateRows, FolderSortType.CREATE_DATE)
        assertEquals(day(9), added["$music/New"])
        assertEquals(day(3), added["$music/New/CD 1"])
        assertEquals(day(9), added[music])
        val released = FolderOrder.newestDates(dateRows, FolderSortType.RELEASE_DATE)
        // A year with no full date counts from its first day, as it does for songs.
        assertEquals(LocalDateTime.of(1999, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), released["$music/Old"])
        assertEquals(day(5), released["$music/New"])
        // A name has no date.
        assertEquals(emptyMap<String, Long>(), FolderOrder.newestDates(dateRows, FolderSortType.NAME))
    }

    @Test
    fun `the date orders put folders by the newest date of their songs, the arrow honoured`() {
        val top = tree(music, "Old/a.mp3", "New/CD 1/a.mp3", "New/CD 2/a.mp3", "Middle/a.mp3", "Undated/a.mp3")
        val added = FolderOrder.newestDates(dateRows, FolderSortType.CREATE_DATE)
        // Oldest first with the arrow up; a folder with no date first, as a song with none is.
        assertEquals(
            listOf("Undated", "Old", "Middle", "New"),
            names(FolderOrder.sorted(top.subdirs, descending = false, sortType = FolderSortType.CREATE_DATE, dates = added)),
        )
        assertEquals(
            listOf("New", "Middle", "Old", "Undated"),
            names(FolderOrder.sorted(top.subdirs, descending = true, sortType = FolderSortType.CREATE_DATE, dates = added)),
        )
        val modified = FolderOrder.newestDates(dateRows, FolderSortType.MODIFIED_DATE)
        assertEquals(
            listOf("Old", "Middle", "New", "Undated"),
            names(FolderOrder.sorted(top.subdirs, descending = true, sortType = FolderSortType.MODIFIED_DATE, dates = modified)),
        )
        val released = FolderOrder.newestDates(dateRows, FolderSortType.RELEASE_DATE)
        assertEquals(
            listOf("New", "Middle", "Old", "Undated"),
            names(FolderOrder.sorted(top.subdirs, descending = true, sortType = FolderSortType.RELEASE_DATE, dates = released)),
        )
    }

    @Test
    fun `each order the folders offer gives an order of its own, and the name one is where they start`() {
        // Three folders whose names, newest additions, newest file dates and newest releases all run differently.
        val top = tree(music, "Blue/a.mp3", "Night/a.mp3", "Quiet/a.mp3")
        val rows = listOf(
            LocalFolderDates("$music/Blue/", added = day(5), modified = day(25), released = day(9), year = null),
            LocalFolderDates("$music/Night/", added = day(8), modified = day(20), released = day(3), year = null),
            LocalFolderDates("$music/Quiet/", added = day(2), modified = day(22), released = day(12), year = null),
        )
        assertEquals(setOf(FolderSortType.NAME, FolderSortType.CREATE_DATE, FolderSortType.MODIFIED_DATE, FolderSortType.RELEASE_DATE), FolderSortType.entries.toSet())
        val expected = mapOf(
            FolderSortType.NAME to listOf("Blue", "Night", "Quiet"),
            FolderSortType.CREATE_DATE to listOf("Quiet", "Blue", "Night"),
            FolderSortType.MODIFIED_DATE to listOf("Night", "Quiet", "Blue"),
            FolderSortType.RELEASE_DATE to listOf("Night", "Blue", "Quiet"),
        )
        for (sort in FolderSortType.entries) {
            val dates = FolderOrder.newestDates(rows, sort)
            assertEquals("$sort", expected.getValue(sort), names(FolderOrder.sorted(top.subdirs, descending = false, sortType = sort, dates = dates)))
            assertEquals("$sort turned round", expected.getValue(sort).reversed(), names(FolderOrder.sorted(top.subdirs, descending = true, sortType = sort, dates = dates)))
            // Whatever order the library hands the folders over in.
            assertEquals("$sort, handed over backwards", expected.getValue(sort), names(FolderOrder.sorted(top.subdirs.reversed(), descending = false, sortType = sort, dates = dates)))
        }
    }

    @Test
    fun `folders with the same date go by name, whichever way the arrow points`() {
        val top = tree(music, "B/a.mp3", "A/a.mp3", "C/a.mp3")
        val same = mapOf("$music/A" to 5L, "$music/B" to 5L, "$music/C" to 5L)
        for (descending in listOf(false, true)) {
            assertEquals(listOf("A", "B", "C"), names(FolderOrder.sorted(top.subdirs, descending, FolderSortType.CREATE_DATE, same)))
        }
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

    private fun played(
        sort: FolderSongSortType,
        songsDescending: Boolean,
        foldersDescending: Boolean,
        foldersSort: FolderSortType = FolderSortType.NAME,
        dates: Map<String, Long> = emptyMap(),
    ) = FolderOrder.playOrder("$artist/", artistSongs, sort, songsDescending, foldersDescending, foldersSort, dates)

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
    fun `a folder played whole follows the folders' date order too`() {
        fun titles(songs: List<Song>) = songs.map { it.song.title }
        val rows = listOf(
            LocalFolderDates("$artist/", added = day(9), modified = null, released = null, year = null),
            LocalFolderDates("$artist/First album/CD 1/", added = day(2), modified = null, released = null, year = null),
            LocalFolderDates("$artist/First album/CD 2/", added = day(3), modified = null, released = null, year = null),
            LocalFolderDates("$artist/Second album/", added = day(21), modified = null, released = null, year = null),
        )
        val dates = FolderOrder.newestDates(rows, FolderSortType.CREATE_DATE)
        // Newest first: the second album (added last), then the first album, whose CD 2 came after its CD 1.
        assertEquals(
            listOf("Single", "1 Ay", "2 Bee", "1 Opener two", "1 First", "2 Second"),
            titles(played(FolderSongSortType.TRACK_NUMBER, false, foldersDescending = true, foldersSort = FolderSortType.CREATE_DATE, dates = dates)),
        )
        // Oldest first: the first album's CD 1, its CD 2, then the second album.
        assertEquals(
            listOf("Single", "1 First", "2 Second", "1 Opener two", "1 Ay", "2 Bee"),
            titles(played(FolderSongSortType.TRACK_NUMBER, false, foldersDescending = false, foldersSort = FolderSortType.CREATE_DATE, dates = dates)),
        )
    }

    @Test
    fun `whatever the songs are sorted by and whichever way, the folders keep to their own order and arrow`() {
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
    fun `the Folders tab lists its folders by their own order and its songs by theirs, and plays a folder in that order`() {
        val screen = source("ui/screens/library/FolderScreen.kt")
        assertTrue(screen.contains("FolderSongOrder.sorted(block.library(currDir.files), sortType, sortDescending)"))
        assertTrue(screen.contains("LaunchedEffect(folderSortType, folderSortDescending, currDir, flatSubfolders) {"))
        assertTrue(screen.contains("val dates = if (FolderOrder.byDate(folderSortType)) viewModel.folderDates(folderSortType) else emptyMap()"))
        assertTrue(screen.contains("addAll(FolderOrder.sorted(folders, folderSortDescending, folderSortType, dates))"))
        assertTrue(screen.contains("items = shownFolders,"))
        val menu = source("ui/menu/FolderMenu.kt")
        assertTrue(menu.contains("FolderOrder.playOrder(dir, dbSongs, sortType, sortDescending, folderSortDescending, folderSortType, dates)"))
        assertTrue(menu.contains("val dates = if (FolderOrder.byDate(folderSortType)) FolderOrder.newestDates(database.localFolderDates(dir), folderSortType) else emptyMap()"))
    }

    @Test
    fun `the folders' order and arrow are kept in preferences of their own, A to Z until they are changed`() {
        val keys = source("constants/PreferenceKeys.kt")
        assertTrue(keys.contains("val FolderSortDescendingKey = booleanPreferencesKey(\"folderSortDescending\")"))
        assertTrue(keys.contains("val FolderSortTypeKey = stringPreferencesKey(\"folderSortType\")"))
        assertEquals(1, Regex("\"folderSortDescending\"").findAll(keys).count())
        assertEquals(1, Regex("\"folderSortType\"").findAll(keys).count())
        // The screen and the menu read them alike, or a folder would play in another order than it shows.
        val menu = source("ui/menu/FolderMenu.kt")
        assertTrue(menu.contains("val (folderSortType) = rememberEnumPreference(FolderSortTypeKey, FolderSortType.NAME)"))
        assertTrue(menu.contains("val (folderSortDescending) = rememberPreference(FolderSortDescendingKey, false)"))
        val screen = source("ui/screens/library/FolderScreen.kt")
        assertTrue(screen.contains("val (folderSortType, onFolderSortTypeChange) = rememberEnumPreference(FolderSortTypeKey, FolderSortType.NAME)"))
        assertTrue(screen.contains("val (folderSortDescending, onFolderSortDescendingChange) = rememberPreference(FolderSortDescendingKey, false)"))
        // A choice writes it, and the arrow writes the turned-round value (SortHeader does both).
        assertTrue(screen.contains("onSortTypeChange = onFolderSortTypeChange,"))
        assertTrue(screen.contains("onSortDescendingChange = onFolderSortDescendingChange,"))
        // Nothing reads the songs' choice for the folders.
        assertFalse(screen.contains("FolderOrder.sorted(folders, sortType"))
    }

    @Test
    fun `the folders' choice sits after the songs', with a folder in front, and before the song count`() {
        val screen = source("ui/screens/library/FolderScreen.kt")
        val songs = screen.indexOf("SortHeader(\n                                        sortType = sortType,")
        val folders = screen.indexOf("SortHeader(\n                                        sortType = folderSortType,")
        val count = screen.indexOf("pluralStringResource(R.plurals.n_song, subDirSongCount, subDirSongCount)")
        assertTrue(songs in 1 until folders)
        assertTrue(folders < count)
        // The songs' choice is left as it was.
        val songsSort = screen.substring(songs, folders)
        for (argument in listOf("sortType = sortType,", "sortDescending = sortDescending,", "onSortTypeChange = onSortTypeChange,", "onSortDescendingChange = onSortDescendingChange,")) {
            assertTrue(argument, songsSort.contains(argument))
        }
        // Each choice only where there is something for it to sort: the songs' where the folder has
        // songs of its own (or over search results, which they now sort), the folders' where it has
        // folders and the rows are not search results. Over a list of folders alone the songs' sort
        // had nothing to sort, and looked broken.
        assertTrue(screen.contains("val showSongSort = isSearching || currDir.files.isNotEmpty()"))
        assertTrue(screen.contains("val showFolderSort = !isSearching && currDir.subdirs.isNotEmpty()"))
        assertTrue(Regex("""if \(showSongSort\) \{\s*SortHeader\(""").containsMatchIn(screen))
        assertTrue(Regex("""if \(showFolderSort\) \{\s*Icon\(\s*Icons\.Rounded\.Folder,""").containsMatchIn(screen))
    }

    @Test
    fun `the folders offer a name and the three dates, each with words, and no order that means nothing for a folder`() {
        val screen = source("ui/screens/library/FolderScreen.kt")
        val folders = screen.substring(screen.indexOf("sortType = folderSortType,"))
        for ((type, words) in listOf(
            "NAME" to "sort_by_name", "CREATE_DATE" to "sort_by_create_date",
            "MODIFIED_DATE" to "sort_by_date_modified", "RELEASE_DATE" to "sort_by_date_released",
        )) {
            assertTrue(type, folders.contains("FolderSortType.$type -> R.string.$words"))
        }
        // The songs' own (artist, play count, track number) are not offered to folders.
        val enum = source("constants/Settings.kt")
        assertTrue(enum.contains("enum class FolderSortType {\n    NAME, CREATE_DATE, MODIFIED_DATE, RELEASE_DATE\n}"))
        // The arrow's two sentences went with the arrow: "Folders from A to Z" is wrong for a date.
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertFalse(english.contains("folder_order_a_to_z") || english.contains("folder_order_z_to_a"))
        assertFalse(french.contains("folder_order_a_to_z") || french.contains("folder_order_z_to_a"))
    }
}
