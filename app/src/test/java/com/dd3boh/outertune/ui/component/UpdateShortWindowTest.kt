/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The update dialog when Auto Blocker, or anything else, stopped the install: a message and four
 * buttons. Stacked and as wide as the dialog, they took all the height of a phone on its side with
 * a large font and left the message a line and a half to be scrolled in, and the useful sentence
 * of it is the last. On a short window the three smaller buttons share a row instead.
 */
class UpdateShortWindowTest {

    @Test
    fun `on a short window the blocked dialog's smaller buttons share a row, and the stacked ones stay elsewhere`() {
        // A phone on its side was 448 dp tall on the emulator; Android's compact height ends at 480.
        assertTrue(shortWindow(448))
        assertTrue(shortWindow(479))
        assertFalse(shortWindow(480))
        assertFalse(shortWindow(891))

        val prompt = File("src/main/java/com/dd3boh/outertune/ui/component/UpdatePrompt.kt").readText()
        val buttons = prompt.substringAfter("private fun TroubleButtons(").substringBefore("private fun TroubleSecondary(")
        assertTrue(buttons.contains("shortWindow(LocalConfiguration.current.screenHeightDp)"))
        assertTrue(buttons.contains("FlowRow("))
        // The one that matters is the same wherever it is, and full width.
        assertEquals(1, Regex("""\bButton\(""").findAll(buttons).count())
        // The same buttons in both layouts, one function: Try again or the release page, Remind me later, Skip.
        val secondary = prompt.substringAfter("private fun TroubleSecondary(").substringBefore("internal fun shortWindow")
        assertTrue(secondary.contains("val fit = if (wide) Modifier.fillMaxWidth() else Modifier"))
        assertEquals(4, Regex("""\bTextButton\(""").findAll(secondary).count())
        for (word in listOf("update_try_again", "update_open_page", "update_prompt_later", "update_skip")) {
            assertTrue(word, secondary.contains("R.string.$word"))
        }
    }
}
