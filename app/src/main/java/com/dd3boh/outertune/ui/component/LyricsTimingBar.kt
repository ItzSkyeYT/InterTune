/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.lyrics.LyricsTiming
import kotlin.math.roundToLong

/**
 * The bar the lyrics view shows while its timing is being set, where its own buttons were, so the
 * lines stay in sight and the highlight can be matched to the singing by ear: how far the lyrics
 * are moved, a slider of ±5 s, a tenth of a second either way for the last bit, Reset, and Done.
 * Every change is [onShift] at once, so the lines move as the slider does.
 */
@Composable
fun LyricsTimingBar(shiftMs: Long, onShift: (Long) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    // The slider's own position while a finger drags it, so it moves smoothly between the steps
    // the saved shift snaps to.
    var dragged by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragged.isNaN()) shiftMs else LyricsTiming.snap(dragged.roundToLong())
    val haptic = LocalHapticFeedback.current

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
                    text = when {
                        shown == 0L -> stringResource(R.string.lyrics_timing_original)
                        shown < 0L -> stringResource(R.string.lyrics_timing_earlier, LyricsTiming.seconds(shown))
                        else -> stringResource(R.string.lyrics_timing_later, LyricsTiming.seconds(shown))
                    },
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
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { step(LyricsTiming.STEP_MS) }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.lyrics_timing_step_later))
                }
            }
        }
    }
}
