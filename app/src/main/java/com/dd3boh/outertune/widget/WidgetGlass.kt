/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import androidx.annotation.DrawableRes
import com.dd3boh.outertune.R
import com.google.material.color.hct.Hct
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The colours the widget's styles are drawn in that do not come from the launcher. */
object WidgetColours {
    /** For a widget with no cover colour yet: a near black with the slightest blue in it. */
    const val NEUTRAL_DEEP = 0xFF1C1D24.toInt()

    /** The tone of Cover's background: dark enough for white words on it whatever the cover's colour. */
    const val DEEP_TONE = 20.0

    /** The most colour it is given: a deep plum from a bright pink, never a neon one. */
    const val DEEP_CHROMA = 36.0

    /**
     * Cover's background (25 Oct 2026, the approved mockup's deep plum under a pink and purple
     * cover): the cover's own colour, as the player takes it ([WidgetSong.colour]), at a deep tone.
     * Its hue and as much of its colour as a deep tone holds, so a grey cover gives a grey one.
     */
    fun deep(colour: Int?): Int {
        if (colour == null) return NEUTRAL_DEEP
        val hct = Hct.fromInt(colour)
        return Hct.from(hct.hue, minOf(hct.chroma, DEEP_CHROMA), DEEP_TONE).toInt()
    }
}

/**
 * The darkening under Full cover's words. Drawn by a drawable on the widget, which costs nothing to
 * send to the launcher, and worked out here for the glass, which is drawn ahead of time over the
 * same spot and must be as dark there. [WidgetScrimTest] holds the two to the same numbers.
 *
 * The stops are the alpha of black from the gradient's start to its end, with the place of the
 * middle one; for [BOTTOM] the start is the bottom, for [START] the side the words are on.
 */
enum class WidgetScrim(
    @param:DrawableRes val ltr: Int,
    @param:DrawableRes val rtl: Int,
    val from: Float,
    val middle: Float,
    val at: Float,
    val to: Float,
) {
    /** Nothing: a tile's cover is left as it is. */
    NONE(0, 0, 0f, 0f, 0.5f, 0f),

    /** Clear at the top and dark at the bottom, under a poster's words and bar. */
    BOTTOM(R.drawable.widget_scrim, R.drawable.widget_scrim, 0.85f, 0.40f, 0.4f, 0f),

    /** Dark at the start, where the words of a banner and a line are. */
    START(R.drawable.widget_scrim_start, R.drawable.widget_scrim_end, 0.60f, 0.375f, 0.5f, 0.15f),

    /** Dark at the end, under a tablet's Up next. */
    END(R.drawable.widget_scrim_panes, R.drawable.widget_scrim_panes_rtl, 0.15f, 0.55f, 0.55f, 0.80f),

    /** An even veil, for a line with no words on it. */
    FLAT(0, 0, 0.25f, 0.25f, 0.5f, 0.25f);

    /** How dark, 0 to 1, the scrim is at [x], [y] dp of a widget [w] by [h] dp. */
    fun alpha(x: Float, y: Float, w: Float, h: Float, rtl: Boolean): Float {
        val t = when (this) {
            NONE -> return 0f
            FLAT -> return from
            BOTTOM -> 1f - y / h
            START, END -> if (rtl) 1f - x / w else x / w
        }.coerceIn(0f, 1f)
        return if (t <= at) from + (middle - from) * t / at else middle + (to - middle) * (t - at) / (1f - at)
    }

    companion object {
        /** The scrim a plan is drawn with. */
        fun of(plan: WidgetPlan): WidgetScrim = when (plan.shape) {
            WidgetShape.TILE -> NONE
            WidgetShape.LINE -> if (plan.titleLines > 0) START else FLAT
            WidgetShape.BANNER -> START
            WidgetShape.PANES -> END
            else -> BOTTOM
        }
    }
}

/**
 * Full cover's pane of glass, drawn ahead of time: a widget cannot blur anything live. What lies
 * under the pane, the cover as the launcher crops it and the scrim over that, is drawn into a small
 * bitmap the size of the pane, blurred, kept from being too bright for white buttons and lightened,
 * then given its rounded shape and a fine light edge. The pane is laid under the buttons as their
 * background, exactly where the plan put them, so the cover runs on round it.
 *
 * Small on purpose: every bitmap of a widget goes to the launcher with every redraw, so a pane is at
 * most [MAX_PX] pixels, and it is drawn once per song and size and kept ([cache]), never again on a
 * play or a pause.
 */
object WidgetGlass {
    private const val TAG = "WidgetGlass"

