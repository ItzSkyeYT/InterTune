/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.animation.core.CubicBezierEasing
import com.dd3boh.outertune.ui.screens.recap.ActorId.A
import com.dd3boh.outertune.ui.screens.recap.ActorId.B
import com.dd3boh.outertune.ui.screens.recap.ActorId.C
import com.dd3boh.outertune.ui.screens.recap.ActorId.D
import com.dd3boh.outertune.ui.screens.recap.ActorId.F
import com.dd3boh.outertune.ui.screens.recap.ActorId.H
import com.dd3boh.outertune.ui.screens.recap.ActorId.Q
import com.dd3boh.outertune.ui.screens.recap.ActorId.R
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Blue
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Cream
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Ink
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Lemon
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Mint
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Orange
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Pink
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Plum
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Red
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Sky
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Sun
import com.dd3boh.outertune.ui.screens.recap.RecapInk.Violet

/** The parts of a slide a piece of paper can sit on. Each slide names its own (Modifier.anchor). */
internal object Spot {
    const val YEAR = "year"
    const val COVER = "cover"
    const val NUMBER = "number"
    const val DIAL = "dial"
    const val FACE = "face"
    const val FOUND = "found"
    const val NUMERAL = "numeral"
    const val STAGE = "stage"
    const val HERO = "hero"
}

/** How a slide's sheet comes in over the last one. */
internal sealed interface Sheet {
    /** The new paper grows as [shape] out of [at] (the design's pixels), or out of where piece [from] was. */
    data class Grow(val shape: Paper, val at: Pair<Float, Float>? = null, val from: ActorId? = null) : Sheet

    /** The new paper slides in as [strips] strips from alternate sides, or from the top and the foot. */
    data class Strips(val strips: Int, val vertical: Boolean = false) : Sheet

    /** Through the start screen's tunnel: nothing grows; coming back to it from a later slide, [again] does. */
    data class Dive(val again: Grow) : Sheet
}

/**
 * Where the eight pieces of paper sit on each slide, and how its sheet comes in: the calm shapes
 * design's poses (experiments/features/recap/v5-shapes-calm/recap.src.html), slide by slide. The
 * design is 414 by 736; a slide on a phone works those places out on its own size.
 */
internal object Choreography {
    private fun pose(
        sh: Paper = Paper.CIRCLE, x: Float = Float.NaN, y: Float = Float.NaN, s: Float = Float.NaN, r: Float = 0f,
        c: List<androidx.compose.ui.graphics.Color> = emptyList(), k: List<Float> = Pose.DEFAULT_K, o: Float = 1f, spin: Float = 0f,
        w: Float = 6f, at: String? = null, dx: Float = 0f, dy: Float = 0f, dr: Float = Float.NaN, mt: Int = 0,
        me: androidx.compose.animation.core.Easing? = null, fit: Float = Float.NaN,
    ) = Pose(sh, x, y, at, dx, dy, s, r, dr, c, k, o, spin, w, false, mt, me, fit)

    private val hide = Pose.HIDE

    /** The start screen: the big blob round the year, a tunnel of three papers. */
    val start = listOf(
        Poses(
            0f, mapOf(
                A to pose(Paper.BLOB, at = Spot.YEAR, s = 424f, c = listOf(Pink, Lemon, Blue), k = listOf(1f, 0.85f, 0.73f)),
                B to pose(Paper.BURST, 38f, 640f, 86f, c = listOf(Lemon), spin = 60f),
                C to pose(Paper.TRI, 382f, 644f, 92f, -14f, listOf(Cream, Ink), listOf(1f, 0.46f)),
                D to pose(Paper.CIRCLE, 356f, 116f, 50f, c = listOf(Ink, Lemon), k = listOf(1f, 0.44f)),
                F to pose(Paper.PILL, 40f, 330f, 120f, -62f, listOf(Blue)),
                R to pose(at = Spot.YEAR, s = 440f, w = 3f, c = listOf(Ink), o = 0.5f),
                Q to pose(x = 66f, y = 112f, s = 84f, r = -8f, c = listOf(Ink)),
                H to pose(x = 392f, y = 312f, s = 110f, c = listOf(Ink), o = 0.3f),
            )
        )
    )

