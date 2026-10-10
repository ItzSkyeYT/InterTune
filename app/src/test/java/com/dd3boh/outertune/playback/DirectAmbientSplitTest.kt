/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** What the split calls the sound itself and what it calls the air round it. */
class DirectAmbientSplitTest {

    private val late = DirectAmbientSplit.DELAY

    private class Parts(n: Int) {
        val directLeft = FloatArray(n)
        val directRight = FloatArray(n)
        val ambientLeft = FloatArray(n)
        val ambientRight = FloatArray(n)
    }

    private fun split(left: FloatArray, right: FloatArray, with: DirectAmbientSplit = DirectAmbientSplit()): Parts {
        val out = Parts(left.size)
        for (n in left.indices) {
            with.process(left[n], right[n])
            out.directLeft[n] = with.directLeft
            out.directRight[n] = with.directRight
            out.ambientLeft[n] = with.ambientLeft
            out.ambientRight[n] = with.ambientRight
        }
        return out
    }

    private fun noise(seed: Long, n: Int, level: Float = 0.25f) = Random(seed).let { r -> FloatArray(n) { (r.nextGaussian() * level).toFloat() } }

    private fun tone(hz: Double, n: Int, level: Float = 0.3f) = FloatArray(n) { (level * sin(2 * PI * hz * it / 48000)).toFloat() }

    /** The energy once the estimates have had half a second to settle. */
    private fun settled(a: FloatArray) = (24000 until a.size).sumOf { a[it].toDouble() * a[it] }

    private fun decibels(part: Double, whole: Double) = 10 * log10(part / whole)

    private val long = 96000

    @Test
    fun `there and back is where it started`() {
        val re = noise(1, DirectAmbientSplit.SIZE)
        val im = noise(2, DirectAmbientSplit.SIZE)
        val a = re.copyOf()
        val b = im.copyOf()
        DirectAmbientSplit.fft(a, b, false)
        DirectAmbientSplit.fft(a, b, true)
        for (n in re.indices) {
            assertEquals(re[n], a[n], 1e-5f)
            assertEquals(im[n], b[n], 1e-5f)
        }
    }

    @Test
    fun `a wave that fits the frame five times is at five and nowhere else`() {
        val size = DirectAmbientSplit.SIZE
        val re = FloatArray(size) { cos(2 * PI * 5 * it / size).toFloat() }
        val im = FloatArray(size)
        DirectAmbientSplit.fft(re, im, false)
        for (k in 0 until size) {
            val strength = sqrt(re[k] * re[k] + im[k] * im[k])
            assertEquals("at $k", if (k == 5 || k == size - 5) size / 2f else 0f, strength, 1e-2f)
        }
    }

    @Test
    fun `the two parts add up to the recording, one frame late`() {
        val left = noise(3, 20000).also { tone(440.0, 20000).forEachIndexed { i, v -> it[i] += v } }
        val right = noise(4, 20000).also { tone(440.0, 20000).forEachIndexed { i, v -> it[i] += v } }
        val out = split(left, right)
        for (n in 0 until 20000) {
            val was = if (n >= late) left[n - late] else 0f
            val wasRight = if (n >= late) right[n - late] else 0f
            assertEquals("left at $n", was, out.directLeft[n] + out.ambientLeft[n], 1e-6f)
            assertEquals("right at $n", wasRight, out.directRight[n] + out.ambientRight[n], 1e-6f)
        }
    }

    @Test
    fun `a sound in the middle is all direct`() {
        val both = noise(5, long)
        val out = split(both, both)
        assertTrue(decibels(settled(out.ambientLeft), settled(both)) < -40.0)
        assertTrue(decibels(settled(out.ambientRight), settled(both)) < -40.0)
    }

    @Test
    fun `a sound panned part of the way is direct too`() {
        val sound = noise(6, long)
        val out = split(FloatArray(long) { sound[it] * 0.9f }, FloatArray(long) { sound[it] * 0.3f })
        assertTrue("left ${decibels(settled(out.ambientLeft), settled(sound))}", decibels(settled(out.ambientLeft), settled(sound)) < -40.0)
        assertTrue("right ${decibels(settled(out.ambientRight), settled(sound))}", decibels(settled(out.ambientRight), settled(sound)) < -40.0)
    }

