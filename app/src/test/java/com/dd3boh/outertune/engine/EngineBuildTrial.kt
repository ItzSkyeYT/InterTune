/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import kotlin.random.Random

/**
 * How long each part of a build takes over a real library, gated on `ENGINE_REPLAY=`. The phone
 * is slower than this machine, but the shape of the cost is the same, and this says where it is.
 */
class EngineBuildTrial {
    @Test
    fun `time a build over a real library`() {
        val path = System.getenv("ENGINE_REPLAY"); assumeTrue("set ENGINE_REPLAY to run", !path.isNullOrBlank())
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val now = System.currentTimeMillis()
            fun <T> timed(name: String, block: () -> T): T { val t0 = System.nanoTime(); val r = block(); println("  %-14s %5d ms".format(name, (System.nanoTime() - t0) / 1_000_000)); return r }
            println("engine build trial")
            val songs = timed("songs", { JdbcEngineInput.songs(db) })
            val listens = timed("listens", { JdbcEngineInput.listens(db, now) })
            val edges = timed("edges", { JdbcEngineInput.edges(db) })
            val links = timed("links", { JdbcEngineInput.links(db) })
            val input = EngineInput(now, songs, listens, edges, links, bucket = dayPartBucket(now, 120), tzOffsetMin = 120)
            val stats = timed("stats", { LibraryStats(input) })
            timed("groups", { VersionGroups(songs.values, links) })
            val row = timed("build (all)", { EngineRow.build(input, random = Random(1)) })
            timed("build again", { EngineRow.build(input, random = Random(2)) })
            timed("rank 40", { EngineRow.rank(input, songs.keys.take(40).toList()) })
            println("  ${row.cards.size} cards, ${row.pool.size} pool, ${row.seeds.size} seeds; ${stats.songs.size} songs with listens, ${stats.artists.size} artists")
            row.cards.forEachIndexed { i, c -> println("  %2d %-9s z %.2f p %.3f %-40s %s".format(i, c.lane, c.z, c.p, songs[c.songId]?.title?.take(40), c.reasons)) }
            assertTrue(row.cards.size >= 8)
        }
    }
}
