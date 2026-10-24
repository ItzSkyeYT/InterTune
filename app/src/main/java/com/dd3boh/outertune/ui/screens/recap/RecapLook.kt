/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.coilCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

/**
 * The Recap's colours: loud papers, an ink and a cream, as the calm shapes design has them
 * (experiments/features/recap/v5-shapes-calm/recap.src.html, C).
 */
internal object RecapInk {
    val Ink = Color(0xFF17131F)
    val Cream = Color(0xFFFFF4E2)
    val White = Color(0xFFFFFCF5)
    val Red = Color(0xFFED5564)
    val Blue = Color(0xFF3346FF)
    val Lemon = Color(0xFFFFE34A)
    val Pink = Color(0xFFFF6AB1)
    val Violet = Color(0xFF7B3DF5)
    val Mint = Color(0xFF3DE2A4)
    val Orange = Color(0xFFFF8B24)
    val Sky = Color(0xFF86CCFF)
    val Lilac = Color(0xFFCFB8FF)
    val Tomato = Color(0xFFFF5436)
    val Plum = Color(0xFF5B1FD1)
    val Sun = Color(0xFFFFD21F)

    /** Shadows are a deep plum, never grey. */
    val Shade = Color(0xFF190823)

    /** Number one's pill, a little lighter than the ink it floats on. */
    val NightPill = Color(0xFF2A2433)
}

/** InterTune's mark: the play button on its red disc, as the launcher icon has it. */
val RecapRed = Color(0xFFED5564)

/**
 * A slide's colours: its paper, the words on it, the quieter words, an accent and the words on
 * the accent, and the two papers that run just ahead of its sheet as it grows over the last one.
 */
@Immutable
internal class SlidePaper(val paper: Color, val ink: Color, val accent: Color, val onAccent: Color, val rims: List<Color> = emptyList()) {
    /** The quieter words: as soft as the design's where the paper allows, stronger where it does not. */
    val ink2: Color = quieter(ink, paper)

    /** A dark paper: light words, and the light status bar icons with them. */
    val dark: Boolean get() = paper.luminance() < 0.18f
}

/** The countdown's papers, one for each step, from number five on, as the design gives them. */
internal class CountdownColours(val paper: Color, val blob: List<Color>, val star: Color, val bits: Color, val pill: Color)

internal val COUNTDOWN_COLOURS = listOf(
    CountdownColours(RecapInk.Sky, listOf(RecapInk.Tomato, RecapInk.Orange), RecapInk.Blue, RecapInk.Ink, RecapInk.Blue),
    CountdownColours(RecapInk.Lemon, listOf(RecapInk.Sky, RecapInk.Pink), RecapInk.Blue, RecapInk.Violet, RecapInk.Blue),
    CountdownColours(RecapInk.Lilac, listOf(RecapInk.Pink, RecapInk.Orange), RecapInk.Violet, RecapInk.Blue, RecapInk.Violet),
    CountdownColours(RecapInk.Tomato, listOf(RecapInk.Lemon, RecapInk.Cream), RecapInk.Plum, RecapInk.Sky, RecapInk.Plum),
)

/** The start screen's paper: the app's own red. */
internal val START_PAPER = SlidePaper(RecapInk.Red, RecapInk.Ink, RecapInk.Ink, RecapInk.Cream)

/** Each slide's colour pair: one loud paper each, the thank-you page in the app's red. */
internal fun paperOf(kind: SlideKind, step: Int = 0): SlidePaper = with(RecapInk) {
    when (kind) {
        SlideKind.SINCE -> SlidePaper(Blue, Cream, Lemon, Ink, listOf(Lemon, Pink))
        SlideKind.MINUTES -> SlidePaper(Pink, Ink, Lemon, Ink, listOf(Lemon, Violet))
        SlideKind.HOUR -> SlidePaper(Orange, Ink, Plum, Cream, listOf(Plum, Lemon))
        SlideKind.STREAK -> SlidePaper(Mint, Ink, Violet, Cream)
        SlideKind.ARTISTS -> SlidePaper(Plum, Cream, Lemon, Ink, listOf(Pink, Lemon))
        SlideKind.ONLY_HERE -> SlidePaper(Red, Ink, Cream, Ink, listOf(Cream, Lemon))
        SlideKind.COUNTDOWN -> SlidePaper(COUNTDOWN_COLOURS[step.coerceIn(0, COUNTDOWN_COLOURS.lastIndex)].paper, Ink, Ink, Cream, listOf(Lemon, Blue))
        SlideKind.NUMBER_ONE -> SlidePaper(Ink, Cream, Lemon, Ink, listOf(Lemon, Pink))
        SlideKind.CARD -> SlidePaper(Cream, Ink, Ink, Cream, listOf(Blue, Lemon))
        SlideKind.THANKS -> SlidePaper(Red, Ink, Ink, Cream, listOf(Pink, Lemon))
    }
}

