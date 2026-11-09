/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.PointTransformer
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import com.dd3boh.outertune.playback.automix.MixGains
import com.dd3boh.outertune.playback.automix.MixOnScreen
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * The player while a change plays as a mix (experiments/features/mix-player/SPEC.md), as plain
 * sums: where each cover is, which song the title and the seek bar name, and what the bar shows.
 * A place is a share of the cover's box, a square, from its top left corner.
 */
object MixLook {
    /** How long the covers take to come apart as a change starts, and to come together as it ends. */
    const val MORPH_MS = 1_100

    /** How long the title and the bar take to swap to the incoming song: a fade through. */
    const val SWAP_MS = 300

    /** Of that, the time the outgoing words take to fade out, as Material's fade through has it (35 %). */
    const val SWAP_OUT_MS = 105

    // Where the covers stand while the change plays: the outgoing one lower left, the incoming one upper right.
    const val OUT_X = 0.328f
    const val OUT_Y = 0.693f
    const val IN_X = 0.666f
    const val IN_Y = 0.310f

    /** The smallest either cover gets, and how much more each grows with its song's gain. */
    const val SMALLEST = 0.333f
    const val OUT_GROWS = 0.222f
    const val IN_GROWS = 0.244f

    /** The incoming cover comes in from this much further right and up. */
    const val IN_FROM = 0.044f

    /** The share of the way in by which the incoming cover is wholly seen. */
    const val IN_SEEN_BY = 0.6f

    /**
     * One cover: its centre and its width as shares of the box's side, how far its shape has turned
     * from today's rounded square into the cookie (0 to 1), and how much of it shows.
     */
    data class Spot(val x: Float, val y: Float, val size: Float, val cookie: Float, val alpha: Float)

    /** Both covers. The incoming one is drawn over the outgoing one. */
    data class Covers(val outgoing: Spot, val incoming: Spot)

    /** Today's cover: the whole box, the rounded square. */
    val WHOLE = Spot(0.5f, 0.5f, 1f, 0f, 1f)

    /** The outgoing song's gain with [heard] of the change heard: the plan's, or an equal-power pair's. */
    fun outgoingGain(heard: Float, gains: MixGains?): Float = gains?.outgoingAt(heard) ?: MixGains.equalPowerOut(heard)

    /** The incoming song's. */
    fun incomingGain(heard: Float, gains: MixGains?): Float = gains?.incomingAt(heard) ?: MixGains.equalPowerIn(heard)

    /** Quadratic easing, in and out. */
    fun eased(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 2 * x * x else 1 - (-2 * x + 2) * (-2 * x + 2) / 2
    }

    /**
     * Both covers with [heard] of the change heard. [coming] is how far the covers have come apart
     * as the change started and [going] how far they have come together as it ended, each 0 to 1
     * as time, eased here.
     *
     * Coming, the playing cover turns from the rounded square into the cookie, shrinks and goes to
     * its spot, and the next one grows from nothing at its own, from a little further right and up.
     * Between, each cover's width follows its song's gain. Going, the outgoing cover shrinks away
     * where it is, and the incoming one goes to the middle, grows to the whole box and turns back
     * into the rounded square.
     */
    fun covers(heard: Float, gains: MixGains?, coming: Float, going: Float): Covers {
        val outSize = SMALLEST + OUT_GROWS * outgoingGain(heard, gains).coerceIn(0f, 1f)
        val inSize = SMALLEST + IN_GROWS * incomingGain(heard, gains).coerceIn(0f, 1f)
        val c = eased(coming)
        val g = eased(going)
        val outgoing = Spot(
            x = lerp(0.5f, OUT_X, c),
            y = lerp(0.5f, OUT_Y, c),
            size = lerp(1f, outSize, c) * (1 - g),
            cookie = c,
            alpha = 1 - g,
        )
        val from = IN_FROM * (1 - c)
        val incoming = Spot(
            x = lerp(IN_X + from, 0.5f, g),
            y = lerp(IN_Y - from, 0.5f, g),
            size = lerp(inSize * c, 1f, g),
            cookie = 1 - g,
            alpha = lerp((c / IN_SEEN_BY).coerceAtMost(1f), 1f, g),
        )
        return Covers(outgoing, incoming)
    }

    /** Whether the title, the buttons' song and the seek bar name the outgoing song: until the incoming one takes over. */
    fun showsOutgoing(heard: Float, handover: Float): Boolean = heard < handover

    /**
     * How much a cover's picture is enlarged in its shape, [cookie] of the way into the cookie: a
     * picture of [aspect] (width over height; 16:9 for a video's thumbnail) is drawn whole in
     * today's square, as today, and fills the cookie as a square crop of it, so the cookie keeps
     * its scallops all round. 1 for a square picture, and while its aspect is not known (0).
     */
    fun cropScale(aspect: Float, cookie: Float): Float {
        if (aspect <= 0f || !aspect.isFinite()) return 1f
        val crop = if (aspect >= 1f) aspect else 1f / aspect
        return lerp(1f, crop, cookie.coerceIn(0f, 1f))
    }

