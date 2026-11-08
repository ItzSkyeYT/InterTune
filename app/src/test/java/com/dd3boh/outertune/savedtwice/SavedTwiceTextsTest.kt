/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.savedtwice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words of Saved twice, open to translators since it ships in 0.13 (his pick of 8 Nov 2026),
 * with their French beside them, every count a plural, and the French carrying the same numbers
 * and names as the English.
 */
class SavedTwiceTextsTest {

    private fun file(folder: String) = File("src/main/res/$folder/strings-ot.xml").readText()

    private val english = file("values")
    private val french = file("values-fr")

    /** Every saved_twice* entry, string or plurals, by name, with its whole element. */
    private fun entries(xml: String): Map<String, String> =
        Regex("""<(string|plurals) name="(saved_twice\w*)"[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[2] to it.value }

    private val en = entries(english)
    private val fr = entries(french)

    private fun placeholders(text: String) = Regex("""%\d\$[ds]""").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun `every text is open to translators and has its French`() {
        assertEquals(en.keys.toString(), 21, en.size)
        for ((name, element) in en) {
            assertFalse("$name is still held from translators", element.substringBefore(">").contains("translatable=\"false\""))
            assertTrue("$name has no French", name in fr)
        }
        assertEquals("French with no English", emptySet<String>(), fr.keys - en.keys)
    }

    @Test
    fun `counts are plurals, and the French says the same numbers and names`() {
        for ((name, element) in en) {
            val counted = "%1\$d" in element
            assertEquals("$name counts but is not a plural", counted, element.startsWith("<plurals"))
            if (element.startsWith("<plurals")) {
                assertTrue("$name has no English one", "quantity=\"one\"" in element)
                assertTrue("$name has no English other", "quantity=\"other\"" in element)
                for (q in listOf("one", "many", "other")) assertTrue("$name has no French $q", "quantity=\"$q\"" in fr.getValue(name))
            }
            assertEquals("$name's French drops or adds a number or a name", placeholders(element), placeholders(fr.getValue(name)))
        }
    }

    @Test
    fun `no long dash, and the French speaks to vous`() {
        for (element in en.values + fr.values) {
            assertFalse(element, '—' in element || '–' in element)
        }
        val words = fr.values.joinToString(" ").lowercase()
        assertFalse("the app says vous", Regex("""\b(tu|ton|ta|tes|choisis)\b""").containsMatchIn(words))
        assertTrue("choisissez" in words)
    }
}
