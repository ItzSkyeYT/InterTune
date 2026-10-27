/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A sheet on its way down leaves further back presses to what is under it (issue #31): pressed
 * quickly, back used to restart the fall from a standstill with every press.
 */
class BackWhileClosingTest {
    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/BottomSheet.kt").readText()

    @Test
    fun `both back handlers stand aside while the sheet is closing`() {
        assertTrue(source.contains("animatable.isRunning && animatable.targetValue == collapsedBound"))
        assertTrue(source.contains("val takesBack: Boolean\n        get() = !isCollapsed && !isDismissed && !isCollapsing"))
        assertTrue(source.contains("if (backOn) {\n                BackHandler(onBack = moves::collapseSoft)"))
        assertTrue(source.contains("PredictiveBackHandler(enabled = backOn)"))
    }

    @Test
    fun `a Back that moves another sheet stands aside while that one is closing too`() {
        // The queue's Back, when it closes the player (issue #30): pressed again while the player
        // falls, it goes under the player, as the second of two quick presses always did.
        assertTrue(source.contains("val backOn = state.takesBack && (moves === state || moves.takesBack)"))
    }
}
