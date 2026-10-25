/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** The eight pieces of paper that stay on screen from slide to slide, in the order they are stacked. */
internal enum class ActorId(val kind: ActorKind, val layers: Int) {
    A(ActorKind.SHAPE, 3), B(ActorKind.SHAPE, 2), C(ActorKind.SHAPE, 2), D(ActorKind.SHAPE, 2), F(ActorKind.SHAPE, 1),
    R(ActorKind.RING, 0), Q(ActorKind.SQUIGGLE, 0), H(ActorKind.DOTS, 0),
}

internal enum class ActorKind { SHAPE, RING, SQUIGGLE, DOTS }

/**
 * Where a piece of paper goes and what it becomes: its middle ([x], [y] in the design's 414 by
 * 736, or on the part of the slide named [at], moved by [dx], [dy]), its size [s], its turn [r] (or
 * a turn by [dr] from where it is), its outline [sh], its colours outside in [c] with the size of
 * each layer [k], how opaque [o], a slow turn at rest once in [spin] seconds, the ring's width [w].
 * [hide] shrinks it away where it is. [mt] and [me] give this one move its own time and curve.
 * [fit], given with [at], makes its size that share of the part's width instead of [s]: for a piece
 * a slide's drawing is made round, as the hour's sun inside its dial.
 */
internal class Pose(
    val sh: Paper = Paper.CIRCLE,
    val x: Float = Float.NaN,
    val y: Float = Float.NaN,
    val at: String? = null,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val s: Float = Float.NaN,
    val r: Float = 0f,
    val dr: Float = Float.NaN,
    val c: List<Color> = emptyList(),
    val k: List<Float> = DEFAULT_K,
    val o: Float = 1f,
    val spin: Float = 0f,
    val w: Float = 6f,
    val hide: Boolean = false,
    val mt: Int = 0,
    val me: Easing? = null,
    val fit: Float = Float.NaN,
) {
    companion object {
        val DEFAULT_K = listOf(1f, 0.62f, 0.38f)
        val HIDE = Pose(hide = true)
    }
}

/**
 * The pieces' places on a slide, applied [at] steps after it came up: the first at once, others
 * later (number one's wind-up and drop). [mt] and [me] the time and curve of the move; [stagger]
 * how long each piece waits after the one before (0: they go together).
 */
internal class Poses(
    val at: Float,
    val set: Map<ActorId, Pose>,
    val mt: Int = RecapMotion.MOVE_MS,
    val me: Easing = RecapMotion.Settle,
    val stagger: Int = RecapMotion.STAGGER_MS,
)

/** A pose worked out on the screen, in pixels. */
internal class Placed(
    val sh: Paper, val x: Float, val y: Float, val s: Float, val r: Float, val c: List<Color>, val k: List<Float>,
    val o: Float, val spin: Float, val w: Float,
)

/** How one move is made. */
internal class Move(
    val delay: Int = 0,
    val mt: Int = RecapMotion.MOVE_MS,
    val me: Easing = RecapMotion.Settle,
    val ms: Easing = RecapMotion.Morph,
    val layerStep: Int = 0,
    val jump: Boolean = false,
)

/** One value on its way from where it was to where it goes. */
internal class Track(initial: Float = 0f) {
    private var from = initial
    var target = initial
        private set
    private var start = 0L
    private var duration = 0
    private var easing: Easing = LinearEasing

    fun at(now: Long): Float = when {
        duration <= 0 || now >= start + duration -> target
        now <= start -> from
        else -> from + (target - from) * easing.transform((now - start).toFloat() / duration)
    }

    fun go(to: Float, now: Long, delay: Int, ms: Int, curve: Easing) {
        from = at(now)
        target = to
        start = now + delay
        duration = ms
        easing = curve
    }

    fun jump(to: Float) {
        from = to
        target = to
        duration = 0
    }
}

/** A colour on its way. */
internal class ColourTrack(initial: Color = Color.Transparent) {
    private var from = initial
    var target = initial
        private set
    private var start = 0L
    private var duration = 0

    fun at(now: Long): Color = when {
        duration <= 0 || now >= start + duration -> target
        now <= start -> from
        else -> lerp(from, target, RecapMotion.Ease.transform((now - start).toFloat() / duration))
    }

