/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A slide's colours, as ARGB: what stands out, the words, the quieter words, the depths, and words on the accent. */
data class RecapColours(val accent: Int, val ink: Int, val ink2: Int, val deep: Int, val onAccent: Int)

/**
 * Each slide of the Recap takes its colours from the cover of the song it is about, as the mock
 * did (experiments/features/recap/recap.src.html, measure and tokens): the heaviest hue on the
 * cover, by how colourful its pixels are, set at fixed lightnesses in OKLCH so that every slide
 * reads alike. A cover with little colour gives greys: nothing is lifted out of near-black noise
 * or made more colourful than the cover is.
 *
 * Plain arithmetic over pixels, so it is tested on the JVM (RecapPaletteTest). The caller hands
 * it the cover, small: the mock measured 40 by 40.
 */
object RecapPalette {
    /** The app's own colours: the thank-you page's, and a slide's until its cover has been read. */
    val APP = RecapColours(
        accent = 0xFFFFB3B5.toInt(),
        ink = 0xFFF7EEEE.toInt(),
        ink2 = 0xFFD7C1C1.toInt(),
        deep = 0xFF1A1111.toInt(),
        onAccent = 0xFF561D22.toInt(),
    )

    /** What a cover was found to be: grey, or a hue in degrees and how colourful it is (OKLCH chroma). */
    data class Hue(val grey: Boolean, val degrees: Double, val chroma: Double)

    private const val BINS = 36

    /** The heaviest hue in [pixels], ARGB. */
    fun measure(pixels: IntArray): Hue {
        if (pixels.isEmpty()) return Hue(true, 0.0, 0.0)
        val w = DoubleArray(BINS)
        val sa = DoubleArray(BINS)
        val sb = DoubleArray(BINS)
        val sc = DoubleArray(BINS)
        var coloured = 0
        for (p in pixels) {
            val lab = oklab((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            val l = lab[0]
            val c = hypot(lab[1], lab[2])
            if (l < 0.18 || l > 0.97 || c < 0.04) continue
            coloured++
            val hue = (Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360
            val weight = c * c * (1.15 - abs(l - 0.62))
            val bin = floor(hue / 10).toInt() % BINS
            w[bin] += weight
            sa[bin] += lab[1] * weight
            sb[bin] += lab[2] * weight
            sc[bin] += c * weight
        }
        var best = -1
        var bestScore = 0.0
        for (i in 0 until BINS) {
            val score = w[(i + BINS - 1) % BINS] * 0.5 + w[i] + w[(i + 1) % BINS] * 0.5
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0 || coloured.toDouble() / pixels.size < 0.05) return Hue(true, 0.0, 0.0)
        var tw = 0.0
        var ta = 0.0
        var tb = 0.0
        var tc = 0.0
        for (j in listOf(best - 1, best, best + 1)) {
            val k = (j + BINS) % BINS
            tw += w[k]
            ta += sa[k]
            tb += sb[k]
            tc += sc[k]
        }
        val hue = (Math.toDegrees(atan2(tb / tw, ta / tw)) + 360) % 360
        return Hue(false, hue, tc / tw)
    }

    /** The five colours for a cover found to be [hue]. */
    fun colours(hue: Hue): RecapColours {
        if (hue.grey) return RecapColours(
            accent = oklch(0.93, 0.0, 0.0),
            ink = oklch(0.97, 0.0, 0.0),
            ink2 = oklch(0.82, 0.0, 0.0),
            deep = oklch(0.17, 0.0, 0.0),
            onAccent = oklch(0.2, 0.0, 0.0),
        )
        val c = hue.chroma
        val h = hue.degrees
        return RecapColours(
            accent = oklch(0.86, minOf(c, 0.135), h),
            ink = oklch(0.97, minOf(c * 0.12, 0.014), h),
            ink2 = oklch(0.85, minOf(c * 0.3, 0.03), h),
            deep = oklch(0.2, minOf(c * 0.5, 0.055), h),
            onAccent = oklch(0.26, minOf(c * 0.6, 0.08), h),
        )
    }

    fun of(pixels: IntArray): RecapColours = colours(measure(pixels))

    private fun linear(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    /** OKLab of an sRGB colour, 0 to 255 each. */
    fun oklab(r: Int, g: Int, b: Int): DoubleArray {
        val lr = linear(r)
        val lg = linear(g)
        val lb = linear(b)
        val l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        val m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        val s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    /** Linear sRGB of an OKLCH colour, possibly outside 0 to 1. */
    private fun linearOf(l: Double, c: Double, degrees: Double): DoubleArray {
        val a = c * cos(Math.toRadians(degrees))
        val b = c * sin(Math.toRadians(degrees))
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val lc = l1 * l1 * l1
        val mc = m1 * m1 * m1
        val sc = s1 * s1 * s1
        return doubleArrayOf(
            4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
            -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
            -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc,
        )
    }

    private fun inGamut(rgb: DoubleArray) = rgb.all { it in -1e-4..1.0001 }

    /**
     * An OKLCH colour as ARGB. One the screen cannot show keeps its lightness and hue and loses
     * chroma until it can, as a browser does with the mock's oklch().
     */
    fun oklch(l: Double, c: Double, degrees: Double): Int {
        var rgb = linearOf(l, c, degrees)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = c
            repeat(24) {
                val mid = (lo + hi) / 2
                if (inGamut(linearOf(l, mid, degrees))) lo = mid else hi = mid
            }
            rgb = linearOf(l, lo, degrees)
        }
        fun channel(v: Double): Int {
            val x = v.coerceIn(0.0, 1.0)
            val s = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            return (s * 255).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
    }
}
