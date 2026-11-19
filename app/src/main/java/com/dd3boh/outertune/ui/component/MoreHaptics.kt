/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
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
 * Whether Compose gives haptics on this phone at all. Compose 1.9 hands every haptic to one that does nothing unless the
 * phone runs Android 12 or later with a vibrator that plays the click, tick and thud primitives (its HapticDefaults,
 * read in its 1.9.4 jar): the app's ticks are felt only there, and [segmentTick]'s stand-in follows the same rule
 * rather than tick on a phone where nothing else does.
 */
fun hapticsLive(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
    return vibrator.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )
}

/** How a segment's tick reaches the finger on Android [sdk]: see [segmentTick]. */
enum class TickWay { COMPOSE, CLOCK, NONE }

fun tickWay(sdk: Int, live: Boolean): TickWay = when {
    sdk >= 34 -> TickWay.COMPOSE
    live -> TickWay.CLOCK
    else -> TickWay.NONE
}

/**
 * A segment's tick: the seek bar's rumble ([frequent], the lighter one) and the volume bar landing on 100%. Android
 * knows these from 14 on (HapticFeedbackConstants.SEGMENT_TICK is API 34); below, Compose hands them to the view all
 * the same and nothing is felt, so the view's CLOCK_TICK, which every Android has, takes their place there, on a
 * phone where Compose gives haptics at all ([live], from [hapticsLive]).
 */
fun HapticFeedback.segmentTick(view: View, live: Boolean, frequent: Boolean = false) {
    when (tickWay(Build.VERSION.SDK_INT, live)) {
        TickWay.COMPOSE -> performHapticFeedback(if (frequent) HapticFeedbackType.SegmentFrequentTick else HapticFeedbackType.SegmentTick)
        TickWay.CLOCK -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        TickWay.NONE -> Unit
    }
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
 *
 * Once it rests there, a drag leaves only [MARGIN] of the bar past the zone's edges, so a finger resting at an edge,
 * whose tiny moves cross it back and forth, neither flips the volume nor ticks again on every other move.
 */
object VolumeSnap {
    /** A few percent of the bar above the line: 3% of its range, 0.12 on the bar that goes to 4. */
    const val ZONE = 0.03f

    /** How far past the zone's edges a drag goes before it leaves the line: a quarter of the zone. */
    const val MARGIN = ZONE / 4

    /**
     * Whether the bar rests on the line after a move to [dragged], the drag's own value, on a bar that goes from 0 to
     * [max], when it did before the move or not ([held]).
     */
    fun holds(dragged: Float, max: Float, held: Boolean, on: Boolean = Unreleased.HAPTICS_MORE): Boolean {
        if (!on || max <= 1f) return false
        val top = 1f + ZONE * max
        if (!held) return dragged >= 1f && dragged <= top
        val margin = MARGIN * max
        return dragged >= 1f - margin && dragged <= top + margin
    }

    /** What the bar shows: the line while it rests there, and below the line the finger, margin included. */
    fun shown(dragged: Float, held: Boolean): Float = if (held && dragged >= 1f) 1f else dragged

    /** One tick as the bar comes to rest on the line, none while it stays. */
    fun lands(was: Boolean, now: Boolean): Boolean = now && !was
}
