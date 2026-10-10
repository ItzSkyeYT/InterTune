/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisionosTestTest {

    @Test
    fun `each choice is read back from the two switches it stores`() {
        for (choice in VisionosTest.entries) {
            assertEquals(choice, VisionosTest.of(own = choice.own, all = choice.all))
        }
    }

    @Test
    fun `no choice leaves both switches on`() {
        for (choice in VisionosTest.entries) assertFalse(choice.name, choice.own && choice.all)
    }

    @Test
    fun `both switches on, as two rows once allowed, shows as every version`() {
        assertEquals(VisionosTest.ALL, VisionosTest.of(own = true, all = true))
    }

    @Test
    fun `neither switch on is off`() {
        assertEquals(VisionosTest.OFF, VisionosTest.of(own = false, all = false))
    }

    @Test
    fun `a build offers only what its chain has`() {
        assertEquals(listOf(VisionosTest.OFF, VisionosTest.OWN, VisionosTest.ALL), VisionosTest.choices(identities = true, fallback = true))
        assertEquals(listOf(VisionosTest.OFF, VisionosTest.ALL), VisionosTest.choices(identities = false, fallback = true))
        assertEquals(listOf(VisionosTest.OFF, VisionosTest.OWN), VisionosTest.choices(identities = true, fallback = false))
    }
}
