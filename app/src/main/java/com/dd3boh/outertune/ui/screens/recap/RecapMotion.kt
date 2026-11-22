/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.dd3boh.outertune.ui.utils.Animations
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Every movement of the Recap, in one place, as the calm shapes design has them
 * (experiments/features/recap/v5-shapes-calm/recap.src.html): how the parts of a slide arrive
 * (with a turn, a little past their place, then a settle), how numbers count up once, how a new
 * sheet of paper grows over the old one, how the paper shapes travel from slide to slide, and
 * the one thing that moves while a slide rests: a slow drift of the shapes, and a slow turn of
 * the stars. Nothing beats or pulses in a loop; RecapRestTest holds that.
 *
 * Every entrance is written in steps of [STEP_MS] after its slide came up, so the slides say
 * what arrives when, and this file how.
 */
internal object RecapMotion {
    /** The ruler the entrances are timed on. Only a ruler: nothing repeats on it. */
    const val STEP_MS = 500f

    /**
     * How long a slide rests once everything on it has settled (its words and pictures arrived,
     * its numbers landed, its shapes' last move made, number one's confetti over) before the next
     * one comes: asked for on 24 Oct 2026, "the time between animation finish and next screen
     * start is around 6-8 seconds". RecapStage.lengthOf.
     */
    const val REST_MS = 7_000L

    /** Number one's confetti is over this long after it is let go. */
    const val CONFETTI_OVER_MS = 5_500L

    /** A paper shape has come to rest this long after its last move was due to end. */
    const val SHAPES_SETTLE_MS = 400L

    /** How long [how] takes to arrive, once begun. */
    fun takes(how: Entrance): Int = when (how) {
        Entrance.UP -> UP_MS
        Entrance.SLAP -> SLAP_MS
        Entrance.FLY -> FLY_MS
        Entrance.POP -> POP_MS
        Entrance.FADE -> FADE_MS
        Entrance.BOOM -> BOOM_MS
        Entrance.DROP -> DROP_MS
    }

    /** A touch past, then back: the badges, the words, the buttons (softened 22 Nov 2026, his word: too bouncy). */
    val Spring: Easing = CubicBezierEasing(0.2f, 1.08f, 0.35f, 1f)

    /** Quick out, gentle in. */
    val Snap: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

    /** How an outline changes into another: slow, then all at once, then slow. */
    val Morph: Easing = CubicBezierEasing(0.72f, 0f, 0.2f, 1f)

    /** The web's "ease": colours and fades. */
    val Ease: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** A damped spring: 3% past its place once, then a settle that comes to rest by the end (8% until his "not a fan of the amount of bouncyness", 22 Nov 2026). */
    val Settle: Easing = Sampled(
        0f, 0.0159f, 0.0587f, 0.1216f, 0.1985f, 0.2842f, 0.3742f, 0.4649f, 0.5533f, 0.6373f, 0.7152f, 0.7857f, 0.8484f, 0.9028f, 0.949f,
        0.9873f, 1.0068f, 1.0157f, 1.0224f, 1.027f, 1.0298f, 1.0311f, 1.0312f, 1.0303f, 1.0287f, 1.0266f, 1.024f, 1.0213f, 1.0184f, 1.0156f,
        1.0129f, 1.0103f, 1.008f, 1.0058f, 1.0039f, 1.0022f, 1.0009f, 1f, 0.9994f, 0.9991f, 0.999f, 0.9991f, 0.9992f, 0.9994f, 0.9996f,
        0.9998f, 0.9999f, 1f, 1f,
    )

    /** A livelier spring: 6% past, a small swing back, then rest (20% until 22 Nov 2026). Number one's burst opening. */
    val Bouncy: Easing = Sampled(
        0f, 0.0301f, 0.1104f, 0.2258f, 0.3624f, 0.508f, 0.6522f, 0.787f, 0.9064f, 1.002f, 1.0258f, 1.0432f, 1.0545f, 1.0604f, 1.0615f,
        1.0588f, 1.0533f, 1.0458f, 1.0372f, 1.0282f, 1.0194f, 1.0113f, 1.0042f, 0.9983f, 0.9937f, 0.9905f, 0.9884f, 0.9875f, 0.9874f, 0.9881f,
        0.9894f, 0.991f, 0.9928f, 0.9947f, 0.9966f, 0.9982f, 0.9995f, 1.0005f, 1.001f, 1.0012f, 1.0012f, 1.001f, 1.0008f, 1.0005f, 1.0003f,
        1.0002f, 1.0001f, 1f, 1f,
    )

