/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The editor is held back with Unreleased.AUTOMIX_EDITOR: its page has no route and the player's
 * menu no entry without it. Its words wait for a yes before translators, in a file of their own,
 * English and French both, short, with no em dash.
 *
 * This reads the source, as AutomixHeldBackTest does, because a flag is a constant of the build.
 */
class MixEditorHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the route is made only under the flag`() {
        val graph = code("ui/navigation/AppNavGraph.kt")
        val guarded = graph.substringAfter("if (Unreleased.AUTOMIX_EDITOR) {").substringBefore("\n        }\n")
        assertTrue(guarded, "route = MIX_EDITOR_ROUTE" in guarded)
        assertTrue("MixEditorScreen(" in guarded)
        assertTrue("the route is made outside the flag", graph.split("MixEditorScreen(").size == 2)
    }

    @Test
    fun `the player menu offers it only under the flag`() {
        val menu = code("ui/menu/PlayerMenu.kt")
        val guarded = menu.substringAfter("if (Unreleased.AUTOMIX_EDITOR) {").substringBefore("\n        }\n")
        assertTrue(guarded, "openMixEditor(" in guarded)
        assertTrue("the entry is made outside the flag", menu.split("openMixEditor(").size == 2)
    }

    @Test
    fun `its words are in English and in French, short, with no em dash`() {
        val res = File("src/main/res")
        val english = File(res, "values/strings-mix.xml").readText()
        val french = File(res, "values-fr/strings-mix.xml").readText()
        val names = Regex("""<(string|plurals|bool) name="([a-z0-9_]+)"""").findAll(english).map { it.groupValues[2] }.toList()
        assertTrue(names.size > 100)
        for (name in names) assertTrue("$name has no French", "name=\"$name\"" in french)
        for ((language, file) in listOf("English" to english, "French" to french)) {
            assertTrue("an em dash in $language", '—' !in file)
            for (m in Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""").findAll(file)) {
                val sentences = Regex("[.!?](?=\\s|$)").findAll(m.groupValues[2]).count()
                assertTrue("${m.groupValues[1]} in $language runs past three sentences", sentences <= 3)
            }
        }
    }

    @Test
    fun `none of its words are in the files translators see yet`() {
        val res = File("src/main/res")
        for (path in listOf("values/strings-ot.xml", "values-fr/strings-ot.xml")) {
            assertTrue("$path has a mix_ text", "name=\"mix_" !in File(res, path).readText())
        }
    }
}
