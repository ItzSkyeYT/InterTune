/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Look and feel without the rows the player and the whole-screen tuner already cover (his ask of 27 Nov 2026, for 0.13:
 * "remove the 'group player buttons' option as well as the sliders under living blur as well as the option underneath,
 * as everything happens in the fullscreen thing"). Read from the source, as the other settings tests are.
 */
class LookAndFeelCleanupTest {

    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val theme = read("ui/screens/settings/fragments/ThemeFrag.kt")
    private val sample = read("ui/screens/settings/fragments/LivingBlurSample.kt")

    private fun strings(folder: String) = File("src/main/res/$folder/strings-ot.xml").readText()

    @Test
    fun `the player's buttons share one glass bar wherever Liquid glass is on, and no switch takes it apart`() {
        val player = read("ui/player/Player.kt")
        val bar = player.substringAfter("// The bar's glass is a layer of its own behind the buttons").substringBefore("Row(")
        assertTrue(bar, bar.contains("if (liquidGlass) {"))
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        assertFalse("something still reads the old switch", sources.any { "GroupedPlayerControlsKey" in it })
        assertFalse("its row is gone", theme.contains("R.string.grouped_player_controls"))
        // The glass sample under Liquid glass shows the bar as the player has it: one bar wherever there is glass.
        val glassSample = theme.substringAfter("private fun GlassSample(")
        assertTrue(glassSample.startsWith("intensity: Float, modifier: Modifier = Modifier)"))
        assertFalse(glassSample.contains("grouped"))
        // The texts stay for the translators until they are tidied away with the others.
        assertTrue(strings("values").contains("name=\"grouped_player_controls\""))
    }

    @Test
    fun `Living blur's colours, strength and smoothing are set in the tuner, and Look and feel has no rows for them`() {
        for (row in listOf("player_living_colours", "player_living_strength", "player_living_smoothing")) {
            assertFalse("$row is still a row", theme.contains("R.string.$row"))
        }
        for (setter in listOf("onLivingColoursChange", "onLivingStrengthChange", "onLivingSmoothingChange")) {
            assertFalse("$setter: Look and feel still sets it", theme.contains(setter))
        }
        // One slider is left on the page, Liquid glass's intensity.
        assertEquals(1, Regex("""\bSlider\(""").findAll(theme).count())
        // The sample still draws what the tuner set.
        val call = theme.substringAfter("LivingBlurSample(").substringBefore("\n            )")
        for (value in listOf("strength = livingStrength,", "smoothing = livingSmoothing,", "colours = livingColours,")) {
            assertTrue(value, call.contains(value))
        }
        // And says why it stands still, with animations off.
        assertTrue(theme.contains("MeaningLine(stringResource(stillBecause))"))
    }

    @Test
    fun `a button with the tuner's name, right under the sample, is the way in`() {
        val living = theme.substringAfter("LivingBlurSample(").substringBefore("val stillBecause")
        val button = living.substringAfter("if (Unreleased.LIVING_TUNE) {")
        assertTrue(living, button.startsWith("\n                FilledTonalButton(\n                    onClick = { tuning = true },"))
        assertTrue(button, button.contains("Icon(Icons.Rounded.Tune, contentDescription = null") && button.contains("Text(stringResource(R.string.living_tune_open))"))
        // The app's own words for it, in English and in French: nothing new to translate.
        for (folder in listOf("values", "values-fr")) assertTrue(folder, strings(folder).contains("<string name=\"living_tune_open\">"))
        // Not an icon in the sample's corner any more, and no button in the sample's words (Ti-Die, 19 Nov 2026).
        assertFalse(sample.contains("Icons.Rounded.Fullscreen") || sample.contains("onFullScreen"))
        assertFalse(sample.contains("FilledTonalButton"))
        assertFalse("the line about the sliders went with them", sample.contains("R.string.player_living_sample_playing"))
    }

    @Test
    fun `the search knows no row that is gone, and finds Living blur's old rows on the style they hang from`() {
        val index = read("ui/screens/settings/SettingsIndex.kt")
        for (gone in listOf("grouped_player_controls", "player_living_colours", "player_living_strength", "player_living_smoothing")) {
            assertFalse("$gone has an entry", Regex("""SettingEntry\([^,]+, R\.string\.$gone\b""").containsMatchIn(index))
        }
        val style = index.lines().single { "SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_background_style," in it }
        for (old in listOf("player_living_colours", "player_living_strength", "player_living_smoothing")) {
            assertTrue("$old is not a word of the style's entry", style.contains("R.string.$old"))
        }
    }
}