    // ---- the parts of a slide arriving, each once

    const val UP_MS = 700
    const val SLAP_MS = 500
    val SlapEasing: Easing = CubicBezierEasing(0.3f, 0.7f, 0.3f, 1.04f)
    const val FLY_MS = 1150
    const val POP_MS = 900
    const val FADE_MS = 600
    const val BOOM_MS = 1150
    const val DROP_MS = 1250
    const val WORD_MS = 620

    /** How far a part rises, and a word rises out of its line (a share of its height), in the design's pixels. */
    const val UP_DISTANCE = 28f
    const val WORD_RISE = 1.15f

    /** A sticker thrown off as its slide goes. */
    const val TOSS_MS = 500
    val TossEasing: Easing = CubicBezierEasing(0.55f, 0f, 0.85f, 0.4f)

    /** The rest of a slide that goes without a sheet over it: a fade and a little growth. */
    const val LEAVE_MS = 400
    val LeaveEasing: Easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.2f)

    // ---- numbers: they count up once, fast then slowing, and land with one small swell

    const val COUNT_IN_MS = 320
    val CountInEasing: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
    const val LAND_MS = 500
    val LandEasing: Easing = CubicBezierEasing(0.2f, 1.15f, 0.4f, 1f)

    /** The value of a number counting up to [target] from step [at] to step [land], [elapsed] ms into its slide. */
    fun counted(target: Long, at: Float, land: Float, elapsed: Float): Long {
        val p = ((elapsed - at * STEP_MS) / ((land - at) * STEP_MS)).coerceIn(0f, 1f)
        return (target * (1.0 - (1.0 - p).pow(4))).roundToLong()
    }

    // ---- the charts drawing themselves, once

    const val BAR_MS = 550
    val BarEasing: Easing = CubicBezierEasing(0.2f, 1.1f, 0.4f, 1f)
    const val BAR_FROM = 2.5f
    const val BAR_GAP_MS = 7f
    const val RAY_MS = 600
    val RayEasing: Easing = CubicBezierEasing(0.2f, 1.08f, 0.4f, 1f)
    const val RAY_FROM = 1.5f
    const val RAY_GAP_MS = 30f
    const val TILE_MS = 450
    val TileEasing: Easing = CubicBezierEasing(0.3f, 1.15f, 0.5f, 1f)
    const val TILE_FROM = 2.5f
    const val TILE_GAP_MS = 22f

    /** However long the streak, its days light within this. */
    const val TILES_SPREAD_MS = 1200f
    const val DOTS_FROM = 4.6f
    const val DOTS_STEPS = 2.2f

    // ---- the paper shapes

    /** A move from one place to the next: place, turn and size on [Settle], the outline on [Morph]. */
    const val MOVE_MS = 1100
    const val ACTOR_FADE_MS = 450
    const val COLOUR_MS = 700
    const val RING_WIDTH_MS = 900

    /** Each shape sets off a little after the one before. */
    const val STAGGER_MS = 45

    /** The start screen: the pieces spring on one after another, the big blob's layers stacking up. */
    const val INTRO_MS = 1200
    const val INTRO_STAGGER_MS = 70
    const val INTRO_LAYER_MS = 120

    /** How quickly a star's slow turn changes speed between slides, so it never stops dead. */
    const val SPIN_EASE_MS = 600f

    // ---- Open Recap: the start's paper tunnel opens and the story goes through it

    const val TUNNEL_MS = 900
    val TunnelEasing: Easing = CubicBezierEasing(0.55f, 0f, 0.6f, 0.4f)
    const val SCATTER_MS = 700
    val ScatterEasing: Easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.3f)
    const val SCATTER_STAGGER_MS = 20
    const val THROUGH_MS = 660L
    const val DIVE_MS = 1050
    const val DIVE_STAGGER_MS = 40
    const val DIVE_GROW_MS = 950
    const val CALM_OPEN_MS = 300L

    // ---- from one slide to the next: a new sheet of paper grows over the old one

    const val WIPE_MS = 700
    val WipeEasing: Easing = CubicBezierEasing(0.5f, 0f, 0.28f, 1f)
    const val STRIP_MS = 440
    const val STRIP_GAP_MS = 50
    val StripEasing: Easing = CubicBezierEasing(0.6f, 0f, 0.25f, 1f)

    /** How long the slide that went stays drawn, under the sheet or fading. */
    const val KEEP_LEAVING_MS = 560

    /** The chrome and the pill take the new slide's colours. */
    const val CHROME_COLOUR_MS = 600

    // ---- with Remove animations on: things fade in place, nothing travels, nothing moves at rest

    const val CROSSFADE_MS = 450
    const val CALM_FADE_MS = 550
    const val CALM_LEAVE_MS = 350
    const val CALM_SHAPES_MS = 700
    const val CALM_SHAPES_JUMP_MS = 300

    // ---- the pill at the foot

    const val PILL_OUT_MS = 200
    const val PILL_IN_MS = 460
    const val PILL_IN_DELAY_MS = 160
    const val PILL_WIDTH_MS = 450
    const val EQ_MS = 800

    // ---- confetti: twice in the whole story

    const val CONFETTI_NUMBER_ONE_AT = 3f
    const val CONFETTI_THANKS_AT = 2f

    /**
     * What moves while a slide rests, and nothing else does: each shape wanders a few pixels and
     * degrees on its own long periods (seconds for one cycle), so they never move together and
     * nothing ticks; the stars turn about once a minute. Amplitudes in the design's pixels and
     * degrees. RecapRestTest holds every period here to [SLOWEST_S] or slower.
     */
    object Rest {
        class Drift(val ax: Float, val ay: Float, val ar: Float, val px: Float, val py: Float, val pr: Float, val phase: Float)

        val drift: Map<ActorId, Drift> = mapOf(
            ActorId.A to Drift(7f, 6f, 2.5f, 17f, 13f, 21f, 0.3f),
            ActorId.B to Drift(5f, 5f, 0f, 11.5f, 14f, 0f, 1.7f),
            ActorId.C to Drift(5f, 6f, 5f, 13f, 9.5f, 16f, 2.9f),
            ActorId.D to Drift(4f, 5f, 0f, 9f, 12.5f, 0f, 4.1f),
            ActorId.F to Drift(6f, 5f, 4f, 15f, 11f, 19f, 5.3f),
            ActorId.R to Drift(4f, 4f, 0f, 19f, 14.5f, 0f, 0.9f),
            ActorId.Q to Drift(5f, 4f, 4f, 12f, 16f, 10.5f, 2.2f),
            ActorId.H to Drift(5f, 5f, 0f, 14f, 18f, 0f, 3.6f),
        )

        /** No drift comes round faster than this, in seconds. */
        const val SLOWEST_S = 8f

        /** Nothing turns at rest faster than once in this many seconds: the stars take about a minute. */
        const val SLOWEST_TURN_S = 20f

        /** The working screen's burst turns once in this long. */
        const val WORKING_TURN_S = 24f
    }

    /** A press held this long holds the story; a shorter one is a tap. */
    const val HOLD_MS = 220L

    /** The left part of the screen that goes back; the rest goes on. */
    const val BACK_SHARE = 0.3f

    /**
     * Whether things move at all. With Remove animations on, the animator turned off in the
     * developer options, or the app's own Animations switch off, the Recap fades instead: parts
     * appear in place, slides cross-fade, the shapes change where they are, and nothing moves while
     * a slide rests. The app's one rule (Animations.off, which reads Android's setting too): the
     * story runs on a clock of its own, and what does asks that rule, as Living blur does.
     */
    fun still(): Boolean = Animations.off
}

