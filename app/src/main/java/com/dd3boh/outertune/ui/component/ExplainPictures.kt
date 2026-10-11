/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * A small moving picture at the head of an explanation, for the settings where a picture says
 * it faster than the words under it (Unreleased.EXPLAIN_PICTURES). Drawn here from the theme's
 * own colours, so it follows the theme and weighs nothing; it has no words in it, so there is
 * nothing to translate; and it holds still on a phone that has animations turned off.
 */
enum class ExplainPicture { FADE }

/** One turn of a picture's loop. */
private const val LOOP_MS = 3600

/** Where a loop is held on a phone with animations off: the first song part of the way down. */
internal const val STILL_PHASE = 0.375f

/**
 * How loud the song that is ending and the one that is starting are at [phase] of the loop, 0
 * to 1: the first holds, sinks to nothing, and only then does the next rise and hold. Never
 * both at once, which is what Fade between tracks does and what a crossfade does not.
 */
internal fun fadeLevels(phase: Float): Pair<Float, Float> {
    val p = phase.coerceIn(0f, 1f)
    return when {
        p <= 0.25f -> 1f to 0f
        p < 0.5f -> (0.5f - p) / 0.25f to 0f
        p < 0.75f -> 0f to (p - 0.5f) / 0.25f
        else -> 0f to 1f
    }
}

@Composable
fun ExplainPicturePanel(picture: ExplainPicture, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val moving = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val phase = if (moving) {
        val loop = rememberInfiniteTransition(label = "explain picture")
        val turning by loop.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)),
            label = "phase",
        )
        turning
    } else {
        STILL_PHASE
    }
    val ending = MaterialTheme.colorScheme.primary
    val starting = MaterialTheme.colorScheme.tertiary
    val silent = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            // A picture of what the words under it say: a screen reader is read the words.
            .clearAndSetSemantics { }
    ) {
        when (picture) {
            ExplainPicture.FADE -> fade(phase, ending, starting, silent)
        }
    }
}

/** The heights of a song's bars of sound, as a share of the tallest. */
private val BARS = floatArrayOf(0.4f, 0.65f, 0.9f, 0.55f, 0.75f, 1f, 0.6f, 0.85f, 0.5f, 0.7f, 0.95f, 0.55f)

/** Two songs side by side as bars of sound: the left one sinks to a line, and only then the right one grows. */
private fun DrawScope.fade(phase: Float, ending: Color, starting: Color, silent: Color) {
    val (first, next) = fadeLevels(phase)
    val edge = size.width * 0.08f
    val between = size.width * 0.07f
    val song = (size.width - 2 * edge - between) / 2f
    val step = song / BARS.size
    val middle = size.height / 2f
    val tallest = size.height * 0.58f
    val least = 3.dp.toPx()
    fun bars(left: Float, level: Float, colour: Color) {
        for (i in BARS.indices) {
            val tall = max(tallest * BARS[i] * level, least)
            drawRoundRect(
                color = if (level > 0f) colour else silent,
                topLeft = Offset(left + i * step + step * 0.2f, middle - tall / 2f),
                size = Size(step * 0.6f, tall),
                cornerRadius = CornerRadius(step * 0.3f),
            )
        }
    }
    bars(edge, first, ending)
    bars(edge + song + between, next, starting)
}
