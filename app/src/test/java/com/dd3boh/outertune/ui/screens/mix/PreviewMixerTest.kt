/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.abs

/**
 * What the preview plays, on two made-up songs: A holds 1000 on both channels, B 2000, so who is
 * heard and how loud reads straight off the samples.
 */
class PreviewMixerTest {

    private val rate = 48_000
    private val gridA = SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111)
    private val gridB = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109)

    private fun window(startMs: Double, seconds: Int, value: Short, durationMs: Double): PcmWindow {
        val frames = seconds * rate
        val samples = ShortBuffer.allocate(frames * 2)
        repeat(frames * 2) { samples.put(it, value) }
        return PcmWindow(WindowInfo(rate, 2, startMs, frames.toLong(), durationMs), samples)
    }

    private val a = window(236_000.0, 72, 1000, 308_000.0)
    private val b = window(0.0, 60, 2000, 361_000.0)
    private val facts = PairFacts(
        a = SongFacts("a", "A", 308_000.0, 236_000.0, 308_000.0, gridA, focusMs = 290_000.0),
        b = SongFacts("b", "B", 361_000.0, 0.0, 60_000.0, gridB, firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    private fun at(mixer: PreviewMixer, editor: MixEditor, u: Double): Pair<Double, Double> {
        val levels = DoubleArray(2)
        mixer.levels(editor.spec, u, levels)
        mixer.outL = 0.0
        mixer.outR = 0.0
        mixer.frame(editor.spec, u, levels[0], levels[1])
        return mixer.outL to mixer.outR
    }

    @Test
    fun `before the change only A plays, after it only B`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        val m = PreviewMixer(a, b)
        assertEquals(1000.0, at(m, e, -1.0).first, 1e-6)
        assertEquals(2000.0, at(m, e, e.spec.lengthBars + 1).first, 1e-6)
    }

    @Test
    fun `in the middle of a fade both are heard, neither at full`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        e.tapPreset(Preset.BLEND)
        val m = PreviewMixer(a, b)
        val (l, _) = at(m, e, e.spec.lengthBars / 2)
        assertTrue("$l", l > 1000.0 && l < 3000.0)
    }

    @Test
    fun `a plain change plays A to its end, then B from its start`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        e.plainChange()
        val m = PreviewMixer(a, b)
        val endU = (308_000.0 - e.spec.a.atMs) / e.perA()
        assertEquals(1000.0, at(m, e, endU - 0.1).first, 1e-6)
        assertEquals(2000.0, at(m, e, endU + 0.1).first, 1e-6)
    }

    @Test
    fun `a lane alone plays only its song, at that lane's level`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        val m = PreviewMixer(a, b)
        m.solo = LaneId(Side.B, Lane.LOWS)
        assertEquals(0.0, at(m, e, -1.0).first, 1e-6)
        assertEquals(2000.0, at(m, e, e.spec.lengthBars).first, 1e-6)
    }

    @Test
    fun `the click marks every beat of the song, and a higher one on the 1`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        val m = PreviewMixer(a, b)
        m.click = Side.B
        val out = ShortArray(2 * rate)
        m.render(e.spec, 0.0, rate, rate, out)
        // A beat at 109 BPM is 550 ms: the click sits on the first 25 ms of each.
        val onBeat = abs(out[2 * 240].toInt() - 1400)
        val offBeat = abs(out[2 * 12_000].toInt() - 1400)
        assertTrue("$onBeat", onBeat > 100)
        assertEquals(0, offBeat)
    }

    @Test
    fun `a rendered block goes through the same frames the preview plays`() {
        val e = MixEditor(facts, AutoSpec.fallback(facts), null)
        val m = PreviewMixer(a, b)
        val out = ShortArray(2 * 4800)
        m.render(e.spec, -1.0, rate, 4800, out)
        assertTrue(out.all { it == 1000.toShort() })
    }
}
