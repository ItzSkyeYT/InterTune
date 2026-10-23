/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Every movement of the Recap, in one place: how a slide comes in and goes, how its parts rise
 * into place one after another, how its numbers count up, how the start screen opens onto the
 * first slide, the countdown's pop and number one's cover coming into focus. The slides say what
 * moves and in what order; how long and along which curve is decided here.
 *
 * These are the approved mock's values (experiments/features/recap/recap.src.html). The livelier
 * variations being drawn (v2-kinetic, v3-shapes, v4-cinematic) change mostly this: other curves,
 * a different change between slides ([slideChange]), more that moves in the backdrop. So a move
 * towards one of them starts in this file and leaves the slides' layouts alone.
 */
internal object RecapMotion {
    /** The entrances: each part comes up [RISE_DISTANCE] from below, a beat after the one before. */
    val RiseEasing: Easing = CubicBezierEasing(0.2f, 0.75f, 0.25f, 1f)
    const val RISE_MS = 620
    const val RISE_FIRST_MS = 120L
    const val RISE_BEAT_MS = 90f
    val RISE_DISTANCE = 18.dp

    /** A number counts up from nothing, slowing at the end. */
    const val COUNT_DELAY_MS = 250L
    const val COUNT_MS = 1300

    /** One slide fades into the next. */
    const val SLIDE_FADE_MS = 450

    /**
     * The charts draw themselves in this long, each bar, line or day a little after the one
     * before: the minutes' days from the left, the hours round the dial, the streak's days in turn.
     */
    const val SKY_MS = 1900
    const val DIAL_MS = 1600
    const val CALENDAR_MS = 1800

    /** Behind each slide its cover drifts, two copies at two speeds, back and forth. */
    const val DRIFT_MS = 24_000
    const val DRIFT_2_MS = 31_000

    /** A slide's colours, when its cover has been read or the countdown moves on. */
    const val COLOUR_MS = 800

    /** The start screen opens: its covers come into focus, then the first slide spreads from the button. */
    const val OPEN_MS = 650L
    const val REVEAL_MS = 900
    val RevealEasing: Easing = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)
    val OpenEasing: Easing = CubicBezierEasing(0.3f, 0.6f, 0.2f, 1f)

    /** Each step of the countdown: its number and cover pop in, a little past their size and back. */
    const val POP_MS = 550
    val PopEasing: Easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)

    /** Number one's cover: blurred and small for a moment, then sharp. */
    const val UNBLUR_WAIT_MS = 980L
    const val UNBLUR_MS = 720
    val UnblurEasing: Easing = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)

    /** A press held this long holds the story; a shorter one is a tap. */
    const val HOLD_MS = 220L

    /** The left part of the screen that goes back; the rest goes on. */
    const val BACK_SHARE = 0.3f

    /** A curve that slows at the end, for the count-ups. */
    val EaseOutCubic: Easing = Easing { 1f - (1f - it).pow(3) }

    /**
     * Whether things move at all. With Remove animations on, or the animator turned off in the
     * developer options, every slide is shown as it ends, still.
     */
    fun still(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()

    /** How one slide gives way to the next. */
    fun slideChange(scope: AnimatedContentTransitionScope<Int>): ContentTransform = with(scope) {
        fadeIn(tween(SLIDE_FADE_MS)) togetherWith fadeOut(tween(SLIDE_FADE_MS))
    }
}

/** Comes up from a little below, [order] beats after the slide: the mock's entrances. */
@Composable
internal fun Modifier.rise(order: Float, s: SlideScope): Modifier {
    val shown = remember(s.key) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key) {
        if (s.still) return@LaunchedEffect
        delay((order * RecapMotion.RISE_BEAT_MS).toLong() + RecapMotion.RISE_FIRST_MS)
        shown.animateTo(1f, tween(RecapMotion.RISE_MS, easing = RecapMotion.RiseEasing))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * RecapMotion.RISE_DISTANCE.toPx()
    }
}

/** A number counting up to [target] as its slide comes up. */
@Composable
internal fun countUp(target: Long, s: SlideScope): Long {
    val done = remember(s.key) { Animatable(if (s.still) 1f else 0f) }
    LaunchedEffect(s.key) {
        if (s.still) return@LaunchedEffect
        delay(RecapMotion.COUNT_DELAY_MS)
        done.animateTo(1f, tween(RecapMotion.COUNT_MS, easing = RecapMotion.EaseOutCubic))
    }
    return (target * done.value).roundToLong()
}

/**
 * A circle around [centre] grown to [fraction] of the way to the furthest corner: what is drawn
 * through it is the first slide spreading out of the Open button.
 */
internal class CircleReveal(private val centre: Offset, private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val far = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
            .maxOf { hypot(it.x - centre.x, it.y - centre.y) }
        return Outline.Generic(Path().apply { addOval(Rect(centre, far * fraction.coerceIn(0f, 1f))) })
    }
}
