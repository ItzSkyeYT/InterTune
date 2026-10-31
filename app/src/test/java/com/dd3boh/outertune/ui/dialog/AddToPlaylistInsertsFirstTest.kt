/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every way into Add to playlist has its songs in the song table before the add runs (#62).
 *
 * The picker is handed song ids, and the map row it writes has a foreign key on the song. A song
 * that has only been seen in a search result, a radio's upcoming songs or a recognition result is
 * not in the table until something writes it, and the add used to find that out by crashing: the
 * insert fails its foreign key, `INSERT OR IGNORE` does not cover that, and the exception ended
 * the process (0.12.5, Android 16).
 *
 * Two ways in broke it. Some wrote nothing (the recognition candidate's Add to playlist, a queue
 * that was not saved). Others wrote with `database.transaction { }`, which only queues the write
 * on Room's four executor threads and returns, while the add that follows is a blocking call on
 * the caller's own thread: behind a library sync's backlog the add got to the database first.
 * `transactionNow { }` returns when the rows are committed.
 *
 * This reads the source, as NoWaitingInTransactionsTest does, because the failure needs a busy
 * database and no JVM test of the dialog has one.
 */
class AddToPlaylistInsertsFirstTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** Songs that may never have been written: each call has to write them itself, before it returns. */
    private val writesFirst = mapOf(
        "ui/menu/PlayerMenu.kt" to 1,
        "ui/menu/YouTubeSongMenu.kt" to 1,
        "ui/menu/YouTubePlaylistMenu.kt" to 1,
        "ui/menu/QueueMenu.kt" to 1,
        "ui/menu/SelectionSongsMenu.kt" to 1,
        "ui/screens/RecognitionScreen.kt" to 2,
        "ui/dialog/ImportM3uDialog.kt" to 1,
    )

    /** Songs read from the database a moment ago, so they are rows: nothing to write first. */
    private val alreadyRows = setOf(
        "ui/menu/SongMenu.kt",
        "ui/menu/AlbumMenu.kt",
        "ui/menu/FolderMenu.kt",
        "ui/menu/PlaylistMenu.kt",
        "ui/menu/YouTubeAlbumMenu.kt",
    )

    private fun kotlinFiles() = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rel(file: File) = file.relativeTo(sources).path

    /** The text of each `AddToPlaylistDialog(...)` call in [text], from its parenthesis to the matching one. */
    private fun calls(text: String): List<String> {
        val found = mutableListOf<String>()
        Regex("""(?<!fun )\bAddToPlaylistDialog\(""").findAll(text).forEach { m ->
            var depth = 0
            var i = m.range.last
            var inString = false
            val start = i
            while (i < text.length) {
                val c = text[i]
                when {
                    inString -> if (c == '\\') i++ else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '/' && text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    c == '(' -> depth++
                    c == ')' -> { depth--; if (depth == 0) { found += text.substring(start, i + 1); break } }
                }
                i++
            }
        }
        return found
    }

    private fun callsByFile(): Map<String, List<String>> =
        kotlinFiles().associate { rel(it) to calls(it.readText()) }.filterValues { it.isNotEmpty() }

    @Test
    fun `every way into the picker has been looked at`() {
        val known = writesFirst.keys + alreadyRows
        val all = callsByFile().keys
        assertEquals("a new way into Add to playlist has to say here where its songs come from", known, all)
    }

    @Test
    fun `the counts of calls are the ones the audit saw`() {
        val byFile = callsByFile()
        for ((file, count) in writesFirst) assertEquals(file, count, byFile[file]?.size)
    }

    @Test
    fun `no call queues a write it then depends on`() {
        val queued = Regex("""\.(transaction|query)\s*\{""")
        val offenders = callsByFile().flatMap { (file, calls) ->
            calls.filter { queued.containsMatchIn(it) }.map { "$file: ${queued.find(it)!!.value}" }
        }
        assertEquals("a write queued with transaction or query may land after the add; use transactionNow", emptyList<String>(), offenders)
    }

    @Test
    fun `the ways in that may hold unwritten songs write them before they return`() {
        val writes = Regex("""transactionNow|database\.insert\(|viewModel\.prepare""")
        val offenders = callsByFile().flatMap { (file, calls) ->
            if (file !in writesFirst) emptyList()
            else calls.filterNot { "onPreAdd" in it && writes.containsMatchIn(it) }.map { "$file: no onPreAdd that writes the songs" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the picker's three ways of adding are one path`() {
        val dialog = File(sources, "ui/dialog/AddToPlaylistDialog.kt").readText()
        // The duplicates prompt's two buttons queued the add with database.transaction and called
        // onAdded before it had run.
        assertFalse("the add goes through the one helper", Regex("""database\.transaction\s*\{""").containsMatchIn(dialog))
        assertTrue(Regex("""addSongToPlaylist\(""").findAll(dialog).count() == 1)
    }

    @Test
    fun `the search finds a call and its parenthesis`() {
        val text = """
            fun AddToPlaylistDialog(
                navController: NavController,
            ) { }
            if (a) {
                AddToPlaylistDialog(
                    navController = navController,
                    // a comment with a ) in it
                    songIds = listOf("(", song.id),
                    onPreAdd = { database.transaction { insert(song) }; listOf(song.id) },
                )
            }
        """.trimIndent()
        val found = calls(text)
        assertEquals(1, found.size)
        assertTrue("database.transaction {" in found[0] && found[0].trimEnd().endsWith(")"))
    }
}
