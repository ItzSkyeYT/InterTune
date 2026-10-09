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
 */
class ExplanationsStayShortTest {
    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\n", "\n") }

    /** The names an explanation goes by, and the few that are one under another name. */
    private fun isExplanation(name: String) =
        name.endsWith("_info") || name.endsWith("_explain") || name.endsWith("_tooltip") || name in OTHERS

    private fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }

    @Test
    fun `no explanation runs past sixty words, in English or in French`() {
        for (folder in listOf("values", "values-fr")) {
            val long = strings(folder).filter { isExplanation(it.key) && words(it.value) > MAX_WORDS }
            assertTrue("$folder: " + long.map { "${it.key} (${words(it.value)} words)" }, long.isEmpty())
        }
    }

    @Test
    fun `an explanation is one or two paragraphs`() {
        for (folder in listOf("values", "values-fr")) {
            val split = strings(folder).filter { isExplanation(it.key) && it.value.split("\n\n").size > 2 }
            assertTrue("$folder: ${split.keys}", split.isEmpty())
        }
    }

    @Test
    fun `the list has explanations in it`() {
        // A renamed file or a changed naming habit would otherwise leave the two tests above passing on nothing.
        assertTrue(strings("values").keys.count { isExplanation(it) } > 60)
        assertTrue(OTHERS.all { it in strings("values") })
    }

    private companion object {
        const val MAX_WORDS = 60

        val OTHERS = setOf(
            "quick_picks_source_description",
            "quick_picks_source_description_basic",
            "min_playback_duration_description",
            "proxy_examples",
            "token_adv_login_description",
        )
    }
}
