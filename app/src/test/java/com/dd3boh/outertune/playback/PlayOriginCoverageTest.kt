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
 * Every screen that starts playback says where from.
 *
 * playQueue takes an origin that defaults to UNKNOWN, which is the right default for a call site
 * nobody has looked at yet and the wrong final state for any screen. This reads the source, because
 * there is no other way to make a default argument fail to compile. Radio calls are exempt: the
 * isRadio flag is mapped to the RADIO origin inside playQueue.
 */
class PlayOriginCoverageTest {

    private val screens = File("src/main/java/com/dd3boh/outertune/ui/screens")

    @Test
    fun `every playQueue call in a screen carries an origin`() {
        val untagged = mutableListOf<String>()
        screens.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("playerConnection.playQueue(", from)
                if (at < 0) break
                val args = argumentsOf(text, at + "playerConnection.playQueue(".length)
                if ("origin =" !in args && "isRadio = true" !in args) {
                    untagged += "${file.relativeTo(screens)}:${text.substring(0, at).count { it == '\n' } + 1}"
                }
                from = at + 1
            }
        }
        assertEquals("playQueue calls with no origin:\n" + untagged.joinToString("\n"), emptyList<String>(), untagged)
    }

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
