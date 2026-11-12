/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Simple level's waveforms frame the change, not the whole stretch of A a change may use: his
 * report of 7 Nov 2026 from his S25U (1440 px wide), Am I Dreaming at 90 BPM into Starboy at 93,
 * a 2-bar change at A's end, drawn in the last twelfth of the width with Starboy after it a sliver.
 */
class SimpleWaveViewTest {

    private val gridA = SongGrid(t0Ms = 410.0, periodMs = 60_000.0 / 90)
    private val gridB = SongGrid(t0Ms = 520.0, periodMs = 60_000.0 / 93)

    private fun facts() = PairFacts(
        a = SongFacts("am-i-dreaming", "Am I Dreaming", 256_000.0, 150_000.0, 256_000.0, gridA, focusMs = 240_000.0),
        b = SongFacts("starboy", "Starboy", 230_000.0, 0.0, 60_000.0, gridB, firstSoundMs = 520.0, focusMs = 520.0),
    )

    /** The S25U's waveform box: 1440 px less the sheet's 16 dp either side, at 3.5 px a dp. */
    private val dp = 3.5f
    private val width = 1440f - 2 * 16 * dp
    private val height = 120 * dp

    /** The editor with a 2-bar change moved as late in A as it goes. */
    private fun atTheEnd(): MixEditor {
        val e = MixEditor(facts(), AutoSpec.fallback(facts()), null)
        assertTrue(e.setLength(2) || e.spec.bars == 2)
        // A finger's drag to the right: a later moment of A comes to bar 1.
        val (late, _) = e.anchorMoved(e.spec, Side.A, -60_000.0, byBeats = false)
        e.apply(late)
        return e
    }

    /** The share of the width the change's window takes, and the bars of the change's tempo in view after it. */
    private fun shares(frame: WaveFrame, spec: TransitionSpec): Pair<Double, Double> {
        val x0 = frame.xOfU(0.0)
        val x1 = frame.xOfU(spec.lengthBars)
        val bar = frame.xOfU(1.0) - frame.xOfU(0.0)
        return (x1 - x0).toDouble() / (frame.width - 2 * frame.pad) to (frame.width - frame.pad - x1).toDouble() / bar
    }

    @Test
    fun `a 2-bar change at the end of A takes a fair share of the width, with bars of B after it`() {
        val e = atTheEnd()
        val s = e.spec
        assertEquals(2, s.bars)
        // At A's end: its last bar line, no later one fits.
        assertTrue(s.aMsAt(2.0) <= 256_000.0 && s.aMsAt(3.0) > 256_000.0)
        // As it was drawn: from the earliest of A a change may use to past A's end.
        val before = WaveView(e.facts.aReachStartMs, e.facts.a.durationMs + (s.lengthBars / 2 + 1) * e.perA(s) - e.facts.aReachStartMs)
        val (oldShare, oldAfter) = shares(WaveFrame(e, s, before, width, height, dp), s)
        val (share, after) = shares(WaveFrame(e, s, WaveView.of(e, s, null), width, height, dp), s)
        println("before: the change %.1f%% of the width, %.2f bars after it; now %.1f%%, %.2f bars".format(oldShare * 100, oldAfter, share * 100, after))
        assertTrue("$oldShare", oldShare < 0.1)
        // Now a quarter of the width, two bars of A before it and three of B after it.
        assertEquals(0.25, share, 0.01)
        assertTrue("$after", after >= 2.5)
        val view = WaveView.of(e, s, null)
        assertTrue(view.startMs <= s.aMsAt(-2.0) + 1e-6)
    }

    @Test
    fun `a long change is framed with two bars either side, as Advanced frames it`() {
        val e = atTheEnd()
        assertTrue(e.setLength(16))
        val s = e.spec
        val view = WaveView.of(e, s, null)
        assertEquals(s.aMsAt(-2.0), view.startMs, 1e-6)
        assertEquals(s.aMsAt(18.0), view.endMs, 1e-6)
        val (share, after) = shares(WaveFrame(e, s, view, width, height, dp), s)
        assertEquals(16.0 / 20, share, 0.01)
        assertEquals(2.0, after, 0.05)
    }

    @Test
    fun `the view stays while a drag keeps the change clear of its edges, and follows it to one`() {
        val e = atTheEnd()
        val start = e.spec
        val view = WaveView.of(e, start, null)
        val bar = e.perA(start)
        // A bar earlier: still clear of the left edge by a bar, the same view.
        val (one, _) = e.anchorMoved(start, Side.A, bar, byBeats = false)
        assertSame(view, WaveView.of(e, one, view))
        // Three bars earlier, the change would come within a bar of the edge: the view follows it.
        val (three, _) = e.anchorMoved(start, Side.A, 3 * bar, byBeats = false)
        val followed = WaveView.of(e, three, view)
        assertEquals(view.spanMs, followed.spanMs, 0.0)
        assertEquals(three.aMsAt(0.0) - WaveView.EDGE_BARS * bar, followed.startMs, 1e-6)
        // Back towards the end, step by step as a drag goes: the view comes back with it, never past the change.
        var v = followed
        for (k in 3 downTo 0) {
            val (s, _) = e.anchorMoved(start, Side.A, k * bar, byBeats = false)
            v = WaveView.of(e, s, v)
            assertTrue(s.aMsAt(0.0) >= v.startMs + bar - 1e-6)
            assertTrue(s.aMsAt(s.lengthBars) <= v.endMs - bar + 1e-6)
        }
        // A change moved far from the view (Back to Auto, an undo) is framed afresh around it.
        val (far, _) = e.anchorMoved(start, Side.A, 40_000.0, byBeats = false)
        assertEquals(WaveView.of(e, far, null).startMs, WaveView.of(e, far, view).startMs, 1e-6)
        // Another length lays it out afresh too.
        val longer = start.copy(bars = 4)
        assertEquals(WaveView.of(e, longer, null).spanMs, WaveView.of(e, longer, view).spanMs, 1e-6)
    }

    @Test
    fun `the view never starts before what was read of A`() {
        // A read only from 4 s before the change: the view starts there, the change still clear of both edges.
        val f = facts()
        val e = MixEditor(f, AutoSpec.fallback(f), null)
        val s = e.spec
        val tight = PairFacts(SongFacts("am-i-dreaming", "Am I Dreaming", 256_000.0, s.aMsAt(-2.0) - 500.0, 256_000.0, gridA, focusMs = 240_000.0), f.b)
        val t = MixEditor(tight, AutoSpec.fallback(tight), s)
        val view = WaveView.of(t, t.spec, null)
        assertTrue(view.startMs >= tight.a.windowStartMs - 1e-6)
        assertTrue(t.spec.aMsAt(t.spec.lengthBars) <= view.endMs)
    }
}
