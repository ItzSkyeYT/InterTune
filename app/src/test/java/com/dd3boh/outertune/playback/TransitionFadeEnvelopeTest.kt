/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import com.dd3boh.outertune.playback.TransitionFadeEnvelope.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The fade between songs: the curve, when there is one at all, and when to look at it next. */
class TransitionFadeEnvelopeTest {

    private val song = 240_000L
    private val fade = 6_000L
    private val step = 1f / TransitionFadeEnvelope.STEPS

    private fun factor(
        position: Long,
        duration: Long = song,
        fadeIn: Boolean = true,
        fadeOut: Boolean = true,
    ) = TransitionFadeEnvelope.factor(position, duration, fade, fadeIn, fadeOut)

    private fun nextCheck(
        position: Long,
        fadeIn: Boolean,
        fadeOut: Boolean,
        speed: Float = 1f,
        duration: Long = song,
    ) = TransitionFadeEnvelope.nextCheckMs(position, duration, fade, fadeIn, fadeOut, speed)

    @Test
    fun `the middle of a song is exactly full volume`() {
        for (position in listOf(fade, 30_000L, song / 2, song - fade)) {
            assertEquals(1f, factor(position), 0f)
        }
        // With neither end fading, nothing changes anywhere, the edges included.
        for (position in listOf(0L, 1_000L, song - 1_000L, song)) {
            assertEquals(1f, factor(position, fadeIn = false, fadeOut = false), 0f)
        }
    }

    @Test
    fun `each end that fades reaches silence at the edge, and only those ends`() {
        assertEquals(0f, factor(0L), 0f)
        assertEquals(0f, factor(song), 0f)
        assertEquals(1f, factor(0L, fadeIn = false), 0f)
        assertEquals(1f, factor(song, fadeOut = false), 0f)
    }

    @Test
    fun `the fades follow a quarter sine, not a straight line`() {
        // Halfway through a fade it is 3 dB down, where a straight line would already be at a half.
        val halfway = sqrt(0.5f)
        assertEquals(halfway, factor(fade / 2), step)
        assertEquals(halfway, factor(song - fade / 2), step)
        assertTrue(factor(fade / 2) > 0.6f)
        // A tenth of the way in is sin(pi / 20).
        assertEquals(0.156f, factor(fade / 10), step)
        // Rising the whole way through the fade in, falling the whole way through the fade out.
        val rising = (0L..fade step 250).map { factor(it) }
        assertEquals(rising.sorted(), rising)
        val falling = (song - fade..song step 250).map { factor(it) }
        assertEquals(falling.sortedDescending(), falling)
    }

    @Test
    fun `a fade and its mirror image add up to constant power`() {
        // What makes the curve equal power: in squared plus out squared is one all the way across.
        for (t in 0L..fade step 100) {
            val fadingIn = TransitionFadeEnvelope.quarterSine(t, fade)
            val fadingOut = TransitionFadeEnvelope.quarterSine(fade - t, fade)
            assertEquals(1f, fadingIn * fadingIn + fadingOut * fadingOut, 1e-5f)
        }
    }

    @Test
    fun `a song shorter than two fades still reaches full volume`() {
        // Eight seconds against six second fades: each end gets four, and the middle is full.
        val short = 8_000L
        assertEquals(4_000L, TransitionFadeEnvelope.lengthMs(short, fade))
        assertEquals(1f, factor(4_000L, duration = short), 0f)
        assertEquals(sqrt(0.5f), factor(2_000L, duration = short), step)
        assertEquals(sqrt(0.5f), factor(6_000L, duration = short), step)
        // Three seconds: a second and a half each way, and full at the middle.
        assertEquals(1f, factor(1_500L, duration = 3_000L), 0f)
        // A song long enough keeps the whole length it was given.
        assertEquals(fade, TransitionFadeEnvelope.lengthMs(song, fade))
    }

    @Test
    fun `a song of unknown length is not faded at all`() {
        for (position in listOf(0L, 500L, 5_000L)) {
            assertEquals(1f, factor(position, duration = C.TIME_UNSET), 0f)
            assertEquals(1f, factor(position, duration = 0L), 0f)
        }
        assertEquals(0L, TransitionFadeEnvelope.lengthMs(C.TIME_UNSET, fade))
        assertNull(nextCheck(0L, fadeIn = true, fadeOut = true, duration = C.TIME_UNSET))
    }

