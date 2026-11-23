/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screen stays awake while the Recap's story plays (his ask of 22 Nov 2026), through the one owner of the
 * flag, and is let go when it is paused, on the start screen, and when the Recap closes.
 */
class RecapAwakeTest {
    private val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapScreen.kt").readText()

    @Test
    fun `the story playing holds the screen awake, nothing else does`() {
        assertTrue("val playing = page.at > 0 && !stopped" in screen)
        assertTrue("KeepScreenOnController.set(view, KeepScreenOnReason.RECAP, playing)" in screen)
        assertTrue("let go when the Recap closes", "onDispose { KeepScreenOnController.set(view, KeepScreenOnReason.RECAP, false) }" in screen)
        assertTrue("never the flag itself, which the player's lyrics use too", "view.keepScreenOn" !in screen)
    }
}
