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
 * The write of a playlist's cover (PlaylistSql.SET_COVER), run against the exported schema as on
 * the device. A playlist from YouTube has YouTube's picture written into the same column by its
 * sync, so the write is for a playlist with no browseId and for nothing else: whatever screen asks.
 */
class PlaylistCoverSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO playlist(id, name, thumbnailUrl) VALUES ('LPmine', 'Night drive', NULL)")
        exec("INSERT INTO playlist(id, name, thumbnailUrl, browseId) VALUES ('LPsynced', 'From YouTube', 'https://yt.example/pic.jpg', 'PLabc123')")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Room's named parameters, filled in for JDBC; returns the rows written, as the DAO does. */
    private fun setCover(playlistId: String, cover: String?): Int = db.createStatement().use { st ->
        st.executeUpdate(
            PlaylistSql.SET_COVER
                .replace(":thumbnailUrl", cover?.let { "'$it'" } ?: "NULL")
                .replace(":playlistId", "'$playlistId'")
        )
    }

    private fun coverOf(playlistId: String): String? = db.createStatement().use { st ->
        st.executeQuery("SELECT thumbnailUrl FROM playlist WHERE id = '$playlistId'").use { rs -> if (rs.next()) rs.getString(1) else "no such playlist" }
    }

    @Test
    fun `a playlist of one's own gets the cover and loses it again`() {
        assertEquals(1, setCover("LPmine", "https://lh3.example/song-cover=w544-h544"))
        assertEquals("https://lh3.example/song-cover=w544-h544", coverOf("LPmine"))
        assertEquals(1, setCover("LPmine", "playlist-covers/LPmine-1730500000000.jpg"))
        assertEquals("playlist-covers/LPmine-1730500000000.jpg", coverOf("LPmine"))
        assertEquals(1, setCover("LPmine", null))
        assertEquals(null, coverOf("LPmine"))
    }

    @Test
    fun `a playlist with a YouTube playlist behind it keeps YouTube's picture`() {
        assertEquals(0, setCover("LPsynced", "playlist-covers/LPsynced-1.jpg"))
        assertEquals(0, setCover("LPsynced", null))
        assertEquals("https://yt.example/pic.jpg", coverOf("LPsynced"))
    }

    @Test
    fun `a playlist that is gone is nothing to write`() {
        assertEquals(0, setCover("LPgone", "x"))
    }

    @Test
    fun `no other column of the row is written`() {
        exec("UPDATE playlist SET name = 'Renamed', isLocal = 1 WHERE id = 'LPmine'")
        setCover("LPmine", "x")
        db.createStatement().use { st ->
            st.executeQuery("SELECT name, isLocal, browseId, isEditable FROM playlist WHERE id = 'LPmine'").use { rs ->
                rs.next()
                assertEquals("Renamed", rs.getString("name"))
                assertEquals(1, rs.getInt("isLocal"))
                assertEquals(null, rs.getString("browseId"))
                assertEquals(1, rs.getInt("isEditable"))
            }
        }
    }
}
