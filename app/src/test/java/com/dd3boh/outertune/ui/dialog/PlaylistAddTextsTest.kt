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
 * The words Add to playlist says when it could not put every song in (#62), in English and in
 * French. They are said instead of closing the app, so they have to be there, count the songs, and
 * stay short and plain.
 */
class PlaylistAddTextsTest {

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    private fun plural(text: String, name: String): String =
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()

    private fun items(text: String, name: String): List<String> =
        Regex("""<item quantity="\w+">(.*?)</item>""").findAll(plural(text, name)).map { it.groupValues[1] }.toList()

    @Test
    fun `the text is there in English and in French`() {
        assertTrue(plural(english, "playlist_add_skipped").isNotBlank())
        assertTrue(plural(french, "playlist_add_skipped").isNotBlank())
    }

    @Test
    fun `French has one, many and other and English one and other`() {
        val fr = Regex("""quantity="(\w+)"""").findAll(plural(french, "playlist_add_skipped")).map { it.groupValues[1] }.toSet()
        val en = Regex("""quantity="(\w+)"""").findAll(plural(english, "playlist_add_skipped")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("one", "many", "other"), fr)
        assertEquals(setOf("one", "other"), en)
    }

    @Test
    fun `every form counts the songs`() {
        for (text in listOf(english, french)) for (item in items(text, "playlist_add_skipped")) {
            assertTrue(item, "%1\$d" in item)
        }
    }

    @Test
    fun `no em dash, one sentence`() {
        for (text in listOf(english, french)) for (item in items(text, "playlist_add_skipped")) {
            assertFalse(item, '—' in item || '–' in item)
            assertEquals(item, 1, item.count { it == '.' })
        }
    }
}
