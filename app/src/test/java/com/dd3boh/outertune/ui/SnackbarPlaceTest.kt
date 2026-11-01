/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Exclusions page showed its Undo at the top of the screen, over the page's first line (seen on
 * the emulator, 1 Nov 2026): its snackbar was not pinned to the bottom, as every other screen's is.
 */
class SnackbarPlaceTest {
    @Test
    fun `every screen's snackbar sits at the bottom`() {
        val screens = File("src/main/java/com/dd3boh/outertune/ui/screens").walkTopDown().filter { it.extension == "kt" }
        val wrong = mutableListOf<String>()
        var seen = 0
        for (file in screens) {
            val text = file.readText()
            for (m in Regex("""\bSnackbarHost\(""").findAll(text)) {
                seen++
                // The host's own arguments, up to the parenthesis that closes them.
                var depth = 0
                var end = m.range.last
                while (end < text.length) {
                    when (text[end]) { '(' -> depth++; ')' -> if (--depth == 0) break }
                    end++
                }
                if ("align(Alignment.Bottom" !in text.substring(m.range.first, end)) wrong += "${file.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        assertTrue("no snackbars found", seen > 10)
        assertEquals("snackbars not at the bottom", emptyList<String>(), wrong)
    }
}
