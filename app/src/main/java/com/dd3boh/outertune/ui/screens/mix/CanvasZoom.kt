/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.max
import kotlin.math.min

/** A stretch of the change's bars: from [start], [span] bars wide. */
data class TimeView(val start: Double, val span: Double) {
    val end: Double get() = start + span

    operator fun contains(u: Double): Boolean = u >= start && u <= end
}

/**
 * How far the canvas is zoomed and where it looks (his ask of 9 Nov 2026: "let me zoom in/out so
 * i'm not limited by the number of bars or seconds"), as audio editors and DJ apps zoom a waveform:
 * a pinch zooms about the fingers and two fingers scroll, the zoom buttons halve or double the view
 * about the playhead (Audacity's Zoom In and Out, Mixxx and Serato keeping the playhead in place),
 * and the overview scrolls (Ableton's and Mixxx's overview). From everything the editor read of the
 * two songs ([whole]) down to a few beats ([minSpan]): no count of bars or seconds is fixed. Pure.
 */
object CanvasZoom {
    /** What a canvas shows: everything it may show, its narrowest, what it shows now, and whether that is the fit. */
    class Frame(val whole: TimeView, val minSpan: Double, val shown: TimeView, val atFit: Boolean)

    /** [editor]'s canvas for [spec] with the view [start], [span] asked for (NaN: the fit). */
    fun frame(editor: MixEditor, spec: TransitionSpec, start: Double, span: Double): Frame {
        val fitStart = editor.fitStart(spec)
        val fitEnd = editor.fitEnd(spec)
        val whole = whole(fitStart, fitEnd, editor.aWallU(spec), editor.bWallU(spec))
        val minSpan = minSpan(spec)
        val atFit = span.isNaN() || start.isNaN()
        val shown = if (atFit) TimeView(fitStart, fitEnd - fitStart) else clamp(TimeView(start, span), whole, minSpan)
        return Frame(whole, minSpan, shown, atFit)
    }

    /** One tap of a zoom button: twice as close, or twice as far. */
    const val STEP = 2.0

    /** The closest view: this many beats across. */
    const val FEWEST_BEATS = 2

    /** Where the change is timed in seconds, a second across. */
    const val FEWEST_MS = 1_000.0

    /** A double tap on the ruler zooms this much closer than the fit, about the bar tapped, and back. */
    const val DOUBLE_TAP = 3.0

    /**
     * Everything the editor has of the two songs, in bars of the change: from where A was read
     * ([aWallU]) to where B was read to ([bWallU]), the fit view always inside.
     */
    fun whole(fitStart: Double, fitEnd: Double, aWallU: Double, bWallU: Double): TimeView {
        val lo = if (aWallU.isFinite()) min(fitStart, aWallU) else fitStart
        val hi = if (bWallU.isFinite()) max(fitEnd, bWallU) else fitEnd
        return TimeView(lo, hi - lo)
    }

    /** The narrowest view, in bars: [FEWEST_BEATS] beats, or [FEWEST_MS] where the change is timed in seconds. */
    fun minSpan(spec: TransitionSpec): Double =
        if (spec.bars == 0) FEWEST_MS / spec.b.barMs else FEWEST_BEATS * spec.beat

    /** [view] no narrower than [minSpan], no wider than [whole], and inside it. */
    fun clamp(view: TimeView, whole: TimeView, minSpan: Double): TimeView {
        val span = view.span.coerceIn(min(minSpan, whole.span), whole.span)
        val start = view.start.coerceIn(whole.start, whole.end - span)
        return TimeView(start, span)
    }

    /**
     * [view] [factor] times closer (above 1) or further (below 1) about bar [at], which keeps its
     * place across the canvas where the bounds allow.
     */
    fun zoom(view: TimeView, factor: Double, at: Double, whole: TimeView, minSpan: Double): TimeView {
        val f = ((at - view.start) / view.span).coerceIn(0.0, 1.0)
        val span = (view.span / factor).coerceIn(min(minSpan, whole.span), whole.span)
        return clamp(TimeView(at - f * span, span), whole, minSpan)
    }

    /**
     * Two fingers: the view [scale] times closer than [span0] as the fingers spread, with bar [u0],
     * which was under their middle when they came down, under their middle now, at [fraction] of
     * the canvas's width. Moving both fingers together scrolls.
     */
    fun pinch(span0: Double, u0: Double, fraction: Double, scale: Double, whole: TimeView, minSpan: Double): TimeView {
        val span = (span0 / scale).coerceIn(min(minSpan, whole.span), whole.span)
        return clamp(TimeView(u0 - fraction * span, span), whole, minSpan)
    }

    /** [view] moved by [du] bars, inside [whole]. */
    fun scroll(view: TimeView, du: Double, whole: TimeView, minSpan: Double): TimeView =
        clamp(TimeView(view.start + du, view.span), whole, minSpan)

    /** The bar the zoom buttons zoom about: the playhead where it is in view, the middle of the view otherwise. */
    fun anchor(view: TimeView, playU: Double): Double = if (playU.isFinite() && playU in view) playU else view.start + view.span / 2

    /** Whether a zoom button can still do something, either way. */
    fun canZoomIn(view: TimeView, minSpan: Double, whole: TimeView): Boolean = view.span > min(minSpan, whole.span) * (1 + 1e-6)
    fun canZoomOut(view: TimeView, whole: TimeView): Boolean = view.span < whole.span * (1 - 1e-6)

    /**
     * Bars between two lines or labels, as a DAW's grid thins out as it zooms out (Ableton's adaptive
     * grid): 1, 2, 4, 8... the first at least [minPx] apart at [pxPerBar].
     */
    fun barStep(pxPerBar: Double, minPx: Double): Int {
        var step = 1
        while (step * pxPerBar < minPx && step < MAX_BAR_STEP) step *= 2
        return step
    }

    /** Seconds between two labels on a ruler timed in seconds: 1, 2, 5, 10, 15, 30, 60, the first at least [minPx] apart. */
    fun secondsStep(pxPerSecond: Double, minPx: Double): Int =
        SECOND_STEPS.firstOrNull { it * pxPerSecond >= minPx } ?: SECOND_STEPS.last()

    private const val MAX_BAR_STEP = 1 shl 10
    private val SECOND_STEPS = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300)
}
