/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The seek bar's own drag. The player sheet takes a drag that goes down a touch slop (22 px on the
 * Pixel 5) before the slider's own drag goes across one, so a thumb that went a little down first
 * moved the sheet and not the song (his word of 6 Nov 2026, "the playback bar is kinda annoying to
 * drag"; seen on the Pixel 5 on 7 Nov). This takes the finger after [CLAIM] of movement in any
 * direction, long before the sheet's slop, and moves the song to the point under the finger. Taking
 * the moves stops both the sheet and the slider's own drag (each gives up on a move taken by
 * another), so this one drives the slider: [onSlide] as it goes, [onSlideDone] when the finger
 * lifts. A tap, a finger that moves less than [CLAIM], is the slider's, as before.
 *
 * [trackInset] is where the slider's track starts and ends inside it: half its thumb's width. [rtl]:
 * the bar runs right to left, as the slider does in a right-to-left language.
 */
internal fun Modifier.seekDrag(duration: Long, trackInset: Dp, rtl: Boolean, onSlide: (Long) -> Unit, onSlideDone: () -> Unit): Modifier =
    pointerInput(duration, rtl) {
        if (duration <= 0) return@pointerInput
        val claimAt = CLAIM.toPx()
        val inset = trackInset.toPx()
        fun valueAt(x: Float): Long {
            val track = (size.width - 2 * inset).coerceAtLeast(1f)
            val f = ((x - inset) / track).coerceIn(0f, 1f)
            return ((if (rtl) 1f - f else f) * duration).toLong()
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var claimed = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (claimed) change.consume()
                        break
                    }
                    if (!claimed && (change.position - down.position).getDistance() >= claimAt) claimed = true
                    if (claimed) {
                        change.consume()
                        onSlide(valueAt(change.position.x))
                    }
                }
            } finally {
                // Lifted, or the gesture taken away: the song goes where the finger left it.
                if (claimed) onSlideDone()
            }
        }
    }

/** How far a finger moves on the bar before the drag is the bar's: well under a touch slop. */
private val CLAIM = 3.dp
