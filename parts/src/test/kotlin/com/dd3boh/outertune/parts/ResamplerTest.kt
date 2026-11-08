/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The resampler between a window's rate and the model's: nothing moves in time, a tone keeps its
 * level and phase there and back, what would fold back stays 90 dB down, and the numbers are
 * torchaudio's with the PC tool's settings (reference made by parts/tools/make_resample_reference.py,
 * skipped without it).
 */
class ResamplerTest {

    private fun resampleAll(r: Resampler, x: FloatArray): FloatArray {
        val out = FloatArray(r.outputFrames(x.size.toLong()).toInt())
        r.resample(x, 0, x.size, 0, out.size, out, 0)
        return out
    }

    private fun sine(rate: Int, frames: Int, hz: Double, phase: Double = 0.0, amp: Double = 0.5) =
        FloatArray(frames) { (amp * sin(2 * PI * hz * it / rate + phase)).toFloat() }

    /** Amplitude and phase of [hz] in [x] over frames [from] until [to], by least squares on a sine and a cosine. */
    private fun tone(x: FloatArray, rate: Int, hz: Double, from: Int, to: Int): Pair<Double, Double> {
        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var xs = 0.0
        var xc = 0.0
        for (i in from until to) {
            val s = sin(2 * PI * hz * i / rate)
            val c = cos(2 * PI * hz * i / rate)
            ss += s * s; cc += c * c; sc += s * c
            xs += x[i] * s; xc += x[i] * c
        }
        val det = ss * cc - sc * sc
        val a = (xs * cc - xc * sc) / det
        val b = (xc * ss - xs * sc) / det
        return hypot(a, b) to atan2(b, a)
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return 10 * log10(e / (to - from) + 1e-300)
    }

    @Test
    fun `48 kHz to 44_1 kHz goes by 160 frames to 147, and lengths are torchaudio's`() {
        val r = Resampler(48_000, 44_100)
        assertEquals(160, r.down)
        assertEquals(147, r.up)
        // ceil(n * 147 / 160)
        assertEquals(147L, r.outputFrames(160))
        assertEquals(148L, r.outputFrames(161))
        assertEquals(3_175_200L, r.outputFrames(3_456_000))
        assertEquals(1L, r.outputFrames(1))
        val back = Resampler(44_100, 48_000)
        assertEquals(3_456_000L, back.outputFrames(3_175_200))
        // There and back is never shorter than what went in.
        for (n in listOf(1L, 2L, 159L, 160L, 161L, 2_646_001L)) assertTrue(back.outputFrames(r.outputFrames(n)) >= n)
        // Each output frame reads about 2 * 64 zero crossings of input around it.
        assertTrue("${r.span}", r.span in 140..160)
    }

    @Test
    fun `an impulse is not moved, one way or there and back`() {
        val r = Resampler(48_000, 44_100)
        val back = Resampler(44_100, 48_000)
        for (at in listOf(8_000, 8_001, 8_037)) {
            val x = FloatArray(16_000).also { it[at] = 1f }
            val there = resampleAll(r, x)
            val again = resampleAll(back, there)
            // There and back: the peak where the impulse was, and the same on both sides of it. On the
            // grid both rates share (every 160 frames) to 1e-5: torchaudio's taps are placed in float32,
            // which leaves its filter that far from mirrored. Off the grid only nearly: the little that
            // folds back near 22 kHz is not mirrored.
            var peak = 0
            for (i in again.indices) if (again[i] > again[peak]) peak = i
            assertEquals("impulse at $at", at, peak)
            val tolerance = if (at % 160 == 0) 1e-5f else 1e-3f
            for (m in 1..400) assertEquals("impulse at $at, $m either side", again[at - m], again[at + m], tolerance)
            if (at % 160 == 0) {
                // On a block's edge the impulse lands on an output frame: there too it stays put, even on both sides.
                val o = at / 160 * 147
                var p = 0
                for (i in there.indices) if (there[i] > there[p]) p = i
                assertEquals(o, p)
                for (m in 1..400) assertEquals(there[o - m], there[o + m], 1e-5f)
            }
        }
    }

