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

    /**
     * Colours take this long to turn into the next cover's, slowly at first and at the end
     * ([eased]). It was under half a second and fastest in its first frame, which read as a cut
     * with a tail on it.
     */
    const val COVER_TURN = 1.6f

    /** A part [t] of the way, taken slowly at both ends: 0 at 0, 1 at 1, and level at each. */
    fun eased(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /**
     * What a range shows is made of two things: how loud it is, and how much louder than it has
     * been over the last [USUAL_OVER] seconds, which is a hit. A song whose bass never lets up is
     * loud all the time, and shown by loudness alone it held the picture swollen and still; it is
     * the kick on top of that bass that has to be seen. So loudness counts for [LEVEL_SHARE] and
     * a hit for [HIT_SHARE] times its size.
     */
    const val USUAL_OVER = 0.4f
    const val LEVEL_SHARE = 0.4f
    const val HIT_SHARE = 1.8f

    /**
     * How strongly the picture answers the music is a setting, from 0 (it drifts and nothing else)
     * to 1. [reach] turns it into the number every reaction is multiplied by: 1.5 in the middle,
     * where the setting starts, and 3 at the top. However far that is turned up, a patch swells by
     * no more than [MOST_SWELL] of itself and is lit no more than [MOST_LIGHT] times.
     */
    const val DEFAULT_STRENGTH = 0.5f
    const val MOST_SWELL = 0.9f
    const val MOST_LIGHT = 1.7f

    fun reach(strength: Float): Float = if (strength.isNaN()) reach(DEFAULT_STRENGTH) else 3f * strength.coerceIn(0f, 1f)

    /**
     * How softly the picture follows the music is the other setting, 0 to 1. [ease] turns it into
     * what every rise and fall time above is multiplied by: a quarter at the bottom, where the
     * picture jumps with every hit, 1 in the middle, and four times at the top, where a kick is a
     * slow swell and the whole thing breathes more than it beats.
     */
    const val DEFAULT_SMOOTHING = 0.5f

    fun ease(smoothing: Float): Float =
        if (smoothing.isNaN()) 1f else Math.pow(4.0, 2.0 * smoothing.coerceIn(0f, 1f) - 1.0).toFloat()

    /**
     * Every patch takes this much of the bass, whatever range its row breathes with. The bass has
     * the top and the bottom of the screen to itself; the rows between are the voice's, and take
     * just enough of a kick that the whole picture is felt to move as one.
     */
    const val KICK_EVERYWHERE = 0.3f

    /**
     * The cover takes up most of the player, and what goes on behind it is not seen. So most of
     * the reaction is put where it shows: an aura on the cover's edge, half of it hidden behind the
     * cover and half spilling out round it, in the colours the cover has along that edge.
     *
     * Its radius is in parts of the cover's side: a thin halo at rest, [AURA_SWELL] more for a
     * full level at a reach of 1, and never more than [AURA_MOST] in all.
     */
    const val AURA_REST = 0.10f
    const val AURA_SWELL = 0.16f
    const val AURA_MOST = 0.62f

    /**
     * One point of the aura.
     *
     * @param x where on the cover it sits, in parts of the cover's width and height: always on an edge
     * @param patch the patch whose colour it takes, which is the part of the cover beside it
     * @param band the range it answers besides the bass: the top edge the top of the spectrum, the sides the middle
     * @param far how far round the cover it is from where a kick lands, 0 to 1. A kick lands in the
     *   middle of the top and of the bottom edge and runs round from there, which is what makes
     *   the halo look pushed rather than switched on.
     */
    class AuraPoint(val x: Float, val y: Float, val patch: Int, val band: Int, val far: Float)

    /** The aura for a grid of [columns] by [rows]: one point for every patch on the grid's rim. */
    fun aura(columns: Int, rows: Int): List<AuraPoint> {
        // As the rows have it: the bass along the cover's top and bottom, the voice down its sides
        // and the top of the spectrum at the middle of each side. A kick lands in the middle of
        // the top and of the bottom edge and runs round to meet half way down the sides, which is
        // one cover side away: half a side to the corner, half a side down.
        val points = ArrayList<AuraPoint>(2 * (columns + rows))
        for (row in 0 until rows) {
            val y = (row + 0.5f) / rows
            val band = if (kotlin.math.abs(y - 0.5f) < 1f / 6f) MusicLevels.HIGH else MusicLevels.MID
            val far = 0.5f + min(y, 1f - y)
            points += AuraPoint(0f, y, row * columns, band, far)
            points += AuraPoint(1f, y, row * columns + columns - 1, band, far)
        }
        for (column in 0 until columns) {
            val x = (column + 0.5f) / columns
            val far = kotlin.math.abs(x - 0.5f)
            points += AuraPoint(x, 0f, column, MusicLevels.BASS, far)
            points += AuraPoint(x, 1f, (rows - 1) * columns + column, MusicLevels.BASS, far)
        }
        return points
    }

    /**
     * How many patches along a side [long] when the other side is [short], so that a patch is
     * about as wide as it is tall: round, where a square grid on a phone made tall pillars.
     */
    fun along(long: Float, short: Float): Int =
        if (short <= 0f || long <= 0f) ACROSS else Math.round(ACROSS * long / short).coerceIn(ACROSS, MOST_ALONG)

    /**
     * The range a row breathes with, rows counted from the top: the rows at the top and at the
     * bottom of the screen the bass, the rows between them the voice, and the very middle the top
     * of the spectrum. The bass is what is felt, and it frames the picture; what is sung and
     * played over it sits inside that frame, where the eye already is.
     */
    fun bandOfRow(row: Int, rows: Int): Int {
        if (rows <= 2) return MusicLevels.BASS
        val outer = max(1, (rows + 1) / 4)
        if (row < outer || row >= rows - outer) return MusicLevels.BASS
        val between = rows - 2 * outer
        val inMiddle = if (between % 2 == 1) row == rows / 2 else row == rows / 2 - 1 || row == rows / 2
        return if (between >= 3 && inMiddle) MusicLevels.HIGH else MusicLevels.MID
    }

    /**
     * The colour of each cell of a grid of [columns] by [rows] laid over the cover, row by row
     * from the top left, as opaque ARGB: the cell's average, made [COLOUR] times as colourful.
     * [pixels] is the cover, [width] by [height], row by row.
     */
    fun patches(pixels: IntArray, width: Int, height: Int, columns: Int, rows: Int): IntArray {
        val cells = cells(pixels, width, height, columns, rows)
        return IntArray(cells.size) { colourful(cells[it], COLOUR) }
    }

    /** How many main colours a cover is reduced to: one for each range. */
    const val MAINS = MusicLevels.BANDS

    /** Two colours count as unlike when their channels differ by this much in all, of 765. */
    private const val UNLIKE = 90

    /**
     * The cover's main colours, strongest first: [MAINS] of them, each unlike the others. The
     * other way of colouring the picture, for which the cover is a palette and not a picture: the
     * strongest colour goes to the bass, the next to the voice, the third to the top.
     *
     * Strongest is the most colourful that is not nearly black, as for the glow, and not the most
     * common: on most covers the most common colour is the black round the subject. A cover with
     * fewer than three colours to tell apart is filled up with shades of its first, so the ranges
     * can still be told from each other.
     */
    fun mainColours(pixels: IntArray, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return IntArray(MAINS) { GREY }
        val picked = ArrayList<Int>(MAINS)
        for (cell in cells(pixels, width, height, 8, 8).sortedByDescending { strength(it) }) {
            if (picked.size == MAINS) break
            if (picked.none { unlike(it, cell) < UNLIKE }) picked += cell
        }
        val first = picked[0]
        if (picked.size < 2) picked += lit(first, 0.62f)
        if (picked.size < 3) picked += lit(first, 1.45f).let { if (unlike(it, first) < 12) lit(first, 0.35f) else it }
        return IntArray(MAINS) { colourful(picked[it], COLOUR) }
    }

    /**
     * The patches of a grid of [columns] by [rows] coloured from [mainColours]: each row takes the
     * colour of the range it breathes with, a little lighter or darker from patch to patch so that
     * a row is not one flat band.
     */
    fun mainPatches(pixels: IntArray, width: Int, height: Int, columns: Int, rows: Int): IntArray {
        val mains = mainColours(pixels, width, height)
        return IntArray(columns * rows) {
            val row = it / columns
            val column = it % columns
            lit(mains[bandOfRow(row, rows)], 0.90f + 0.05f * ((column * 2 + row) % 5))
        }
    }

    private fun unlike(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)) +
            kotlin.math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)) +
            kotlin.math.abs((a and 0xff) - (b and 0xff))

    /** The plain average colour of each cell, row by row from the top left. */
    private fun cells(pixels: IntArray, width: Int, height: Int, columns: Int, rows: Int): IntArray {
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
                out[row * columns + column] = if (n == 0) GREY else argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
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
            val score = strength(p)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    /** How strong a colour is: how colourful, counted for more the lighter it is, with light alone breaking a tie between greys. */
    private fun strength(color: Int): Float {
        val r = ((color shr 16) and 0xff) / 255f
        val g = ((color shr 8) and 0xff) / 255f
        val b = (color and 0xff) / 255f
        val most = max(r, max(g, b))
        val least = min(r, min(g, b))
        return (most - least) * (0.35f + 0.65f * most) + 0.02f * most
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

    /** The setting, 0 to 1 (see [LivingField.reach]). */
    var strength = LivingField.DEFAULT_STRENGTH
    private val reach get() = LivingField.reach(strength)

    /** The other setting, 0 to 1 (see [LivingField.ease]). */
    var smoothing = LivingField.DEFAULT_SMOOTHING

    /** The aura round the cover, and how far each of its points is taken, 0 to 1. */
    val aura = LivingField.aura(columns, rows)
    val auraShown = FloatArray(aura.size)

    /** Whether there is a cover on screen to put the aura round. Without one it fades away. */
    var coverThere = false

    /** How much of the aura is there, 0 to 1: it comes and goes with the cover over a quarter of a second. */
    var auraPresence = 0f
        private set

    /**
     * The colours on screen, turning into [turnTo]'s cover: the [count] patches, then [under],
     * then [glow].
     */
    val colors = IntArray(count + 2) { LivingField.GREY }
    private var wanted = IntArray(count + 2) { LivingField.GREY }
    private val turnedFrom = IntArray(count + 2) { LivingField.GREY }
    private var turn = 1f
    private var coverKnown = false

    /** What each range has been lately, to tell a hit from a level. */
    private val usual = FloatArray(MusicLevels.BANDS)

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
            turn = 1f
        } else {
            // from what is on screen now, so a cover that arrives while another is still being
            // turned into does not jump
            colors.copyInto(turnedFrom)
            turn = 0f
        }
    }

    /**
     * Moves on by [seconds]. [levels] is what is being heard, or null when there is nothing to go
     * by (paused, or audio that cannot be measured); [playing] says whether the music runs at all.
     * With music but no levels the picture still drifts, it just does not breathe.
     */
    fun step(seconds: Float, levels: FloatArray?, playing: Boolean) {
        val dt = seconds.coerceIn(0f, 0.1f)          // a long gap between frames is not a leap in the picture
        val ease = LivingField.ease(smoothing)
        for (band in 0 until MusicLevels.BANDS) {
            val level = levels?.get(band)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val hit = max(0f, level - usual[band])
            usual[band] += (level - usual[band]) * part(dt, LivingField.USUAL_OVER)
            val to = min(1f, LivingField.LEVEL_SHARE * level + LivingField.HIT_SHARE * hit)
            val within = if (to > shown[band]) LivingField.RISE[band] else LivingField.FALL[band]
            shown[band] += (to - shown[band]) * part(dt, within * ease)
        }
        for (i in aura.indices) {
            val point = aura[i]
            val to = max(shown[MusicLevels.BASS], 0.6f * shown[point.band] * LivingField.SHARE[point.band])
            // later and softer the further round the cover from where the kick lands
            val within = if (to > auraShown[i]) 0.012f + 0.11f * point.far else 0.20f + 0.16f * point.far
            auraShown[i] += (to - auraShown[i]) * part(dt, within * ease)
        }
        auraPresence += ((if (coverThere) 1f else 0f) - auraPresence) * part(dt, 0.12f)
        pace += ((if (playing) 1f else 0f) - pace) * part(dt, 0.7f)
        // quicker when the music is loud, never still while it plays
        drift += dt * pace * (0.55f + 0.9f * max(shown[MusicLevels.BASS], shown[MusicLevels.MID]))
        if (turn < 1f) {
            turn = min(1f, turn + dt / LivingField.COVER_TURN)
            val by = LivingField.eased(turn)
            for (i in colors.indices) colors[i] = LivingField.between(turnedFrom[i], wanted[i], by)
        }
    }

    /**
     * How far ahead of what is being heard the levels should be read, in seconds. The audio is
     * measured well before it is heard, so the picture can be started early by what it takes to
     * get it on screen: a frame or two on its way to the display, and the time a rise takes to be
     * seen. Read at the moment itself, the picture lands after the sound, every time.
     */
    fun lead(): Float = 0.045f + 0.6f * LivingField.RISE[MusicLevels.BASS] * LivingField.ease(smoothing)

    /** Straight to rest, with the cover's colours as they are: for when nothing is to move at all. */
    fun settle() {
        wanted.copyInto(colors)
        turn = 1f
        usual.fill(0f)
        shown.fill(0f)
        auraShown.fill(0f)
        auraPresence = if (coverThere) 1f else 0f
        pace = 0f
    }

    /** True when nothing would change on another [step] without music: the frames can stop. */
    fun atRest(): Boolean = pace < 0.01f && shown.all { it < 0.005f } && auraShown.all { it < 0.005f } &&
        kotlin.math.abs(auraPresence - (if (coverThere) 1f else 0f)) < 0.01f && turn >= 1f && colors.contentEquals(wanted)

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
    private fun lean(at: Float) = 0.5f + (at - 0.5f) * (1f + LivingField.LEAN * reach * shown[MusicLevels.BASS])

    /** How far patch [i] is taken, 0 to 1: by the range its row breathes with, and by the bass wherever it is. */
    private fun taken(i: Int): Float {
        val band = LivingField.bandOfRow(i / columns, rows)
        return max(shown[band] * LivingField.SHARE[band], LivingField.KICK_EVERYWHERE * shown[MusicLevels.BASS])
    }

    fun radius(i: Int): Float {
        val swell = min(LivingField.MOST_SWELL, reach * (LivingField.SWELL * taken(i) + 0.08f * shown[MusicLevels.BASS]))
        val ripple = 0.04f * sin(drift * 0.31f + i * (2f * PI.toFloat() / 5f))
        return LivingField.REST_RADIUS * (1f + swell + ripple)
    }

    /** Lighter on a beat, and more colourful with it: light alone washes a colour out towards white. */
    fun color(i: Int): Int {
        val by = reach * taken(i)
        val light = min(LivingField.MOST_LIGHT, LivingField.REST_LIGHT + LivingField.FULL_LIGHT * by)
        return LivingField.lit(LivingField.colourful(colors[i], 1f + 0.18f * min(by, 3f)), light)
    }

    /** The glow at the bottom edge: how tall it stands in parts of the height, and how strong it is, 0 to 1. */
    fun glowHeight(): Float = 0.30f + min(0.5f, 0.25f * reach * shown[MusicLevels.BASS])
    fun glowStrength(): Float = min(1f, 0.05f + 0.43f * reach * shown[MusicLevels.BASS])

    // The aura's point [i]: its radius in parts of the cover's side, how strong it is from 0 to 1, its colour.

    fun auraRadius(i: Int): Float =
        min(LivingField.AURA_MOST, LivingField.AURA_REST + LivingField.AURA_SWELL * reach * auraShown[i])

    fun auraStrength(i: Int): Float = auraPresence * min(1f, 0.30f + 0.40f * reach * auraShown[i])

    fun auraColor(i: Int): Int {
        val by = min(reach * auraShown[i], 3f)
        return LivingField.lit(LivingField.colourful(colors[aura[i].patch], 1.15f + 0.15f * by), min(LivingField.MOST_LIGHT, 1f + 0.30f * by))
    }
}
