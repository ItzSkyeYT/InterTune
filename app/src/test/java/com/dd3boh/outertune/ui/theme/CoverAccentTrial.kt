/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import com.google.material.color.hct.Hct
import com.google.material.color.scheme.SchemeTonalSpot
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Not a test: numbers to look at. For every cover in the folder ACCENT_COVERS names (binary PPM,
 * as LivingPaletteTrial reads them), the colours the app's palette finds in it, the one the theme
 * is built from, and the accent before and after [CoverAccent].
 *
 * A unit test has no Bitmap, so the palette is worked out here the way androidx.palette does it:
 * five bits a channel, near black, near white and the reds it sets aside left out, then a median
 * cut down to eight.
 */
class CoverAccentTrial {

    @Test
    fun numbers() {
        val folder = System.getenv("ACCENT_COVERS"); assumeTrue("set ACCENT_COVERS to run", !folder.isNullOrBlank())
        val out = StringBuilder()
        File(folder!!).listFiles { f -> f.extension == "ppm" && !f.name.startsWith("_") }!!.sortedBy { it.name }.forEach { file ->
            val swatches = paletteOf(read(file), 8)
            val all = swatches.values.sum().coerceAtLeast(1)
            out.appendLine(file.name)
            swatches.entries.sortedByDescending { it.value }.forEach { (rgb, count) ->
                val hct = Hct.fromInt(rgb)
                out.appendLine("    %s  %4.1f%%  hue %5.1f chroma %5.1f tone %5.1f".format(hex(rgb), 100.0 * count / all, hct.hue, hct.chroma, hct.tone))
            }
            val pick = com.google.material.color.score.Score.score(swatches).first()
            val source = Hct.fromInt(CoverAccent.source(swatches))
            val before = SchemeTonalSpot(Hct.fromInt(pick), true, 0.0)
            val after = CoverAccent.scheme(source, true)
            out.appendLine("  the scorer takes %s (chroma %.1f); the theme is built from %s (chroma %.1f, share %.2f)".format(
                hex(pick), Hct.fromInt(pick).chroma, hex(source.toInt()), source.chroma, CoverAccent.share(source.chroma)))
            out.appendLine("  accent, dark theme: before %s (chroma %.1f), after %s (chroma %.1f); light theme: before %s, after %s".format(
                hex(before.primary), Hct.fromInt(before.primary).chroma, hex(after.primary), Hct.fromInt(after.primary).chroma,
                hex(SchemeTonalSpot(Hct.fromInt(pick), false, 0.0).primary), hex(CoverAccent.scheme(source, false).primary)))
        }
        File(folder, "_numbers.txt").writeText(out.toString())
        print(out)
    }

    private fun hex(color: Int) = "#%06x".format(color and 0xffffff)

    private fun read(file: File): IntArray {
        val bytes = file.readBytes()
        var at = 0
        fun word(): String {
            while (bytes[at].toInt().toChar().isWhitespace()) at++
            val from = at
            while (!bytes[at].toInt().toChar().isWhitespace()) at++
            return String(bytes, from, at - from)
        }
        check(word() == "P6"); val width = word().toInt(); val height = word().toInt(); word(); at++
        return IntArray(width * height) { i ->
            val p = at + 3 * i
            ((bytes[p].toInt() and 0xff) shl 16) or ((bytes[p + 1].toInt() and 0xff) shl 8) or (bytes[p + 2].toInt() and 0xff)
        }
    }

    // androidx.palette's ColorCutQuantizer with its default filter, in short.

    private fun paletteOf(pixels: IntArray, most: Int): Map<Int, Int> {
        val hist = IntArray(1 shl 15)
        for (p in pixels) hist[((p shr 19) and 31 shl 10) or ((p shr 11) and 31 shl 5) or ((p shr 3) and 31)]++
        for (c in hist.indices) if (hist[c] > 0 && ignored(wide(c))) hist[c] = 0
        val colors = hist.indices.filter { hist[it] > 0 }.toIntArray()
        if (colors.size <= most) return colors.associate { wide(it) to hist[it] }

        class Box(var lower: Int, var upper: Int) {
            var minR = 0; var maxR = 0; var minG = 0; var maxG = 0; var minB = 0; var maxB = 0; var population = 0
            init { fit() }
            fun fit() {
                minR = 31; minG = 31; minB = 31; maxR = 0; maxG = 0; maxB = 0; population = 0
                for (i in lower..upper) {
                    val c = colors[i]; population += hist[c]
                    val r = c shr 10 and 31; val g = c shr 5 and 31; val b = c and 31
                    minR = min(minR, r); maxR = max(maxR, r); minG = min(minG, g); maxG = max(maxG, g); minB = min(minB, b); maxB = max(maxB, b)
                }
            }
            val volume get() = (maxR - minR + 1) * (maxG - minG + 1) * (maxB - minB + 1)
            fun split(): Box {
                val r = maxR - minR; val g = maxG - minG; val b = maxB - minB
                val key: (Int) -> Int = when {
                    r >= g && r >= b -> { c -> c }
                    g >= r && g >= b -> { c -> (c shr 5 and 31 shl 10) or (c shr 10 and 31 shl 5) or (c and 31) }
                    else -> { c -> (c and 31 shl 10) or (c shr 5 and 31 shl 5) or (c shr 10 and 31) }
                }
                val sorted = (lower..upper).map { colors[it] }.sortedBy(key)
                sorted.forEachIndexed { i, c -> colors[lower + i] = c }
                var at = lower; var count = 0
                for (i in lower..upper) { count += hist[colors[i]]; if (count >= population / 2) { at = min(upper - 1, i); break } }
                val other = Box(at + 1, upper)
                upper = at; fit()
                return other
            }
        }
        val queue = PriorityQueue<Box>(most) { a, b -> b.volume - a.volume }
        queue.offer(Box(0, colors.size - 1))
        while (queue.size < most) {
            val box = queue.poll() ?: break
            if (box.upper > box.lower) { queue.offer(box.split()); queue.offer(box) } else { queue.offer(box); break }
        }
        val found = LinkedHashMap<Int, Int>()
        for (box in queue) {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (i in box.lower..box.upper) {
                val c = colors[i]; val p = hist[c]; n += p
                r += p * (c shr 10 and 31); g += p * (c shr 5 and 31); b += p * (c and 31)
            }
            val mean = ((r.toFloat() / n).roundToInt() shl 10) or ((g.toFloat() / n).roundToInt() shl 5) or (b.toFloat() / n).roundToInt()
            if (!ignored(wide(mean))) found[wide(mean) or (0xff shl 24)] = n
        }
        return found
    }

    private fun wide(c: Int) = ((c shr 10 and 31) shl 19) or ((c shr 5 and 31) shl 11) or ((c and 31) shl 3)

    private fun ignored(rgb: Int): Boolean {
        val r = (rgb shr 16 and 0xff) / 255f; val g = (rgb shr 8 and 0xff) / 255f; val b = (rgb and 0xff) / 255f
        val most = max(r, max(g, b)); val least = min(r, min(g, b)); val delta = most - least
        val l = (most + least) / 2f
        var h = 0f; var s = 0f
        if (most != least) {
            h = when (most) { r -> ((g - b) / delta) % 6f; g -> ((b - r) / delta) + 2f; else -> ((r - g) / delta) + 4f }
            s = delta / (1f - abs(2f * l - 1f))
        }
        h = (h * 60f) % 360f; if (h < 0) h += 360f
        return l >= 0.95f || l <= 0.05f || (h in 10f..37f && s <= 0.82f)
    }
}
