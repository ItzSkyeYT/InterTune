/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.playback.EqualizerAudioProcessor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The equalizer as people know one: a column per band, its knob raised or lowered on a track, the
 * dB above and the frequency below, a line at nought and the curve drawn through the knobs.
 *
 * A knob is taken by the finger that lands on it and follows it up and down a dB at a time. A
 * finger that lands elsewhere on the graph is left to the page, so a swipe over it scrolls rather
 * than moving a band by accident. Screen readers reach every band as a slider of its own.
 */
@Composable
fun EqualizerGraph(
    gainsDb: List<Float>,
    enabled: Boolean,
    onBand: (band: Int, db: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxDb = EqualizerAudioProcessor.MAX_DB
    val gains by rememberUpdatedState(gainsDb)
    val change by rememberUpdatedState(onBand)
    val scheme = MaterialTheme.colorScheme
    val accent = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f)
    val track = scheme.onSurface.copy(alpha = 0.12f)
    val grid = scheme.onSurface.copy(alpha = 0.07f)
    val zero = scheme.onSurface.copy(alpha = 0.22f)
    val knobCentre = scheme.surface
    val density = LocalDensity.current
    // Room above and below the tracks, so a knob at either end is drawn whole.
    val inset = with(density) { 14.dp.toPx() }
    // How near the knob a finger has to land to take it.
    val reach = with(density) { 28.dp.toPx() }
    val labelColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurface.copy(alpha = 0.38f)

    fun yOf(db: Float, height: Float): Float = inset + (maxDb - db) / (2 * maxDb) * (height - 2 * inset)
    fun dbAt(y: Float, height: Float): Float {
        val t = ((y - inset) / (height - 2 * inset)).coerceIn(0f, 1f)
        return (maxDb - t * 2 * maxDb).roundToInt().toFloat()
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            for (db in gainsDb) {
                Text(dbText(db), style = MaterialTheme.typography.labelSmall, color = labelColor,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .drawBehind {
                    val bands = gains.size
                    if (bands == 0) return@drawBehind
                    val column = size.width / bands
                    val thin = 1.dp.toPx()
                    for (g in listOf(-maxDb, -maxDb / 2, maxDb / 2, maxDb)) {
                        drawLine(grid, Offset(0f, yOf(g, size.height)), Offset(size.width, yOf(g, size.height)), strokeWidth = thin)
                    }
                    drawLine(zero, Offset(0f, yOf(0f, size.height)), Offset(size.width, yOf(0f, size.height)), strokeWidth = thin * 2)
                    val trackWidth = 4.dp.toPx()
                    gains.forEachIndexed { i, db ->
                        val x = column * (i + 0.5f)
                        drawLine(track, Offset(x, inset), Offset(x, size.height - inset), strokeWidth = trackWidth, cap = StrokeCap.Round)
                        drawLine(accent, Offset(x, yOf(0f, size.height)), Offset(x, yOf(db, size.height)), strokeWidth = trackWidth, cap = StrokeCap.Round)
                    }
                    val curve = Path()
                    gains.forEachIndexed { i, db ->
                        val x = column * (i + 0.5f)
                        val y = yOf(db, size.height)
                        if (i == 0) curve.moveTo(x, y) else {
                            val px = column * (i - 0.5f)
                            val py = yOf(gains[i - 1], size.height)
                            val mid = (px + x) / 2
                            curve.cubicTo(mid, py, mid, y, x, y)
                        }
                    }
                    drawPath(curve, accent.copy(alpha = 0.45f), style = Stroke(width = 2.dp.toPx()))
                    gains.forEachIndexed { i, db ->
                        val centre = Offset(column * (i + 0.5f), yOf(db, size.height))
                        drawCircle(accent, radius = 10.dp.toPx(), center = centre)
                        drawCircle(knobCentre, radius = 4.dp.toPx(), center = centre)
                    }
                }
        ) {
            for (band in gainsDb.indices) {
                val name = spokenBandName(EqualizerAudioProcessor.BANDS[band])
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = name
                            progressBarRangeInfo = ProgressBarRangeInfo(gainsDb[band], -maxDb..maxDb, steps = (2 * maxDb).toInt() - 1)
                            if (enabled) setProgress { target ->
                                change(band, target.roundToInt().toFloat().coerceIn(-maxDb, maxDb))
                                true
                            }
                        }
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val height = size.height.toFloat()
                                val knob = yOf(gains[band], height)
                                // Not on the knob: the page keeps the gesture and scrolls.
                                if (abs(down.position.y - knob) > reach) return@awaitEachGesture
                                down.consume()
                                // Held where it was taken, so the knob does not jump to the finger.
                                val grip = down.position.y - knob
                                var last = gains[band]
                                while (true) {
                                    val moved = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    moved.consume()
                                    if (!moved.pressed) break
                                    val db = dbAt(moved.position.y - grip, height)
                                    if (db != last) {
                                        last = db
                                        change(band, db)
                                    }
                                }
                            }
                        }
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (hz in EqualizerAudioProcessor.BANDS) {
                Text(shortBandName(hz), style = MaterialTheme.typography.labelSmall, color = labelColor,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "31", "1k", "16k": what fits under a column. */
private fun shortBandName(hz: Double): String = if (hz >= 1000) "${(hz / 1000).roundToInt()}k" else "${hz.roundToInt()}"

/** "31 Hz", "1 kHz": what a screen reader says for a band. */
private fun spokenBandName(hz: Double): String = if (hz >= 1000) "${(hz / 1000).roundToInt()} kHz" else "${hz.roundToInt()} Hz"

private fun dbText(db: Float): String = db.roundToInt().let { if (it > 0) "+$it" else "$it" }
