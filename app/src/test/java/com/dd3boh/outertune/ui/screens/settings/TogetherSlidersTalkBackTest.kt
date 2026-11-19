/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings > Listen together: the three sliders had no name and TalkBack read each as a percent of its range, so a
 * delay of 250 ms out of 1000 was "25 percent" and nothing said which setting moved (the 0.13 accessibility read,
 * 18 Nov 2026). Each slider now carries its setting's name and the value as the line above it shows it.
 */
class TogetherSlidersTalkBackTest {

    private val settings = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/ListenTogetherSettings.kt").readText()

    @Test
    fun `each slider is named by its setting and says the value as shown, not a percent`() {
        val slider = settings.substringAfter("private fun SliderSetting(").substringBefore("\n}\n")
        assertTrue(slider, "val now = shown(moving.roundToInt())" in slider)
        val semantics = slider.substringAfter("Slider(").substringBefore("\n        )\n")
        assertTrue(semantics, "modifier = Modifier.semantics {\n                contentDescription = title\n                stateDescription = now\n            }," in semantics)
        // the line above shows the same words TalkBack says
        assertTrue(slider, "Text(\n            now,\n            style = MaterialTheme.typography.bodyMedium," in slider)
        assertEquals("one reading of the value", 1, Regex("""shown\(moving""").findAll(slider).count())
    }

    @Test
    fun `the three settings go through it, each with its own name and its unit`() {
        assertEquals(3, Regex("""SliderSetting\(\n\s+title = stringResource\(R\.string\.together_""").findAll(settings).count())
        for (value in listOf("together_tolerance_value", "together_catch_up_value", "together_delay_value")) {
            assertTrue(value, "shown = { stringResource(R.string.$value, it) }" in settings)
        }
    }
}
