/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapColours
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.utils.shareSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Recap's two cards to share, story sized, 1080 by 1920: "My number one song" and the year on
 * one card. Drawn here with the Canvas at that size, as the lyrics cards are (LyricsCard), so they
 * come out the same from every phone; the mock's .card layouts, to the pixel where it could be.
 * The slide that shows the year's card shows this very picture, so what is seen is what is sent.
 */
object RecapCards {
    private const val TAG = "RecapCards"
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val PAD = 92f

    /** How long a card stays in the cache: the app it went to may still be reading it. */
    private const val KEPT_MS = 60L * 60 * 1000

    enum class Kind { TOP, YEAR }

    private fun face(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, weight, false)
        else if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    private fun paint(size: Float, colour: Int, weight: Int = 400) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour
        typeface = face(weight)
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int = 1, spacing: Float = 1f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /**
     * [text] as large as it fits in [maxLines] from [largest] down to [smallest], cut at the end
     * only below that: a card is a picture, nobody can tap it open.
     */
    private fun fitted(text: String, largest: Float, smallest: Float, colour: Int, weight: Int, width: Int, maxLines: Int, spacing: Float = 1f): StaticLayout {
        var size = largest
        while (true) {
            val p = paint(size, colour, weight)
            val whole = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setIncludePad(false).setLineSpacing(0f, spacing).build()
            if ((whole.lineCount <= maxLines && (0 until whole.lineCount).all { whole.getLineWidth(it) <= width + 1 }) || size <= smallest) {
                return layout(text, p, width, maxLines, spacing)
            }
            size -= 2f
        }
    }

