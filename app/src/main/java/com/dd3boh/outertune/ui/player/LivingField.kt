/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.playback.MusicLevels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The living background's picture, with nothing of Android in it: what the cover is reduced to,
 * and how that moves to the music. LivingBackground.kt draws it.
 *
 * The cover becomes a few patches of colour, the way it reads when it is blurred until nothing
 * else is left of it, each patch where that colour sits on the cover. Still, it looks like the
 * blurred cover the player already has. Then the patches breathe: the bottom rows with the bass,
 * the top row with the cymbals, the rows between with everything else, and all of them lean out a
 * little on a kick. A glow in the cover's strongest colour rises from the bottom edge with the bass.
 */
object LivingField {
    /** Patches along the short side of the screen. Three is about what a 150 dp blur leaves of a cover on a phone. */
    const val ACROSS = 3

    /** No more patches than this along the long side, however long it is. */
    const val MOST_ALONG = 7

    /** A patch's radius at rest, in cells. Wide enough that neighbours run into each other. */
    const val REST_RADIUS = 0.95f

    /** How far a patch swells at full level, and how far a full bass pushes everything outwards. */
    const val SWELL = 0.34f
    const val LEAN = 0.05f

    /** How far a patch wanders from its place, in cells. */
    const val WANDER = 0.24f

    /** A patch is this bright at rest and this much brighter at full level. The glow is its colour this much lighter. */
    const val REST_LIGHT = 0.80f
    const val FULL_LIGHT = 0.42f
    const val GLOW_LIGHT = 1.3f

    /**
     * How much more colourful a patch is than the plain average of its part of the cover. An
     * average of red and yellow on black is brown; this takes it some of the way back.
     */
    const val COLOUR = 1.3f

    /**
     * How fast the picture follows each range going up, how slowly it lets go, in seconds, and how
     * much of the range's level it shows at all. Bass, middle, top. The bass is the whole show: a
     * kick lands at once and fades over a third of a second. The top is busy in almost any song,
     * and shown in full it made the upper edge of the screen flicker, so it comes softer and at half.
     */
    val RISE = floatArrayOf(0.030f, 0.045f, 0.070f)
    val FALL = floatArrayOf(0.30f, 0.22f, 0.16f)
    val SHARE = floatArrayOf(1f, 0.75f, 0.5f)

    /** Colours take this long to turn into the next cover's. */
    const val COVER_TURN = 0.45f

    /**
     * How many patches along a side [long] when the other side is [short], so that a patch is
     * about as wide as it is tall: round, where a square grid on a phone made tall pillars.
     */
    fun along(long: Float, short: Float): Int =
        if (short <= 0f || long <= 0f) ACROSS else Math.round(ACROSS * long / short).coerceIn(ACROSS, MOST_ALONG)

    /** The range a row breathes with, rows counted from the top: the top row the top, the bottom third the bass. */
    fun bandOfRow(row: Int, rows: Int): Int = when {
        row >= rows - max(1, rows / 3) -> MusicLevels.BASS
        row == 0 -> MusicLevels.HIGH
        else -> MusicLevels.MID
    }

