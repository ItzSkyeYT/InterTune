/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * The Recap's cut-paper shapes, as the calm shapes design draws them
 * (experiments/features/recap/v5-shapes-calm/recap.src.html, PTS): circles, two blobs, a burst,
 * a seal, stars of eight, six and four points, a flower, the app icon's rounded play triangle, a
 * rounded square, a pill and a dome. All our own drawing.
 *
 * [symmetry] is how far the shape turns and looks the same (a star of eight points every 45
 * degrees, a pill every 180, a circle at any turn: 0), which a shape on the move uses to land
 * looking as it should.
 */
internal enum class Paper(val symmetry: Float) {
    CIRCLE(0f), BLOB(360f), BLOB2(360f), BURST(30f), SEAL(15f), STAR8(45f), STAR6(60f), STAR4(90f),
    FLOWER(90f), TRI(360f), SQUARE(90f), PILL(180f), DOME(360f),
}

/**
 * Every shape is [N] points round its middle in a box of 100 by 100, the first straight up and
 * then clockwise, so that any shape turns into any other point by point. Plain arithmetic, tested
 * on the JVM (RecapShapesTest).
 */
internal object PaperShapes {
    const val N = 96

    private val outlines: Map<Paper, FloatArray> by lazy { Paper.entries.associateWith { build(it) } }

    /** The outline of [paper]: x and y of each of its [N] points, one after the other. */
    fun of(paper: Paper): FloatArray = outlines.getValue(paper)

    /** How close to its middle the outline comes, as a share of the half width: 1 for the circle. */
    fun inner(paper: Paper): Float {
        val p = of(paper)
        var least = Float.MAX_VALUE
        for (k in 0 until N) least = minOf(least, hypot(p[2 * k] - 50f, p[2 * k + 1] - 50f))
        return least / 50f
    }

    /** [from] turning into [to], [t] of the way (beyond 0 and 1 on a curve that overshoots), into [out]. */
    fun mix(from: FloatArray, to: FloatArray, t: Float, out: FloatArray) {
        for (i in out.indices) out[i] = from[i] + (to[i] - from[i]) * t
    }

    private fun build(paper: Paper): FloatArray = when (paper) {
        Paper.CIRCLE -> polar { 50.0 }
        Paper.BLOB -> blob(0.4, 1.9, 2.6)
        Paper.BLOB2 -> blob(2.2, 0.3, 4.4)
        Paper.BURST -> star(12, 0.76)
        Paper.SEAL -> star(24, 0.88)
        Paper.STAR8 -> star(8, 0.5)
        Paper.STAR6 -> star(6, 0.56)
        Paper.STAR4 -> star(4, 0.42)
        Paper.FLOWER -> polar { a -> 50 * (1 - 0.13 * (1 - abs(cos(4 * (a + PI / 2))))) }
        Paper.TRI -> cast(rounded(listOf(14.0 to 7.0, 93.0 to 50.0, 14.0 to 93.0), 13.0), 40.0, 50.0)
        Paper.SQUARE -> cast(rounded(listOf(7.0 to 7.0, 93.0 to 7.0, 93.0 to 93.0, 7.0 to 93.0), 20.0))
        Paper.PILL -> cast(rounded(listOf(2.0 to 31.0, 98.0 to 31.0, 98.0 to 69.0, 2.0 to 69.0), 18.5))
        Paper.DOME -> {
            val arc = (0..48).map { i -> val a = PI + PI * i / 48; (50 + 48 * cos(a)) to (76 + 48 * sin(a)) }
            val floor = (0 until 11).map { i -> (98 - 96.0 * (i + 1) / 12) to 76.0 }
            cast(arc + floor, 50.0, 58.0)
        }
    }.let { points -> FloatArray(points.size) { points[it].toFloat() } }

