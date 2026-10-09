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
 * An explanation that names a setting, one of its choices, a button or a row writes the name in
 * quotation marks, as it stands in the app, so that it reads as something to go and find and not
 * as part of the sentence: "Tidy Home rows" and not Tidy Home rows. Asked for on 9 Oct 2026.
 *
 * Held for the names that cannot be mistaken for ordinary words, in the two languages written by
 * hand. English writes \"name\" in the file, French « name ». Home, History, Stats and Settings
 * are places, written as any word is.
 */
class ExplanationsNameSettingsTest {
    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun isExplanation(name: String) =
        name.endsWith("_info") || name.endsWith("_explain") || name.endsWith("_tooltip") || name in OTHERS

    /** How often [name] stands in [text] without [open] before it and [close] after it. */
    private fun bare(text: String, name: String, names: List<String>, open: String, close: String): Int {
        var count = 0
        var at = text.indexOf(name)
        while (at >= 0) {
            val end = at + name.length
            // Inside a longer name of the list, which is looked at by itself.
            val inLonger = names.any { longer ->
                longer.length > name.length && (0..longer.length - name.length).any { off ->
                    longer.startsWith(name, off) && text.startsWith(longer, at - off)
                }
            }
            if (!inLonger && !(text.substring(0, at).endsWith(open) && text.startsWith(close, end))) count++
            at = text.indexOf(name, end)
        }
        return count
    }

    @Test
    fun `in English a setting named in an explanation is in quotation marks`() {
        val found = mutableListOf<String>()
        for ((key, text) in strings("values").filter { isExplanation(it.key) }) {
            for (name in ENGLISH) if (bare(text, name, ENGLISH, "\\\"", "\\\"") > 0) found += "$key: $name"
        }
        assertTrue(found.toString(), found.isEmpty())
    }

    @Test
    fun `in French it is in guillemets`() {
        val found = mutableListOf<String>()
        for ((key, text) in strings("values-fr").filter { isExplanation(it.key) }) {
            for (name in FRENCH) if (bare(text, name, FRENCH, "« ", " »") > 0) found += "$key: $name"
        }
        assertTrue(found.toString(), found.isEmpty())
    }

    @Test
    fun `the names are named somewhere, so the two tests above look at something`() {
        val english = strings("values").filter { isExplanation(it.key) }.values.joinToString("\n")
        val french = strings("values-fr").filter { isExplanation(it.key) }.values.joinToString("\n")
        assertTrue(ENGLISH.filter { "\\\"$it\\\"" !in english }.toString(), ENGLISH.all { "\\\"$it\\\"" in english })
        assertTrue(FRENCH.filter { "« $it »" !in french }.toString(), FRENCH.all { "« $it »" in french })
    }

    private companion object {
        val OTHERS = setOf(
            "quick_picks_source_description",
            "quick_picks_source_description_basic",
            "min_playback_duration_description",
            "proxy_examples",
            "token_adv_login_description",
        )

        val ENGLISH = listOf(
            "Quick picks source", "Quick picks", "Best recommendations", "Try both", "Tidy Home rows",
            "Rank with your listening", "Discover something new", "Learn from listening", "Pause listen history",
            "Clear listen history", "Rebuild what it learned", "Reset what it learned", "Privacy and history",
            "Your data", "Keep listening", "What you have heard", "Surround upmix", "Not this song",
            "Less of this artist", "Never this artist", "Last.fm only", "YouTube only", "Your library",
            "Songs that fit what I am playing now",
        )

        val FRENCH = listOf(
            "Source de la Sélection Rapide", "Sélection Rapide", "Meilleures recommandations", "Essayer les deux",
            "Épurer les rangées de l\\'accueil", "Classer selon votre écoute", "Découvrir du nouveau",
            "Apprendre de l\\'écoute", "Suspendre l\\'historique d\\'écoute", "Effacer l\\'historique d\\'écoute",
            "Reconstruire l\\'apprentissage", "Réinitialiser l\\'apprentissage", "Confidentialité et historique",
            "Vos données", "Écoute continue", "Ce que vous avez entendu", "Conversion surround", "Pas ce titre",
            "Moins de cet artiste", "Jamais cet artiste", "Last.fm uniquement", "YouTube uniquement",
            "Votre bibliothèque", "Des titres qui vont avec ce que j\\'écoute en ce moment",
        )
    }
}
