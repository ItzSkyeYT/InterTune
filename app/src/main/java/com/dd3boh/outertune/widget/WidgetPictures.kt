/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.dd3boh.outertune.R
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Shelf's looks draw what a widget cannot: a cover leaning, a record with its grooves and its
 * label, a shadow, the cover blurred behind it all. Each is drawn here into one bitmap at the size
 * the plan gave it and shown as one picture, with the places to tap laid over it by MusicWidget.
 *
 * Drawn where the widget is composed, which is never the main thread (Glance's own worker, and the
 * settings screen's preview off it too), from the bitmaps WidgetStore already has, and kept: a play
 * or a pause draws nothing again, and only a new song or a new list does.
 *
 * Every picture is drawn at the size it is shown and never larger, at the screen's density up to
 * [MAX_DENSITY], and all of one widget's pictures together are kept under [MAX_PX] pixels at each
 * size it is drawn at (the launcher asks for two): a launcher allows a widget's bitmaps about one
 * and a half screens of pixels in all (AppWidgetService), and this keeps well under that.
 */
object WidgetPictures {
    private const val TAG = "WidgetPictures"

    /** Pixels per dp at most: past three a phone's eye sees nothing more. */
    const val MAX_DENSITY = 3f

    /** The most pixels the pictures of one widget at one size take together. */
    const val MAX_PX = 600_000

    /** The blurred background is drawn a pixel for this many dp and stretched by the launcher: a blur has nothing finer in it. */
    const val BACKGROUND_DP_PER_PX = 3

    /** And never more pixels across or down than this. */
    const val BACKGROUND_MAX_PX = 200

    /** How light the background may be at its lightest, so white words read on any cover. */
    const val BACKGROUND_MAX_LIGHT = 0.36f

    /**
     * Under Big cover's words: black this strong, then fading out over [FADE_DP], or less where the
     * pill comes sooner. Strong enough that a white letter of the cover's own under the title (the
     * M83. of Midnight City's) is a dark grey shape and never a second title: 0.72 still let it read.
     */
    const val BAND_ALPHA = 0.85f
    const val FADE_DP = 28

    /** A cover's shadow: how dark, how soft, how far down. */
    private const val SHADOW_ALPHA = 0.42f
    private const val SHADOW_BLUR_DP = 5f
    private const val SHADOW_DROP_DP = 3f

    /** A record: its vinyl, its grooves, its label, and the light on it. */
    private const val VINYL = 0xFF101012.toInt()
    private const val GROOVE = 0xFF28282E.toInt()
    private const val LABEL_SHARE = 0.56f

    /** The pixels per dp the pictures of [look] are drawn at on a screen of [density]. */
    fun density(look: LookPlan, density: Float): Float {
        val d = density.coerceIn(0.5f, MAX_DENSITY)
        val area = (listOfNotNull(look.heroPicture) + look.rowPictures).sumOf { it.w.toLong() * it.h }
        return if (area <= 0 || area * d * d <= MAX_PX) d else sqrt(MAX_PX / area.toFloat())
    }

    /** The pixels of [spot] at [density]. */
    fun pixels(spot: Spot, density: Float): Pair<Int, Int> =
        ceil(spot.w * density).toInt().coerceAtLeast(1) to ceil(spot.h * density).toInt().coerceAtLeast(1)

    /** The blurred background's pixels for a widget [w] by [h] dp. */
    fun backgroundPixels(w: Int, h: Int): Pair<Int, Int> =
        (w / BACKGROUND_DP_PER_PX).coerceIn(8, BACKGROUND_MAX_PX) to (h / BACKGROUND_DP_PER_PX).coerceIn(8, BACKGROUND_MAX_PX)

    /** The pictures of the widgets on screen, at their two sizes, and the last song's, by bytes. */
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun key(vararg parts: Any?): String = parts.joinToString(":") {
        when (it) {
            is Bitmap -> "${System.identityHashCode(it)}.${it.generationId}"
            is List<*> -> it.joinToString(",") { e -> if (e is Bitmap) "${System.identityHashCode(e)}.${e.generationId}" else e.toString() }
            else -> it.toString()
        }
    }

    private fun cached(key: String, draw: () -> Bitmap): Bitmap? =
        cache.get(key) ?: runCatching(draw).onFailure { Log.w(TAG, "Could not draw a picture of the widget", it) }
            .getOrNull()?.also { cache.put(key, it) }

    /**
     * The cover behind a look, blurred and darkened as all three were drawn: centred and cut to the
     * widget's shape as the launcher would cut it, its colours a little richer, then dark enough at
     * its lightest for white words. [opacity] is the background's own, in percent.
     */
    fun background(cover: Bitmap, w: Int, h: Int, look: ShelfLook, opacity: Int): Bitmap? =
        cached(key("background", cover, w, h, look, opacity)) {
            val (pw, ph) = backgroundPixels(w, h)
            val small = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val crop = coverCrop(cover.width, cover.height, pw, ph)
            Canvas(small).drawBitmap(
                cover,
                Matrix().apply {
                    setScale(crop.scale, crop.scale)
                    postTranslate(crop.left, crop.top)
                },
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val px = IntArray(pw * ph)
            small.getPixels(px, 0, pw, 0, 0, pw, ph)
            small.recycle()
            saturate(px, 1.35f)
            // The mockups' blur, 20 dp for the crate and the records and 30 for Big cover, in pixels of this size.
            val radius = ((if (look == ShelfLook.BIG_COVER) 30f else 20f) / BACKGROUND_DP_PER_PX).roundToInt().coerceAtLeast(2)
            repeat(3) {
                boxBlur(px, pw, ph, radius, horizontal = true)
                boxBlur(px, pw, ph, radius, horizontal = false)
            }
            var lightest = 0f
            for (c in px) lightest = maxOf(lightest, lightness(c))
            val darken = when (look) {
                ShelfLook.BIG_COVER -> 0.42f
                ShelfLook.CRATE -> 0.55f
                ShelfLook.RECORDS -> 0.5f
            }
            val k = minOf(darken, BACKGROUND_MAX_LIGHT / lightest.coerceAtLeast(0.001f))
            val alpha = (opacity.coerceIn(0, 100) * 255 / 100) shl 24
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16 and 0xFF) * k).roundToInt()
                val g = ((c shr 8 and 0xFF) * k).roundToInt()
                val b = ((c and 0xFF) * k).roundToInt()
                px[i] = alpha or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
        }

    /** [px]'s colours pushed away from grey by [amount] (1 leaves them as they are). */
    private fun saturate(px: IntArray, amount: Float) {
        for (i in px.indices) {
            val c = px[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            val grey = 0.299f * r + 0.587f * g + 0.114f * b
            fun push(v: Int) = (grey + (v - grey) * amount).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (push(r) shl 16) or (push(g) shl 8) or push(b)
        }
    }

    /**
     * A cover in the picture [picture], drawn at [hero] with [radius] corners and its shadow, and for
     * Big cover the band under its words, [band] dp from its top, fading out over [fade] dp. With no
     * cover yet, a quiet square of [placeholder] (a colour with its own alpha) with a note.
     */
    fun cover(context: Context, cover: Bitmap?, picture: Spot, hero: Spot, radius: Int, band: Int, fade: Int, density: Float, placeholder: Int): Bitmap? =
        cached(key("cover", cover, picture, hero, radius, band, fade, density, placeholder)) {
            val (pw, ph) = pixels(picture, density)
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val rect = rect(hero, picture, density, false, picture.w)
            val r = radius * density
            shadow(canvas, rect, r, density)
            face(context, canvas, cover, rect, r, placeholder)
            if (band > 0) {
                val solid = rect.top + band * density
                val end = solid + fade.coerceAtLeast(1) * density
                val dark = (BAND_ALPHA * 255).roundToInt() shl 24
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, rect.top, 0f, end, intArrayOf(dark, dark, 0), floatArrayOf(0f, (solid - rect.top) / (end - rect.top), 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(rect, r, r, paint)
            }
            out
        }

    /**
     * A row of the crate: the covers [arts] at [picks], each leaning as [WidgetLooks.ANGLES] has it
     * from the [first] of the list, each over the one before. Right to left, the row is drawn the
     * other way round and leans the other way.
     */
    fun crate(context: Context, arts: List<Bitmap?>, picks: List<Spot>, first: Int, picture: Spot, radius: Int, density: Float, rtl: Boolean, placeholder: Int): Bitmap? =
        cached(key("crate", arts, picks, first, picture, radius, density, rtl, placeholder)) {
            val (pw, ph) = pixels(picture, density)
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val r = radius * density
            for ((i, spot) in picks.withIndex()) {
                val rect = rect(spot, picture, density, rtl, picture.w)
                val angle = WidgetLooks.ANGLES[(first + i) % WidgetLooks.ANGLES.size].toFloat().let { if (rtl) -it else it }
                canvas.save()
                canvas.rotate(angle, rect.centerX(), rect.centerY())
                shadow(canvas, rect, r, density)
                face(context, canvas, arts.getOrNull(i), rect, r, placeholder)
                canvas.restore()
            }
            out
        }

    /**
     * Records: the sleeve [hero] with the record out of it towards its end ([WidgetLooks.disc]), its
     * label the cover, under the sleeve, in the picture [picture].
     */
    fun sleeve(context: Context, cover: Bitmap?, picture: Spot, hero: Spot, radius: Int, density: Float, rtl: Boolean, placeholder: Int): Bitmap? =
        cached(key("sleeve", cover, picture, hero, radius, density, rtl, placeholder)) {
            val (pw, ph) = pixels(picture, density)
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val (dx, dy, d) = WidgetLooks.disc(hero.w)
            record(context, canvas, cover, rect(Spot(hero.x + dx, hero.y + dy, d, d), picture, density, rtl, picture.w), density, placeholder)
            val rect = rect(hero, picture, density, rtl, picture.w)
            val r = radius * density
            shadow(canvas, rect, r, density)
            face(context, canvas, cover, rect, r, placeholder)
            out
        }

    /** A row of records, each one's cover its label, each a little over the one before. */
    fun records(context: Context, arts: List<Bitmap?>, picks: List<Spot>, picture: Spot, density: Float, rtl: Boolean, placeholder: Int): Bitmap? =
        cached(key("records", arts, picks, picture, density, rtl, placeholder)) {
            val (pw, ph) = pixels(picture, density)
            val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            for ((i, spot) in picks.withIndex()) {
                record(context, canvas, arts.getOrNull(i), rect(spot, picture, density, rtl, picture.w), density, placeholder)
            }
            out
        }

    /** A cover rounded at [radius] dp, at its own file's size: Big cover's mosaic, where the launcher stretches it and Android 11 and under cannot round it. */
    fun rounded(cover: Bitmap, side: Int, radius: Int): Bitmap? = cached(key("rounded", cover, side, radius)) {
        val px = minOf(cover.width, cover.height)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val r = radius * px / side.coerceAtLeast(1).toFloat()
        face(null, Canvas(out), cover, RectF(0f, 0f, px.toFloat(), px.toFloat()), r, 0)
        out
    }

    /** [spot] in the picture [picture]'s pixels, mirrored for right to left. */
    private fun rect(spot: Spot, picture: Spot, density: Float, rtl: Boolean, pictureW: Int): RectF {
        val x = if (rtl) pictureW - (spot.x - picture.x) - spot.w else spot.x - picture.x
        val y = spot.y - picture.y
        return RectF(x * density, y * density, (x + spot.w) * density, (y + spot.h) * density)
    }

    private fun shadow(canvas: Canvas, rect: RectF, r: Float, density: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ((SHADOW_ALPHA * 255).roundToInt() shl 24)
            maskFilter = BlurMaskFilter(SHADOW_BLUR_DP * density, BlurMaskFilter.Blur.NORMAL)
        }
        val drop = SHADOW_DROP_DP * density
        canvas.drawRoundRect(RectF(rect.left, rect.top + drop, rect.right, rect.bottom + drop), r, r, paint)
    }

    /** [cover] cut to [rect] from its middle with [r] corners, or a quiet square with a note while there is none. */
    private fun face(context: Context?, canvas: Canvas, cover: Bitmap?, rect: RectF, r: Float, placeholder: Int) {
        if (cover == null) {
            canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = placeholder })
            note(context, canvas, rect)
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = coverShader(cover, rect) }
        canvas.drawRoundRect(rect, r, r, paint)
    }

    /** The cover scaled to cover [rect] from its middle, as the launcher crops a cover. */
    private fun coverShader(cover: Bitmap, rect: RectF): BitmapShader {
        val scale = maxOf(rect.width() / cover.width, rect.height() / cover.height)
        return BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(rect.left + (rect.width() - cover.width * scale) / 2, rect.top + (rect.height() - cover.height * scale) / 2)
            })
        }
    }

    private fun note(context: Context?, canvas: Canvas, rect: RectF) {
        val drawable = context?.let { ContextCompat.getDrawable(it, R.drawable.music_note) } ?: return
        val side = minOf(rect.width(), rect.height()) * 0.42f
        drawable.setBounds(
            (rect.centerX() - side / 2).roundToInt(), (rect.centerY() - side / 2).roundToInt(),
            (rect.centerX() + side / 2).roundToInt(), (rect.centerY() + side / 2).roundToInt(),
        )
        drawable.setTint(0x8CFFFFFF.toInt())
        drawable.draw(canvas)
    }

    /**
     * A record in [rect]: black vinyl with its grooves, the cover as its round label, the hole, a
     * sheen across it, and its shadow under it (the mockup's).
     */
    private fun record(context: Context, canvas: Canvas, cover: Bitmap?, rect: RectF, density: Float, placeholder: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = rect.width() / 2
        shadow(canvas, rect, radius, density)
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VINYL })
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = GROOVE
            strokeWidth = maxOf(1f, density / 3)
        }
        for (k in 6..46 step 3) canvas.drawCircle(cx, cy, radius * k / 50f, groove)
        val label = radius * LABEL_SHARE
        val labelRect = RectF(cx - label, cy - label, cx + label, cy + label)
        if (cover != null) {
            canvas.drawCircle(cx, cy, label, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = coverShader(cover, labelRect) })
        } else {
            canvas.drawCircle(cx, cy, label, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = placeholder })
            note(context, canvas, labelRect)
        }
        canvas.drawCircle(cx, cy, radius * 0.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VINYL })
        canvas.drawArc(rect, 200f, 50f, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF })
    }
}
