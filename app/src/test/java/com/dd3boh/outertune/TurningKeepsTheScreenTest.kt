/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Turning the phone keeps the screen as it is: the main activity takes the change itself instead
 * of being made anew, which lost whatever was open or half done (15 Oct 2026: "stuff breaks quite
 * often when I rotate while doing something").
 */
class TurningKeepsTheScreenTest {

    private val mainActivity: String = File("src/main/AndroidManifest.xml").readText()
        .substringAfter("android:name=\".MainActivity\"")
        .substringBefore(">")

    @Test
    fun theMainScreenTakesATurnItself() {
        val changes = Regex("android:configChanges=\"([^\"]*)\"").find(mainActivity)?.groupValues?.get(1)?.split("|").orEmpty()
        for (change in listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout")) {
            assertTrue("$change is taken by the screen itself (has: $changes)", change in changes)
        }
    }

    @Test
    fun itsLayoutsFollowTheWindowAsItChanges() {
        // The rail, the two panes and the player's shape come from the window's size as it is now,
        // not from one read once when the screen was made.
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue(main.contains("LocalWindowInfo.current.containerSize"))
        assertTrue(main.contains("remember(windowPx, laidOutWidth, windowDensity)"))
    }

    @Test
    fun thePagesMakeRoomForTheRailOnceItIsThere() {
        // Turned on its side the bar becomes a rail down the left, and every page is pushed right
        // by its width through the player-aware insets. Those were remembered on the bottom inset
        // and the player alone, so on a turn without a new screen they kept the width they had
        // upright, none, and Home ran under the rail: "ory" for History, "icks" for Quick picks
        // (his S25U, 18 Oct 2026).
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        val keys = main.substringAfter("val playerAwareWindowInsets =").substringBefore(") {")
        assertTrue("the insets follow the rail: $keys", keys.contains("useNavRail"))
    }
}
