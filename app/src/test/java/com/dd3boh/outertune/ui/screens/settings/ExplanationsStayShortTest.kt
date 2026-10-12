/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What stands behind an "i" button, or as a note under a setting, says what the thing does in two
 * or three short sentences. Several had grown to a page: 311 words behind one button. Nobody reads
 * that to learn what a switch is for, so the length is held here, in the two languages written by
 * hand. The other languages follow as they are translated.
 *
 * Words and paragraphs alone let four sentences through, so sentences are counted too. And the
 * notes are held as the explanations are: the line under a row's title, the note under a setting
 * and the text of a card or a dialog, which a reader meets without tapping anything.
 */
class ExplanationsStayShortTest {
    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\n", "\n") }

    /** The names an explanation goes by, and the few that are one under another name. */
    private fun isExplanation(name: String) =
        name.endsWith("_info") || name.endsWith("_explain") || name.endsWith("_tooltip") || name in OTHERS

    /** The names a note goes by. One that is an explanation by the list above is counted as that. */
    private fun isNote(name: String) = NOTE_ENDINGS.any { name.endsWith(it) } && !isExplanation(name)

    /** Everything the lengths are held for, but for the texts that say what is sent. */
    private fun isHeld(name: String) = (isExplanation(name) || isNote(name)) && name !in SAY_WHAT_IS_SENT

    /** Words, not marks: French sets its guillemets, colons and semicolons off with a space. */
    private fun words(text: String) = text.split(Regex("\\s+")).count { word -> word.any { it.isLetterOrDigit() } }

    /** A full stop, a question mark or an exclamation mark that ends something: the one in Last.fm does not. */
    private fun sentences(text: String) = Regex("[.!?](?=\\s|$)").findAll(text).count()

    @Test
    fun `no explanation or note runs past sixty words, in English or in French`() {
        for (folder in listOf("values", "values-fr")) {
            val long = strings(folder).filter { isHeld(it.key) && words(it.value) > MAX_WORDS }
            assertTrue("$folder: " + long.map { "${it.key} (${words(it.value)} words)" }, long.isEmpty())
        }
    }

    @Test
    fun `nor past three sentences`() {
        for (folder in listOf("values", "values-fr")) {
            val long = strings(folder).filter { isHeld(it.key) && it.key !in NOT_SHOWN && sentences(it.value) > MAX_SENTENCES }
            assertTrue("$folder: " + long.map { "${it.key} (${sentences(it.value)} sentences)" }, long.isEmpty())
        }
    }

    @Test
    fun `an explanation or a note is one or two paragraphs`() {
        for (folder in listOf("values", "values-fr")) {
            val split = strings(folder).filter { (isExplanation(it.key) || isNote(it.key)) && it.value.split("\n\n").size > 2 }
            assertTrue("$folder: ${split.keys}", split.isEmpty())
        }
    }

    @Test
    fun `the lists have texts in them`() {
        // A renamed file or a changed naming habit would otherwise leave the tests above passing on nothing.
        val english = strings("values")
        assertTrue(english.keys.count { isExplanation(it) } > 40)
        assertTrue(english.keys.count { isNote(it) } > 100)
        assertTrue((OTHERS + SAY_WHAT_IS_SENT + NOT_SHOWN).all { it in english })
        // And a list of exceptions that no longer needs one of its names says so.
        for (folder in listOf("values", "values-fr")) {
            val texts = strings(folder)
            for (name in SAY_WHAT_IS_SENT) {
                val text = texts.getValue(name)
                assertTrue("$folder $name is short now", words(text) > MAX_WORDS || sentences(text) > MAX_SENTENCES)
            }
            for (name in NOT_SHOWN) assertTrue("$folder $name is short now", sentences(texts.getValue(name)) > MAX_SENTENCES)
        }
    }

    private companion object {
        const val MAX_WORDS = 60
        const val MAX_SENTENCES = 3

        val OTHERS = setOf(
            "quick_picks_source_description",
            "quick_picks_source_description_basic",
            "min_playback_duration_description",
            "proxy_examples",
            "token_adv_login_description",
        )

        val NOTE_ENDINGS = listOf("_description", "_desc", "_note", "_body")

        /**
         * The line under the usage count switch and the two questions put before anything is
         * sent. Each has to say all of what goes, where to and how to stop it, and is read once,
         * when deciding, which is not what a note on a switch is for. Their paragraphs are held
         * as the others' are, their words and sentences are not.
         */
        val SAY_WHAT_IS_SENT = setOf(
            "usage_count_enabled_description",
            "usage_count_opt_in_body",
            "lastfm_opt_in_body",
        )

        /**
         * Four sentences, and left as it is: the picture over the stage sliders stands in for
         * this "i" in every build. It is only what the row falls back to without the picture
         * (StageSliderRow in PlayerFrag).
         */
        val NOT_SHOWN = setOf("stage_width_explain")
    }
}
