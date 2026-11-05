/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import com.dd3boh.outertune.fingerprint.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The filters: the cookbook's responses, sweeps that never zip or step, and a bypass that is exact. */
class BiquadTest {

    private val rate = 44_100

    private fun staticMove(kind: FilterKind, order: Int, hz: Double, frames: Long) =
        FilterMove(kind, order, start = 0, end = frames, fadeIn = 0, fadeOut = 0, sweepFrom = 0.0, sweepTo = 0.0, fromHz = hz, toHz = hz)

    /** Runs a mono [signal] through [move], one frame at a time. */
    private fun run(move: FilterMove, signal: DoubleArray): DoubleArray {
        val filter = MovingFilter(move, 1, rate)
        val frame = DoubleArray(1)
        return DoubleArray(signal.size) { i ->
            frame[0] = signal[i]
            filter.process(i.toLong(), frame)
            frame[0]
        }
    }

    private fun sine(hz: Double, frames: Int) = DoubleArray(frames) { sin(2 * PI * hz * it / rate) }

    private fun rmsDb(x: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return 10 * log10(s / (to - from))
    }

    @Test
    fun staticResponsesMatchTheCookbookToATenthOfADecibel() {
        val cases = listOf(
            Triple(FilterKind.LOW_PASS, 2, 1000.0) to doubleArrayOf(200.0, 1000.0, 3000.0),
            Triple(FilterKind.LOW_PASS, 2, 800.0) to doubleArrayOf(400.0, 800.0, 1600.0),
            Triple(FilterKind.HIGH_PASS, 2, 400.0) to doubleArrayOf(100.0, 400.0, 2000.0),
            Triple(FilterKind.HIGH_PASS, 4, 120.0) to doubleArrayOf(60.0, 120.0, 500.0),
            Triple(FilterKind.HIGH_PASS, 4, 20.0) to doubleArrayOf(20.0, 60.0),
        )
        for ((filter, tones) in cases) {
            val (kind, order, hz) = filter
            for (tone in tones) {
                val frames = 4 * rate
                val input = sine(tone, frames)
                val output = run(staticMove(kind, order, hz, frames.toLong()), input)
                val measured = rmsDb(output, frames / 2, frames) - rmsDb(input, frames / 2, frames)
                var expected = 0.0
                for (q in Biquad.qualities(order)) expected += 20 * log10(Biquad.magnitude(kind, hz, q, rate, tone))
                println("%s %d at %.0f Hz, tone %.0f Hz: %.3f dB, cookbook %.3f dB".format(kind, order, hz, tone, measured, expected))
                assertEquals("$kind $order $hz at $tone", expected, measured, 0.1)
            }
        }
    }

    @Test
    fun outsideItsFramesTheSongIsUntouchedToTheBit() {
        val move = FilterMove(FilterKind.HIGH_PASS, 4, start = 1000, end = 3000, fadeIn = 100, fadeOut = 100, sweepFrom = 1000.0, sweepTo = 3000.0, fromHz = 120.0, toHz = 20.0)
        val input = DoubleArray(5000) { ((it * 7919) % 2001 - 1000).toDouble() }
        val output = run(move, input)
        for (i in 0 until 1000) assertEquals(input[i], output[i], 0.0)
        for (i in 3000 until 5000) assertEquals(input[i], output[i], 0.0)
    }

    @Test
    fun aSweepNeverStepsMoreThanTheStaticFilter() {
        val frames = 3 * rate
        val input = sine(1000.0, frames)
        val sweep = FilterMove(FilterKind.LOW_PASS, 2, start = 0, end = frames.toLong(), fadeIn = 0, fadeOut = 0, sweepFrom = 0.5 * rate, sweepTo = 2.5 * rate, fromHz = 19_000.0, toHz = 800.0)
        val swept = run(sweep, input)
        val still = run(staticMove(FilterKind.LOW_PASS, 2, 19_000.0, frames.toLong()), input)
        fun maxStep(x: DoubleArray) = (rate / 10 until x.size).maxOf { abs(x[it] - x[it - 1]) }
        println("largest step: swept %.5f, static %.5f".format(maxStep(swept), maxStep(still)))
        assertTrue(maxStep(swept) <= maxStep(still) * 1.001)

        // And nothing the coefficient updates add stands above -80 dB: away from the tone, the
        // spectrum of the swept output in the middle of the sweep is empty.
        val n = 16_384
        val from = (1.5 * rate).toInt() - n / 2
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val block = FloatArray(n) { (swept[from + it] * window[it]).toFloat() }
        val power = FloatArray(n / 2 + 1)
        Fft(n).realForwardSquaredMagnitude(block, power)
        val binHz = rate.toDouble() / n
        val peak = power.max()
        var spur = 0f
        for (k in power.indices) {
            val f = k * binHz
            if (abs(f - 1000.0) > 150.0 && f > 30.0) spur = max(spur, power[k])
        }
        val db = 10 * log10(spur / peak.toDouble())
        println("strongest component away from the tone: %.1f dB".format(db))
        assertTrue(db < -80.0)
    }

    @Test
    fun aFilterFadedInAndOutLeavesNoStepAtItsEdges() {
        val frames = 2 * rate
        val input = sine(220.0, frames)
        val move = FilterMove(FilterKind.HIGH_PASS, 4, start = rate / 2L, end = rate + rate / 2L, fadeIn = (0.05 * rate).toLong(), fadeOut = (0.05 * rate).toLong(), sweepFrom = 0.0, sweepTo = 0.0, fromHz = 120.0, toHz = 120.0)
        val output = run(move, input)
        val sineStep = 2 * PI * 220.0 / rate
        val largest = (1 until frames).maxOf { abs(output[it] - output[it - 1]) }
        assertTrue("step $largest against $sineStep", largest <= sineStep * 1.05)
        val rms = sqrt(output.sumOf { it * it } / frames)
        assertTrue(rms > 0.5)
    }
}
