/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.dd3boh.outertune.constants.Unreleased
import kotlin.math.abs

/**
 * The tick of a tap where the app gave none (Unreleased.HAPTICS_MORE, #176): the one a chip, a
 * library row and the queue's button already give, ContextClick. It goes through the view, so
 * Android's Touch feedback setting decides whether anything is felt, as it does for every app.
 */
fun HapticFeedback.tapTick() {
    if (Unreleased.HAPTICS_MORE) performHapticFeedback(HapticFeedbackType.ContextClick)
}

/**
 * The seek bar's faint rumble while it is dragged (Unreleased.HAPTICS_MORE, #176): one light tick
 * each [steps]th of the bar the finger travels, as the Pixel's own volume slider ticks, and never
 * one for each move the slider reports, which came many times a frame and buzzed (why the tick
 * upstream gave the slider had been taken out: "slider too granular"). The count runs from where
 * the drag went down and from each tick on, so a finger that trembles on the spot stays quiet and
 * a slow drag ticks as evenly as a quick one. [reset] when the finger lifts.
 */
class SeekRumble(private val steps: Int = STEPS) {
    /** Where the last tick was, or the drag went down, as a share of the bar; null between drags. */
    private var anchor: Float? = null

    /** Whether the drag, now at [position] of [duration], has gone a step since the last tick. */
    fun tick(position: Long, duration: Long): Boolean {
        if (duration <= 0) return false
        val at = (position.coerceIn(0L, duration).toDouble() / duration).toFloat()
        val from = anchor
        if (from == null) {
            anchor = at
            return false
        }
        // A hair under one step, so that the float arithmetic never drops a tick the bar's own
        // steps would have.
        if (abs(at - from) < 1f / steps - 1e-6f) return false
        anchor = at
        return true
    }

    fun reset() {
        anchor = null
    }

    companion object {
        /** Forty ticks from one end of the bar to the other: about one every 8 dp on a phone. */
        const val STEPS = 40
    }
}

/**
 * The volume bar's pull towards the line where boost begins (Unreleased.HAPTICS_MORE; asked in #general on 18 Nov 2026,
 * "does it magnetize / snap if i drag it back to the line"): a drag that comes within [ZONE] of the bar above 1 (100%)
 * rests on it, and one that goes on past that leaves it again. Only on the boost side, where the ask came from (coming
 * back down from boost): below 100% the bar follows the finger, so 88% to 99% can still be set. The drag's own value is
 * kept apart from what the bar shows, so that a slow drag is never held at the line for good. Only on a bar that goes
 * past 1: on Saver it stops at 1 and has no line to snap to.
 */
object VolumeSnap {
    /** A few percent of the bar above the line: 3% of its range, 0.12 on the bar that goes to 4. */
    const val ZONE = 0.03f

    /** What the bar shows for a drag whose own value is [dragged], on a bar that goes from 0 to [max]. */
    fun shown(dragged: Float, max: Float, on: Boolean = Unreleased.HAPTICS_MORE): Float =
        if (on && max > 1f && dragged >= 1f && dragged - 1f <= ZONE * max) 1f else dragged

    /** Whether the bar, showing [before] and now [now], has just landed on the line: one tick then. */
    fun lands(before: Float, now: Float): Boolean = now == 1f && before != 1f
}
