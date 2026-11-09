/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.BHeard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Where the canvas looks among all that is at hand of the two songs, as DJ apps and DAWs keep an
 * overview of the whole track (Mixxx, Serato, rekordbox, Ableton): A above the line, B below, the
 * change under a wash, the stretch the canvas shows framed, and the playhead. Dragging it across
 * scrolls the canvas; a tap brings the frame there. Under the length row, so it takes no room from
 * the canvas and zooming moves nothing on the page.
 */
@Composable
fun CanvasOverview(
    editor: MixEditor,
    spec: TransitionSpec,
    pair: LoadedPair?,
    state: CanvasState,
    colors: MixColors,
    playU: () -> Double,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bHeard = remember(spec) { BHeard(spec) }
    val frameNow = rememberUpdatedState(CanvasZoom.frame(editor, spec, state.start, state.span))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val dp = density.density
        val pad = 12 * dp
        val axis = width - 2 * pad
        val height = 16 * dp
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else 32 * dp
        val top = ((room - height) / 2).coerceAtLeast(0f)
        val f = frameNow.value
        fun xOf(u: Double, w: TimeView): Float = (pad + (u - w.start) / w.span * axis).toFloat()
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(editor, width) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = frameNow.value
                        val per = start.whole.span / axis
                        // A touch outside the frame brings the frame there, centred on it.
                        val at = start.whole.start + (down.position.x - pad) * per
                        val outside = at !in start.shown
                        val from = if (!outside) start.shown else
                            CanvasZoom.scroll(start.shown, at - (start.shown.start + start.shown.span / 2), start.whole, start.minSpan)
                        var moving = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!moving && outside) state.show(from)
                                break
                            }
                            val d = change.position - down.position
                            if (!moving) {
                                if (d.getDistance() < slop) continue
                                // Down scrolls the page; across moves the frame.
                                if (abs(d.y) > abs(d.x)) break
                                moving = true
                            }
                            change.consume()
                            state.show(CanvasZoom.scroll(from, d.x * per, start.whole, start.minSpan))
                        }
                    }
                },
        ) {
            val whole = f.whole
            drawRoundRect(colors.surfaceHigh, Offset(pad, top), Size(axis, height), CornerRadius(4 * dp))
            val centre = top + height / 2
            val a = pair?.a
            val b = pair?.b
            val bStart = editor.bStartU(spec)
            val aEnd = editor.aEndU(spec)
            val columns = (axis / (2 * dp)).toInt().coerceAtLeast(1)
            val du = whole.span / columns
            for (i in 0 until columns) {
                val u = whole.start + du * i
                val x = pad + i * 2 * dp
                if (a != null && u < aEnd) {
                    val v = a.bands.peak(Lane.MIDS, spec.aHeardMsAt(u) - a.pcm.info.startMs, spec.aHeardMsAt(u + du) - a.pcm.info.startMs) / a.bands.reference
                    val h = min(7 * dp, v * 10 * dp)
                    drawRect(colors.onSurfaceVariant.copy(alpha = 0.45f), Offset(x, centre - h), Size(1.5f * dp, h))
                }
                if (b != null && u + du > bStart) {
                    val v = b.bands.peak(Lane.MIDS, bHeard.msAt(u) - b.pcm.info.startMs, bHeard.msAt(u + du) - b.pcm.info.startMs) / b.bands.reference
                    val h = min(7 * dp, v * 10 * dp)
                    drawRect(colors.onSurfaceVariant.copy(alpha = 0.45f), Offset(x, centre), Size(1.5f * dp, h))
                }
            }
            // The change, under a wash as on the canvas.
            val c0 = xOf(0.0, whole).coerceIn(pad, pad + axis)
            val c1 = xOf(spec.lengthBars, whole).coerceIn(pad, pad + axis)
            drawRect(colors.onSurface.copy(alpha = 0.08f), Offset(c0, top), Size(c1 - c0, height))
            // The stretch the canvas shows.
            val s = f.shown
            val wx = xOf(s.start, whole)
            val ww = (s.span / whole.span * axis).toFloat().coerceAtLeast(2 * dp)
            drawRect(colors.primary.copy(alpha = 0.16f), Offset(wx, top), Size(ww, height))
            drawRect(colors.primary, Offset(wx + 0.5f, top + 0.5f), Size(ww - 1, height - 1), style = Stroke(1f))
        }
        // The playhead, on its own so playing redraws only this.
        Canvas(Modifier.fillMaxSize()) {
            val whole = frameNow.value.whole
            val u = playU()
            if (!u.isFinite() || u < whole.start || u > whole.end) return@Canvas
            val x = xOf(u, whole)
            drawRect(colors.primary, Offset(x - 0.75f * dp, top - 2 * dp), Size(1.5f * dp, height + 4 * dp))
        }
    }
}

/**
 * The two zoom buttons, for one hand, as an audio editor's Zoom Out and Zoom In: each tap twice as
 * far or twice as close, about the playhead where it is in view and about the middle otherwise.
 * They keep to the room [BoxWithConstraints] gives them and are left out where there is none, so
 * nothing beside them moves.
 */
@Composable
fun ZoomButtons(editor: MixEditor, spec: TransitionSpec, state: CanvasState, playU: () -> Double, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val glide = remember { arrayOfNulls<Job>(1) }
    val f = CanvasZoom.frame(editor, spec, state.start, state.span)
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        if (maxWidth < 80.dp) return@BoxWithConstraints
        fun zoom(factor: Double) {
            glide[0]?.cancel()
            val from = CanvasZoom.frame(editor, spec, state.start, state.span)
            val at = CanvasZoom.anchor(from.shown, playU())
            val to = CanvasZoom.zoom(from.shown, factor, at, from.whole, from.minSpan)
            // A short glide, the span on a log scale so it moves evenly, the anchor where it was.
            val fraction = ((at - from.shown.start) / from.shown.span).coerceIn(0.0, 1.0)
            glide[0] = scope.launch {
                Animatable(0f).animateTo(1f, tween(ZOOM_GLIDE_MS)) {
                    val span = from.shown.span * (to.span / from.shown.span).pow(value.toDouble())
                    state.show(CanvasZoom.clamp(TimeView(at - fraction * span, span), from.whole, from.minSpan))
                }
                state.show(to)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { zoom(1 / CanvasZoom.STEP) }, enabled = CanvasZoom.canZoomOut(f.shown, f.whole), modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ZoomOut, stringResource(R.string.mix_edit_zoom_out))
            }
            IconButton(onClick = { zoom(CanvasZoom.STEP) }, enabled = CanvasZoom.canZoomIn(f.shown, f.minSpan, f.whole), modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ZoomIn, stringResource(R.string.mix_edit_zoom_in))
            }
        }
    }
}

private const val ZOOM_GLIDE_MS = 160
