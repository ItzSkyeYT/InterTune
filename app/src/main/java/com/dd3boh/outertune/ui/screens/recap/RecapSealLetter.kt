/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.sin

/**
 * A letter being sealed (his ask of 22 Nov 2026: "a letter, stamped, sealed"), the moment after
 * Seal it: the taped note folds in thirds, slides into an envelope, the flap closes, a wax stamp
 * comes down, presses with a squash and a little shake and lifts away, leaving a red seal with the
 * play triangle; the letter settles and slides off upwards, to next year. Its steps, in ms of the
 * story's clock from the seal; with Remove animations on there are none and it is sealed at once.
 */
internal object SealLetter {
    enum class Step(val ms: Int) { FOLD(700), INTO(450), FLAP(350), STAMP(650), SETTLE(250), OFF(450) }

    /** About 2.8 s in all. */
    val TOTAL_MS: Int = Step.entries.sumOf { it.ms }

    /** When each step begins, in ms after the seal. */
    fun startOf(step: Step): Int = Step.entries.takeWhile { it != step }.sumOf { it.ms }

    /** When the stamp presses into the wax: the light tick under the finger. */
    val PRESS_MS: Int get() = startOf(Step.STAMP) + (Step.STAMP.ms * PRESS).toInt()

    /** Where in the stamp's step it presses, lifts and is gone. */
    const val PRESS = 0.3f
    const val LIFT = 0.62f

    /** The step [ms] after the seal and how far through it, or null once all is done (at once with Remove animations on). */
    fun at(ms: Long, still: Boolean): Pair<Step, Float>? {
        if (still || ms < 0) return null
        var from = 0L
        for (step in Step.entries) {
            if (ms < from + step.ms) return step to ((ms - from).toFloat() / step.ms).coerceIn(0f, 1f)
            from += step.ms
        }
        return null
    }
}

private fun ease(t: Float): Float = (1f - (1f - t.coerceIn(0f, 1f)).let { it * it * it })
private fun easeIn(t: Float): Float = t.coerceIn(0f, 1f).let { it * it }

/**
 * The letter over the paper it was written on, for as long as [SealLetter] runs after [sealedAt]
 * on the story's clock: [text] the words just sealed, drawn on the paper as it folds. A light tick
 * as the stamp presses.
 */