    /**
     * How long the covers hold still once a change has begun, before they come apart. The incoming
     * song becoming the player's is the heaviest moment of a change (the player made again for it,
     * the phone's notification drawn again), and covers that came apart right then lost their first
     * frames to it: two frames of about 180 ms on the Pixel 5's debug app (9 Nov 2026). Held through
     * it, they then move at every frame. The music comes in under the outgoing song, so the wait is
     * not heard against it.
     */
    const val SETTLE_MS = 400

    /**
     * How far the covers have come apart [sinceStartMs] of the incoming song after the change
     * began, 0 to 1 over [MORPH_MS] once [SETTLE_MS] have passed (eased in [covers]). Counted in
     * the music, so a player opened late finds them apart, and a pause holds them where they are.
     */
    fun coming(sinceStartMs: Long): Float = ((sinceStartMs - SETTLE_MS).toFloat() / MORPH_MS).coerceIn(0f, 1f)

    /**
     * Where the incoming song is for this frame, in ms: [was] moved on by the frame's [frameNanos] while it plays,
     * and drawn a little toward the player's [position] at each frame, so the covers move at every frame even when
     * the player tells its position in steps (over Bluetooth, and as one song is handed to the next). A gap of more
     * than [PACE_SNAP_MS] is taken at once (a jump, a pause); with no frame time (the screen not drawn), the
     * position as it is.
     */
    fun paced(was: Long, position: Long, frameNanos: Long, playing: Boolean): Long {
        if (frameNanos <= 0L || !playing) return position
        val predicted = was + frameNanos / 1_000_000.0
        val drift = position - predicted
        if (abs(drift) > PACE_SNAP_MS) return position
        return Math.round(predicted + drift / PACE_EASE)
    }

    /** A gap between the paced clock and the player's position past which the position is taken as it is. */
    const val PACE_SNAP_MS = 250.0

    /** The share of the gap made up at each frame: an eighth, about 70 ms to close most of it at 120 Hz. */
    const val PACE_EASE = 8.0

    /** How far they have come together [sinceEndMs] after the outgoing song was gone, the same way. */
    fun going(sinceEndMs: Long): Float = (sinceEndMs.toFloat() / MORPH_MS).coerceIn(0f, 1f)

    /** Both covers with the incoming song at [atMs] in [change]. */
    fun covers(change: MixOnScreen, atMs: Long): Covers = covers(
        heard = change.heard(atMs),
        gains = change.gains,
        coming = coming(atMs - change.entryMs),
        going = going(atMs - change.entryMs - change.lengthMs),
    )

    /** Whether the screen shows [change] with the incoming song at [atMs]: from its start until the incoming cover is whole again. */
    fun shows(change: MixOnScreen, atMs: Long): Boolean = going(atMs - change.entryMs - change.lengthMs) < 1f

    /**
     * What the seek bar shows: a place and a length for its two times, and where its thumb stands
     * in the range of the song the player has, which a drag still seeks in.
     */
    data class Bar(val at: Long, val length: Long, val thumb: Float)

    /**
     * The bar with the player at [at] of [length] in the song it has. While [change] shows the
     * outgoing song ([outgoing]), its place, as it was when the change began and the time since,
     * over its own length; otherwise, and while a finger is on the bar, the player's song as always.
     */
    fun bar(change: MixOnScreen?, outgoing: Boolean, at: Long, length: Long, dragging: Boolean): Bar {
        if (change == null || !outgoing || dragging) return Bar(at, length, at.toFloat())
        val outAt = change.outgoingAt(at)
        val outLength = change.outgoingLengthMs
        val share = if (outLength > 0) (outAt.toFloat() / outLength).coerceIn(0f, 1f) else 0f
        return Bar(outAt, outLength, if (length > 0) share * length else 0f)
    }

    /** How many steps the Blur and Gradient backgrounds take across a change: each one composes the background again (MixBackdrop). */
    const val COLOUR_STEPS = 32

    /** [heard] to the nearest of [COLOUR_STEPS] steps. */
    fun stepped(heard: Float): Float = Math.round(heard.coerceIn(0f, 1f) * COLOUR_STEPS).toFloat() / COLOUR_STEPS

    /**
     * How much of the outgoing and the incoming cover's background to draw, each faded by
     * [artwork] (the background's own opacity), so that the two together are [t] of the way from
     * one to the other, over what is behind them: at 0 the outgoing one alone, at 1 the incoming
     * one alone, each as it is drawn today.
     */
    fun backgroundAlphas(t: Float, artwork: Float): Pair<Float, Float> {
        val incoming = artwork * t
        val left = 1 - incoming
        val outgoing = if (left <= 0f) 0f else (artwork * (1 - t) / left).coerceIn(0f, 1f)
        return outgoing to incoming
    }

