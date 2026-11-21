/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.ui.component.getNextInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The 0.13 walks, 21 Nov 2026: 4:00 PM picked under At at 15:45:14 became 16 minutes from then, and the dialog read
 * 4:01. A clock time is that time itself, to the second.
 */
class SleepClockTimeTest {

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `a quarter of the hour is that clock time at its first second`() {
        val now = LocalDateTime.of(2026, 11, 21, 15, 45, 14)
        val fourPm = getNextInterval(60, now)
        assertEquals(ms(LocalDateTime.of(2026, 11, 21, 16, 0, 0)), fourPm.atMs)
        assertEquals(14.77f, fourPm.minutes, 0.01f)
        // A quarter already gone this hour is the next hour's.
        assertEquals(ms(LocalDateTime.of(2026, 11, 21, 16, 15, 0)), getNextInterval(15, now).atMs)
        assertEquals(ms(LocalDateTime.of(2026, 11, 21, 16, 45, 0)), getNextInterval(45, now).atMs)
        // Later this hour: this hour's.
        assertEquals(ms(LocalDateTime.of(2026, 11, 21, 16, 30, 0)), getNextInterval(30, LocalDateTime.of(2026, 11, 21, 16, 10, 59)).atMs)
    }

    @Test
    fun `Start stops at the chip's time, and falls back to the minutes once it has gone by`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/component/SleepTimer.kt").readText()
        assertTrue("if (clockAt?.let(timer::startAt) != true) timer.start(sleepTimerValue.roundToInt())" in dialog)
        assertTrue("onAt(chip.atMs)" in dialog)
        val timer = File("src/main/java/com/dd3boh/outertune/playback/SleepTimer.kt").readText()
        val startAt = timer.substringAfter("fun startAt(atMs: Long): Boolean {").substringBefore("\n    }\n")
        assertTrue(startAt, "if (atMs <= now) return false" in startAt)
        assertTrue(startAt, "triggerTime = minOf(atMs, now + SleepTimerRules.MAX_AHEAD_MS)" in startAt)
    }
}
