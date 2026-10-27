/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.settings.SettingsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stops "Show me" on the welcome back page leads through, held to the screens they point at,
 * and their words.
 *
 * A stop whose mark went, or that was given the wrong screen, is never seen to be wrong on a
 * phone: the tour passes over it without a word, or the page says there is nothing to point at
 * and the card is never ticked. So the screens are read here, the way SettingsCloserLookTest
 * reads them for the closer look, and through SettingsSource, which follows each screen of
 * settings into what it draws for the search.
 */
class WelcomeBackStopsTest {

    private val main = File("src/main/java/com/dd3boh/outertune")
    private val settingsDir = File(main, "ui/screens/settings")
    private val sources = main.walkTopDown().filter { it.extension == "kt" && "ui/screens/walkthrough" !in it.invariantSeparatorsPath }.toList()

    /** The constants of [Tour] by their value: the stops hold the value, the screens write the name. */
    private val targetNames: Map<String, String> = Regex("""const val (\w+) = "([^"]+)"""")
        .findAll(File(main, "ui/screens/walkthrough/Tour.kt").readText())
        .associate { it.groupValues[2] to it.groupValues[1] }

    /** The name a string is written under, since a test has no resources to read its text from. */
    private fun stringName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    private val stops = NEW_THINGS.flatMap { it.stops }
    private val onTheList = stops.filter { it.route == Tour.ROUTE_SETTINGS }
    private val inAScreen = stops.filter { it.route != null && it.route != Tour.ROUTE_SETTINGS }

    /** A tourTarget of a target: its file, where it is in the text, and its line counted from 1. */
    private class Mark(val file: File, val at: Int, val line: Int)

    /** The text with its comments blanked out, so that a sentence naming a mark is not taken for one. */
    private fun code(file: File) = SettingsSource.blank(file.readText())

    private fun lineAt(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    private fun marks(target: String): List<Mark> {
        val name = targetNames[target] ?: return emptyList()
        val mark = Regex("""tourTarget\(Tour\.$name\)""")
        return sources.flatMap { file ->
            val text = code(file)
            mark.findAll(text).map { Mark(file, it.range.first, lineAt(text, it.range.first)) }.toList()
        }
    }

    /** A call: what is called, its arguments, and the line it is on. */
    private class Call(val name: String, val args: String, val line: Int)

    /** The call [mark] is written in: the row it is the modifier of, or what it wraps a block in. */
    private fun callAround(mark: Mark): Call? {
        val text = code(mark.file)
        val call = Regex("""\b([A-Z]\w*)\(""").findAll(text.substring(0, mark.at)).toList()
            .lastOrNull { SettingsSource.close(text, it.range.last) > mark.at } ?: return null
        val open = call.range.last
        return Call(call.groupValues[1], text.substring(open + 1, SettingsSource.close(text, open)), lineAt(text, call.range.first))
    }

    /** What a function of the app writes inside it, for a field of ours that writes its own title. */
    private fun bodyOf(function: String): String? {
        for (file in sources) {
            val text = code(file)
            val head = Regex("""\bfun (?:[\w.]+\.)?$function\(""").find(text) ?: continue
            val open = text.indexOf('{', SettingsSource.close(text, head.range.last))
            if (open < 0) continue
            return text.substring(open, SettingsSource.close(text, open) + 1)
        }
        return null
    }

    /**
     * The row of a screen of settings that [mark] marks: the row it is the modifier of, or the
     * first row of the block it wraps (two switches are one stop, so that the bubble does not
     * stand over the second).
     */
    private fun rowAt(mark: Mark, rows: List<SettingsSource.Row>): SettingsSource.Row? {
        val path = mark.file.relativeTo(settingsDir).path
        val here = rows.filter { it.file == path }
        val call = callAround(mark) ?: return null
        return if (call.name in SettingsSource.ROW_COMPONENTS) here.firstOrNull { it.line == call.line }
        else here.filter { it.line > mark.line }.minByOrNull { it.line }?.takeIf { it.line <= mark.line + 2 }
    }

    private val settings by lazy { SettingsSource(main) }

    // Where they point

    @Test
    fun `every stop points at a constant of Tour that a screen marks`() {
        for (stop in stops) {
            assertTrue("${stop.id}: ${stop.targetId} is not a constant of Tour", stop.targetId in targetNames)
            assertTrue("${stop.id}: nothing is marked Tour.${targetNames[stop.targetId]}", marks(stop.targetId!!).isNotEmpty())
        }
    }

    @Test
    fun `a stop on the list of settings is marked on it once, upright and in the two panes alike`() {
        // The two panes are the list in a wide window: a mark on the upright list alone would
        // leave the stop with nothing to point at on a tablet or a phone on its side.
        val upright = File(settingsDir, "SettingsScreen.kt")
        val panes = File(settingsDir, "SettingsTwoPane.kt")
        assertTrue(onTheList.isNotEmpty())
        for (stop in onTheList) {
            val files = marks(stop.targetId!!).map { it.file }
            assertEquals("${stop.id}: Tour.${targetNames[stop.targetId]} on the upright list", 1, files.count { it == upright })
            assertEquals("${stop.id}: Tour.${targetNames[stop.targetId]} in the two panes", 1, files.count { it == panes })
        }
    }

    @Test
    fun `a stop in a screen of settings is marked on a row that screen draws, once`() {
        assertTrue(inAScreen.isNotEmpty())
        for (stop in inAScreen) {
            val rows = settings.rows.filter { it.route == stop.route }
            assertTrue("${stop.id}: ${stop.route} opens no screen of settings", rows.isNotEmpty())
            val drawn = marks(stop.targetId!!).filter { rowAt(it, rows) != null }
            assertEquals("${stop.id}: rows on ${stop.route} marked Tour.${targetNames[stop.targetId]}", 1, drawn.size)
        }
    }

    @Test
    fun `a stop is titled with the string its row is titled with`() {
        // On the list, as the upright list writes the row: the two panes name their rows from
        // settingsGroups, which SettingsTwoPaneTest holds to the same names.
        val upright = File(settingsDir, "SettingsScreen.kt")
        for (stop in onTheList) {
            val title = Regex("""R\.string\.${stringName(stop.title)}\b""")
            val call = marks(stop.targetId!!).filter { it.file == upright }.firstNotNullOfOrNull { callAround(it) }
            assertNotNull("${stop.id} is marked in no call on the list", call)
            // A row of the list has its title written in it. A field of our own writes its own
            // inside, as the search over the list does.
            val written = if (call!!.name == "PreferenceEntry") SettingsSource.argument(call.args, "title") else bodyOf(call.name)
            assertTrue("${stop.id} is titled ${stringName(stop.title)}, and the ${call.name} it marks is not", written != null && title.containsMatchIn(written))
        }
        // Two switches are one stop and have no one title: the stop has its card's.
        val titledByTheirCard = setOf("content_filters_setting")
        for (stop in inAScreen.filter { it.id !in titledByTheirCard }) {
            val rows = settings.rows.filter { it.route == stop.route }
            val row = marks(stop.targetId!!).firstNotNullOfOrNull { rowAt(it, rows) }
            assertNotNull(stop.id, row)
            // The title written in the row or, where the row works it out (read once above it and
            // used twice), the one its settingRow mark is made from.
            val titled = row!!.titles.ifEmpty { row.marks }
            assertTrue("${stop.id} is titled ${stringName(stop.title)}, and its row $titled", stringName(stop.title) in titled)
        }
    }

    // What they say

    private val english = File("src/main/res/values/strings-ot.xml").readLines()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readLines()

    private fun line(lines: List<String>, name: String) = lines.singleOrNull { "name=\"$name\"" in it }

    private fun wordsOf(line: String) = line.substringAfter('>').substringBefore("</string>")

    @Test
    fun `every bubble has its French, and is short and plain`() {
        for (name in stops.map { stringName(it.body) }.toSet()) {
            val both = listOfNotNull(line(english, name), line(french, name)).map { wordsOf(it) }
            assertEquals("$name is not in strings-ot.xml in English and in French", 2, both.size)
            for (words in both) {
                assertFalse("$name has an exclamation mark", '!' in words)
                assertFalse("$name has a long dash", 0x2014.toChar() in words)
                assertTrue("$name runs to ${words.length} characters", words.length in 20..240)
            }
        }
    }

    @Test
    fun `0_12_5's new bubbles are one short sentence each, open to translators since his yes of 27 Oct`() {
        // To translators once the words are agreed, as the widget's styles went: this then says they are open.
        val bubbles = NEW_THINGS.filter { it.id in listOf("player_pins", "settings_search", "battery_use") }.flatMap { it.stops }.map { stringName(it.body) }
        assertEquals(listOf("new_player_pins_way", "new_player_pins_here", "new_settings_search_here", "new_battery_use_here"), bubbles)
        for (name in bubbles) {
            assertFalse("$name is held from translators", "translatable=\"false\"" in line(english, name)!!)
            for (words in listOf(line(english, name), line(french, name)).map { wordsOf(it!!) }) {
                assertEquals("$name is one sentence: $words", 1, Regex("""[.?]( |$)""").findAll(words).count())
                assertTrue("$name runs to ${words.length} characters", words.length <= 130)
            }
        }
    }
}
