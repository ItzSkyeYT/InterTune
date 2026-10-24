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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.utils.shareSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Recap's two cards to share, story sized, 1080 by 1920, in the slides' paper look:
 * "My number one song" on ink under a burst, and the year on one card on blue with its covers as
 * stickers and its numbers on squares of coloured paper. The calm shapes design's cardTop and
 * cardYear (experiments/features/recap/v5-shapes-calm/recap.src.html), to the pixel where it could
 * be. Drawn here with the Canvas at that size, as the lyrics cards are (LyricsCard), so they come
 * out the same from every phone. The slide that shows the year's card shows this very picture, so
 * what is seen is what is sent.
 */
object RecapCards {
    private const val TAG = "RecapCards"
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val PAD = 92f

    /** How long a card stays in the cache: the app it went to may still be reading it. */
    private const val KEPT_MS = 60L * 60 * 1000

    /** The year card's three numbers: their size, the paper round them, the squares' height and tilts. */
    private const val NUMBER_PX = 104f
    private const val TILE_PAD = 30f
    private const val TILE_HEIGHT = 214f
    private val TILE_TURNS = floatArrayOf(-3f, 2f, -2f)

    private fun Color.int() = toArgb()

    private fun text(size: Float, colour: Color, weight: Int = 400, display: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour.int()
        typeface = if (display) RecapFaces.typeface(weight) else android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (weight >= 600) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
    private fun fitted(text: CharSequence, largest: Float, smallest: Float, colour: Color, weight: Int, width: Int, maxLines: Int, spacing: Float = 1f, display: Boolean = true): StaticLayout {
        var size = largest
        while (true) {
            val p = text(size, colour, weight, display)
            val whole = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setIncludePad(false).setLineSpacing(0f, spacing).build()
            if ((whole.lineCount <= maxLines && (0 until whole.lineCount).all { whole.getLineWidth(it) <= width + 1 }) || size <= smallest) {
                return layout(text, p, width, maxLines, spacing)
            }
            size -= 2f
        }
    }

    /** The paper: one loud colour and its grain. */
    private fun paper(canvas: Canvas, colour: Color) {
        canvas.drawColor(colour.int())
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { shader = PaperGrain.shader(2.2f) })
    }

    private fun outline(paper: Paper, cx: Float, cy: Float, side: Float, turn: Float): Path {
        val pts = PaperShapes.of(paper)
        val a = Math.toRadians(turn.toDouble())
        val c = Math.cos(a).toFloat()
        val s = Math.sin(a).toFloat()
        return Path().apply {
            for (p in 0 until PaperShapes.N) {
                val dx = (pts[2 * p] - 50f) / 100f * side
                val dy = (pts[2 * p + 1] - 50f) / 100f * side
                val x = cx + dx * c - dy * s
                val y = cy + dx * s + dy * c
                if (p == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** A cut-paper shape of layers outside in, each with its soft shadow and the paper's grain. */
    private fun shape(canvas: Canvas, paper: Paper, cx: Float, cy: Float, side: Float, turn: Float, colours: List<Color>, layers: List<Float> = listOf(1f, 0.6f, 0.36f)) {
        colours.forEachIndexed { i, colour ->
            val path = outline(paper, cx, cy, side * layers.getOrElse(i) { 1f }, turn)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colour.int()
                setShadowLayer(24f, 0f, 18f, RecapInk.Shade.copy(alpha = 0.3f).int())
            })
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = PaperGrain.shader(2.2f) })
        }
    }

