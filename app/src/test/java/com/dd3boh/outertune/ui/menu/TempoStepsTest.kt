/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His ask of 6 Nov 2026: "change a song's bpm by little bits so the song becomes slightly faster or
 * slower". Tempo and Pitch moved 5% at a time; near the song's own speed it now moves 1%.
 */
class TempoStepsTest {

    @Test
    fun `1% steps from 0_90 to 1_10, 5% beyond, from 0_25 to 2`() {
        assertEquals(0.25f, TEMPO_STEPS.first())
        assertEquals(2f, TEMPO_STEPS.last())
        assertEquals(TEMPO_STEPS.sorted(), TEMPO_STEPS)
        assertEquals(TEMPO_STEPS.distinct(), TEMPO_STEPS)
        assertEquals(1.01f, stepValue(TEMPO_STEPS, 1f, up = true))
        assertEquals(0.99f, stepValue(TEMPO_STEPS, 1f, up = false))
        assertEquals(1.15f, stepValue(TEMPO_STEPS, 1.1f, up = true))
        assertEquals(0.85f, stepValue(TEMPO_STEPS, 0.9f, up = false))
        assertTrue(1f in TEMPO_STEPS)
    }

    @Test
    fun `the dialog steps through them`() {
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt").readText()
        assertTrue("values = TEMPO_STEPS," in menu)
    }
}
