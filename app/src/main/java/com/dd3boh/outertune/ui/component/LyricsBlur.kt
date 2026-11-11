/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Progressive blur on the lyrics (his wish of 11 Nov 2026, as Apple Music has it): the line being
 * sung is sharp, and each line further from it is a little more out of focus, on top of the
 * dimming the lines already have. Only while the view follows the song: scrolled by hand, or while
 * lines are picked, every line is sharp, and lyrics with no times, or with "Follow the song" off,
 * are never blurred, since there the view rests on no line.
 *
 * A trial of the debug app (Unreleased.LYRICS_BLUR), chosen in the developer options or from a
 * command line ([trial]). Two ways to draw it are kept side by side while it is one, so that one can
 * be measured against the other on a phone ([How]).
 */
object LyricsBlur {

    /**
     * How strong: how much more out of focus each step away from the line being sung is, and where
     * that stops, both as shares of the lyrics' font size, so that large lyrics look as small ones do.
     */
    enum class Strength(val perStep: Float, val most: Float) {
        SOFT(0.025f, 0.125f),
        MEDIUM(0.045f, 0.225f),
        STRONG(0.08f, 0.4f);

        /**
         * The blur radius of a line [steps] lines from the one being sung, for lyrics [fontPx] tall.
         * In whole pixels: at rest a line is then drawn once ([How.GLYPHS]), and a song goes through
         * a handful of radii, not one for every frame.
         */
        fun radius(steps: Int, fontPx: Float): Float = (minOf(steps * perStep, most) * fontPx).roundToInt().toFloat()
    }

    /** The two ways a line is drawn out of focus. */
    enum class How {
        /**
         * The text's own soft shadow with the text left out. Android keeps each blurred letter the
         * way it keeps a sharp one, so a blurred line costs what a line costs, also while the list
         * scrolls, and nothing in it needs Android 12.
         */
        GLYPHS,

        /**
         * A blur on the line's layer (RenderEffect), Android 12 and newer: the phone blurs every
         * line anew at each frame that draws the list.
         */
        LAYER,
    }

    /** Which way it is drawn. In memory only, as the trial sets it: back to the first at the next start. */
    var how by mutableStateOf(How.GLYPHS)

    /** Whether this phone can draw it [how] it is asked; where it cannot, the lines are drawn as they were. */
    fun canDraw(how: How = this.how): Boolean = how == How.GLYPHS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The strength stored under LyricsBlurKey, or null for the lines as they are. */
    fun strengthOf(stored: String?): Strength? = Strength.entries.firstOrNull { it.name == stored }

    /**
     * Which sung line each row of the lyrics belongs to, [translated] saying which rows are a
     * translation: one stands with the line above it, so the two are as sharp as each other and a
     * song with translations is not blurred twice as fast.
     */
    fun steps(translated: List<Boolean>): IntArray {
        var sung = -1
        return IntArray(translated.size) { row -> if (!translated[row] || sung < 0) ++sung else sung }
    }

    /**
     * How many lines row [index] is from the one being sung at row [current]. Before the first
     * line, where none is lit ([current] is -1), the first line is the one the view waits on, and
     * it is the sharp one.
     */
    fun distance(steps: IntArray, index: Int, current: Int): Int {
        val line = steps.getOrNull(index) ?: return 0
        val sung = steps.getOrNull(current.coerceAtLeast(0)) ?: return 0
        return abs(line - sung)
    }

    /** The debug app's start extra that sets the trial: `--es lyricsblur medium,glyphs,lines`. */
    const val TRIAL = "lyricsblur"

    /** What a trial asks for; null where it says nothing of it. [strength] as it is stored, "" for off. */
    data class Trial(val strength: String?, val how: How?, val wordByWord: Boolean?)

