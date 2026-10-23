/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every song picked out of a list says so (Queue.songPicked), and nothing else does.
 *
 * A row tapped in a playlist, an album, the library or any other list plays the list from that
 * song, as a ListQueue with a startIndex, and only a queue marked as a pick follows the shuffle
 * button (issue #42). A list screen that left the mark off would start its list in order with
 * shuffle on again, and nothing on screen would say why. A list's Play and Shuffle buttons name no startIndex, say themselves how
 * the list is to play, and must not be marked. This reads the source, as PlayOriginCoverageTest
 * does.
 */
class PickedSongCallSitesTest {

    private val sources = File("src/main/java")

    @Test
    fun `every list started at a tapped song is marked as a pick, and only those`() {
        val unmarked = mutableListOf<String>()
        val wrongly = mutableListOf<String>()
        var picks = 0
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            Regex("""(?<!fun )\bplayQueue\(""").findAll(text).forEach { m ->
                if (isComment(text, m.range.first)) return@forEach
                val args = argumentsOf(text, m.range.last + 1)
                val list = Regex("""\bListQueue\(""").find(args) ?: return@forEach
                val listArgs = argumentsOf(args, list.range.last + 1)
                val at = "${file.relativeTo(sources)}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
                val started = Regex("""\bstartIndex\s*=""").containsMatchIn(listArgs)
                val marked = Regex("""\bsongPicked\s*=\s*true\b""").containsMatchIn(listArgs)
                if (started && !marked) unmarked += at
                if (marked && !started) wrongly += at
                if (marked) picks++
            }
        }
        assertEquals("lists started at a tapped song that are not marked songPicked:\n" + unmarked.joinToString("\n"), emptyList<String>(), unmarked)
        assertEquals("songPicked on a list that names no startIndex:\n" + wrongly.joinToString("\n"), emptyList<String>(), wrongly)
        // The sixteen of 23 Oct 2026 at least, so a reading that finds nothing cannot pass.
        assertTrue("only $picks picks found", picks >= 16)
    }

    /** Whether the match sits in a // comment, where "handled by playQueue()" is prose, not a call. */
    private fun isComment(text: String, at: Int): Boolean =
        "//" in text.substring(text.lastIndexOf('\n', at) + 1, at)

    /** The text between the opening parenthesis and its match. */
    private fun argumentsOf(text: String, start: Int): String {
        var depth = 1
        var i = start
        var inString = false
        while (depth > 0 && i < text.length) {
            val c = text[i]
            if (c == '"' && text[i - 1] != '\\') inString = !inString
            else if (!inString) when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            i++
        }
        return text.substring(start, i - 1)
    }
}
