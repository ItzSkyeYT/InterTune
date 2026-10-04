/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.engine.EngineSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/** The queries Quick picks leans toward reads, on the schema Room exports for this version. */
class LeanSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('a', 'A', 200, 0), ('b', 'B', 200, 0)")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) { db.createStatement().use { it.execute(sql) } }

    private fun rows(sql: String): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
        }
    }

    private fun listen(id: Long, song: String, startedAt: Long, endReason: Int = 1) = exec(
        "INSERT INTO listen(id, songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, originSlot, queueId, autoplayDepth, sessionId, tzOffsetMin, ratio, counted, learn) " +
            "VALUES ($id, '$song', $startedAt, ${startedAt + 1000}, 1000, 200000, $endReason, 1, -1, 0, 0, 1, 0, 0.0, 0, 1)"
    )

    @Test
    fun `a build's lean and weight come back by id, and a build before leans reads as none`() {
        exec("INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, lean, leanApplied, leadWeight) VALUES (1, 10, 1, 1, 0, 0, 50, 0, '[]', '{}', 1, 1, 0.074)")
        exec("INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights) VALUES (2, 20, 2, 1, 0, 0, 50, 0, '[]', '{}')")
        exec("INSERT INTO row_build(id, builtAt, rowKey, sessionId, bucket, contextChip, dial, engineVersion, seeds, weights, lean, leanApplied, leadWeight) VALUES (3, 30, 1, 1, 0, 2, 50, 0, '[]', '{}', 2, 0, 1.0)")
        val got = rows(EngineSql.BUILD_LEANS.replace(":ids", "1, 2, 3") + " ORDER BY id")
        assertEquals(listOf(listOf("1", "1", "0.074"), listOf("2", "0", "1.0"), listOf("3", "0", "1.0")), got)
    }

    @Test
    fun `the latest start includes a listen still open, and the listens since a build are the later ones`() {
        assertEquals(listOf(listOf<String?>(null)), rows(EngineSql.LATEST_LISTEN_START))
        listen(1, "a", 100); listen(2, "b", 200); listen(3, "a", 300, endReason = 6)
        assertEquals(listOf(listOf("300")), rows(EngineSql.LATEST_LISTEN_START))
        // The engine's shape, column for column: the id is the sixteenth, the row carried on the last.
        val since = rows(EngineSql.LISTENS_SINCE.replace(":since", "100") + " ORDER BY id")
        assertEquals(listOf("2", "3"), since.map { it[15] })
        assertEquals(17, since.first().size)
        exec("UPDATE listen SET continuesListenId = 2 WHERE id = 3")
        assertEquals(listOf(null, "2"), rows(EngineSql.LISTENS_SINCE.replace(":since", "100") + " ORDER BY id").map { it.last() })
    }

    @Test
    fun `every query that reads a listen in the engine's shape takes the same columns, one for each field`() {
        listen(1, "a", 100); listen(2, "b", 200)
        // Room fills ListenRow by column name, so the names are the shape: all of its fields, none twice.
        val columns = EngineSql.LISTEN_COLUMNS.split(", ")
        val fields = com.dd3boh.outertune.engine.ListenRow::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
        assertEquals(fields.toSet(), columns.toSet()); assertEquals(fields.size, columns.size)
        // The loader's query and Playing now's give the same rows in the same columns.
        val all = rows(EngineSql.LISTENS + " ORDER BY id")
        assertEquals(2, all.size); assertEquals(columns.size, all.first().size)
        assertEquals(all, rows(EngineSql.LISTENS_SINCE.replace(":since", "0") + " ORDER BY id"))
        assertEquals(all.drop(1), rows(EngineSql.LISTENS_SINCE.replace(":since", "100") + " ORDER BY id"))
    }

    @Test
    fun `a play that failed is not the latest start, since the engine's input leaves it out`() {
        listen(1, "a", 100); listen(2, "b", 200, endReason = 5)
        assertEquals(listOf(listOf("100")), rows(EngineSql.LATEST_LISTEN_START))
        // It is still among the listens since a build; what counts of them is decided in LeanRow.
        assertEquals(listOf("2"), rows(EngineSql.LISTENS_SINCE.replace(":since", "100")).map { it[15] })
    }
}