    @Test
    fun `a tone there and back keeps its level and its phase`() {
        val r = Resampler(48_000, 44_100)
        val back = Resampler(44_100, 48_000)
        for (hz in listOf(50.0, 440.0, 1_000.0, 5_000.0, 10_000.0, 16_000.0, 19_000.0)) {
            val x = sine(48_000, 48_000, hz, phase = 0.7)
            val y = resampleAll(back, resampleAll(r, x))
            val from = 4_000
            val to = 44_000
            val (ax, px) = tone(x, 48_000, hz, from, to)
            val (ay, py) = tone(y, 48_000, hz, from, to)
            val levelDb = 20 * log10(ay / ax)
            val phaseDeg = (py - px) * 180 / PI
            val residual = FloatArray(x.size) { x[it] - y[it] }
            val restDb = rmsDb(residual, from, to) - rmsDb(x, from, to)
            println("resampler: $hz Hz there and back: level %+.6f dB, phase %+.5f degrees, rest %.1f dB".format(levelDb, phaseDeg, restDb))
            // No phase anywhere: the filter is symmetric. Level and the rest only in the flat part;
            // from 18 kHz the roll-off starts (20.9 kHz is its half-way point).
            assertTrue("$hz Hz phase $phaseDeg degrees", abs(phaseDeg) < 0.001)
            if (hz <= 16_000) {
                assertTrue("$hz Hz level $levelDb dB", abs(levelDb) < 0.001)
                assertTrue("$hz Hz rest $restDb dB", restDb < -90)
            } else {
                assertTrue("$hz Hz level $levelDb dB", abs(levelDb) < 0.1)
            }
        }
        // One way, against the tone itself sampled at 44.1 kHz.
        for (hz in listOf(440.0, 15_000.0)) {
            val y = resampleAll(r, sine(48_000, 48_000, hz, phase = 1.1))
            val want = sine(44_100, y.size, hz, phase = 1.1)
            val diff = FloatArray(y.size) { y[it] - want[it] }
            val restDb = rmsDb(diff, 4_000, 40_000) - rmsDb(want, 4_000, 40_000)
            println("resampler: $hz Hz to 44.1 kHz against the tone sampled there: rest %.1f dB".format(restDb))
            assertTrue("$hz Hz one way, rest $restDb dB", restDb < -90)
        }
    }

    @Test
    fun `what would fold back is held 90 dB down`() {
        val r = Resampler(48_000, 44_100)
        // 22.3 to 24 kHz at 48 kHz would land at 20.1 to 21.8 kHz at 44.1 kHz.
        for (hz in listOf(22_300.0, 23_000.0, 23_900.0)) {
            val x = sine(48_000, 48_000, hz)
            val y = resampleAll(r, x)
            val db = rmsDb(y, 4_000, 40_000) - rmsDb(x, 4_000, 44_000)
            println("resampler: $hz Hz down to 44.1 kHz: %.1f dB".format(db))
            assertTrue("$hz Hz comes through at $db dB", db < -90)
        }
        // Upward, a tone's image above 22.05 kHz (folded under 24 kHz at 48 kHz) is held down the same.
        val back = Resampler(44_100, 48_000)
        for (hz in listOf(15_000.0, 20_000.0)) {
            val y = resampleAll(back, sine(44_100, 44_100, hz))
            val (a, p) = tone(y, 48_000, hz, 4_000, 44_000)
            val rest = FloatArray(y.size) { (y[it] - a * sin(2 * PI * hz * it / 48_000 + p)).toFloat() }
            val db = rmsDb(rest, 4_000, 44_000) - 20 * log10(0.5 / sqrt(2.0))
            println("resampler: $hz Hz up to 48 kHz: everything else %.1f dB".format(db))
            assertTrue("$hz Hz up: $db dB of images", db < -90)
        }
    }

