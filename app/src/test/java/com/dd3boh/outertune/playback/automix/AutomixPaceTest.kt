/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lead a change far from the end needs, worked out from how fast this phone holds an end,
 * skips the next song to its entry and works a change out: the Pixel 5's numbers of 6 Nov as the
 * first guess, slower measurements taken at once, faster ones bit by bit, kept across a restart,
 * and a change that would need more than the end can be held refused early, not late.
 */
class AutomixPaceTest {

    // The Pixel 5's change of 6 Nov (run5): 105.8 s held in 9.4 s, 30.5 s skipped in 1711 ms,
    // a 19.2 s change rendered in 7067 ms, 10044 ms of work in all after the end was held.
    private val pixel = AutomixPace()
        .withHold(105.8, 9_400)
        .withSkip(30.5, 1_711)
        .withWork(tailSeconds = 105.8, analyseMs = 10_044 - 7_067, spanSeconds = 19.2, renderMs = 7_067)

    @Test
    fun `with no history it guesses as slow as the Pixel 5`() {
        val first = AutomixPace()
        assertTrue(first.holdSpeed <= 105.8 / 9.4)
        assertTrue(first.skipSpeed <= 30.5 / 1.711)
        assertTrue(first.renderMsPerS >= 7_067 / 19.2)
        assertTrue(first.analyseMsPerS >= (10_044 - 7_067) / 105.8)
    }

    @Test
    fun `the Pixel's trial needs more than the end can be held, and says so`() {
        // The trial's box: its first change 88.6 s before A's end, 19.2 s long, B in from 30.5 s.
        val need = pixel.armFromMs(changeLeftMs = 88_600, spanMs = 19_200, entrySeconds = 30.5)
        assertTrue("$need", need > AutomixSchedule.ARM_FROM_LEFT_MAX_MS)
        // What failed on the phone: held from 105.8 s, the plan 1.5 s late. The lead has to cover
        // the hold, the work and the stage's second ahead.
        val hold = 105.8 / pixel.holdSpeed
        val work = 105.8 * pixel.analyseMsPerS / 1000 + 19.2 * pixel.renderMsPerS / 1000
        assertTrue(need / 1000.0 - 88.6 >= hold + work + 1.0)
        // Its line 65 s before the end instead fits.
        val nearer = pixel.armFromMs(changeLeftMs = 83_600, spanMs = 19_200, entrySeconds = 30.5)
        assertTrue("$nearer", nearer <= AutomixSchedule.ARM_FROM_LEFT_MAX_MS)
        assertEquals(String.format(java.util.Locale.ROOT, "needs %.1f s, the end can be held 108.0 s", need / 1000.0), AutomixPace.tooFarWords(need))
    }

    @Test
    fun `on the emulator's pace the same trial fits with time to spare`() {
        // The emulator, run11: 106.1 s held in 1.6 s, 30 s skipped in 486 ms, the change rendered in 2475 ms.
        var emulator = AutomixPace()
        repeat(20) {
            emulator = emulator.withHold(106.1, 1_600).withSkip(30.0, 486)
                .withWork(tailSeconds = 106.1, analyseMs = 1_000, spanSeconds = 19.2, renderMs = 2_475)
        }
        val need = emulator.armFromMs(changeLeftMs = 88_600, spanMs = 19_200, entrySeconds = 30.0)
        assertTrue("$need", need in 88_600L..100_000L)
    }

    @Test
    fun `a slower measurement counts at once, a faster one bit by bit`() {
        val slow = pixel.withHold(100.0, 20_000)
        assertEquals(5.0, slow.holdSpeed, 1e-9)
        val back = slow.withHold(100.0, 5_000)
        assertTrue(back.holdSpeed > 5.0 && back.holdSpeed < 20.0)
        val render = pixel.withWork(100.0, 1_000, 10.0, 10_000)
        assertEquals(1_000.0, render.renderMsPerS, 1e-9)
        // Too short to say anything: not counted.
        assertEquals(pixel, pixel.withHold(5.0, 100))
        assertEquals(pixel, pixel.withSkip(0.5, 10))
    }