    /** The background: the cover washed out into its colours, as the slides have it, darker top and foot. */
    private fun backdrop(canvas: Canvas, cover: Bitmap?, colours: RecapColours) {
        canvas.drawColor(colours.deep)
        if (cover != null) {
            val side = minOf(cover.width, cover.height)
            val wash = Bitmap.createScaledBitmap(Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side), 20, 20, true)
            val saturate = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.3f) })
            }
            saturate.alpha = 230
            canvas.drawBitmap(wash, null, RectF(-0.25f * WIDTH, -0.2f * HEIGHT, 1.25f * WIDTH, 1.1f * HEIGHT), saturate)
            saturate.alpha = 140
            // The copy turned over, faded in from above the middle so that its edge never shows.
            val layer = canvas.saveLayer(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), null)
            canvas.withRotation(180f, WIDTH / 2f, HEIGHT * 0.8f) {
                drawBitmap(wash, null, RectF(-0.3f * WIDTH, 0.3f * HEIGHT, 1.3f * WIDTH, 1.4f * HEIGHT), saturate)
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0.25f * HEIGHT, 0f, 0.45f * HEIGHT, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            canvas.restoreToCount(layer)
        }
        canvas.drawColor((colours.deep and 0x00FFFFFF) or (0x57 shl 24))
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT.toFloat(),
                intArrayOf(0x57000000, 0x1F000000, 0x4D000000, 0x9E000000.toInt()),
                floatArrayOf(0f, 0.34f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
    }

    /** A cover cut square from its middle, with round corners. */
    private fun drawCover(canvas: Canvas, cover: Bitmap?, into: RectF, radius: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (cover == null) {
            fill.color = 0x14FFFFFF
            canvas.drawRoundRect(into, radius, radius, fill)
            return
        }
        val side = minOf(cover.width, cover.height).toFloat()
        val from = RectF((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        fill.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setRectToRect(from, into, Matrix.ScaleToFit.FILL) })
        }
        // A shadow under it, as the mock's box-shadow.
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x00000000
            setShadowLayer(radius * 2.2f, 0f, radius, 0x80000000.toInt())
        }
        canvas.drawRoundRect(into, radius, radius, shade)
        canvas.drawRoundRect(into, radius, radius, fill)
    }

    /** The mark and [name] after it, the card's first line. */
    private fun drawBrand(context: Context, canvas: Canvas, name: String, rest: String?, colours: RecapColours) {
        val icon = 72f
        canvas.drawCircle(PAD + icon / 2, PAD + icon / 2, icon / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFED5564.toInt() })
        ContextCompat.getDrawable(context, R.drawable.launcher_foreground)?.mutate()?.let {
            it.setBounds(PAD.toInt(), PAD.toInt(), (PAD + icon).toInt(), (PAD + icon).toInt())
            it.draw(canvas)
        }
        val bold = paint(40f, colours.ink, 700)
        val baseline = PAD + icon / 2 - (bold.descent() + bold.ascent()) / 2
        canvas.drawText(name, PAD + icon + 22f, baseline, bold)
        if (rest != null) canvas.drawText(rest, PAD + icon + 22f + bold.measureText("$name "), baseline, paint(40f, colours.ink2))
    }

    private fun drawFoot(canvas: Canvas, left: String, right: String, colours: RecapColours) {
        val p = paint(34f, colours.ink2)
        val baseline = HEIGHT - 84f - p.descent()
        canvas.drawText(left, PAD, baseline, p)
        canvas.drawText(right, WIDTH - PAD - p.measureText(right), baseline, p)
    }

    /** "My number one song": its cover, its name, its plays. */
    fun top(context: Context, shown: RecapShown, words: RecapWords, cover: Bitmap?, colours: RecapColours): Bitmap {
        val recap = shown.recap
        val top = recap.topSongs.first()
        val song = shown.song(top.songId)
        val card = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(card)
        backdrop(canvas, cover, colours)
        drawBrand(context, canvas, context.getString(R.string.recap_name), recap.year.toString(), colours)
        val inner = (WIDTH - 2 * PAD).toInt()
        var y = PAD + 72f + 96f
        drawCover(canvas, cover, RectF((WIDTH - 780f) / 2, y, (WIDTH + 780f) / 2, y + 780f), 44f)
        y += 780f + 84f
        val kick = layout(context.getString(R.string.recap_card_number_one), paint(46f, colours.accent, 500), inner)
        canvas.withTranslation(PAD, y) { kick.draw(this) }
        y += kick.height + 18f
        val title = fitted(song?.title.orEmpty(), 92f, 56f, colours.ink, 900, inner, maxLines = 2)
        canvas.withTranslation(PAD, y) { title.draw(this) }
        y += title.height + 18f
        val artists = layout(song?.artists.orEmpty(), paint(46f, colours.ink2), inner)
        canvas.withTranslation(PAD, y) { artists.draw(this) }
        y += artists.height + 54f
        val big = paint(200f, colours.accent, 900).apply { letterSpacing = -0.05f }
        val count = words.number(top.plays)
        val baseline = y - big.ascent() * 0.8f
        canvas.drawText(count, PAD - 8f, baseline, big)
        val since = if (recap.firstYear) context.getString(R.string.recap_card_plays_since, words.day(recap.sinceDay))
        else context.getString(R.string.recap_card_plays_in, recap.year.toString())
        canvas.drawText(
            context.resources.getQuantityString(R.plurals.recap_card_plays, top.plays, since),
            PAD - 8f + big.measureText(count) + 26f, baseline, paint(50f, colours.ink, 700),
        )
        drawFoot(canvas, context.getString(R.string.recap_card_site), context.getString(R.string.recap_card_made_here), colours)
        return card
    }

    /** The year on one card: five covers fanned out, the top songs and artists, three numbers. */
    fun year(context: Context, shown: RecapShown, words: RecapWords, covers: List<Bitmap?>, colours: RecapColours): Bitmap {
        val recap = shown.recap
        val card = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(card)
        backdrop(canvas, covers.firstOrNull(), colours)
        drawBrand(context, canvas, context.getString(R.string.recap_card_my_year, recap.year.toString()), null, colours)
        val inner = (WIDTH - 2 * PAD).toInt()
        var y = PAD + 72f + 70f
        // The fan: the number one in front, each after it smaller and further back.
        for (i in covers.indices.reversed()) {
            val scale = 1f - i * 0.06f
            val side = 330f * scale
            val left = PAD + i * 142f
            val top = y + (330f - side) / 2
            drawCover(canvas, covers[i], RectF(left, top, left + side, top + side), 28f)
        }
        y += 330f + 64f
        fun list(heading: String, names: List<String>) {
            val h = layout(heading, paint(40f, colours.accent, 500), inner)
            canvas.withTranslation(PAD, y) { h.draw(this) }
            y += h.height + 14f
            // One size for the whole list, the largest at which every line fits on one.
            var size = 46f
            while (size > 34f && names.any { paint(size, colours.ink, 700).measureText(it) > inner - 58f }) size -= 1f
            for ((i, name) in names.withIndex()) {
                val rank = paint(size, colours.ink2, 500)
                val line = paint(size, colours.ink, 700)
                val baseline = y - line.ascent()
                canvas.drawText("${i + 1}", PAD, baseline, rank)
                canvas.drawText(TextUtils.ellipsize(name, line, inner - 58f, TextUtils.TruncateAt.END).toString(), PAD + 58f, baseline, line)
                y += size * 1.32f
            }
        }
        list(context.getString(R.string.recap_card_top_songs), recap.topSongs.map { shown.song(it.songId)?.title.orEmpty() })
        if (recap.topArtists.isNotEmpty()) {
            y += 64f - 14f
            list(context.getString(R.string.recap_card_top_artists), recap.topArtists.map { shown.artists[it.artistId]?.name.orEmpty() })
        }
        // Three numbers under a hairline, each with its word.
        y += 64f
        val hair = Paint().apply { color = 0x2EFFFFFF; strokeWidth = 2f }
        canvas.drawLine(PAD, y, WIDTH - PAD, y, hair)
        y += 40f
        val numbers = listOfNotNull(
            words.number(words.minutes(recap.playedMs)) to context.getString(R.string.recap_card_minutes),
            words.hour(recap.peakHour) to context.getString(R.string.recap_card_my_hour),
            recap.streak?.let { words.number(it.days) to context.getString(R.string.recap_card_days_in_a_row) },
        )
        val big = paint(84f, colours.accent, 900).apply { letterSpacing = -0.03f }
        val small = paint(34f, colours.ink2)
        val column = (inner - (numbers.size - 1) * 2f) / numbers.size
        numbers.forEachIndexed { i, (n, w) ->
            val left = PAD + i * (column + 2f) + if (i > 0) 44f else 0f
            if (i > 0) canvas.drawLine(PAD + i * (column + 2f), y, PAD + i * (column + 2f), y + 150f, hair)
            // Smaller where it would reach the next hairline: "6:00 PM" is longer than the mock's "18:00".
            val room = column - (if (i > 0) 44f else 0f) - 28f
            var size = 84f
            while (size > 48f && big.apply { textSize = size }.measureText(n) > room) size -= 2f
            val baseline = y - big.ascent()
            canvas.drawText(n, left, baseline, big)
            canvas.drawText(w, left, baseline + big.descent() + 12f - small.ascent(), small)
        }
        val since = if (recap.firstYear) context.getString(R.string.recap_card_since, words.day(recap.sinceDay)) else recap.year.toString()
        drawFoot(canvas, context.getString(R.string.recap_card_site), since, colours)
        return card
    }

    /**
     * Saves [card] where the app's FileProvider hands it out from and opens the share sheet with
     * it. False when it could not be saved.
     */
    suspend fun share(context: Context, card: Bitmap, name: String): Boolean = runCatching {
        val file = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "recap-cards")
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEPT_MS }?.forEach { it.deleteRecursively() }
            val own = File(folder, System.currentTimeMillis().toString()).apply { mkdirs() }
            File(own, "$name.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(context.shareSheet(send))
    }.onFailure { Log.w(TAG, "Could not share the Recap card", it) }.isSuccess
}
