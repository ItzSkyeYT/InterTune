/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The volume past 100% (#93, 7 Nov 2026): on a Galaxy M30s the music distorted as soon as the bar
 * passed its line, even with the phone's volume low. Every sample past -2.5 dBFS went through an
 * instant soft clip, and at 4x most of a mastered song is past it. A boost turns the music up and
 * keeps its shape: a limiter lowers the gain when a peak comes and gives it back slowly.
 */
class GainAudioProcessorTest {
    private val rate = 48_000

    private fun sine(freq: Double, amp: Double, seconds: Double, channels: Int = 2): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i -> (amp * sin(2 * PI * freq * (i / channels) / rate)).toFloat() }
    }

    private fun run(input: FloatArray, gain: Float, channels: Int = 2, encoding: Int = C.ENCODING_PCM_16BIT): FloatArray {
        val p = GainAudioProcessor()
        p.gain = gain
        p.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        p.flush()
        val bytes = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val out = ArrayList<Float>(input.size)
        var i = 0
        while (i < input.size) {
            val n = min(1024 * channels, input.size - i)
            val buf = ByteBuffer.allocateDirect(n * bytes).order(ByteOrder.nativeOrder())
            for (k in 0 until n) {
                if (bytes == 2) buf.putShort((input[i + k] * 32767f).roundToInt().toShort()) else buf.putFloat(input[i + k])
            }
            buf.flip()
            p.queueInput(buf)
            val o = p.output
            while (o.hasRemaining()) out += if (bytes == 2) o.short / 32767f else o.float
            i += n
        }
        return out.toFloatArray()
    }

    /** How far [out] is from a scaled copy of [x] over the samples [from] to [to], as a share of it. */
    private fun reshaped(x: FloatArray, out: FloatArray, from: Int, to: Int): Double {
        var xy = 0.0; var xx = 0.0
        for (i in from until to) { xy += x[i] * out[i]; xx += x[i] * x[i].toDouble() }
        val k = xy / xx
        var err = 0.0; var sig = 0.0
        for (i in from until to) { val e = out[i] - k * x[i]; err += e * e; sig += (k * x[i]) * (k * x[i]) }
        return sqrt(err / sig)
    }

    @Test
    fun `turned up 4x, a loud song comes out louder and keeps its shape (#93)`() {
        val x = sine(1000.0, 0.7, 1.0)
        val out = run(x, 4f)
        val from = (0.1 * rate).toInt() * 2
        assertTrue("reshaped by ${reshaped(x, out, from, x.size)}", reshaped(x, out, from, x.size) < 0.01)
        assertTrue("past full scale", out.all { abs(it) <= 0.9f })
        assertTrue("not turned up", out.drop(from).maxOf { abs(it) } > 0.8f)
    }

    @Test
    fun `a bass note keeps its shape too, the limiter holding through its cycles`() {
        val x = sine(40.0, 0.7, 1.5)
        val out = run(x, 4f)
        val from = (0.3 * rate).toInt() * 2
        assertTrue("reshaped by ${reshaped(x, out, from, x.size)}", reshaped(x, out, from, x.size) < 0.02)
    }

    @Test
    fun `after a loud part the boost comes back in full`() {
        val loud = sine(500.0, 0.7, 0.5)
        val quiet = sine(500.0, 0.05, 2.0)
        val out = run(loud + quiet, 4f)
        val tail = out.takeLast(rate / 2 * 2)
        assertEquals(0.2f, tail.maxOf { abs(it) }, 0.01f)
    }

    @Test
    fun `at or below 100% the samples pass bit for bit, and float output stays inside full scale`() {
        val x = sine(1000.0, 0.9, 0.2)
        for (g in listOf(1f, 0.5f)) {
            val shorts = x.map { (it * 32767f).roundToInt() / 32767f }.toFloatArray()
            assertArrayEquals(shorts, run(x, g), 0f)
        }
        assertTrue(run(sine(1000.0, 0.9, 0.5), 4f, encoding = C.ENCODING_PCM_FLOAT).all { abs(it) <= 0.9f })
    }
}
