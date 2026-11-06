/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.res.Resources
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bracket labels of the Songs view on a narrow phone at the largest font size: a label wider
 * than the canvas is shortened to fit inside it. It used to be placed with a range that was empty
 * once the label was wider than the canvas less its margins, which threw while the page was drawn.
 */
class MixCanvasLabelTest {

    private val gridA = SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111)
    private val gridB = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109)
    private val facts = PairFacts(
        a = SongFacts("wake-me-up", "Wake Me Up", 308_000.0, 236_000.0, 308_000.0, gridA, focusMs = 290_000.0),
        b = SongFacts("after-hours", "After Hours", 361_000.0, 0.0, 60_000.0, gridB, firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    /** Every word as long as the longest French bracket label. */
    @Suppress("DEPRECATION")
    private class LongWords : Resources(null, null, null) {
        override fun getString(id: Int): String = LONG
        override fun getString(id: Int, vararg formatArgs: Any?): String = LONG
        override fun getBoolean(id: Int): Boolean = false
    }

    @Test
    fun `a bracket label wider than the canvas is shortened to fit inside it`() {
        val editor = MixEditor(facts, AutoSpec.fallback(facts), null)
        val density = Density(2f)
        val dp = density.density
        // A 360 dp phone; 11 sp at twice the size is about 10.5 dp a letter.
        val width = 360 * dp
        val measure = { text: String -> text.length * 10.5f * dp }
        val layout = CanvasLayout(editor, editor.spec, width, density, view = 0f, zoom = 1f, startWanted = Double.NaN, adjust = null, small = false)
        layout.placeTargets(EditorWords(LongWords(), "Wake Me Up", "After Hours"), measure)
        assertTrue(layout.brackets.isNotEmpty())
        for (br in layout.brackets) {
            val tw = measure(br.label) + 14 * dp
            assertTrue("${br.label} is wider than the canvas", tw <= width - 2 * layout.pad + 0.01f)
            assertTrue("${br.label} leaves the canvas", br.labelX - tw / 2 >= layout.pad - 0.01f && br.labelX + tw / 2 <= width - layout.pad + 0.01f)
            assertTrue(br.label, br.label.length >= 10)
        }
    }

    private companion object {
        const val LONG = "en sourdine dès mesure 12, temps 3"
    }
}
