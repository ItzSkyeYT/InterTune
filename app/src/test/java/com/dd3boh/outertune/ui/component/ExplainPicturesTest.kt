/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picture in the explanation of Fade between tracks shows what the setting does, and not a crossfade. */
class ExplainPicturesTest {

    @Test
    fun `it starts on the song that is ending and ends on the next`() {
        assertEquals(1f to 0f, fadeLevels(0f))
        assertEquals(0f to 1f, fadeLevels(1f))
    }

    @Test
    fun `the two songs never sound at once`() {
        for (i in 0..1000) {
            val (ending, starting) = fadeLevels(i / 1000f)
            assertTrue("both sound at ${i / 1000f}", minOf(ending, starting) == 0f)
        }
    }

    @Test
    fun `there is a moment of nothing between them`() {
        assertEquals(0f to 0f, fadeLevels(0.5f))
    }

    @Test
    fun `the first only falls and the next only rises`() {
        var before = fadeLevels(0f)
        for (i in 1..1000) {
            val now = fadeLevels(i / 1000f)
            assertTrue(now.first <= before.first && now.second >= before.second)
            assertTrue(now.first in 0f..1f && now.second in 0f..1f)
            before = now
        }
    }

    @Test
    fun `held still, it shows the first song part of the way down`() {
        val (ending, starting) = fadeLevels(STILL_PHASE)
        assertTrue(ending > 0f && ending < 1f)
        assertEquals(0f, starting, 0f)
    }
}
