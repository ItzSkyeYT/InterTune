/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test
    fun `a value inside the slider's own range is left alone`() {
        assertEquals(30f, coerceSleepTimerMinutes(30f))
        assertEquals(1f, coerceSleepTimerMinutes(1f))
        assertEquals(120f, coerceSleepTimerMinutes(120f))
    }

    @Test
    fun `0, typed on the plain numeric keypad, is clamped up to the minimum`() {
        assertEquals(1f, coerceSleepTimerMinutes(0f))
    }

    @Test
    fun `a pasted negative number does not collide with the pause-at-end-of-song sentinel`() {
        assertEquals(1f, coerceSleepTimerMinutes(-1f))
        assertEquals(1f, coerceSleepTimerMinutes(-50f))
    }

    @Test
    fun `a manually typed value past the slider's own 2 hour range is kept, not clamped down to it`() {
        // The manual field exists to go past the slider's range (c0055dd0e); only an extreme
        // value should ever be clamped, see below.
        assertEquals(240f, coerceSleepTimerMinutes(240f))
        assertEquals(999f, coerceSleepTimerMinutes(999f))
    }

    @Test
    fun `an extreme value is still clamped, generously, to protect the Int math it feeds`() {
        assertEquals(1440f, coerceSleepTimerMinutes(999_999f))
    }

    @Test
    fun `NaN and Infinity, reachable only from a hardware keyboard, do not survive to crash roundToInt`() {
        assertEquals(1f, coerceSleepTimerMinutes(Float.NaN))
        assertEquals(1f, coerceSleepTimerMinutes(Float.POSITIVE_INFINITY))
        assertEquals(1f, coerceSleepTimerMinutes(Float.NEGATIVE_INFINITY))
    }
}
