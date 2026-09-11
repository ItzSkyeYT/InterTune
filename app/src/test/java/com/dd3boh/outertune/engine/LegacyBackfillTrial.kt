/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.db.MusicDatabase.Companion.MUSIC_DATABASE_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.ZoneId

/**
 * The backfill over a copy of a real library, run twice. Gated on `BACKFILL_DB=/path/to/song.db`
 * (a pre-0.11 database, schema 20 or later) so the ordinary test run never depends on private
 * data. It adds the tables and columns version 21 brings, the way the migration would, then
 * reports counts and timing and asserts that the second run changes nothing.
 */
class LegacyBackfillTrial {
    @Test
    fun `a real play log backfills once and only once`() {
        val source = System.getenv("BACKFILL_DB"); assumeTrue("set BACKFILL_DB to run", !source.isNullOrBlank())
        val copy = File.createTempFile("backfill-trial", ".db").apply { deleteOnExit() }
        File(source!!).copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            // Bring the copy up to the current schema: new tables whole, new columns on old tables.
            val schema = Json.parseToJsonElement(File("schemas/com.dd3boh.outertune.db.InternalDatabase/$MUSIC_DATABASE_VERSION.json").readText())
                .jsonObject["database"]!!.jsonObject
            val existing = db.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
            db.createStatement().use { st ->
                for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    if (table !in existing) {
                        st.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        entity["indices"]?.jsonArray?.forEach { st.execute(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
                    } else {
                        val have = st.executeQuery("PRAGMA table_info($table)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
                        for (field in entity["fields"]!!.jsonArray.map { it.jsonObject }) {
                            val col = field["columnName"]!!.jsonPrimitive.content
                            if (col in have) continue
                            val default = field["defaultValue"]?.jsonPrimitive?.content?.let { " DEFAULT $it" } ?: ""
                            val notNull = if (field["notNull"]!!.jsonPrimitive.content == "true") " NOT NULL" else ""
                            st.execute("ALTER TABLE $table ADD COLUMN `$col` ${field["affinity"]!!.jsonPrimitive.content}$notNull$default")
                        }
                    }
                }
            }
            fun count(sql: String) = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
            val events = count("SELECT COUNT(*) FROM event e JOIN song s ON s.id = e.songId")
            val edgesBefore = count("SELECT COUNT(*) FROM related_song_map")
            val duplicatePairs = count("SELECT COUNT(*) - COUNT(DISTINCT songId || '|' || relatedSongId) FROM related_song_map")
            val io = JdbcBackfillIo(db)
            val t0 = System.nanoTime()
            val written = LegacyBackfill(io, ZoneId.of("Europe/Paris")).run()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val again = LegacyBackfill(io, ZoneId.of("Europe/Paris")).run()
            println("backfill trial: $events events -> $written listens in $ms ms, second run wrote $again")
            println("  sessions ${count("SELECT COUNT(DISTINCT sessionId) FROM listen")}, counted ${count("SELECT COUNT(*) FROM listen WHERE counted")}, with length ${count("SELECT COUNT(*) FROM listen WHERE durationMs > 0")}")
            println("  edges $edgesBefore -> ${count("SELECT COUNT(*) FROM related_song_map")} ($duplicatePairs duplicate pairs dropped), dated ${count("SELECT COUNT(*) FROM related_song_map WHERE fetchedAt > 0")}, still undated ${count("SELECT COUNT(*) FROM related_song_map WHERE fetchedAt = 0")}")
            println("  first listen ${count("SELECT MIN(startedAt) FROM listen")}, last ${count("SELECT MAX(endedAt) FROM listen")}, negative spans ${count("SELECT COUNT(*) FROM listen WHERE endedAt < startedAt")}")
            assertEquals(events, written.toLong())
            assertEquals(0, again)
            assertEquals(events, count("SELECT COUNT(*) FROM listen"))
            assertEquals(edgesBefore - duplicatePairs, count("SELECT COUNT(*) FROM related_song_map"))
        }
        // BACKFILL_KEEP names where to leave the migrated, backfilled copy for the replay and the timing trials.
        System.getenv("BACKFILL_KEEP")?.takeIf { it.isNotBlank() }?.let { keep -> copy.copyTo(File(keep), overwrite = true); println("  kept at $keep") }
    }
}
