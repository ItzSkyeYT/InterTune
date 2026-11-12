/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #150: a local file with no artist tag has no artist (the scanners drop blank names), and View
 * artist in its menu opened the choice of artists with nothing in it. Every menu that offers View
 * artist shows it only when there is an artist to go to, as the player's and YouTube's menus
 * already did. Read from the source, comments taken out.
 */
class ViewArtistTest {

    private val menus = File("src/main/java/com/dd3boh/outertune/ui/menu")

    private fun code(file: File) = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `View artist is offered only when there is an artist to go to`() {
        val offering = menus.listFiles { f -> f.extension == "kt" }!!.filter { "R.string.view_artist" in code(it) }
        assertTrue("found ${offering.map { it.name }}", offering.size >= 5)
        for (file in offering) {
            val text = code(file)
            var at = text.indexOf("R.string.view_artist")
            while (at >= 0) {
                // Right above the entry: an if that asks for at least one artist.
                val entry = text.lastIndexOf("GridMenuItem(", at)
                val before = text.substring(maxOf(0, entry - 120), entry)
                assertTrue(
                    "${file.name}: View artist without a check that there is an artist:\n$before",
                    Regex("""if \([\w.]*artists\.isNotEmpty\(\)\) \{\s*$""").containsMatchIn(before),
                )
                at = text.indexOf("R.string.view_artist", at + 1)
            }
        }
    }
}
