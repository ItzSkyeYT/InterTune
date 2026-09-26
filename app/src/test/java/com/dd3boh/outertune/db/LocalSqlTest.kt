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

    @Test
    fun `songs with no file are never duplicates`() {
        song("a", isLocal = false, path = null)
        song("b", isLocal = false, path = null)
        song("LSgone", isLocal = true, path = null)
        assertEquals(emptyList<String>(), duplicates())
    }
}
