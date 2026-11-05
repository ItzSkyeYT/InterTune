/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The row "Open links in InterTune" (OpenLinks) in Settings and in the search of Settings: where
 * it sits, that it is found by what people type, that its title carries the name of the build it
 * is in (InterTune, InterTune Preview, InterTune Debug) in the row and in the results alike, and
 * its words in English and in French. SettingsIndexTest holds the row and its entry to each other.
 */
class OpenLinksSettingsTest {

    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun strings(folder: String): Map<String, String> =
        File("src/main/res/$folder").listFiles()!!.filter { it.name.startsWith("strings") }.flatMap { file ->
            Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }.toList()
        }.toMap()

    private val english = strings("values")
    private val french = strings("values-fr")

    private fun text(language: Map<String, String>): (Int) -> String = { id ->
        val name = nameOf[id] ?: error("no string with id $id")
        language[name] ?: english[name] ?: error("no text for $name")
    }

    private fun titles(index: SettingsSearchIndex, query: String) = index.search(query).map { it.title }

    private val entry get() = SETTINGS_INDEX.single { it.title == R.string.open_links_title }

    @Test
    fun `its entry sits on Library and content, right after Links you share, with its own line`() {
        val at = SETTINGS_INDEX.indexOf(entry)
        assertEquals(SETTINGS_INDEX.indexOfFirst { it.title == R.string.share_link_kind_title } + 1, at)
        assertEquals(Tour.ROUTE_LIBRARY, entry.route)
        assertEquals(R.string.open_links_description, entry.description)
        assertTrue("on in every build: nothing holds it back", entry.there)
    }

    @Test
    fun `search finds it by its name, its kind and what people call it`() {
        val index = SettingsSearchIndex.of(text(english), engine = true)
        for (query in listOf("open links", "links", "link", "default app", "open by default", "supported links", "youtube music", "preview", "intertune")) {
            assertTrue(query, index.search(query).any { it.entry == entry })
        }
        assertEquals("Open links in InterTune", index.search("open links").first().title)
        assertEquals("Library and content", index.search("open links").first().place.substringBefore(" ›"))
    }

    @Test
    fun `its title names the build it is in, in the results as on the row`() {
        for (name in listOf("InterTune", "InterTune Preview", "InterTune Debug")) {
            val index = SettingsSearchIndex.of(text(english), engine = true, appName = name)
            assertEquals("Open links in $name", titles(index, "open links").first())
        }
        val french = SettingsSearchIndex.of(text(french), engine = true, appName = "InterTune Preview")
        assertEquals("Ouvrir les liens dans InterTune Preview", titles(french, "ouvrir les liens").first())
        for (query in listOf("liens", "liens pris en charge", "application par défaut", "youtube music")) {
            assertTrue(query, french.search(query).any { it.entry == entry })
        }
    }

    @Test
    fun `no title of the index is shown with its placeholder`() {
        val placeholder = Regex("""%(\d+\$)?[sdf]""")
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            val index = SettingsSearchIndex.of(text(texts), engine = true, appName = "InterTune Debug")
            // Every entry looked up by its own title: whatever the results then show for it.
            val shown = SETTINGS_INDEX.filter { it.there }.flatMap { entry ->
                index.search(text(texts)(entry.title).replace(APP_NAME_IN_TITLE, "InterTune Debug")).map { it.title }
            }.toSet()
            assertTrue("$language: ${shown.filter { placeholder.containsMatchIn(it) }}", shown.none { placeholder.containsMatchIn(it) })
            assertTrue("$language: the name is put in", "Open links in InterTune Debug" in shown || "Ouvrir les liens dans InterTune Debug" in shown)
        }
        // Only a title written for it carries the name: the placeholder is the one the row fills in.
        val named = SETTINGS_INDEX.filter { APP_NAME_IN_TITLE in english.getValue(nameOf.getValue(it.title)) }.map { nameOf.getValue(it.title) }
        assertEquals(listOf("open_links_title"), named)
    }

    @Test
    fun `its words are written in English and in French, short and plain`() {
        val all = listOf("open_links_title", "open_links_description", "open_links_android_off", "open_links_android_settings", "search_words_open_links")
        for (name in all) {
            assertTrue("$name in English", name in english)
            assertTrue("$name in French", name in french)
            assertFalse("$name is the same in French as in English", french.getValue(name) == english.getValue(name))
        }
        // The button into Android's own page, and the line that says Android's switch is off.
        assertEquals("Android's link settings", english.getValue("open_links_android_settings"))
        assertEquals("R\u00e9glages des liens d'Android", french.getValue("open_links_android_settings"))
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            assertFalse("$language: no figure or name to fill in", '%' in texts.getValue("open_links_android_off"))
            assertTrue("$language: one short sentence", texts.getValue("open_links_android_off").split(". ").size <= 2)
        }
        // The title is filled in with the build's name, in both languages.
        assertTrue(english.getValue("open_links_title").contains("%1\$s"))
        assertTrue(french.getValue("open_links_title").contains("%1\$s"))
        assertEquals("Open links in %1\$s", english.getValue("open_links_title"))
        for ((language, texts) in listOf("English" to english, "French" to french)) {
            val description = texts.getValue("open_links_description")
            // A line under a row is searched as written (SettingsIndexTest), so it holds no figure or name to fill in.
            assertFalse("$language: $description", '%' in description)
            assertTrue("$language: two or three short sentences at most", description.split(". ").size <= 3)
            // No em dash, in any of them.
            for (name in all) {
                assertFalse("$language $name", '\u2014' in texts.getValue(name) || "--" in texts.getValue(name))
            }
        }
        // What the line promises is what the filters carry: YouTube Music and the share page's host.
        assertTrue(english.getValue("open_links_description").let { "YouTube Music" in it && "intertune.app" in it })
        assertTrue(french.getValue("open_links_description").let { "YouTube Music" in it && "intertune.app" in it })
    }

    @Test
    fun `the row is drawn with the build's own name and the line under it`() {
        val row = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/InterfaceFrag.kt").readText()
            .substringAfter("fun ColumnScope.OpenLinksFrag()").substringBefore("\n}\n")
        assertTrue(row, "stringResource(R.string.open_links_title, stringResource(R.string.app_name))" in row)
        assertTrue(row, "R.string.open_links_description" in row)
        assertTrue(row, "settingRow(R.string.open_links_title)" in row)
        assertTrue(row, "OpenLinks.isOn(context)" in row && "OpenLinks.set(context, it)" in row)
        // Android's own switch: read when the screen is drawn and when it comes back to the front, shown as the row's state, the row out of reach while it is off.
        assertTrue(row, "OpenLinks.androidAllows(context)" in row && "Lifecycle.Event.ON_RESUME" in row)
        assertTrue(row, "isEnabled = androidAllows" in row && "checked = OpenLinks.opens(on, androidAllows)" in row)
        assertTrue(row, "if (androidAllows) R.string.open_links_description else R.string.open_links_android_off" in row)
        // The way into Android's page, in a button of its own under the row: a screen reader reads the row and the button apart.
        assertTrue(row, "TextButton(" in row && "onClick = { OpenLinks.openAndroidSettings(context) }" in row)
        assertTrue(row, "stringResource(R.string.open_links_android_settings)" in row)
        // The screen of results is given the same name, or its titles would read differently from the rows.
        val search = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/SettingsSearch.kt").readText()
        assertTrue(search, "appName = resources.getString(R.string.app_name)" in search)
    }

    @Test
    fun `each build names itself, so the title reads InterTune, InterTune Preview or InterTune Debug`() {
        fun name(folder: String) = Regex("""<string name="app_name">([^<]*)</string>""").find(File("src/$folder/res/values/app_name.xml").readText())!!.groupValues[1]
        assertEquals("InterTune", name("main"))
        assertEquals("InterTune Preview", name("preview"))
        assertEquals("InterTune Debug", name("debug"))
    }
}
