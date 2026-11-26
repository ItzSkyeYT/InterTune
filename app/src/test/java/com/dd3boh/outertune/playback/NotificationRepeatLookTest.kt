/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #182: the notification's repeat button looks like the player's: off the plain icon, on and one a
 * lit box with the glyph cut out. The semantic icon constant stays on each button.
 */
class NotificationRepeatLookTest {

    private val source = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val repeat = source.substringAfter("MediaControlButton.REPEAT ->").substringBefore("MediaControlButton.LIKE ->")

    @Test
    fun `each repeat state keeps its semantic icon and gets the players look`() {
        for (c in listOf("ICON_REPEAT_OFF", "ICON_REPEAT_ONE", "ICON_REPEAT_ALL")) assertTrue(c, "CommandButton.$c" in repeat)
        assertTrue(repeat, "REPEAT_MODE_OFF -> R.drawable.repeat_on" in repeat)
        assertTrue(repeat, "REPEAT_MODE_ONE -> R.drawable.repeat_one_box" in repeat)
        assertTrue(repeat, "else -> R.drawable.repeat_all_box" in repeat)
        assertTrue(repeat, ".setCustomIconResId(" in repeat)
    }

    @Test
    fun `the two box drawables are white single colour vectors with the glyph cut out`() {
        for (n in listOf("repeat_all_box", "repeat_one_box")) {
            val x = File("src/main/res/drawable/$n.xml").readText()
            assertTrue(n, "<vector" in x)
            assertEquals(n, 1, Regex("<path").findAll(x).count())
            assertEquals(n, setOf("@android:color/white"), Regex("fillColor=\"([^\"]+)\"").findAll(x).map { it.groupValues[1] }.toSet())
            assertTrue(n, "android:fillType=\"evenOdd\"" in x)
        }
    }
}