    /** The sheet each slide comes in on. */
    fun sheet(kind: SlideKind, step: Int): Sheet = when (kind) {
        SlideKind.SINCE -> Sheet.Dive(Sheet.Grow(Paper.TRI, at = 207f to 380f))
        SlideKind.MINUTES -> Sheet.Grow(Paper.BURST, from = B)
        SlideKind.HOUR -> Sheet.Grow(Paper.CIRCLE, from = A)
        SlideKind.STREAK -> Sheet.Strips(7)
        SlideKind.ARTISTS -> Sheet.Grow(Paper.BLOB, at = -40f to 780f)
        SlideKind.ONLY_HERE -> Sheet.Grow(Paper.STAR8, from = A)
        SlideKind.COUNTDOWN -> if (step == 0) Sheet.Grow(Paper.SEAL, at = 207f to 368f) else Sheet.Strips(5, vertical = step % 2 == 1)
        SlideKind.NUMBER_ONE -> Sheet.Grow(Paper.CIRCLE, at = 207f to 300f)
        SlideKind.CARD -> Sheet.Grow(Paper.BURST, at = 207f to 214f)
        SlideKind.THANKS -> Sheet.Grow(Paper.BLOB2, at = 207f to 780f)
    }

    /** The pieces' places on a slide: the first set at once, any others later. */
    fun of(kind: SlideKind, step: Int): List<Poses> = when (kind) {
        SlideKind.SINCE -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.BLOB2, 420f, 744f, 190f, 20f, listOf(Pink, Orange), listOf(1f, 0.56f)),
                    B to pose(Paper.BURST, at = Spot.COVER, dx = 106f, dy = -84f, s = 196f, c = listOf(Lemon, Orange), k = listOf(1f, 0.6f), spin = 64f),
                    C to pose(Paper.TRI, 396f, 372f, 76f, -16f, listOf(Sky)),
                    D to pose(Paper.CIRCLE, 384f, 470f, 40f, c = listOf(Lemon)),
                    F to pose(Paper.PILL, 392f, 150f, 118f, 58f, listOf(Pink)),
                    R to pose(at = Spot.COVER, s = 300f, w = 3f, c = listOf(Cream), o = 0.35f),
                    Q to pose(x = 330f, y = 528f, s = 96f, r = -6f, c = listOf(Lemon)),
                    H to pose(x = 14f, y = 690f, s = 110f, c = listOf(Cream), o = 0.28f),
                )
            )
        )
        SlideKind.MINUTES -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.BLOB, at = Spot.NUMBER, dx = 6f, dy = 6f, s = 300f, r = -10f, c = listOf(Lemon, Orange), k = listOf(1f, 0f)),
                    B to pose(Paper.STAR8, 380f, 248f, 76f, c = listOf(Violet), spin = 56f),
                    C to pose(Paper.TRI, 34f, 700f, 84f, -24f, listOf(Orange, Lemon), listOf(1f, 0.5f)),
                    D to hide,
                    F to pose(Paper.PILL, 384f, 690f, 150f, -24f, listOf(Violet)),
                    R to hide,
                    Q to hide,
                    H to pose(x = 24f, y = 322f, s = 96f, c = listOf(Ink), o = 0.22f),
                )
            )
        )
        SlideKind.HOUR -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.DOME, 207f, 820f, 560f, c = listOf(Plum, Pink, Lemon), k = listOf(1f, 0.78f, 0.56f)),
                    B to pose(Paper.STAR4, 390f, 262f, 52f, c = listOf(Cream), spin = 48f),
                    C to pose(Paper.TRI, 38f, 504f, 62f, 22f, listOf(Plum)),
                    // The sun inside the dial and the ring round it, the design's 128 and 300 to the dial's 320, whatever size the dial is drawn.
                    D to pose(Paper.CIRCLE, at = Spot.DIAL, s = 128f, fit = 128f / 320f, c = listOf(Lemon, Sun), k = listOf(1f, 0.74f)),
                    F to pose(Paper.PILL, 400f, 420f, 120f, 36f, listOf(Pink)),
                    R to pose(at = Spot.DIAL, s = 300f, fit = 300f / 320f, w = 2.5f, c = listOf(Ink), o = 0.3f),
                    Q to hide,
                    H to pose(x = 36f, y = 420f, s = 96f, c = listOf(Plum), o = 0.3f),
                )
            )
        )
        SlideKind.STREAK -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.SQUARE, 316f, 176f, 178f, 12f, listOf(Violet, Plum, Lemon), listOf(1f, 0.66f, 0.3f)),
                    B to pose(Paper.STAR6, 34f, 690f, 70f, c = listOf(Lemon), spin = 52f),
                    C to pose(Paper.TRI, 390f, 694f, 84f, -36f, listOf(Blue)),
                    D to pose(Paper.CIRCLE, 236f, 112f, 40f, c = listOf(Lemon)),
                    F to pose(Paper.PILL, -6f, 342f, 110f, -64f, listOf(Violet)),
                    R to pose(x = 316f, y = 176f, s = 250f, w = 3f, c = listOf(Ink), o = 0.4f),
                    Q to pose(x = 376f, y = 300f, s = 70f, r = 70f, c = listOf(Violet)),
                    H to pose(x = 392f, y = 470f, s = 92f, c = listOf(Ink), o = 0.2f),
                )
            )
        )
        SlideKind.ARTISTS -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.BLOB, at = Spot.FACE, dx = -6f, dy = 16f, s = 196f, r = 30f, c = listOf(Pink, Lemon), k = listOf(1f, 0f)),
                    B to pose(Paper.STAR8, 384f, 98f, 60f, c = listOf(Lemon), spin = 50f),
                    C to pose(Paper.TRI, 396f, 548f, 84f, 18f, listOf(Sky)),
                    D to pose(Paper.CIRCLE, 30f, 640f, 58f, c = listOf(Orange)),
                    F to pose(Paper.PILL, 416f, 236f, 120f, -38f, listOf(Violet)),
                    R to pose(x = 384f, y = 98f, s = 96f, w = 3f, c = listOf(Lemon), o = 0.7f),
                    Q to pose(x = 312f, y = 312f, s = 74f, r = -4f, c = listOf(Pink), o = 0f),
                    H to pose(x = 392f, y = 690f, s = 104f, c = listOf(Cream), o = 0.2f),
                )
            )
        )
        SlideKind.ONLY_HERE -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.FLOWER, at = Spot.FOUND, dx = -4f, dy = -10f, s = 206f, r = 8f, c = listOf(Cream, Lemon), k = listOf(1f, 0f)),
                    B to pose(Paper.BURST, 398f, 196f, 62f, c = listOf(Blue), spin = 60f),
                    C to pose(Paper.TRI, 380f, 88f, 62f, 0f, listOf(Ink, Cream), listOf(1f, 0.42f)),
                    D to hide,
                    F to pose(Paper.PILL, 410f, 610f, 110f, 28f, listOf(Blue)),
                    R to hide,
                    Q to hide,
                    H to hide,
                )
            )
        )
        SlideKind.COUNTDOWN -> {
            val st = COUNTDOWN_COLOURS[step.coerceIn(0, COUNTDOWN_COLOURS.lastIndex)]
            listOf(
                Poses(
                    0f, mapOf(
                        A to pose(if (step % 2 == 1) Paper.BLOB2 else Paper.BLOB, at = Spot.NUMERAL, dx = 8f, dy = 14f, s = 210f, r = step * 40f, c = st.blob, k = listOf(1f, 0.6f)),
                        B to pose(if (step % 2 == 1) Paper.STAR6 else Paper.STAR8, 380f, 90f, 66f, c = listOf(st.star), spin = 54f),
                        C to pose(Paper.TRI, 396f, 330f, 64f, 20f + step * 30f, listOf(st.bits)),
                        D to pose(Paper.CIRCLE, 196f, 96f, 30f, c = listOf(st.bits)),
                        F to pose(Paper.PILL, 404f, 704f, 100f, -30f + step * 8f, listOf(st.pill)),
                        R to pose(at = Spot.STAGE, s = 236f, w = 3f, c = listOf(Ink), o = 0.35f),
                        Q to hide,
                        H to hide,
                    )
                )
            )
        }
        SlideKind.NUMBER_ONE -> {
            val cols = listOf(Lemon, Pink, Blue)
            // Placed round the cover, which the design has at (207, 237): the pieces draw in towards it.
            listOf(
                Poses(
                    0f, mapOf(
                        A to pose(Paper.BURST, at = Spot.HERO, s = 70f, c = cols, k = listOf(1f, 0.78f, 0.56f)),
                        B to pose(Paper.STAR8, at = Spot.HERO, dx = -87f, dy = -15f, s = 30f, c = listOf(Pink)),
                        C to pose(Paper.TRI, at = Spot.HERO, dx = 87f, dy = -15f, s = 30f, r = 0f, c = listOf(Sky)),
                        D to pose(Paper.CIRCLE, at = Spot.HERO, dy = -97f, s = 22f, c = listOf(Lemon)),
                        F to pose(Paper.PILL, at = Spot.HERO, dy = 67f, s = 40f, r = 0f, c = listOf(Violet)),
                        R to pose(at = Spot.HERO, s = 120f, w = 3f, c = listOf(Cream), o = 0.5f),
                        Q to hide,
                        H to pose(at = Spot.HERO, s = 160f, c = listOf(Cream), o = 0.18f),
                    )
                ),
                // The wind-up, one breath in: the burst grows and turns back against the way it will
                // go, the little pieces draw in towards it, and it all holds for a moment.
                Poses(
                    0.3f, mapOf(
                        A to pose(Paper.BURST, at = Spot.HERO, s = 172f, dr = -40f, c = cols, k = listOf(1f, 0.78f, 0.56f)),
                        B to pose(Paper.STAR8, at = Spot.HERO, dx = -61f, dy = -7f, s = 26f, dr = 25f, c = listOf(Pink)),
                        C to pose(Paper.TRI, at = Spot.HERO, dx = 61f, dy = -7f, s = 26f, dr = -20f, c = listOf(Sky)),
                        D to pose(Paper.CIRCLE, at = Spot.HERO, dy = -71f, s = 18f, c = listOf(Lemon)),
                        F to pose(Paper.PILL, at = Spot.HERO, dy = 49f, s = 34f, dr = 20f, c = listOf(Violet)),
                        R to pose(at = Spot.HERO, s = 150f, w = 3f, c = listOf(Cream), o = 0.6f),
                        Q to hide,
                        H to pose(at = Spot.HERO, s = 196f, c = listOf(Cream), o = 0.2f),
                    ),
                    mt = 1250, me = CubicBezierEasing(0.45f, 0f, 0.4f, 1f), stagger = 0,
                ),
                // The drop, with the cover: all of it let go at once. The burst opens past its size and
                // settles behind the cover, the pieces fly out to their places turning as they go, the
                // ring runs out and fades. Then everything rests; the burst turns about once a minute.
                Poses(
                    3f, mapOf(
                        A to pose(Paper.BURST, at = Spot.HERO, dy = -6f, s = 326f, dr = 75f, c = cols, k = listOf(1f, 0.8f, 0.62f), spin = 70f, me = RecapMotion.Bouncy, mt = 1300),
                        B to pose(Paper.STAR8, 34f, 330f, 46f, c = listOf(Pink), spin = 50f),
                        C to pose(Paper.TRI, 372f, 476f, 70f, 22f, listOf(Sky)),
                        D to pose(Paper.CIRCLE, 378f, 92f, 36f, c = listOf(Lemon)),
                        F to pose(Paper.PILL, 24f, 120f, 96f, 44f, listOf(Violet)),
                        R to pose(at = Spot.HERO, s = 760f, w = 2f, c = listOf(Cream), o = 0f, mt = 950, me = CubicBezierEasing(0.15f, 0.7f, 0.3f, 1f)),
                        Q to hide,
                        H to pose(x = 380f, y = 640f, s = 96f, c = listOf(Cream), o = 0.14f),
                    ),
                    mt = 1200, stagger = 28,
                ),
            )
        }
        SlideKind.CARD -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.BLOB, 58f, 196f, 230f, 40f, listOf(Blue, Sky), listOf(1f, 0.56f)),
                    B to pose(Paper.BURST, 360f, 560f, 150f, c = listOf(Lemon, Orange), k = listOf(1f, 0.6f), spin = 64f),
                    C to pose(Paper.TRI, 380f, 140f, 92f, -22f, listOf(Pink)),
                    D to pose(Paper.CIRCLE, 36f, 520f, 58f, c = listOf(Red)),
                    F to pose(Paper.PILL, 60f, 680f, 150f, -16f, listOf(Mint)),
                    R to pose(x = 360f, y = 560f, s = 200f, w = 3f, c = listOf(Ink), o = 0.35f),
                    Q to pose(x = 340f, y = 74f, s = 80f, r = 6f, c = listOf(Violet)),
                    H to pose(x = 40f, y = 330f, s = 100f, c = listOf(Ink), o = 0.16f),
                )
            )
        )
        SlideKind.THANKS -> listOf(
            Poses(
                0f, mapOf(
                    A to pose(Paper.BLOB2, 350f, 2f, 296f, -20f, listOf(Pink, Orange, Lemon), listOf(1f, 0.72f, 0.44f)),
                    B to pose(Paper.BURST, 352f, 506f, 92f, c = listOf(Lemon), spin = 60f),
                    C to pose(Paper.TRI, 42f, 98f, 78f, 16f, listOf(Cream, Ink), listOf(1f, 0.44f)),
                    D to hide,
                    F to pose(Paper.PILL, 400f, 690f, 140f, -20f, listOf(Blue)),
                    R to hide,
                    Q to pose(x = 110f, y = 498f, s = 90f, r = -8f, c = listOf(Lemon)),
                    H to pose(x = 24f, y = 410f, s = 96f, c = listOf(Ink), o = 0.18f),
                )
            )
        )
    }
}
