/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * The 23 Sep Last.fm trial's edges, rebuilt with the app's own matcher instead of the Python
 * script's. Gated on `LFM_CACHE=<lfm_cache.json>` and `LFM_DB=<a migrated song.db>`, so the ordinary
 * run never depends on private data. With `LFM_KEEP=<out.db>` it writes a copy holding YouTube's
 * edges as source 0 and these as source 1, for the replay to read either one through the app's
 * query. The cache is the trial's: every Last.fm answer keyed by song id, so this makes no request.
 */
class LastFmSimilarTrial {
    @Test
    fun `the app's matcher rebuilds the trial's Last-fm edges`() {
        val cachePath = System.getenv("LFM_CACHE"); assumeTrue("set LFM_CACHE and LFM_DB to run", !cachePath.isNullOrBlank())
        val source = System.getenv("LFM_DB")!!
        val keep = System.getenv("LFM_KEEP")
        val target = if (keep.isNullOrBlank()) File.createTempFile("lfm-trial", ".db").apply { deleteOnExit() } else File(keep)
        File(source).copyTo(target, overwrite = true)
        val cache = Json.parseToJsonElement(File(cachePath!!).readText()).jsonObject
        DriverManager.getConnection("jdbc:sqlite:${target.path}").use { db ->
            val songs = JdbcEngineInput.songs(db)
            val t0 = System.nanoTime()
            val index = SimilarMatch.Index(songs.values)
            val indexMs = (System.nanoTime() - t0) / 1_000_000
            var neighbours = 0; var found = 0; var knew = 0; var seeds = 0; var viaFallback = 0
            val edges = ArrayList<Pair<String, String>>()
            for ((id, entry) in cache) {
                val seed = songs[id] ?: continue
                val similar = entry.jsonObject["similar"]!!.jsonArray.map {
                    val o = it.jsonObject
                    SimilarMatch.Neighbour(o["name"]!!.jsonPrimitive.content, o["artist"]?.takeIf { a -> a !is JsonNull }?.jsonPrimitive?.content)
                }
                if (similar.isNotEmpty()) knew++
                if (entry.jsonObject["how"]?.jsonPrimitive?.content == "base") viaFallback++
                neighbours += similar.size
                found += similar.count { n -> SimilarMatch.key(n.title, n.artist)?.let { index[it].isNotEmpty() } == true }
                val ids = SimilarMatch.match(id, seed.title, seed.artistName, similar, index)
                if (ids.isNotEmpty()) seeds++
                ids.forEach { edges += id to it }
            }
            println("    songs ${songs.size}, index built in $indexMs ms")
            println("    Last.fm knew $knew of ${cache.size} (${viaFallback} via the plain title); neighbours $neighbours, found in library $found (${100 * found / maxOf(1, neighbours)}%)")
            println("    edges ${edges.size} from $seeds seeds (the Python trial: 22903 from 793)")
            db.autoCommit = false
            db.createStatement().use { it.execute("DELETE FROM related_song_map WHERE source = 1") }
            db.prepareStatement("INSERT INTO related_song_map(songId, relatedSongId, fetchedAt, source) VALUES (?, ?, 0, 1)").use { ps ->
                for ((a, b) in edges) { ps.setString(1, a); ps.setString(2, b); ps.addBatch() }
                ps.executeBatch()
            }
            db.commit()
        }
    }
}