    @Test
    fun `a stream gives what one call gives, piece by piece`() {
        val random = Random(5)
        for ((from, to) in listOf(48_000 to 44_100, 44_100 to 48_000, 44_100 to 44_100, 96_000 to 44_100)) {
            val r = Resampler(from, to)
            val n = 50_000 + random.nextInt(1_000)
            val left = FloatArray(n) { random.nextFloat() - 0.5f }
            val right = FloatArray(n) { random.nextFloat() - 0.5f }
            val wantL = resampleAll(r, left)
            val wantR = resampleAll(r, right)
            val gotL = FloatArray(wantL.size)
            val gotR = FloatArray(wantR.size)
            var next = 0L
            val stream = ResamplerStream(r, 2, n.toLong()) { at, count, values ->
                assertEquals(next, at)
                values[0].copyInto(gotL, at.toInt(), 0, count)
                values[1].copyInto(gotR, at.toInt(), 0, count)
                next += count
            }
            var fed = 0
            while (fed < n) {
                val k = minOf(n - fed, 1 + random.nextInt(20_000))
                stream.push(arrayOf(left, right), fed, k)
                fed += k
            }
            stream.finish()
            assertEquals(wantL.size.toLong(), next)
            assertTrue("$from to $to left", wantL.contentEquals(gotL))
            assertTrue("$from to $to right", wantR.contentEquals(gotR))
        }
    }

    @Test
    fun `the same rate passes every frame through`() {
        val r = Resampler(44_100, 44_100)
        assertTrue(r.identity)
        val x = FloatArray(1_000) { (it * 0.37f) % 1f - 0.5f }
        assertTrue(x.contentEquals(resampleAll(r, x)))
    }

    @Test
    fun `the numbers are torchaudio's with the PC tool's settings`() {
        val dir = referenceDir()
        assumeTrue("no reference: run parts/tools/make_resample_reference.py", dir != null && File(dir, "resample-down.in.f32").isFile)
        for ((name, rates) in listOf("down" to (48_000 to 44_100), "up" to (44_100 to 48_000), "down96" to (96_000 to 44_100))) {
            val input = read(File(dir, "resample-$name.in.f32"))
            val want = read(File(dir, "resample-$name.out.f32"))
            val r = Resampler(rates.first, rates.second)
            val frames = input.size / 2
            val outFrames = want.size / 2
            assertEquals(name, outFrames.toLong(), r.outputFrames(frames.toLong()))
            for (c in 0..1) {
                val x = input.copyOfRange(c * frames, (c + 1) * frames)
                val y = resampleAll(r, x)
                var signal = 0.0
                var error = 0.0
                var worst = 0f
                for (i in 0 until outFrames) {
                    val w = want[c * outFrames + i]
                    signal += w.toDouble() * w
                    error += (y[i] - w).toDouble() * (y[i] - w)
                    worst = maxOf(worst, abs(y[i] - w))
                }
                val db = 10 * log10(signal / error)
                println("resampler: $name channel $c against torchaudio: %.1f dB, worst sample %.2e (%.4f of a 16-bit step)".format(db, worst, worst * 32768))
                // torchaudio's own taps summed in float64 land 130 to 133 dB from it (its float32 sums);
                // these 127 to 128. Taps worked out wholly in float64 were 107 dB off.
                assertTrue("$name channel $c: $db dB", db > 125)
                assertTrue("$name channel $c: worst $worst", worst < 0.05f / 32768)
            }
        }
    }

    private fun read(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun referenceDir(): File? {
        System.getProperty("parts.reference")?.let { return File(it).takeIf(File::isDirectory) }
        var at: File? = File("").absoluteFile
        while (at != null) {
            val candidate = File(at, "experiments/features/automix/parts/reference")
            if (candidate.isDirectory) return candidate
            at = at.parentFile
        }
        return null
    }
}
