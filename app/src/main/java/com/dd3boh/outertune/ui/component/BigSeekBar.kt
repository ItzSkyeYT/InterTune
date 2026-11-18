/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.Unreleased

@Composable
fun BigSeekBar(
    progressProvider: () -> Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Upper end of the range. Above 1f the bar can express gain past unity, and a marker is drawn
     * at the 1f point so it is obvious where normal ends and boost begins.
     */
    maxProgress: Float = 1f,
    background: Color = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.13f),
    color: Color = MaterialTheme.colorScheme.primary,
    /** Fill colour past unity. Deliberately distinct: boosting can distort and should look like it. */
    boostColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    var width by remember {
        mutableFloatStateOf(0f)
    }
    val haptic = LocalHapticFeedback.current

    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .onPlaced {
                width = it.size.width.toFloat()
            }
            .pointerInput(progressProvider) {
                // The drag's own value, apart from what the bar shows while the line holds it (VolumeSnap), and what
                // this drag last showed: the volume comes back through a flow a frame late, and must not tick twice.
                var dragged = Float.NaN
                var shown = Float.NaN
                detectHorizontalDragGestures(
                    onDragStart = { dragged = progressProvider(); shown = dragged },
                    onDragEnd = { dragged = Float.NaN; shown = Float.NaN },
                    onDragCancel = { dragged = Float.NaN; shown = Float.NaN },
                ) { _, dragAmount ->
                    // Drag distance maps to the whole range, so a wider range is not slower to
                    // traverse; it just packs more value into the same sweep.
                    val delta = dragAmount * 1.2f / width * maxProgress
                    if (!Unreleased.HAPTICS_MORE) {
                        onProgressChange((progressProvider() + delta).coerceIn(0f, maxProgress))
                        return@detectHorizontalDragGestures
                    }
                    // Near the line where boost begins the bar rests on it, with one tick as it lands.
                    val before = if (shown.isNaN()) progressProvider() else shown
                    dragged = ((if (dragged.isNaN()) before else dragged) + delta).coerceIn(0f, maxProgress)
                    val now = VolumeSnap.shown(dragged, maxProgress)
                    if (VolumeSnap.lands(before, now)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    shown = now
                    onProgressChange(now)
                }
            }
    ) {
        drawRect(color = background)

        val value = progressProvider().coerceIn(0f, maxProgress)
        val unityX = size.width / maxProgress

        // Up to unity in the normal colour.
        drawRect(
            color = color,
            size = size.copy(width = size.width * (minOf(value, 1f) / maxProgress))
        )

        // Past unity in the boost colour, starting at the unity mark.
        if (value > 1f) {
            drawRect(
                color = boostColor,
                topLeft = Offset(unityX, 0f),
                size = size.copy(width = size.width * (value - 1f) / maxProgress)
            )
        }

        // The unity mark itself, so 100% is findable by feel rather than guesswork.
        if (maxProgress > 1f) {
            drawRect(
                color = background.copy(alpha = 0.9f),
                topLeft = Offset(unityX - 1.dp.toPx() / 2, 0f),
                size = size.copy(width = 1.dp.toPx())
            )
        }
    }
}