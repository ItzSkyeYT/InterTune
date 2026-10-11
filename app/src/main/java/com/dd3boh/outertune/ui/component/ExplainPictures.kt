/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A small moving picture at the head of an explanation, for the settings where a picture says
 * it faster than the words under it (Unreleased.EXPLAIN_PICTURES). Drawn here from the theme's
 * own colours, so it follows the theme and weighs nothing; it has no words in it, so there is
 * nothing to translate; and it holds still on a phone that has animations turned off.
 */
enum class ExplainPicture { FADE }

/** The bars of sound a song is drawn with. */
internal const val FADE_BARS = 12

/** How long a song sounds before it hands over, in seconds. */
internal const val FADE_HOLD = 1.4f

/** How long one bar takes to sink, or to rise. */
internal const val FADE_FALL = 0.9f

/** How much later the last bar of a song starts than its first: a song does not move as one block. */
internal const val FADE_SWEEP = 0.3f

/** How long nothing sounds between the two. */
internal const val FADE_GAP = 0.2f

/** One song handing over to the other: it sounds, sinks, nothing sounds, and the other rises. */
internal const val FADE_PASS = FADE_HOLD + 2 * (FADE_FALL + FADE_SWEEP) + FADE_GAP

/**
 * There and back: the left song hands over to the right one, which hands it back. So the loop
 * ends where it began and has no seam, where a picture that started over would jump.
 */
internal const val FADE_LOOP = 2 * FADE_PASS

/** An ease with no corner at either end: nothing starts or stops with a jolt. */
private fun eased(x: Float): Float {
    val within = x.coerceIn(0f, 1f)
    return within * within * (3f - 2f * within)
}

/**
 * How loud bar [bar] (0 is the leftmost) of song [song] (0 the left one, 1 the right) is at
 * [seconds], from 0 for nothing to 1.
 *
 * The song that is ending sinks first, and only when its last bar is down and a moment has
 * passed does the other rise: never both at once, which is what Fade between tracks does and
 * what a crossfade does not. Neither moves as one block. The sinking starts at the far side of
 * the song that is ending and the rising at the near side of the one that is starting, so the
 * change travels across the picture from the one to the other.
 */
internal fun fadeLevel(song: Int, bar: Int, seconds: Float): Float {
    val at = seconds.mod(FADE_LOOP)
    val handing = if (at < FADE_PASS) 0 else 1
    val into = at - handing * FADE_PASS
    val ending = song == handing
    val fromFarSide = if (song == 0) bar else FADE_BARS - 1 - bar
    val turn = if (ending) fromFarSide else FADE_BARS - 1 - fromFarSide
    val wait = turn * FADE_SWEEP / (FADE_BARS - 1)
    return if (ending) {
        1f - eased((into - FADE_HOLD - wait) / FADE_FALL)
    } else {
        eased((into - FADE_HOLD - FADE_SWEEP - FADE_FALL - FADE_GAP - wait) / FADE_FALL)
    }
}

/** How far a bar of sound sways from its height, as a share of it. */
private const val SWAY = 0.3f

/**
 * The share of its full height a bar has at [seconds] while its song sounds, up to 1. Bars of
 * sound are never still, and neighbours move together and not each for itself: two slow waves
 * run along the song, one each way, and each song has its own.
 */
internal fun fadeSway(song: Int, bar: Int, seconds: Float): Float {
    val one = sin(2 * PI * (0.9 * seconds - bar / 7.0) + song * 1.9)
    val other = sin(2 * PI * (1.6 * seconds + bar / 4.3) + song * 3.8)
    return (1f + SWAY * (0.6 * one + 0.4 * other).toFloat()) / (1f + SWAY)
}

/** A bar's share of its height where nothing moves: the middle of its sway. */
private const val STILL_SWAY = 1f / (1f + SWAY)

/**
 * The picture for a phone with animations off, where nothing may move, so time runs from left
 * to right instead: the left song's bars get shorter towards the middle, down to nothing, and
 * the right song's grow from nothing.
 */
internal fun fadeStill(song: Int, bar: Int): Float {
    val fromMiddle = if (song == 0) FADE_BARS - 1 - bar else bar
    return eased((fromMiddle - 1) / 6f)
}

@Composable
fun ExplainPicturePanel(picture: ExplainPicture, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The system's own setting for how fast things move: 0 is animations off, and the picture
    // then stands still.
    val pace = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    val moving = pace > 0f
    // Read where the picture is drawn and nowhere else, so a new frame draws the picture again
    // and composes nothing.
    val seconds = remember { mutableFloatStateOf(0f) }
    if (moving) {
        LaunchedEffect(pace) {
            val began = withFrameNanos { it }
            while (true) withFrameNanos { seconds.floatValue = (it - began) / 1e9f / pace }
        }
    }
    val one = MaterialTheme.colorScheme.primary
    val other = MaterialTheme.colorScheme.tertiary
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
            ExplainPicture.FADE -> fade(if (moving) seconds.floatValue else null, one, other, silent)
        }
    }
}

/** The heights of a song's bars of sound, as a share of the tallest. */
private val SHAPE = floatArrayOf(0.4f, 0.65f, 0.9f, 0.55f, 0.75f, 1f, 0.6f, 0.85f, 0.5f, 0.7f, 0.95f, 0.55f)

/**
 * Two songs side by side as bars of sound. The one that sounds sways; as it ends its bars sink
 * to a row of small dots and lose their colour on the way, and only then do the other's grow
 * and take theirs. At [seconds] of the loop, or held still where there is none (fadeStill).
 */
private fun DrawScope.fade(seconds: Float?, one: Color, other: Color, silent: Color) {
    val edge = size.width * 0.08f
    val between = size.width * 0.07f
    val song = (size.width - 2 * edge - between) / 2f
    val step = song / FADE_BARS
    val middle = size.height / 2f
    val tallest = size.height * 0.6f
    val wide = step * 0.58f
    // A bar that is silent is a dot, a little smaller than a bar is wide.
    val dot = wide * 0.62f
    for (which in 0..1) {
        val left = if (which == 0) edge else edge + song + between
        val colour = if (which == 0) one else other
        for (bar in 0 until FADE_BARS) {
            val level = if (seconds != null) fadeLevel(which, bar, seconds) else fadeStill(which, bar)
            val sway = if (seconds != null) fadeSway(which, bar, seconds) else STILL_SWAY
            // The right song is the left one's shape turned round: two songs, and not one twice.
            val shape = SHAPE[if (which == 0) bar else FADE_BARS - 1 - bar]
            val tall = dot + (tallest * shape * sway - dot) * level
            val width = dot + (wide - dot) * level
            drawRoundRect(
                color = lerp(silent, colour, level),
                topLeft = Offset(left + bar * step + (step - width) / 2f, middle - tall / 2f),
                size = Size(width, tall),
                cornerRadius = CornerRadius(width / 2f),
            )
        }
    }
}