    /**
     * The colour of each cell of a grid of [columns] by [rows] laid over the cover, row by row
     * from the top left, as opaque ARGB: the cell's average, made [COLOUR] times as colourful.
     * [pixels] is the cover, [width] by [height], row by row.
     */
    fun patches(pixels: IntArray, width: Int, height: Int, columns: Int, rows: Int): IntArray {
        val out = IntArray(columns * rows)
        if (width <= 0 || height <= 0 || pixels.size < width * height) return out.also { it.fill(GREY) }
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = column * width / columns
                val right = max(left + 1, (column + 1) * width / columns)
                val top = row * height / rows
                val bottom = max(top + 1, (row + 1) * height / rows)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (y in top until min(bottom, height)) {
                    for (x in left until min(right, width)) {
                        val p = pixels[y * width + x]
                        r += (p shr 16) and 0xff
                        g += (p shr 8) and 0xff
                        b += p and 0xff
                        n++
                    }
                }
                out[row * columns + column] =
                    if (n == 0) GREY else colourful(argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()), COLOUR)
            }
        }
        return out
    }

    /** The colour of the whole cover, for whatever the patches leave uncovered. */
    fun average(patches: IntArray): Int {
        if (patches.isEmpty()) return GREY
        var r = 0
        var g = 0
        var b = 0
        for (p in patches) {
            r += (p shr 16) and 0xff
            g += (p shr 8) and 0xff
            b += p and 0xff
        }
        return argb(r / patches.size, g / patches.size, b / patches.size)
    }

    /**
     * The cover's strongest colour, for the glow: the patch that is the most colourful without
     * being nearly black. A grey cover gives its lightest grey.
     */
    fun strongest(patches: IntArray): Int {
        var best = GREY
        var bestScore = -1f
        for (p in patches) {
            val r = ((p shr 16) and 0xff) / 255f
            val g = ((p shr 8) and 0xff) / 255f
            val b = (p and 0xff) / 255f
            val most = max(r, max(g, b))
            val least = min(r, min(g, b))
            val score = (most - least) * (0.35f + 0.65f * most) + 0.02f * most
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    /** [color] with its light multiplied by [light], which may be above 1; no channel overflows. */
    fun lit(color: Int, light: Float): Int {
        val r = min(255f, ((color shr 16) and 0xff) * light).toInt()
        val g = min(255f, ((color shr 8) and 0xff) * light).toInt()
        val b = min(255f, (color and 0xff) * light).toInt()
        return argb(max(0, r), max(0, g), max(0, b))
    }

    /**
     * [color] with each channel [by] times as far from its grey: more colourful above 1, grey at
     * 0. A grey stays the grey it is, and nothing leaves the range.
     */
    fun colourful(color: Int, by: Float): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        val grey = 0.299f * r + 0.587f * g + 0.114f * b
        fun channel(c: Int) = (grey + (c - grey) * by + 0.5f).toInt().coerceIn(0, 255)
        return argb(channel(r), channel(g), channel(b))
    }

    /** [from] a part [t] of the way to [to], channel by channel. */
    fun between(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xff
            val b = (to shr shift) and 0xff
            return (a + (b - a) * k + 0.5f).toInt()
        }
        return argb(channel(16), channel(8), channel(0))
    }

    /**
     * [from] a part [t] of the way to [to], but always at least one shade nearer in every channel
     * that is not there yet. Repeated, it arrives; [between] alone stops a few shades short, where
     * a part of what is left rounds to nothing.
     */
    fun nearer(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xff
            val b = (to shr shift) and 0xff
            if (a == b) return a
            val step = ((b - a) * k).toInt()
            return if (b > a) min(b, a + max(1, step)) else max(b, a + min(-1, step))
        }
        return argb(channel(16), channel(8), channel(0))
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    const val GREY = 0xff595959.toInt()
}

/**
 * Where the picture is at one moment, for a grid of [columns] by [rows] patches. [step] moves it on
 * by the time since the last frame; the getters say where each patch is to be drawn. One instance
 * lives as long as the player's screen keeps its shape.
 */
class LivingMotion(val columns: Int = LivingField.ACROSS, val rows: Int = LivingField.ACROSS) {
    val count = columns * rows

    /** The levels as the picture shows them: up at once, down slowly. */
    val shown = FloatArray(MusicLevels.BANDS)

    /** The picture's own time, in seconds. It runs with the music and stops with it. */
    var drift = 0f
        private set

    /** How fast [drift] runs against the clock: 1 with music playing, down to 0 without. */
    var pace = 0f
        private set

    /**
     * The colours on screen, turning into [turnTo]'s cover: the [count] patches, then [under],
     * then [glow].
     */
    val colors = IntArray(count + 2) { LivingField.GREY }
    private var wanted = IntArray(count + 2) { LivingField.GREY }
    private var coverKnown = false

    /** What shows where the patches leave anything uncovered: the cover's colour as a whole. */
    val under get() = colors[count]

    /** The colour of the glow on the bottom edge: the cover's strongest. */
    val glow get() = colors[count + 1]

