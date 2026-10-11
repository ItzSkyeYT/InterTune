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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A small moving picture at the head of an explanation, for the settings where a picture says
 * it faster than the words under it (Unreleased.EXPLAIN_PICTURES). Drawn here from the theme's
 * own colours, so it follows the theme and weighs nothing; it has no words in it, so there is
 * nothing to translate; and it holds still on a phone that has animations turned off.
 */
enum class ExplainPicture { FADE, HEAD_TRACKING, SOUND_STAGE, PROXIMITY }

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

/**
 * The small room round the listener and the speakers, as shares of the picture's height: how far
 * to either side of the middle its side walls are, and how far down its front wall and the wall
 * behind the listener.
 */
internal const val ROOM_HALF = 0.92f
internal const val ROOM_FRONT = 0.07f
internal const val ROOM_BACK = 0.975f

/**
 * Where the first echo of a sound at [spot] seems to come from: as far behind the side wall as
 * the sound is in front of it, which is where a wall sends a sound back from.
 */
internal fun echoSpot(spot: Pair<Float, Float>): Pair<Float, Float> = (2 * ROOM_HALF - spot.first) to spot.second

/**
 * How far to the side of straight ahead the room sound of a recording is, in degrees, with the
 * speakers [either] degrees to the side and [ambience] of the way out, 0 to 1: with none it is
 * at its speaker with everything else, and with all of it, it is beside the listener's ear.
 */
internal fun ambienceAngle(either: Float, ambience: Float): Float =
    either + (max(90f, either) - either) * ambience.coerceIn(0f, 1f)

/** The rings of low sound round one cup at any moment, where the low notes stay in the headphones. */
internal const val THUMPS = 2

/** How long one of them takes to spread and go, in seconds: slow, as low notes are. */
internal const val THUMP_SECONDS = 1.3f

/** Where ring [number] round a cup is at [seconds]: how far it has spread, 0 to 1, and how strongly it shows. */
internal fun thump(number: Int, seconds: Float): Pair<Float, Float> {
    val spread = (seconds / THUMP_SECONDS + number.toFloat() / THUMPS).mod(1f)
    return spread to 4f * spread * (1f - spread)
}

/** How long the listener takes to walk away from the phone and come back, in seconds. */
internal const val WALK_LOOP = 6.4f

/**
 * How much of the music is left at the far end of the walk: it gets quieter, and it never mutes.
 * Less than the setting itself takes off, which is only a little: a picture has to show the
 * change at a glance.
 */
internal const val WALK_QUIETEST = 0.28f

/**
 * How far from the phone the listener is at [seconds], 0 beside it to 1 at the far end of the
 * walk. It opens beside the phone, and they stand a moment at each end before turning round.
 */
internal fun walkAway(seconds: Float): Float = (1f + swing(seconds / WALK_LOOP - 0.25f)) / 2f

/**
 * Which way the listener faces at [seconds], in degrees from up the picture: to the right on the
 * way out, to the left on the way back, and turning round while they stand at either end.
 */
internal fun walkFacing(seconds: Float): Float = 90f * swing(seconds / WALK_LOOP)

/**
 * How loud the music is to the listener at [seconds], from [WALK_QUIETEST] to 1: by how far
 * from the phone they are at that moment, and by nothing else. So it changes while they walk,
 * step for step, and holds while they stand: a picture where the sound went on changing after
 * the walking had stopped would say the wrong thing about what changes it.
 */
internal fun walkLevel(seconds: Float): Float = 1f - (1f - WALK_QUIETEST) * walkAway(seconds)

/** The moment of the walk shown on a phone with animations off: on the way out, and quieter already. */
internal const val STILL_WALK = WALK_LOOP * 0.32f

/**
 * What a listener has set of where the sound sits round their head. Beside those sliders the
 * picture shows it, and moves as any of them is dragged.
 *
 * @param width how far to either side of straight ahead the two speakers stand, in degrees
 * @param strength how much of the effect there is, 0 to 1: with less, the sound is drawn that
 *   much of the way back into the headphones
 * @param room how much of a small room there is round the speakers, 0 to 1: its walls, and the
 *   first echoes off them
 * @param ambience how far the room sound of a recording is moved out to the sides, 0 to 1
 * @param bassAtEars whether the low notes skip the effect and stay in the headphones
 */
