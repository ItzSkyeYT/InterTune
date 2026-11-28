/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.screens.settings.fragments.SamplePlan
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
    fun `a pencil with Edit on the sample's top end corner is the way in, and the wide button under it went`() {
        // Ti-Die's layout of 28 Nov 2026, with his yes: "more accurate to what the menu does and cleaner".
        val call = theme.substringAfter("LivingBlurSample(").substringBefore("\n            )")
        // It opens the tuner the activity keeps open through a turn (LivingTuneDoor, tuner-fixes of 27 Nov 2026).
        assertTrue(call, call.contains("onEdit = if (Unreleased.LIVING_TUNE) ({ tune.open = true }) else null,"))
        val living = theme.substringAfter("LivingBlurSample(").substringBefore("val stillBecause")
        assertFalse("the wide button under the sample", living.contains("Button("))
        assertFalse(theme.contains("R.string.living_tune_open"))
        assertFalse(theme.contains("import androidx.compose.material3.FilledTonalButton"))
        // A pencil and the word Edit, translated in every language, and what TalkBack reads: editing Living blur, from texts
        // the app already has.
        val pencil = sample.substringAfter("private fun EditButton(").substringBefore("\n}\n")
        assertTrue(pencil, pencil.contains("Icon(Icons.Rounded.Edit, contentDescription = null"))
        assertTrue(pencil, pencil.contains("val described = \"\${stringResource(R.string.edit)}, \${stringResource(R.string.player_background_living)}\""))
        // On the word, not on the button: there Compose gives the description a node of its own, read before the word's
        // ("Edit, Living blur, Edit"), as the emulator's accessibility tree showed on 28 Nov 2026.
        assertTrue(pencil, pencil.contains("Text(stringResource(R.string.edit), maxLines = 1, modifier = Modifier.semantics { contentDescription = described })"))
        assertEquals(pencil, 1, Regex("""contentDescription = described""").findAll(pencil).count())
        // Made as the small button on the cover is: the same dark fill over the picture, white on it, 32 dp tall.
        assertTrue(pencil, pencil.contains("ButtonDefaults.buttonColors(containerColor = BUTTON_ON_PICTURE, contentColor = Color.White)"))
        assertTrue(sample, sample.contains("IconButtonDefaults.filledIconButtonColors(\n                            containerColor = BUTTON_ON_PICTURE,"))
        assertTrue(sample.contains("private val EDIT_HEIGHT = 32.dp"))
        // The sample gives it its place, and only where the page asks for it.
        assertTrue(sample.contains("edit = { if (onEdit != null) EditButton(onEdit) },"))
        // Not the old icon in the corner, and nothing in the sample's words (Ti-Die, 19 Nov 2026).
        assertFalse(sample.contains("Icons.Rounded.Fullscreen") || sample.contains("onFullScreen"))
        assertFalse(sample.contains("FilledTonalButton"))
        val words = sample.substringAfter("words = {").substringBefore("edit = {")
        assertFalse(words, words.contains("Button("))
        assertFalse("the line about the sliders went with them", sample.contains("R.string.player_living_sample_playing"))
        // The wide button's name stays in English and in French: the search finds the style by it.
        for (folder in listOf("values", "values-fr")) assertTrue(folder, strings(folder).contains("<string name=\"living_tune_open\">"))
    }

    @Test
    fun `the pencil sits in the card's top end corner, and the words are never under it`() {
        val layout = sample.substringAfter("private fun SampleLayout(").substringBefore("\n}\n")
        // At the inset from the top and from the end, mirrored for right-to-left languages.
        assertTrue(layout, layout.contains("editPart?.placeRelative(width - inset - editPart.width, inset)"))
        assertTrue(layout, layout.contains("val clear = if (editPart == null) 0 else inset + editPart.height + EDIT_GAP.roundToPx()"))
        assertTrue(layout, layout.contains("val overCover = editPart != null && width - inset - editPart.width < side + coverPart.width"))
        assertTrue(layout, layout.contains("val height = SamplePlan.height(LIVING_SAMPLE_HEIGHT.roundToPx(), coverPart.height, wordsPart.height, clear, overCover)"))
        assertTrue(layout, layout.contains("wordsPart.placeRelative(side + coverPart.width + gap, (height - wordsPart.height) / 2)"))
        // No taller in the layout than it is drawn, so the corner and the words' room are where it is seen.
        val pencil = sample.substringAfter("private fun EditButton(").substringBefore("\n}\n")
        assertTrue(pencil, pencil.contains("CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp)"))
        assertTrue(pencil, pencil.contains(".heightIn(min = EDIT_HEIGHT)"))

        // The numbers, at 2.75 px a dp: the pencil 32 dp tall, 8 dp in from the top, 8 dp over the words.
        fun dp(v: Int) = (v * 2.75f).toInt()
        val clear = dp(8) + dp(32) + dp(8)
        fun wordsTop(height: Int, words: Int) = (height - words) / 2
        // The usual size, English, a song playing (two lines) or none (three lines): the card as it was, 168 dp.
        for (words in listOf(dp(44), dp(60))) {
            val height = SamplePlan.height(dp(168), dp(96), words, clear)
            assertEquals("$words px of words", dp(168), height)
            assertTrue("$words px of words", wordsTop(height, words) >= clear)
        }
        // French at text size 1.3 on a small phone, five lines: the card grows, the words as far from its bottom as from
        // the top, both clear of the pencil.
        val french = dp(130)
        val taller = SamplePlan.height(dp(168), dp(96), french, clear)
        assertTrue(taller > dp(168))
        assertTrue(wordsTop(taller, french) >= clear)
        assertEquals(french + 2 * clear, taller)
        // Without the pencil, nothing to keep clear of: the card as it was, its words in the middle.
        assertEquals(dp(168), SamplePlan.height(dp(168), dp(96), dp(130), clear = 0))
        // Never shorter than the cover, and where the pencil is so wide that it reaches over the cover (a long word at a very
        // large text size), the cover keeps clear of it as the words do.
        assertEquals(dp(200), SamplePlan.height(dp(168), dp(200), dp(20), clear))
        val over = SamplePlan.height(dp(168), dp(96), dp(60), clear, overCover = true)
        assertEquals(dp(96) + 2 * clear, over)
        assertTrue((over - dp(96)) / 2 >= clear)
    }

    @Test
    fun `the search knows no row that is gone, and finds Living blur's old rows on the style they hang from`() {
        val index = read("ui/screens/settings/SettingsIndex.kt")
        for (gone in listOf("grouped_player_controls", "player_living_colours", "player_living_strength", "player_living_smoothing")) {
            assertFalse("$gone has an entry", Regex("""SettingEntry\([^,]+, R\.string\.$gone\b""").containsMatchIn(index))
        }
        val style = index.lines().single { "SettingEntry(Tour.ROUTE_LOOK_AND_FEEL, R.string.player_background_style," in it }
        // And the wide button's name, which opened the tuner until the sample's pencil did (28 Nov 2026).
        for (old in listOf("player_living_colours", "player_living_strength", "player_living_smoothing", "living_tune_open")) {
            assertTrue("$old is not a word of the style's entry", style.contains("R.string.$old"))
        }
    }
}
