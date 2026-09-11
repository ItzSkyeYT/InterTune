/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class EngineMathTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    // Engagement

    @Test
    fun `a natural end is a full positive whatever the ratio`() {
        assertEquals(1f, Engagement.weight(playedMs = 7_000, ratio = 0.03f, endReason = EndReason.ENDED))
    }

    @Test
    fun `under thirty seconds is not a positive`() {
        assertEquals(0f, Engagement.weight(playedMs = 8_000, ratio = 0.04f, endReason = EndReason.SKIPPED))
        assertEquals(0f, Engagement.weight(playedMs = 29_999, ratio = 0.5f, endReason = EndReason.REPLACED))
    }

    @Test
    fun `the ramp reaches full weight at eighty percent`() {
        assertEquals(0.25f, Engagement.weight(60_000, 0.2f, EndReason.SKIPPED), 1e-6f)
        assertEquals(1f, Engagement.weight(60_000, 0.8f, EndReason.SKIPPED), 1e-6f)
        assertEquals(1f, Engagement.weight(60_000, 0.95f, EndReason.STOPPED), 1e-6f)
    }

    @Test
    fun `the milestone ladder matches Flow`() {
        val m = Engagement.Curve.MILESTONES
        assertEquals(0f, Engagement.weight(60_000, 0.10f, EndReason.SKIPPED, m))
        assertEquals(0.35f, Engagement.weight(60_000, 0.15f, EndReason.SKIPPED, m))
        assertEquals(0.70f, Engagement.weight(60_000, 0.50f, EndReason.SKIPPED, m))
        assertEquals(1.0f, Engagement.weight(60_000, 0.90f, EndReason.SKIPPED, m))
    }

    @Test
    fun `an unknown duration is half a positive once past the floor`() {
        assertEquals(0.5f, Engagement.weight(90_000, -1f, EndReason.SKIPPED))
    }

    @Test
    fun `only a real skip counts as one`() {
        assertTrue(Engagement.isMeaningfulSkip(59_000, 0.20f, EndReason.SKIPPED))
        assertFalse("too short to be a verdict", Engagement.isMeaningfulSkip(8_000, 0.04f, EndReason.SKIPPED))
        assertFalse("leaving in the fade-out", Engagement.isMeaningfulSkip(200_000, 0.95f, EndReason.SKIPPED))
        assertFalse("replaced is not a skip", Engagement.isMeaningfulSkip(59_000, 0.20f, EndReason.REPLACED))
        assertFalse("ended is not a skip", Engagement.isMeaningfulSkip(200_000, 1f, EndReason.ENDED))
    }

    // Activation

    @Test
    fun `a full listen just now scores zero and a day later scores minus ln 5`() {
        assertEquals(0.0, Activation.of(listOf(0L to 1f)), 1e-9)
        assertEquals(-ln(5.0), Activation.of(listOf(day to 1f)), 1e-9)   // (24 + 1)^-0.5 = 1/5
    }

    @Test
    fun `more listens score higher and recent ones score higher`() {
        val one = Activation.of(listOf(day to 1f))
        val two = Activation.of(listOf(day to 1f, 2 * day to 1f))
        assertTrue(two > one)
        assertTrue(Activation.of(listOf(hour to 1f)) > Activation.of(listOf(7 * day to 1f)))
    }

    @Test
    fun `a year-old favourite is still present, an exponential half-life would have erased it`() {
        val yearOld = Activation.of(listOf(365 * day to 1f))
        assertTrue(yearOld > Activation.NONE)
        // (8760 + 1)^-0.5 is about 0.0107: small, not gone
        assertEquals(ln(1 / kotlin.math.sqrt(8761.0)), yearOld, 1e-9)
    }

    @Test
    fun `engagement scales each term`() {
        assertEquals(ln(0.5), Activation.of(listOf(0L to 0.5f)), 1e-6)
        assertEquals(Activation.NONE, Activation.of(listOf(0L to 0f)), 0.0)
    }

    @Test
    fun `a play from the future is treated as now, never as a negative age`() {
        // The legacy event table stores the wall clock as UTC, so in Paris every play sits two
        // hours ahead of the clock. (-2h + 1h)^-0.5 would be a complex number.
        assertEquals(Activation.of(listOf(0L to 1f)), Activation.of(listOf(-2 * hour to 1f)), 0.0)
        assertFalse(Activation.of(listOf(-2 * hour to 1f)).isNaN())
    }

    @Test
    fun `nothing heard is the floor`() {
        assertEquals(Activation.NONE, Activation.of(emptyList()), 0.0)
    }
}
