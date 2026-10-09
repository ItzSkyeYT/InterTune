/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.engine.EngineTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a listener reads about the lean and the chips: short, the same in both English files, there
 * in French, and in step with the numbers the engine uses.
 */
class LeanTextsTest {
    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\n", "\n") }

    private val english = strings("values")
    private val canadian = strings("values-en-rCA")
    private val french = strings("values-fr")

    private val leanInfo = "quick_picks_lean_info"
    private val chipsInfo = "context_chips_info"
    private val strictLine = "quick_picks_lean_new_only_strict"

    private fun paragraphs(text: String) = text.split("\n\n")
    private fun sentences(paragraph: String) = Regex("[.!?](?=\\s|$)").findAll(paragraph).count()

    @Test
    fun `the explanations are one paragraph of three sentences at most`() {
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            for (name in listOf(leanInfo, chipsInfo)) {
                val text = texts.getValue(name)
                assertEquals("$language $name", 1, paragraphs(text).size)
                assertTrue("$language $name", sentences(text) in 2..3)
            }
            assertEquals(language, 1, sentences(texts.getValue(strictLine)))
            for (name in listOf(leanInfo, chipsInfo, strictLine)) assertFalse("$language $name", '\u2014' in texts.getValue(name))
        }
    }

    @Test
    fun `Canadian English reads as English does`() {
        for (name in listOf(leanInfo, chipsInfo, strictLine)) assertEquals(name, english.getValue(name), canadian.getValue(name))
    }

    @Test
    fun `the lean's explanation says which choice follows the session, in the option's own words, and gives no count`() {
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            assertTrue(language, texts.getValue("quick_picks_lean_similar") in texts.getValue(leanInfo))
            // Three songs as shipped, but the developer page can change it or turn it off, so the text says a few.
            assertTrue(EngineTuning.entries.any { it.name == "leanSimilarRebuildListens" })
            val text = texts.getValue(leanInfo)
            assertFalse(language, text.any { it.isDigit() } || "three" in text || "trois" in text)
        }
    }

    @Test
    fun `the chips' explanation gives no count, and does not say Favourites holds only liked songs`() {
        // The moods follow your own plays after ContextChip.MIN_TAGGED of them. The text used to
        // give that number; it says what the chips do now and leaves the number to the engine.
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            val text = texts.getValue(chipsInfo)
            assertFalse(language, text.any { it.isDigit() } || "eight" in text || "huit" in text)
        }
        // It used to say Favourites builds only from liked songs, so unliked songs still appear.
        assertFalse("unliked" in english.getValue(chipsInfo))
        assertFalse("non aimés" in french.getValue(chipsInfo))
        assertFalse("only" in english.getValue(chipsInfo))
    }
}
