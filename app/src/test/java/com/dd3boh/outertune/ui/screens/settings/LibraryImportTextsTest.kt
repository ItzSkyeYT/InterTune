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
    fun `the page that offers the import is short enough to read standing up`() {
        // Six paragraphs and about 165 words stood between the title and "Choose a file". What
        // the next screen shows by itself is not said here, and each tool gets one line.
        val intro = screen.substringAfter("private fun Intro()").substringBefore("@Composable")
        val shown = Regex("""R\.string\.(import_library_[a-z0-9_]+)""").findAll(intro).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("import_library_intro", "import_library_spotify", "import_library_others", honest, "import_library_premium"),
            shown,
        )
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            val words = shown.sumOf { texts.getValue(it).split(' ').size }
            assertTrue("$language: $words words", words <= 112)
            for (tool in listOf("import_library_spotify", "import_library_others")) {
                assertTrue("$language, $tool", sentences(texts.getValue(tool)) <= 2)
            }
        }
        // The tools are still named, and where Exportify lives.
        assertTrue("exportify.app" in english.getValue("import_library_spotify") && "exportify.app" in french.getValue("import_library_spotify"))
        for (tool in listOf("TuneMyMusic", "Soundiiz", "Apple Music")) {
            assertTrue(tool, tool in english.getValue("import_library_others") && tool in french.getValue("import_library_others"))
        }
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

    // The first result for every song still to check, in one tap.

    private val forAll = "import_library_take_first_all"
    private val forRest = "import_library_take_first_rest"
    private val canChange = "import_library_take_first_note"

    /** The values a text takes, as written: %1$d, %2$s. */
    private fun placeholders(text: String) = Regex("""%(?:\d+\$)?[a-zA-Z]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `the French of each text takes the values the English takes`() {
        val own = english.filterKeys { it.startsWith("import_library_") }
        assertTrue("no text of the import takes a value", own.values.any { placeholders(it).isNotEmpty() })
        for ((name, text) in own) assertEquals(name, placeholders(text), placeholders(french.getValue(name)))
    }

    @Test
    fun `the tap that takes the first one for all says so in a few plain words, in both languages`() {
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            for (name in listOf(forAll, forRest)) {
                val text = texts.getValue(name)
                assertTrue("$language: ${text.length} letters is long for a button", text.length <= 40)
                assertFalse("$language: $text", text.any { it in ".!\u2014\u2013\n" })
                assertTrue("$language: $text", placeholders(text).isEmpty())
            }
            val line = texts.getValue(canChange)
            assertEquals("$language: $line", 1, sentences(line))
            assertTrue("$language is ${line.split(' ').size} words", line.split(' ').size <= 14)
            assertFalse("$language: $line", line.any { it in "!\u2014\u2013\n" })
        }
        // What it says is what it does: the first one, for all of them while nobody has decided
        // anything by hand, and for the rest once somebody has, whose choices stay.
        assertTrue("first" in english.getValue(forAll) && english.getValue(forAll).endsWith("for all"))
        assertTrue("first" in english.getValue(forRest) && english.getValue(forRest).endsWith("for the rest"))
        assertTrue("premier" in french.getValue(forAll) && french.getValue(forAll).endsWith("pour tous"))
        assertTrue("premier" in french.getValue(forRest) && french.getValue(forRest).endsWith("pour les autres"))
        // And the line under it says that nothing it picks is final.
        assertTrue("changed" in english.getValue(canChange) && "playlists" in english.getValue(canChange))
        assertTrue("modifiable" in french.getValue(canChange) && "playlists" in french.getValue(canChange))
    }

    @Test
    fun `it stands where the review list starts, as words alone, and asks nothing before it acts`() {
        val head = screen.substringAfter("item(key = \"review-title\")").substringBefore("items(progress.review")
        assertTrue("it is not under the heading of the songs to check", "TakeFirst(" in head)
        // Only when the session says it is worth offering: more than three songs waiting, and a
        // list that the lookups are not still adding to.
        assertTrue(Regex("""if \(state\.offersFirsts\) \{\s*TakeFirst\(""").containsMatchIn(head))
        assertEquals(1, Regex("""\bTakeFirst\(""").findAll(screen.substringBefore("private fun TakeFirst(")).count())
        // Straight to the session: no dialog stands between the tap and the picks.
        assertTrue("onClick = session::pickFirsts," in head)
        assertTrue("rest = progress.undecided < progress.review.size," in head)

        val button = screen.substringAfter("private fun TakeFirst(").substringBefore("\n}\n")
        assertTrue("words alone, where Create playlists is filled", "TextButton(" in button)
        assertFalse(Regex("""\bButton\(""").containsMatchIn(button.replace("TextButton(", "")))
        assertTrue("no smaller to the finger than the buttons of setup", ".heightIn(min = 48.dp)" in button)
        assertTrue("if (rest) R.string.$forRest else R.string.$forAll" in button)
        assertTrue("R.string.$canChange" in button)
    }
}
