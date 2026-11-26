/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Recap goes to everyone in 0.13, and its texts to translators before the release build (26 Nov 2026): strings are
 * built into the app, so only what is translated before then is in 0.13. Held back still: its own names, what reads the
 * same in every language, and the thank-you page, written by hand in every language in strings-recap-thanks.xml (were Weblate to write them
 * into strings-ot.xml as well, each language would define them twice and the app would not build).
 */
class RecapTextsForTranslatorsTest {
    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()
    private val held = setOf(
        "recap_thanks_title", "recap_thanks_words", "recap_thanks_signature", "recap_name", "recap_short", "recap_flag",
        // The same in every language: a site, a bare per cent mark, the Auto chip (the engine's name).
        "recap_card_site", "recap_from_percent_mark", "recap_auto_chip",
    )

    private fun recapEntries(text: String) =
        Regex("""<(string|plurals) name="(recap_[^"]+)"([^>]*)>""").findAll(text).associate { it.groupValues[2] to it.groupValues[3] }

    @Test
    fun `every Recap text goes to translators but its names, a bare format and the thank-you page`() {
        val entries = recapEntries(english)
        assertTrue(entries.size > 150)
        val stillHeld = entries.filterValues { "translatable=\"false\"" in it }.keys
        assertEquals(held, stillHeld)
    }

    @Test
    fun `each one has its French, so translators have two versions to read`() {
        val fr = recapEntries(french).keys
        val missing = recapEntries(english).keys.filter { it !in held && it !in fr }
        assertTrue("no French for $missing", missing.isEmpty())
    }

    /**
     * Polish, Russian, Czech or Ukrainian say 2, 5 and 21 of something with different words, which only a <plurals>
     * gives them (26 Nov 2026). So no Recap text puts a number before what it counts in a plain <string>.
     */
    @Test
    fun `no Recap text puts a number before what it counts in a plain string`() {
        val counting = Regex("""<string name="(recap_[^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(english)
            .filter { "translatable=\"false\"" !in it.groupValues[2] && COUNTED.containsMatchIn(it.groupValues[3]) }
            .map { it.groupValues[1] }.toList()
        assertTrue("a count in a plain string: $counting", counting.isEmpty())
    }

    @Test
    fun `each count made a plural has its forms in English and French, and keeps every placeholder`() {
        for ((name, places) in MADE_PLURALS) {
            val (all, count) = places
            val en = forms(english, name)
            val fr = forms(french, name)
            assertEquals("$name in English", setOf("one", "other"), en.keys)
            assertEquals("$name in French, as its other plurals have it", setOf("one", "many", "other"), fr.keys)
            assertEquals("$name, English other", all, placeholders(en.getValue("other")))
            assertEquals("$name, English one", all, placeholders(en.getValue("one")))
            assertTrue("$name: the count is one of its placeholders", count == null || count in all)
            for ((quantity, text) in fr) assertEquals("$name, French $quantity", all, placeholders(text))
            assertFalse("$name is still a plain string", "<string name=\"$name\"" in english || "<string name=\"$name\"" in french)
        }
    }

    /**
     * Translators write their "one" from the English "one", and in Russian, Ukrainian or Croatian that form is also 21,
     * 31 and 61 (in Filipino 2, 3 and 5 too): an English "one" that says "once" or "a day" in words becomes "once" for
     * 21 there (26 Nov 2026). So every English "one" keeps each number its "other" has. The words drawn beside a big
     * number of their own ("minute", "days in a row") have none in either form, and need none.
     */
    @Test
    fun `every English one form keeps its number, since in other languages it is also 21`() {
        val dropped = Regex("""<plurals name="(recap_[^"]+)"[^>]*>""").findAll(english).map { it.groupValues[1] }
            .filter { name -> forms(english, name).let { placeholders(it.getValue("other")) - placeholders(it.getValue("one")) }.isNotEmpty() }
            .toList()
        assertTrue("an English one form without its number: $dropped", dropped.isEmpty())
    }

    /** Where the number a plural is about is not its first, the code gives that one as the count. */
    @Test
    fun `a plural follows the number it is about`() {
        val code = File("src/main/java/com/dd3boh/outertune/ui/screens/recap")
        val slides = File(code, "RecapSlides.kt").readText()
        assertTrue("R.plurals.recap_line_month, line.of, w.number(line.plays), w.number(line.of)" in slides)
        assertTrue("R.plurals.recap_in_a_row_on else R.plurals.recap_times_on, it.times, w.day(it.day), w.number(it.times)" in slides)
        assertTrue("R.plurals.recap_hour_line, w.quantity(peakMinutes)," in slides)
        assertTrue("w.number(peakMinutes) to true" in slides)
        val buttons = File(code, "RecapPlaylistButtons.kt").readText()
        assertTrue("R.plurals.recap_playlist_copied_some, step.of, w.number(step.added), w.number(step.of)" in buttons)
    }

    private fun forms(text: String, name: String): Map<String, String> {
        val body = Regex("""<plurals name="$name"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?: return emptyMap()
        return Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String) = Regex("""%(\d)\$[sd]""").findAll(text).map { it.groupValues[1].toInt() }.toSet()

    private companion object {
        /** A number, then what it counts: "%1$s nights", "%2$s plays", "%1$s different days", "%1$s of those minutes". */
        val COUNTED = Regex(
            """%\d\$[sd](?: and a half| of (?:those|its|them))?(?: different| new)? """ +
                """(?:nights?|plays?|days?|times?|minutes?|hours?|songs?|skips?|notes?|artists?|friends?|rooms?|characters?)\b"""
        )

        /**
         * The texts made plurals on 26 Nov 2026: every placeholder each keeps, and the one it counts (null where the
         * count stands on its own beside the words, drawn big).
         */
        val MADE_PLURALS: Map<String, Pair<Set<Int>, Int?>> = mapOf(
            "recap_hour_line" to (setOf(1, 2, 3, 4) to 3),
            "recap_late_nights" to (setOf(1, 2) to 1),
            "recap_minutes_days_half" to (setOf(1) to 1),
            "recap_minutes_top" to (setOf(1, 2) to 1),
            "recap_line_month" to (setOf(1, 2, 3) to 2),
            "recap_line_days" to (setOf(1) to 1),
            "recap_line_minutes" to (setOf(1) to 1),
            "recap_line_found_day" to (setOf(1, 2) to 1),
            "recap_line_in_a_row" to (setOf(1, 2) to 1),
            "recap_line_best_day" to (setOf(1, 2) to 1),
            "recap_in_a_row_on" to (setOf(1, 2) to 2),
            "recap_times_on" to (setOf(1, 2) to 2),
            "recap_playlist_copied_some" to (setOf(1, 2) to 2),
            "recap_auto_plays_since" to (setOf(1) to null),
            "recap_card_minutes" to (emptySet<Int>() to null),
            "recap_card_days_in_a_row" to (emptySet<Int>() to null),
        )
    }
}
