/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.playback.MusicLevels
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The living background's picture, with nothing of Android in it: what the cover is reduced to,
 * and how that moves to the music. LivingBackground.kt draws it.
 *
 * The cover becomes a few patches of colour, and the patches breathe with the music: the rows at
 * the top and the bottom of the screen with the bass, the rows between them with the voice, the
 * very middle with the top of the spectrum ([bandOfRow]). A glow rises from the top and bottom
 * edges with the bass, and an aura stands round the cover, which hides most of the rest.
 *
 * There are two ways of colouring it. As a picture of the cover: each patch is the colour of its
 * part of the cover, the way the cover reads when it is blurred until nothing else is left of it
 * ([patches]), and every patch takes a share of the kick, so that the picture moves as one. Or
 * with the cover as a palette: three of its colours, picked to be told apart, one to a range
 * ([mainColours]), and each range moves its own colour and no other ([OWN_SHARE]). The ranges
 * then do not lie in rows but in ribbons with curved edges that flow with the music ([flowShares]).
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
     * Coloured from the cover's main colours, every range has a colour of its own, and the point
     * of that is to tell the ranges apart. So there each one moves its own part of the picture
     * and nothing else: the voice's rows take no share of the kick, and the top of the spectrum
     * is shown nearly in full, where it has a colour to itself and is no longer a flicker on
     * somebody else's. The three are told apart by how they move as well. The bass pushes: it
     * swells the most. The voice glows: it swells less and lights more. The top glints: it hardly
     * swells and lights the most. And a range that is quiet sits a little darker than the bass
     * does, so that it is seen to come in. Bass, middle, top.
     */
    val OWN_SHARE = floatArrayOf(1f, 1f, 0.85f)
    val OWN_SWELL = floatArrayOf(1f, 0.7f, 0.4f)
    val OWN_LIGHT = floatArrayOf(0.9f, 1.15f, 1.4f)
    val OWN_REST = floatArrayOf(0.80f, 0.74f, 0.68f)

    /**
     * How much of a range's plain loudness shows when the ranges are kept apart ([LEVEL_SHARE]
     * when they are not). A voice is held notes more than it is hits, and at the bass's share a
     * sung line barely lit its rows.
     */
    val OWN_LEVEL = floatArrayOf(0.4f, 0.6f, 0.45f)

    /**
     * The song's shape, on top of its levels: a build-up, the drop and the part the drop sets off
     * (MusicLevels.TENSION, DROP and DRIVE). Bar for bar the part after the drop is no louder
     * than the part before the breakdown was, so the levels alone show the two alike, and the
     * drop itself as one more kick.
     *
     * A build-up gathers the picture. The patches draw in towards the middle and shrink
     * ([GATHER_IN], [GATHER_SHRINK]: by that much at full tension and a reach of 1), the picture's
     * own time runs faster, so that everything in it flows and wanders quicker ([GATHER_PACE],
     * added to its pace), and the top of the spectrum, which is what a build-up is made of,
     * charges: it is shown as if it were that much louder ([GATHER_CHARGE]). All of it comes on
     * over [GATHER_RISE] seconds, with the tension, and is let go of in [GATHER_FALL], which is
     * the drop.
     */
    const val GATHER_IN = 0.05f
    const val GATHER_SHRINK = 0.06f
    const val GATHER_PACE = 0.8f
    const val GATHER_CHARGE = 0.5f
    const val GATHER_RISE = 0.6f
    const val GATHER_FALL = 0.12f

    /**
     * The drop is one burst. Everything is pushed outwards ([BURST_PUSH], as [LEAN] is for a
     * kick), every patch takes it as a full kick whatever range it breathes with, the halo round
     * the cover reaches out by a full kick's worth more, the bass floods in over the voice as far
     * as the top's line where each has its colour ([BURST_FLOOD]: the bass is taken that much
     * further), and the whole picture is lit once ([BURST_LIGHT], on top of a kick's light). It is
     * the one flash that is wanted. It is held for [BURST_HOLD] seconds, long enough to get there
     * at a kick's pace, and gone over [BURST_FALL]. However hard, nothing is pushed out by more
     * than [MOST_PUSH] of its distance from the middle.
     */
    const val BURST_PUSH = 0.08f
    const val BURST_FLOOD = 1.2f
    const val BURST_LIGHT = 0.3f
    const val BURST_HOLD = 0.1f
    const val BURST_FALL = 0.45f
    const val MOST_PUSH = 0.35f

    /**
     * While the drive lasts everything answers the music that much more ([DRIVE_MORE]: the reach
     * is 1 and that times what the setting makes it), the patches are bigger even between two
     * kicks ([DRIVE_SWELL] at a reach of 1) and the picture's time runs faster ([DRIVE_PACE]). It
     * is there with the drop, over [DRIVE_RISE] seconds, and goes over [DRIVE_FALL].
     */
    const val DRIVE_MORE = 0.35f
    const val DRIVE_SWELL = 0.05f
    const val DRIVE_PACE = 0.5f
    const val DRIVE_RISE = 0.1f
    const val DRIVE_FALL = 0.8f

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
    fun along(long: Float, short: Float, across: Int = ACROSS, most: Int = MOST_ALONG): Int =
        if (short <= 0f || long <= 0f) across else Math.round(across * long / short).coerceIn(across, most)

    /**
     * Patches along the short side, and no more than so many along the long one, when the ranges
     * lie in ribbons ([flowShares]): the ribbons' edges are curves, and three patches across
     * cannot draw a curve.
     */
    const val FLOW_ACROSS = 5
    const val FLOW_MOST_ALONG = 11

    /**
     * A patch's radius at rest where the ranges lie in ribbons, in cells. More than [REST_RADIUS]:
     * there are more and smaller patches, each a sample of a colour that changes smoothly from
     * place to place, and at the cover's radius they showed as dots.
     */
    const val FLOW_RADIUS = 1.35f

    /** An edge between two ranges is this soft, in parts of the picture's height. */
    const val FLOW_EDGE = 0.055f

    private fun wave(turns: Float) = sin(2f * PI.toFloat() * turns)

    /**
     * Where the top of the spectrum's line is at [x], in parts of the height. It wanders across
     * the middle of the picture, a little under half way down, which on a phone is just under
     * the cover: one slow wave and a quicker, smaller one, each flowing its own way with [time].
     */
    fun flowLine(x: Float, time: Float): Float =
        0.53f + 0.055f * wave(0.55f * x + 0.050f * time) + 0.030f * wave(1.25f * x - 0.071f * time + 0.21f)

    /**
     * Half the thickness of the top's ribbon at [x]: thicker and thinner along its length, and
     * thicker with the cymbals ([top], 0 and up). Never thinner than a row of patches is tall, or
     * there would be stretches of it that no patch stood in.
     */
    fun flowTop(x: Float, time: Float, top: Float): Float =
        0.068f + 0.020f * wave(0.9f * x + 0.063f * time + 0.1f) + 0.045f * top

    /**
     * How far the voice reaches from the top's line at [x], above it or below: an edge with a
     * curve of its own on each side, so that the two are never each other's mirror. No wave here
     * or in [flowLine] has more than about one and a quarter turns across the picture: five
     * patches across cannot draw a tighter one, and it came out as steps. A voice
     * ([voice], 0 and up) pushes the edge out, a kick ([bass]) pushes it in: the bass comes in
     * from both ends of the screen along a curved front.
     */
    fun flowVoice(x: Float, time: Float, above: Boolean, voice: Float, bass: Float): Float {
        val still = if (above) {
            0.21f + 0.055f * wave(0.7f * x - 0.045f * time + 0.33f) + 0.025f * wave(1.15f * x + 0.080f * time + 0.70f)
        } else {
            0.23f + 0.065f * wave(0.85f * x + 0.057f * time + 0.62f) + 0.025f * wave(1.3f * x - 0.090f * time + 0.15f)
        }
        return still + 0.06f * voice - 0.07f * bass
    }

    /**
     * Where the bass begins at [x], as a distance from the top's line: the voice's own reach
     * ([flowVoice]), but never so little that the voice has no room left between the top's ribbon
     * and the bass, however hard the kick. Only a drop takes that room ([flood], 0 to 1): for
     * that moment the bass comes in as far as the top's ribbon.
     */
    fun flowReach(x: Float, time: Float, above: Boolean, voice: Float, bass: Float, top: Float, flood: Float = 0f): Float =
        max(flowTop(x, time, top) + (FLOW_EDGE + 0.02f) * (1f - flood.coerceIn(0f, 1f)), flowVoice(x, time, above, voice, bass))

    /**
     * How much of the point at [x], [y] (parts of the width and height) is each range's, into
     * [into]: bass, voice, top, adding up to one.
     *
     * With a colour to each range the ranges do not lie in rows. The top of the spectrum runs
     * along a line that wanders across the middle ([flowLine]); the voice lies either side of it
     * as far as an edge that curves ([flowVoice]); the bass has everything beyond, which is the
     * top and the bottom of the screen. Line and edges are waves and flow with [time], the
     * picture's own, which runs with the music: nothing in the picture is a straight line, and
     * nothing stays where it was. Rows of colour were what this replaced, and they looked ruled.
     *
     * [bass], [voice] and [top] are how far each range is taken by the music at this moment, and
     * [flood] how much of a drop there is ([flowReach]).
     */
    fun flowShares(x: Float, y: Float, time: Float, bass: Float, voice: Float, top: Float, into: FloatArray, flood: Float = 0f) {
        val line = flowLine(x, time)
        val away = abs(y - line)
        val thick = flowTop(x, time, top)
        val reach = flowReach(x, time, y < line, voice, bass, top, flood)
        val ofTop = 1f - eased((away - (thick - FLOW_EDGE / 2f)) / FLOW_EDGE)
        val ofBass = eased((away - (reach - FLOW_EDGE / 2f)) / FLOW_EDGE)
        into[MusicLevels.BASS] = ofBass
        into[MusicLevels.HIGH] = ofTop
        into[MusicLevels.MID] = max(0f, 1f - ofBass - ofTop)
    }

    /** [colors] mixed by [shares], channel by channel: as many of each as there are ranges. */
    fun mixed(colors: IntArray, from: Int, shares: FloatArray): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        for (k in shares.indices) {
            val c = colors[from + k]
            r += shares[k] * ((c shr 16) and 0xff)
            g += shares[k] * ((c shr 8) and 0xff)
            b += shares[k] * (c and 0xff)
        }
        return argb((r + 0.5f).toInt().coerceIn(0, 255), (g + 0.5f).toInt().coerceIn(0, 255), (b + 0.5f).toInt().coerceIn(0, 255))
    }

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
     * The range a patch breathes with: its row's ([bandOfRow]), in a picture taller than it is
     * wide. A wide one, a phone on its side or the strip in Settings, has too few rows to give
     * the top of the spectrum one, so there the middle third of the voice's row is the top's:
     * still the very middle of the picture.
     */
    fun bandOfCell(column: Int, row: Int, columns: Int, rows: Int): Int {
        val band = bandOfRow(row, rows)
        if (band != MusicLevels.MID || rows >= 5 || columns < 5) return band
        return if (abs((column + 0.5f) / columns - 0.5f) < 1f / 6f) MusicLevels.HIGH else MusicLevels.MID
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

    /** The colour wheel is cut into this many parts to find a cover's hues: fifteen degrees each. */
    private const val HUES = 24

    /** A hue counts when it weighs this much of the heaviest. Less is a speck, or the fringe where two colours meet. */
    private const val WORTH = 0.08f

    /** A pixel has a hue when it is at least this colourful, in OKLab's measure (a pure red is 0.26). Under it, it is a grey. */
    private const val HUED = 0.02f

    /**
     * In the dark a pixel has to be this colourful to have a hue, and it is in the dark under
     * this lightness (OKLab's, 0 to 1). Near black that measure stretches differences nobody can
     * see: a black of 6, 6, 20 comes out as colourful as a slate that is plainly bluish, and a
     * black cover with grey lettering was given a royal blue for its accent.
     */
    private const val DARK = 0.25f
    private const val HUED_IN_THE_DARK = 0.06f

    /** A hue is one of the cover's colours when this much of the cover has it. Less is a few stray pixels. */
    private const val SEEN_WORTH = 0.015f

    /**
     * A main colour is at most this many times as colourful as it is on the cover. Making a dark
     * colour light enough to be seen multiplies its tint along with its light, and a charcoal
     * with a faint cast came out a steel blue nobody could find on the cover.
     */
    private const val MOST_GAIN = 1.6f

    /** The cover is a coloured one when this much of it has a hue at all. Less, and it is a grey cover with an accent. */
    private const val COLOURED = 0.15f

    /** White, or nearly, is a colour of the cover's when this much of the cover is that. */
    private const val LIGHT_WORTH = 0.06f

    /**
     * A main colour's lightest channel is at least this, of 255: a navy is shown as a blue, or
     * its range could not be seen to light up. A grey is held to less, so that a black cover
     * still looks like one.
     */
    private const val BODY = 150
    private const val GREY_BODY = 96

    /** Two colours this far apart or less ([apart]) cannot be told from each other once they are blurred and moving. */
    private const val TOO_ALIKE = 0.07f

    /** One colour a cover could be told by: how much of the cover's colour it is, and where it lies in OKLab. */
    private class Candidate(val color: Int, val weight: Float) {
        val lab = FloatArray(3).also { oklab((color shr 16) and 0xff, (color shr 8) and 0xff, color and 0xff, it) }
    }

    /**
     * How far apart two colours look, with their light counting for half. The picture lights
     * every colour up and down with the music, so a dark red on a kick is a light red at rest:
     * what tells two ranges apart for good is their hue and how colourful they are.
     */
    private fun apart(x: Candidate, y: Candidate): Float {
        val light = 0.5f * (x.lab[0] - y.lab[0])
        val a = x.lab[1] - y.lab[1]
        val b = x.lab[2] - y.lab[2]
        return sqrt(light * light + a * a + b * b)
    }

    /**
     * The cover's main colours, one for each range: the bass's, the voice's, the top's. The other
     * way of colouring the picture, for which the cover is a palette and not a picture.
     *
     * The cover's hues are weighed round a colour wheel on which equal steps look equally far
     * apart (OKLab's), a vivid pixel for more than a dull one. The heaviest is the bass's, which
     * has the most of the screen, so that the picture as a whole is the cover's colour. The other
     * two are the pair, out of the cover's other hues and its white if it has any, that leaves
     * the three furthest apart from each other ([apart]): they are there to tell three parts of
     * the sound from each other, and that is all they are chosen for. Of that pair the lighter
     * goes to the top of the spectrum and the other to the voice.
     *
     * It used to take the strongest of an eight by eight grid's averages that differed by so much
     * in their channels, which on a cover of magenta and green gave a magenta, a pink and a
     * green, and nobody could tell which of the first two was moving.
     *
     * A cover with too few colours for that is filled up with neighbours of its own hue. One with
     * hardly any hue is a grey cover: its grey takes the bass and whatever colour it has is the
     * accent. One with none gives its black, a grey and a white.
     *
     * No colour is made up: a hue counts only when enough of the cover has it ([SEEN_WORTH]) and
     * it can be seen there ([HUED], [HUED_IN_THE_DARK]), and what is shown of it is not much more
     * colourful than what is on the cover ([MOST_GAIN]).
     */
    fun mainColours(pixels: IntArray, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return IntArray(MAINS) { GREY }
        val weight = FloatArray(HUES)
        val counts = IntArray(HUES)
        val reds = FloatArray(HUES)
        val greens = FloatArray(HUES)
        val blues = FloatArray(HUES)
        var seen = 0
        var coloured = 0
        var lights = 0
        val light = LongArray(3)
        var greys = 0
        val grey = LongArray(3)
        val lab = FloatArray(3)
        // some four thousand pixels say as much as all of them
        val stride = max(1, sqrt(width.toFloat() * height / 4096f).toInt())
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                oklab(r, g, b, lab)
                val chroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
                seen++
                if (chroma >= HUED && (lab[0] >= DARK || chroma >= HUED_IN_THE_DARK)) {
                    val degrees = Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())).toFloat().let { if (it < 0f) it + 360f else it }
                    val bin = Math.round(degrees * HUES / 360f) % HUES
                    weight[bin] += chroma
                    counts[bin]++
                    reds[bin] += chroma * r
                    greens[bin] += chroma * g
                    blues[bin] += chroma * b
                    coloured++
                } else if (lab[0] >= 0.82f) {
                    lights++
                    light[0] += r.toLong(); light[1] += g.toLong(); light[2] += b.toLong()
                } else {
                    greys++
                    grey[0] += r.toLong(); grey[1] += g.toLong(); grey[2] += b.toLong()
                }
                x += stride
            }
            y += stride
        }

        // A hue's weight and colour take in the parts of the wheel either side of it.
        fun around(bin: Int) = weight[bin] + 0.6f * (weight[(bin + HUES - 1) % HUES] + weight[(bin + 1) % HUES])
        fun colourOf(bin: Int): Int {
            var w = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (near in intArrayOf((bin + HUES - 1) % HUES, bin, (bin + 1) % HUES)) {
                w += weight[near]; r += reds[near]; g += greens[near]; b += blues[near]
            }
            val onCover = argb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
            val shown = withBody(colourful(onCover, 1.15f), BODY)
            val was = colourfulness(onCover)
            val now = colourfulness(shown)
            return if (now > MOST_GAIN * was) colourful(shown, MOST_GAIN * was / now) else shown
        }
        fun share(bin: Int) = (counts[(bin + HUES - 1) % HUES] + counts[bin] + counts[(bin + 1) % HUES]).toFloat() / seen
        val worthy = (0 until HUES).filter { weight[it] > 0f && share(it) >= SEEN_WORTH }
        val heaviest = worthy.maxByOrNull { around(it) }
        val hues = if (heaviest == null) emptyList() else
            worthy.filter { around(it) >= WORTH * around(heaviest) }.map { Candidate(colourOf(it), around(it)) }
        // White weighs as a colour a third as vivid as a pure one would: a cover that is a fifth white has a white.
        val white = if (lights >= LIGHT_WORTH * seen) {
            Candidate(argb(min(232, (light[0] / lights).toInt()), min(232, (light[1] / lights).toInt()), min(232, (light[2] / lights).toInt())), lights * 0.08f)
        } else null
        val dull = if (greys > 0) withBody(colourful(argb((grey[0] / greys).toInt(), (grey[1] / greys).toInt(), (grey[2] / greys).toInt()), 0f), GREY_BODY) else null

        val bass: Int
        val others: List<Candidate>
        if (heaviest == null || coloured < COLOURED * seen) {
            // A grey cover: its grey, or its white when there is more of that, and what else it has.
            val whiter = white != null && (dull == null || lights > greys)
            bass = if (whiter) white!!.color else dull ?: GREY
            others = hues + listOfNotNull(white.takeUnless { whiter }, dull?.takeIf { whiter }?.let { Candidate(it, greys * 0.08f) })
        } else {
            bass = colourOf(heaviest)
            others = hues.filter { it.color != bass } + listOfNotNull(white)
        }
        val first = Candidate(bass, 0f)

        // The pair that leaves the three furthest apart; of pairs about as far apart, the heavier.
        var pair: Pair<Candidate, Candidate>? = null
        var pairScore = 0f
        for (i in others.indices) for (j in i + 1 until others.size) {
            val least = min(apart(first, others[i]), min(apart(first, others[j]), apart(others[i], others[j])))
            val score = Math.round(least / 0.03f) + 0.5f * min(1f, (others[i].weight + others[j].weight) / (others.maxOf { it.weight } * 2f))
            if (least > TOO_ALIKE && score > pairScore) {
                pairScore = score
                pair = others[i] to others[j]
            }
        }
        val a: Int
        val b: Int
        if (pair != null) {
            a = pair.first.color
            b = pair.second.color
        } else {
            // One other colour worth the name at most. The rest are the bass's neighbours: a grey's
            // are greys a step lighter or darker, a hue's a little way round the wheel one way and
            // lighter the other.
            val other = others.filter { apart(first, it) > TOO_ALIKE }.maxByOrNull { apart(first, it) }?.color
            if (isGrey(bass)) {
                // Black, grey and white, or the other way up for a white cover: the far end of the
                // greys from the bass's, and the grey half way there.
                val far = if (luma(bass) > 150) 0xff383838.toInt() else 0xffe8e8e8.toInt()
                a = other ?: far
                b = between(bass, if (other != null && isGrey(other)) other else far, 0.5f)
            } else {
                a = other ?: turned(bass, 40f)
                b = between(turned(bass, -30f), 0xffffffff.toInt(), 0.45f)
            }
        }
        return if (luma(a) >= luma(b)) intArrayOf(bass, b, a) else intArrayOf(bass, a, b)
    }

    /** How colourful a colour looks, in OKLab's measure: nothing for a grey, about a quarter for a pure red. */
    fun colourfulness(color: Int): Float {
        val lab = FloatArray(3)
        oklab((color shr 16) and 0xff, (color shr 8) and 0xff, color and 0xff, lab)
        return sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    }

    private fun isGrey(color: Int): Boolean {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return max(r, max(g, b)) - min(r, min(g, b)) < 12
    }

    /** What an sRGB channel is in light, 0 to 1, for each of its 256 values. */
    private val LINEAR = FloatArray(256) {
        val c = it / 255.0
        (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
    }

    /**
     * A colour in OKLab, into [into]: how light it is from 0 to 1, then its two colour axes. The
     * angle of those two is its hue and their length how colourful it is, and both go by what is
     * seen: equal steps look equally far apart, which the hue of red, green and blue does not do.
     */
    private fun oklab(r: Int, g: Int, b: Int, into: FloatArray) {
        val red = LINEAR[r]
        val green = LINEAR[g]
        val blue = LINEAR[b]
        val l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
        val m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
        val s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
        into[0] = (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat()
        into[1] = (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat()
        into[2] = (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat()
    }

    /** [color] made lighter, hue and all, until its lightest channel is [body]. One that is lighter already is left alone, and black is a grey. */
    private fun withBody(color: Int, body: Int): Int {
        val most = max((color shr 16) and 0xff, max((color shr 8) and 0xff, color and 0xff))
        return when {
            most >= body -> color
            most == 0 -> argb(body, body, body)
            else -> lit(color, body / most.toFloat())
        }
    }

    /** [color] with its hue turned [degrees] round the wheel, as light and as colourful as it was. */
    fun turned(color: Int, degrees: Float): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        val most = max(r, max(g, b))
        val chroma = most - min(r, min(g, b))
        if (chroma == 0) return color
        val was = when (most) {
            r -> ((g - b).toFloat() / chroma).let { if (it < 0f) it + 6f else it }
            g -> (b - r).toFloat() / chroma + 2f
            else -> (r - g).toFloat() / chroma + 4f
        }
        val sixth = ((was + degrees / 60f) % 6f + 6f) % 6f
        // the three channels of a hue: full, none, and one on its way between them
        val part = chroma * (1f - abs(sixth % 2f - 1f))
        val least = (most - chroma).toFloat()
        val (red, green, blue) = when (sixth.toInt()) {
            0 -> Triple(chroma.toFloat(), part, 0f)
            1 -> Triple(part, chroma.toFloat(), 0f)
            2 -> Triple(0f, chroma.toFloat(), part)
            3 -> Triple(0f, part, chroma.toFloat())
            4 -> Triple(part, 0f, chroma.toFloat())
            else -> Triple(chroma.toFloat(), 0f, part)
        }
        return argb((red + least + 0.5f).toInt(), (green + least + 0.5f).toInt(), (blue + least + 0.5f).toInt())
    }

    /** How light a colour looks, 0 to 255. */
    fun luma(color: Int): Int = (299 * ((color shr 16) and 0xff) + 587 * ((color shr 8) and 0xff) + 114 * (color and 0xff)) / 1000

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

    /** What every reaction is multiplied by: the setting's, and more of it while a drop drives the song on. */
    private val reach get() = LivingField.reach(strength) * (1f + LivingField.DRIVE_MORE * driven)

    /**
     * The song's shape as the picture shows it, each 0 to 1: how gathered it is by a build-up,
     * the burst of a drop, and how driven it is by one (see [LivingField.GATHER_IN] and after).
     */
    var gathered = 0f
        private set
    var burst = 0f
        private set
    var driven = 0f
        private set

    // A drop is said once. From there the burst is the picture's own: held at what the drop was
    // for a moment, then let go.
    private var dropHeard = 0f
    private var burstTo = 0f
    private var burstFor = 0f

    /** The other setting, 0 to 1 (see [LivingField.ease]). */
    var smoothing = LivingField.DEFAULT_SMOOTHING

    /**
     * True when the picture is coloured from the cover's main colours, one to a range: each range
     * then moves its own colour and no other (see [LivingField.OWN_SHARE]), and the ranges lie in
     * ribbons with curved edges that flow ([LivingField.flowShares]), not in rows.
     */
    var separate = false

    /** The range each patch breathes with, in a picture of the cover. */
    private val bands = IntArray(count) { LivingField.bandOfCell(it % columns, it / columns, columns, rows) }

    /**
     * How far the music takes range [band] when each has its own colour: what moves its edge and
     * lights it. A build-up charges the top's, and on a drop the bass floods in.
     */
    private fun own(band: Int): Float = reach * (shown[band] * LivingField.OWN_SHARE[band] + ofShape(band))

    /** What the song's shape adds to how far range [band] is taken. */
    private fun ofShape(band: Int): Float = when (band) {
        MusicLevels.HIGH -> LivingField.GATHER_CHARGE * gathered
        MusicLevels.BASS -> LivingField.BURST_FLOOD * burst
        else -> 0f
    }

    private val share = FloatArray(MusicLevels.BANDS)

    /** How much of the point at [x], [y] is each range's at this moment, left in [share]. */
    private fun sharesAt(x: Float, y: Float) =
        LivingField.flowShares(x, y, drift, own(MusicLevels.BASS), own(MusicLevels.MID), own(MusicLevels.HIGH), share, flood = min(1f, reach * burst))

    /** The same for patch [i], where it lives: the patches wander a little, the ribbons do not wander with them. */
    private fun sharesOf(i: Int) = sharesAt((i % columns + 0.5f) / columns, (i / columns + 0.5f) / rows)

    /** How much of the point at [x], [y] is each range's: bass, voice, top. For whoever wants to know where the ribbons are. */
    fun sharesAt(x: Float, y: Float, into: FloatArray) {
        sharesAt(x, y)
        share.copyInto(into)
    }

    /** The aura round the cover, and how far each of its points is taken: 0 to 1, and beyond for a drop. */
    val aura = LivingField.aura(columns, rows)
    val auraShown = FloatArray(aura.size)

    // Where the cover is in the picture, in parts of its width and height. Only the aura asks.
    private var coverLeft = 0f
    private var coverTop = 0f
    private var coverRight = 1f
    private var coverBottom = 1f

    /** Says where the cover is, in parts of the picture's width and height. */
    fun coverAt(left: Float, top: Float, right: Float, bottom: Float) {
        coverLeft = left
        coverTop = top
        coverRight = right
        coverBottom = bottom
    }

    /**
     * The ranges at the place a point of the aura stands on, left in [share]. With a colour to
     * each range the halo takes its colour and its range from the ribbons behind it: the cover
     * hides most of what it stands in front of, and its edge is where that can be seen.
     */
    private fun sharesOfAura(i: Int) {
        val point = aura[i]
        sharesAt(coverLeft + point.x * (coverRight - coverLeft), coverTop + point.y * (coverBottom - coverTop))
    }

    /** Whether there is a cover on screen to put the aura round. Without one it fades away. */
    var coverThere = false

    /** How much of the aura is there, 0 to 1: it comes and goes with the cover over a quarter of a second. */
    var auraPresence = 0f
        private set

    /**
     * The colours on screen, turning into [turnTo]'s cover: the [count] patches, then [under],
     * then [glow], then the three main colours, the bass's first.
     */
    val colors = IntArray(count + 2 + LivingField.MAINS) { LivingField.GREY }
    private var wanted = IntArray(count + 2 + LivingField.MAINS) { LivingField.GREY }
    private val turnedFrom = IntArray(count + 2 + LivingField.MAINS) { LivingField.GREY }
    private val mainsAt = count + 2
    private var turn = 1f
    private var coverKnown = false

    /** What each range has been lately, to tell a hit from a level. */
    private val usual = FloatArray(MusicLevels.BANDS)

    /** What shows where the patches leave anything uncovered: the cover's colour as a whole. */
    val under get() = colors[count]

    /** The colour of the glow on the bottom edge: the cover's strongest. */
    val glow get() = colors[count + 1]

    /**
     * The next cover's patches. The first cover is shown at once, later ones are turned into.
     * [glow] is the colour the glow on the top and bottom edges is made from: the bass's own when
     * the ranges each have one, and left out, the strongest of the patches. [mains] are the three
     * colours of the ranges ([LivingField.mainColours]), for a picture made of those.
     */
    fun turnTo(patches: IntArray, glow: Int? = null, mains: IntArray? = null) {
        val next = IntArray(count + 2 + LivingField.MAINS)
        patches.copyInto(next, endIndex = minOf(count, patches.size))
        for (i in patches.size until count) next[i] = LivingField.GREY
        val cover = next.copyOf(count)
        next[count] = LivingField.lit(LivingField.average(cover), LivingField.REST_LIGHT)
        next[count + 1] = LivingField.lit(glow ?: LivingField.strongest(cover), LivingField.GLOW_LIGHT)
        for (k in 0 until LivingField.MAINS) next[mainsAt + k] = mains?.getOrNull(k) ?: LivingField.GREY
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
     * Moves on by [seconds]. [levels] is what is being heard (the three ranges, and after them how
     * much is going on and where the song is in its shape, as far as it goes), or null when there
     * is nothing to go by (paused, or audio that cannot be measured); [playing] says whether the
     * music runs at all.
     * With music but no levels the picture still drifts, it just does not breathe.
     */
    fun step(seconds: Float, levels: FloatArray?, playing: Boolean) {
        val dt = seconds.coerceIn(0f, 0.1f)          // a long gap between frames is not a leap in the picture
        val ease = LivingField.ease(smoothing)
        // How much is going on, when the levels say: a quiet passage is measured against itself,
        // and shown in full it flashed as hard as the loudest part of the song.
        val going = levels?.getOrNull(MusicLevels.PRESENCE)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        for (band in 0 until MusicLevels.BANDS) {
            val level = levels?.get(band)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val hit = max(0f, level - usual[band])
            usual[band] += (level - usual[band]) * part(dt, LivingField.USUAL_OVER)
            val steady = if (separate) LivingField.OWN_LEVEL[band] else LivingField.LEVEL_SHARE
            val to = going * min(1f, steady * level + LivingField.HIT_SHARE * hit)
            val within = if (to > shown[band]) LivingField.RISE[band] else LivingField.FALL[band]
            shown[band] += (to - shown[band]) * part(dt, within * ease)
        }
        // The song's shape, when the levels say: like them, it counts for as much as is going on.
        val tension = going * shapeIn(levels, MusicLevels.TENSION)
        val drop = going * shapeIn(levels, MusicLevels.DROP)
        val drive = going * shapeIn(levels, MusicLevels.DRIVE)
        gathered += (tension - gathered) * part(dt, (if (tension > gathered) LivingField.GATHER_RISE else LivingField.GATHER_FALL) * ease)
        driven += (drive - driven) * part(dt, (if (drive > driven) LivingField.DRIVE_RISE else LivingField.DRIVE_FALL) * ease)
        // What is heard of a drop fades from its first frame. Coming up is the drop itself, and
        // what the burst goes to is the most that is heard of it while it is held.
        if (drop > dropHeard + 0.05f) {
            burstTo = 0f
            burstFor = LivingField.BURST_HOLD * ease
        }
        dropHeard = drop
        if (burstFor > 0f) {
            burstFor -= dt
            burstTo = max(burstTo, drop)
        } else {
            burstTo = 0f
        }
        burst += (burstTo - burst) * part(dt, (if (burstTo > burst) LivingField.RISE[MusicLevels.BASS] else LivingField.BURST_FALL) * ease)
        for (i in aura.indices) {
            val point = aura[i]
            val to: Float
            val far: Float
            if (separate) {
                // by the ranges it stands on, each for its share
                sharesOfAura(i)
                var sum = 0f
                for (band in 0 until MusicLevels.BANDS) sum += share[band] * shown[band] * LivingField.OWN_SHARE[band]
                to = sum
                // The voice and the top have no place they land: they come all along their stretch at once.
                far = share[MusicLevels.BASS] * point.far + (1f - share[MusicLevels.BASS]) * 0.15f
            } else {
                to = max(shown[MusicLevels.BASS], 0.6f * shown[point.band] * LivingField.SHARE[point.band])
                far = point.far
            }
            // a drop reaches out all the way round, beyond what any kick does, and later and
            // softer the further round the cover from where the kick lands
            val out = to + burst
            val within = if (out > auraShown[i]) 0.012f + 0.11f * far else 0.20f + 0.16f * far
            auraShown[i] += (out - auraShown[i]) * part(dt, within * ease)
        }
        auraPresence += ((if (coverThere) 1f else 0f) - auraPresence) * part(dt, 0.12f)
        pace += ((if (playing) 1f else 0f) - pace) * part(dt, 0.7f)
        // quicker when the music is loud, quicker still while it builds up and while a drop drives
        // it, never still while it plays
        val hurry = min(1f, reach) * (LivingField.GATHER_PACE * gathered + LivingField.DRIVE_PACE * driven)
        drift += dt * pace * (0.55f + 0.9f * max(shown[MusicLevels.BASS], shown[MusicLevels.MID]) + hurry)
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

    /**
     * The colours as the last cover wants them, at once: for when the picture comes back on
     * screen. Covers go on arriving while it is off ([turnTo]) and nothing steps, so it used to
     * come back in the colours of whatever song it was showing when it left, and then turn.
     */
    fun arrive() {
        wanted.copyInto(colors)
        turn = 1f
    }

    /** Straight to rest, with the cover's colours as they are: for when nothing is to move at all. */
    fun settle() {
        arrive()
        usual.fill(0f)
        shown.fill(0f)
        auraShown.fill(0f)
        auraPresence = if (coverThere) 1f else 0f
        pace = 0f
        gathered = 0f
        driven = 0f
        burst = 0f
        burstTo = 0f
        burstFor = 0f
        dropHeard = 0f
    }

    /** True when nothing would change on another [step] without music: the frames can stop. */
    fun atRest(): Boolean = pace < 0.01f && shown.all { it < 0.005f } && auraShown.all { it < 0.005f } &&
        gathered < 0.005f && burst < 0.005f && driven < 0.005f &&
        kotlin.math.abs(auraPresence - (if (coverThere) 1f else 0f)) < 0.01f && turn >= 1f && colors.contentEquals(wanted)

    /** The share of the way covered in [dt] by something that takes [within] seconds to get most of the way. */
    private fun part(dt: Float, within: Float) = 1f - exp(-dt / within)

    /** One of the values that say where the song is in its shape, or nothing when the levels do not go that far. */
    private fun shapeIn(levels: FloatArray?, value: Int): Float =
        levels?.getOrNull(value)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

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

    /** Pushed away from the middle on a kick and further on a drop, drawn in towards it by a build-up. */
    private fun lean(at: Float) = 0.5f + (at - 0.5f) * push()

    /**
     * How far from the middle everything stands, against where it stands at rest: above 1 pushed
     * out, below 1 drawn in.
     */
    fun push(): Float =
        1f + min(LivingField.MOST_PUSH, reach * (LivingField.LEAN * shown[MusicLevels.BASS] + LivingField.BURST_PUSH * burst)) -
            LivingField.GATHER_IN * reach * gathered

    /** How far patch [i] is taken in a picture of the cover, 0 to 1: by the range it breathes with, and by the bass wherever it is. */
    private fun taken(i: Int): Float {
        val band = bands[i]
        val charge = if (band == MusicLevels.HIGH) LivingField.GATHER_CHARGE * gathered else 0f
        // a drop is everybody's, in full
        return max(max(shown[band] * LivingField.SHARE[band] + charge, LivingField.KICK_EVERYWHERE * shown[MusicLevels.BASS]), burst)
    }

    fun radius(i: Int): Float {
        val swell = if (separate) {
            // each range swells what is its own, and the push of a kick is the bass's to show
            sharesOf(i)
            var sum = share[MusicLevels.BASS] * 0.08f * shown[MusicLevels.BASS]
            for (band in 0 until MusicLevels.BANDS) {
                sum += share[band] * LivingField.SWELL * LivingField.OWN_SWELL[band] * shown[band] * LivingField.OWN_SHARE[band]
            }
            // and a drop swells every patch, whoever's it is, as a kick does the bass's
            sum + LivingField.SWELL * burst
        } else {
            LivingField.SWELL * taken(i) + 0.08f * shown[MusicLevels.BASS]
        }
        val ripple = 0.04f * sin(drift * 0.31f + i * (2f * PI.toFloat() / 5f))
        val rest = if (separate) LivingField.FLOW_RADIUS else LivingField.REST_RADIUS
        // bigger all the while a drop drives the song, smaller while it gathers for one
        val grown = min(LivingField.MOST_SWELL, reach * (swell + LivingField.DRIVE_SWELL * driven))
        return rest * (1f + grown + ripple - LivingField.GATHER_SHRINK * reach * gathered)
    }

    /** How much lighter or darker patch [i] is than the colour of its place, so that a ribbon is not one flat colour. */
    fun grain(i: Int): Float = 0.97f + 0.015f * ((i % columns * 2 + i / columns) % 5)

    /** Range [band]'s own colour as the music has it now: lighter on a beat, and more colourful with it. */
    private fun mainNow(band: Int): Int {
        val by = own(band)
        val light = min(LivingField.MOST_LIGHT, LivingField.OWN_REST[band] + LivingField.FULL_LIGHT * LivingField.OWN_LIGHT[band] * by + flash())
        return LivingField.lit(LivingField.colourful(colors[mainsAt + band], 1f + 0.18f * min(by, 3f)), light)
    }

    private val mainsNow = IntArray(MusicLevels.BANDS)

    /** Lighter on a beat, and more colourful with it: light alone washes a colour out towards white. */
    fun color(i: Int): Int {
        if (separate) {
            // The three colours as they are lit now, mixed by how much of this place is each
            // range's, and a little lighter or darker from patch to patch so that a ribbon is
            // not one flat colour.
            for (band in 0 until MusicLevels.BANDS) mainsNow[band] = mainNow(band)
            sharesOf(i)
            return LivingField.lit(LivingField.mixed(mainsNow, 0, share), grain(i))
        }
        val by = reach * taken(i)
        val light = min(LivingField.MOST_LIGHT, LivingField.REST_LIGHT + LivingField.FULL_LIGHT * by + flash())
        return LivingField.lit(LivingField.colourful(colors[i], 1f + 0.18f * min(by, 3f)), light)
    }

    /** The light a drop throws over the whole picture, once. */
    private fun flash(): Float = LivingField.BURST_LIGHT * reach * burst

    /** The glow at the bottom edge: how tall it stands in parts of the height, and how strong it is, 0 to 1. */
    fun glowHeight(): Float = 0.30f + min(0.5f, 0.25f * reach * (shown[MusicLevels.BASS] + burst))
    fun glowStrength(): Float = min(1f, 0.05f + 0.43f * reach * (shown[MusicLevels.BASS] + burst))

    // The aura's point [i]: its radius in parts of the cover's side, how strong it is from 0 to 1, its colour.

    fun auraRadius(i: Int): Float =
        min(LivingField.AURA_MOST, LivingField.AURA_REST + LivingField.AURA_SWELL * reach * auraShown[i])

    fun auraStrength(i: Int): Float = auraPresence * min(1f, 0.30f + 0.40f * reach * auraShown[i])

    fun auraColor(i: Int): Int {
        val by = min(reach * auraShown[i], 3f)
        val from = if (separate) {
            sharesOfAura(i)
            LivingField.mixed(colors, mainsAt, share)
        } else {
            colors[aura[i].patch]
        }
        return LivingField.lit(LivingField.colourful(from, 1.15f + 0.15f * by), min(LivingField.MOST_LIGHT, 1f + 0.30f * by))
    }
}

/**
 * Which of the screen's frames the picture is redrawn on.
 *
 * The picture is soft and slow, and everything drawn over it that looks through it (the glass
 * panels) is redrawn with it, so it is redrawn no more often than it takes to look smooth, which
 * is about [SMOOTH] times a second. But no less often either, where the screen can do that. It
 * used to wait until a sixtieth of a second had passed: every frame at 60 Hz and every second one
 * at 120 Hz as meant, but every second one at 90 Hz too, 45 a second, and every third at 144 Hz,
 * 48 a second, and that is a kick drawn a frame late every so often.
 *
 * So the step is taken from the screen's own frame time, measured from the frames as they come:
 * every frame up to 90 Hz and a little over, every second one at 120 and at 144, every third at 180.
 */
class RedrawPace {
    /** The screen's frame time as far as it is known, in nanoseconds: the shortest step from one frame to the next lately. */
    var frameNanos = 0L
        private set
    private var last = 0L
    private var shown = 0L

    /** A frame of the screen's, at [nanos]: true when the picture is to be redrawn on it. */
    fun due(nanos: Long): Boolean {
        val step = nanos - last
        if (last != 0L && step > 0L) {
            // The shortest step is the screen's own, a longer one has a missed frame in it. What is
            // known is let grow a little at every frame, or a screen that slowed down would never
            // be found out.
            frameNanos = if (frameNanos == 0L) step else min(step, frameNanos + frameNanos / 50)
        }
        last = nanos
        // half a frame early, so that a frame is not let go by for a hair
        if (shown != 0L && nanos - shown < everyNth(frameNanos) * frameNanos - frameNanos / 2) return false
        shown = nanos
        return true
    }

    companion object {
        /** How many redraws a second look smooth. */
        const val SMOOTH = 60

        /** On a screen whose frames are [frameNanos] apart, every which one is redrawn on: as few as give [SMOOTH] a second, or all but. */
        fun everyNth(frameNanos: Long): Int =
            if (frameNanos <= 0L) 1 else max(1, (1e9 / SMOOTH / frameNanos + 0.1).toInt())
    }
}
