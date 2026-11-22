/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A file on the phone starts in the Recap at its loudest stretch (RecapChorus, his pick for listeners
 * of their own files, 22 Nov 2026), as a YouTube song starts at its heat map's peak.
 */
class RecapChorusTest {
    private val step = RecapChorus.STEP_MS

    /** A song of [seconds] with [quiet] everywhere and [loudness] from [fromS] for [forS] seconds. */
    private fun song(seconds: Int, quiet: Float = 0.1f, loudness: Float = 0.8f, fromS: Int = -1, forS: Int = 0): FloatArray =
        FloatArray(seconds / 2) { i -> if (i * 2 >= fromS && i * 2 < fromS + forS) loudness else quiet }

    @Test
    fun `the loudest run as long as the piece is where it starts`() {
        assertEquals(80_000L, RecapChorus.startMs(song(240, fromS = 80, forS = 20), step, 10_000L, 240_000L))
        assertEquals("a piece longer than the loud part starts where it begins", 80_000L, RecapChorus.startMs(song(240, fromS = 80, forS = 6), step, 10_000L, 240_000L))
    }

    @Test
    fun `not in the opening nor the fade at the end`() {
        val loudStart = song(240, fromS = 0, forS = 12)
        val at = RecapChorus.startMs(loudStart.also { it[60] = 0.5f; it[61] = 0.5f }, step, 10_000L, 240_000L)
        assertTrue("$at", at != null && at >= (240_000L * RecapChorus.SKIP_START).toLong())
        val loudEnd = song(240, fromS = 232, forS = 8).also { it[50] = 0.5f; it[51] = 0.5f; it[52] = 0.5f }
        assertTrue(RecapChorus.startMs(loudEnd, step, 10_000L, 240_000L)!! + 10_000L <= (240_000L * (1 - RecapChorus.SKIP_END)).toLong())
    }

    @Test
    fun `a flat song, a silent one or one shorter than the piece has none`() {
        assertNull(RecapChorus.startMs(FloatArray(120) { 0.5f }, step, 10_000L, 240_000L))
        assertNull(RecapChorus.startMs(FloatArray(120), step, 10_000L, 240_000L))
        assertNull(RecapChorus.startMs(song(8, fromS = 2, forS = 2), step, 10_000L, 8_000L))
        assertNull(RecapChorus.startMs(FloatArray(2) { 1f }, step, 10_000L, 240_000L))
    }

    @Test
    fun `the Recap's player starts a file there, a third of the way in when it is not known in time`() {
        val player = File("src/main/java/com/dd3boh/outertune/recap/RecapPlayer.kt").readText()
        assertTrue("RecapChorus.startMs(envelope, RecapChorus.STEP_MS, piece.lengthMs, durationMs)" in player)
        assertTrue("else piece.fromMs to \"a local file: a third of the way in\"" in player)
        assertTrue("loud.values.forEach { it.cancel() }" in player)
    }
}