    @Test
    fun `two songs from one album play straight through`() {
        val a1 = Track("a1", "album-a")
        val a2 = Track("a2", "album-a")
        val b1 = Track("b1", "album-b")
        assertFalse(TransitionFadeEnvelope.fadesBetween(a1, a2))
        assertTrue(TransitionFadeEnvelope.fadesBetween(a2, b1))
        // Knowing nothing about the album is not the same album.
        assertTrue(TransitionFadeEnvelope.fadesBetween(Track("x", null), Track("y", null)))
        assertTrue(TransitionFadeEnvelope.fadesBetween(Track("x", ""), Track("y", "")))
        assertTrue(TransitionFadeEnvelope.fadesBetween(Track("x", null), a1))
        // Repeat one plays the same song again, and the end of the queue is not a change of song.
        assertFalse(TransitionFadeEnvelope.fadesBetween(b1, b1))
        assertFalse(TransitionFadeEnvelope.fadesBetween(b1, null))
        assertFalse(TransitionFadeEnvelope.fadesBetween(null, b1))
    }

    @Test
    fun `only a song played into from its start fades in`() {
        val a = Track("a", "album-a")
        val b = Track("b", "album-b")
        assertTrue(TransitionFadeEnvelope.fadesIn(a, b, startPositionMs = 0L))
        assertFalse(TransitionFadeEnvelope.fadesIn(a, Track("a2", "album-a"), startPositionMs = 0L))
        // Picked up from partway in, as a resume or a seek would be.
        assertFalse(TransitionFadeEnvelope.fadesIn(a, b, startPositionMs = 42_000L))
        assertFalse(TransitionFadeEnvelope.fadesIn(a, b, startPositionMs = C.TIME_UNSET))
    }

    @Test
    fun `a jump in position is worked out again from where it lands`() {
        // Deep in the fade out, then seeked back to the middle: full volume at once, nothing held.
        assertTrue(factor(song - 500L) < 0.2f)
        assertEquals(1f, factor(song / 2), 0f)
        // Seeked into the fade out: the value for the new position, not a ramp from the old one.
        assertEquals(sqrt(0.5f), factor(song - fade / 2), step)
        // A seek cancels a fade in, so a jump back to near the start plays at full volume.
        assertEquals(1f, factor(1_000L, fadeIn = false), 0f)
        // The wait is worked out again too: a tick inside a fade, the time to the fade out outside.
        assertEquals(TransitionFadeEnvelope.TICK_MS, nextCheck(song - 1_000L, fadeIn = false, fadeOut = true))
        assertEquals(song - fade - 60_000L, nextCheck(60_000L, fadeIn = false, fadeOut = true))
    }

    @Test
    fun `the wait for the fade out follows the playback speed, and nothing waits for nothing`() {
        val atNormal = nextCheck(60_000L, fadeIn = false, fadeOut = true)!!
        val atDouble = nextCheck(60_000L, fadeIn = false, fadeOut = true, speed = 2f)!!
        assertEquals(atNormal / 2, atDouble)
        // A fade in ticks until it is over, and then there is one wait for the fade out.
        assertEquals(TransitionFadeEnvelope.TICK_MS, nextCheck(0L, fadeIn = true, fadeOut = true))
        assertEquals(song - 2 * fade, nextCheck(fade, fadeIn = true, fadeOut = true))
        // Nothing left to fade, so nothing to wake up for until the player says something changed.
        assertNull(nextCheck(fade, fadeIn = true, fadeOut = false))
        assertNull(nextCheck(0L, fadeIn = false, fadeOut = false))
    }

    @Test
    fun `the factor moves in 256ths, so a flat stretch sets nothing`() {
        for (position in 0L..fade step 37) {
            val value = factor(position)
            assertEquals(value, Math.round(value * 256) / 256f, 0f)
        }
        // The top of the fade out is flat enough to stay exactly 1 for its first few percent.
        assertEquals(1f, factor(song - fade + 150L), 0f)
    }

    @Test
    fun `the stored length is kept to what the settings offer`() {
        assertTrue(TransitionFadeEnvelope.DEFAULT_SECONDS in TransitionFadeEnvelope.SECONDS_CHOICES)
        assertEquals(6_000L, TransitionFadeEnvelope.fadeMsFor(6))
        assertEquals(2_000L, TransitionFadeEnvelope.fadeMsFor(0))
        assertEquals(12_000L, TransitionFadeEnvelope.fadeMsFor(99))
    }
}
