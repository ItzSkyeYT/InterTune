/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "App language" row (AppLanguage, AppLanguageFrag): its list is the translations the app
 * carries, the same ones Android's own App languages page shows, and the phone's language is
 * asked for by handing the platform no language at all.
 */
class AppLanguageTest {

    private val res = File("src/main/res")
    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** A folder of translations as the generated locale config names it: values-pt-rBR is pt-BR, values-b+sr+Latn is sr-Latn. */
    private fun tagOf(folder: String): String {
        val qualifier = folder.removePrefix("values-")
        return if (qualifier.startsWith("b+")) qualifier.removePrefix("b+").replace('+', '-')
        else qualifier.replace("-r", "-")
    }

    @Test
    fun `the list is the translations present, and English`() {
        val folders = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
            .filter { dir -> dir.listFiles()!!.any { it.name.startsWith("strings") } }
            .map { tagOf(it.name) }
        assertTrue(folders.size.toString(), folders.size >= 40)
        assertEquals((folders + "en").sorted(), AppLanguage.TAGS.sorted())
        assertEquals("a language is listed twice", AppLanguage.TAGS.size, AppLanguage.TAGS.toSet().size)
    }

    @Test
    fun `system default hands the platform an empty list`() {
        assertEquals("", AppLanguage.languageTags(SYSTEM_DEFAULT))
        assertEquals("fr", AppLanguage.languageTags("fr"))
        assertEquals("pt-BR", AppLanguage.languageTags("pt-BR"))
        // Nothing the app has no words for is ever asked of the platform.
        assertEquals("", AppLanguage.languageTags("xx"))
    }

    @Test
    fun `what the platform reports is read back as a choice of the list`() {
        assertEquals(SYSTEM_DEFAULT, AppLanguage.choiceFor(null))
        assertEquals(SYSTEM_DEFAULT, AppLanguage.choiceFor(""))
        assertEquals("fr", AppLanguage.choiceFor("fr"))
        // Android's page offers regions of a language: French (France) is our French.
        assertEquals("fr", AppLanguage.choiceFor("fr-FR"))
        assertEquals("pt-BR", AppLanguage.choiceFor("pt-BR"))
        assertEquals("pt", AppLanguage.choiceFor("pt-PT"))
        assertEquals("zh-TW", AppLanguage.choiceFor("zh-Hant-TW"))
        assertEquals("zh-CN", AppLanguage.choiceFor("zh-Hans-CN"))
        assertEquals("sr-Latn", AppLanguage.choiceFor("sr-Latn-RS"))
        assertEquals("sr", AppLanguage.choiceFor("sr-Cyrl-RS"))
        // Java writes Indonesian and Hebrew with their new codes, the folders with the old ones.
        assertEquals("in", AppLanguage.choiceFor("id-ID"))
        assertEquals("iw", AppLanguage.choiceFor("he"))
        assertEquals("nb-NO", AppLanguage.choiceFor("nb-NO"))
        assertEquals(SYSTEM_DEFAULT, AppLanguage.choiceFor("xx"))
    }

    @Test
    fun `the row is the first of the group of the content language`() {
        val screen = File(sources, "ui/screens/settings/LibrarySettings.kt").readText()
        val group = screen.substringAfter("R.string.grp_localization").substringBefore("SettingsClickToReveal")
        val app = group.indexOf("AppLanguageFrag()")
        val content = group.indexOf("LocalizationFrag()")
        assertTrue("the row is not in the group", app >= 0 && content >= 0)
        assertTrue("the row is not the first", app < content)
        // One card for the three rows.
        assertEquals(1, Regex("ElevatedCard").findAll(group).count())
    }

    @Test
    fun `the search of settings finds it by language`() {
        val index = File(sources, "ui/screens/settings/SettingsIndex.kt").readText()
        val entry = index.lines().single { "R.string.app_language" in it }
        assertTrue(entry, "Tour.ROUTE_LIBRARY" in entry && "R.string.search_words_language" in entry)
        assertTrue(index.indexOf("R.string.app_language") < index.indexOf("R.string.content_language"))
    }

    @Test
    fun `a change of content language or country loads Home and the suggestions again`() {
        for (file in listOf("viewmodels/HomeViewModel.kt", "viewmodels/OnlineSearchSuggestionViewModel.kt")) {
            val text = File(sources, file).readText()
            assertTrue(file, "ContentLanguageKey" in text && "ContentCountryKey" in text)
        }
    }
}
