/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.LocalFolderDates
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Issue #39, second report: in the Folders tab the folders kept no order whatever the sort, and
 * a folder played from its menu came in the order the library holds its songs.
 */
class FolderOrderTest {

    private val music = "/storage/emulated/0/Music"

    private fun song(path: String, track: Int? = null, disc: Int? = null) = Song(
        SongEntity(
            id = "LS" + path.hashCode(),
            title = path.substringAfterLast('/').substringBeforeLast('.'),
            isLocal = true,
            localPath = path,
            trackNumber = track,
            discNumber = disc,
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
        assertEquals(listOf("Bonus", "cd 1", "CD 2", "CD 10"), names(FolderOrder.sorted(music4.subdirs, FolderSongSortType.NAME, false)))
    }

    @Test
    fun `the arrow turns the folders round too`() {
        assertEquals(listOf("CD 10", "CD 2", "cd 1", "Bonus"), names(FolderOrder.sorted(music4.subdirs, FolderSongSortType.NAME, true)))
    }

    @Test
    fun `sorts with no meaning for a folder put the folders by name`() {
        for (sort in listOf(FolderSongSortType.TRACK_NUMBER, FolderSongSortType.ARTIST, FolderSongSortType.PLAY_COUNT)) {
            assertEquals("$sort", listOf("Bonus", "cd 1", "CD 2", "CD 10"), names(FolderOrder.sorted(music4.subdirs, sort, false)))
            assertEquals("$sort", listOf("CD 10", "CD 2", "cd 1", "Bonus"), names(FolderOrder.sorted(music4.subdirs, sort, true)))
        }
    }

    @Test
    fun `the flat list goes by the names it shows, and folders of the same name by their path`() {
        val top = tree(music, "Zed/Album/CD1/a.mp3", "Abba/Gold/CD1/a.mp3", "Abba/Gold/CD2/a.mp3", "Mono/a.mp3")
        val sorted = FolderOrder.sorted(top.getFlattenedSubdirs(), FolderSongSortType.NAME, false)
        assertEquals(
            listOf("$music/Abba/Gold/CD1", "$music/Zed/Album/CD1", "$music/Abba/Gold/CD2", "$music/Mono"),
            sorted.map { it.getFullPath() },
        )
    }

    @Test
    fun `a squashed folder goes by the path its row shows`() {
        // Artist folders that each hold one album folder show as "Artist/Album".
        val top = tree(music, "Beta/Second/a.mp3", "Alpha/First/a.mp3", "Alpha2/a.mp3")
        assertEquals(listOf("Alpha/First", "Alpha2", "Beta/Second"), names(FolderOrder.sorted(top.subdirs, FolderSongSortType.NAME, false)))
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
        val added = FolderOrder.newestDates(dateRows, FolderSongSortType.CREATE_DATE)
        assertEquals(day(9), added["$music/New"])
        assertEquals(day(3), added["$music/New/CD 1"])
        assertEquals(day(9), added[music])
        val released = FolderOrder.newestDates(dateRows, FolderSongSortType.RELEASE_DATE)
        // A year with no full date counts from its first day, as it does for songs.
        assertEquals(LocalDateTime.of(1999, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), released["$music/Old"])
        assertEquals(day(5), released["$music/New"])
        assertEquals(emptyMap<String, Long>(), FolderOrder.newestDates(dateRows, FolderSongSortType.NAME))
    }

    @Test
    fun `the date sorts put folders by the newest date of their songs, the arrow honoured`() {
        val top = tree(music, "Old/a.mp3", "New/CD 1/a.mp3", "New/CD 2/a.mp3", "Middle/a.mp3", "Undated/a.mp3")
        val added = FolderOrder.newestDates(dateRows, FolderSongSortType.CREATE_DATE)
        assertEquals(
            listOf("Undated", "Old", "Middle", "New"),
            names(FolderOrder.sorted(top.subdirs, FolderSongSortType.CREATE_DATE, false, added)),
        )
        assertEquals(
            listOf("New", "Middle", "Old", "Undated"),
            names(FolderOrder.sorted(top.subdirs, FolderSongSortType.CREATE_DATE, true, added)),
        )
        val modified = FolderOrder.newestDates(dateRows, FolderSongSortType.MODIFIED_DATE)
        assertEquals(
            listOf("Old", "Middle", "New", "Undated"),
            names(FolderOrder.sorted(top.subdirs, FolderSongSortType.MODIFIED_DATE, true, modified)),
        )
    }

    @Test
    fun `folders with the same date go by name`() {
        val top = tree(music, "B/a.mp3", "A/a.mp3", "C/a.mp3")
        val same = mapOf("$music/A" to 5L, "$music/B" to 5L, "$music/C" to 5L)
        assertEquals(listOf("A", "B", "C"), names(FolderOrder.sorted(top.subdirs, FolderSongSortType.CREATE_DATE, true, same)))
    }

    @Test
    fun `a folder played whole goes its own songs first, then folder by folder, each in the order of the sort`() {
        val artist = "$music/Artist"
        // In the order the library holds them.
        val songs = listOf(
            song("$artist/Second album/2 Bee.mp3", track = 2),
            song("$artist/First album/CD 2/1 Opener two.mp3", track = 1, disc = 2),
            song("$artist/Single.mp3"),
            song("$artist/First album/CD 1/2 Second.mp3", track = 2, disc = 1),
            song("$artist/Second album/1 Ay.mp3", track = 1),
            song("$artist/First album/CD 1/1 First.mp3", track = 1, disc = 1),
        )
        fun played(sort: FolderSongSortType, descending: Boolean) =
            FolderOrder.playOrder("$artist/", songs, sort, descending).map { it.song.title }
        assertEquals(
            listOf("Single", "1 First", "2 Second", "1 Opener two", "1 Ay", "2 Bee"),
            played(FolderSongSortType.TRACK_NUMBER, false),
        )
        // Turned round as the screens show it: the folders from Z, each from its last track.
        assertEquals(
            listOf("Single", "2 Bee", "1 Ay", "1 Opener two", "2 Second", "1 First"),
            played(FolderSongSortType.TRACK_NUMBER, true),
        )
    }
}
