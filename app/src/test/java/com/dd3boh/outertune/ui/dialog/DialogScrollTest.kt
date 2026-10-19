/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A dialog scrolls inside DefaultDialog, never around it.
 *
 * What a dialog says gives way to its buttons (a weight in a column). A scroll put on the whole
 * dialog through its modifier measures that column with no height limit, and the part that gives
 * way then gets no height at all: the lyrics search showed its title and buttons with nothing
 * between them (19 Oct 2026), and a name asked for on a phone on its side lost its field and its
 * buttons.
 */
class DialogScrollTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no dialog is scrolled through its modifier`() {
        val offenders = sources.flatMap { file ->
            val text = file.readText()
            Regex("""DefaultDialog\(""").findAll(text).mapNotNull { call ->
                val head = text.substring(call.range.first, minOf(text.length, call.range.first + 400))
                val modifier = Regex("""modifier\s*=\s*([^\n]*)""").find(head)?.groupValues?.get(1).orEmpty()
                if ("verticalScroll" in modifier) "${file.name}: $modifier" else null
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the part that gives way to the buttons is the part that scrolls`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/Dialog.kt").readText()
        assertTrue(dialog.contains(".weight(1f, fill = false)\n                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)"))
        val lyrics = File("src/main/java/com/dd3boh/outertune/ui/menu/LyricsMenu.kt").readText()
        assertTrue(lyrics.contains("DefaultDialog(\n            scrollable = true,"))
    }
}
