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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the search of Settings finds for what is typed (Unreleased.SETTINGS_SEARCH): by names,
 * lines and search words, whatever the case and the accents, from part of a word, in English and
 * in French, the words of the app's own string files.
 */
class SettingsSearchTest {

    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    /** A string file's texts by name, as the app shows them. */
    private fun strings(folder: String): Map<String, String> =
        File("src/main/res/$folder").listFiles()!!.filter { it.name.startsWith("strings") }.flatMap { file ->
            Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).map { it.groupValues[1] to shown(it.groupValues[2]) }.toList()
        }.toMap()

    private fun shown(written: String) = written
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        .replace("\\u00A0", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

    private val english = strings("values")
    private val french = strings("values-fr")

    private fun text(language: Map<String, String>): (Int) -> String = { id ->
        val name = nameOf[id] ?: error("no string with id $id")
        language[name] ?: english[name] ?: error("no text for $name")
    }

    private val inEnglish = SettingsSearchIndex.of(text(english), engine = true)
    private val inFrench = SettingsSearchIndex.of(text(french), engine = true)

    private fun titles(index: SettingsSearchIndex, query: String) = index.search(query).map { it.title }

    // The form words are compared in

    @Test
    fun `case, accents and ligatures do not count, and anything between letters is a space`() {
        assertEquals("ecran", searchForm("Écran"))
        assertEquals("theme sombre", searchForm("THÈME SOMBRE"))
        assertEquals("coeur", searchForm("cœur"))
        assertEquals("last fm", searchForm("Last.fm"))
        assertEquals(listOf("l", "egaliseur"), searchWords("l'égaliseur"))
        assertEquals(listOf("l", "egaliseur"), searchWords("l’égaliseur"))
        assertEquals(listOf("wi", "fi"), searchWords("Wi-Fi"))
    }

    @Test
    fun `small words are left out of a query unless they are all there is`() {
        assertEquals(listOf("mode", "synchronisation"), queryWords("Mode de synchronisation"))
        assertEquals(listOf("queue"), queryWords("the queue"))
        assertEquals(listOf("de"), queryWords("de"))
        assertTrue(queryWords("   ").isEmpty())
    }

    // Matching one setting

    private val quality = Searchable(searchWords("Audio quality"), searchWords("bitrate, sound quality, data"))

    @Test
    fun `a word is found from the start of a word, and from three letters on inside one`() {
        assertTrue(searchScore(listOf("qual"), quality)!! > 0)
        assertTrue(searchScore(listOf("ality"), quality)!! > 0)
        assertTrue(searchScore(listOf("au"), quality)!! > 0)
        // Two letters inside a word are too common to mean anything.
        assertNull(searchScore(listOf("ua"), quality))
    }

    @Test
    fun `every word typed has to be there`() {
        assertTrue(searchScore(listOf("audio", "bitrate"), quality)!! > 0)
        assertNull(searchScore(listOf("audio", "lyrics"), quality))
        assertNull(searchScore(emptyList(), quality))
    }

    @Test
    fun `the title counts for more than the rest, and its first word most`() {
        val inTitle = searchScore(listOf("quality"), quality)!!
        val inRest = searchScore(listOf("bitrate"), quality)!!
        val first = searchScore(listOf("audio"), quality)!!
        assertTrue(inTitle > inRest)
        assertTrue(first > inTitle)
    }

    @Test
    fun `a plural finds the word without its s`() {
        val wifi = Searchable(searchWords("Download on Wi-Fi only"), emptyList())
        assertTrue(searchScore(listOf("downloads"), wifi)!! > 0)
        assertNull(searchScore(listOf("downloadz"), wifi))
    }

    // Searching all of it, in the app's own words

    @Test
    fun `nothing is found for nothing typed, or for what no setting says`() {
        assertTrue(inEnglish.search("").isEmpty())
        assertTrue(inEnglish.search("  ").isEmpty())
        assertTrue(inEnglish.search("zqxj").isEmpty())
    }

    @Test
    fun `English, whatever the case, and from part of a word`() {
        assertEquals("Audio quality", titles(inEnglish, "audio qual").first())
        assertTrue("Dark theme" in titles(inEnglish, "DARK"))
        assertTrue("Equalizer" in titles(inEnglish, "equal"))
        assertTrue("Lyrics text position" in titles(inEnglish, "lyr pos"))
    }

    @Test
    fun `a description finds its setting`() {
        // In no title: Pure black's line says what it does on an OLED screen, Skip silence's what it skips.
        assertTrue("Pure black" in titles(inEnglish, "OLED"))
        assertTrue("Skip silence" in titles(inEnglish, "silent gaps"))
    }

    @Test
    fun `search words find what the names do not say`() {
        assertTrue("Fade between tracks" in titles(inEnglish, "crossfade"))
        assertTrue("Dark theme" in titles(inEnglish, "night mode"))
        assertTrue("Keep listening" in titles(inEnglish, "shazam"))
        assertTrue("Equalizer" in titles(inEnglish, "EQ"))
        // A choice's name finds the setting it is a choice of.
        assertTrue("Player background style" in titles(inEnglish, "living blur"))
    }

    @Test
    fun `French, with or without its accents`() {
        assertTrue("Égaliseur" in titles(inFrench, "égaliseur"))
        assertTrue("Égaliseur" in titles(inFrench, "egaliseur"))
        assertTrue("Thème sombre" in titles(inFrench, "theme sombre"))
        assertTrue("Thème sombre" in titles(inFrench, "mode nuit"))
        assertTrue("Fondu entre les titres" in titles(inFrench, "fondu"))
        assertTrue("Télécharger en Wi-Fi uniquement" in titles(inFrench, "telecharg wi-fi"))
        // An elision is two words, and the second is found.
        assertTrue(titles(inFrench, "écoute").any { "écoute" in it.lowercase() })
    }

    @Test
    fun `in French the results are in French`() {
        assertTrue("Thème sombre" in titles(inFrench, "sombre"))
        assertFalse("Dark theme" in titles(inFrench, "sombre"))
    }

    @Test
    fun `a screen named by what is typed comes first, then its settings`() {
        val hits = inEnglish.search("lyrics")
        assertNull(hits.first().entry)
        assertEquals(Tour.ROUTE_LYRICS, hits.first().route)
        assertTrue(hits.drop(1).any { it.entry != null && it.route == Tour.ROUTE_LYRICS })
    }

    @Test
    fun `a result says where it is, with the fold it is in`() {
        val keepAlive = inEnglish.search("prevent system killing").first()
        assertEquals("Prevent system killing app", keepAlive.title)
        assertEquals(listOf(english["player_and_audio"], english["advanced"]).joinToString(" › "), keepAlive.place)
        val forget = inEnglish.search("forget the last session").first()
        assertEquals(listOf(english["recommendations"], english["recommendations_data_title"]).joinToString(" › "), forget.place)
    }

    @Test
    fun `two settings of one name on one screen are told apart by what they hang from`() {
        val fades = inEnglish.search("fade length").filter { it.title == "Fade length" }
        assertEquals(2, fades.size)
        assertEquals(
            setOf("Fade between tracks", "Fade out before stopping").map { listOf(english["player_and_audio"], it).joinToString(" › ") }.toSet(),
            fades.map { it.place }.toSet(),
        )
    }

    @Test
    fun `a setting this build does not have is never found`() {
        val gone = SettingEntry(Tour.ROUTE_PLAYER, R.string.audio_quality, there = false)
        val index = SettingsSearchIndex(listOf(gone), emptyList(), text(english))
        assertTrue(index.search("audio quality").isEmpty())
    }

    @Test
    fun `a result leads to its row, through its fold, and to the row it hangs from when that one is missing`() {
        val proxy = SETTINGS_INDEX.single { it.title == R.string.proxy_type }
        val jump = proxy.jump()
        assertEquals(settingMark(R.string.proxy_type), jump.target)
        assertEquals(Tour.ROUTE_PRIVACY, jump.route)
        assertEquals(R.string.advanced, jump.fold)
        assertEquals(settingMark(R.string.enable_proxy), jump.instead)
    }

    // The words

    private fun line(folder: String, name: String) =
        File("src/main/res/$folder/strings-ot.xml").readLines().firstOrNull { "name=\"$name\"" in it }

    @Test
    fun `every search word string is used, and every one used is in both languages`() {
        val used = SETTINGS_INDEX.flatMap { it.words }.mapNotNull { nameOf[it] }.filter { it.startsWith("search_words_") }.toSet()
        val written = english.keys.filter { it.startsWith("search_words_") }.toSet()
        assertEquals(written.sorted(), used.sorted())
        for (name in used + listOf("search_settings", "search_settings_none", "search_settings_clear")) {
            assertTrue("$name has no French", name in french)
        }
    }

    @Test
    fun `the search's own words are short, plain, and held from translators`() {
        for (name in english.keys.filter { it.startsWith("search_words_") || it.startsWith("search_settings") }) {
            val en = line("values", name)!!
            assertTrue("$name is offered to translators before his yes", "translatable=\"false\"" in en)
            for (text in listOf(english[name]!!, french[name]!!)) {
                assertFalse("$name has a long dash", '—' in text)
                assertTrue("$name runs to ${text.length} characters", text.length <= 80)
            }
        }
    }
}
