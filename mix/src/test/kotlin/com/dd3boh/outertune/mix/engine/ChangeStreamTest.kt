/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Specs
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * His ask of 9 Nov 2026: an edit heard as soon as it is made. The window is worked out from where
 * the playhead is: from the window's start it is a whole render's samples to the bit, from inside
 * the change the same music once the stream has settled, and its first frames come long before a
 * whole render is done.
 */
class ChangeStreamTest {

    private val rate = 44_100
    private val songA by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 40, seed = 81)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 30, seed = 82)) }

    private fun spec(preset: Preset, bars: Int = 8): TransitionSpec {
        val at = songA.downbeats[songA.downbeats.size - (bars + 6)] * 1000
        val base = Specs.fadeOnBands().copy(
            a = Anchor("a", at, 120.0), b = Anchor("b", songB.downbeats[0] * 1000, 120.0), beatMatch = false, bars = bars,
        )
        return Presets.apply(base, preset)
    }

    private fun whole(s: TransitionSpec, a: Copy = Copy(songA.samples, rate, 2)) = Preview.render(s, a, Copy(songB.samples, rate, 2)).getOrThrow()
    private fun stream(s: TransitionSpec, a: Copy = Copy(songA.samples, rate, 2)) = Preview.stream(s, a, Copy(songB.samples, rate, 2)).getOrThrow()

    /** The window from its frame [index] to its end, through the stream. */
    private fun streamed(p: PreviewStream, index: Int): ShortArray {
        val n = p.frames - index
        val out = ShortArray(n * 2)
        val st = p.from(index)
        var done = 0
        while (done < n) {
            val k = minOf(1024, n - done)
            st.next(out, done, k)
            done += k
        }
        return out
    }

    private fun db(expected: ShortArray, from: Int, got: ShortArray): Double {
        var err = 0.0
        var sig = 0.0
        for (i in got.indices) {
            val d = (expected[from * 2 + i] - got[i]).toDouble()
            err += d * d
            sig += expected[from * 2 + i].toDouble() * expected[from * 2 + i]
        }
        return 10 * log10(err / sig + 1e-30)
    }

    @Test
    fun `from the window's start the stream is the whole render to the bit`() {
        for (preset in listOf(Preset.FADE, Preset.ECHO_OUT, Preset.RISE, Preset.BLEND, Preset.CUT, Preset.RIFF_RISE)) {
            val s = spec(preset)
            val w = whole(s)
            val p = stream(s)
            assertEquals(w.fromFrame, p.fromFrame)
            assertEquals(w.toFrame, p.toFrame)
            assertArrayEquals("$preset", w.pcm, streamed(p, 0))
        }
    }

    @Test
    fun `beat matched, from the window's start the stream is the whole render to the bit`() {
        val a = Synth.song(SynthSpec(bpm = 122.0, bars = 40, seed = 83))
        val at = a.downbeats[a.downbeats.size - 14] * 1000
        val s = Presets.apply(
            Specs.fadeOnBands().copy(a = Anchor("a", at, 122.0), b = Anchor("b", songB.downbeats[0] * 1000, 120.0), beatMatch = true),
            Preset.FADE,
        )
        assertTrue(s.beatMatched)
        val copy = Copy(a.samples, rate, 2)
        assertArrayEquals(whole(s, copy).pcm, streamed(stream(s, copy), 0))
    }

    @Test
    fun `from inside the change the stream is the same music, once settled`() {
        for (preset in listOf(Preset.FADE, Preset.RISE, Preset.BLEND, Preset.CUT, Preset.RIFF_RISE)) {
            val s = spec(preset)
            val w = whole(s)
            val p = stream(s)
            for (u in listOf(0.5, 3.25, 6.0)) {
                val index = p.indexAt(u)
                val got = streamed(p, index)
                val d = db(w.pcm, index, got)
                var worst = 0
                for (i in got.indices) worst = maxOf(worst, abs(w.pcm[index * 2 + i] - got[i]))
                println("$preset from bar $u: %.1f dB, worst %d".format(d, worst))
                assertTrue("$preset from bar $u: %.1f dB".format(d), d < -60)
                assertTrue("$preset from bar $u: worst $worst", worst <= 2)
            }
        }
    }

    @Test
    fun `an echo out from inside the change is the same after its ring, and the next song exactly`() {
        val s = spec(Preset.ECHO_OUT)
        val w = whole(s)
        val p = stream(s)
        val index = p.indexAt(4.0)
        val got = streamed(p, index)
        val after = (p.plan.mixTo - p.fromFrame).toInt() - index
        for (i in after * 2 until got.size) assertEquals(w.pcm[index * 2 + i], got[i])
        println("echo out from bar 4: %.1f dB over the rest of the window".format(db(w.pcm, index, got)))
    }

    @Test
    fun `its first frames come long before a whole render is done`() {
        val s = spec(Preset.BLEND, bars = 16)
        // Warmed once, as the phone's code is after the first change.
        whole(s)
        streamed(stream(s), 0)
        val t0 = System.nanoTime()
        whole(s)
        val renderMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        val p = stream(s)
        val st = p.from(p.indexAt(6.0))
        st.next(ShortArray(4096 * 2), 0, 4096)
        val firstMs = (System.nanoTime() - t1) / 1e6
        println("16 bars: a whole render %.0f ms, the first 4096 frames from bar 6 of the stream %.0f ms".format(renderMs, firstMs))
        assertTrue("first %.0f ms, render %.0f ms".format(firstMs, renderMs), firstMs < renderMs / 3)
    }
}
