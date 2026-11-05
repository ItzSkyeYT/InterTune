/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The resampler between the rates songs decode at and the rates renderers list: the sound kept
 * flat, nothing folded back into what can be heard, and a start in the middle giving the frames a
 * start from the beginning gives.
 */
class PcmResamplerTest {
    /** [seconds] of a tone at [hz], sampled at [rate], in [channels] identical channels, at [amplitude]. */
    private fun tone(hz: Double, rate: Int, seconds: Double, channels: Int = 2, amplitude: Double = 16_000.0): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * channels) { index ->
            (amplitude * sin(2 * PI * hz * (index / channels) / rate)).roundToInt().toShort()
        }
    }

    /** Everything [input] becomes, pushed in pieces of [piece] frames as a decoder hands them over. */
    private fun resampleAll(input: ShortArray, inRate: Int, outRate: Int, channels: Int = 2, piece: Int = 960): ShortArray {
        val resampler = PcmResampler(inRate, outRate, channels).apply { start(0, 0) }
        val out = ArrayList<Short>()
        val buffer = ShortArray(4096 * channels)
        fun drain() {
            while (true) {
                val count = resampler.pull(buffer, 0, 4096)
                if (count == 0) return
                for (i in 0 until count * channels) out += buffer[i]
            }
        }
        val frames = input.size / channels
        var at = 0
        while (at < frames) {
            val count = minOf(piece, frames - at)
            resampler.push(input, at * channels, count)
            drain()
            at += count
        }
        resampler.finish()
        drain()
        return out.toShortArray()
    }

    /**
     * The amplitudes of [frequencies] in channel 0 of [samples] at [rate], over frames [from] until
     * [until], fitted together by least squares, so neither leaks into the other the way a plain
     * correlation over a window that is not a whole number of periods would.
     */
    private fun amplitudes(samples: ShortArray, rate: Int, frequencies: List<Double>, from: Int, until: Int, channels: Int = 2): List<Double> {
        val size = frequencies.size * 2
        val matrix = Array(size) { DoubleArray(size) }
        val vector = DoubleArray(size)
        val basis = DoubleArray(size)
        for (frame in from until until) {
            for ((index, hz) in frequencies.withIndex()) {
                val angle = 2 * PI * hz * frame / rate
                basis[2 * index] = sin(angle)
                basis[2 * index + 1] = cos(angle)
            }
            val value = samples[frame * channels].toDouble()
            for (row in 0 until size) {
                vector[row] += basis[row] * value
                for (column in 0 until size) matrix[row][column] += basis[row] * basis[column]
            }
        }
        // Gaussian elimination with partial pivoting.
        for (pivot in 0 until size) {
            val best = (pivot until size).maxBy { abs(matrix[it][pivot]) }
            matrix[pivot] = matrix[best].also { matrix[best] = matrix[pivot] }
            vector[pivot] = vector[best].also { vector[best] = vector[pivot] }
            for (row in pivot + 1 until size) {
                val factor = matrix[row][pivot] / matrix[pivot][pivot]
                for (column in pivot until size) matrix[row][column] -= factor * matrix[pivot][column]
                vector[row] -= factor * vector[pivot]
            }
        }
        val solution = DoubleArray(size)
        for (row in size - 1 downTo 0) {
            var sum = vector[row]
            for (column in row + 1 until size) sum -= matrix[row][column] * solution[column]
            solution[row] = sum / matrix[row][row]
        }
        return frequencies.indices.map { sqrt(solution[2 * it] * solution[2 * it] + solution[2 * it + 1] * solution[2 * it + 1]) }
    }

    private fun amplitude(samples: ShortArray, rate: Int, hz: Double, from: Int, until: Int, channels: Int = 2): Double =
        amplitudes(samples, rate, listOf(hz), from, until, channels).single()

    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test
    fun `tones keep their level from 48 to 44 1 kHz and back, up to 20 kHz`() {
        for ((inRate, outRate) in listOf(48_000 to 44_100, 44_100 to 48_000)) {
            for (hz in listOf(100.0, 1_000.0, 10_000.0, 15_000.0, 19_000.0)) {
                val out = resampleAll(tone(hz, inRate, 0.5), inRate, outRate)
                // Away from the two ends, which meet silence.
                val level = amplitude(out, outRate, hz, outRate / 10, outRate * 4 / 10)
                val loss = db(level / 16_000.0)
                assertTrue("$inRate to $outRate at $hz Hz: $loss dB", abs(loss) < 0.05)
            }
        }
    }

    @Test
    fun `the length follows the ratio, and the tone stays clean`() {
        val input = tone(1_000.0, 48_000, 1.0)
        val out = resampleAll(input, 48_000, 44_100)
        // One second in, one second out, give or take the filter's reach at the end.
        val frames = out.size / 2
        assertTrue("$frames frames", frames in 44_100..44_100 + 40)
        // What is left after taking the tone away is noise far below it.
        var residual = 0.0
        var count = 0
        for (frame in 4_410 until 39_690) {
            val ideal = 16_000.0 * sin(2 * PI * 1_000.0 * frame / 44_100)
            residual += (out[frame * 2] - ideal) * (out[frame * 2] - ideal)
            count++
        }
        val noise = db(sqrt(residual / count) / (16_000.0 / sqrt(2.0)))
        assertTrue("residual $noise dB", noise < -80)
    }

    @Test
    fun `nothing heard is folded back when 44 1 kHz goes up to 48`() {
        // A 10 kHz tone's image at 34.1 kHz would come back at 13.9 kHz in a 48 kHz file.
        val out = resampleAll(tone(10_000.0, 44_100, 0.5), 44_100, 48_000)
        val (tone, image) = amplitudes(out, 48_000, listOf(10_000.0, 13_900.0), 4_800, 19_200)
        assertTrue("image ${db(image / tone)} dB", db(image / tone) < -80)
    }

    @Test
    fun `silence stays silence and a constant stays the same constant`() {
        val silent = resampleAll(ShortArray(48_000 * 2), 48_000, 44_100)
        assertTrue(silent.all { it == 0.toShort() })
        val constant = resampleAll(ShortArray(48_000 * 2) { 1_234 }, 48_000, 44_100)
        // Away from the ends, where it meets the silence around the song.
        for (frame in 100 until constant.size / 2 - 100) assertEquals(1_234.toShort(), constant[frame * 2])
    }

    @Test
    fun `a start in the middle gives the very frames a start from the beginning gives`() {
        val input = ShortArray(48_000 * 2) { index -> (sin(index * 0.01) * 12_000 + sin(index * 0.37) * 6_000).roundToInt().toShort() }
        val whole = resampleAll(input, 48_000, 44_100)
        for (startOut in listOf(1L, 147L, 10_000L, 22_051L)) {
            // As a range request does: the decode starts a little early, at some input frame.
            val firstInput = (startOut * 48_000 / 44_100 - 2_000).coerceAtLeast(0)
            val resampler = PcmResampler(48_000, 44_100, 2).apply { start(firstInput, startOut) }
            resampler.push(input, (firstInput * 2).toInt(), 5_000)
            val out = ShortArray(1_000 * 2)
            assertEquals(1_000, resampler.pull(out, 0, 1_000))
            assertArrayEquals("from $startOut", whole.copyOfRange((startOut * 2).toInt(), (startOut * 2).toInt() + 2_000), out)
        }
    }

    @Test
    fun `an odd pair of rates works, with its places shared out`() {
        val resampler = PcmResampler(11_025, 48_000, 1)
        assertEquals(640, resampler.phases)
        val odd = PcmResampler(44_100, 47_999, 1)
        assertEquals(1024, odd.phases)
        val out = resampleAll(tone(440.0, 44_100, 0.5, channels = 1), 44_100, 47_999, channels = 1)
        val level = amplitude(out, 47_999, 440.0, 4_800, 19_200, channels = 1)
        assertTrue("${db(level / 16_000.0)} dB", abs(db(level / 16_000.0)) < 0.05)
    }
}
