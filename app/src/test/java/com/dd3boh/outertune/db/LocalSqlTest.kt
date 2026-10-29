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

/** The local scan's duplicate sweep, run against the exported schema as on the device. */
class LocalSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun song(id: String, isLocal: Boolean, path: String?) = db.createStatement().use {
        it.execute(
            "INSERT INTO song(id, title, duration, liked, isLocal, localPath) " +
                    "VALUES ('$id', '$id', 200, 0, ${if (isLocal) 1 else 0}, ${path?.let { p -> "'$p'" } ?: "NULL"})"
        )
    }

    private fun duplicates(): List<String> = db.createStatement().use { st ->
        st.executeQuery(LocalSql.DUPLICATED_LOCAL_SONGS).use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    @Test
    fun `a download in a scan folder is not a duplicate of the local song made of it`() {
        val file = "/storage/emulated/0/Music/InterTune/Song [dQw4w9WgXcQ].mka"
        song("dQw4w9WgXcQ", isLocal = false, path = file)
        song("LSimported", isLocal = true, path = file)
        assertEquals(emptyList<String>(), duplicates())
    }

    @Test
    fun `two local songs on one file are still found`() {
        val file = "/storage/emulated/0/Music/a.flac"
        song("LSone", isLocal = true, path = file)
        song("LStwo", isLocal = true, path = file)
        song("LSother", isLocal = true, path = "/storage/emulated/0/Music/b.flac")
        song("dQw4w9WgXcQ", isLocal = false, path = file)
        assertEquals(setOf("LSone", "LStwo"), duplicates().toSet())
    }

    /** A song row for TrackTagReread's queries: in the library unless [inLibrary] is false. */
    private fun row(id: String, isLocal: Boolean = true, track: Int?, disc: Int? = null, inLibrary: Boolean = true, path: String? = "/m/$id.mp3") =
        db.createStatement().use {
            it.execute(
                "INSERT INTO song(id, title, duration, liked, isLocal, localPath, inLibrary, trackNumber, discNumber) " +
                        "VALUES ('$id', '$id', 200, 0, ${if (isLocal) 1 else 0}, ${path?.let { p -> "'$p'" } ?: "NULL"}, " +
                        "${if (inLibrary) 1761200000000 else "NULL"}, ${track ?: "NULL"}, ${disc ?: "NULL"})"
            )
        }

    private fun toReread(after: String, limit: Int): List<String> =
        db.prepareStatement(LocalSql.SONGS_TO_REREAD).use { st ->
            st.setString(1, after)
            st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add("${rs.getString("id")} ${rs.getString("localPath")} ${rs.getObject("trackNumber")} ${rs.getObject("discNumber")}")
                }
            }
        }

    @Test
    fun `the track tag pass finds local songs in the library with no track number, a page at a time`() {
        row("LSnone", track = null, disc = 1)
        row("LSzero", track = 0, disc = 1)
        row("LSnumbered", track = 3, disc = 1)
        row("LSdisabled", track = null, inLibrary = false)
        row("LSnofile", track = null, path = null)
        row("dQw4w9WgXcQ", isLocal = false, track = null)
        assertEquals(listOf("LSnone /m/LSnone.mp3 null 1", "LSzero /m/LSzero.mp3 0 1"), toReread("", 50))
        assertEquals(listOf("LSnone /m/LSnone.mp3 null 1"), toReread("", 1))
        assertEquals(listOf("LSzero /m/LSzero.mp3 0 1"), toReread("LSnone", 50))
    }

    @Test
    fun `the track tag pass also finds the songs that have their track number and no disc`() {
        // Before 0.12.5 a track tag "4" was read and a disc tag "2/2" was not: the pass of 0.12.5
        // looked at track numbers only and passed these over.
        row("LSnodisc", track = 4, disc = null)
        row("LSzerodisc", track = 4, disc = 0)
        row("LScomplete", track = 4, disc = 2)
        assertEquals(listOf("LSnodisc /m/LSnodisc.mp3 4 null", "LSzerodisc /m/LSzerodisc.mp3 4 0"), toReread("", 50))
    }

    @Test
    fun `the track tag pass keeps a stored number the file does not give`() {
        row("LSa", track = null, disc = 2)
        row("LSb", track = 0, disc = null)
        fun set(id: String, track: Int?, disc: Int?) = db.prepareStatement(LocalSql.SET_TRACK_TAGS).use { st ->
            if (track == null) st.setNull(1, java.sql.Types.INTEGER) else st.setInt(1, track)
            if (disc == null) st.setNull(2, java.sql.Types.INTEGER) else st.setInt(2, disc)
            st.setString(3, id)
            st.executeUpdate()
        }
        set("LSa", 4, null)
        set("LSb", 7, 1)
        val stored = db.createStatement().use { st ->
            st.executeQuery("SELECT id, trackNumber, discNumber FROM song ORDER BY id").use { rs ->
                buildList { while (rs.next()) add("${rs.getString(1)} ${rs.getInt(2)} ${rs.getObject(3)}") }
            }
        }
        assertEquals(listOf("LSa 4 2", "LSb 7 1"), stored)
    }

    @Test
    fun `songs with no file are never duplicates`() {
        song("a", isLocal = false, path = null)
        song("b", isLocal = false, path = null)
        song("LSgone", isLocal = true, path = null)
        assertEquals(emptyList<String>(), duplicates())
    }
}