    /**
     * The trial in [words], apart by commas, in any order and case: off, soft, medium or strong;
     * glyphs or layer; words or lines, for the lyrics lit word by word or line by line, which is
     * the other thing a picture of it varies. Null where no trial was asked for.
     */
    fun trial(words: String?): Trial? {
        if (words == null) return null
        var strength: String? = null
        var how: How? = null
        var wordByWord: Boolean? = null
        for (word in words.split(',').map { it.trim().uppercase() }) {
            when {
                word == "OFF" -> strength = ""
                Strength.entries.any { it.name == word } -> strength = word
                How.entries.any { it.name == word } -> how = How.valueOf(word)
                word == "WORDS" -> wordByWord = true
                word == "LINES" -> wordByWord = false
            }
        }
        return Trial(strength, how, wordByWord)
    }

    /** A line comes into focus in this long, as it starts being sung or a finger starts scrolling. */
    const val CLEAR_MS = 150

    /** A line goes out of focus, or changes how far it is, in this long: about the glide to the next line. */
    const val SETTLE_MS = 350

    /** Under this radius a line is drawn as it always was. */
    const val SHARP = 0.02f

    /**
     * A shadow is never fully opaque: Android takes an opaque shadow colour to mean "as see-through
     * as the text", and the text is drawn clear.
     */
    const val MOST_INK = 0.996f
}

/**
 * A line's blur radius as it stands at the frame being drawn, on its way to [target]. A line that
 * comes onto the screen starts at its own, with nothing to animate.
 */
@Composable
internal fun rememberLineBlur(target: Float): Animatable<Float, AnimationVector1D> {
    val radius = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (radius.targetValue != target) {
            radius.animateTo(target, tween(if (target == 0f) LyricsBlur.CLEAR_MS else LyricsBlur.SETTLE_MS))
        }
    }
    return radius
}

/**
 * A line of the lyrics in [color] at [alpha], out of focus by [radius] pixels, read as the line is
 * drawn so that a change of it composes nothing. [layout] is the line's text as it was laid out.
 * With no radius the line is drawn exactly as it was without this: its text behind `alpha`.
 */
internal fun Modifier.lyricsLineBlur(
    how: LyricsBlur.How,
    radius: () -> Float,
    alpha: Float,
    color: Color,
    layout: () -> TextLayoutResult?,
): Modifier = when (how) {
    LyricsBlur.How.LAYER -> graphicsLayer {
        this.alpha = alpha
        val r = radius()
        // Decal: the blur runs out past the line's own box instead of smearing its edge inwards.
        renderEffect = if (r >= 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurEffect(r, r, TileMode.Decal) else null
    }

    LyricsBlur.How.GLYPHS -> graphicsLayer {
        // Out of focus the dimming is in the ink itself: a see-through layer is cut at the line's
        // box, and the blur has to run out past it.
        this.alpha = if (radius() < LyricsBlur.SHARP) alpha else 1f
    }.drawWithContent {
        val r = radius()
        val laidOut = layout()
        if (r < LyricsBlur.SHARP || laidOut == null) drawContent() else drawOutOfFocus(laidOut, color, alpha, r)
    }
}

/**
 * [laidOut] drawn out of focus by [radius]: as its own shadow with the text itself left clear.
 * Between two whole radii it is the two drawn over each other, each at its share and added up, so
 * a line on its way from one to the next never dips; under one pixel the lower of the two is the
 * sharp text.
 */
private fun DrawScope.drawOutOfFocus(laidOut: TextLayoutResult, color: Color, alpha: Float, radius: Float) {
    val low = floor(radius)
    val upper = radius - low
    val ink = color.alpha * alpha
    var drawn = false
    for ((blur, share) in listOf(low to 1f - upper, low + 1f to upper)) {
        if (share < 0.004f) continue
        val blend = if (drawn) BlendMode.Plus else BlendMode.SrcOver
        if (blur == 0f) {
            drawText(textLayoutResult = laidOut, color = color.copy(alpha = ink * share), shadow = Shadow.None, blendMode = blend)
        } else {
            drawText(
                textLayoutResult = laidOut,
                color = Color.Transparent,
                shadow = Shadow(color.copy(alpha = (ink * share).coerceAtMost(LyricsBlur.MOST_INK)), Offset.Zero, blur),
                blendMode = blend,
            )
        }
        drawn = true
    }
}
