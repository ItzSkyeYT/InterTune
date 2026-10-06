/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Nothing waits on a coroutine while it holds the database's write connection.
 *
 * A transaction block runs on one thread with the write connection in its hand. A runBlocking in
 * there that reads through Room goes to another thread, and since Room 2.7 that thread first brings
 * the triggers up to date under a lock, wanting the write connection itself when there is one to
 * add or drop. The two then wait on each other for good. Counting a play did exactly that on
 * 6 Oct 2026 (experiments/bugs/playback-stopped-1835): every song after it sat buffering, and the
 * app would not open past its splash screen until it was force stopped.
 *
 * This reads the source, as PlayOriginCoverageTest does, because the wait compiles and passes
 * every test that does not happen to have a trigger pending at that instant.
 */
class NoWaitingInTransactionsTest {

    private val sources = File("src/main/java")

    @Test
    fun `nothing in the database layer waits on a coroutine`() {
        val found = mutableListOf<String>()
        File(sources, "com/dd3boh/outertune/db").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            Regex("""\brunBlocking\b""").findAll(text).forEach { m ->
                if (!isComment(text, m.range.first) && !isImport(text, m.range.first)) found += place(file, text, m.range.first)
            }
        }
        assertEquals("runBlocking in the database layer:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `no transaction block waits on a coroutine`() {
        val found = mutableListOf<String>()
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            waitsInTransactions(text).forEach { found += place(file, text, it) }
        }
        assertEquals("runBlocking inside a transaction block:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `the search finds the wait it is there for`() {
        val was = """
            database.transaction {
                var old: Int
                runBlocking { old = counts("${'$'}{id}").first() }
                insert(Event(id))
            }
            runBlocking { database.song(id).first() }
            // database.transaction { runBlocking { } }
            db.transactionNow { runBlocking { it } }
            delegate.runInTransaction { runBlocking { it } }
            database.transaction { update(song) }
        """.trimIndent()
        val lines = waitsInTransactions(was).map { was.substring(0, it).count { c -> c == '\n' } + 1 }
        assertEquals("the one after its transaction and the one in a comment are not it", listOf(3, 8, 9), lines)
    }

    /** Where in [text] a runBlocking sits inside the braces of a transaction call. */
    private fun waitsInTransactions(text: String): List<Int> {
        val found = mutableListOf<Int>()
        Regex("""\b(transaction|transactionNow|runInTransaction|withTransaction)\s*(\([^(){}]*\)\s*)?\{""").findAll(text).forEach { m ->
            if (isComment(text, m.range.first)) return@forEach
            val block = text.substring(m.range.last + 1, closingBrace(text, m.range.last))
            Regex("""\brunBlocking\b""").findAll(block).forEach { found += m.range.last + 1 + it.range.first }
        }
        return found.distinct().sorted()
    }

    /** The index of the brace closing the one at [open]. Braces in string templates come in pairs, so they count like any other. */
    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return text.length
    }

    private fun lineOf(text: String, at: Int) = text.substring(text.lastIndexOf('\n', at - 1) + 1, at)
    private fun isComment(text: String, at: Int) = lineOf(text, at).trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } || "//" in lineOf(text, at)
    private fun isImport(text: String, at: Int) = lineOf(text, at).trimStart().startsWith("import ")
    private fun place(file: File, text: String, at: Int) = "${file.relativeTo(sources)}:${text.substring(0, at).count { it == '\n' } + 1}"

    // What the count does now, against SQLite itself: the row is made when the month has none and left alone when it has.

    private val insertOrIgnore = "INSERT OR IGNORE INTO `playCount` (`song`,`year`,`month`,`count`) VALUES (?,?,?,?)"

    /** The table as the current schema makes it, and the update as SongsDao words it. */
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        val schema = File("schemas/com.dd3boh.outertune.db.InternalDatabase").listFiles { f -> f.extension == "json" }!!
            .maxBy { it.nameWithoutExtension.toInt() }
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        val table = entities.map { it.jsonObject }.single { it["tableName"]!!.jsonPrimitive.content == "playCount" }
        db.createStatement().use { it.execute(table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "playCount")) }
    }

    private val increment: String by lazy {
        val dao = File(sources, "com/dd3boh/outertune/db/daos/SongsDao.kt").readText()
        Regex("""@Query\("(UPDATE playCount SET count = count \+ 1[^"]*)"\)""").find(dao)!!.groupValues[1]
            .replace(":songId", "?1").replace(":year", "?2").replace(":month", "?3")
    }

    private fun Connection.count(song: String, year: Int, month: Int) {
        prepareStatement(insertOrIgnore).use { it.setString(1, song); it.setInt(2, year); it.setInt(3, month); it.setInt(4, 0); it.executeUpdate() }
        prepareStatement(increment).use { it.setString(1, song); it.setInt(2, year); it.setInt(3, month); it.executeUpdate() }
    }

    private fun Connection.counts(): List<String> = createStatement().use { st ->
        st.executeQuery("SELECT song, year, month, count FROM playCount ORDER BY song, year, month").use { rs ->
            buildList { while (rs.next()) add("${rs.getString(1)} ${rs.getInt(2)}-${rs.getInt(3)} ${rs.getInt(4)}") }
        }
    }

    @Test
    fun `a play is counted once, in a month with a row and in one without`() {
        database().use { db ->
            db.count("a", 2026, 10)
            assertEquals("the first play of the month makes the row", listOf("a 2026-10 1"), db.counts())
            db.count("a", 2026, 10)
            db.count("a", 2026, 10)
            assertEquals("later ones add to it and make no second row", listOf("a 2026-10 3"), db.counts())
            db.count("a", 2026, 11)
            db.count("b", 2026, 10)
            assertEquals(listOf("a 2026-10 3", "a 2026-11 1", "b 2026-10 1"), db.counts())
        }
    }

    @Test
    fun `a row left at nothing by an older version is counted from there`() {
        database().use { db ->
            // what the old code made before its update ran, were the app killed between the two
            db.prepareStatement(insertOrIgnore).use { it.setString(1, "a"); it.setInt(2, 2026); it.setInt(3, 10); it.setInt(4, 0); it.executeUpdate() }
            db.count("a", 2026, 10)
            assertEquals(listOf("a 2026-10 1"), db.counts())
        }
    }

    @Test
    fun `the count is found in the source`() {
        assertTrue(increment, increment.startsWith("UPDATE playCount SET count = count + 1 WHERE song = ?1"))
    }
}