    /** The next cover's patches. The first cover is shown at once, later ones are turned into. */
    fun turnTo(patches: IntArray) {
        val next = IntArray(count + 2)
        patches.copyInto(next, endIndex = minOf(count, patches.size))
        for (i in patches.size until count) next[i] = LivingField.GREY
        val cover = next.copyOf(count)
        next[count] = LivingField.lit(LivingField.average(cover), LivingField.REST_LIGHT)
        next[count + 1] = LivingField.lit(LivingField.strongest(cover), LivingField.GLOW_LIGHT)
        wanted = next
        if (!coverKnown) {
            wanted.copyInto(colors)
            coverKnown = true
        }
    }

    /**
     * Moves on by [seconds]. [levels] is what is being heard, or null when there is nothing to go
     * by (paused, or audio that cannot be measured); [playing] says whether the music runs at all.
     * With music but no levels the picture still drifts, it just does not breathe.
     */
    fun step(seconds: Float, levels: FloatArray?, playing: Boolean) {
        val dt = seconds.coerceIn(0f, 0.1f)          // a long gap between frames is not a leap in the picture
        for (band in 0 until MusicLevels.BANDS) {
            val to = levels?.get(band)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val within = if (to > shown[band]) LivingField.RISE[band] else LivingField.FALL[band]
            shown[band] += (to - shown[band]) * part(dt, within)
        }
        pace += ((if (playing) 1f else 0f) - pace) * part(dt, 0.7f)
        // quicker when the music is loud, never still while it plays
        drift += dt * pace * (0.55f + 0.9f * max(shown[MusicLevels.BASS], shown[MusicLevels.MID]))
        if (dt > 0f) {
            val turn = part(dt, LivingField.COVER_TURN)
            for (i in colors.indices) {
                if (colors[i] != wanted[i]) colors[i] = LivingField.nearer(colors[i], wanted[i], turn)
            }
        }
    }

    /** Straight to rest, with the cover's colours as they are: for when nothing is to move at all. */
    fun settle() {
        wanted.copyInto(colors)
        shown.fill(0f)
        pace = 0f
    }

    /** True when nothing would change on another [step] without music: the frames can stop. */
    fun atRest(): Boolean = pace < 0.01f && shown.all { it < 0.005f } && colors.contentEquals(wanted)

    /** The share of the way covered in [dt] by something that takes [within] seconds to get most of the way. */
    private fun part(dt: Float, within: Float) = 1f - exp(-dt / within)

    // Where patch [i] is drawn. x and y in parts of the width and height, the radius in cells.

    fun x(i: Int): Float {
        val home = (i % columns + 0.5f) / columns
        val wander = LivingField.WANDER / columns * sin(drift * (0.21f + 0.037f * (i % 5)) + i * 1.7f)
        return lean(home + wander)
    }

    fun y(i: Int): Float {
        val home = (i / columns + 0.5f) / rows
        val wander = LivingField.WANDER / rows * cos(drift * (0.17f + 0.029f * (i % 7)) + i * 2.3f)
        return lean(home + wander)
    }

    /** Pushed away from the middle on a kick. */
    private fun lean(at: Float) = 0.5f + (at - 0.5f) * (1f + LivingField.LEAN * shown[MusicLevels.BASS])

    /** How far patch [i] is taken by the range its row breathes with, 0 to 1. */
    private fun taken(i: Int): Float {
        val band = LivingField.bandOfRow(i / columns, rows)
        return shown[band] * LivingField.SHARE[band]
    }

    fun radius(i: Int): Float {
        val level = taken(i)
        val ripple = 0.04f * sin(drift * 0.31f + i * (2f * PI.toFloat() / 5f))
        return LivingField.REST_RADIUS * (1f + LivingField.SWELL * level + 0.08f * shown[MusicLevels.BASS] + ripple)
    }

    fun color(i: Int): Int = LivingField.lit(colors[i], LivingField.REST_LIGHT + LivingField.FULL_LIGHT * taken(i))

    /** The glow at the bottom edge: how tall it stands in parts of the height, and how strong it is, 0 to 1. */
    fun glowHeight(): Float = 0.30f + 0.38f * shown[MusicLevels.BASS]
    fun glowStrength(): Float = 0.05f + 0.65f * shown[MusicLevels.BASS]
}
