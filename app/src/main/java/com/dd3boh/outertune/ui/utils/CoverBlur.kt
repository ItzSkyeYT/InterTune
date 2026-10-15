/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Blur player background, blurred once. It is the cover's 100 px thumbnail stretched over the
 * whole player and blurred by 100 to 150 dp. Done on the GPU (Modifier.blur) that blur ran again on
 * every frame the window drew, and the progress bar alone redraws it four times a second: 61 ms a
 * frame on a Pixel 5 (15 Oct 2026). Blurred here, on the thumbnail, it is a plain picture to draw.
 *
 * [spreadX] and [spreadY] are the blur's spread (a Gaussian's sigma) as a share of the picture's
 * width and height: the screen's blur over the screen's size, so that the thumbnail, once stretched,
 * comes out as the GPU blur made it.
 */
class CoverBlur(private val spreadX: Float, private val spreadY: Float) : Transformation() {
    override val cacheKey = "CoverBlur(${(spreadX * 1000).roundToInt()},${(spreadY * 1000).roundToInt()})"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        val readable = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        readable.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = blur(pixels, w, h, spreadX * w, spreadY * h)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** The radius of each of three box blurs that together approach a Gaussian of [sigma]. */
        fun boxRadius(sigma: Float): Int =
            ((sqrt(12f * sigma * sigma / 3f + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(0)

        /** [pixels] (w by h, ARGB) blurred three box passes each way, the edges held as they are. */
        fun blur(pixels: IntArray, w: Int, h: Int, sigmaX: Float, sigmaY: Float): IntArray {
            val rx = boxRadius(sigmaX)
            val ry = boxRadius(sigmaY)
            val out = IntArray(w * h)
            val channels = Array(4) { c -> IntArray(w * h) { i -> (pixels[i] ushr (24 - 8 * c)) and 0xFF } }
            val spare = IntArray(w * h)
            for (channel in channels) {
                repeat(3) { pass(channel, spare, w, h, rx, horizontal = true); spare.copyInto(channel) }
                repeat(3) { pass(channel, spare, w, h, ry, horizontal = false); spare.copyInto(channel) }
            }
            for (i in out.indices) {
                out[i] = (channels[0][i] shl 24) or (channels[1][i] shl 16) or (channels[2][i] shl 8) or channels[3][i]
            }
            return out
        }

        /** One box blur of radius [r] along each row ([horizontal]) or column, edges clamped. */
        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            if (r == 0) {
                src.copyInto(dst); return
            }
            val length = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val width = 2 * r + 1
            for (line in 0 until lines) {
                fun at(i: Int): Int {
                    val k = i.coerceIn(0, length - 1)
                    return if (horizontal) src[line * w + k] else src[k * w + line]
                }
                var sum = 0
                for (i in -r..r) sum += at(i)
                for (i in 0 until length) {
                    dst[if (horizontal) line * w + i else i * w + line] = (sum + width / 2) / width
                    sum += at(i + r + 1) - at(i - r)
                }
            }
        }
    }
}
