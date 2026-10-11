/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.StageWidthKey
import com.dd3boh.outertune.playback.BinauralAudioProcessor
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A small moving picture at the head of an explanation, for the settings where a picture says
 * it faster than the words under it (Unreleased.EXPLAIN_PICTURES). Drawn here from the theme's
 * own colours, so it follows the theme and weighs nothing; it has no words in it, so there is
 * nothing to translate; and it holds still on a phone that has animations turned off.
 */
enum class ExplainPicture { FADE, HEAD_TRACKING, STAGE_WIDTH }

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

/**
 * A swing from -1 to 1 and back over one of [turns], which lingers at each end and has no
 * corner anywhere: a sine pressed towards its ends. A head that turns holds its look a moment
 * before it turns back, and so does anything else that goes to and fro here.
 */
internal fun swing(turns: Float): Float = (tanh(LINGER * sin(2 * PI * turns)) / tanh(LINGER)).toFloat()

private const val LINGER = 1.4

/** How long the head takes to look one way, the other, and to the front again, in seconds. */
internal const val TURN_LOOP = 5.2f

/** How far the head turns to either side, in degrees. */
internal const val TURN_MOST = 42f

/** Which way the head faces at [seconds], in degrees, to the right above 0. It opens facing the music. */
internal fun headTurn(seconds: Float): Float = TURN_MOST * swing(seconds / TURN_LOOP)

/** The head on a phone with animations off: turned, with the music where it was. */
internal const val STILL_TURN = -28f

/** How long the two speakers take to open out and come back, in seconds. */
internal const val WIDTH_LOOP = 5.2f

/** The narrowest and the widest the stage width picture shows, in degrees either side. */
internal const val WIDTH_NARROW = 18f
internal const val WIDTH_WIDE = 80f

/** How far either side the two speakers stand at [seconds]. It opens narrow. */
internal fun stageAngle(seconds: Float): Float =
    (WIDTH_NARROW + WIDTH_WIDE) / 2f + (WIDTH_WIDE - WIDTH_NARROW) / 2f * swing(seconds / WIDTH_LOOP - 0.25f)

/** The speakers on a phone with animations off. */
internal const val STILL_WIDTH = 45f

/**
 * The measures of the picture with the listener and the two speakers, as shares of its height:
 * how far down the listener's head is, how big it is, how far from it the speakers stand, and
 * how big they are.
 */
internal const val STAGE_HEAD_AT = 0.8f
internal const val STAGE_HEAD = 0.13f
internal const val STAGE_AWAY = 0.56f
internal const val STAGE_SPEAKER = 0.05f

/** How far to the side of the head's middle an ear is, with its cup over it. */
internal const val STAGE_EAR = STAGE_HEAD * 1.09f

/**
 * Where the sound on the right seems to come from, as shares of the picture's height: how far
 * right of the middle, and how far down from the top. The one on the left is its mirror.
 *
 * @param either how far to the right of straight ahead its speaker stands, in degrees
 * @param out how much of the spatial effect there is, 0 to 1. With all of it the sound is out
 *   at its speaker. With none it is the plain sound of the headphones, in the cup over the ear,
 *   and in between it is on its way from the one to the other
 */
internal fun stageSpot(either: Float, out: Float = 1f): Pair<Float, Float> {
    val angle = Math.toRadians(either.toDouble())
    val right = (STAGE_AWAY * sin(angle)).toFloat()
    val down = (STAGE_HEAD_AT - STAGE_AWAY * cos(angle)).toFloat()
    val share = out.coerceIn(0f, 1f)
    return (STAGE_EAR + (right - STAGE_EAR) * share) to (STAGE_HEAD_AT + (down - STAGE_HEAD_AT) * share)
}

/** The ripples of sound on their way from one speaker to the listener at any moment. */
internal const val RIPPLES = 3

/** How long a ripple takes to get there, in seconds. */
internal const val RIPPLE_SECONDS = 1.8f

