/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.screens.settings.fragments.TunePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Living blur tuned on the whole screen (his ask of 20 Nov 2026, a mockup first): for everyone in 0.13,
 * opened from the sample in Look and feel, and changing the very settings the player reads, so it has
 * each change at once. Since 27 Nov 2026 the only place they are set: the rows under the sample went.
 */
class LivingBlurTuneTest {

    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `everyone has it since 0_13, his yes of 20 Nov 2026`() {
        assertTrue(read("constants/Unreleased.kt").contains("val LIVING_TUNE = true"))
    }

    @Test
    fun `the button under the sample opens it, and only where the flag is on`() {
        val theme = read("ui/screens/settings/fragments/ThemeFrag.kt")
        assertTrue("no button without the flag", theme.contains("if (Unreleased.LIVING_TUNE) {\n                FilledTonalButton(\n                    onClick = { tuning = true },"))
        assertTrue("the sample asks no frames under it", theme.contains("paused = tuning"))
        assertTrue(theme.contains("if (tuning) LivingBlurTune(onClose = { tuning = false })"))
        val sample = read("ui/screens/settings/fragments/LivingBlurSample.kt")
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

    @Test
    fun `with Liquid glass off it has no glass, a plain back panel and Material's own buttons`() {
        // Ti-Die, #testers 21 Nov 2026: "this menu is liquid glassed even when liquid glass isn't on ... remove the blur
        // and the edge effects. The icons at the top tho and the "Three Colours/cover" segment can be normal material3 style".
        val tune = read("ui/screens/settings/fragments/LivingBlurTune.kt")
        assertTrue(tune.contains("val liquidGlass by rememberPreference(PlayerLiquidGlassKey, defaultValue = false)"))
        // Nothing of the picture is kept for glass that is not drawn.
        assertTrue(tune.contains("if (still || !liquidGlass) Modifier else Modifier.layerBackdrop(backdrop)"))
        val pane = tune.substringAfter("private fun Modifier.pane(").substringBefore("\n}")
        assertTrue(pane, pane.contains("if (!pane.glass) return this.background(pane.tint, shape)"))
        val round = tune.substringAfter("private fun TuneRoundButton(").substringBefore("\n}")
        assertTrue(round, round.contains("if (!pane.glass) {\n        FilledTonalIconButton(onClick = onClick"))
        assertTrue("Reset without glass", tune.contains("FilledTonalButton(onClick = reset, modifier = Modifier.height(48.dp))"))
        assertFalse("the old name, always glass", tune.contains("GlassRoundButton("))
    }

    @Test
    fun `nothing touches, the cover fits the room between the top row and the bar, and the bar scrolls where it cannot fit`() {
        // His report of 26 Nov 2026, his S25U at 3.75 px a dp with his lane's colours row in the bar: the cover, as wide as
        // the screen (320 dp) in a room 286 dp tall, ran 8 dp under the top row's buttons and down to the title's letters.
        fun dp(v: Int) = (v * 3.75f).toInt()
        // The same screen now: the room the cover and the song's two lines (52 dp) share, 12 dp apart.
        val side = TunePlan.coverSide(width = dp(320), height = dp(314), songHeight = dp(52), gap = dp(12))
        assertTrue("a square in the room, the lines under it", side + dp(12) + dp(52) <= dp(314))
        assertEquals("as big as the room lets it be", dp(250), side)
        assertEquals("a room with no space for it has no cover, never less", 0, TunePlan.coverSide(dp(300), dp(40), dp(52), dp(12)))
        // Upright the bar is the screen's width, under the cover; on its side the end half, beside it.
        assertFalse(TunePlan.sideBySide(1440, 2933))
        assertEquals(1440, TunePlan.barWidth(1440, 2933))
        assertTrue(TunePlan.sideBySide(2933, 1440))
        assertEquals(2933 / 2, TunePlan.barWidth(2933, 1440))
        val tune = read("ui/screens/settings/fragments/LivingBlurTune.kt")
        assertTrue(tune.contains("val side = TunePlan.coverSide(width, height, lines.maxIntrinsicHeight(width), gap)"))
        assertFalse("never as wide as the screen whatever its room", tune.contains(".fillMaxWidth()\n                            .aspectRatio(1f)"))
        assertTrue("the bar scrolls where the screen is too short", tune.contains(".verticalScroll(rememberScrollState())"))
    }
}
