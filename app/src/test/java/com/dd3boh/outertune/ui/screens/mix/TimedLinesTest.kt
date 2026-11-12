/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A change timed in seconds (no steady beat in both songs) has no bar line to cut on and no beat
 * for the basses to swap on: the Simple sheet's Cut and Bass swap lines and the Cut preset's line
 * said "on a bar line", "on one beat" and "on the bar" there all the same.
 */
class TimedLinesTest {

    @Test
    fun `a change timed in seconds gets the lines without bars or beats`() {
        assertEquals(R.string.mix_simple_cut_line_timed, TimedLines.cut(timed = true))
        assertEquals(R.string.mix_simple_cut_line, TimedLines.cut(timed = false))
        assertEquals(R.string.mix_simple_bass_swap_line_timed, TimedLines.bassSwap(timed = true))
        assertEquals(R.string.mix_simple_bass_swap_line, TimedLines.bassSwap(timed = false))
        assertEquals(R.string.mix_line_cut_timed, TimedLines.cutPreset(timed = true))
        assertEquals(R.string.mix_line_cut, TimedLines.cutPreset(timed = false))
    }

    @Test
    fun `the timed lines name no bar or beat, in English and in French`() {
        val res = File("src/main/res")
        val words = mapOf(
            "values/strings-mix.xml" to Regex("""\b(bar|bars|beat|beats)\b""", RegexOption.IGNORE_CASE),
            "values-fr/strings-mix.xml" to Regex("""\b(mesure|mesures|temps)\b""", RegexOption.IGNORE_CASE),
        )
        for ((path, barred) in words) {
            val file = File(res, path).readText()
            for (name in listOf("mix_simple_cut_line_timed", "mix_simple_bass_swap_line_timed", "mix_line_cut_timed")) {
                val text = Regex("""<string name="$name">(.*?)</string>""").find(file)?.groupValues?.get(1)
                assertFalse("$path has no $name", text == null)
                assertFalse("$name in $path: $text", barred.containsMatchIn(text!!))
            }
        }
    }

    @Test
    fun `the sheet and the preset line choose them by the change's bars`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixSimpleSheet.kt").readText()
        assertFalse("R.string.mix_simple_cut_line)" in sheet)
        assertFalse("R.string.mix_simple_bass_swap_line)" in sheet)
        assertTrue("TimedLines.cut(timed)" in sheet && "TimedLines.bassSwap(timed)" in sheet)
        assertTrue("val timed = spec.bars == 0" in sheet)
        val words = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/EditorWords.kt").readText()
        assertTrue("Preset.CUT -> res.getString(TimedLines.cutPreset(s.bars == 0))" in words)
    }
}
