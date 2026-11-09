/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song change with the player open makes only the player again for its colours: a new theme makes
 * everything under it again, and the pages under the open player cost a frame of about 0.7 s on a
 * Pixel 5 at each song change (9 Nov 2026). Read from the source, as the rule lives in MainActivity.
 */
class PlayerThemeTest {

    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    @Test
    fun `the player takes the song's colours at once, the pages once it is down`() {
        // The app's theme follows the pages' colours, not the song's.
        assertTrue("themeColor = pagesThemeColor" in main)
        assertEquals(1, Regex("""OuterTuneTheme\(""").findAll(main).count())
        // The player in a theme of its own, which reads the song's colour inside it.
        assertTrue("PlayerTheme(useDarkTheme, pureBlack, highContrastCompat, { themeColor }) {" in main)
        val player = main.substringAfter("PlayerTheme(useDarkTheme, pureBlack, highContrastCompat, { themeColor }) {").substringBefore("}")
        assertTrue(player, "BottomSheetPlayer(" in player)
        // The pages catch up only with the player down: collapsed or dismissed, never while it moves.
        assertTrue("snapshotFlow { themeColor to (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed) }" in main)
        assertTrue(".collect { (colour, down) -> if (down && pagesThemeColor != colour) pagesThemeColor = colour }" in main)
    }
}
