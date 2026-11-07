/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His rule of 2 Nov 2026 on the Mix editor ("WHY in GODS GREEN EARTH does A MIXER NEED COMMENTS???"),
 * held on 6 Nov when the app still had them: labels and numbers only, no sentence saying what the
 * picture already shows. And a dot's bar of choices closes when it is tapped again or anything else is.
 */
class MixNoCommentsTest {
    private val dir = File("src/main/java/com/dd3boh/outertune/ui/screens/mix")
    private fun source(name: String) = File(dir, name).readText()

    private fun value(file: String, name: String): String {
        val text = File(file).readText()
        return Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(text)?.groupValues?.get(1) ?: error("no $name in $file")
    }

    @Test
    fun `no sentence under the presets, under the options, or as a hint in the sheets`() {
        assertFalse("words.presetLine(" in source("MixEditorScreen.kt"))
        val simple = source("MixSimpleSheet.kt")
        assertFalse("words.presetLine(" in simple)
        assertFalse("o.line?.let" in simple)
        assertFalse("mix_simple_line_custom" in simple)
        val sheets = source("MixSheets.kt")
        assertFalse("R.string.mix_moments_note" in sheets)
        assertFalse("R.string.mix_edit_length_note" in sheets)
    }

    @Test
    fun `moments and beat matching are labels, in English and in French`() {
        val en = "src/main/res/values/strings-mix.xml"
        val fr = "src/main/res/values-fr/strings-mix.xml"
        assertEquals("%1\$s in", value(en, "mix_edit_m_in"))
        assertEquals("%1\$s out · %2\$s", value(en, "mix_edit_m_out"))
        assertEquals("Same tempo", value(en, "mix_edit_bm_same"))
        assertEquals("Auto · %1\$s", value(en, "mix_simple_auto_picked"))
        assertEquals("%1\$s : sortie · %2\$s", value(fr, "mix_edit_m_out"))
        // Short enough to be a label: no full stop, and no line longer than a few words.
        for (name in listOf("mix_edit_m_in", "mix_edit_m_out", "mix_edit_m_muted", "mix_edit_m_waits", "mix_edit_m_curve", "mix_edit_bm_slower",
            "mix_edit_bm_off", "mix_edit_bm_refused", "mix_edit_bm_no_beat_a", "mix_edit_bm_no_bars", "mix_edit_two_basslines")) {
            for (file in listOf(en, fr)) {
                val v = value(file, name)
                assertFalse("$name in $file ends a sentence: $v", v.trimEnd().endsWith("."))
                assertTrue("$name in $file is a sentence: $v", v.split(' ').size <= 6)
            }
        }
    }

    @Test
    fun `a dot's bar of choices closes on a second tap and on any other tap of the canvas`() {
        val view = source("MixCanvasView.kt")
        assertTrue("state.selection = if (state.selection == tappedOn) null else tappedOn" in view)
        assertTrue("if (tappedOn == null && hit !is CanvasLayout.Hit.Point) state.selection = null" in view)
    }

    /**
     * His order of 7 Nov 2026: "NUKE the moments thing. DELETE IT". No list of the change's moments,
     * and no line narrating it, playing or stopped, on either level: a refusal or the loading stage only.
     */
    @Test
    fun `no moments sheet and no line narrating the change`() {
        val dir = File("src/main/java/com/dd3boh/outertune/ui/screens/mix")
        for (name in listOf("MixSheets.kt", "MixEditorScreen.kt", "MixSimpleSheet.kt")) {
            assertFalse(name, "MomentsSheet" in File(dir, name).readText())
        }
        val screen = File(dir, "MixEditorScreen.kt").readText()
        val now = screen.substringAfter("private fun NowLine(").substringBefore("\n}\n")
        val simple = File(dir, "MixSimpleSheet.kt").readText()
        val where = simple.substringAfter("private fun WhereLine(").substringBefore("\n}\n")
        for ((name, body) in listOf("NowLine" to now, "WhereLine" to where)) {
            assertFalse(name, "nowPlaying(" in body || "nowStopped(" in body || "mix_simple_starts" in body || "clickable(" in body)
        }
    }
}