data class SoundStage(
    val width: Float,
    val strength: Float = 1f,
    val room: Float = 0f,
    val ambience: Float = 0f,
    val bassAtEars: Boolean = false,
)

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
 * @param stage for the pictures with the two speakers: where the sound sits, where whoever shows
 *   the picture knows it better than what is stored. Beside the sliders that set it, that is
 *   where the sliders are while one is being dragged.
 */
@Composable
fun ExplainPicturePanel(picture: ExplainPicture, modifier: Modifier = Modifier, stage: SoundStage? = null) {
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
    // Where the two speakers stand, in the pictures that have them: where the sliders are,
    // beside the sliders, and anywhere else where this listener has set them. The music of the
    // head tracking picture stays where they have put it. Nothing jumps to where a slider is: a
    // tap on one, or a drag that goes step by step, is glided to.
    val set = when (picture) {
        ExplainPicture.FADE, ExplainPicture.PROXIMITY -> null
        ExplainPicture.SOUND_STAGE, ExplainPicture.HEAD_TRACKING -> stage?.width ?: rememberPreference(
            StageWidthKey,
            defaultValue = BinauralAudioProcessor.DEFAULT_STAGE_WIDTH.toInt(),
        ).value.toFloat()
    }
    val glide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val standing = animateFloatAsState(set ?: 0f, glide, label = "speakers")
    val out = animateFloatAsState(stage?.strength ?: 1f, glide, label = "strength")
    val walls = animateFloatAsState(stage?.room ?: 0f, glide, label = "room")
    val aside = animateFloatAsState(stage?.ambience ?: 0f, glide, label = "ambience")
    val low = animateFloatAsState(if (stage?.bassAtEars == true) 1f else 0f, glide, label = "bass")
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
                out = 1f, room = 0f, ambience = 0f, bass = 0f,
                seconds = if (moving) seconds.floatValue else 0f,
                headphones = one, music = other, listener = listener, faint = silent, width = false,
            )
            // The head stays where it is, and the sound is where the sliders beside the picture
            // have it.
            ExplainPicture.SOUND_STAGE -> stage(
                turn = 0f,
                either = standing.value,
                out = out.value, room = walls.value, ambience = aside.value, bass = low.value,
                seconds = if (moving) seconds.floatValue else 0f,
                headphones = one, music = other, listener = listener, faint = silent, width = true,
            )
            // The phone is where the music is, and the listener walks away from it and back.
            ExplainPicture.PROXIMITY -> walk(
                seconds = if (moving) seconds.floatValue else STILL_WALK,
                headphones = one, music = other, listener = listener,
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
 * @param room how much of a small room there is round them, 0 to 1: its walls, and the first
 *   echo of each sound off the wall beside it
 * @param ambience how far the room sound of the recording is moved out to the sides, 0 to 1: a
 *   second, fainter pair of sounds that leaves the speakers for the listener's sides
 * @param bass how much the low notes stay in the headphones, 0 to 1: slow rings round the cups
 * @param seconds where the ripples are
 * @param width whether a line is drawn from one sound round to the other, where the picture is
 *   about how far apart they are
 */
private fun DrawScope.stage(
    turn: Float,
    either: Float,
    out: Float,
    room: Float,
    ambience: Float,
    bass: Float,
    seconds: Float,
    headphones: Color,
    music: Color,
    listener: Color,
    faint: Color,
    width: Boolean,
) {
    val tall = size.height
    val centre = Offset(size.width / 2f, tall * STAGE_HEAD_AT)
    val skull = tall * STAGE_HEAD
    val speaker = tall * STAGE_SPEAKER
    val line = 2.dp.toPx()

    val spot = stageSpot(either, out)
    val across = spot.first * tall
    val ahead = (STAGE_HEAD_AT - spot.second) * tall
    // How far from the middle of the head the sound is, and how far round from straight ahead.
    val away = hypot(across, ahead)
    val round = Math.toDegrees(atan2(across.toDouble(), ahead.toDouble())).toFloat()

    // Ripples are parts of circles round a sound, the part that faces the listener, and a narrow
    // part: wide ones from two speakers that stand close cross into a tangle. They cross from
    // [from] to [to], measured from where they start, and fade by [shown].
    fun ripples(source: Offset, from: Float, to: Float, shown: Float, half: Float) {
        if (shown <= 0f || to <= from) return
        val facing = Math.toDegrees(atan2((centre.y - source.y).toDouble(), (centre.x - source.x).toDouble())).toFloat()
        for (number in 0 until RIPPLES) {
            val (far, strength) = ripple(number, seconds)
            val reach = from + (to - from) * far
            drawArc(
                color = music.copy(alpha = shown * strength),
                startAngle = facing - half,
                sweepAngle = 2 * half,
                useCenter = false,
                topLeft = Offset(source.x - reach, source.y - reach),
                size = Size(reach * 2, reach * 2),
                style = Stroke(width = line, cap = StrokeCap.Round),
            )
        }
    }

    // The room first, behind everything: its walls, and inside them the first echo of each
    // sound, which comes from as far behind the side wall as the sound is in front of it.
    if (room > 0f) {
        val left = centre.x - ROOM_HALF * tall
        val top = ROOM_FRONT * tall
        val walls = Size(2 * ROOM_HALF * tall, (ROOM_BACK - ROOM_FRONT) * tall)
        drawRoundRect(
            color = faint.copy(alpha = faint.alpha * room),
            topLeft = Offset(left, top),
            size = walls,
            cornerRadius = CornerRadius(tall * 0.1f),
            style = Stroke(width = line * 0.75f),
        )
        val behind = echoSpot(spot)
        clipRect(left, top, left + walls.width, top + walls.height) {
            for (side in intArrayOf(-1, 1)) {
                val source = Offset(centre.x + side * behind.first * tall, behind.second * tall)
                val wall = (behind.first - ROOM_HALF) * tall
                val whole = hypot(source.x - centre.x, source.y - centre.y)
                ripples(source, from = wall, to = whole - skull * 1.35f, shown = 0.45f * room * out, half = 13f)
            }
        }
    }

    if (width) {
        drawArc(
            // Gone by the time the sound is back in the headphones: there is no width left to show.
            color = faint.copy(alpha = faint.alpha * out),
            // 0 degrees is to the right here and straight ahead is up the picture, a quarter turn back.
            startAngle = -90f - round,
            sweepAngle = 2 * round,
            useCenter = false,
            topLeft = Offset(centre.x - away, centre.y - away),
            size = Size(away * 2, away * 2),
            style = Stroke(width = line * 0.75f, cap = StrokeCap.Round),
        )
    }

    // The sounds have the way from where they are to the head to cross, and their ripples fade
    // as that way gets short. By the root, so that half the way still shows more than half as
    // strongly: with the effect at half there is sound out there, and the picture should not
    // go dead.
    val first = speaker * 1.5f
    val furthest = tall * STAGE_AWAY - skull * 1.35f
    fun crossing(distance: Float) = sqrt(((distance - skull * 1.35f - first) / (furthest - first)).coerceIn(0f, 1f))
    for (side in intArrayOf(-1, 1)) {
        ripples(Offset(centre.x + side * across, centre.y - ahead), first, away - skull * 1.35f, 0.75f * crossing(away), half = 20f)
    }

    // The room sound of the recording, where it has been moved out to the sides: a second pair,
    // fainter, that leaves the speakers for the listener's ears. Not shown while it is still
    // at its speaker, where it is part of that sound.
    val sideways = ambienceAngle(either, ambience)
    val apart = ((sideways - either) / 8f).coerceIn(0f, 1f)
    val beside = stageSpot(sideways, out)
    val besideAcross = beside.first * tall
    val besideAhead = (STAGE_HEAD_AT - beside.second) * tall
    if (apart > 0f) for (side in intArrayOf(-1, 1)) {
        val away2 = hypot(besideAcross, besideAhead)
        ripples(Offset(centre.x + side * besideAcross, centre.y - besideAhead), first, away2 - skull * 1.35f, 0.4f * apart * crossing(away2), half = 16f)
    }

    listener(centre, skull, turn, listener, headphones)

    // The low notes, where they stay in the headphones: slow rings spreading from each cup.
    if (bass > 0f) for (side in intArrayOf(-1, 1)) {
        val ear = Offset(centre.x + side * STAGE_EAR * tall, centre.y)
        for (number in 0 until THUMPS) {
            val (spread, strength) = thump(number, seconds)
            drawCircle(
                color = music.copy(alpha = 0.6f * strength * bass),
                radius = skull * (0.55f + 0.75f * spread),
                center = ear,
                style = Stroke(width = line * 1.25f),
            )
        }
    }

    // The sounds last, over the cups when they are in them, and a little smaller there. The
    // room sound is a ring and not a dot: it is some of the sound, not another speaker.
    if (apart > 0f) for (side in intArrayOf(-1, 1)) {
        drawCircle(
            color = music.copy(alpha = 0.85f * apart),
            radius = speaker * 0.8f * (0.72f + 0.28f * out),
            center = Offset(centre.x + side * besideAcross, centre.y - besideAhead),
            style = Stroke(width = line * 0.9f),
        )
    }
    for (side in intArrayOf(-1, 1)) {
        drawCircle(
            color = music,
            radius = speaker * (0.72f + 0.28f * out),
            center = Offset(centre.x + side * across, centre.y - ahead),
        )
    }
}

/** A listener seen from above with headphones on, the head [skull] across its half, facing [turn] degrees from up the picture. */
private fun DrawScope.listener(centre: Offset, skull: Float, turn: Float, head: Color, headphones: Color) {
    rotate(degrees = turn, pivot = centre) {
        // The nose says which way the head faces: a small bump at the front of it.
        drawCircle(color = head, radius = skull * 0.27f, center = Offset(centre.x, centre.y - skull * 0.96f))
        drawCircle(color = head, radius = skull, center = centre)
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
}

/** The heights of the few bars of sound over the listener's head, as a share of the tallest. */
private val HEARD = floatArrayOf(0.65f, 0.9f, 1f, 0.8f, 0.6f)

/**
 * A phone lying on the left, which is where the music comes from, and a listener seen from
 * above who walks away from it and back. The ripples from the phone thin out with distance, and
 * the bars of sound over the listener's head, which are what they hear, shrink as they go and
 * grow as they return: a little behind them, and never to nothing.
 */
private fun DrawScope.walk(seconds: Float, headphones: Color, music: Color, listener: Color) {
    val tall = size.height
    val middle = size.width / 2f
    val ground = tall * 0.7f
    val skull = tall * STAGE_HEAD
    val line = 2.dp.toPx()
    val phone = Offset(middle - tall * 0.88f, ground)
    val nearest = middle - tall * 0.44f
    val farthest = middle + tall * 0.8f
    val head = Offset(nearest + (farthest - nearest) * walkAway(seconds), ground)

    // The music leaving the phone towards where the listener walks, weaker the further it has gone.
    val reach = farthest - phone.x
    for (number in 0 until RIPPLES) {
        val (far, strength) = ripple(number, seconds)
        val gone = tall * 0.16f + (reach - tall * 0.16f) * far
        drawArc(
            color = music.copy(alpha = 0.6f * strength * (1f - 0.85f * far)),
            startAngle = -14f,
            sweepAngle = 28f,
            useCenter = false,
            topLeft = Offset(phone.x - gone, phone.y - gone),
            size = Size(gone * 2, gone * 2),
            style = Stroke(width = line, cap = StrokeCap.Round),
        )
    }

    // The phone, lying flat, and on it the dot of a sound, as the speakers of the other picture are.
    val body = Size(tall * 0.17f, tall * 0.32f)
    drawRoundRect(
        color = listener,
        topLeft = Offset(phone.x - body.width / 2f, phone.y - body.height / 2f),
        size = body,
        cornerRadius = CornerRadius(body.width * 0.24f),
    )
    drawCircle(color = music, radius = tall * STAGE_SPEAKER * 0.8f, center = phone)

    listener(head, skull, walkFacing(seconds), listener, headphones)

    // What they hear, over their head and going with them: bars that are tall and bright beside
    // the phone and short and dim far from it. They sway only a little, so that it is the
    // distance that is seen to change them.
    val level = walkLevel(seconds)
    val step = tall * 0.085f
    val wide = step * 0.6f
    val tallest = tall * 0.44f
    val over = ground - skull - tall * 0.27f
    val bright = 0.5f + 0.5f * (level - WALK_QUIETEST) / (1f - WALK_QUIETEST)
    for (bar in HEARD.indices) {
        val alive = 0.82f + 0.18f * fadeSway(0, bar, seconds)
        val height = max(wide, tallest * HEARD[bar] * alive * level)
        drawRoundRect(
            color = music.copy(alpha = bright),
            topLeft = Offset(head.x + (bar - (HEARD.size - 1) / 2f) * step - wide / 2f, over - height / 2f),
            size = Size(wide, height),
            cornerRadius = CornerRadius(wide / 2f),
        )
    }
}
