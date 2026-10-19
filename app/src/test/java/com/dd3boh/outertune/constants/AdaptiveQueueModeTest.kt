/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep radios up to date is a switch from 0.12, and the three values stored before it stay as they
 * are: Never is off, and both of the others are on, for radios and mixes only.
 */
class AdaptiveQueueModeTest {

    @Test
    fun `never is off, and the two values that were on are both on`() {
        assertFalse(AdaptiveQueueMode.OFF.isOn)
        assertTrue(AdaptiveQueueMode.AUTOPLAY_ONLY.isOn)
        assertTrue("a listener who chose Any queue keeps it on, for radios", AdaptiveQueueMode.ALWAYS.isOn)
    }
}
