/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The picture in the explanation of Fade between tracks shows what the setting does, and not a
 * crossfade, and it moves the way sound does: nothing jumps, nothing moves as one block, and the
 * loop has no seam.
 */
class ExplainPicturesTest {

    private val bars = 0 until FADE_BARS

    /** Every 240th of a second through one loop, and a little past its end. */
    private val moments = generateSequence(0f) { it + 1f / 240f }.takeWhile { it < FADE_LOOP + 0.5f }.toList()

    /** One frame of a screen that draws 60 a second, the slowest there is. */
    private val frame = 1f / 60f

    private fun loudest(song: Int, at: Float) = bars.maxOf { fadeLevel(song, it, at) }

    @Test
    fun `it opens on the left song sounding and the right one silent`() {
        for (bar in bars) {
            assertEquals(1f, fadeLevel(0, bar, 0f), 0f)
            assertEquals(0f, fadeLevel(1, bar, 0f), 0f)
        }
    }

    @Test
    fun `half way round it is the right song that sounds`() {
        for (bar in bars) {
            assertEquals(0f, fadeLevel(0, bar, FADE_PASS), 0f)
            assertEquals(1f, fadeLevel(1, bar, FADE_PASS), 0f)
        }
    }

    @Test
    fun `the two songs never sound at once`() {
        for (at in moments) {
            assertTrue("both sound at $at", minOf(loudest(0, at), loudest(1, at)) == 0f)
        }
    }

    @Test
    fun `there is a moment of nothing each time one hands over to the other`() {
        for (pass in 0..1) {
            val quiet = moments.count {
                it >= pass * FADE_PASS && it < (pass + 1) * FADE_PASS && loudest(0, it) == 0f && loudest(1, it) == 0f
            }
            assertTrue("only ${quiet / 240f} s of nothing in half $pass", quiet / 240f >= FADE_GAP - 0.02f)
        }
    }

    @Test
    fun `every bar stays between nothing and full`() {
        for (song in 0..1) for (bar in bars) for (at in moments) {
            assertTrue(fadeLevel(song, bar, at) in 0f..1f)
        }
    }

    @Test
    fun `no bar ever jumps, at the loop's seam least of all`() {
        for (song in 0..1) for (bar in bars) for (at in moments) {
            val step = abs(fadeLevel(song, bar, at + frame) - fadeLevel(song, bar, at))
            assertTrue("bar $bar of song $song jumps by $step at $at", step <= 0.04f)
        }
    }

    @Test
    fun `the loop comes back to where it began`() {
        for (song in 0..1) for (bar in bars) for (at in listOf(0f, 0.7f, 2.1f, 3.3f, 5.9f, 7.7f)) {
            assertEquals(fadeLevel(song, bar, at), fadeLevel(song, bar, at + FADE_LOOP), 0.001f)
        }
    }

    @Test
    fun `a song does not sink or rise as one block`() {
        // Part of the way down, its bars are at different heights.
        val falling = FADE_HOLD + (FADE_FALL + FADE_SWEEP) / 2f
        val levels = bars.map { fadeLevel(0, it, falling) }
        assertTrue(levels.max() - levels.min() > 0.15f)
        val rising = FADE_PASS - (FADE_FALL + FADE_SWEEP) / 2f
        val up = bars.map { fadeLevel(1, it, rising) }
        assertTrue(up.max() - up.min() > 0.15f)
    }

    @Test
    fun `the change travels from the song that is ending to the one that is starting`() {
        // Left to right while the left song hands over: its left bars are down first, and the
        // right song's left bars are up first. The other way round on the way back.
        for (at in moments.filter { it < FADE_PASS }) for (bar in 1 until FADE_BARS) {
            assertTrue(fadeLevel(0, bar - 1, at) <= fadeLevel(0, bar, at))
            assertTrue(fadeLevel(1, bar - 1, at) >= fadeLevel(1, bar, at))
        }
        for (at in moments.filter { it >= FADE_PASS && it < FADE_LOOP }) for (bar in 1 until FADE_BARS) {
            assertTrue(fadeLevel(1, bar - 1, at) >= fadeLevel(1, bar, at))
            assertTrue(fadeLevel(0, bar - 1, at) <= fadeLevel(0, bar, at))
        }
    }

    @Test
    fun `a bar that sounds is never still, never taller than its place, and never jerks`() {
        for (song in 0..1) for (bar in bars) {
            val shares = moments.map { fadeSway(song, bar, it) }
            assertTrue(shares.all { it > 0.4f && it <= 1f })
            assertTrue("bar $bar of song $song hardly moves", shares.max() - shares.min() > 0.2f)
            for (at in moments) {
                assertTrue(abs(fadeSway(song, bar, at + frame) - fadeSway(song, bar, at)) <= 0.05f)
            }
        }
    }

    @Test
    fun `neighbours sway together and the two songs do not sway alike`() {
        for (at in moments) for (bar in 1 until FADE_BARS) {
            assertTrue(abs(fadeSway(0, bar, at) - fadeSway(0, bar - 1, at)) < 0.35f)
        }
        assertTrue(moments.any { at -> bars.any { abs(fadeSway(0, it, at) - fadeSway(1, it, at)) > 0.2f } })
    }

    @Test
    fun `held still, it shows the fall, the silence and the rise from left to right`() {
        val left = bars.map { fadeStill(0, it) }
        val right = bars.map { fadeStill(1, it) }
        assertEquals(1f, left.first(), 0f)
        assertEquals(0f, left.last(), 0f)
        assertEquals(0f, right.first(), 0f)
        assertEquals(1f, right.last(), 0f)
        assertTrue(left.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(right.zipWithNext().all { (a, b) -> a <= b })
        for (song in listOf(left, right)) {
            assertTrue(song.count { it == 1f } >= 3)
            assertTrue(song.count { it > 0f && it < 1f } >= 3)
            assertTrue(song.count { it == 0f } >= 2)
        }
    }
}
