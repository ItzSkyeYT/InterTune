/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.scanners.TrackTags
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #39: a folder sorted by track number came out in no order at all. These songs carry their
 * track and disc tags as the files hold them, read the way the TagLib scanner reads them, and come
 * in shuffled, as the library hands a folder over.
 */
class FolderSongOrderTest {

    private fun song(title: String, track: String?, disc: String? = null) = Song(
        song = SongEntity(
            id = "LS$title",
            title = title,
            isLocal = true,
            localPath = "/storage/emulated/0/Music/Album/$title.mp3",
            trackNumber = TrackTags.number(track),
            discNumber = TrackTags.number(disc),
        ),
        artists = emptyList(),
    )

    private val folder = listOf(
        song("Ten", "10"),
        song("Bonus b", null),
        song("Three", "3/12"),
        song("Second disc opener", "1", disc = "2/2"),
        song("Two", "2"),
        song("Bonus a", ""),
        song("One", "1", disc = "1/2"),
    )

    private fun titles(songs: List<Song>) = songs.map { it.song.title }

    @Test
    fun `track number goes disc by disc, then track, as numbers, with untagged songs last by title`() {
        assertEquals(
            listOf("One", "Two", "Three", "Ten", "Second disc opener", "Bonus a", "Bonus b"),
            titles(FolderSongOrder.sorted(folder, FolderSongSortType.TRACK_NUMBER, descending = false)),
        )
    }

    @Test
    fun `track number turned round keeps the untagged songs last`() {
        assertEquals(
            listOf("Second disc opener", "Ten", "Three", "Two", "One", "Bonus a", "Bonus b"),
            titles(FolderSongOrder.sorted(folder, FolderSongSortType.TRACK_NUMBER, descending = true)),
        )
    }

    @Test
    fun `a track 0 left by an older MediaStore scan counts as no number`() {
        // That scan read a missing tag, and "08" or "09", as 0.
        val stored = listOf(
            Song(
                SongEntity(id = "LSzero", title = "Zero", localPath = "/m/zero.mp3", trackNumber = 0),
                artists = emptyList()
            ),
            song("First", "1"),
        )
        assertEquals(
            listOf("First", "Zero"),
            titles(FolderSongOrder.sorted(stored, FolderSongSortType.TRACK_NUMBER, descending = false)),
        )
    }

    @Test
    fun `two songs on the same disc and track go by title`() {
        // A folder holding two albums has two of every track.
        val twoAlbums = listOf(song("Opener of B", "1"), song("Opener of A", "01"))
        assertEquals(
            listOf("Opener of A", "Opener of B"),
            titles(FolderSongOrder.sorted(twoAlbums, FolderSongSortType.TRACK_NUMBER, descending = false)),
        )
    }

    @Test
    fun `the other sorts are as they were`() {
        assertEquals(
            listOf("Bonus a", "Bonus b", "One", "Second disc opener", "Ten", "Three", "Two"),
            titles(FolderSongOrder.sorted(folder, FolderSongSortType.NAME, descending = false)),
        )
        assertEquals(
            listOf("Two", "Three", "Ten", "Second disc opener", "One", "Bonus b", "Bonus a"),
            titles(FolderSongOrder.sorted(folder, FolderSongSortType.NAME, descending = true)),
        )
    }
}