    /** The most pixels a pane is drawn with. */
    const val MAX_PX = 24_000

    /** Pixels per dp at most: frosted glass has nothing in it a finer pane would show. */
    const val MAX_DENSITY = 1.5f

    /** The brightest the frosted cover is left, as a mean, so white buttons always read on it. */
    const val MAX_LIGHT = 0.36f

    /** The white laid over it, and its edge. */
    const val TINT = 0.18f
    const val EDGE = 0.35f

    /** The size in pixels a pane of [w] by [h] dp is drawn at on a screen of [density]. */
    fun pixels(w: Int, h: Int, density: Float): Pair<Int, Int> {
        var k = density.coerceIn(0.5f, MAX_DENSITY)
        if (w * h * k * k > MAX_PX) k = sqrt(MAX_PX / (w * h).toFloat())
        return (w * k).toInt().coerceAtLeast(1) to (h * k).toInt().coerceAtLeast(1)
    }

    /** A few panes: the two sizes a launcher asks for, for this song and the last. */
    private val cache = LruCache<String, Bitmap>(6)

    /**
     * The pane [glass] of a Full cover widget [w] by [h] dp whose cover is [cover] under [scrim], or
     * null when it cannot be drawn: the widget then has a plain pane of light.
     */
    fun pane(cover: Bitmap, w: Int, h: Int, glass: GlassPane, scrim: WidgetScrim, density: Float, rtl: Boolean): Bitmap? =
        runCatching {
            val key = "${System.identityHashCode(cover)}:${cover.generationId}:$w:$h:$glass:$scrim:$density:$rtl"
            cache.get(key) ?: draw(cover, w, h, glass, scrim, density, rtl).also { cache.put(key, it) }
        }.onFailure { Log.w(TAG, "Could not draw the glass", it) }.getOrNull()

    private fun draw(cover: Bitmap, w: Int, h: Int, glass: GlassPane, scrim: WidgetScrim, density: Float, rtl: Boolean): Bitmap {
        val (pw, ph) = pixels(glass.w, glass.h, density)
        val kx = pw / glass.w.toFloat()
        val ky = ph / glass.h.toFloat()
        // Where the pane is as drawn: a right to left widget has its start on the right.
        val gx = if (rtl) w - glass.x - glass.w else glass.x
        val under = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val crop = coverCrop(cover.width, cover.height, w, h)
        val matrix = Matrix().apply {
            setScale(crop.scale * kx, crop.scale * ky)
            postTranslate((crop.left - gx) * kx, (crop.top - glass.y) * ky)
        }
        Canvas(under).drawBitmap(cover, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(pw * ph)
        under.getPixels(px, 0, pw, 0, 0, pw, ph)
        under.recycle()
        for (j in 0 until ph) {
            val y = glass.y + (j + 0.5f) / ky
            for (i in 0 until pw) {
                val x = gx + (i + 0.5f) / kx
                px[j * pw + i] = darker(px[j * pw + i], scrim.alpha(x, y, w.toFloat(), h.toFloat(), rtl))
            }
        }
        frost(px, pw, ph, radius = (6 * kx).roundToInt().coerceAtLeast(2))
        val frosted = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
        // Its shape: a pill, or the circle round play, with a fine edge of light.
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val edge = maxOf(1f, kx)
        val bounds = RectF(edge / 2, edge / 2, pw - edge / 2, ph - edge / 2)
        val radius = minOf(bounds.width(), bounds.height()) / 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(frosted, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, pw.toFloat(), ph.toFloat()), ph / 2f, ph / 2f, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edge
            color = android.graphics.Color.argb((EDGE * 255).roundToInt(), 255, 255, 255)
        }
        canvas.drawRoundRect(bounds, radius, radius, line)
        frosted.recycle()
        return out
    }
}

/** How the launcher draws a cover over a widget (CENTER_CROP): its scale, in dp per pixel, and where its top left lands. */
internal data class CoverCrop(val scale: Float, val left: Float, val top: Float)

internal fun coverCrop(coverW: Int, coverH: Int, w: Int, h: Int): CoverCrop {
    val scale = maxOf(w / coverW.toFloat(), h / coverH.toFloat())
    return CoverCrop(scale, (w - coverW * scale) / 2f, (h - coverH * scale) / 2f)
}