@Composable
internal fun SealLetterCanvas(s: SlideScope, sealedAt: Long, text: String, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(sealedAt) {
        if (s.still || sealedAt < 0) return@LaunchedEffect
        val press = sealedAt + SealLetter.PRESS_MS
        if (s.stage.clock.now >= press) return@LaunchedEffect
        snapshotFlow { s.stage.clock.now }.first { it >= press }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val measurer = rememberTextMeasurer()
    val hand = remember { noteHand(19.sp) }
    val triangle = remember { Path() }
    val flap = remember { Path() }
    val pocket = remember { Path() }
    Canvas(modifier) {
        val (step, p) = SealLetter.at(s.stage.clock.now - sealedAt, s.still) ?: return@Canvas
        val side = 4.dp.toPx()
        val top = 18.dp.toPx()
        val paper = Rect(side, top, size.width - side, size.height)
        val third = paper.height / 3f
        val middle = Rect(paper.left, paper.top + third, paper.right, paper.top + 2 * third)
        val laid = measurer.measure(text, hand, constraints = Constraints(maxWidth = (paper.width - 44.dp.toPx()).toInt().coerceAtLeast(1)), maxLines = 4)

        fun DrawScope.sheet(back: Boolean) {
            drawRoundRect(if (back) Color(0xFFF1ECE2) else RecapInk.White, paper.topLeft, paper.size, CornerRadius(4.dp.toPx()))
            if (back) return
            var y = paper.top + 26.dp.toPx() + hand.lineHeight.toPx() * 0.78f + 3.dp.toPx()
            while (y < paper.bottom - 6.dp.toPx()) {
                drawRect(RecapInk.Blue.copy(alpha = 0.22f), Offset(paper.left, y), Size(paper.width, 1.dp.toPx()))
                y += hand.lineHeight.toPx()
            }
            drawText(laid, topLeft = Offset(paper.left + 22.dp.toPx(), paper.top + 26.dp.toPx()))
        }

        /** One band of the sheet, folded over the line [hinge] by [k] (1 flat, -1 folded right over). */
        fun DrawScope.band(from: Float, to: Float, hinge: Float, k: Float) {
            scale(1f, k, Offset(size.width / 2f, hinge)) {
                clipRect(paper.left, from, paper.right, to) { sheet(back = k < 0f) }
            }
        }

        // The envelope, a little larger than the letter folded in three.
        val env = Rect(middle.left - 6.dp.toPx(), middle.top - 6.dp.toPx(), middle.right + 6.dp.toPx(), middle.bottom + 8.dp.toPx())
        fun DrawScope.envelopeBack() = drawRoundRect(Color(0xFFE9DCC4), env.topLeft, env.size, CornerRadius(5.dp.toPx()))
        fun DrawScope.envelopeFront() {
            pocket.rewind()
            pocket.moveTo(env.left, env.top + env.height * 0.18f)
            pocket.lineTo(env.center.x, env.top + env.height * 0.62f)
            pocket.lineTo(env.right, env.top + env.height * 0.18f)
            pocket.lineTo(env.right, env.bottom)
            pocket.lineTo(env.left, env.bottom)
            pocket.close()
            drawPath(pocket, Color(0xFFF6EBD6))
            drawPath(pocket, Color(0xFFD9C7A6), style = Stroke(1.dp.toPx()))
        }
        fun DrawScope.envelopeFlap(k: Float) {
            flap.rewind()
            flap.moveTo(env.left, env.top)
            flap.lineTo(env.right, env.top)
            flap.lineTo(env.center.x, env.top + env.height * 0.58f * k)
            flap.close()
            drawPath(flap, if (k < 0f) Color(0xFFE2D2B4) else Color(0xFFF3E4C8))
            drawPath(flap, Color(0xFFD9C7A6), style = Stroke(1.dp.toPx()))
        }
        val seal = Offset(env.center.x, env.top + env.height * 0.58f)
        val wax = 22.dp.toPx()
        fun DrawScope.waxSeal() {
            drawCircle(RecapInk.Shade.copy(alpha = 0.25f), wax * 1.05f, seal + Offset(0f, 2.dp.toPx()))
            drawCircle(RecapRed, wax, seal)
            drawCircle(Color.Black.copy(alpha = 0.12f), wax * 0.78f, seal, style = Stroke(2.dp.toPx()))
            triangle.rewind()
            val t = wax * 0.42f
            triangle.moveTo(seal.x - t * 0.6f, seal.y - t)
            triangle.lineTo(seal.x + t, seal.y)
            triangle.lineTo(seal.x - t * 0.6f, seal.y + t)
            triangle.close()
            drawPath(triangle, RecapInk.Cream)
        }
        fun DrawScope.sealedLetter() {
            envelopeBack()
            envelopeFront()
            envelopeFlap(1f)
            waxSeal()
        }

        when (step) {
            SealLetter.Step.FOLD -> {
                // The bottom third up over the middle, then the top third down over both.
                val first = (p * 2f).coerceAtMost(1f)
                val second = ((p - 0.5f) * 2f).coerceAtLeast(0f)
                band(middle.top, middle.bottom, middle.bottom, 1f)
                if (second < 0.5f) band(paper.top, middle.top, middle.top, 1f - 2f * ease(second))
                band(middle.bottom, paper.bottom, middle.bottom, 1f - 2f * ease(first))
                if (second >= 0.5f) band(paper.top, middle.top, middle.top, 1f - 2f * ease(second))
            }
            SealLetter.Step.INTO -> {
                // The envelope opens behind it, and the folded letter slips down into its pocket.
                val e = ease(p)
                envelopeBack()
                envelopeFlap(-1f)
                translate(0f, -third * 0.6f * (1f - e)) { clipRect(paper.left, middle.top, paper.right, middle.bottom) { sheet(back = true) } }
                envelopeFront()
            }
            SealLetter.Step.FLAP -> {
                envelopeBack()
                envelopeFront()
                envelopeFlap(-1f + 2f * ease(p))
            }
            SealLetter.Step.STAMP -> {
                envelopeBack()
                envelopeFront()
                envelopeFlap(1f)
                if (p >= SealLetter.PRESS) waxSeal()
                // The stamp: down, a squash and a little shake as it presses, then up and away.
                val down = when {
                    p < SealLetter.PRESS -> 1f - ease(p / SealLetter.PRESS)
                    p < SealLetter.LIFT -> 0f
                    else -> easeIn((p - SealLetter.LIFT) / (1f - SealLetter.LIFT))
                }
                val pressing = p >= SealLetter.PRESS && p < SealLetter.LIFT
                val squash = if (pressing) 1f - 0.14f * sin(((p - SealLetter.PRESS) / (SealLetter.LIFT - SealLetter.PRESS)) * PI.toFloat()) else 1f
                val shake = if (pressing) 4f * sin(((p - SealLetter.PRESS) / (SealLetter.LIFT - SealLetter.PRESS)) * 3f * 2f * PI.toFloat()) else 0f
                val alpha = if (p > SealLetter.LIFT) 1f - (p - SealLetter.LIFT) / (1f - SealLetter.LIFT) else (p / 0.12f).coerceIn(0f, 1f)
                val head = seal + Offset(0f, -wax * 0.15f - down * 90.dp.toPx())
                rotate(shake, head) {
                    scale(2f - squash, squash, Offset(head.x, head.y + wax)) {
                        drawRoundRect(Color(0xFF6B3E26).copy(alpha = alpha), Offset(head.x - 7.dp.toPx(), head.y - 46.dp.toPx()), Size(14.dp.toPx(), 34.dp.toPx()), CornerRadius(7.dp.toPx()))
                        drawCircle(Color(0xFF8A5634).copy(alpha = alpha), 13.dp.toPx(), Offset(head.x, head.y - 50.dp.toPx()))
                        drawRoundRect(Color(0xFF3B2A20).copy(alpha = alpha), Offset(head.x - wax * 1.05f, head.y - 12.dp.toPx()), Size(wax * 2.1f, 14.dp.toPx()), CornerRadius(4.dp.toPx()))
                    }
                }
            }
            SealLetter.Step.SETTLE -> {
                val k = 1f + 0.03f * sin(p * PI.toFloat())
                scale(k, k, env.center) { sealedLetter() }
            }
            SealLetter.Step.OFF -> {
                // Off upwards, to next year.
                val e = easeIn(p)
                translate(0f, -(env.bottom + 40.dp.toPx()) * e) { sealedLetter() }
            }
        }
    }
}

/** How opaque the paper under the letter is: hidden while the letter is sealed, then in again in 0.3 s. */
internal fun paperUnderLetter(ms: Long, still: Boolean): Float = when {
    still || ms < 0 -> 1f
    ms < SealLetter.TOTAL_MS -> 0f
    else -> ((ms - SealLetter.TOTAL_MS) / 300f).coerceIn(0f, 1f)
}
