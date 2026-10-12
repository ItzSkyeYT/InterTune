/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the import screen says of itself: all of it in English and in French, and among it the one
 * sentence that says what an import does not bring.
 *
 * Somebody who moves here from another app has years of likes and skips there. The import brings
 * the playlists and none of that: the recommendations here learn from what is played here and from
 * nothing else. That is said on the page that offers the import, before a file is chosen.
 */
class LibraryImportTextsTest {
    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\n", "\n") }

    private val english = strings("values")
    private val french = strings("values-fr")
    private val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/LibraryImportScreen.kt").readText()

    private val honest = "import_library_recommendations"

    private fun sentences(text: String) = Regex("[.!?](?=\\s|$)").findAll(text).count()

    @Test
    fun `every text of the import is in French as well as in English`() {
        val own = english.keys.filter { it.startsWith("import_library_") }
        assertTrue("the import's texts were not found", own.size >= 45)
        for (name in own) assertTrue("$name has no French", name in french)
        // And the screen shows nothing that is in neither.
        val shown = Regex("""R\.string\.(import_library_[a-z0-9_]+)""").findAll(screen).map { it.groupValues[1] }.toSet()
        for (name in shown) assertTrue("$name is shown and is not a text", name in english)
    }

    @Test
    fun `the page that offers the import says what it does not bring`() {
        val intro = screen.substringAfter("private fun Intro()").substringBefore("@Composable")
        assertTrue("the sentence is not on the page that offers a file", "R.string.$honest" in intro)
        // Before the small print about YouTube's own transfer, not after it.
        assertTrue(intro.indexOf("R.string.$honest") < intro.indexOf("R.string.import_library_premium"))
    }

    @Test
    fun `it is two short sentences in plain words, in both languages`() {
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            val text = texts.getValue(honest)
            assertEquals("$language: $text", 2, sentences(text))
            assertTrue("$language is ${text.split(' ').size} words", text.split(' ').size <= 32)
            assertFalse(language, '—' in text || '–' in text)
            assertFalse("$language has a line break", '\n' in text)
        }
    }

    @Test
    fun `it says playlists come, and that the recommendations know only what is played here`() {
        val en = english.getValue(honest)
        assertTrue(en, "playlists" in en)
        assertTrue(en, "recommendations learn only from what you play here" in en)
        assertTrue(en, "liked or skipped" in en)
        val fr = french.getValue(honest)
        assertTrue(fr, "playlists" in fr)
        assertTrue(fr, "recommandations" in fr && "ici" in fr)
        // It promises nothing about the recommendations after an import.
        for (text in listOf(en, fr)) assertFalse(text, "will" in text.split(' ') || "bientôt" in text)
    }

    @Test
    fun `a run that YouTube turned away says so, and says the songs found are kept`() {
        val paused = "import_library_paused"
        assertTrue("R.string.$paused" in screen)
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            val text = texts.getValue(paused)
            assertTrue("$language: $text", sentences(text) <= 3)
            assertFalse(language, '—' in text)
        }
        assertTrue("Continue" in english.getValue(paused) && "kept" in english.getValue(paused))
        assertTrue("Continuer" in french.getValue(paused) && "gardé" in french.getValue(paused))
        // Offered the same two ways on as a run that was stopped by hand.
        assertTrue("LibraryImport.Stage.STOPPED, LibraryImport.Stage.OFFLINE, LibraryImport.Stage.PAUSED -> {" in screen)
    }
}