    fun go(to: Color, now: Long, ms: Int) {
        if (to == target) return
        from = at(now)
        target = to
        start = now
        duration = ms
    }

    fun jump(to: Color) {
        from = to
        target = to
        duration = 0
    }
}

/**
 * One piece of paper: where it is, what it is becoming, and its slow turn. Moved by [place] when
 * a slide asks, and drawn from the clock by [drawActors]: every value is worked out from the time,
 * so a frame can be drawn at any moment and a held story stands still.
 */
internal class Actor(val id: ActorId) {
    var last: Placed? = null
        private set
    private var rot = 0f
    val x = Track()
    val y = Track()
    val s = Track()
    val r = Track()
    val o = Track()
    val w = Track(6f)
    val k = Array(3) { Track() }
    val c = Array(3) { ColourTrack() }
    private var morphFrom = PaperShapes.of(Paper.CIRCLE).copyOf()
    private var morphTo = PaperShapes.of(Paper.CIRCLE)
    private var morphStart = 0L
    private var morphMs = 0
    private var morphCurve: Easing = RecapMotion.Morph
    private val scratch = FloatArray(PaperShapes.N * 2)
    private var spinTarget = 0f
    private var spinSpeed = 0f
    var spinAngle = 0f
        private set

    /** The shape it is becoming, or is. */
    fun shapeNow(): Paper = last?.sh ?: Paper.CIRCLE

    /** Whether its outline is changing at [now]. */
    fun morphing(now: Long): Boolean = morphMs > 0 && now < morphStart + morphMs

    /** The outline as it is at [now], mid-change or not. */
    fun outline(now: Long): FloatArray {
        if (morphMs <= 0 || now >= morphStart + morphMs) return morphTo
        if (now <= morphStart) return morphFrom
        PaperShapes.mix(morphFrom, morphTo, morphCurve.transform((now - morphStart).toFloat() / morphMs), scratch)
        return scratch
    }

    /**
     * Sends it to [p], already worked out on the screen as ([px], [py]) with size [ps] (NaN: where
     * and as big as it was). [unit] is pixels for one of the design's. As the design's setPose: a
     * shape that travels rolls the way it goes, about half as far as a wheel of its size would,
     * and lands at a turn that leaves a star or a pill looking as it should; never more than 200
     * degrees in one move.
     */
    fun place(p0: Pose, px: Float, py: Float, ps: Float, now: Long, move: Move, unit: Float, middle: Offset) {
        val prev = last
        var shape = p0.sh
        var colours = p0.c
        var layers = p0.k
        var opacity = p0.o
        var spin = p0.spin
        var width = p0.w * unit
        var tx = px
        var ty = py
        var size = ps
        var turnTo = p0.r
        if (p0.hide) {
            // Shrinks away where it is, as it is.
            shape = prev?.sh ?: shape
            colours = prev?.c ?: colours
            layers = prev?.k ?: layers
            spin = prev?.spin ?: 0f
            width = prev?.w ?: width
            tx = prev?.x ?: middle.x
            ty = prev?.y ?: middle.y
            size = max(4f * unit, (prev?.s ?: (40f * unit)) * 0.2f)
            turnTo = prev?.r ?: 0f
            opacity = 0f
        }
        if (tx.isNaN()) tx = prev?.x ?: middle.x
        if (ty.isNaN()) ty = prev?.y ?: middle.y
        if (size.isNaN()) size = prev?.s ?: (60f * unit)

        // The turn, unwound to the nearest whole turn so a shape never spins round the long way.
        rot = when {
            move.jump || prev == null -> turnTo
            !p0.dr.isNaN() && !p0.hide -> rot + p0.dr
            else -> {
                val per = if (id.kind == ActorKind.SHAPE) shape.symmetry else 360f
                val want = ((tx - prev.x) / max(40f * unit, size) * 57f).coerceIn(-200f, 200f)
                val unitTurn = if (per > 0f && abs(want) >= 20f && !p0.hide) per else 360f
                var n = Math.round((rot + (if (unitTurn == 360f) 0f else want) - turnTo) / unitTurn)
                while (unitTurn < 360f && abs(turnTo + n * unitTurn - rot) > 200f) n += if (turnTo + n * unitTurn > rot) -1 else 1
                turnTo + n * unitTurn
            }
        }

        if (move.jump) {
            x.jump(tx); y.jump(ty); s.jump(size); r.jump(rot); o.jump(opacity); w.jump(width)
        } else {
            x.go(tx, now, move.delay, move.mt, move.me)
            y.go(ty, now, move.delay, move.mt, move.me)
            s.go(size, now, move.delay, move.mt, move.me)
            r.go(rot, now, move.delay, move.mt, move.me)
            o.go(opacity, now, move.delay, RecapMotion.ACTOR_FADE_MS, RecapMotion.Ease)
            w.go(width, now, 0, RecapMotion.RING_WIDTH_MS, RecapMotion.Ease)
        }

        if (id.kind == ActorKind.SHAPE) {
            val target = PaperShapes.of(shape)
            if (move.jump) {
                morphTo = target
                morphMs = 0
            } else if (target !== morphTo || morphMs > 0) {
                morphFrom = outline(now).copyOf()
                morphTo = target
                morphStart = now
                morphMs = move.mt
                morphCurve = move.ms
            }
            spinTarget = if (spin > 0f) 360f / spin else 0f
            if (move.jump) spinSpeed = spinTarget
        }
        for (i in 0 until 3) {
            val kv = if (i < colours.size) layers.getOrElse(i) { 1f } else 0f
            if (move.jump) k[i].jump(kv) else k[i].go(kv, now, if (move.layerStep > 0) move.delay + i * move.layerStep else 0, move.mt, move.me)
            colours.getOrNull(i)?.let { if (move.jump) c[i].jump(it) else c[i].go(it, now, RecapMotion.COLOUR_MS) }
        }
        last = Placed(shape, tx, ty, size, turnTo, colours.ifEmpty { prev?.c ?: emptyList() }, layers, opacity, spin, width)
    }

