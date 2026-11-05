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
import kotlin.math.ln
import kotlin.math.sin

/** The stretcher: pitch kept, timing where the map says, and an exact copy where the map does nothing. */
class WsolaTest {

    private val rate = 44_100

    private fun wsola(tail: ShortArray, anchors: DoubleArray = DoubleArray(0)) =
        FrontEnd.of(tail, rate, 2).let { Wsola(tail, 2, rate, it.mono, it.factor, anchors) }

    /** The frequency of the strongest tone in [x] (one channel of interleaved stereo), by a parabola over the FFT's peak. */
    private fun toneHz(x: DoubleArray, from: Int): Double {
        val n = 1 shl 16
        val block = FloatArray(n) { (x[2 * (from + it)] * (0.5 - 0.5 * cos(2 * PI * it / n))).toFloat() }
        val power = FloatArray(n / 2 + 1)
        Fft(n).realForwardSquaredMagnitude(block, power)
        var k = 1
        for (i in 1 until power.size - 1) if (power[i] > power[k]) k = i
        val a = ln(power[k - 1].toDouble())
        val b = ln(power[k].toDouble())
        val c = ln(power[k + 1].toDouble())
        val shift = 0.5 * (a - c) / (a - 2 * b + c)
        return (k + shift) * rate / n
    }

    @Test
    fun aToneKeepsItsPitchAtFourPercentEitherWay() {
        val tail = Synth.sine(1000.0, 12.0, rate, amplitude = 0.5)
        for (speed in doubleArrayOf(0.96, 1.04)) {
            val map = TimeMap(rampStart = 1.0 * rate, rampLength = 0.5 * rate, speed = speed)
            val out = wsola(tail).render(map, 0, (9.0 * rate).toLong())
            val hz = toneHz(out, 3 * rate)
            println("speed $speed: %.3f Hz".format(hz))
            assertEquals(1000.0, hz, 1.0)
        }
    }

    @Test
    fun clicksLandWhereTheMapPutsThem() {
        val beat = 0.5
        val times = DoubleArray(40) { 0.25 + it * beat }
        val tail = Synth.clicks(times, 21.0, rate)
        for (speed in doubleArrayOf(0.96, 1.04)) {
            val map = TimeMap(rampStart = 2.0 * rate, rampLength = 1.0 * rate, speed = speed)
            val length = map.outAt(20.0 * rate).toLong()
            // The clicks are the beats, so they are the anchors.
            val out = wsola(tail, DoubleArray(times.size) { times[it] * rate }).render(map, 0, length)
            val errors = ArrayList<Double>()
            for (t in times) {
                val expected = map.outAt(t * rate)
                if (expected + rate * 0.05 >= length) continue
                // The loudest sample within 20 ms of where the click should be.
                var best = expected.toInt()
                for (o in (expected - 0.02 * rate).toInt()..(expected + 0.02 * rate).toInt()) {
                    if (o in 0 until length.toInt() && abs(out[2 * o]) > abs(out[2 * best])) best = o
                }
                errors += (best - expected) / rate * 1000
            }
            val worst = errors.maxOf { abs(it) }
            val mean = errors.map { abs(it) }.average()
            println("speed $speed: clicks off by %.2f ms on average, %.2f ms at worst, over %d".format(mean, worst, errors.size))
            assertTrue(worst <= 5.0)
            assertTrue(mean < 2.0)
        }
    }

    @Test
    fun withoutAnchorsTheClicksStillStayWithinTheSearch() {
        val times = DoubleArray(40) { 0.25 + it * 0.5 }
        val tail = Synth.clicks(times, 21.0, rate)
        val map = TimeMap(rampStart = 2.0 * rate, rampLength = 1.0 * rate, speed = 0.96)
        val length = map.outAt(20.0 * rate).toLong()
        val out = wsola(tail).render(map, 0, length)
        for (t in times) {
            val expected = map.outAt(t * rate)
            if (expected + rate * 0.05 >= length) continue
            var best = expected.toInt()
            for (o in (expected - 0.02 * rate).toInt()..(expected + 0.02 * rate).toInt()) {
                if (o in 0 until length.toInt() && abs(out[2 * o]) > abs(out[2 * best])) best = o
            }
            // 5 ms from the frame's centre, and up to 1.4 ms more at its edge, where the map's
            // speed has moved the content by (1 - speed) of half a frame.
            assertTrue(abs(best - expected) / rate * 1000 <= 6.5)
        }
    }

    @Test
    fun whereTheMapIsTheIdentityTheTailIsCopiedExactly() {
        val song = Synth.song(SynthSpec(bpm = 126.0, bars = 8, seed = 3))
        val tail = song.samples
        val map = TimeMap(rampStart = 6.0 * rate, rampLength = 0.5 * rate, speed = 0.97)
        val from = 1000L
        val to = (5.0 * rate).toLong()
        val out = wsola(tail).render(map, from, to)
        for (i in 0 until ((to - from) * 2).toInt()) assertEquals(tail[(from * 2 + i).toInt()].toDouble(), out[i], 0.0)
        // And all the way through, the same answer as asking for the whole stretch at once.
        val whole = wsola(tail).render(map, 0, (12.0 * rate).toLong())
        val part = wsola(tail).render(map, (7.0 * rate).toLong(), (9.0 * rate).toLong())
        for (i in part.indices) assertEquals(whole[(7 * rate * 2) + i], part[i], 0.0)
    }

    @Test
    fun theStretchJoinsTheRawTailWithoutAStep() {
        val tail = Synth.sine(330.0, 10.0, rate, amplitude = 0.5)
        val map = TimeMap(rampStart = 2.0 * rate, rampLength = 1.0 * rate, speed = 1.05)
        val out = wsola(tail).render(map, 0, (8.0 * rate).toLong())
        val sineStep = 2 * PI * 330.0 / rate * 0.5 * 32767
        var largest = 0.0
        for (o in 1 until out.size / 2) largest = maxOf(largest, abs(out[2 * o] - out[2 * (o - 1)]))
        println("largest step %.1f against the sine's own %.1f".format(largest, sineStep))
        assertTrue(largest <= sineStep * 1.1)
        // The tone stays a tone: no sample drifts far from its amplitude.
        val peak = (3 * rate until 7 * rate).maxOf { abs(out[2 * it]) }
        assertTrue(peak in 0.45 * 32767..0.52 * 32767)
        assertTrue(sin(0.0) == 0.0)
    }
}
