/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.engine.EngineSql
import com.dd3boh.outertune.engine.HeardSet
import com.dd3boh.outertune.engine.KnownSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * Every song the listener knows, read in one query for Discover's rule (EngineSql.KNOWN_SONGS): a play of any
 * length in the listen log or the old play history, a like, the library, a playlist of their own or a synced
 * one, a download, a local file. Run against the exported schema, as on the device.
 */
class KnownSongsSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String, columns: String = "", values: String = "", title: String = "Title $id") =
        exec("INSERT INTO song(id, title, duration, liked$columns) VALUES ('$id', '$title', 200, 0$values)")

    private fun artist(id: String, name: String) = exec("INSERT INTO artist(id, name, lastUpdateTime, isLocal) VALUES ('$id', '$name', 0, 0)")

    private fun by(songId: String, artistId: String, position: Int) =
        exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$songId', '$artistId', $position)")

    private var listenId = 0L
    private fun listen(songId: String, endReason: Int = 1, playedMs: Long = 1000) {
        listenId++
        exec("""INSERT INTO listen(id, songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
            originSlot, queueId, autoplayDepth, sessionId, counted, learn, runId)
            VALUES ($listenId, '$songId', $listenId, ${listenId + 1}, 0, $playedMs, 200000, 0.005, $endReason, 0, -1, 0, 0, 1, 0, 0, 0)""")
    }

    private fun known(): List<KnownSong> = db.createStatement().use { st ->
        st.executeQuery(EngineSql.KNOWN_SONGS).use { rs ->
            buildList { while (rs.next()) add(KnownSong(rs.getString("id"), rs.getString("title"), rs.getString("artistName"))) }
        }
    }

    @Test
    fun `every way a listener knows a song, each once, and nothing else`() {
        song("skipped"); listen("skipped", endReason = 2, playedMs = 900)       // a skip a second in, forgotten as well (learn 0)
        song("failed"); listen("failed", endReason = 5, playedMs = 0)            // a play that failed is still in the history
        song("old"); exec("INSERT INTO event(songId, timestamp, playTime) VALUES ('old', 0, 1000)")
        song("liked", ", likedDate", ", 1700000000000"); exec("UPDATE song SET liked = 1 WHERE id = 'liked'")
        song("saved", ", inLibrary", ", 1700000000000")
        song("downloaded", ", dateDownload", ", 1700000000000")
        song("file", ", isLocal, localPath", ", 1, '/music/file.mp3'")
        exec("INSERT INTO playlist(id, name, isEditable, isLocal) VALUES ('mine', 'Mine', 1, 1)")
        exec("INSERT INTO playlist(id, name, browseId, isEditable, isLocal) VALUES ('synced', 'Synced', 'VLPL1', 0, 0)")
        song("listed"); exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('mine', 'listed', 0)")
        song("synced"); exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('synced', 'synced', 0)")
        song("twice"); listen("twice"); listen("twice"); exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('mine', 'twice', 1)")
        song("never")
        song("related")

        val ids = known().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf("skipped", "failed", "old", "liked", "saved", "downloaded", "file", "listed", "synced", "twice"), ids.toSet())
    }

    @Test
    fun `each with its title and first artist, so another upload of it is known too`() {
        artist("first", "Toto"); artist("second", "Someone Else")
        song("africa", ", inLibrary", ", 1700000000000", title = "Africa")
        by("africa", "second", 1); by("africa", "first", 0)
        song("video", title = "Africa (Official Video)"); by("video", "first", 0)
        song("cover", title = "Africa"); by("cover", "second", 0)
        val rows = known()
        assertEquals(listOf(KnownSong("africa", "Africa", "Toto")), rows)
        val heard = HeardSet.of(rows)
        assertTrue(heard.knows("africa", "Africa", "Toto"))
        // The official video of a song saved as audio is a song known; someone else's Africa is not.
        assertTrue(heard.knows("video", "Africa (Official Video)", "Toto"))
        assertFalse(heard.knows("cover", "Africa", "Someone Else"))
        assertEquals(1, heard.size)
    }
}
