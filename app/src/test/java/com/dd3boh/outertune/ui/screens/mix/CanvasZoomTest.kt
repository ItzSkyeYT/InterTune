/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His ask of 9 Nov 2026: "in the mixer menu, in advanced, let me zoom in/out so i'm not limited by
 * the number of bars or seconds". The canvas zooms from everything read of the two songs down to a
 * few beats, whatever the change's length; about the fingers, or the playhead for the buttons; and
 * nothing on the page moves for it.
 */
class CanvasZoomTest {

    private val gridA = SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111)
    private val gridB = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109)
    private val facts = PairFacts(
        a = SongFacts("wake-me-up", "Wake Me Up", 308_000.0, 236_000.0, 308_000.0, gridA, focusMs = 290_000.0),
        b = SongFacts("after-hours", "After Hours", 361_000.0, 0.0, 60_000.0, gridB, firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    private fun editor(bars: Int = 8) = MixEditor(facts, AutoSpec.fallback(facts), null).also { it.setLength(bars) }

    @Test
    fun `the widest view is all that was read of both songs, the fit inside it`() {
        val e = editor()
        val s = e.spec
        val f = CanvasZoom.frame(e, s, Double.NaN, Double.NaN)
        assertTrue(f.atFit)
        assertEquals(e.fitStart(s), f.shown.start, 0.0)
        assertEquals(e.fitEnd(s), f.shown.end, 1e-9)
        assertEquals(e.aWallU(s), f.whole.start, 1e-9)
        assertEquals(e.bWallU(s), f.whole.end, 1e-9)
        // 72 s of the song before and 60 s of the next: far more than the change and 2 bars either side.
        println("fit %.1f bars, all that is at hand %.1f bars".format(f.shown.span, f.whole.span))
        assertTrue(f.whole.span > 3 * f.shown.span)
    }

    @Test
    fun `zooming in stops at a few beats, whatever the change's length`() {
        for (bars in listOf(2, 16)) {
            val e = editor(bars)
            val s = e.spec
            var f = CanvasZoom.frame(e, s, Double.NaN, Double.NaN)
            var v = f.shown
            var taps = 0
            while (CanvasZoom.canZoomIn(v, f.minSpan, f.whole)) {
                v = CanvasZoom.zoom(v, CanvasZoom.STEP, 1.0, f.whole, f.minSpan)
                taps++
                assertTrue(taps < 20)
            }
            assertEquals("$bars bars", CanvasZoom.FEWEST_BEATS * s.beat, v.span, 1e-9)
            assertTrue(1.0 in v)
            // Back out to all of it.
            while (CanvasZoom.canZoomOut(v, f.whole)) v = CanvasZoom.zoom(v, 1 / CanvasZoom.STEP, 1.0, f.whole, f.minSpan)
            assertEquals(f.whole, v)
        }
    }

    @Test
    fun `timed in seconds, the closest view is a second`() {
        val e = editor()
        assertTrue(e.setLength(0, 8.0))
        val s = e.spec
        assertEquals(0, s.bars)
        assertEquals(1_000.0, CanvasZoom.minSpan(s) * s.b.barMs, 1e-6)
    }

    @Test
    fun `a zoom keeps the bar it is about where it was on the canvas`() {
        val whole = TimeView(-30.0, 60.0)
        val v = TimeView(-2.0, 12.0)
        val at = 1.0
        val before = (at - v.start) / v.span
        val closer = CanvasZoom.zoom(v, 2.0, at, whole, 0.5)
        assertEquals(6.0, closer.span, 1e-9)
        assertEquals(before, (at - closer.start) / closer.span, 1e-9)
        val further = CanvasZoom.zoom(v, 0.5, at, whole, 0.5)
        assertEquals(24.0, further.span, 1e-9)
        assertEquals(before, (at - further.start) / further.span, 1e-9)
    }

    @Test
    fun `two fingers zoom about their middle and scroll as they move together`() {
        val whole = TimeView(-30.0, 60.0)
        // Spread twice as wide, the middle still: half the bars, the bar between them still there.
        val spread = CanvasZoom.pinch(12.0, 1.0, 0.25, 2.0, whole, 0.5)
        assertEquals(6.0, spread.span, 1e-9)
        assertEquals(0.25, (1.0 - spread.start) / spread.span, 1e-9)
        // The same distance apart, the middle moved to three quarters across: a scroll, same span.
        val moved = CanvasZoom.pinch(12.0, 1.0, 0.75, 1.0, whole, 0.5)
        assertEquals(12.0, moved.span, 1e-9)
        assertEquals(0.75, (1.0 - moved.start) / moved.span, 1e-9)
        // Pinched far past the ends: all of it, never beyond.
        assertEquals(whole, CanvasZoom.pinch(12.0, 1.0, 0.5, 0.01, whole, 0.5))
        assertEquals(0.5, CanvasZoom.pinch(12.0, 1.0, 0.5, 1000.0, whole, 0.5).span, 1e-9)
    }

    @Test
    fun `scrolling stops at the ends of what was read`() {
        val whole = TimeView(-30.0, 60.0)
        val v = TimeView(0.0, 10.0)
        assertEquals(TimeView(-30.0, 10.0), CanvasZoom.scroll(v, -100.0, whole, 0.5))
        assertEquals(TimeView(20.0, 10.0), CanvasZoom.scroll(v, 100.0, whole, 0.5))
        assertEquals(TimeView(3.0, 10.0), CanvasZoom.scroll(v, 3.0, whole, 0.5))
    }

    @Test
    fun `the buttons zoom about the playhead where it is in view, about the middle otherwise`() {
        val v = TimeView(0.0, 8.0)
        assertEquals(3.0, CanvasZoom.anchor(v, 3.0), 0.0)
        assertEquals(4.0, CanvasZoom.anchor(v, 12.0), 0.0)
        assertEquals(4.0, CanvasZoom.anchor(v, Double.NaN), 0.0)
    }

    @Test
    fun `the grid and the ruler thin out as the view widens`() {
        assertEquals(1, CanvasZoom.barStep(18.5, 6.0))
        assertEquals(2, CanvasZoom.barStep(5.0, 6.0))
        assertEquals(8, CanvasZoom.barStep(1.0, 6.0))
        assertEquals(2, CanvasZoom.secondsStep(18.5, 30.0))
        assertEquals(5, CanvasZoom.secondsStep(7.0, 30.0))
        assertEquals(30, CanvasZoom.secondsStep(1.2, 30.0))
    }

    @Test
    fun `the canvas draws the view asked for and keeps its height at every zoom`() {
        val e = editor()
        val s = e.spec
        val density = Density(2.75f)
        val fit = CanvasLayout(e, s, 1080f, density, view = 0f, viewStart = Double.NaN, viewSpan = Double.NaN, adjust = null, small = false)
        val close = CanvasLayout(e, s, 1080f, density, view = 0f, viewStart = 2.0, viewSpan = 1.0, adjust = null, small = false)
        val far = CanvasLayout(e, s, 1080f, density, view = 0f, viewStart = -1000.0, viewSpan = 1000.0, adjust = null, small = false)
        assertEquals(fit.total, close.total, 0f)
        assertEquals(fit.total, far.total, 0f)
        assertEquals(2.0, close.start, 1e-9)
        assertEquals(close.axis / 1.0, close.ppb, 1e-9)
        assertFalse(close.atFit)
        // Asked for more than is at hand: all of it.
        assertEquals(far.whole, far.shown)
        // A bar 2 to 3 fills the canvas: bar 2.5 is in its middle.
        assertEquals(close.pad + close.axis / 2, close.x(2.5), 0.01f)
    }

    /** Nothing else moves: the overview left the canvas for the line under the length row, and the buttons take the row's free middle. */
    @Test
    fun `the zoom's controls take no room from the page`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorScreen.kt").readText()
        assertTrue("zoom(Modifier.weight(1f)" in screen)
        assertTrue("if (text.isEmpty() && overview != null) Box(Modifier.matchParentSize())" in screen)
        val canvas = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixCanvas.kt").readText()
        assertFalse("overview = if (zoom" in canvas)
    }
}
