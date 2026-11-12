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
 * Liquid glass below Android 13 (Mesh Raj's ask of 7 Nov 2026, a Galaxy M30s on Android 11): no
 * glass can be drawn there, but the tab bar and the mini player still float as a dock with a plain
 * fill, and only there: Android 13 and up keep their glass, the switch off keeps the classic bar.
 */
class PlainDockTest {
    private fun src(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the plain dock is only below Android 13 with Liquid glass on, and never on the rail`() {
        val main = src("MainActivity.kt")
        assertTrue("val plainDock = liquidGlass && !navGlass && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !useNavRail" in main)
        assertTrue("LocalPlainDock provides plainDock," in main)
        assertTrue("containerColor = if (navGlass || plainDock) Color.Transparent" in main)
        // No switch left in that would draw it as Android 11 on a newer phone.
        assertTrue("pictureAsAndroid11" !in main)
    }

    @Test
    fun `the mini player floats with it, over a clear sheet`() {
        assertTrue("val plain = !glassOn && LocalPlainDock.current" in src("ui/player/MiniPlayer.kt"))
        assertTrue("collapsedBackgroundColor = if ((liquidGlass && LocalAppBackdrop.current != null) || LocalPlainDock.current) Color.Transparent" in src("ui/player/Player.kt"))
    }

    @Test
    fun `the switch says what it gives below Android 13`() {
        val en = File("src/main/res/values/strings-ot.xml").readText().substringAfter("name=\"player_liquid_glass_description_legacy\">").substringBefore("</string>")
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText().substringAfter("name=\"player_liquid_glass_description_legacy\">").substringBefore("</string>")
        assertTrue(en, "dock" in en)
        assertTrue(fr, "dock" in fr)
    }
}
