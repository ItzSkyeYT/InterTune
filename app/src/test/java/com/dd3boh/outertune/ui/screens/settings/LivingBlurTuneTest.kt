/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.screens.settings.fragments.LivingTuneDoor
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

    private fun bodyOf(source: String, header: String) = source.substringAfter(header).substringBefore("\n}\n")

    @Test
    fun `everyone has it since 0_13, his yes of 20 Nov 2026`() {
        assertTrue(read("constants/Unreleased.kt").contains("val LIVING_TUNE = true"))
    }

    @Test
    fun `the pencil on the sample opens it, and only where the flag is on`() {
        val theme = read("ui/screens/settings/fragments/ThemeFrag.kt")
        assertTrue("no pencil without the flag", theme.contains("onEdit = if (Unreleased.LIVING_TUNE) ({ tune.open = true }) else null,"))
        assertTrue("the sample asks no frames under it", theme.contains("paused = tune.open"))
        assertTrue(theme.contains("val tune = LocalLivingTune.current"))
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
        assertFalse(TunePlan.sideBySide(1440.dp, 2933.dp))
        assertEquals(1440, TunePlan.barWidth(1440, sideBySide = false))
        assertTrue(TunePlan.sideBySide(2933.dp, 1440.dp))
        assertEquals(2933 / 2, TunePlan.barWidth(2933, sideBySide = true))
        val tune = read("ui/screens/settings/fragments/LivingBlurTune.kt")
        assertTrue(tune.contains("val side = TunePlan.coverSide(width, height, lines.maxIntrinsicHeight(width), gap)"))
        assertFalse("never as wide as the screen whatever its room", tune.contains(".fillMaxWidth()\n                            .aspectRatio(1f)"))
        assertTrue("the bar scrolls where the screen is too short", tune.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `turning the phone keeps it open, the activity keeps it and draws it`() {
        // His report of 27 Nov 2026: "when i go from portrait to landscape it closes itself". Turning the phone moves Look
        // and feel between a page of its own and Settings' two panes (SettingsFollowTheTurn), which made it anew, and the
        // tuner it held went with it. The activity keeps whether it is open, saved, and draws it over every screen.
        val theme = read("ui/screens/settings/fragments/ThemeFrag.kt")
        assertFalse("Look and feel no longer draws it", theme.contains("LivingBlurTune("))
        assertFalse(theme.contains("var tuning by rememberSaveable"))
        val main = read("MainActivity.kt")
        assertTrue(main.contains("val livingTune = rememberSaveable(saver = LivingTuneDoor.Saver) { LivingTuneDoor() }"))
        assertTrue(main.contains("LocalLivingTune provides livingTune,"))
        assertTrue(main.contains("LivingTuneHost(livingTune)"))
        val host = bodyOf(read("ui/screens/settings/fragments/LivingBlurTune.kt"), "fun LivingTuneHost(door: LivingTuneDoor) {")
        assertEquals("if (door.open) LivingBlurTune(onClose = { door.open = false })", host.trim())
        // Saved with the activity: a change of language or text size makes it anew, and the tuner is open again.
        val door = LivingTuneDoor().apply { open = true }
        val saved = with(LivingTuneDoor.Saver) { SaverScope { true }.save(door) }
        assertEquals(true, saved)
        assertTrue(LivingTuneDoor.Saver.restore(saved!!)!!.open)
        assertFalse(LivingTuneDoor().open)
    }

    @Test
    fun `on its side the sliders share a row, and the play row is never scrolled away nor cut`() {
        // His report of 27 Nov 2026, his S25U on its side (832 x 384 dp, 3.75 px a dp on his screenshot), his lane's own
        // colours' row in the bar: the bar has 318 dp, its settings and play row one over the other needed 376, and the
        // play button was cut in half at the bar's edge. Side by side the two sliders take one slider's height (72 dp less).
        val tune = read("ui/screens/settings/fragments/LivingBlurTune.kt")
        assertTrue(tune.contains("val sideBySide = with(LocalLandscape.current) { TunePlan.sideBySide(windowWidth, windowHeight) }"))
        assertTrue("the screen and the bar go by one decision", tune.contains("TuneScreen(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), sideBySide) {"))
        assertTrue(tune.contains("TuneSliders(sideBySide, strength, onStrength, smoothing, onSmoothing)"))
        val sliders = bodyOf(tune, "private fun TuneSliders(")
        assertTrue(sliders, sliders.contains("if (sideBySide) {\n        Row(horizontalArrangement = Arrangement.spacedBy(SLIDERS_GAP)) {"))
        assertTrue(sliders, sliders.contains("Column(Modifier.weight(1f)) { TuneSlider(R.string.living_tune_strength"))
        assertTrue(sliders, sliders.contains("Column(Modifier.weight(1f)) { TuneSlider(R.string.living_tune_smoothing"))
        // Upright as the 26 Nov fix left it: one over the other, 8 dp between.
        assertTrue(sliders, sliders.contains("} else {\n        TuneSlider(R.string.living_tune_strength, strength, onStrength, names)\n        // A slider's name nearer its own slider than the one above.\n        Spacer(Modifier.height(8.dp))"))
        // The settings scroll in what the play row leaves; the play row is outside the scroll, under it, always whole.
        val bar = bodyOf(tune, "private fun TuneBar(")
        val scroll = bar.indexOf(".weight(1f, fill = false)\n                .verticalScroll(rememberScrollState())")
        val play = bar.indexOf("content = play)")
        assertTrue(bar, scroll > 0 && play > scroll)
        assertEquals("one scroll in the bar, the settings'", 1, Regex("verticalScroll").findAll(bar).count())
        assertTrue(tune.contains("TuneBar(pane, onHeight = { barHeight = it }, settings = {"))
        assertTrue("the play row is the bar's last part", tune.contains("}) {\n                            if (song != null) {\n                                // The player's own three, in its order."))
    }

    @Test
    fun `the segmented button's names stay beside the tick and shrink together`() {
        // Seen 26 Nov 2026 in French at text size 1.3 on a 360 dp phone: "Trois couleurs" ran into the divider, its tick on
        // the outline. Material measures the name with the whole segment, the tick's room too.
        val tune = read("ui/screens/settings/fragments/LivingBlurTune.kt")
        assertTrue(tune.contains("SegmentLabel(stringResource(R.string.living_tune_three_colours), segments)"))
        assertTrue(tune.contains("SegmentLabel(stringResource(R.string.living_tune_cover), segments)"))
        val label = bodyOf(tune, "private fun SegmentLabel(")
        assertTrue(label, label.contains("val tick = SegmentedButtonDefaults.IconSize + SEGMENT_ICON_GAP"))
        assertTrue(label, label.contains("maxWidth = (constraints.maxWidth - tick.roundToPx()).coerceAtLeast(0)"))
        assertTrue(label, label.contains("style.shrinkToFit(smallest = 11.sp, text = text, row = names)"))
    }
}
