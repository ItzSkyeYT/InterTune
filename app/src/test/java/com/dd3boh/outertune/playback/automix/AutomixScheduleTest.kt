/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.playback.automix.AutomixSchedule.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When AutoMix reads the next song, holds the end and gives up, and the checks on what it gets back. */
class AutomixScheduleTest {

    @Test
    fun `the opening is read two minutes out, the end held between forty and twenty five seconds, and later is too late`() {
        assertEquals(Step.WAIT, AutomixSchedule.due(120_001))
        assertEquals(Step.READ, AutomixSchedule.due(120_000))
        assertEquals(Step.READ, AutomixSchedule.due(40_001))
        assertEquals(Step.ARM, AutomixSchedule.due(40_000))
        assertEquals(Step.ARM, AutomixSchedule.due(25_000))
        assertEquals(Step.TOO_LATE, AutomixSchedule.due(24_999))
        // A song shorter than two minutes has its next song read at once.
        assertEquals(Step.READ, AutomixSchedule.due(90_000))
    }

    @Test
    fun `a saved change that starts further from the end has its end held earlier, as far as the ring allows`() {
        // Wake Me Up into After Hours: 8 bars at 109 BPM and 4 beats of ramp, ending 9 s before the end.
        val changeLeft = 9_000L + 17_615L + 2_162L
        val from = AutomixSchedule.armFromFor(changeLeft)
        assertEquals(changeLeft + AutomixSchedule.SAVED_LEAD_MS, from)
        assertEquals(Step.ARM, AutomixSchedule.due(from, from))
        assertEquals(Step.READ, AutomixSchedule.due(from + 1, from))
        assertEquals(500L, AutomixSchedule.nextLookMs(from - 100, 1f, from))
        // A change close to the end is held as Auto's are, and one too far from it no earlier than the ring can hold.
        assertEquals(AutomixSchedule.ARM_FROM_LEFT_MS, AutomixSchedule.armFromFor(10_000))
        assertEquals(AutomixSchedule.ARM_FROM_LEFT_MAX_MS, AutomixSchedule.armFromFor(60_000))
        assertTrue(AutomixSchedule.ARM_FROM_LEFT_MAX_MS < AutomixSchedule.CAPACITY_SECONDS * 1000L)
    }

    @Test
    fun `it looks again when the next step is due, often in the window, and not at all after it`() {
        assertEquals(60_000L, AutomixSchedule.nextLookMs(240_000, 1f))
        assertEquals(30_000L, AutomixSchedule.nextLookMs(150_000, 1f))
        assertEquals(15_000L, AutomixSchedule.nextLookMs(150_000, 2f))
        assertEquals(20_000L, AutomixSchedule.nextLookMs(60_000, 1f))
        assertEquals(500L, AutomixSchedule.nextLookMs(40_100, 1f))
        assertEquals(500L, AutomixSchedule.nextLookMs(30_000, 1f))
        assertNull(AutomixSchedule.nextLookMs(20_000, 1f))
    }

    @Test
    fun `holding the end waits for the song to be loaded to its end and the next one for five seconds`() {
        // 40 s left of a 200 s song, loaded to its end, and 6 s of the next one.
        assertTrue(AutomixSchedule.loaded(200_000, 160_000, 200_000, 46_000))
        assertFalse("the next song only 3 s in", AutomixSchedule.loaded(200_000, 160_000, 200_000, 43_000))
        assertFalse("this song not loaded to its end", AutomixSchedule.loaded(200_000, 160_000, 190_000, 46_000))
        assertFalse("no length", AutomixSchedule.loaded(0, 0, 0, 0))
    }

    @Test
    fun `a held end is the end of the song only when it is as long as what was left`() {
        assertTrue(AutomixSchedule.tailComplete(38_600, 38_400))
        assertTrue(AutomixSchedule.tailComplete(37_000, 38_400))
        assertFalse("a stream configured again in the middle of the song", AutomixSchedule.tailComplete(6_000, 38_400))
        assertFalse(AutomixSchedule.tailComplete(41_000, 38_400))
    }

    @Test
    fun `a plan may change nothing before what will be out by the time it is in, with a second to spare`() {
        assertEquals(44_100L * 10 + 44_100 * 5, AutomixSchedule.earliestChange(44_100L * 10, 44_100, 4_000))
        assertEquals(4_000L, AutomixSchedule.workAllowanceMs(null))
        assertEquals(2_000L, AutomixSchedule.workAllowanceMs(300))
        assertEquals(3_000L, AutomixSchedule.workAllowanceMs(2_000))
        assertEquals(8_000L, AutomixSchedule.workAllowanceMs(60_000))
    }

    @Test
    fun `the held end has room for the whole window and what follows it`() {
        assertEquals(48 * 44_100, AutomixSchedule.capacityFrames(44_100))
        assertTrue(AutomixSchedule.capacityFrames(48_000) >= (AutomixSchedule.ARM_FROM_LEFT_MS / 1000 + 5) * 48_000)
    }
}
