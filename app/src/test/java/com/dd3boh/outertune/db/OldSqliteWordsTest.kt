/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app runs its SQL on the phone's own SQLite, and Android 7 to 10 ship 3.9 to 3.22, which know
 * no TRUE or FALSE: "liked = true" reads true as the name of a column, and the statement fails
 * ("no such column: true"). The tests here run on a newer SQLite, which takes it, so only reading
 * the SQL can tell. InnerTune backups were refused on those phones for that one word (Migration12To13).
 */
class OldSqliteWordsTest {

    /** Every string in the app's code, raw strings included, with the file and line it starts on. */
    private fun strings(): List<Triple<File, Int, String>> {
        val literal = Regex("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        return File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val code = file.readText()
            val newlines = code.indices.filter { code[it] == '\n' }
            literal.findAll(code).map { m ->
                val line = newlines.binarySearch(m.range.first).let { if (it < 0) -it - 1 else it } + 1
                Triple(file, line, m.groups[1]?.value ?: m.groups[2]?.value.orEmpty())
            }
        }.toList()
    }

    @Test
    fun `no SQL compares with TRUE or FALSE, which Android 10 and older cannot read`() {
        val sql = Regex("\\b(SELECT|UPDATE|DELETE|INSERT|WHERE)\\b", RegexOption.IGNORE_CASE)
        val word = Regex("(=|!=|<>|\\bIS(\\s+NOT)?)\\s*(TRUE|FALSE)\\b", RegexOption.IGNORE_CASE)
        val found = strings().filter { (_, _, text) -> sql.containsMatchIn(text) && word.containsMatchIn(text) }
            .map { (file, line, text) -> "${file.path}:$line ${text.trim().take(120)}" }
        assertEquals("use 1 and 0 instead", emptyList<String>(), found)
    }

    @Test
    fun `the strings of the app are read, so the rule above has something to look at`() {
        val all = strings()
        assertTrue("too few strings read: ${all.size}", all.size > 1000)
        assertTrue(all.any { (file, _, text) -> file.name == "MusicDatabase.kt" && text == "SELECT * from song WHERE liked = 1" })
    }
}