/** A curve given as evenly spaced samples from 0 to 1, joined by straight lines: the web's linear(). */
internal class Sampled(private vararg val samples: Float) : Easing {
    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return samples.first()
        if (fraction >= 1f) return samples.last()
        val at = fraction * (samples.size - 1)
        val i = floor(at).toInt()
        return samples[i] + (samples[i + 1] - samples[i]) * (at - i)
    }
}

/**
 * The Recap's own time, in ms: it moves on with the frames and stands still while the story is
 * held, away or sharing. Everything that moves works its place out from it, in drawing only, so
 * a slide's parts are not composed again for each frame.
 */
@Stable
internal class RecapClock {
    private var exact = 0.0
    var now by mutableLongStateOf(0L)
        private set

    fun advance(ms: Double) {
        if (ms <= 0.0) return
        exact += ms
        now = exact.toLong()
    }
}

/** How a part of a slide arrives. */
internal enum class Entrance { UP, SLAP, FLY, POP, FADE, BOOM, DROP }

/** Where a sticker is thrown to as its slide goes: how far (the design's pixels) and how much more it turns. */
internal class Toss(val x: Float = 60f, val y: Float = 520f, val turn: Float = 40f)

/**
 * A part of a slide arriving once, [at] steps after the slide came up, as [how] says; then it
 * rests, tilted by [turn]. A [Entrance.FLY] comes from ([fx], [fy]) away, turned [fr] more. A
 * sticker given a [toss] is thrown off when its slide goes. With Remove animations on it fades in
 * place. Worked out in the layer from the story's clock: nothing is composed again.
 */