/** [colour] under black at [alpha]. */
internal fun darker(colour: Int, alpha: Float): Int {
    val keep = 1f - alpha.coerceIn(0f, 1f)
    val r = ((colour shr 16 and 0xFF) * keep).roundToInt()
    val g = ((colour shr 8 and 0xFF) * keep).roundToInt()
    val b = ((colour and 0xFF) * keep).roundToInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** How light a colour looks, 0 to 1: the usual weights, on the values as they are. */
internal fun lightness(colour: Int): Float =
    (0.2126f * (colour shr 16 and 0xFF) + 0.7152f * (colour shr 8 and 0xFF) + 0.0722f * (colour and 0xFF)) / 255f

/**
 * Frosts [px], a [w] by [h] picture, in place: three box blurs of [radius] each way, which comes
 * close to a gaussian; dimmed to a mean lightness of at most [WidgetGlass.MAX_LIGHT], so white
 * buttons read on a white cover too; then [WidgetGlass.TINT] of white over it.
 */
internal fun frost(px: IntArray, w: Int, h: Int, radius: Int) {
    repeat(3) {
        boxBlur(px, w, h, radius, horizontal = true)
        boxBlur(px, w, h, radius, horizontal = false)
    }
    var sum = 0.0
    for (c in px) sum += lightness(c)
    val mean = (sum / px.size.coerceAtLeast(1)).toFloat()
    val dim = if (mean > WidgetGlass.MAX_LIGHT) WidgetGlass.MAX_LIGHT / mean else 1f
    val tint = WidgetGlass.TINT
    for (i in px.indices) {
        val c = px[i]
        fun channel(v: Int): Int = (v * dim + (255 - v * dim) * tint).roundToInt().coerceIn(0, 255)
        px[i] = (0xFF shl 24) or (channel(c shr 16 and 0xFF) shl 16) or (channel(c shr 8 and 0xFF) shl 8) or channel(c and 0xFF)
    }
}

/** One pass of a box blur along rows or columns, the edges held at their last pixel. */
internal fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    if (radius <= 0 || w <= 0 || h <= 0) return
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val index = { line: Int, at: Int -> if (horizontal) line * w + at else at * w + line }
    val r = IntArray(length)
    val g = IntArray(length)
    val b = IntArray(length)
    val span = 2 * radius + 1
    for (line in 0 until lines) {
        for (at in 0 until length) {
            val c = px[index(line, at)]
            r[at] = c shr 16 and 0xFF
            g[at] = c shr 8 and 0xFF
            b[at] = c and 0xFF
        }
        var sr = 0
        var sg = 0
        var sb = 0
        for (k in -radius..radius) {
            val at = k.coerceIn(0, length - 1)
            sr += r[at]; sg += g[at]; sb += b[at]
        }
        for (at in 0 until length) {
            px[index(line, at)] = (0xFF shl 24) or ((sr / span) shl 16) or ((sg / span) shl 8) or (sb / span)
            val out = (at - radius).coerceIn(0, length - 1)
            val into = (at + radius + 1).coerceIn(0, length - 1)
            sr += r[into] - r[out]; sg += g[into] - g[out]; sb += b[into] - b[out]
        }
    }
}

/**
 * The bitmaps one widget sends the launcher, in bytes, for the test that keeps them small. Every
 * cover is a square PNG decoded at its size in [WidgetStore], at four bytes a pixel; a pane of glass
 * is drawn for each of the two sizes a launcher asks for (upright and on its side), and a cover is
 * the same bitmap in both and goes once.
 */
object WidgetArt {
    /** The now playing cover beside a title, the one drawn big, and a list song's. */
    const val NOW_PX = 192
    const val BIG_PX = 320
    const val PICK_PX = 128

    /** Under this many dp on its longest side a cover is drawn from the smaller file. */
    const val BIG_FROM_DP = 120

    /** The most one widget may send, all its bitmaps together. */
    const val BUDGET_BYTES = 1_000_000

    /** Whether the plan draws the now playing cover big enough to want the big file. */
    fun wantsBig(plan: WidgetPlan): Boolean = maxOf(plan.coverW, plan.coverH) > BIG_FROM_DP

    fun bytes(plan: WidgetPlan, density: Float): Int {
        val square = { px: Int -> px * px * 4 }
        var total = when {
            plan.shape == WidgetShape.SHELF -> square(NOW_PX)
            plan.coverW <= 0 -> 0
            wantsBig(plan) -> square(BIG_PX)
            else -> square(NOW_PX)
        }
        total += plan.shown * square(PICK_PX)
        total += plan.upNext * square(PICK_PX)
        plan.glass?.let { g ->
            val (pw, ph) = WidgetGlass.pixels(g.w, g.h, density)
            total += 2 * pw * ph * 4
        }
        return total
    }
}
