/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.floor

/**
 * The card About's version line gives after seven quick taps (his ask of 7 Nov 2026: "it must feel
 * rewarding", "unique for each device"): a picture drawn from this device alone, with a serial no
 * other device has. [VersionCard] draws it and shows it; this is what is drawn, worked out from a
 * hash of the device's id and nothing else, so the same device always gets the same card and two
 * devices never get the same one.
 *
 * Pure on purpose: no Android in it, so the tests can hold it to that. Its numbers must never
 * change once a version has shipped, or every card in every hand changes with the update: the
 * salt, the palettes, the order and the sums of the draws below are its contract
 * (DeviceCardTest pins the cards of a few ids).
 */
object DeviceCard {
    /** Put before the id, so the hash is the card's own and matches no other use of the id. */
    const val SALT = "InterTune card 1\n"

    /** The art's height and the dark band's under it, in card widths: a card of 5 by 7. */
    const val ART = 1.19
    const val BAND = 0.21

    /** The rounded corners' radius, in card widths. */
    const val CORNER = 0.048

    /** The saved picture's size in pixels. */
    const val WIDTH = 1500
    const val HEIGHT = 2100

    /**
     * A set of colours that go together: the paper the art is on, the colours of its circles, the
     * ink of the band at the foot, and two more for the arcs. Calm ones, which suit the app, never
     * neon. The circles are laid on the paper as inks are printed, each darkening what is under
     * it, except on [dark] paper, where they are painted.
     */
    data class Palette(val paper: Int, val shapes: List<Int>, val ink: Int, val lines: List<Int>, val dark: Boolean)

    /** A circle at [x], [y] of radius [r], in card widths from the art's top left. */
    data class Circle(val x: Double, val y: Double, val r: Double, val colour: Int)

    /** An arc of the circle around [cx], [cy], from [start] degrees for [sweep] degrees, clockwise, [stroke] wide; card widths. */
    data class Arc(val cx: Double, val cy: Double, val radius: Double, val start: Double, val sweep: Double, val stroke: Double, val colour: Int)

    /**
     * One card: its [code], eight hexadecimal digits, the [palette], the paper's [tint] towards the
     * foot (or towards the bottom right where [diagonal]), the circles largest first and the arcs
     * over them.
     */
    data class Design(
        val code: String,
        val palette: Palette,
        val tint: Int,
        val diagonal: Boolean,
        val circles: List<Circle>,
        val arcs: List<Arc>,
    ) {
        /** As the card prints it: No. 3F9A-C21B. */
        val serial: String get() = "No. " + code.substring(0, 4) + "-" + code.substring(4)
    }

    val PALETTES: List<Palette> = listOf(
        // Coral: the app's own red, with apricot, straw, sage and cornflower
        Palette(rgb(0xF5EDE6), rgbs(0xED5564, 0xF4A582, 0xF2D07E, 0x7FB3A0, 0x6C8EBF), rgb(0x1F2433), rgbs(0x1F2433, 0xFFFBF7), dark = false),
        // Harbour: sea blue, sea glass, sun, terracotta, lavender
        Palette(rgb(0xE9EFF1), rgbs(0x4F8FB0, 0x93CFC9, 0xF2C35B, 0xE6896B, 0x8E8CC2), rgb(0x16222B), rgbs(0x16222B, 0xFBFDFD), dark = false),
        // Orchard: leaf, pale green, light yellow, ochre, green teal
        Palette(rgb(0xF2EFE0), rgbs(0x86B06A, 0xC3D49F, 0xE9D37A, 0xEBB04F, 0x5E9A78), rgb(0x1C2619), rgbs(0x1C2619, 0xFFFDF5), dark = false),
        // Plum: lilac, rose, salmon, peach, slate
        Palette(rgb(0xF2EBEF), rgbs(0x9A7FB0, 0xD07E92, 0xEF8A80, 0xF1BC9A, 0x6F8FB8), rgb(0x211B2C), rgbs(0x211B2C, 0xFFFAFC), dark = false),
        // Dune: sand, sage, clay, dusty rose, slate blue
        Palette(rgb(0xF4EEE3), rgbs(0xE2B77E, 0xB9CC95, 0xD98F6E, 0xE7A6A1, 0x7E9CB0), rgb(0x241E18), rgbs(0x241E18, 0xFFFCF6), dark = false),
        // Night: gold, orange, terracotta, teal and sky on a dark blue
        Palette(rgb(0x1F2B3A), rgbs(0xE9C46A, 0xF4A261, 0xE76F51, 0x2A9D8F, 0x7FA7D9), rgb(0x0E141C), rgbs(0xEDE6D6, 0x9FB4C7), dark = true),
    )