    @Test
    fun `and so is a sound that is in one channel only`() {
        // By what the two channels share alone, this would be all ambient: they share nothing.
        val sound = noise(7, long)
        val out = split(sound, FloatArray(long))
        assertTrue("left ${decibels(settled(out.ambientLeft), settled(sound))}", decibels(settled(out.ambientLeft), settled(sound)) < -40.0)
        assertEquals(0.0, settled(out.ambientRight), 0.0)
    }

    @Test
    fun `what the two channels do not share is ambient`() {
        val left = noise(8, long)
        val right = noise(9, long)
        val out = split(left, right)
        val ofLeft = decibels(settled(out.ambientLeft), settled(left))
        val ofRight = decibels(settled(out.ambientRight), settled(right))
        assertTrue("left $ofLeft dB, right $ofRight dB", ofLeft > -1.5 && ofRight > -1.5)
        // And it is that sound, in time with it, not something like it: the best match is at no lag at all.
        fun match(lag: Int) = (24000 until long - 8).sumOf { out.ambientLeft[it + lag].toDouble() * left[it - late] }
        val best = (-4..4).maxByOrNull { match(it) }
        assertEquals(0, best)
        assertTrue(match(0) / sqrt(settled(out.ambientLeft) * settled(left)) > 0.9)
    }

    @Test
    fun `a voice stays direct while the hall round it goes to the ambience`() {
        val voice = tone(440.0, long)
        val hallLeft = noise(10, long, 0.05f)
        val hallRight = noise(11, long, 0.05f)
        val out = split(FloatArray(long) { voice[it] + hallLeft[it] }, FloatArray(long) { voice[it] + hallRight[it] })
        // How much of a 440 Hz wave is in a signal, over the settled part.
        fun at440(a: FloatArray): Double {
            var re = 0.0
            var im = 0.0
            for (n in 24000 until long) { re += a[n] * cos(2 * PI * 440 * n / 48000); im += a[n] * sin(2 * PI * 440 * n / 48000) }
            return re * re + im * im
        }
        val kept = decibels(at440(out.ambientLeft), at440(out.directLeft))
        assertTrue("the voice in the ambience: $kept dB beside the direct", kept < -25.0)
        // The hall is nearly all of what the ambience holds, and most of the hall is there.
        val ofHall = decibels(settled(out.ambientLeft), settled(hallLeft))
        assertTrue("the ambience beside the hall: $ofHall dB", abs(ofHall) < 3.0)
    }

    @Test
    fun `nothing in, nothing out, and no number that is not one`() {
        val out = split(FloatArray(5000), FloatArray(5000))
        for (n in 0 until 5000) {
            assertEquals(0f, out.directLeft[n], 0f)
            assertEquals(0f, out.ambientLeft[n], 0f)
            assertEquals(0f, out.directRight[n], 0f)
            assertEquals(0f, out.ambientRight[n], 0f)
        }
    }

    @Test
    fun `a seek leaves nothing of where it was`() {
        val with = DirectAmbientSplit()
        split(noise(12, 5000), noise(13, 5000), with)
        with.reset()
        val out = split(FloatArray(4000), FloatArray(4000), with)
        assertEquals(0.0, out.directLeft.sumOf { abs(it).toDouble() } + out.ambientLeft.sumOf { abs(it).toDouble() }, 0.0)
        assertEquals(0.0, out.directRight.sumOf { abs(it).toDouble() } + out.ambientRight.sumOf { abs(it).toDouble() }, 0.0)
    }

    @Test
    fun `it goes on past the point where a count of samples wraps`() {
        // Twelve hours of music is more samples than an Int holds. Nothing here may depend on the count itself.
        val with = DirectAmbientSplit()
        val field = DirectAmbientSplit::class.java.getDeclaredField("at").apply { isAccessible = true }
        field.setInt(with, Int.MAX_VALUE - 3000)
        val left = noise(14, 12000)
        val right = noise(15, 12000)
        val out = split(left, right, with)
        for (n in late until 12000) assertEquals("left at $n", left[n - late], out.directLeft[n] + out.ambientLeft[n], 1e-6f)
        assertTrue(out.ambientLeft.drop(6000).sumOf { it.toDouble() * it } > 0.0)
    }
}
