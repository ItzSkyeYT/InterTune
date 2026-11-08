/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #104 (0.12.9, Pixel 11 Pro, Android 17): the mini player's previous, play and next were black on
 * its black panel while its title read fine. The buttons wear the title's colour.
 */
class MiniPlayerButtonsTest {

    @Test
    fun `the buttons wear the title's colour`() {
        val source = File("src/main/java/com/dd3boh/outertune/ui/player/MiniPlayer.kt").readText()
        val buttons = Regex("""val iconButtonColor = MaterialTheme\.colorScheme\.(\w+)""").find(source)!!.groupValues[1]
        val title = Regex("""text = mediaMetadata\.title,\s*color = MaterialTheme\.colorScheme\.(\w+)""").find(source)!!.groupValues[1]
        assertEquals(title, buttons)
        assertTrue("the three buttons use it", Regex("tint = iconButtonColor").findAll(source).count() >= 3)
    }
}