    /** Puts it at once where [place] would send it, small and see-through: where the start screen's pieces grow from. */
    fun shrinkFrom(scale: Float, turnBy: Float, now: Long) {
        val p = last ?: return
        x.jump(p.x); y.jump(p.y); s.jump(max(1f, p.s * scale)); o.jump(0f)
        rot = p.r + turnBy
        r.jump(rot)
        for (i in 0 until 3) k[i].jump(0f)
        morphTo = PaperShapes.of(p.sh)
        morphMs = 0
    }

    /** The slow turn at rest: its speed eases to the slide's, so it never jumps or stops dead. */
    fun turn(dtMs: Float, still: Boolean) {
        if (dtMs <= 0f) return
        spinSpeed += (spinTarget - spinSpeed) * (1f - exp(-dtMs / RecapMotion.SPIN_EASE_MS))
        if (!still) spinAngle = (spinAngle + spinSpeed * dtMs / 1000f) % 360f
    }
}

/** Reused paths, so a frame makes nothing new. */
internal class ActorPaths {
    val shape = Path()
    val squiggle = Path()
    private val dots = HashMap<Int, List<Offset>>()

    /** The halftone dots of a disc [size] pixels across, [step] apart, round its middle. */
    fun dots(size: Float, step: Float): List<Offset> {
        val key = (size / 2f).roundToInt() * 1000 + step.roundToInt()
        return dots.getOrPut(key) {
            val radius = size / 2f - step * 0.2f
            val n = (radius / step).toInt()
            buildList {
                for (i in -n..n) for (j in -n..n) {
                    val px = i * step
                    val py = j * step
                    if (px * px + py * py <= radius * radius) add(Offset(px, py))
                }
            }
        }.also { if (dots.size > 64) dots.clear() }
    }
}

/**
 * The eight pieces as they are at [now]: each layer of a shape as cut paper with a soft shadow
 * under it and the paper's grain, the ring, the squiggle, the dots. [rest] says whether they
 * drift while a slide rests (not with Remove animations on). [unit] is pixels for one of the
 * design's.
 */
