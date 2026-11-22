/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaMetadataRetriever
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import com.dd3boh.outertune.recap.MadeCoverPlan
import com.dd3boh.outertune.recap.RecapMadeCover
import com.dd3boh.outertune.utils.LocalCovers

/**
 * Coil's reader for a [RecapMadeCover]: the file's own picture, else its folder's (LocalCovers), else a
 * cover the Recap draws: a Recap paper picked by the title, a paper shape in a corner, the artist small
 * at the top and the title big at the foot in the Recap's display face; under MadeCoverPlan.SMALL_PX the
 * title's first letter alone. Every Recap cover goes through the app's loader, so the stickers and the
 * drawn cards (RecapCovers) both get it.
 */
class RecapCoverFetcher(
    private val context: Context,
    private val data: RecapMadeCover,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = maxOf(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 }).takeIf { it > 0 } ?: DEFAULT_PX
        val own = data.path?.let { path -> runCatching { embedded(path, px) ?: LocalCovers.of(context, path, px) }.getOrNull() }
        return ImageFetchResult(
            image = (own ?: draw(data, px)).asImage(),
            isSampled = own != null,
            dataSource = if (own != null) DataSource.DISK else DataSource.MEMORY,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<RecapMadeCover> {
        override fun create(data: RecapMadeCover, options: Options, imageLoader: ImageLoader): Fetcher = RecapCoverFetcher(context, data, options)
    }

    companion object {
        /** The size drawn when none is asked for. */
        const val DEFAULT_PX = 600

        /** The Recap's papers, each with the ink that reads on it and its shape's colour, as in local-covers-sheet.jpg. */
        internal val PAPERS: List<Triple<Color, Color, Color>> = with(RecapInk) {
            listOf(
                Triple(Blue, Cream, Lemon), Triple(Pink, Ink, Lemon), Triple(Orange, Ink, Plum), Triple(Mint, Ink, Plum),
                Triple(Plum, Cream, Pink), Triple(Red, Ink, Cream), Triple(Lemon, Ink, Tomato), Triple(Sky, Ink, Blue),
                Triple(Lilac, Ink, Plum), Triple(Tomato, Ink, Lemon), Triple(Violet, Cream, Mint),
            )
        }

        /** The picture inside the file at [path], decoded to about [px], or null where it has none. */
        internal fun embedded(path: String, px: Int): Bitmap? {
            val retriever = MediaMetadataRetriever()
            val art = try {
                retriever.setDataSource(path)
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            return BitmapFactory.decodeByteArray(art, 0, art.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /** The cover the Recap makes for [cover], [px] square. */
        fun draw(cover: RecapMadeCover, px: Int): Bitmap {
            val (paper, ink, accent) = PAPERS[MadeCoverPlan.paper(cover.title, PAPERS.size)]
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val w = px.toFloat()
            canvas.drawColor(paper.toArgb())
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent.toArgb() }
            when (MadeCoverPlan.shape(cover.title)) {
                MadeCoverPlan.Shape.CIRCLE -> canvas.drawOval(w * 0.52f, -w * 0.18f, w * 1.18f, w * 0.48f, fill)
                MadeCoverPlan.Shape.PILL -> canvas.drawRoundRect(w * 0.38f, w * 0.06f, w * 1.1f, w * 0.3f, w * 0.12f, w * 0.12f, fill)
                MadeCoverPlan.Shape.TRIANGLE -> canvas.drawPath(Path().apply { moveTo(w * 0.5f, 0f); lineTo(w, 0f); lineTo(w, w * 0.5f); close() }, fill)
            }
            val pad = w * 0.08f
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); typeface = RecapFaces.typeface(900) }
            if (px < MadeCoverPlan.SMALL_PX) {
                big.textSize = w * 0.62f
                canvas.drawText(MadeCoverPlan.letter(cover.title), pad, w - pad - big.fontMetrics.descent * 0.6f, big)
                return bitmap
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); typeface = RecapFaces.typeface(700); textSize = w * 0.06f }
            val artists = TextUtils.ellipsize(cover.artists, android.text.TextPaint(small), w * 0.5f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(artists, pad, pad - small.fontMetrics.ascent, small)
            var size = w * 0.2f
            var lines: List<String>? = null
            while (size >= w * 0.05f) {
                big.textSize = size
                lines = MadeCoverPlan.lines(cover.title, w - 2 * pad) { big.measureText(it) }
                if (lines != null) break
                size -= w * 0.01f
            }
            val shown = lines ?: listOf(TextUtils.ellipsize(cover.title, android.text.TextPaint(big), w - 2 * pad, TextUtils.TruncateAt.END).toString())
            val metrics = big.fontMetrics
            val step = (metrics.descent - metrics.ascent) * 0.92f
            var y = w - pad - metrics.descent - step * (shown.size - 1)
            for (line in shown) {
                canvas.drawText(line, pad, y, big)
                y += step
            }
            return bitmap
        }
    }
}

/** A made cover's key: one per file and title, whatever the size (Coil keeps sizes apart). */
class RecapMadeCoverKeyer : Keyer<RecapMadeCover> {
    override fun key(data: RecapMadeCover, options: Options): String = "recap-cover;${data.path};${data.title};${data.artists}"
}