    /** The radii the circles take, in card widths, from the largest size to the smallest: three or four of them on a card. */
    private val SIZES = listOf(0.34 to 0.46, 0.19 to 0.26, 0.10 to 0.15, 0.04 to 0.07)

    /** The arcs' widths, in card widths. */
    private val STROKES = listOf(0.008, 0.016, 0.028)

    private const val HEX = "0123456789ABCDEF"

    /**
     * Sixteen directions, a sixteenth of a turn apart, clockwise from the right as the screen has
     * them (y down): written out, as no sine or cosine may differ from one runtime to another here.
     */
    private val DIRECTIONS: List<Pair<Double, Double>> = run {
        val c = 0.9238795325112867
        val s = 0.3826834323650898
        val h = 0.7071067811865476
        val quarter = listOf(1.0 to 0.0, c to s, h to h, s to c)
        (0 until 4).flatMap { turns ->
            quarter.map { (x, y) ->
                var p = x to y
                // a quarter turn clockwise on the screen: (x, y) to (-y, x)
                repeat(turns) { p = -p.second to p.first }
                p
            }
        }
    }

    /** The hash a card is drawn from: SHA-256 of [SALT] and the device's [id]. The id itself is never kept. */
    fun digest(id: String): ByteArray = MessageDigest.getInstance("SHA-256").digest((SALT + id).toByteArray(Charsets.UTF_8))

    /** The card for [id]. */
    fun design(id: String): Design = design(digest(id))

    /** The card for a [digest] of 32 bytes: the code from its first four, the rest of the card from the next eight. */
    fun design(digest: ByteArray): Design {
        require(digest.size >= 12) { "a SHA-256 digest" }
        // Spelt out rather than through a format, whose digits can follow the phone's language.
        val code = buildString {
            for (i in 0 until 4) {
                val byte = digest[i].toInt() and 0xFF
                append(HEX[byte shr 4]).append(HEX[byte and 0xF])
            }
        }
        var seed = 0L
        for (i in 4 until 12) seed = (seed shl 8) or (digest[i].toLong() and 0xFF)
        val dice = Dice(seed)

        val palette = PALETTES[dice.int(PALETTES.size)]
        val order = palette.shapes.toMutableList()
        for (i in order.size - 1 downTo 1) {
            val j = dice.int(i + 1)
            val kept = order[i]
            order[i] = order[j]
            order[j] = kept
        }
        val colours = order.subList(0, 3 + dice.int(2)).toList()
        val tint = mix(palette.paper, colours[0], dice.range(0.06, 0.14))
        val diagonal = dice.int(2) == 0

        val sizes = mutableListOf(0, 1, 2, 3)
        if (dice.int(2) == 0) sizes.remove(1 + dice.int(2))
        val counts = intArrayOf(1 + (if (dice.int(4) == 0) 1 else 0), 1 + dice.int(2), 1 + dice.int(3), 2 + dice.int(3))
        val circles = mutableListOf<Circle>()
        for (size in sizes) {
            val (lo, hi) = SIZES[size]
            repeat(counts[size]) {
                val r = dice.range(lo, hi)
                val big = size <= 1
                val colourAt = dice.int(colours.size)
                if (circles.isEmpty()) {
                    // The first and largest off the middle, a third of the way across and up or down.
                    val x = if (dice.int(2) == 0) dice.range(0.22, 0.42) else dice.range(0.58, 0.78)
                    val y = (if (dice.int(2) == 0) dice.range(0.30, 0.52) else dice.range(0.62, 0.86)) * ART
                    circles += Circle(x, y, r, colours[colourAt])
                    return@repeat
                }
                // Each next one over the edge of one already there, never nearly on top of another.
                var at: Pair<Double, Double>? = null
                var parent: Circle? = null
                for (attempt in 0 until 16) {
                    parent = circles[dice.int(circles.size)]
                    val (dx, dy) = DIRECTIONS[dice.int(16)]
                    val d = (parent.r + r) * dice.range(0.55, 1.0)
                    val x = parent.x + dx * d
                    val y = parent.y + dy * d
                    val inside = if (big) x >= -0.05 && x <= 1.05 && y >= -0.05 && y <= ART + 0.05
                    else x >= r && x <= 1 - r && y >= r && y <= ART - r
                    if (!inside) continue
                    val apart = circles.all { o ->
                        (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) >= (0.5 * (r + o.r)) * (0.5 * (r + o.r))
                    }
                    if (apart) {
                        at = x to y
                        break
                    }
                }
                if (at == null) {
                    at = dice.range(r, 1 - r) to dice.range(r, ART - r)
                    parent = null
                }
                var colour = colours[colourAt]
                if (parent != null && colour == parent.colour) colour = colours[(colourAt + 1) % colours.size]
                circles += Circle(at.first, at.second, r, colour)
            }
        }

        // Each arc through a point of the art, so none falls wholly off the card.
        val arcs = List(2 + dice.int(2)) {
            val px = dice.range(0.15, 0.85)
            val py = dice.range(0.15, 0.85) * ART
            val radius = dice.range(0.32, 0.95)
            val k = dice.int(16)
            val (dx, dy) = DIRECTIONS[k]
            val sweep = dice.range(70.0, 200.0)
            val start = k * 22.5 - sweep / 2
            val stroke = STROKES[dice.int(3)]
            val pick = dice.int(3)
            val colour = if (pick < 2) palette.lines[pick] else colours[dice.int(colours.size)]
            Arc(px - dx * radius, py - dy * radius, radius, start, sweep, stroke, colour)
        }
        return Design(code, palette, tint, diagonal, circles, arcs)
    }

