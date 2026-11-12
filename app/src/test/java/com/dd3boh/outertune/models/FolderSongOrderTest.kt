/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.utils.scanners.TrackTags
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

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
    fun `songs that tie on another sort keep the album's order, whichever way the list is turned`() {
        // None of them has an artist, a release date or a play: they all tie. They used to keep
        // the order the library handed them over in, which follows their random ids.
        val albumOrder = listOf("One", "Two", "Three", "Ten", "Second disc opener", "Bonus a", "Bonus b")
        for (sort in listOf(FolderSongSortType.ARTIST, FolderSongSortType.RELEASE_DATE, FolderSongSortType.PLAY_COUNT)) {
            for (descending in listOf(false, true)) {
                assertEquals("$sort, descending $descending", albumOrder, titles(FolderSongOrder.sorted(folder, sort, descending)))
            }
        }
    }

    @Test
    fun `two songs alike in every tag go by their file, wherever they come from`() {
        fun copy(path: String) = Song(
            SongEntity(id = "LS" + path.hashCode(), title = "Intro", isLocal = true, localPath = path, trackNumber = 1),
            artists = emptyList(),
        )
        val a = copy("/m/Album/a.mp3")
        val b = copy("/m/Album/b.mp3")
        val expected = listOf("/m/Album/a.mp3", "/m/Album/b.mp3")
        for (songs in listOf(listOf(a, b), listOf(b, a))) {
            assertEquals(expected, FolderSongOrder.sorted(songs, FolderSongSortType.TRACK_NUMBER, false).map { it.song.localPath })
            assertEquals(expected, FolderSongOrder.sorted(songs, FolderSongSortType.NAME, false).map { it.song.localPath })
        }
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

    /**
     * The Folders tab's report of 11 Nov 2026 ("sorting by artist, name, date added, track number
     * etc in folders doesnt work"): every order the songs' sort offers has to put a folder's songs
     * in order, both ways. Songs with a real value for each tag (the tests above only had ties for
     * the artist, the dates and the plays), shuffled as the library hands a folder over.
     */
    private fun tagged(
        title: String,
        artist: String,
        track: Int,
        added: Int,
        modified: Int,
        released: Int,
        plays: Int,
    ) = Song(
        song = SongEntity(
            id = "LS$title",
            title = title,
            isLocal = true,
            localPath = "/storage/emulated/0/Music/Night Drive/$title.mp3",
            trackNumber = track,
            inLibrary = LocalDateTime.of(2026, 11, added, 12, 0),
            dateModified = LocalDateTime.of(2026, 5, modified, 12, 0),
            date = LocalDateTime.of(released, 1, 1, 0, 0),
        ),
        artists = listOf(ArtistEntity(id = "LA$artist", name = artist, isLocal = true)),
        playCount = if (plays == 0) emptyList() else listOf(PlayCountEntity("LS$title", 2026, 11, plays)),
    )

    // Every column runs in an order of its own: by title Afterglow, Cold, Harbor, Midnight, Zero;
    // by artist Aki, Bo, Cleo, Mira, Zed; by track 1 to 5; by the day added Cold, Afterglow,
    // Midnight, Harbor, Zero; and so on. No two columns run alike, so an order that is not the one
    // asked for shows.
    private val nightDrive = listOf(
        tagged("Zero Hour", "Mira Tolland", track = 3, added = 5, modified = 3, released = 2019, plays = 4),
        tagged("Afterglow", "Bo Venn", track = 1, added = 2, modified = 1, released = 2021, plays = 1),
        tagged("Midnight Run", "Aki Sato", track = 4, added = 3, modified = 5, released = 2017, plays = 9),
        tagged("Cold Lights", "Zed Harper", track = 2, added = 1, modified = 2, released = 2020, plays = 0),
        tagged("Harbor Fog", "Cleo Marsh", track = 5, added = 4, modified = 4, released = 2018, plays = 2),
    )

    private val ascending = mapOf(
        FolderSongSortType.NAME to listOf("Afterglow", "Cold Lights", "Harbor Fog", "Midnight Run", "Zero Hour"),
        FolderSongSortType.ARTIST to listOf("Midnight Run", "Afterglow", "Harbor Fog", "Zero Hour", "Cold Lights"),
        FolderSongSortType.TRACK_NUMBER to listOf("Afterglow", "Cold Lights", "Zero Hour", "Midnight Run", "Harbor Fog"),
        FolderSongSortType.CREATE_DATE to listOf("Cold Lights", "Afterglow", "Midnight Run", "Harbor Fog", "Zero Hour"),
        FolderSongSortType.MODIFIED_DATE to listOf("Afterglow", "Cold Lights", "Zero Hour", "Harbor Fog", "Midnight Run"),
        FolderSongSortType.RELEASE_DATE to listOf("Midnight Run", "Harbor Fog", "Zero Hour", "Cold Lights", "Afterglow"),
        FolderSongSortType.PLAY_COUNT to listOf("Cold Lights", "Afterglow", "Harbor Fog", "Zero Hour", "Midnight Run"),
    )

    @Test
    fun `every order the songs' sort offers puts a folder's songs in order`() {
        assertEquals(FolderSongSortType.entries.toSet(), ascending.keys)
        for (sort in FolderSongSortType.entries) {
            assertEquals("$sort ascending", ascending.getValue(sort), titles(FolderSongOrder.sorted(nightDrive, sort, descending = false)))
        }
    }

    @Test
    fun `every order the songs' sort offers runs the other way when its arrow is turned`() {
        for (sort in FolderSongSortType.entries) {
            val expected = ascending.getValue(sort).reversed()
            assertEquals("$sort descending", expected, titles(FolderSongOrder.sorted(nightDrive, sort, descending = true)))
            // Whatever order the library hands the folder over in.
            assertEquals("$sort descending, handed over backwards", expected, titles(FolderSongOrder.sorted(nightDrive.reversed(), sort, descending = true)))
        }
    }

    @Test
    fun `a song with no value for the order goes first, and last when the order is turned round`() {
        val bare = Song(SongEntity(id = "LSbare", title = "Bare", localPath = "/m/Bare.mp3", isLocal = true), artists = emptyList())
        for (sort in listOf(FolderSongSortType.ARTIST, FolderSongSortType.CREATE_DATE, FolderSongSortType.MODIFIED_DATE, FolderSongSortType.RELEASE_DATE)) {
            val up = titles(FolderSongOrder.sorted(nightDrive + bare, sort, descending = false))
            assertEquals("$sort ascending", "Bare", up.first())
            val down = titles(FolderSongOrder.sorted(nightDrive + bare, sort, descending = true))
            assertEquals("$sort descending", "Bare", down.last())
        }
    }
}
