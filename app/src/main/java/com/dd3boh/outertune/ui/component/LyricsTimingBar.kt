/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.lyrics.LyricsTiming
import kotlin.math.roundToLong

/**
 * The bar the lyrics view shows while its timing is being set, where its own buttons were, so the
 * lines stay in sight and the highlight can be matched to the singing by ear: how far the lyrics
 * are moved, a slider of ±5 s that starts in its middle, a tenth of a second either way with − and
 * + for the last bit, Reset, and Done.
 * Every change is [onShift] at once, so the lines move as the slider does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsTimingBar(shiftMs: Long, onShift: (Long) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    // The slider's own position while a finger drags it, so it moves smoothly between the steps
    // the saved shift snaps to.
    var dragged by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragged.isNaN()) shiftMs else LyricsTiming.snap(dragged.roundToLong())
    val haptic = LocalHapticFeedback.current
    val sliding = remember { MutableInteractionSource() }
    val said = when {
        shown == 0L -> stringResource(R.string.lyrics_timing_original)
        shown < 0L -> stringResource(R.string.lyrics_timing_earlier, LyricsTiming.seconds(shown))
        else -> stringResource(R.string.lyrics_timing_later, LyricsTiming.seconds(shown))
    }

    fun step(by: Long) {
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onShift(LyricsTiming.snap(shiftMs + by))
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = said,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (shown != 0L) TextButton(onClick = { onShift(0L) }) { Text(stringResource(R.string.reset)) }
                IconButton(onClick = onDone) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.action_done))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { step(-LyricsTiming.STEP_MS) }) {
                    Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.lyrics_timing_step_earlier))
                }
                Slider(
                    value = if (dragged.isNaN()) shiftMs.toFloat() else dragged,
                    onValueChange = {
                        dragged = it
                        val now = LyricsTiming.snap(it.roundToLong())
                        // A tick where the lyrics are back as they came, so the start is found by feel.
                        if (now == 0L && shown != 0L) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onShift(now)
                    },
                    onValueChangeFinished = { dragged = Float.NaN },
                    valueRange = -LyricsTiming.MOST_MS.toFloat()..LyricsTiming.MOST_MS.toFloat(),
                    // The player's seek bar's thumb, over a track filled from its middle.
                    interactionSource = sliding,
                    thumb = { SliderDefaults.Thumb(interactionSource = sliding, thumbSize = DpSize(4.dp, 36.dp)) },
                    track = { CentredTrack(it) },
                    // TalkBack reads the bar's own words, not a percentage of ten seconds.
                    modifier = Modifier.weight(1f).semantics { stateDescription = said },
                )
                IconButton(onClick = { step(LyricsTiming.STEP_MS) }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.lyrics_timing_step_later))
                }
            }
        }
    }
}

/**
 * The slider's track filled from its middle, where the lyrics are as they came, out to the thumb, so
 * how far they are moved shows as a length either way. Material's own track fills from the left
 * end, which put "as they came" at half full, and its empty part was the bar's own colour, so half
 * the track could not be seen. Sized and cut round the thumb like the player's seek bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CentredTrack(state: SliderState) {
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.22f)
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val range = state.valueRange
        val thumb = size.width * ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val middle = size.width / 2
        val end = size.height / 2
        val inside = 4.dp.toPx()
        // Half the thumb's 4dp and the seek bar's 5dp gap, clear either side of it.
        val clear = 7.dp.toPx()
        piece(0f, thumb - clear, empty, end, inside)
        piece(thumb + clear, size.width, empty, inside, end)
        if (thumb > middle) piece(middle, thumb - clear, filled, inside, inside)
        if (thumb < middle) piece(thumb + clear, middle, filled, inside, inside)
    }
}

/** A length of track from [from] to [to], its two ends rounded by [startRadius] and [endRadius]. */
private fun DrawScope.piece(from: Float, to: Float, color: Color, startRadius: Float, endRadius: Float) {
    if (to - from < 1f) return
    val start = CornerRadius(startRadius)
    val end = CornerRadius(endRadius)
    drawPath(Path().apply { addRoundRect(RoundRect(Rect(from, 0f, to, size.height), start, end, end, start)) }, color)
}
