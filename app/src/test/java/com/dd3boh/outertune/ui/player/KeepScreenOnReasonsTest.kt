/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnReasonsTest {

    @Test
    fun `no reasons active means the screen may sleep`() {
        val reasons = KeepScreenOnReasons()
        assertFalse(reasons.set(KeepScreenOnReason.LYRICS, false))
    }

    @Test
    fun `one reason is enough to keep the screen on`() {
        val reasons = KeepScreenOnReasons()
        assertTrue(reasons.set(KeepScreenOnReason.LYRICS, true))
    }

    @Test
    fun `a second reason does not need the first to still be on`() {
        val reasons = KeepScreenOnReasons()
        reasons.set(KeepScreenOnReason.LYRICS, true)
        assertTrue(reasons.set(KeepScreenOnReason.RECOGNITION, true))
    }

    @Test
    fun `clearing one reason does not clobber another that is still active`() {
        // This is the bug: two independent owners of one flag, and whichever disposes last used to
        // win. With a set, releasing lyrics while recognition is still listening must leave the
        // screen on.
        val reasons = KeepScreenOnReasons()
        reasons.set(KeepScreenOnReason.LYRICS, true)
        reasons.set(KeepScreenOnReason.RECOGNITION, true)
        assertTrue(reasons.set(KeepScreenOnReason.LYRICS, false))
    }

    @Test
    fun `the flag only clears once every reason has been released`() {
        val reasons = KeepScreenOnReasons()
        reasons.set(KeepScreenOnReason.LYRICS, true)
        reasons.set(KeepScreenOnReason.RECOGNITION, true)
        reasons.set(KeepScreenOnReason.LYRICS, false)
        assertFalse(reasons.set(KeepScreenOnReason.RECOGNITION, false))
    }

    @Test
    fun `setting the same reason on twice does not need two releases`() {
        val reasons = KeepScreenOnReasons()
        reasons.set(KeepScreenOnReason.IMMERSIVE_LANDSCAPE, true)
        reasons.set(KeepScreenOnReason.IMMERSIVE_LANDSCAPE, true)
        assertFalse(reasons.set(KeepScreenOnReason.IMMERSIVE_LANDSCAPE, false))
    }
}
