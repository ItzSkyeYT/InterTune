/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * What each list hands the rule: its own songs only, a playlist's rows each a copy of its own, and
 * never a file on the phone in the library, which the app has no way to take out of it.
 */
class SavedTwicePlaceTest {

    private val day = LocalDateTime.of(2026, 10, 29, 12, 0)

    private fun song(
        id: String,
        liked: Boolean = false,
        inLibrary: LocalDateTime? = null,
        isLocal: Boolean = false,
        album: AlbumEntity? = null,
        albumName: String? = null,
        thumbnail: String? = null,
    ) = Song(
        song = SongEntity(
            id = id, title = "Misery Business", duration = 211, thumbnailUrl = thumbnail, inLibrary = inLibrary,
            isLocal = isLocal, localPath = if (isLocal) "/sdcard/Music/$id.mp3" else null, liked = liked,
            likedDate = if (liked) day else null, albumName = albumName,
        ),
        artists = listOf(ArtistEntity(id = "UCparamore", name = "Paramore")),
        album = album,
    )

    @Test
    fun `Liked hands over its liked songs, files on the phone with them`() {
        val copies = likedCopies(listOf(song("a", liked = true), song("b"), song("c", liked = true, isLocal = true)))
        assertEquals(listOf("a", "c"), copies.map { it.key })
        assertEquals(SavedTwice.Kind.FILE, copies[1].kind)
        assertEquals(day.toEpochSecond(java.time.ZoneOffset.UTC), copies[0].savedAt)
    }

    @Test
    fun `the library hands over its YouTube songs only`() {
        val copies = libraryCopies(listOf(song("a", inLibrary = day), song("b"), song("c", inLibrary = day, isLocal = true)))
        assertEquals(listOf("a"), copies.map { it.key })
    }

    @Test
    fun `a playlist's rows are copies of their own, the same song twice included`() {
        fun row(mapId: Int, song: Song) = PlaylistSong(map = PlaylistSongMap(id = mapId, playlistId = "P", songId = song.id, position = mapId), song = song)
        val s = song("a")
        val copies = playlistCopies(listOf(row(7, s), row(3, s)))
        assertEquals(listOf("7", "3"), copies.map { it.key })
        assertEquals(listOf("a", "a"), copies.map { it.songId })
        // Added first, kept first.
        assertEquals("3", SavedTwice.groups(copies).single().keptByDefault.key)
    }

    @Test
    fun `a copy names its album from the album row, else from the song, and says what it is`() {
        val riot = AlbumEntity(id = "MPREb_riot", title = "Riot!", songCount = 1, duration = 211)
        assertEquals("Riot!", song("a", album = riot, albumName = "Other").savedTwiceCopy(savedAt = null).album)
        assertEquals("Riot!", song("a", albumName = "Riot!").savedTwiceCopy(savedAt = null).album)
        val video = song("v", thumbnail = "https://i.ytimg.com/vi/aCyGvGEtOwc/hqdefault.jpg").savedTwiceCopy(savedAt = null)
        assertNull(video.album)
        assertEquals(SavedTwice.Kind.MUSIC_VIDEO, video.kind)
        assertEquals(listOf("Paramore"), video.artists)
    }

    @Test
    fun `each place finds its way back from its route`() {
        for (place in listOf(SavedTwicePlace.Liked, SavedTwicePlace.Library, SavedTwicePlace.Playlist("LPabc12345"))) {
            val route = place.route.removePrefix(SavedTwicePlace.ROUTE + "/")
            val name = route.substringBefore("?")
            val playlist = route.substringAfter("?playlist=", "").ifEmpty { null }
            assertEquals(place, SavedTwicePlace.of(name, playlist))
        }
        assertNull(SavedTwicePlace.of("playlist", null))
        assertNull(SavedTwicePlace.of("downloaded", null))
    }
}
