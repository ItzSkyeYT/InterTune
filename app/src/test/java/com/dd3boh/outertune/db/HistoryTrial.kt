/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.history.HistoryRemoval
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * History over a copy of a real library: what it lists, whether Remove from history would take
 * out exactly the pieces of every row, and Select all then Remove, timed. Gated on
 * `HISTORY_DB=/path/to/song.db`, so the ordinary run never depends on private data; the copy takes
 * the -wal beside the file too, when there is one. Prints counts and times only.
 */
class HistoryTrial {
    private fun Connection.count(sql: String): Long = createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }

    private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000

    @Test
    fun `select all and remove on a real library`() {
        val source = System.getenv("HISTORY_DB"); assumeTrue("set HISTORY_DB to run", !source.isNullOrBlank())
        val copy = File.createTempFile("history-trial", ".db").apply { deleteOnExit() }
        File(source!!).copyTo(copy, overwrite = true)
        File("$source-wal").takeIf { it.exists() }?.copyTo(File("${copy.path}-wal").apply { deleteOnExit() }, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            val history = JdbcHistory(db)
            val listens = db.count("SELECT COUNT(*) FROM listen")

            var t0 = System.nanoTime()
            val plays = history.plays()
            println("History: ${plays.size} rows (${plays.count { it.listenId != null }} from the listen log) in ${ms(t0)} ms")

            t0 = System.nanoTime()
            val groups = history.groups()
            var walked = 0
            val differ = plays.mapNotNull { it.listenId }.filter { head ->
                walked++
                history.chain(head).map { it.id }.toSet() != groups.getValue(head)
            }
            println("CHAIN for each of $walked rows in ${ms(t0)} ms; rows whose pieces differ from PLAYS: ${differ.size} ${differ.take(10)}")
            assertEquals(emptyList<Long>(), differ)

            val spans = mutableListOf<Long>()
            t0 = System.nanoTime()
            HistoryRemoval.remove(history, plays, System.currentTimeMillis(), transaction = { block ->
                val start = System.nanoTime()
                history.transaction(block)
                spans += ms(start)
            })
            println("Select all, Remove: ${ms(t0)} ms in ${spans.size} transactions, the longest ${spans.max()} ms")

            assertEquals(0, history.plays().size)
            assertEquals(listens, db.count("SELECT COUNT(*) FROM listen"))
        }
    }
}
