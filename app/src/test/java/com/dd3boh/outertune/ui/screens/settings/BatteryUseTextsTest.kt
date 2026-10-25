/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words of Battery use wait for his yes before they go to translators, so they are held
 * (translatable="false") with their French beside them, and they are short: a line or two under
 * each choice, two or three short sentences behind the (i).
 */
class BatteryUseTextsTest {

    private fun lines(folder: String) = File("src/main/res/$folder/strings-ot.xml").readLines()

    private val english = lines("values")
    private val french = lines("values-fr")

    private fun text(lines: List<String>, name: String): String? =
        lines.firstOrNull { "name=\"$name\"" in it }?.let { Regex(""">(.*)</string>""").find(it)?.groupValues?.get(1) }

    private val names = english.mapNotNull { Regex("""name="(battery_use\w*|battery_saver_\w+|battery_follow_phone\w*)"""").find(it)?.groupValues?.get(1) }

    private fun sentences(text: String) = Regex("""[.!?](?=\s|$)""").findAll(text).count()

    @Test
    fun `every text of Battery use is held from translators and has its French`() {
        assertEquals(names.toString(), 15, names.size)
        for (name in names) {
            val line = english.single { "name=\"$name\"" in it }
            assertTrue("$name is open to translators before his yes", "translatable=\"false\"" in line)
            assertTrue("$name has no French", text(french, name) != null)
        }
    }

    @Test
    fun `no long dash, at most two sentences under a choice and three behind the (i)`() {
        for (name in names) for (lines in listOf(english, french)) {
            assertFalse("$name has a long dash", '—' in text(lines, name)!!)
        }
        for (mode in listOf("full", "balanced", "saver")) for (lines in listOf(english, french)) {
            val line = text(lines, "battery_use_${mode}_description")!!
            assertTrue("the line under $mode is more than two sentences: $line", sentences(line) in 1..2)
        }
        for (lines in listOf(english, french)) {
            val explain = text(lines, "battery_use_explain")!!
            assertTrue("the (i) is more than three sentences: $explain", sentences(explain) in 2..3)
        }
    }

    @Test
    fun `the lines under Balanced and Saver name what they do`() {
        val balanced = text(english, "battery_use_balanced_description")!!
        assertTrue(balanced, "two hours" in balanced && "bars" in balanced)
        val saver = text(english, "battery_use_saver_description")!!
        for (what in listOf("Sound effects off", "black", "frames a second", "Low quality on mobile data", "Wi-Fi")) assertTrue("$what: $saver", what in saver)
    }

    @Test
    fun `the background's note names the background drawn, in both languages`() {
        assertTrue("%1\$s" in text(english, "battery_saver_background_note")!!)
        assertTrue("%1\$s" in text(french, "battery_saver_background_note")!!)
    }
}
