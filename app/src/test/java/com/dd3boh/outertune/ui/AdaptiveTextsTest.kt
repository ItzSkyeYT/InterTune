/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The words of Keep radios up to date, in English and in French. */
class AdaptiveTextsTest {

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    private val strings = listOf("adaptive_queue", "adaptive_radio_description", "adaptive_queue_explain", "adaptive_pick_mark")
    private val plurals = listOf("adaptive_note_after_skip", "adaptive_note_like", "adaptive_note_finish", "adaptive_note_habits", "adaptive_note_dropped")

    private fun plural(text: String, name: String): String =
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()

    private fun string(text: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()

    @Test
    fun `every new text is there in English and in French`() {
        for (name in strings) {
            assertTrue("$name has no English", string(english, name).isNotBlank())
            assertTrue("$name has no French", string(french, name).isNotBlank())
        }
        for (name in plurals) {
            assertTrue("$name has no English", plural(english, name).isNotBlank())
            assertTrue("$name has no French", plural(french, name).isNotBlank())
        }
    }

    @Test
    fun `the French plurals have one, many and other, and the English one and other`() {
        for (name in plurals) {
            val fr = Regex("""quantity="(\w+)"""").findAll(plural(french, name)).map { it.groupValues[1] }.toSet()
            assertEquals(name, setOf("one", "many", "other"), fr)
            val en = Regex("""quantity="(\w+)"""").findAll(plural(english, name)).map { it.groupValues[1] }.toSet()
            assertEquals(name, setOf("one", "other"), en)
        }
    }

    @Test
    fun `each note counts, and the like's names the song`() {
        for (name in plurals) for (text in listOf(english, french)) {
            Regex("""<item quantity="\w+">(.*?)</item>""").findAll(plural(text, name)).forEach {
                assertTrue("$name: ${it.groupValues[1]}", "%1\$d" in it.groupValues[1])
            }
        }
        assertTrue("%2\$s" in plural(english, "adaptive_note_like"))
        assertTrue("%2\$s" in plural(french, "adaptive_note_like"))
    }

    @Test
    fun `no em dash, and short`() {
        for (text in listOf(english, french)) {
            for (name in strings) {
                assertFalse(name, "—" in string(text, name))
                assertTrue("$name is three sentences at most", string(text, name).count { it == '.' } <= 3)
            }
            for (name in plurals) assertFalse(name, "—" in plural(text, name))
        }
    }

    @Test
    fun `the setting no longer offers a value that reaches playlists`() {
        val frag = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/PlayerFrag.kt").readText()
        val adaptive = frag.substringAfter("fun ColumnScope.AdaptiveQueueFrag()").substringBefore("\n}\n")
        assertTrue("a switch with its explanation", "ExplainedSwitchPreference(" in adaptive && "R.string.adaptive_queue_explain" in adaptive)
        assertFalse("adaptive_queue_always" in adaptive)
    }
}