    /** A point at each of the [N] directions from (cx, cy), as far out as [radius] says. */
    private fun polar(cx: Double = 50.0, cy: Double = 50.0, radius: (Double) -> Double): DoubleArray {
        val out = DoubleArray(2 * N)
        for (k in 0 until N) {
            val a = -PI / 2 + 2 * PI * k / N
            val r = radius(a)
            out[2 * k] = cx + r * cos(a)
            out[2 * k + 1] = cy + r * sin(a)
        }
        return out
    }

    /** Where a ray from (cx, cy) leaves [outline]: the outline must be star-shaped round that point. */
    private fun cast(outline: List<Pair<Double, Double>>, cx: Double = 50.0, cy: Double = 50.0): DoubleArray = polar(cx, cy) { a ->
        val dx = cos(a)
        val dy = sin(a)
        var best = 0.0
        for (i in outline.indices) {
            val (x1, y1) = outline[i]
            val (x2, y2) = outline[(i + 1) % outline.size]
            val ex = x2 - x1
            val ey = y2 - y1
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-9) continue
            val qx = x1 - cx
            val qy = y1 - cy
            val t = (qx * ey - qy * ex) / den
            val u = (qx * dy - qy * dx) / den
            if (t > 0 && u >= -1e-7 && u <= 1 + 1e-7 && t > best) best = t
        }
        best
    }

    /** [corners] with each corner rounded to [radius], as a fine outline. */
    private fun rounded(corners: List<Pair<Double, Double>>, radius: Double, steps: Int = 14): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        val n = corners.size
        for (i in 0 until n) {
            val (x0, y0) = corners[(i - 1 + n) % n]
            val (x1, y1) = corners[i]
            val (x2, y2) = corners[(i + 1) % n]
            val ax = x0 - x1
            val ay = y0 - y1
            val bx = x2 - x1
            val by = y2 - y1
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            val ux = ax / la
            val uy = ay / la
            val vx = bx / lb
            val vy = by / lb
            val angle = acos((ux * vx + uy * vy).coerceIn(-1.0, 1.0))
            val d = radius / tan(angle / 2)
            val cd = radius / sin(angle / 2)
            val sx = ux + vx
            val sy = uy + vy
            val sl = hypot(sx, sy)
            val cx = x1 + sx / sl * cd
            val cy = y1 + sy / sl * cd
            val a1 = atan2(y1 + uy * d - cy, x1 + ux * d - cx)
            var da = atan2(y1 + vy * d - cy, x1 + vx * d - cx) - a1
            while (da > PI) da -= 2 * PI
            while (da < -PI) da += 2 * PI
            for (s in 0..steps) out += (cx + radius * cos(a1 + da * s / steps)) to (cy + radius * sin(a1 + da * s / steps))
        }
        return out
    }

    /** A star of [points] points, its inner corners [inner] of the way out. */
    private fun star(points: Int, inner: Double): DoubleArray = cast(
        (0 until 2 * points).map { j ->
            val a = -PI / 2 + PI * j / points
            val r = if (j % 2 == 1) 50 * inner else 50.0
            (50 + r * cos(a)) to (50 + r * sin(a))
        }
    )

    /** A soft blob: a circle with three slow waves round it, filling the box. */
    private fun blob(p: Double, q: Double, s: Double): DoubleArray {
        val raw = polar(0.0, 0.0) { a -> 0.84 + 0.095 * sin(2 * a + p) + 0.06 * sin(3 * a + q) + 0.035 * sin(5 * a + s) }
        var most = 0.0
        for (k in 0 until N) most = maxOf(most, hypot(raw[2 * k], raw[2 * k + 1]))
        return DoubleArray(2 * N) { 50 + raw[it] / most * 50 }
    }

    /** The squiggle: a wavy line in a box of 150 by 44, as cubic curves (each six numbers after the start). */
    val SQUIGGLE_START = floatArrayOf(8f, 26f)
    val SQUIGGLE = floatArrayOf(
        22f, 4f, 36f, 4f, 46f, 22f,
        56f, 40f, 72f, 40f, 84f, 22f,
        96f, 4f, 110f, 4f, 122f, 22f,
        134f, 40f, 138f, 36f, 144f, 26f,
    )
}
