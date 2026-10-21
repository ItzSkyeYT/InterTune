/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Specs
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * The editor's preview is the change as the phone plays it: from a copy of A's end that starts
 * anywhere, it gives the samples a render from the held end gives, and the window it loops runs
 * from two bars before the change to two after.
 */
class PreviewTest {

    private val rate = 44_100
    private val songA by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 40, seed = 81)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 30, seed = 82)) }

    private fun spec(preset: Preset): TransitionSpec {
        val at = songA.downbeats[songA.downbeats.size - 12] * 1000
        val base = Specs.fadeOnBands().copy(
            a = Anchor("a", at, 120.0), b = Anchor("b", songB.downbeats[0] * 1000, 120.0), beatMatch = false,
        )
        return Presets.apply(base, preset)
    }

    /** The music the phone holds: A's end from [from] frames in, as a copy starting there. */
    private fun endOfA(from: Int) = Copy(songA.samples.copyOfRange(from * 2, songA.samples.size), rate, 2, from * 1000.0 / rate)

    @Test
    fun `the window loops from two bars before the change to two after, and knows where each bar is`() {
        val s = spec(Preset.FADE)
        val window = Preview.render(s, Copy(songA.samples, rate, 2), Copy(songB.samples, rate, 2)).getOrThrow()
        val bar = 4 * 60.0 / 120.0 * rate
        assertEquals(12 * bar, window.frames.toDouble(), bar * 0.25)
        assertEquals(0.0, window.uAt(window.indexAt(0.0)), 1.0 / bar)
        assertEquals(-2.0, window.uAt(0), 1e-3)
        assertEquals(s.a.atMs, window.aMsAt(window.indexAt(0.0)), 1000.0 / rate)
        assertEquals(s.b.atMs, window.bMsAt(window.indexAt(0.0)), 1000.0 / rate)
    }

    @Test
    fun `from a copy of A starting anywhere the preview is what the held end plays`() {
        for (preset in listOf(Preset.FADE, Preset.ECHO_OUT, Preset.RISE)) {
            val s = spec(preset)
            val whole = Preview.render(s, Copy(songA.samples, rate, 2), Copy(songB.samples, rate, 2)).getOrThrow()
            // The held end starts a whole number of grid steps into A: the same samples to the bit.
            val aligned = 1024 * 47
            val held = Preview.render(s, endOfA(aligned), Copy(songB.samples, rate, 2)).getOrThrow()
            assertEquals(whole.fromFrame - aligned, held.fromFrame)
            assertArrayEquals("$preset", whole.pcm, held.pcm)
            // Anywhere else, the same music to well under -60 dB.
            val offset = 1024 * 47 + 517
            val other = Preview.render(s, endOfA(offset), Copy(songB.samples, rate, 2)).getOrThrow()
            val shift = (whole.fromFrame - offset - other.fromFrame).toInt()
            var err = 0.0
            var sig = 0.0
            for (i in 0 until minOf(whole.frames, other.frames - shift) * 2) {
                val j = i + shift * 2
                if (j < 0 || j >= other.pcm.size) continue
                val d = (whole.pcm[i] - other.pcm[j]).toDouble()
                err += d * d
                sig += whole.pcm[i].toDouble() * whole.pcm[i]
            }
            val db = 10 * log10(err / sig + 1e-30)
            println("$preset from a copy ${offset} frames in: %.1f dB".format(db))
            assertTrue("$preset: %.1f dB".format(db), db < -60)
        }
    }

    @Test
    fun `stretched onto the next song's beat, the preview is what the held end plays, wherever its copy starts`() {
        val a = Synth.song(SynthSpec(bpm = 122.0, bars = 40, seed = 83))
        val at = a.downbeats[a.downbeats.size - 12] * 1000
        val s = Presets.apply(
            Specs.fadeOnBands().copy(a = Anchor("a", at, 122.0), b = Anchor("b", songB.downbeats[0] * 1000, 120.0), beatMatch = true),
            Preset.FADE,
        )
        assertTrue(s.beatMatched)
        val whole = Preview.render(s, Copy(a.samples, rate, 2), Copy(songB.samples, rate, 2)).getOrThrow()
        val from = 4096 * 13
        val held = Preview.render(s, Copy(a.samples.copyOfRange(from * 2, a.samples.size), rate, 2, from * 1000.0 / rate), Copy(songB.samples, rate, 2)).getOrThrow()
        assertArrayEquals(whole.pcm, held.pcm)
        // A copy starting anywhere else is stretched with the same joins, the guide being taken on the change's grid.
        val offset = 4096 * 13 + 517
        val other = Preview.render(s, Copy(a.samples.copyOfRange(offset * 2, a.samples.size), rate, 2, offset * 1000.0 / rate), Copy(songB.samples, rate, 2)).getOrThrow()
        val shift = (whole.fromFrame - offset - other.fromFrame).toInt()
        var err = 0.0
        var sig = 0.0
        for (i in 0 until whole.pcm.size) {
            val j = i + shift * 2
            if (j < 0 || j >= other.pcm.size) continue
            val d = (whole.pcm[i] - other.pcm[j]).toDouble()
            err += d * d
            sig += whole.pcm[i].toDouble() * whole.pcm[i]
        }
        val db = 10 * log10(err / sig + 1e-30)
        println("stretched, from a copy $offset frames in: %.1f dB".format(db))
        assertTrue("%.1f dB".format(db), db < -60)
    }

    @Test
    fun `a change on parts is heard on bands, and copies in two formats are refused`() {
        val parts = Presets.apply(spec(Preset.FADE).copy(lanes = LaneSet.PARTS, moves = emptyMap()), Preset.VOCALS_OVER)
        val window = Preview.render(parts, Copy(songA.samples, rate, 2), Copy(songB.samples, rate, 2)).getOrThrow()
        assertEquals(LaneSet.BANDS, window.plan.spec.lanes)
        val other = Preview.render(parts, Copy(songA.samples, rate, 2), Copy(songB.samples, 48_000, 2))
        assertTrue(other.isFailure)
    }
}