internal fun Modifier.enter(
    s: SlideScope,
    how: Entrance,
    at: Float,
    turn: Float = 0f,
    fx: Float = 0f,
    fy: Float = 300f,
    fr: Float = 30f,
    toss: Toss? = null,
): Modifier {
    s.arrives(at, if (s.still) RecapMotion.CALM_FADE_MS else RecapMotion.takes(how))
    return enterLayer(s, how, at, turn, fx, fy, fr, toss)
}

private fun Modifier.enterLayer(s: SlideScope, how: Entrance, at: Float, turn: Float, fx: Float, fy: Float, fr: Float, toss: Toss?): Modifier = graphicsLayer {
    val t = s.elapsed() - at * RecapMotion.STEP_MS
    val u = s.unit
    rotationZ = turn
    if (s.still) {
        alpha = RecapMotion.Ease.transform((t / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
        return@graphicsLayer
    }
    fun part(ms: Int) = (t / ms).coerceIn(0f, 1f)
    when (how) {
        Entrance.UP -> {
            val e = RecapMotion.Spring.transform(part(RecapMotion.UP_MS))
            alpha = e.coerceIn(0f, 1f)
            translationY = (1f - e) * RecapMotion.UP_DISTANCE * u
        }
        Entrance.SLAP -> {
            val p = part(RecapMotion.SLAP_MS)
            if (p < 0.6f) {
                val e = RecapMotion.SlapEasing.transform(p / 0.6f)
                scaleX = 2f + (0.93f - 2f) * e
                alpha = e.coerceIn(0f, 1f)
                rotationZ = turn + 16f * (1f - e)
            } else {
                scaleX = 0.93f + 0.07f * RecapMotion.SlapEasing.transform((p - 0.6f) / 0.4f)
            }
            scaleY = scaleX
        }
        Entrance.FLY -> {
            val p = part(RecapMotion.FLY_MS)
            val e = RecapMotion.Settle.transform(p)
            alpha = RecapMotion.Settle.transform((p / 0.25f).coerceAtMost(1f)).coerceIn(0f, 1f)
            translationX = fx * u * (1f - e)
            translationY = fy * u * (1f - e)
            rotationZ = turn + fr * (1f - e)
        }
        Entrance.POP -> {
            val p = part(RecapMotion.POP_MS)
            val e = RecapMotion.Settle.transform(p)
            alpha = RecapMotion.Settle.transform((p / 0.55f).coerceAtMost(1f)).coerceIn(0f, 1f)
            scaleX = e.coerceAtLeast(0f)
            scaleY = scaleX
            rotationZ = turn - 50f * (1f - e)
        }
        Entrance.FADE -> alpha = RecapMotion.Ease.transform(part(RecapMotion.FADE_MS))
        Entrance.BOOM -> {
            val p = part(RecapMotion.BOOM_MS)
            val e = RecapMotion.Settle.transform(p)
            alpha = RecapMotion.Settle.transform((p / 0.4f).coerceAtMost(1f)).coerceIn(0f, 1f)
            scaleX = 0.2f + 0.8f * e
            scaleY = scaleX
            rotationZ = turn - 30f * (1f - e)
        }
        Entrance.DROP -> {
            val p = part(RecapMotion.DROP_MS)
            val e = RecapMotion.Settle.transform(p)
            alpha = RecapMotion.Settle.transform((p / 0.3f).coerceAtMost(1f)).coerceIn(0f, 1f)
            translationY = -640f * u * (1f - e)
            rotationZ = turn + 14f * (1f - e)
        }
    }
    // Thrown off with its slide: from where it rests, out and turning.
    if (toss != null) {
        val gone = s.leftFor()
        if (gone > 0f) {
            val e = RecapMotion.TossEasing.transform((gone / RecapMotion.TOSS_MS).coerceIn(0f, 1f))
            translationX += toss.x * u * e
            translationY += toss.y * u * e
            rotationZ += toss.turn * e
        }
    }
}
