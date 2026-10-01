/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Everything in the app that starts playback says where from.
 *
 * playQueue takes an origin that defaults to UNKNOWN, which is the right default for a call site
 * nobody has looked at yet and the wrong final state for any of them. This reads the source, because
 * there is no other way to make a default argument fail to compile. Radio calls are exempt: the
 * isRadio flag is mapped to the RADIO origin inside playQueue.
 *
 * It used to read only ui/screens, and only "playerConnection.playQueue(": the album cards' play
 * buttons (ui/component), playing a recognised song ("playerConnection?.playQueue("), opening a
 * song link, and Play next or Add to queue with nothing playing (inside MusicService) all slipped
 * past it, and their listens read "not recorded" on How it's doing.
 */
class PlayOriginCoverageTest {

    private val sources = File("src/main/java")

    @Test
    fun `every playQueue call carries an origin`() {
        val untagged = mutableListOf<String>()
        sources.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            Regex("""(?<!fun )\bplayQueue\(""").findAll(text).forEach { m ->
                val args = argumentsOf(text, m.range.last + 1)
                if ("origin =" !in args && "isRadio = true" !in args && !isComment(text, m.range.first)) {
                    untagged += "${file.relativeTo(sources)}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
                }
            }
        }
        assertEquals("playQueue calls with no origin:\n" + untagged.joinToString("\n"), emptyList<String>(), untagged)
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
