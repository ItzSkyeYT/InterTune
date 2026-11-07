/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

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
 * It sits on the bar together with its times and the room under them, not on the bar alone: the
 * bar took a finger only on its own 48 dp of a strip about 76 dp tall down to the buttons, and a
 * thumb on the times moved nothing (7 Nov 2026, "the player bar needs to be easier to drag"). Below
 * [barBottom] (px from the top, the bar's own height) a drag is the bar's only once it goes more
 * across than down ([claimsSeek]), so a swipe down from the times still closes the player.
 *
 * [trackInset] is where the slider's track starts and ends inside the strip: its side padding and
 * half its thumb's width. [rtl]: the bar runs right to left, as the slider does in a right-to-left
 * language.
 */
internal fun Modifier.seekDrag(
    duration: Long,
    trackInset: Dp,
    rtl: Boolean,
    barBottom: () -> Float,
    onSlide: (Long) -> Unit,
    onSlideDone: () -> Unit,
): Modifier =
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
                    // Taken first by something else, the player's swipe down: it is not the bar's.
                    if (!claimed && change.isConsumed) break
                    if (!claimed && claimsSeek(down.position, change.position, claimAt, barBottom())) claimed = true
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

/**
 * Whether a finger that went down at [down] and is now at [now] has made the drag the bar's: [claimPx]
 * of movement, in any direction on the bar itself (above [barBottomPx]), where a thumb often goes
 * down a little first; on the times and the room under them, only a move that goes at least as far
 * across as down.
 */
internal fun claimsSeek(down: Offset, now: Offset, claimPx: Float, barBottomPx: Float): Boolean {
    val moved = now - down
    if (moved.getDistance() < claimPx) return false
    return down.y < barBottomPx || abs(moved.x) >= abs(moved.y)
}

/** How far a finger moves on the bar before the drag is the bar's: well under a touch slop. */
private val CLAIM = 3.dp
