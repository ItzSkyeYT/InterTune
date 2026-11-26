/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 26 Nov 2026 (Niko, #testers): on a phone set to 24 hours, the Recap in English read "4:00 PM" on its hour
 * slide, as it followed the language's habit. Its times follow the phone's own 24-hour switch now.
 */
class RecapClockTest {

    @Test
    fun `the Recap's times follow the phone's 24-hour switch, not the language`() {
        val words = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapWords.kt").readText()
        assertTrue(words, "private val twentyFour = runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(false)" in words)
        assertTrue(words, "LocaleDateFormat(locale, if (twentyFour) \"Hm\" else \"hm\", if (twentyFour) \"HH:mm\" else \"h:mm a\")" in words)
        assertTrue(words, "private val twelveHours = !twentyFour" in words)
        assertFalse("the language's own hour cycle no longer decides", "\"jm\"" in words)
    }
}
