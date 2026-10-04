/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.engine.Lean
import com.dd3boh.outertune.engine.LeanChoice
import com.dd3boh.outertune.engine.LeanLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line under Quick picks leans toward: it names the chip that takes over by the name it has on
 * Home, and it is the sentence that is true of the row as things stand.
 */
class RecommendationsSettingsTest {

    private fun sentence(choice: Lean, source: QuickPicksSource = QuickPicksSource.ENGINE, chip: Int = ContextChip.AUTO, newOnly: Boolean = false) =
        leanLineString(LeanChoice.line(choice, source, chip, newOnly, 0.5)!!)

    @Test
    fun `with New songs only on, Never heard says what it keeps and every other choice that it waits`() {
        assertEquals(R.string.quick_picks_lean_new_only_strict, sentence(Lean.NEW, newOnly = true))
        assertEquals(R.string.quick_picks_lean_new_only_strict, sentence(Lean.NEW, chip = ContextChip.DISCOVER, newOnly = true))
        assertEquals(R.string.quick_picks_lean_new_only_strict, sentence(Lean.NEW, QuickPicksSource.COMPARE, newOnly = true))
        for (choice in listOf(Lean.ARTIST, Lean.FORGOTTEN, Lean.SIMILAR)) assertEquals(R.string.quick_picks_lean_set_aside_new_only, sentence(choice, newOnly = true))
    }

    @Test
    fun `each state of the line has a sentence of its own`() {
        assertEquals(R.string.quick_picks_lean_description, sentence(Lean.NEW))
        assertEquals(R.string.quick_picks_lean_description_compare, sentence(Lean.NEW, QuickPicksSource.COMPARE))
        assertEquals(R.string.quick_picks_lean_not_source, sentence(Lean.NEW, QuickPicksSource.YOUTUBE, newOnly = true))
        assertEquals(R.string.quick_picks_lean_set_aside_chip, sentence(Lean.ARTIST, chip = ContextChip.DISCOVER))
        assertEquals(R.string.quick_picks_lean_set_aside_chip, leanLineString(LeanLine.SetAside(newOnly = false, chip = ContextChip.FAVOURITES)))
    }

    @Test
    fun `each chip that can set the choice aside is called what Home calls it`() {
        assertEquals(R.string.chip_discover, contextChipName(ContextChip.DISCOVER))
        assertEquals(R.string.chip_favourites, contextChipName(ContextChip.FAVOURITES))
        assertEquals(R.string.chip_focus, contextChipName(ContextChip.FOCUS))
        assertEquals(R.string.chip_chill, contextChipName(ContextChip.CHILL))
        assertEquals(R.string.chip_party, contextChipName(ContextChip.PARTY))
    }

    @Test
    fun `the first chip, or one this version does not know, reads Auto`() {
        assertEquals(R.string.chip_auto, contextChipName(ContextChip.AUTO))
        assertEquals(R.string.chip_auto, contextChipName(42))
    }
}