    /** What is kept of the day the card was found: the card's [code] and the [date], "3F9AC21B 2026-11-07". */
    fun foundValue(code: String, date: LocalDate): String = "$code $date"

    /**
     * The day the card with [code] was found, from what [foundValue] kept, or null where it was not
     * found on this device: never, or only on the device a restored backup came from.
     */
    fun foundOn(value: String?, code: String): LocalDate? {
        if (value == null || !value.startsWith("$code ")) return null
        return runCatching { LocalDate.parse(value.substring(code.length + 1).trim()) }.getOrNull()
    }

    /** [a] moved [t] of the way to [b], channel by channel, rounded half up; opaque. */
    private fun mix(a: Int, b: Int, t: Double): Int {
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            out = out or (floor(ca + (cb - ca) * t + 0.5).toInt() shl shift)
        }
        return out
    }

    private fun rgb(value: Int): Int = value or (0xFF shl 24)
    private fun rgbs(vararg values: Int): List<Int> = values.map(::rgb)

    /**
     * SplitMix64: a small generator whose every draw is written here, so that a card comes out the
     * same on every runtime and after every update of the language's own.
     */
    private class Dice(seed: Long) {
        private var state = seed

        fun long(): Long {
            state += 0x9E3779B97F4A7C15uL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }

        /** In [0, 1). */
        fun unit(): Double = (long() ushr 11).toDouble() * (1.0 / (1L shl 53))

        /** In [0, n). */
        fun int(n: Int): Int = ((long() ushr 33) % n).toInt()

        fun range(a: Double, b: Double): Double = a + (b - a) * unit()
    }
}

/**
 * Taps on the version line, counted as Android counts taps on its build number: seven, each within
 * [gapMs] of the one before, and from the fourth on how many are left ([Answer.Left]). A pause
 * longer than that starts the count again.
 */
class VersionTaps(private val needed: Int = 7, private val gapMs: Long = 1500, private val sayFrom: Int = 4) {
    sealed interface Answer {
        /** Nothing to say yet. */
        data object Quiet : Answer

        /** [taps] more to go. */
        data class Left(val taps: Int) : Answer

        /** The seventh: the card. */
        data object Card : Answer
    }

    private var count = 0
    private var last = 0L

    /** A tap at [atMs], on a clock that only goes forward. */
    fun tap(atMs: Long): Answer {
        count = if (count > 0 && atMs - last <= gapMs) count + 1 else 1
        last = atMs
        return when {
            count >= needed -> {
                count = 0
                Answer.Card
            }
            count >= sayFrom -> Answer.Left(needed - count)
            else -> Answer.Quiet
        }
    }
}