    @Test
    fun `the skip counts where it is longer than the work`() {
        val slowSkip = pixel.withSkip(60.0, 30_000)
        val withEntry = slowSkip.armFromMs(changeLeftMs = 50_000, spanMs = 5_000, entrySeconds = 60.0)
        val without = slowSkip.armFromMs(changeLeftMs = 50_000, spanMs = 5_000, entrySeconds = 0.0)
        assertTrue("$withEntry $without", withEntry > without)
    }

    @Test
    fun `it is kept across a restart`() {
        val text = pixel.encode()
        assertEquals(pixel, AutomixPace.decode(text))
        assertNull(AutomixPace.decode("holdSpeed=fast"))
        assertNull(AutomixPace.decode(""))
    }

    @Test
    fun `Auto's own changes are held as before`() {
        // Its window is the schedule's, from 40 s left: nothing here moves it.
        assertEquals(40_000L, AutomixSchedule.ARM_FROM_LEFT_MS)
        val controller = java.io.File("src/main/java/com/dd3boh/outertune/playback/automix/AutomixController.kt").readText()
        // The pace sets only a saved change's (or the trial's) arm point, through the lead the
        // editor works out too.
        assertEquals(1, Regex("""AutomixSchedule\.savedLead\(""").findAll(controller).count())
        assertFalse(controller.contains("armFromMs("))
    }

    @Test
    fun `the furthest a change can start before the end is where its lead just fits`() {
        // His Pixel 5's kept pace of 7 Nov: an 8-bar change at 128 BPM (15.4 s with the pad),
        // the next song in from 30 s, fits to about 84 s before the end.
        val kept = AutomixPace(holdSpeed = 11.06, skipSpeed = 17.19, analyseMsPerS = 35.5, renderMsPerS = 417.0)
        val reach = kept.reachMs(spanMs = 15_400, entrySeconds = 30.0)
        assertTrue("$reach", reach in 83_000L..85_000L)
        for ((span, entry) in listOf(15_400L to 30.0, 15_400L to 0.0, 45_000L to 0.0, 1_000L to 90.0, 0L to 0.0)) {
            val r = kept.reachMs(span, entry)
            assertTrue("$span $entry: $r", kept.armFromMs(r, span, entry) <= AutomixSchedule.ARM_FROM_LEFT_MAX_MS)
            assertTrue("$span $entry: $r", kept.armFromMs(r + 1, span, entry) > AutomixSchedule.ARM_FROM_LEFT_MAX_MS)
        }
        // A longer change, or a slower phone, starts nearer the end.
        assertTrue(kept.reachMs(25_000, 30.0) < reach)
        assertTrue(kept.withHold(60.0, 20_000).reachMs(15_400, 30.0) < reach)
        // A phone that cannot hold anything in time: nothing fits.
        assertEquals(-1L, AutomixPace(holdSpeed = 1.0).reachMs(15_400, 0.0))
    }

    @Test
    fun `the editor reads the pace playback keeps`() {
        val dir = kotlin.io.path.createTempDirectory("pace").toFile()
        try {
            assertNull(AutomixPace.read(dir))
            AutomixPace.write(dir, pixel)
            assertEquals(pixel, AutomixPace.read(dir))
            assertEquals(pixel.encode(), java.io.File(dir, "mix-pace.txt").readText())
            java.io.File(dir, "mix-pace.txt").writeText("holdSpeed=fast")
            assertNull(AutomixPace.read(dir))
        } finally {
            dir.deleteRecursively()
        }
        val service = java.io.File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(service.contains("AutomixPace.read(filesDir)"))
        assertTrue(service.contains("AutomixPace.write(filesDir, "))
    }
}
