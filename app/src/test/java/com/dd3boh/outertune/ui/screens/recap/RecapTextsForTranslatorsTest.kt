/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Recap goes to everyone in 0.13, and its texts to translators before the release build (26 Nov 2026): strings are
 * built into the app, so only what is translated before then is in 0.13. Held back still: its own names, a bare format,
 * and the thank-you page, written by hand in every language in strings-recap-thanks.xml (were Weblate to write them
 * into strings-ot.xml as well, each language would define them twice and the app would not build).
 */
class RecapTextsForTranslatorsTest {
    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()
    private val held = setOf("recap_thanks_title", "recap_thanks_words", "recap_thanks_signature", "recap_name", "recap_short", "recap_flag")

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
}
