/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSearchTest {

    private fun row(mapId: Int, position: Int, title: String, artist: String) = PlaylistSong(
        map = PlaylistSongMap(id = mapId, playlistId = "P", songId = title, position = position),
        song = Song(
            song = SongEntity(id = title, title = title, localPath = null),
            artists = listOf(ArtistEntity(id = artist, name = artist)),
        ),
    )

    private val playlist = listOf(
        row(1, 0, "Xylophone", "Nemo"),
        row(2, 1, "Anthem", "Blur"),
        row(3, 2, "Yellow", "Coldplay"),
        row(4, 3, "Breathe", "Someone"),
        row(5, 4, "Zealot", "Nemo"),
    )

    private fun titles(rows: List<PlaylistSong>) = rows.map { it.song.title }

    @Test
    fun `a title or an artist matches, ignoring case, in the playlist's order`() {
        assertEquals(listOf("Anthem", "Breathe"), titles(playlistSearchResults(playlist, "b")))
        assertEquals(listOf("Xylophone", "Zealot"), titles(playlistSearchResults(playlist, "NEMO")))
    }

    @Test
    fun `an empty query shows the whole playlist`() {
        assertEquals(playlist, playlistSearchResults(playlist, ""))
    }

    @Test
    fun `results rebuilt after a removal lose the song and carry the new positions`() {
        val before = playlistSearchResults(playlist, "b")
        assertEquals(listOf(1, 3), before.map { it.map.position })

        // Anthem taken out: the rows after it close up by one.
        val after = playlist.filter { it.map.id != 2 }.mapIndexed { position, row -> row.copy(map = row.map.copy(position = position)) }
        val rebuilt = playlistSearchResults(after, "b")
        assertEquals(listOf("Breathe"), titles(rebuilt))
        assertEquals(listOf(2), rebuilt.map { it.map.position })
    }
}
