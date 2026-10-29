/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A screen keeps the database flow it collects, and does not make a new one each time it is drawn.
 *
 * `database.song(id).collectAsState(...)` in a composable asks the database for a new flow on
 * every recomposition, and collectAsState starts over whenever its flow is another object: the
 * query runs again each time the composable is drawn. Harmless while what comes back equals what
 * was there. The day it does not (RowsCompareByValueTest tells how that happened), the new value
 * draws the composable again, which makes a new flow, which reads again: a loop at the screen's
 * frame rate that the screen going off does not stop. So the flow is made once, inside remember.
 * Read from the source, comments taken out.
 */
class DatabaseFlowsAreKeptTest {

    @Test
    fun `no composable collects a database flow it has just made`() {
        val made = Regex("""\bdatabase\s*\.\s*\w+\([^()\n]*\)\s*\.collectAsState""")
        val found = File("src/main/java/com/dd3boh/outertune/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                stripComments(file.readText()).lineSequence()
                    .filter { made.containsMatchIn(it) && "remember" !in it.substringBefore("database") }
                    .map { "${file.name}: ${it.trim()}" }
            }
            .toList()
        assertTrue("made anew on every recomposition, wrap each in remember(key) { }:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `the search still finds what it is looking for`() {
        // So the test above cannot pass because the pattern stopped matching anything.
        val made = Regex("""\bdatabase\s*\.\s*\w+\([^()\n]*\)\s*\.collectAsState""")
        assertTrue(made.containsMatchIn("val song by database.song(item.id).collectAsState(initial = null)"))
        assertTrue(made.containsMatchIn("val n by remember { database.count() }.collectAsState(initial = 0)").not())
        val kept = File("src/main/java/com/dd3boh/outertune/ui/component/items/Items.kt").readText()
        assertTrue("Items.kt no longer reads a song for its badges", "remember(item.id) { if (item is SongItem) database.song(item.id) else flowOf(null) }.collectAsState" in kept)
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}
