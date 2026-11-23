/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumWithSongs
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistCoverSong
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * RelationOrder on its own, and the constructors Room builds Song, AlbumWithSongs and Playlist with.
 * ArtistOrderRoomTest runs the same through Room's own SQL on SQLite.
 */
class RelationOrderTest {
    private fun artist(id: String) = ArtistEntity(id = id, name = id)
    private fun song(id: String) = Song(SongEntity(id = id, title = id, localPath = null), artists = emptyList())
    private fun cover(songId: String, position: Int, picture: String?) = PlaylistCoverSong("P", songId, position, listOfNotNull(picture))

    @Test
    fun `artists go in the order the song credits them, whatever their ids`() {
        val byId = listOf(artist("LAjudeline"), artist("UCkarolg"), artist("UCrusowsky"))
        val credits = listOf(SongArtistMap("s", "UCkarolg", 0), SongArtistMap("s", "LAjudeline", 1), SongArtistMap("s", "UCrusowsky", 2))
        assertEquals(listOf("UCkarolg", "LAjudeline", "UCrusowsky"), RelationOrder.artists(byId, credits).map { it.id })
    }

    @Test
    fun `an artist the credits do not place goes last, the others keep their order`() {
        val artists = listOf(artist("b"), artist("x"), artist("a"))
        val credits = listOf(SongArtistMap("s", "a", 0), SongArtistMap("s", "b", 1))
        assertEquals(listOf("a", "b", "x"), RelationOrder.artists(artists, credits).map { it.id })
        // No credits at all: the order they came in.
        assertEquals(listOf("b", "x", "a"), RelationOrder.artists(artists, emptyList()).map { it.id })
        // One artist is left as it is.
        val one = listOf(artist("a"))
        assertSame(one, RelationOrder.artists(one, credits))
    }

    @Test
    fun `an album's songs go by their index in it`() {
        val stored = listOf(song("africa"), song("rosanna"), song("believe"))
        val tracks = listOf(SongAlbumMap("africa", "A", 2), SongAlbumMap("rosanna", "A", 0), SongAlbumMap("believe", "A", 1))
        assertEquals(listOf("rosanna", "believe", "africa"), RelationOrder.songs(stored, tracks).map { it.id })
    }

    @Test
    fun `a playlist's cover pictures go by position, a song without a picture as null`() {
        val stored = listOf(cover("one", 1, "p1"), cover("two", 2, null), cover("three", 3, "p3"), cover("five", 0, "p5"))
        assertEquals(listOf("p5", "p1", null, "p3"), RelationOrder.coverPictures(stored))
    }

    @Test
    fun `Room's constructors put the relations in order, the app's own keep what they are given`() {
        val credits = listOf(SongArtistMap("s", "UCkarolg", 0), SongArtistMap("s", "LAjudeline", 1))
        val fromRoom = Song(SongEntity(id = "s", title = "BbY WOW", localPath = null), listOf(artist("LAjudeline"), artist("UCkarolg")), null, null, null, credits)
        assertEquals(listOf("UCkarolg", "LAjudeline"), fromRoom.artists.map { it.id })
        assertEquals(credits, fromRoom.artistOrder)
        // A copy keeps the order it was made with.
        assertEquals(listOf("UCkarolg", "LAjudeline"), fromRoom.copy(song = fromRoom.song.copy(title = "x")).artists.map { it.id })
        // The app's own constructor takes the artists as they are given.
        val given = Song(SongEntity(id = "s", title = "BbY WOW", localPath = null), artists = listOf(artist("LAjudeline"), artist("UCkarolg")))
        assertEquals(listOf("LAjudeline", "UCkarolg"), given.artists.map { it.id })

        val album = AlbumWithSongs(
            AlbumEntity(id = "A", title = "Toto IV", songCount = 2, duration = 0), emptyList(), listOf(song("africa"), song("rosanna")), 0,
            listOf(SongAlbumMap("africa", "A", 1), SongAlbumMap("rosanna", "A", 0)),
        )
        assertEquals(listOf("rosanna", "africa"), album.songs.map { it.id })

        val playlist = Playlist(PlaylistEntity(id = "P", name = "Mine"), listOf(cover("one", 1, "p1"), cover("five", 0, "p5")), 2, 0)
        assertEquals(listOf("p5", "p1"), playlist.songThumbnails)
        assertEquals("p5", playlist.thumbnails.first())
    }
}
