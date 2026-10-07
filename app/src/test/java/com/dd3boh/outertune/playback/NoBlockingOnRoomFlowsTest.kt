/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing that plays or downloads a song holds its thread on the database without a limit.
 *
 * The loader read a stored row before opening every song as runBlocking { database.format(id)
 * .first() }. A Flow's first value is not a plain read: Room first brings its triggers up to date,
 * under a lock and sometimes on the write connection, and the read then needs one of Room's four
 * threads. When the database jammed on 6 Oct 2026 (experiments/bugs/playback-stopped-1835) five
 * loader threads sat in that line, one for every song tried, and nothing played although every
 * read connection was free.
 *
 * So the loader and the downloader read rows with the queries that return them, through
 * MusicDatabase.readOrNull, which gives up after two seconds. This reads the source, as
 * NoWaitingInTransactionsTest does, because the old lines compile and pass every test that runs
 * against a database that answers.
 */
class NoBlockingOnRoomFlowsTest {

    private val sources = File("src/main/java")
    private val playback = File(sources, "com/dd3boh/outertune/playback")
    private val service = File(playback, "MusicService.kt").readText()
    private val downloads = File(playback, "DownloadUtil.kt").readText()

    @Test
    fun `nothing in playback waits in runBlocking for a Flow from the database`() {
        val found = mutableListOf<String>()
        playback.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            flowReadsUnderRunBlocking(text).forEach { found += place(file, text, it) }
        }
        assertEquals("a Flow from the database read under runBlocking:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `the search finds the read it is there for`() {
        val was = """
            val stored = runCatching {
                runBlocking(Dispatchers.IO) { database.format(mediaId).first() }
            }.getOrNull()?.qualityTier
            song = runBlocking { database.song(dataSpec.key).first()?.toMediaMetadata() }
            // runBlocking { database.format(mediaId).first() }
            val row = database.readOrNull { formatRow(mediaId) }
            val data = runBlocking(Dispatchers.IO) { YTPlayerUtils.playerResponseForPlayback(mediaId) }
            scope.launch { database.song(mediaId).first() }
            runBlocking {
                database.lyrics(id)
                    .firstOrNull()
            }
        """.trimIndent()
        val lines = flowReadsUnderRunBlocking(was).map { was.substring(0, it).count { c -> c == '\n' } + 1 }
        assertEquals("not the comment, the plain read, the network call or the one that suspends", listOf(2, 4, 10), lines)
    }

    @Test
    fun `the loader and the downloader reach the database only through the bounded read and the queued write`() {
        val blocks = mapOf(
            "MusicService's resolver" to resolver(service),
            "MusicService.shouldUpgradeCached" to body(service, "private fun shouldUpgradeCached("),
            "DownloadUtil's resolver" to resolver(downloads),
        )
        val found = mutableListOf<String>()
        blocks.forEach { (name, block) ->
            assertTrue("$name was not found, or reads no stored row any more", "database" in block)
            Regex("""\bdatabase\s*\.\s*(\w+)""").findAll(block).forEach { m ->
                if (m.groupValues[1] !in setOf("readOrNull", "query")) found += "$name: database.${m.groupValues[1]}"
            }
            assertFalse("$name reads a Flow's first value", Regex("""\.\s*first(OrNull)?\s*\(\s*\)""").containsMatchIn(block))
        }
        assertEquals("the database reached some other way:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `the rows the loader reads are the rows the Flows give`() {
        val dao = File(sources, "com/dd3boh/outertune/db/DatabaseDao.kt").readText()
        val songs = File(sources, "com/dd3boh/outertune/db/daos/SongsDao.kt").readText()
        assertEquals(queryOf(dao, "fun format(id: String?): Flow<FormatEntity?>"), queryOf(dao, "fun formatRow(id: String?): FormatEntity?"))
        assertEquals(queryOf(songs, "fun song(songId: String?): Flow<Song?>"), queryOf(songs, "fun songRow(songId: String?): SongEntity?"))
        // A read in a transaction can want the write connection, which is what these are there to stay off.
        val before = songs.substring(0, songs.indexOf("fun songRow("))
        assertFalse("songRow is in a transaction", code(before.substring(before.lastIndexOf("\n\n"))).contains("@Transaction"))
    }

    /** Where in [text] a Flow from the database is read to its first value inside the braces of a runBlocking. */
    private fun flowReadsUnderRunBlocking(text: String): List<Int> {
        val found = mutableListOf<Int>()
        Regex("""\brunBlocking\s*(\([^(){}]*\)\s*)?\{""").findAll(text).forEach { m ->
            if (isComment(text, m.range.first)) return@forEach
            val block = text.substring(m.range.last + 1, closingBrace(text, m.range.last))
            Regex("""\bdatabase\s*\.\s*\w+\s*\([^(){}]*\)\s*\.\s*(first|firstOrNull|single|singleOrNull)\s*\(""")
                .findAll(block).forEach { found += m.range.last + 1 + it.range.first }
        }
        return found.distinct().sorted()
    }

    /** The block handed to ResolvingDataSource.Factory, which media3 runs on its loader thread for every chunk. */
    private fun resolver(text: String): String {
        val factory = text.indexOf("ResolvingDataSource.Factory(")
        val open = text.indexOf("{ dataSpec ->", factory)
        if (factory < 0 || open < 0) return ""
        return text.substring(open, closingBrace(text, open))
    }

    /** The body of the function whose declaration starts with [signature]. */
    private fun body(text: String, signature: String): String {
        val at = text.indexOf(signature)
        if (at < 0) return ""
        val open = text.indexOf('{', text.indexOf(')', at))
        return text.substring(open, closingBrace(text, open))
    }

    /** The query of the DAO function declared as [declaration]. */
    private fun queryOf(text: String, declaration: String): String {
        val at = text.indexOf(declaration)
        assertTrue("$declaration was not found", at >= 0)
        return Regex("""@Query\("([^"]*)"\)""").findAll(text.substring(0, at)).last().groupValues[1]
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

    /** [text] without its comment lines, so a comment that names what is gone does not count as it. */
    private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")

    private fun lineOf(text: String, at: Int) = text.substring(text.lastIndexOf('\n', at - 1) + 1, at)
    private fun isComment(text: String, at: Int) = lineOf(text, at).trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } || "//" in lineOf(text, at)
    private fun place(file: File, text: String, at: Int) = "${file.relativeTo(sources)}:${text.substring(0, at).count { it == '\n' } + 1}"
}
