/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
 * A trial of the debug app (Unreleased.LYRICS_BLUR), turned on in the developer options, where it
 * starts on [START], or from a command line ([trial]).
 *
 * A line out of focus is drawn as its own soft shadow with the text itself left as good as clear
 * ([lyricsLineBlur]). Android keeps each blurred letter the way it keeps a sharp one, so a blurred
 * line costs what a line costs, also while the list scrolls, and nothing in it needs Android 12.
 * The other way that was tried, a blur on each line's layer (RenderEffect), drew the same picture
 * (on the emulator the two were never more than 4 levels of 255 apart) but made the phone blur
 * every line anew at each frame that draws the list, and needed Android 12; it was taken out on
 * 12 Nov 2026.
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
         * In whole pixels: at rest a line is then drawn once, and a song goes through a handful of
         * radii, whose letters Android keeps, not one for every frame.
         */
        fun radius(steps: Int, fontPx: Float): Float = (minOf(steps * perStep, most) * fontPx).roundToInt().toFloat()
    }

    /** The strength the switch starts on when it is turned on: the one he chose from the first pictures. */
    val START = Strength.MEDIUM

    /** The strength stored under LyricsBlurKey, or null for the lines as they are. */
    fun strengthOf(stored: String?): Strength? = Strength.entries.firstOrNull { it.name == stored }

    /** What the switch stores: [START] when it is turned on, nothing chosen when it is turned off. */
    fun switched(on: Boolean): String = if (on) START.name else ""

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

    /** The debug app's start extra that sets the trial: `--es lyricsblur medium,lines`. */
    const val TRIAL = "lyricsblur"

    /** What a trial asks for; null where it says nothing of it. [strength] as it is stored, "" for off. */
    data class Trial(val strength: String?, val wordByWord: Boolean?)

    /**
     * The trial in [words], apart by commas, in any order and case: off, soft, medium or strong;
     * words or lines, for the lyrics lit word by word or line by line, which is the other thing a
     * picture of it varies. A word it does not know is passed over. Null where no trial was asked for.
     */
    fun trial(words: String?): Trial? {
        if (words == null) return null
        var strength: String? = null
        var wordByWord: Boolean? = null
        for (word in words.split(',').map { it.trim().uppercase() }) {
            when {
                word == "OFF" -> strength = ""
                Strength.entries.any { it.name == word } -> strength = word
                word == "WORDS" -> wordByWord = true
                word == "LINES" -> wordByWord = false
            }
        }
        return Trial(strength, wordByWord)
    }

    /** A line comes into focus in this long, as it starts being sung or a finger starts scrolling. */
    const val CLEAR_MS = 150

    /** A line goes out of focus, or changes how far it is, in this long: about the glide to the next line. */
    const val SETTLE_MS = 350

    /** Under this radius a line is drawn as it always was. */
    const val SHARP = 0.02f

    /**
     * A shadow is never fully opaque: Android takes an opaque shadow colour to mean "as see-through
     * as the text", and the text is drawn as good as clear.
     */
    const val MOST_INK = 0.996f

    /**
     * The ink left in the text whose shadow is the line out of focus: one step in 255, which no eye
     * sees. A guard, not a need that was seen: a paint with no ink at all is one Skia may draw
     * nothing for, and the shadow hangs on that same draw. Android 17 does draw it (the emulator,
     * 12 Nov 2026: the build of the day before, with the text fully clear, gave the same picture);
     * older Androids were not looked at.
     */
    const val TEXT_INK = 1f / 255f
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
    radius: () -> Float,
    alpha: Float,
    color: Color,
    layout: () -> TextLayoutResult?,
): Modifier = graphicsLayer {
    // Out of focus the dimming is in the ink itself: a see-through layer is cut at the line's
    // box, and the blur has to run out past it.
    this.alpha = if (radius() < LyricsBlur.SHARP) alpha else 1f
}.drawWithContent {
    val r = radius()
    val laidOut = layout()
    if (r < LyricsBlur.SHARP || laidOut == null) drawContent() else drawOutOfFocus(laidOut, color, alpha, r)
}

/**
 * [laidOut] drawn out of focus by [radius]: as its own shadow with the text itself left as good
 * as clear ([LyricsBlur.TEXT_INK]). Between two whole radii it is the two drawn over each other,
 * each at its share and added up, so a line on its way from one to the next never dips; under one
 * pixel the lower of the two is the sharp text.
 */
private fun DrawScope.drawOutOfFocus(laidOut: TextLayoutResult, color: Color, alpha: Float, radius: Float) {
    val low = floor(radius)
    val upper = radius - low
    val ink = color.alpha * alpha
    val first = drawShare(laidOut, color, ink * (1f - upper), low, BlendMode.SrcOver)
    drawShare(laidOut, color, ink * upper, low + 1f, if (first) BlendMode.Plus else BlendMode.SrcOver)
}

/** One of the two: [laidOut] at [ink], out of focus by the whole radius [blur]. False when it is too faint to draw. */
private fun DrawScope.drawShare(laidOut: TextLayoutResult, color: Color, ink: Float, blur: Float, blend: BlendMode): Boolean {
    if (ink < 0.004f) return false
    if (blur == 0f) {
        drawText(textLayoutResult = laidOut, color = color.copy(alpha = ink), shadow = Shadow.None, blendMode = blend)
    } else {
        drawText(
            textLayoutResult = laidOut,
            color = color.copy(alpha = LyricsBlur.TEXT_INK),
            shadow = Shadow(color.copy(alpha = ink.coerceAtMost(LyricsBlur.MOST_INK)), Offset.Zero, blur),
            blendMode = blend,
        )
    }
    return true
}
