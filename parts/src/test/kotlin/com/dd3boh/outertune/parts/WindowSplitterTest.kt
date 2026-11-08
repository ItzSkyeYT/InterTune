/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A window split into its four parts with stand-ins for the network: the files named and sized as
 * asked, the four adding up to the window within the PC's rounding, nothing moved in time, the
 * segments added back as stemlib adds them to the bit, melody taken as split_windows.py takes it,
 * and a cancelled or failed split leaving nothing behind.
 */
class WindowSplitterTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A network that hands each stem a share of its input ([Scnet.STEMS]' order: drums, bass, other, vocals). */
    private class Shares(private val shares: FloatArray = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)) : SpecModel {
        private var last = FloatArray(0)
        var runs = 0
        override fun run(spec: FloatArray) {
            last = spec.copyOf()
            runs++
        }

        override fun readStem(stem: Int, into: FloatArray) {
            val k = shares[stem]
            for (i in last.indices) into[i] = last[i] * k
        }
    }

    /**
     * A network whose stems change with each run and with frequency, so segments differ where they
     * overlap and the weights that add them back show: stem s gets (s + 1 + run) / 10 of the low
     * bins and a tenth of the rest.
     */
    private class Changing : SpecModel {
        private var last = FloatArray(0)
        private var run = 0
        override fun run(spec: FloatArray) {
            last = spec.copyOf()
            run++
        }

        override fun readStem(stem: Int, into: FloatArray) {
            val low = (stem + 1 + run) / 10f
            for (i in last.indices) {
                val bin = (i % (Scnet.BINS * Scnet.FRAMES)) / Scnet.FRAMES
                into[i] = last[i] * if (bin < 300) low else 0.1f
            }
        }
    }

    /**
     * A song-like window: tones from 55 Hz to 15 kHz at levels and phases of [seed]'s, under the
     * resampler's 20.9 kHz, faded in and out so its two ends are silent.
     */
    private fun window(rate: Int, seconds: Double, channels: Int, seed: Int = 3): ShortArray {
        val frames = (seconds * rate).toInt()
        val random = Random(seed)
        val tones = listOf(55.0, 220.0, 330.0, 1_250.0, 3_300.0, 7_100.0, 11_000.0, 15_000.0)
        val levels = tones.map { 0.02 + 0.08 * random.nextDouble() }
        val phases = List(channels) { tones.map { 2 * PI * random.nextDouble() } }
        val fade = rate / 10
        val out = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val edge = min(1.0, min(f, frames - 1 - f).toDouble() / fade)
            val t = f.toDouble() / rate
            for (c in 0 until channels) {
                var v = 0.0
                for (k in tones.indices) v += levels[k] * sin(2 * PI * tones[k] * t + phases[c][k])
                out[f * channels + c] = (v * edge * 32768).roundToInt().toShort()
            }
        }
        return out
    }

    private fun targets(dir: File, key: String) = WindowSplitter.PART_NAMES.map { File(dir, "$key.$it.pcm") }

    private fun read(file: File): ShortArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(b.remaining()).also { b.get(it) }
    }

    @Test
    fun `a window's four parts are written as named, each as long as the window, adding up to it`() {
        for ((rate, channels) in listOf(48_000 to 2, 44_100 to 2, 48_000 to 1)) {
            val pcm = window(rate, 21.0, channels)
            val dir = folder.newFolder()
            val out = targets(dir, "abc-tail")
            val model = Shares()
            val seen = ArrayList<Double>()
            assertTrue(WindowSplitter(model).split(ShortBuffer.wrap(pcm), rate, channels, out, { seen += it }))
            // 21 s at 44.1 kHz, padded by a border at both ends: four segments.
            assertEquals(4, model.runs)
            assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), seen)
            assertEquals(out.map { it.name }.toSet(), dir.list()!!.toSet())
            val parts = out.map { f ->
                assertEquals("${f.name} at $rate Hz", pcm.size * 2L, f.length())
                read(f)
            }
            // Each part rounded on its own, as on the PC: the four are the window within two steps.
            for (i in pcm.indices) {
                val off = parts.sumOf { it[i].toInt() } - pcm[i]
                assertTrue("sample $i at $rate Hz, $channels channels: $off steps", abs(off) <= 2)
            }
            // Each part is its share of the window, nothing moved: vocals were given 0.4 of it.
            var worst = 0
            for (i in pcm.indices) worst = maxOf(worst, abs(parts[0][i] - (pcm[i] * 0.4).roundToInt()))
            println("window splitter: $rate Hz, $channels channels: vocals off 0.4 of the window by at most $worst steps")
            assertTrue("$rate Hz, $channels channels: vocals off by $worst steps", worst <= 2)
        }
    }

    @Test
    fun `the segments are added back as stemlib adds them, to the bit`() {
        // At 44.1 kHz nothing is resampled, so the files are ScnetSegment.separate's parts, rounded.
        val rate = Scnet.RATE
        val pcm = window(rate, 26.0, 2, seed = 9)
        val frames = pcm.size / 2
        val out = targets(folder.newFolder(), "x-head")
        assertTrue(WindowSplitter(Changing()).split(ShortBuffer.wrap(pcm), rate, 2, out))
        val left = FloatArray(frames) { pcm[2 * it] / 32768f }
        val right = FloatArray(frames) { pcm[2 * it + 1] / 32768f }
        val plan = ScnetPlan(frames, WindowSplitter.OVERLAP)
        assertTrue(plan.padded && plan.count == 4)
        val want = ScnetSegment.separate(left, right, WindowSplitter.OVERLAP, Changing())
        // vocals, drums, bass: stems 3, 0 and 1.
        for ((t, stem) in listOf(0 to 3, 1 to 0, 2 to 1)) {
            val got = read(out[t])
            for (f in 0 until frames) {
                assertEquals("part $t frame $f left", WindowSplitter.quantize(want[2 * stem][f]), got[2 * f].toInt())
                assertEquals("part $t frame $f right", WindowSplitter.quantize(want[2 * stem + 1][f]), got[2 * f + 1].toInt())
            }
        }
        // Melody: ((window - vocals) - drums) - bass in float32, as numpy takes it, then rounded.
        val melody = read(out[3])
        for (f in 0 until frames) {
            for (c in 0..1) {
                val w = pcm[2 * f + c] / 32768f
                val m = ((w - want[6 + c][f]) - want[c][f]) - want[2 + c][f]
                assertEquals("melody frame $f channel $c", WindowSplitter.quantize(m), melody[2 * f + c].toInt())
            }
        }
    }

    @Test
    fun `a mono window's parts are the mean of the two channels the network was given`() {
        val rate = Scnet.RATE
        val pcm = window(rate, 14.0, 1, seed = 4)
        val out = targets(folder.newFolder(), "m-head")
        assertTrue(WindowSplitter(Changing()).split(ShortBuffer.wrap(pcm), rate, 1, out))
        val stereo = FloatArray(pcm.size) { pcm[it] / 32768f }
        val want = ScnetSegment.separate(stereo, stereo.copyOf(), WindowSplitter.OVERLAP, Changing())
        val parts = out.map { read(it) }
        for ((t, stem) in listOf(0 to 3, 1 to 0, 2 to 1)) {
            assertEquals(pcm.size * 2L, out[t].length())
            for (f in pcm.indices) {
                assertEquals("part $t frame $f", WindowSplitter.quantize((want[2 * stem][f] + want[2 * stem + 1][f]) * 0.5f), parts[t][f].toInt())
            }
        }
        for (f in pcm.indices) assertTrue(abs(parts.sumOf { it[f].toInt() } - pcm[f]) <= 2)
    }

    @Test
    fun `a short window, shorter than a segment, splits too`() {
        val pcm = window(48_000, 3.0, 2)
        val out = targets(folder.newFolder(), "short-head")
        val model = Shares()
        assertTrue(WindowSplitter(model).split(ShortBuffer.wrap(pcm), 48_000, 2, out))
        assertEquals(1, model.runs)
        val parts = out.map { read(it) }
        for (i in pcm.indices) assertTrue(abs(parts.sumOf { it[i].toInt() } - pcm[i]) <= 2)
    }

    @Test
    fun `a cancelled split leaves nothing behind`() {
        val pcm = window(48_000, 21.0, 2)
        val dir = folder.newFolder()
        val out = targets(dir, "c-tail")
        val model = Shares()
        assertFalse(WindowSplitter(model).split(ShortBuffer.wrap(pcm), 48_000, 2, out, cancelled = { model.runs >= 2 }))
        assertEquals(2, model.runs)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a failing network leaves nothing behind`() {
        val dir = folder.newFolder()
        val failing = object : SpecModel {
            override fun run(spec: FloatArray) = throw IllegalStateException("no model")
            override fun readStem(stem: Int, into: FloatArray) = Unit
        }
        val thrown = runCatching { WindowSplitter(failing).split(ShortBuffer.wrap(window(48_000, 2.0, 2)), 48_000, 2, targets(dir, "f-head")) }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `rounding is the PC's, halves to even, clipped to 16 bits`() {
        assertEquals(0, WindowSplitter.quantize(0.5f / 32768))
        assertEquals(2, WindowSplitter.quantize(1.5f / 32768))
        assertEquals(-2, WindowSplitter.quantize(-1.5f / 32768))
        assertEquals(32767, WindowSplitter.quantize(1.2f))
        assertEquals(-32768, WindowSplitter.quantize(-1.2f))
    }
}
