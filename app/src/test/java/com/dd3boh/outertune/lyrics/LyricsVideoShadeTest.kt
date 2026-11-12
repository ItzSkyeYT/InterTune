/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A lyrics video's Living blur is shaded to show as much as the player shows behind its lyrics.
 * It used to copy the dark theme's 40% alone: the player lays black at 30% more while the lyrics
 * are up, and Liquid glass halves the field, so on a Pixel 5 with Liquid glass the video came out
 * about one and a half times brighter than the player it was made from (2 Nov 2026).
 */
class LyricsVideoShadeTest {

    @Test
    fun `as much shows as in the player, with Liquid glass and without`() {
        assertEquals("the dark theme, the lyrics up", 0.42f, LyricsVideoPlan.livingShows(0f), 0.001f)
        assertEquals("under Liquid glass", 0.35f, LyricsVideoPlan.livingShows(1f), 0.001f)
        assertEquals("half the glass", 0.75f * 0.8f * 0.7f, LyricsVideoPlan.livingShows(0.5f), 0.001f)
        assertEquals("out of range is held in", LyricsVideoPlan.livingShows(1f), LyricsVideoPlan.livingShows(3f), 0f)
    }

    @Test
    fun `the numbers are still the player's`() {
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        for (line in listOf(
            "val glassT = if (liquidGlass) glassIntensity.coerceIn(0f, 1f) else 0f",
            "val stockOverlayAlpha = if (useDarkTheme) 0.4f else 0.55f",
            "lerp(stockOverlayAlpha, 0f, glassT)",
            "val artworkAlpha = lerp(1f, 0.5f, glassT)",
            "alpha = artworkAlpha,",
            "if (useDarkTheme) Color.Black.copy(alpha = 0.3f)",
            "rememberPreference(PlayerGlassIntensityKey, defaultValue = 1f)",
            "rememberPreference(PlayerLiquidGlassKey, defaultValue = false)",
        )) assertTrue("Player.kt changed how Living blur is shaded: LyricsVideoPlan.livingShows follows it ($line)", player.contains(line))
    }

    @Test
    fun `the video takes the player's own settings`() {
        val card = File("src/main/java/com/dd3boh/outertune/ui/component/LyricsCard.kt").readText()
        assertTrue(card.contains("shows = LyricsVideoPlan.livingShows(if (liquidGlass) glassIntensity else 0f)"))
        assertTrue(card.contains("rememberPreference(PlayerLiquidGlassKey, defaultValue = false)"))
        assertTrue(card.contains("rememberPreference(PlayerGlassIntensityKey, defaultValue = 1f)"))
    }
}