    /** Exactly [from] at 0 and exactly [to] at 1, so today's cover comes out as it is drawn today. */
    private fun lerp(from: Float, to: Float, t: Float) = from * (1 - t) + to * t
}

/**
 * The covers' shapes while a change plays as a mix: today's rounded square, and Material 3
 * Expressive's nine-sided cookie, which the outgoing cover turns into and the incoming one back out of.
 */
object MixShapes {
    /**
     * MaterialShapes.Cookie9Sided as androidx.compose.material3 1.5.0-alpha28 defines it
     * (Apache-2.0): a nine-pointed star 0.8 of its radius deep, every corner rounded by half its
     * radius, turned a quarter back so a point is at the top, and laid in a box of side 1.
     */
    val cookie9: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.8f, rounding = CornerRounding(radius = 0.5f))
            .transformed(rotation(-90f))
            .normalized()
    }

    /** Today's cover in a box of side 1: a square with its corners rounded by [corner], a share of its side. */
    fun roundedSquare(corner: Float): RoundedPolygon =
        RoundedPolygon.rectangle(width = 1f, height = 1f, rounding = CornerRounding(radius = corner), centerX = 0.5f, centerY = 0.5f)

    /** From the rounded square at 0 to the cookie at 1. */
    fun morph(corner: Float): Morph = Morph(roundedSquare(corner), cookie9)

    /** A turn of [degrees] about the origin, as Compose's Matrix.rotateZ makes it, which MaterialShapes uses. */
    private fun rotation(degrees: Float): PointTransformer {
        val r = degrees * (PI / 180.0)
        val s = sin(r).toFloat()
        val c = cos(r).toFloat()
        return PointTransformer { x, y -> TransformResult(c * x - s * y, s * x + c * y) }
    }

    /** The morph's outline at [progress], in a box of side 1. */
    fun cubics(morph: Morph, progress: Float): List<Cubic> = morph.asCubics(progress)
}

/**
 * The morphs for one size of cover box: its rounded square has corners of a fixed width in dp, so
 * a share of its side that changes with the box. Kept for the last size, as the box keeps its
 * size while a change plays.
 */
internal class CoverMorphs(private val corner: Dp) {
    private var share = -1f
    private var morph: Morph? = null

    fun at(size: Size, density: Density): Morph {
        val now = with(density) { corner.toPx() } / size.minDimension.coerceAtLeast(1f)
        morph?.takeIf { abs(now - share) < 1e-4f }?.let { return it }
        return MixShapes.morph(now).also {
            morph = it
            share = now
        }
    }

    private val rounded = RoundedCornerShape(corner)
    private val turns = HashMap<Int, CoverShape>()

    /**
     * The shape of a cover [cookie] of the way from the rounded square to the cookie: today's own shape at 0.
     * In [TURNS] steps, each made once and kept with its outline: the turn lasts about a second, and a new path
     * built and clipped to at every frame of it made the covers come in slow and choppy on a 120 Hz phone
     * (9 Nov 2026). The same step is the same shape, so a layer is given a new outline only when the
     * step changes.
     */
    fun shape(cookie: Float): Shape {
        val step = (cookie.coerceIn(0f, 1f) * TURNS).roundToInt()
        if (step <= 0) return rounded
        return turns.getOrPut(step) { CoverShape(this, step.toFloat() / TURNS) }
    }

    companion object {
        /** How many shapes the turn between the rounded square and the cookie is made of: one every frame or two at 120 Hz. */
        const val TURNS = 64
    }
}

/** A cover's outline [cookie] of the way into the cookie (see [CoverMorphs]). */
private class CoverShape(private val morphs: CoverMorphs, private val cookie: Float) : Shape {
    /** The outline made last, for the size it was made for: the same step at the same size is not built again. */
    private var made: Outline? = null
    private var madeFor: Size? = null
    private var madeAt = 0f

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        made?.let { if (madeFor == size && madeAt == density.density) return it }
        return build(size, density).also {
            made = it
            madeFor = size
            madeAt = density.density
        }
    }

    private fun build(size: Size, density: Density): Outline {
        val path = Path()
        var first = true
        morphs.at(size, density).forEachCubic(cookie) { c ->
            if (first) {
                path.moveTo(c.anchor0X * size.width, c.anchor0Y * size.height)
                first = false
            }
            path.cubicTo(c.control0X * size.width, c.control0Y * size.height, c.control1X * size.width, c.control1Y * size.height, c.anchor1X * size.width, c.anchor1Y * size.height)
        }
        path.close()
        return Outline.Generic(path)
    }

    // The same turn is the same shape, so a layer is not given its outline again for nothing.
    override fun equals(other: Any?): Boolean = other is CoverShape && other.morphs === morphs && other.cookie == cookie

    override fun hashCode(): Int = 31 * System.identityHashCode(morphs) + cookie.hashCode()
}
