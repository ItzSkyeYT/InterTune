/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Living blur tuned on the whole screen (his ask of 20 Nov 2026, a mockup first): the debug app
 * only until he has seen it, opened from the sample in Look and feel, and changing the very
 * settings the rows under the sample change, so the player has each change at once.
 */
class LivingBlurTuneTest {

    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `everyone has it since 0_13, his yes of 20 Nov 2026`() {
        assertTrue(read("constants/Unreleased.kt").contains("val LIVING_TUNE = true"))
    }

    @Test
    fun `the sample opens it, and only where the flag is on`() {
        val theme = read("ui/screens/settings/fragments/ThemeFrag.kt")
        assertTrue(theme.contains("onFullScreen = if (Unreleased.LIVING_TUNE) ({ tuning = true }) else null"))
        assertTrue("the sample asks no frames under it", theme.contains("paused = tuning"))
        assertTrue(theme.contains("if (tuning) LivingBlurTune(onClose = { tuning = false })"))
        val sample = read("ui/screens/settings/fragments/LivingBlurSample.kt")
        assertTrue("no button without the flag", sample.contains("if (onFullScreen != null) {"))
        assertTrue(sample.contains("covered = paused"))
    }

    @Test
    fun `it changes the settings themselves, and draws nothing round the cover`() {
        val tune = read("ui/screens/settings/fragments/LivingBlurTune.kt")
        for (key in listOf("LivingStrengthKey", "LivingSmoothingKey", "LivingColoursKey")) {
            assertTrue(key, tune.contains("rememberPreference($key") || tune.contains("rememberEnumPreference($key"))
        }
        assertTrue("reset is the defaults", tune.contains("onStrength(LivingField.DEFAULT_STRENGTH)") && tune.contains("onSmoothing(LivingField.DEFAULT_SMOOTHING)") && tune.contains("onColours(LivingColours.MAIN)"))
        assertFalse(tune.contains("coverPlace"))
    }
}