    /** A cover stuck on with a white paper edge, turned [turn] degrees round its middle. */
    private fun sticker(canvas: Canvas, cover: Bitmap?, left: Float, top: Float, side: Float, turn: Float, edge: Float = 16f, radius: Float = 40f, innerRadius: Float = 26f) {
        canvas.withRotation(turn, left + side / 2, top + side / 2) {
            val outer = RectF(left, top, left + side, top + side)
            drawRoundRect(outer, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = RecapInk.White.int()
                setShadowLayer(60f, 0f, 30f, RecapInk.Shade.copy(alpha = 0.38f).int())
            })
            val inner = RectF(left + edge, top + edge, left + side - edge, top + side - edge)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (cover == null) {
                fill.color = 0xFFDDDAD3.toInt()
            } else {
                val s = minOf(cover.width, cover.height).toFloat()
                val from = RectF((cover.width - s) / 2, (cover.height - s) / 2, (cover.width + s) / 2, (cover.height + s) / 2)
                fill.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setRectToRect(from, inner, Matrix.ScaleToFit.FILL) })
                }
            }
            drawRoundRect(inner, innerRadius, innerRadius, fill)
        }
    }

    /** The mark and [line] after it, with [year] in lemon wherever the words put it. */
    private fun heading(context: Context, canvas: Canvas, template: String, year: String) {
        val icon = 78f
        canvas.drawCircle(PAD + icon / 2, PAD + icon / 2, icon / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RecapRed.int() })
        ContextCompat.getDrawable(context, R.drawable.launcher_foreground)?.mutate()?.let {
            it.setBounds(PAD.toInt(), PAD.toInt(), (PAD + icon).toInt(), (PAD + icon).toInt())
            it.draw(canvas)
        }
        val (before, after) = around(template)
        val line = SpannableStringBuilder(before).apply {
            val at = length
            append(year)
            setSpan(ForegroundColorSpan(RecapInk.Lemon.int()), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(after)
        }
        val words = fitted(line, 62f, 40f, RecapInk.Cream, 900, (WIDTH - 2 * PAD - icon - 22f).toInt(), 1)
        canvas.withTranslation(PAD + icon + 22f, PAD + icon / 2 - words.height / 2f) { words.draw(this) }
    }

    private fun foot(canvas: Canvas, left: String, right: String) {
        val p = text(34f, RecapInk.Cream.copy(alpha = 0.8f), 500)
        val baseline = HEIGHT - 56f - p.descent()
        canvas.drawText(left, PAD, baseline, p)
        canvas.drawText(right, WIDTH - PAD - p.measureText(right), baseline, p)
    }

    /** "My number one song": its cover as a sticker on a giant burst, its name, its plays. */
    fun top(context: Context, shown: RecapShown, words: RecapWords, cover: Bitmap?): Bitmap {
        val recap = shown.recap
        val top = recap.topSongs.first()
        val song = shown.song(top.songId)
        val card = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(card)
        paper(canvas, RecapInk.Ink)
        shape(canvas, Paper.BURST, 540f, 690f, 1040f, 0f, listOf(RecapInk.Lemon, RecapInk.Pink, RecapInk.Blue), listOf(1f, 0.8f, 0.62f))
        shape(canvas, Paper.STAR8, 112f, 1046f, 140f, 0f, listOf(RecapInk.Pink))
        shape(canvas, Paper.TRI, 980f, 1120f, 150f, 20f, listOf(RecapInk.Sky))
        heading(context, canvas, context.getString(R.string.recap_name) + " " + MARK, recap.year.toString())
        sticker(canvas, cover, 230f, 380f, 620f, -3f)
        val inner = (WIDTH - 2 * PAD).toInt()
        val kick = layout(context.getString(R.string.recap_card_number_one), text(56f, RecapInk.Lemon, 900, display = true), inner)
        canvas.withTranslation(PAD, 1180f) { kick.draw(this) }
        val title = fitted(song?.title.orEmpty(), 100f, 60f, RecapInk.Cream, 900, inner, maxLines = 2, spacing = 0.98f)
        canvas.withTranslation(PAD, 1262f) { title.draw(this) }
        val artists = layout(song?.artists.orEmpty(), text(46f, RecapInk.Cream.copy(alpha = 0.82f)), inner)
        canvas.withTranslation(PAD, maxOf(1484f, 1262f + title.height + 22f)) { artists.draw(this) }
        // The plays, big in lemon, and what they count beside them on two lines.
        val big = text(230f, RecapInk.Lemon, 900, display = true)
        val count = words.number(top.plays)
        val baseline = 1560f - big.ascent() * 0.86f
        canvas.drawText(count, 86f, baseline, big)
        // "plays since" over the day, as the design breaks it.
        val since = if (recap.firstYear) context.getString(R.string.recap_card_plays_since, "\n" + words.day(recap.sinceDay))
        else context.getString(R.string.recap_card_plays_in, "\n" + recap.year.toString())
        val label = context.resources.getQuantityString(R.plurals.recap_card_plays, top.plays, since).replace(" \n", "\n")
        val left = 86f + big.measureText(count) + 28f
        val side = fitted(label, 56f, 36f, RecapInk.Cream, 900, (WIDTH - PAD - left).toInt().coerceAtLeast(200), 2, 1.05f)
        canvas.withTranslation(left, baseline - 30f - side.height) { side.draw(this) }
        foot(canvas, context.getString(R.string.recap_card_site), context.getString(R.string.recap_card_made_here))
        return card
    }

    /** The year on one card: five covers fanned out as stickers, the top songs and artists, three numbers on coloured paper. */
    fun year(context: Context, shown: RecapShown, words: RecapWords, covers: List<Bitmap?>): Bitmap {
        val recap = shown.recap
        val card = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(card)
        paper(canvas, RecapInk.Blue)
        shape(canvas, Paper.BURST, 930f, 330f, 560f, 0f, listOf(RecapInk.Lemon, RecapInk.Orange))
        shape(canvas, Paper.BLOB2, -90f, 1640f, 420f, 30f, listOf(RecapInk.Pink))
        shape(canvas, Paper.CIRCLE, 1010f, 1270f, 260f, 0f, listOf(RecapInk.Mint))
        shape(canvas, Paper.TRI, 1016f, 1548f, 170f, -18f, listOf(RecapInk.Sky))
        heading(context, canvas, context.getString(R.string.recap_card_my_year, MARK), recap.year.toString())
        // The fan: the number one in front, the rest behind it, each its own size and tilt.
        val sides = floatArrayOf(380f, 320f, 290f, 270f, 250f)
        val lefts = floatArrayOf(92f, 404f, 632f, 792f, 502f)
        val tops = floatArrayOf(220f, 238f, 206f, 300f, 400f)
        val turns = floatArrayOf(-6f, 5f, -4f, 8f, -3f)
        for (i in minOf(covers.size, 5) - 1 downTo 0) sticker(canvas, covers[i], lefts[i], tops[i], sides[i], turns[i])
        val inner = (WIDTH - 2 * PAD).toInt()
        fun list(heading: String, names: List<String>, top: Float) {
            canvas.drawText(heading, PAD, top - text(54f, RecapInk.Lemon, 900, true).ascent(), text(54f, RecapInk.Lemon, 900, true))
            // One size for the whole list, the largest at which every line fits on one.
            var size = 46f
            while (size > 34f && names.any { text(size, RecapInk.Cream, 700).measureText(it) > inner - 60f }) size -= 1f
            val line = text(size, RecapInk.Cream, 700)
            val rank = text(size + 4f, RecapInk.Lemon, 900, true)
            var y = top + 70f
            for ((i, name) in names.withIndex()) {
                val baseline = y + 62f * 0.74f
                canvas.drawText("${i + 1}", PAD, baseline, rank)
                canvas.drawText(TextUtils.ellipsize(name, line, inner - 60f, TextUtils.TruncateAt.END).toString(), PAD + 60f, baseline, line)
                y += 62f
            }
        }
        list(context.getString(R.string.recap_card_top_songs), recap.topSongs.map { shown.song(it.songId)?.title.orEmpty() }, 700f)
        if (recap.topArtists.isNotEmpty()) {
            list(context.getString(R.string.recap_card_top_artists), recap.topArtists.map { shown.artists[it.artistId]?.name.orEmpty() }, 1110f)
        }
        // Three numbers, each on its own square of coloured paper, a little crooked: as big as the
        // design has them, each square as wide as what it holds, the row from 44 px to 44 px.
        val numbers = listOfNotNull(
            Triple(words.number(words.minutes(recap.playedMs)), context.getString(R.string.recap_card_minutes), RecapInk.Lemon),
            Triple(words.hourShort(recap.peakHour), context.getString(R.string.recap_card_my_hour), RecapInk.Pink),
            recap.streak?.let { Triple(words.number(it.days), context.getString(R.string.recap_card_days_in_a_row), RecapInk.Mint) },
        )
        val big = text(NUMBER_PX, RecapInk.Ink, 900, true)
        val small = text(36f, RecapInk.Ink, 900, true)
        fun widths(size: Float) = numbers.map { (n, label, _) -> maxOf(big.apply { textSize = size }.measureText(n), small.measureText(label)) + 2 * TILE_PAD }
        var size = NUMBER_PX
        var edge = 44f
        var w = widths(size)
        // A little more room towards the edges before anything gets smaller; smaller only for a
        // language whose words would not fit at all, never against the card's edge.
        if (w.sum() + (numbers.size - 1) * 16f > WIDTH - 2 * edge) edge = 24f
        while (w.sum() + (numbers.size - 1) * 16f > WIDTH - 2 * edge && size > 64f) {
            size -= 2f
            w = widths(size)
        }
        val gap = if (numbers.size > 1) (WIDTH - 2 * edge - w.sum()) / (numbers.size - 1) else 0f
        var left = edge
        numbers.forEachIndexed { i, (n, label, colour) ->
            val tileW = w[i]
            val top = 1560f
            canvas.withRotation(TILE_TURNS[i % TILE_TURNS.size], left + tileW / 2, top + TILE_HEIGHT / 2) {
                val rect = RectF(left, top, left + tileW, top + TILE_HEIGHT)
                drawRoundRect(rect, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colour.int()
                    setShadowLayer(36f, 0f, 18f, RecapInk.Shade.copy(alpha = 0.32f).int())
                })
                drawRoundRect(rect, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = PaperGrain.shader(2.2f) })
                big.textSize = size
                val baseline = top + TILE_PAD - big.ascent()
                drawText(n, left + TILE_PAD, baseline, big)
                drawText(label, left + TILE_PAD, baseline + big.descent() + 8f - small.ascent(), small)
            }
            left += tileW + gap
        }
        val since = if (recap.firstYear) context.getString(R.string.recap_card_since, words.day(recap.sinceDay)) else recap.year.toString()
        foot(canvas, context.getString(R.string.recap_card_site), since)
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
