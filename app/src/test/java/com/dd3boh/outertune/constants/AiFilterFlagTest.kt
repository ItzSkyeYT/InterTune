/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Everything the AI filter does in a build hangs on one flag, Unreleased.AI_FILTER, read without a
 * second condition: no BuildConfig.PREVIEW_FEATURES beside it, no network, nothing of a debug build. So the
 * flag is the one switch between "debug and preview builds only" and "in a release", and shipping
 * it is what ReleasedFlagsTest says for any flag: set it to true and add its name there. With the
 * flag off a release has nothing of it, whatever a restored backup stored. Read from the source, as
 * ReleasedFlagsTest does, since a flag is a constant of the build.
 */
class AiFilterFlagTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun readers(): Set<String> = sources.walkTopDown()
        .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
        .filter { Regex("""Unreleased\.AI_FILTER\b""").containsMatchIn(code(it.readText())) }
        .map { it.relativeTo(sources).path }
        .toSet()

    private fun assertWritten(path: String, written: String) =
        assertTrue("$path no longer has `$written` as it was", written in code(File(sources, path).readText()).lines().map { it.trim() })

    @Test
    fun `the flag is held back or shipped, nothing in between`() {
        val unreleased = code(File(sources, "constants/Unreleased.kt").readText())
        val value = Regex("""^\s*val AI_FILTER = (.+?)\s*$""", RegexOption.MULTILINE).find(unreleased)?.groupValues?.get(1)
        assertTrue("AI_FILTER is $value", value == "BuildConfig.PREVIEW_FEATURES" || value == "true")
    }

    @Test
    fun `everything the filter does in a build hangs on the flag, unconditioned`() {
        assertEquals(
            setOf(
                // The state is never loaded or set without it, and the stored setting reads as off.
                "App.kt",
                "models/AiFilter.kt",
                // The row in Library and content, and its entry in the search of Settings.
                "ui/screens/settings/LibrarySettings.kt",
                "ui/screens/settings/SettingsIndex.kt",
                // Mark as AI and Not AI in the two artist menus.
                "ui/menu/AiArtistMark.kt",
                // The line over search results.
                "ui/screens/search/OnlineSearchResult.kt",
                // A listed song passed over in the queue.
                "playback/MusicService.kt",
            ),
            readers(),
        )
        assertWritten("App.kt", "if (Unreleased.AI_FILTER) {")
        assertWritten("models/AiFilter.kt", "fun modeOf(preferences: Preferences, enabled: Boolean = Unreleased.AI_FILTER): AiFilterMode =")
        assertWritten("models/AiFilter.kt", "if (!enabled) AiFilterMode.OFF else preferences[AiFilterKey].toEnum(AiFilterMode.OFF)")
        assertWritten("models/AiFilter.kt", "enabled: Boolean = Unreleased.AI_FILTER,")
        assertWritten("ui/screens/settings/LibrarySettings.kt", "if (Unreleased.AI_FILTER) AiFilterFrag()")
        assertWritten("ui/menu/AiArtistMark.kt", "if (!Unreleased.AI_FILTER || filter.isOff || !AiArtistList.isChannelId(artistId)) return")
        assertWritten("ui/screens/search/OnlineSearchResult.kt", "if (Unreleased.AI_FILTER && aiHidden > 0) {")
        assertWritten("playback/MusicService.kt", "if (Unreleased.AI_FILTER && reason == MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {")
    }

    @Test
    fun `nothing of it asks the network or waits for a debug build`() {
        // The list is the bundled asset: fetched from nowhere, in any build.
        val files = listOf("models/AiFilter.kt", "models/AiArtistList.kt", "ui/menu/AiArtistMark.kt",
            "ui/screens/settings/fragments/AiFilterFrag.kt", "ui/screens/search/AiHiddenLine.kt")
        for (path in files) {
            val text = code(File(sources, path).readText())
            for (word in listOf("BuildConfig", "http", "YouTube.", "HttpClient", "URL(")) {
                assertFalse("$path has $word", word in text)
            }
        }
        assertTrue(File("src/main/assets/ai_artists.txt").isFile)
    }

    @Test
    fun `the artist menus show the row through the guarded function only`() {
        for (menu in listOf("ui/menu/YouTubeArtistMenu.kt", "ui/menu/ArtistMenu.kt")) {
            val text = code(File(sources, menu).readText())
            assertTrue("$menu has no AI row", "aiArtistMenuItem(artist.id, aiFilter)" in text)
            assertFalse("$menu names the row's texts itself", "ai_filter_" in text)
        }
    }

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    /** Every text of the filter in [xml], by name: its strings and its plurals' items. */
    private fun texts(xml: String): Map<String, List<String>> {
        val strings = Regex("""<string name="(ai_filter[a-z_]*)"[^>]*>(.*?)</string>""").findAll(xml).associate { it.groupValues[1] to listOf(it.groupValues[2]) }
        val plurals = Regex("""<plurals name="(ai_filter[a-z_]*)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { m ->
            m.groupValues[1] to Regex("""<item quantity="[a-z]+">(.*?)</item>""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        return strings + plurals
    }

    @Test
    fun `its texts have their French, and go to translators all together or not at all`() {
        val en = texts(english)
        assertEquals("the filter's texts could not all be found", 11, en.size)
        assertEquals(en.keys, texts(french).keys)
        // Held until the words have a yes (translatable="false"), then opened in one go.
        val held = en.keys.filter { name -> "translatable=\"false\"" in Regex("""<(string|plurals) name="$name"[^>]*>""").find(english)!!.value }
        assertTrue("held: $held, open: ${en.keys - held.toSet()}", held.isEmpty() || held.size == en.size)
    }

    @Test
    fun `its texts are short and plain, in both languages`() {
        for ((language, xml) in listOf("English" to english, "French" to french)) {
            for ((name, items) in texts(xml)) {
                for (text in items) {
                    assertFalse("$language $name has a long dash", '—' in text || '–' in text)
                    assertFalse("$language $name has an exclamation mark", '!' in text)
                    assertTrue("$language $name runs to more than three sentences", Regex("""[.?](?=\s|$)""").findAll(text).count() <= 3)
                    assertTrue("$language $name runs to ${text.length} characters", text.length <= 330)
                }
            }
        }
        // The note names the list as hand-made and where it comes from.
        val note = texts(english).getValue("ai_filter_note").single()
        assertTrue("hand-made" in note && "CevvalKoala" in note)
    }
}
