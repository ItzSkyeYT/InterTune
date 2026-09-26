/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * Taking songs out of a playlist, run against the exported schema as on the device.
 *
 * A removal moves the row to the end by its position and then deletes it by its id. The playlist
 * search kept showing rows with the positions they had before an earlier removal, so the move
 * caught a different song. The playlist here is the one from the audit, X A Y B Z W, with a search
 * that shows A and B.
 */
class PlaylistSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO playlist(id, name) VALUES ('P', 'P')")
        SONGS.forEachIndexed { position, song ->
            exec("INSERT INTO song(id, title, duration, liked) VALUES ('$song', '$song', 200, 0)")
            exec("INSERT INTO playlist_song_map(id, playlistId, songId, position) VALUES (${mapId(song)}, 'P', '$song', $position)")
        }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun mapId(song: String) = SONGS.indexOf(song) + 1

    /** Room's named parameters, filled in for JDBC. */
    private fun move(from: Int, to: Int) = exec(
        PlaylistSql.MOVE.replace(":playlistId", "'P'").replace(":fromPosition", "$from").replace(":toPosition", "$to")
    )

    private fun positionNow(mapId: Int): Int? = db.createStatement().use { st ->
        st.executeQuery(PlaylistSql.MAP_BY_ID.replace(":id", "$mapId")).use { rs -> if (rs.next()) rs.getInt("position") else null }
    }

    /** What the song menu does now: find the row where it is, move it to the end, delete it. */
    private fun remove(mapId: Int) {
        val position = positionNow(mapId) ?: return
        move(position, Int.MAX_VALUE)
        exec("DELETE FROM playlist_song_map WHERE id = $mapId")
    }

    /** What it did before: move by the position the row was drawn with. */
    private fun removeAt(drawnPosition: Int, mapId: Int) {
        move(drawnPosition, Int.MAX_VALUE)
        exec("DELETE FROM playlist_song_map WHERE id = $mapId")
    }

    private fun playlist(): List<Pair<String, Int>> = db.createStatement().use { st ->
        st.executeQuery("SELECT songId, position FROM playlist_song_map WHERE playlistId = 'P' ORDER BY position").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
        }
    }

    @Test
    fun `positions drawn before a removal move songs the search never showed`() {
        // A at 1 and B at 3, as the results were drawn. The first removal is right, since nothing
        // has moved yet, and renumbers B to 2 and Z to 3.
        removeAt(1, mapId("A"))
        assertEquals(listOf("X" to 0, "Y" to 1, "B" to 2, "Z" to 3, "W" to 4), playlist())
        // B's row still says 3, so Z goes to the bottom and B's own place is left empty.
        removeAt(3, mapId("B"))
        assertEquals(listOf("X" to 0, "Y" to 1, "W" to 3, "Z" to Int.MAX_VALUE), playlist())
        // A was still listed, and removing it again sends Y to the bottom as well.
        removeAt(1, mapId("A"))
        assertEquals(listOf("X" to 0, "W" to 2, "Z" to Int.MAX_VALUE - 1, "Y" to Int.MAX_VALUE), playlist())
    }

    @Test
    fun `each removal finds its row where it is now`() {
        remove(mapId("A"))
        remove(mapId("B"))
        assertEquals(listOf("X" to 0, "Y" to 1, "Z" to 2, "W" to 3), playlist())
    }

    @Test
    fun `removing a row that has already gone changes nothing`() {
        remove(mapId("A"))
        remove(mapId("A"))
        assertEquals(listOf("X" to 0, "Y" to 1, "B" to 2, "Z" to 3, "W" to 4), playlist())
    }

    @Test
    fun `the last song and the first come out cleanly too`() {
        remove(mapId("W"))
        remove(mapId("X"))
        assertEquals(listOf("A" to 0, "Y" to 1, "B" to 2, "Z" to 3), playlist())
    }

    private companion object {
        val SONGS = listOf("X", "A", "Y", "B", "Z", "W")
    }
}
