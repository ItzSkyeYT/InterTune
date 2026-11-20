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
 * Controls whose state TalkBack did not say, seen in passing by the review of 0.13 (accessibility.md, "Old, not new in
 * 0.13"): each now says what it is set to, in words the app already has or in Android's own. Nothing is drawn
 * differently. No Compose runs on the JVM, so this reads the source, as the other wiring tests do.
 */
class TalkBackStatesTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the sleep timer's slider says its name and the length it is at, not a percent`() {
        val sheet = source("ui/component/SleepTimer.kt")
        val slider = sheet.substringAfter("        Slider(\n").substringBefore("\n        )\n")
        assertTrue(slider, "valueRange = 1f..120f" in slider)
        assertTrue(slider, "contentDescription = sliderName" in slider)
        // The length as the line above the slider says it ("30 minutes").
        assertTrue(slider, "stateDescription = pluralString" in slider)
        assertTrue("val sliderName = stringResource(R.string.sleep_timer)" in sheet)
        assertTrue("val pluralString = pluralStringResource(R.plurals.minute, minutes, minutes)" in sheet)
    }
}
