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

/** The library queries in LibrarySql, run against the exported schema as on the device. */
class LibrarySqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** The first column of each row. */
    private fun ids(sql: String): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun song(id: String, liked: Boolean = false, inLibrary: Long? = null) = exec(
        "INSERT INTO song(id, title, duration, liked, inLibrary) VALUES ('$id', '$id', 200, ${if (liked) 1 else 0}, $inLibrary)"
    )

    private fun plays(id: String, count: Int) =
        exec("INSERT INTO playCount(song, year, month, count) VALUES ('$id', 2026, 9, $count)")

    @Test
    fun `liked songs by play count are the liked songs only`() {
        song("liked-often", liked = true)
        song("liked-once", liked = true)
        song("liked-never", liked = true)
        song("seen-often")
        song("seen-never")
        plays("liked-often", 12)
        plays("liked-once", 1)
        plays("seen-often", 40)
        // Least played first, never played (a null count) before any.
        assertEquals(listOf("liked-never", "liked-once", "liked-often"), ids(LibrarySql.LIKED_SONGS_BY_PLAY_COUNT))
    }

    private fun album(id: String, title: String, local: Boolean) = exec(
        "INSERT INTO album(id, title, songCount, duration, lastUpdateTime, isLocal) VALUES ('$id', '$title', 1, 200, 0, ${if (local) 1 else 0})"
    )

    @Test
    fun `a title finds only a local album`() {
        album("MPREb_queen", "Greatest Hits", local = false)
        assertEquals(emptyList<String>(), ids(LibrarySql.LOCAL_ALBUM_BY_TITLE.replace(":title", "'Greatest Hits'")))
        album("LBfirst", "Greatest Hits", local = true)
        album("LBsecond", "Greatest Hits", local = true)
        assertEquals(listOf("LBfirst"), ids(LibrarySql.LOCAL_ALBUM_BY_TITLE.replace(":title", "'Greatest Hits'")))
    }
}
