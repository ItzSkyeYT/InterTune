/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The equalizer measured, band by band: an audio stage here has shipped doing nothing, louder,
 * or retuning the music, and only measuring catches those.
 */
class EqualizerAudioProcessorTest {

    private val rate = 48_000
    private val eq = EqualizerAudioProcessor.Eq

    private fun gains(vararg pairs: Pair<Int, Float>): List<Float> =
        List(EqualizerAudioProcessor.BANDS.size) { i -> pairs.firstOrNull { it.first == i }?.second ?: 0f }

    private fun processor(gains: List<Float>, encoding: Int = C.ENCODING_PCM_FLOAT) = EqualizerAudioProcessor().apply {
        settings = EqualizerAudioProcessor.Settings(true, gains)
        configure(AudioProcessor.AudioFormat(rate, 2, encoding))
        flush()
    }

    /** A stereo sine at [hz] and [amplitude], through [p] a buffer at a time, as float samples (left channel). */
    private fun through(p: EqualizerAudioProcessor, hz: Double, amplitude: Double = 0.5, seconds: Double = 1.0): Pair<DoubleArray, DoubleArray> {
        val frames = (rate * seconds).toInt()
        val input = DoubleArray(frames) { amplitude * sin(2 * PI * hz * it / rate) }
        val out = DoubleArray(frames)
        var at = 0
        while (at < frames) {
            val n = minOf(1024, frames - at)
            val buffer = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until n) { buffer.putFloat(input[at + i].toFloat()); buffer.putFloat(input[at + i].toFloat()) }
            buffer.flip()
            p.queueInput(buffer)
            val o = p.output
            for (i in 0 until n) { out[at + i] = o.getFloat().toDouble(); o.getFloat() }
            at += n
        }
        return input to out
    }

    /** Output against input, in dB, over the second half once the filters have settled. */
    private fun gainDb(input: DoubleArray, output: DoubleArray): Double {
        fun rms(a: DoubleArray) = sqrt(a.drop(a.size / 2).sumOf { it * it } / (a.size / 2))
        return 20 * log10(rms(output) / rms(input))
    }

    @Test
    fun `flat or off, every sample passes untouched`() {
        for (settings in listOf(EqualizerAudioProcessor.Settings.OFF, EqualizerAudioProcessor.Settings(true, gains()),
            EqualizerAudioProcessor.Settings(false, gains(3 to 9f)))) {
            val p = EqualizerAudioProcessor().apply { this.settings = settings; configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); flush() }
            val bytes = ByteArray(4000) { (it * 37 % 251).toByte() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buffer.flip()
            p.queueInput(buffer)
            val out = ByteArray(bytes.size).also { p.output.get(it) }
            assertArrayEquals("$settings", bytes, out)
        }
    }

    @Test
    fun `a band raised by 6 dB is raised by 6 dB at its centre and hardly at all two octaves off`() {
        for ((band, hz) in EqualizerAudioProcessor.BANDS.withIndex()) {
            val filters = eq.filters(gains(band to 6f), rate.toDouble())
            assertEquals("centre of $hz", 6.0, eq.responseDb(filters, hz, rate.toDouble()), 0.1)
            if (hz * 4 < 20_000) assertTrue("two octaves above $hz", eq.responseDb(filters, hz * 4, rate.toDouble()) < 1.0)
            if (hz / 4 > 20) assertTrue("two octaves below $hz", eq.responseDb(filters, hz / 4, rate.toDouble()) < 1.0)
        }
    }

    @Test
    fun `a boost turns the rest down instead of making anything louder`() {
        val boosted = gains(5 to 6f) // 1 kHz
        val (inAt1k, outAt1k) = through(processor(boosted), 1_000.0)
        val (inAt62, outAt62) = through(processor(boosted), 62.0)
        val at1k = gainDb(inAt1k, outAt1k)
        val at62 = gainDb(inAt62, outAt62)
        assertEquals("the raised band comes out at the level it went in", 0.0, at1k, 0.3)
        assertEquals("the rest is 6 dB under it", -6.0, at62, 0.5)
        assertEquals("the difference the listener asked for", 6.0, at1k - at62, 0.5)
    }

    @Test
    fun `a cut needs no headroom`() {
        val (input, output) = through(processor(gains(2 to -6f)), 125.0)
        assertEquals(-6.0, gainDb(input, output), 0.3)
        val (input2, output2) = through(processor(gains(2 to -6f)), 4_000.0)
        assertEquals(0.0, gainDb(input2, output2), 0.3)
    }

    @Test
    fun `neighbours raised together are kept under full scale where they overlap`() {
        val both = gains(0 to 12f, 1 to 12f, 2 to 12f)
        val filters = eq.filters(both, rate.toDouble())
        assertTrue("the overlap is higher than either band", eq.headroomDb(filters, rate.toDouble()) > 12.0)
        for (hz in listOf(31.0, 45.0, 62.0, 90.0, 125.0)) {
            val (input, output) = through(processor(both), hz, amplitude = 0.9)
            val peakIn = input.maxOf { abs(it) }
            val peakOut = output.drop(output.size / 2).maxOf { abs(it) }
            assertTrue("$hz Hz: $peakOut out for $peakIn in", peakOut <= peakIn + 0.01)
        }
    }

    @Test
    fun `16 bit is equalized as float is`() {
        val p = processor(gains(5 to -6f), C.ENCODING_PCM_16BIT)
        val frames = rate
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (0.5 * sin(2 * PI * 1_000.0 * i / rate) * 32767).toInt().toShort()
            buffer.putShort(s); buffer.putShort(s)
        }
        buffer.flip()
        p.queueInput(buffer)
        val o = p.output
        val outLeft = DoubleArray(frames) { val v = o.getShort() / 32767.0; o.getShort(); v }
        val inLeft = DoubleArray(frames) { 0.5 * sin(2 * PI * 1_000.0 * it / rate) }
        assertEquals(-6.0, gainDb(inLeft, outLeft), 0.3)
    }

    @Test
    fun `moving a slider while a song plays does not click`() {
        val p = processor(gains(5 to 3f))
        val frames = rate
        val out = DoubleArray(frames)
        var at = 0
        while (at < frames) {
            // Half way through, the band moves a step, as a finger on its slider does.
            if (at >= frames / 2) p.settings = EqualizerAudioProcessor.Settings(true, gains(5 to 4f))
            val n = minOf(512, frames - at)
            val buffer = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until n) { val v = (0.5 * sin(2 * PI * 1_000.0 * (at + i) / rate)).toFloat(); buffer.putFloat(v); buffer.putFloat(v) }
            buffer.flip()
            p.queueInput(buffer)
            val o = p.output
            for (i in 0 until n) { out[at + i] = o.getFloat().toDouble(); o.getFloat() }
            at += n
        }
        // A sine at 1 kHz moves at most about 0.07 from one sample to the next at this level; a
        // filter restarted from silence jumps by up to the whole wave.
        val steady = (frames / 4 until frames / 2).maxOf { abs(out[it] - out[it - 1]) }
        val across = (frames / 2 - 8 until frames / 2 + 1024).maxOf { abs(out[it] - out[it - 1]) }
        assertTrue("$across across the change against $steady before it", across <= steady * 1.3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a curve with the wrong number of bands is refused`() {
        EqualizerAudioProcessor.Settings(true, listOf(1f, 2f))
    }
}
