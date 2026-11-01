/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "equalizer doesn't work", an answer in the 0.13 poll (31 Oct 2026, on 0.12.1): the player menu's
 * Equalizer opened the phone's own panel, which most phones do not have, and then did nothing. It
 * opens the app's own equalizer, in every build since 0.12.
 */
class EqualizerMenuTest {
    @Test
    fun `the player menu's Equalizer opens the app's own equalizer, and folds the player away to show it`() {
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt").readText()
        val item = menu.substringAfter("title = R.string.equalizer").substringBefore("GridMenuItem(")
        assertTrue(item.contains("if (Unreleased.EQUALIZER) {\n                navController.navigate(\"settings/player/equalizer\")\n                playerBottomSheetState.collapseSoft()"))
        assertTrue("the page it opens is there", File("src/main/java/com/dd3boh/outertune/ui/navigation/AppNavGraph.kt").readText().contains("screen(\"settings/player/equalizer\")"))
    }
}
