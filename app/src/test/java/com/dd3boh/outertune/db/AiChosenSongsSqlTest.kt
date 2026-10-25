/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.SongsDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/** The songs the AI filter never touches (SongsDao.CHOSEN_SONG_IDS), run against the exported schema. */
class AiChosenSongsSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String, inLibrary: Long? = null, liked: Boolean = false, downloaded: Long? = null) = exec(
        "INSERT INTO song(id, title, duration, liked, inLibrary, dateDownload) VALUES ('$id', '$id', 200, ${if (liked) 1 else 0}, $inLibrary, $downloaded)"
    )

    private fun playlist(id: String, editable: Boolean) =
        exec("INSERT INTO playlist(id, name, isEditable) VALUES ('$id', '$id', ${if (editable) 1 else 0})")

    private fun inPlaylist(playlist: String, song: String) =
        exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('$playlist', '$song', 0)")

    private fun chosen(): Set<String> = db.prepareStatement(SongsDao.CHOSEN_SONG_IDS).use { ps ->
        ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test
    fun `the library, liked and downloaded songs, and those of the listener's own playlists`() {
        song("saved", inLibrary = 1_700_000_000_000)
        song("liked", liked = true)
        song("downloaded", downloaded = 1_700_000_000_000)
        song("heard")
        song("mine")
        song("theirs")
        song("both")
        playlist("LPmine", editable = true)
        playlist("PLsaved", editable = false)
        inPlaylist("LPmine", "mine")
        inPlaylist("PLsaved", "theirs")
        inPlaylist("LPmine", "both")
        inPlaylist("PLsaved", "both")
        assertEquals(setOf("saved", "liked", "downloaded", "mine", "both"), chosen())
    }

    @Test
    fun `nothing chosen, nothing given`() {
        song("heard")
        assertEquals(emptySet<String>(), chosen())
    }
}