/**
 * The quieter words of a slide (kickers, artists, notes, ticks): [ink] at 74% over [paper] where
 * that reads at [least] to 1 or better, stronger where it does not. The grain darkens the paper by
 * about 6.5%, which matters for dark words, so both papers are checked.
 */
internal fun quieter(ink: Color, paper: Color, least: Double = 4.7): Color {
    fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    fun lum(r: Double, g: Double, b: Double) = 0.2126 * channel(r.toFloat()) + 0.7152 * channel(g.toFloat()) + 0.0722 * channel(b.toFloat())
    fun ratio(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    val papers = listOf(1.0, 0.935).map { k -> doubleArrayOf(paper.red * k, paper.green * k, paper.blue * k) }
    var a = 0.74
    while (a < 1.0) {
        val ok = papers.all { p ->
            val mixed = lum(ink.red * a + p[0] * (1 - a), ink.green * a + p[1] * (1 - a), ink.blue * a + p[2] * (1 - a))
            ratio(mixed, lum(p[0], p[1], p[2])) >= least
        }
        if (ok) break
        a += 0.01
    }
    // A colour keeps its alpha in 255 steps: up to the next one, so the rounding never takes it below.
    return ink.copy(alpha = (ceil(minOf(1.0, a) * 255) / 255).toFloat())
}

/** The Recap's type: a heavy condensed face for what is shouted, the phone's own for the rest. */
internal object RecapFaces {
    private val condensed = DeviceFontFamilyName("sans-serif-condensed")

    val Display = FontFamily(
        Font(condensed, FontWeight.Black),
        Font(condensed, FontWeight.Bold),
    )

    /** The same face for the cards drawn on a Canvas: as heavy as the phone has it. */
    fun typeface(weight: Int): Typeface {
        val base = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
}

/**
 * Paper grain: a fixed tile of fine speckles, the same size whatever it lies on, darkening the
 * paper by about 6.5% as the design's does. Made once, from a fixed seed, so every picture of a
 * slide is the same.
 */
internal object PaperGrain {
    private const val SIDE = 128
    private val tile: Bitmap by lazy {
        val rnd = Random(7)
        val raw = FloatArray(SIDE * SIDE) { rnd.nextFloat() }
        val pixels = IntArray(SIDE * SIDE)
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            // A little smoothed, round the edges of the tile too, so the repeat does not show.
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) sum += raw[((y + dy + SIDE) % SIDE) * SIDE + (x + dx + SIDE) % SIDE]
            val v = ((sum / 9f - 0.5f) * 2.6f + 0.5f).coerceIn(0f, 1f)
            val alpha = (255 * 0.13f * (0.15f + 0.7f * v)).toInt()
            pixels[y * SIDE + x] = alpha shl 24
        }
        Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
    }

    /** The grain as a brush, each speck about 0.7 dp at [density], or [scale] pixels for a card. */
    fun brush(density: Float, scale: Float = density * 0.7f): ShaderBrush =
        ShaderBrush(shader(scale))

    fun shader(scale: Float): Shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        setLocalMatrix(Matrix().apply { setScale(scale, scale) })
    }
}

/**
 * The covers the Recap draws, read once each and kept for as long as it is open: small for the
 * stickers, large for a share card.
 */
class RecapCovers(private val context: Context) {
    suspend fun bitmap(model: Any?, px: Int): Bitmap? {
        if (model == null) return null
        return withContext(coilCoroutine) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context).data(model).size(px, px).allowHardware(false).build()
                ).image?.toBitmap()
            }.getOrNull()
        }
    }
}

/** The mark: [size] across. */
@Composable
fun RecapMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(RecapRed), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.launcher_foreground), null, Modifier.fillMaxSize())
    }
}

/** The Settings banner's backdrop, in the app's colours: it is from the people who make it. */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color(0xFF170E10))
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x9EED5564), Color(0x00ED5564)),
                center = Offset(size.width * 0.18f, size.height * 0.08f),
                radius = size.maxDimension * 0.6f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x8C96283C), Color(0x0096283C)),
                center = Offset(size.width * 0.92f, size.height * 0.88f),
                radius = size.maxDimension * 0.65f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x4DFFB3B5), Color(0x00FFB3B5)),
                center = Offset(size.width * 0.5f, size.height * 0.12f),
                radius = size.width * 0.7f,
            )
        )
    }
}