internal fun DrawScope.drawActors(actors: List<Actor>, now: Long, rest: Boolean, unit: Float, paths: ActorPaths) {
    val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
    val seconds = now / 1000f
    for (a in actors) {
        val opacity = a.o.at(now)
        if (opacity <= 0.004f) continue
        var cx = a.x.at(now)
        var cy = a.y.at(now)
        var turn = a.r.at(now) + a.spinAngle
        if (rest) {
            val d = RecapMotion.Rest.drift.getValue(a.id)
            cx += d.ax * unit * sin(2f * PI.toFloat() * seconds / d.px + d.phase)
            cy += d.ay * unit * sin(2f * PI.toFloat() * seconds / d.py + d.phase * 1.7f)
            if (d.ar > 0f) turn += d.ar * sin(2f * PI.toFloat() * seconds / d.pr + d.phase * 0.6f)
        }
        val size = a.s.at(now)
        when (a.id.kind) {
            ActorKind.SHAPE -> {
                val outline = a.outline(now)
                val angle = turn * PI.toFloat() / 180f
                val cos = cos(angle)
                val sin = sin(angle)
                // The tunnel: a layer that covers the whole screen hides the layers under it and its own shadow.
                val far = corners.maxOf { hypot(it.x - cx, it.y - cy) }
                val inner = PaperShapes.inner(a.shapeNow())
                val covering = if (opacity < 1f || a.morphing(now)) -1
                else (a.id.layers - 1 downTo 0).firstOrNull { i -> size * a.k[i].at(now) * inner / 2f > far } ?: -1
                for (i in maxOf(0, covering) until a.id.layers) {
                    val side = size * a.k[i].at(now)
                    if (side < 0.5f) continue
                    val colour = a.c[i].at(now)
                    if (colour.alpha <= 0f) continue
                    val f = side / 100f
                    val path = paths.shape
                    path.rewind()
                    for (p in 0 until PaperShapes.N) {
                        val lx = (outline[2 * p] - 50f) * f
                        val ly = (outline[2 * p + 1] - 50f) * f
                        val px = cx + lx * cos - ly * sin
                        val py = cy + lx * sin + ly * cos
                        if (p == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    if (i != covering) paperShadow(path, unit, opacity)
                    drawPath(path, colour, alpha = opacity)
                }
            }
            ActorKind.RING -> {
                val width = a.w.at(now)
                if (size > width) drawCircle(a.c[0].at(now), (size - width) / 2f, Offset(cx, cy), alpha = opacity, style = Stroke(width))
            }
            ActorKind.SQUIGGLE -> {
                val f = size / 150f
                val angle = turn * PI.toFloat() / 180f
                val cos = cos(angle)
                val sin = sin(angle)
                fun px(x: Float, y: Float) = cx + ((x - 75f) * cos - (y - 22f) * sin) * f
                fun py(x: Float, y: Float) = cy + ((x - 75f) * sin + (y - 22f) * cos) * f
                val path = paths.squiggle
                path.rewind()
                val start = PaperShapes.SQUIGGLE_START
                path.moveTo(px(start[0], start[1]), py(start[0], start[1]))
                val curve = PaperShapes.SQUIGGLE
                for (j in curve.indices step 6) {
                    path.cubicTo(
                        px(curve[j], curve[j + 1]), py(curve[j], curve[j + 1]),
                        px(curve[j + 2], curve[j + 3]), py(curve[j + 2], curve[j + 3]),
                        px(curve[j + 4], curve[j + 5]), py(curve[j + 4], curve[j + 5]),
                    )
                }
                drawPath(path, a.c[0].at(now), alpha = opacity, style = Stroke(9f * f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            ActorKind.DOTS -> {
                val step = 12f * unit
                val points = paths.dots(size, step)
                if (points.isNotEmpty()) {
                    translate(cx, cy) {
                        drawPoints(points, PointMode.Points, a.c[0].at(now), strokeWidth = 4.8f * unit, cap = StrokeCap.Round, alpha = opacity)
                    }
                }
            }
        }
    }
}

/**
 * The soft shadow under a piece of paper: two copies a little lower and see-through, which on a
 * phone read as the design's drop shadow at a fraction of a blur's cost.
 */
internal fun DrawScope.paperShadow(path: Path, unit: Float, opacity: Float, strength: Float = 1f) {
    translate(0f, 3f * unit) { drawPath(path, RecapInk.Shade, alpha = 0.11f * opacity * strength) }
    translate(0f, 6f * unit) { drawPath(path, RecapInk.Shade, alpha = 0.11f * opacity * strength) }
}
