/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Repeat off is the repeat icon greyed out, as the media notification draws it, not the big "1"
 * upstream drew (his word, 5 Nov 2026). Read from the source, as the flag tests are.
 */
class RepeatOffIconTest {
    private fun code(name: String) = File("src/main/java/com/dd3boh/outertune/ui/player/$name").readText()

    @Test
    fun `the player and the queue draw off as the repeat icon, dimmed`() {
        for (name in listOf("Player.kt", "Queue.kt")) {
            val text = code(name)
            assertTrue(name, "REPEAT_MODE_OFF -> R.drawable.repeat_on" in text)
            assertTrue(name, Regex("""color = if \(repeatMode == REPEAT_MODE_OFF\) \w+\.copy\(alpha = REPEAT_OFF_ALPHA\) else \w+,""").containsMatchIn(text))
            assertFalse(name, "R.drawable.repeat_off" in text)
        }
        assertFalse("the big 1 is gone", File("src/main/res/drawable/repeat_off.xml").exists())
    }

    @Test
    fun `repeat one keeps its own icon`() {
        for (name in listOf("Player.kt", "Queue.kt")) assertEquals(name, 1, Regex("""REPEAT_MODE_ONE -> R\.drawable\.repeat_one""").findAll(code(name)).count())
    }
}
