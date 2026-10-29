/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When Living blur stands still, and that it says why. #49 (27 Oct 2026): on a Samsung in Power
 * saving it never moved, Settings showed it in full, and every slider seemed to do nothing.
 */
class LivingStillTest {
    private fun read(path: String) = File(path).readText()

    @Test
    fun `it stands still for Saver as Battery use decides it, not for the phone's saver behind its back`() {
        val body = read("src/main/java/com/dd3boh/outertune/ui/player/LivingBackground.kt")
        assertTrue(body.contains("wantsStillness(context, LocalBatteryMode.current)"))
        assertTrue(body.contains("mode == BatteryMode.SAVER || (!Unreleased.BATTERY_MODES && context.isPowerSaver()) || context.animationsOff()"))
        assertTrue("a change of mode while it is on screen is not taken", body.contains("motion, lifecycleOwner, still) {"))
    }

    @Test
    fun `with animations off Settings says why, in English and in French`() {
        val frag = read("src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/ThemeFrag.kt")
        assertTrue(Regex("""animationsOff\(\)\) \{\s*Box\([^\n]*\) \{\s*MeaningLine\(stringResource\(R\.string\.living_blur_still_animations\)\)""").containsMatchIn(frag))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            assertTrue(path, read(path).contains("name=\"living_blur_still_animations\""))
        }
    }
}
