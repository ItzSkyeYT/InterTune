/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * The joined song read against the plain one, over a real library, gated on `ENGINE_REPLAY=`.
 * Same rows, same primary artist for every song, and a timing for each.
 */
class EngineSqlTrial {
    @Test
    fun `the joined song read matches the subquery form`() {
        val path = System.getenv("ENGINE_REPLAY"); assumeTrue("set ENGINE_REPLAY to run", !path.isNullOrBlank())
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            fun read(sql: String): Pair<Map<String, Pair<String?, String?>>, Long> {
                val t0 = System.nanoTime()
                val out = HashMap<String, Pair<String?, String?>>()
                db.createStatement().use { st -> st.executeQuery(sql).use { rs -> while (rs.next()) out[rs.getString("id")] = rs.getString("artistId") to rs.getString("artistName") } }
                return out to (System.nanoTime() - t0) / 1_000_000
            }
            val plain = read("""SELECT s.id, s.title,
                (SELECT m.artistId FROM song_artist_map m WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistId,
                (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistName
                FROM song s""")
            val joined = read(EngineSql.SONGS)
            println("engine song read: subqueries ${plain.second} ms, join ${joined.second} ms, ${joined.first.size} songs")
            assertEquals(plain.first.size, joined.first.size)
            val differing = plain.first.count { (id, v) -> joined.first[id] != v }
            println("  differing primary artists: $differing")
            assertEquals(0, differing)
        }
    }
}