/**
 * Where ripple [number] of a speaker is at [seconds]: how far it has come, 0 at the speaker to 1
 * at the listener, and how strongly it shows, 0 to 1. It is born and it dies at nothing, so that
 * a ripple starting over is never seen to.
 */
internal fun ripple(number: Int, seconds: Float): Pair<Float, Float> {
    val far = (seconds / RIPPLE_SECONDS + number.toFloat() / RIPPLES).mod(1f)
    return far to 4f * far * (1f - far)
}

/**
 * @param either for the pictures with the two speakers: how far to either side they stand, in
 *   degrees, where whoever shows the picture knows it better than what is stored. Beside the
 *   slider that sets it, that is where the slider is while it is being dragged.
 * @param strength beside the sliders, how much of the spatial effect is set, 0 to 1: the sound
 *   is drawn that far out of the headphones towards its speakers
 */
@Composable
fun ExplainPicturePanel(
    picture: ExplainPicture,
    modifier: Modifier = Modifier,
    either: Float? = null,
    strength: Float? = null,
) {
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
    // Where the two speakers stand, in the pictures that have them. Beside its slider the stage
    // width picture shows the width that is being set. The head tracking picture shows this
    // listener's own: the music stays where they have put it. Neither jumps there: a tap on the
    // slider, or a drag that goes degree by degree, is glided to.
    val set = when (picture) {
        ExplainPicture.FADE -> null
        ExplainPicture.STAGE_WIDTH -> either
        ExplainPicture.HEAD_TRACKING -> either ?: rememberPreference(
            StageWidthKey,
            defaultValue = BinauralAudioProcessor.DEFAULT_STAGE_WIDTH.toInt(),
        ).value.toFloat()
    }
    val standing = animateFloatAsState(
        targetValue = set ?: STILL_WIDTH,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "speakers",
    )
    val out = animateFloatAsState(
        targetValue = strength ?: 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "strength",
    )
    val one = MaterialTheme.colorScheme.primary
    val other = MaterialTheme.colorScheme.tertiary
    val silent = MaterialTheme.colorScheme.outlineVariant
    val listener = MaterialTheme.colorScheme.onSurfaceVariant
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
            // The music stays where it is and the head turns.
            ExplainPicture.HEAD_TRACKING -> stage(
                turn = if (moving) headTurn(seconds.floatValue) else STILL_TURN,
                either = standing.value,
                out = 1f,
                seconds = if (moving) seconds.floatValue else 0f,
                headphones = one, music = other, listener = listener, width = null,
            )
            // The head stays where it is and the two speakers move: to where the slider is,
            // beside the slider, and open and shut in the explanation, which shows what it does.
            ExplainPicture.STAGE_WIDTH -> stage(
                turn = 0f,
                either = if (set != null) standing.value else if (moving) stageAngle(seconds.floatValue) else STILL_WIDTH,
                out = out.value,
                seconds = if (moving) seconds.floatValue else 0f,
                headphones = one, music = other, listener = listener, width = silent,
            )
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

/**
 * A listener seen from above, wearing headphones and facing up the picture, and the two places
 * in front of them the sound comes from, with ripples of it on their way in.
 *
 * @param turn which way the head faces, in degrees, to the right above 0
 * @param either how far to either side of straight ahead the two speakers stand, in degrees
 * @param out how much of the spatial effect there is, 0 to 1: how far out of the headphones
 *   and towards its speaker each sound is (stageSpot)
 * @param seconds where the ripples are
 * @param width the colour of a line drawn from one sound round to the other, where the picture
 *   is about how far apart they are; none where it is not
 */
private fun DrawScope.stage(
    turn: Float,
    either: Float,
    out: Float,
    seconds: Float,
    headphones: Color,
    music: Color,
    listener: Color,
    width: Color?,
) {
    val centre = Offset(size.width / 2f, size.height * STAGE_HEAD_AT)
    val skull = size.height * STAGE_HEAD
    val speaker = size.height * STAGE_SPEAKER
    val line = 2.dp.toPx()

    val (right, down) = stageSpot(either, out)
    val across = right * size.height
    val ahead = (STAGE_HEAD_AT - down) * size.height
    // How far from the middle of the head the sound is, and how far round from straight ahead.
    val away = hypot(across, ahead)
    val round = Math.toDegrees(atan2(across.toDouble(), ahead.toDouble())).toFloat()

    if (width != null) {
        drawArc(
            // Gone by the time the sound is back in the headphones: there is no width left to show.
            color = width.copy(alpha = width.alpha * out),
            // 0 degrees is to the right here and straight ahead is up the picture, a quarter turn back.
            startAngle = -90f - round,
            sweepAngle = 2 * round,
            useCenter = false,
            topLeft = Offset(centre.x - away, centre.y - away),
            size = Size(away * 2, away * 2),
            style = Stroke(width = line * 0.75f, cap = StrokeCap.Round),
        )
    }

    // Ripples are parts of circles round the sound, the part that faces the listener, and a
    // narrow part: wide ones from two speakers that stand close cross into a tangle. They have
    // the way from the sound to the head to cross, and fade as that way gets short.
    val first = speaker * 1.5f
    val last = away - skull * 1.35f
    val furthest = size.height * STAGE_AWAY - skull * 1.35f
    // By the root, so that half the way still shows more than half as strongly: with the
    // effect at half there is sound out there, and the picture should not go dead.
    val room = sqrt(((last - first) / (furthest - first)).coerceIn(0f, 1f))
    for (side in intArrayOf(-1, 1)) {
        val at = Offset(centre.x + side * across, centre.y - ahead)
        val facing = Math.toDegrees(atan2((centre.y - at.y).toDouble(), (centre.x - at.x).toDouble())).toFloat()
        if (room > 0f) for (number in 0 until RIPPLES) {
            val (far, strength) = ripple(number, seconds)
            val reach = first + (last - first) * far
            drawArc(
                color = music.copy(alpha = 0.75f * strength * room),
                startAngle = facing - 20f,
                sweepAngle = 40f,
                useCenter = false,
                topLeft = Offset(at.x - reach, at.y - reach),
                size = Size(reach * 2, reach * 2),
                style = Stroke(width = line, cap = StrokeCap.Round),
            )
        }
    }

    rotate(degrees = turn, pivot = centre) {
        // The nose says which way the head faces: a small bump at the front of it.
        drawCircle(color = listener, radius = skull * 0.27f, center = Offset(centre.x, centre.y - skull * 0.96f))
        drawCircle(color = listener, radius = skull, center = centre)
        // The headphones: a thin band over the head from ear to ear, and a cup at each end of it.
        val band = skull * 0.2f
        val cup = Size(skull * 0.46f, skull * 0.95f)
        drawRoundRect(
            color = headphones,
            topLeft = Offset(centre.x - skull - cup.width * 0.3f, centre.y - band / 2f),
            size = Size(skull * 2 + cup.width * 0.6f, band),
            cornerRadius = CornerRadius(band / 2f),
        )
        for (ear in intArrayOf(-1, 1)) {
            drawRoundRect(
                color = headphones,
                topLeft = Offset(centre.x + ear * (skull + cup.width * 0.2f) - cup.width / 2f, centre.y - cup.height / 2f),
                size = cup,
                cornerRadius = CornerRadius(cup.width / 2f),
            )
        }
    }

    // The two sounds last, over the cups when they are in them, and a little smaller there.
    for (side in intArrayOf(-1, 1)) {
        drawCircle(
            color = music,
            radius = speaker * (0.72f + 0.28f * out),
            center = Offset(centre.x + side * across, centre.y - ahead),
        )
    }
}
