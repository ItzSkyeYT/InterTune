/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.utils.Animations
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The pictures of How rooms work (RoomTour): a room drawn as the sheet shows it, phones with a cover and a playhead,
 * the circles of the people in it and the ring round the one in control, and what happens in it as it happens. Drawn
 * from the theme's colours like the (i) pictures (ExplainPictures), with no words in them, and still on a phone with
 * animations off.
 *
 * Each one loops with no seam: a loop ends on a change of song, the room's own, so what starts again is the next song
 * and never the picture. The first one tells how a room fills only once, and then plays on.
 */
enum class RoomTourScene { IN_STEP, CONTROL, ADD, OWN }

/** An ease with no corner at either end: nothing starts or stops with a jolt. */
internal fun tourEase(x: Float): Float {
    val within = x.coerceIn(0f, 1f)
    return within * within * (3f - 2f * within)
}

/** 0 until [start], 1 from [start] + [length] on, eased in between. */
internal fun tourStep(t: Float, start: Float, length: Float): Float = tourEase((t - start) / length)

/** How long a playhead takes to fade before a change of song, and to come back after it. */
internal const val DIP_OUT = 0.25f
internal const val DIP_IN = 0.3f

/**
 * How much a playhead shows [age] seconds into a song [length] long: it fades in at the start and out at the end, so
 * that it is never seen to jump from the end of its line back to the start when the song changes.
 */
internal fun songShown(age: Float, length: Float): Float =
    min(tourStep(age, 0f, DIP_IN), 1f - tourStep(age, length - DIP_OUT, DIP_OUT))

/** How long a new song's cover takes to come in over the one before. */
internal const val COVER_FADE = 0.45f

// The first picture: the same song at the same moment, and a room filling up.

/** How long a song lasts, in seconds. */
internal const val STEP_SONG = 5.6f

/** When the invite leaves the host for each of the two others. */
internal val STEP_INVITE = floatArrayOf(0.8f, 2.1f)

/** How long the invite takes to reach a phone. */
internal const val STEP_FLIGHT = 0.9f

/** How long a phone the invite reached takes to show the song and glide into step. */
internal const val STEP_ARRIVE = 0.7f

/** Where the host's playhead is at [t], from 0 to 1 of the song. */
internal fun stepHead(t: Float): Float = t.mod(STEP_SONG) / STEP_SONG

/** Which of two songs plays at [t]: they take turns, and every phone in the room changes at the same moment. */
internal fun stepSong(t: Float): Int = floor(t / STEP_SONG).toInt().mod(2)

/** How far the cover of the song playing at [t] has come in over the one before. */
internal fun stepCover(t: Float): Float = tourStep(t.mod(STEP_SONG), 0f, COVER_FADE)

/** How much of [guest]'s phone shows the room's song at [t]: nothing until the invite reaches it. */
internal fun stepLit(guest: Int, t: Float): Float = tourStep(t, STEP_INVITE[guest] + STEP_FLIGHT, STEP_ARRIVE)

/** Where [guest]'s playhead is at [t]: it glides from the start of its line to the host's, then goes with it. */
internal fun stepGuestHead(guest: Int, t: Float): Float = stepHead(t) * stepLit(guest, t)

/** How far the invite to [guest] has come at [t], 0 at the host and 1 at the phone, or null when it is not on its way. */
internal fun stepInvite(guest: Int, t: Float): Float? = ((t - STEP_INVITE[guest]) / STEP_FLIGHT).takeIf { it in 0f..1f }

// The second picture: one person chooses the music, and control goes to someone and comes back.

/** One loop: control given, a song chosen, control taken back, a song chosen. */
internal const val CONTROL_LOOP = 8.4f

/** When the host hands control over, and when it is taken back. */
internal const val CONTROL_GIVE = 1.2f
internal const val CONTROL_BACK = 5.4f

/** How long the ring takes to go from one person to the other. */
internal const val CONTROL_HOP = 1.0f

/** How long a song chosen takes to rise from its chooser to the cover. */
internal const val CONTROL_PICK = 0.7f

/** When the song the guest chose reaches the cover, and the one the host chose. */
internal const val CONTROL_GUEST_SONG = CONTROL_GIVE + CONTROL_HOP + CONTROL_PICK
internal const val CONTROL_HOST_SONG = CONTROL_BACK + CONTROL_HOP + CONTROL_PICK

/** How long a song is in this picture: longer than it ever plays, since someone chooses another first. */
internal const val CONTROL_SONG_LENGTH = 9f

/** Where the ring is at [t]: 0 round the host, 1 round the one the host gave control to. */
internal fun controlRing(t: Float): Float {
    val u = t.mod(CONTROL_LOOP)
    return tourStep(u, CONTROL_GIVE, CONTROL_HOP) - tourStep(u, CONTROL_BACK, CONTROL_HOP)
}

/** Whose song plays at [t], 0 the host's and 1 the guest's, and for how long it has played. */
internal fun controlSong(t: Float): Pair<Int, Float> {
    val u = t.mod(CONTROL_LOOP)
    return if (u >= CONTROL_GUEST_SONG && u < CONTROL_HOST_SONG) 1 to u - CONTROL_GUEST_SONG
    else 0 to (u - CONTROL_HOST_SONG).mod(CONTROL_LOOP)
}

/** How much the playhead shows at [t]: it fades before someone's song takes the place of the one playing, and back after. */
internal fun controlShown(t: Float): Float {
    val (whose, age) = controlSong(t)
    val guestPlays = CONTROL_HOST_SONG - CONTROL_GUEST_SONG
    return songShown(age, if (whose == 1) guestPlays else CONTROL_LOOP - guestPlays)
}

/**
 * The song on its way from whoever chose it to the cover at [t]: whose (0 the host, 1 the guest) and how far it has
 * come, 0 to 1, then past 1 for as long as the cover takes to change under it. Null while none is on its way.
 */
internal fun controlCard(t: Float): Pair<Int, Float>? {
    val u = t.mod(CONTROL_LOOP)
    val after = 1f + COVER_FADE / CONTROL_PICK
    val guest = (u - (CONTROL_GUEST_SONG - CONTROL_PICK)) / CONTROL_PICK
    if (guest in 0f..after) return 1 to guest
    val host = (u - (CONTROL_HOST_SONG - CONTROL_PICK)) / CONTROL_PICK
    if (host in 0f..after) return 0 to host
    return null
}

// The third picture: anyone adds a song, it goes in right after the one playing, and it plays next.

/** One loop: a song added, and the one playing ending, so that the one added plays. */
internal const val ADD_LOOP = 6.4f

/** When the song leaves the guest who adds it, and how long it takes to reach the queue. */
internal const val ADD_LEAVE = 0.7f
internal const val ADD_FLIGHT = 1.1f

/** How long the songs after the one playing take to move down and make room. */
internal const val ADD_MAKE_ROOM = 0.8f

/** When the song playing ends and the queue moves up one, and how long that takes. */
internal const val ADD_NEXT = 4.3f
internal const val ADD_SCROLL = 0.8f

/** When the song added starts to play: half way through the queue's move. */
internal const val ADD_STARTS = ADD_NEXT + ADD_SCROLL / 2

/** How far the songs after the one playing have moved down at [t], 0 to 1 row. */
internal fun addRoom(t: Float): Float = tourStep(t.mod(ADD_LOOP), ADD_LEAVE + 0.2f, ADD_MAKE_ROOM)

/** How far the whole queue has moved up at [t], 0 to 1 row, as the song playing ends. */
internal fun addScroll(t: Float): Float = tourStep(t.mod(ADD_LOOP), ADD_NEXT, ADD_SCROLL)

/** How far the song added has come at [t]: below 0 not yet gone, above 1 in the queue. */
internal fun addFlight(t: Float): Float = (t.mod(ADD_LOOP) - ADD_LEAVE) / ADD_FLIGHT

/**
 * The song playing as a loop at [t] begins: the one added in the loop before, so each loop ends where the next
 * begins. Two songs take turns: 0 and 1.
 */
internal fun addPlaying(t: Float): Int = floor(t / ADD_LOOP).toInt().mod(2)

/** The song added in the loop at [t]: the other one. */
internal fun addAdded(t: Float): Int = 1 - addPlaying(t)

/**
 * A song in the third picture's queue: which it is ([id], the same for as long as it is in the picture), where, in rows
 * from the top, how much of it shows, its cover (one of the songs, or null for a plain one), how much of its names
 * show, and its playhead, if it plays.
 */
internal data class QueueRow(val id: Int, val slot: Float, val alpha: Float, val song: Int?, val words: Float, val head: Float?, val shown: Float)

/** Each song added has its own id from this one up, by the loop it was added in. */
internal const val ADDED_ID = 1000

/**
 * The third picture's queue at [t]: the song playing, which leaves by the top as it ends; the three after it, which
 * move down to make room and back up, the last going out of the picture and coming back; and the song added, once it
 * has landed, which takes the place of the one playing. At the end of a loop each song is where the next loop has it.
 */
internal fun addRows(t: Float): List<QueueRow> {
    val loop = floor(t / ADD_LOOP).toInt()
    val u = t.mod(ADD_LOOP)
    val room = addRoom(t)
    val scroll = addScroll(t)
    val rows = mutableListOf(
        QueueRow(
            id = ADDED_ID + loop - 1, slot = -scroll, alpha = 1f - scroll, song = addPlaying(t), words = 1f,
            head = (u + ADD_LOOP - ADD_STARTS) / ADD_LOOP, shown = 1f - tourStep(u, ADD_STARTS - DIP_OUT, DIP_OUT),
        )
    )
    for (n in 1..3) {
        val alpha = if (n == 3) (1f - room + scroll).coerceIn(0f, 1f) else 1f
        rows += QueueRow(id = n, slot = n + room - scroll, alpha = alpha, song = null, words = 0.75f, head = null, shown = 0f)
    }
    if (addFlight(t) >= 1f) {
        rows += QueueRow(
            id = ADDED_ID + loop, slot = 1f - scroll, alpha = 1f, song = addAdded(t),
            words = tourStep(u, ADD_LEAVE + ADD_FLIGHT, 0.4f),
            head = ((u - ADD_STARTS) / ADD_LOOP).coerceAtLeast(0f), shown = tourStep(u, ADD_STARTS, DIP_IN),
        )
    }
    return rows
}

/** The light on a song just added, at [t]: on as it lands, and gone well before the queue moves. */
internal fun addGlow(t: Float): Float {
    val u = t.mod(ADD_LOOP)
    val landing = ADD_LEAVE + ADD_FLIGHT
    return 0.16f * (tourStep(u, landing - 0.2f, 0.3f) - tourStep(u, landing + 0.5f, 0.8f))
}

// The fourth picture: a pause just for you, your own song, and Catch up.

/** One loop: in step, paused here, in step again, on your own, Catch up, in step. */
internal const val OWN_LOOP = 9.6f

/** When you pause, play again, play another song, and catch up. */
internal const val OWN_PAUSE = 1.1f
internal const val OWN_PLAY = 2.7f
internal const val OWN_OTHER = 4.0f
internal const val OWN_CATCH = 6.3f

/** How long a song is: the room's ends with the loop, a little before its end, and the next begins. */
internal const val OWN_SONG = 10f

/** How long a paused playhead takes to come to rest, and a playing one to glide back to the room's. */
internal const val OWN_STOP = 0.25f
internal const val OWN_GLIDE = 0.6f
internal const val OWN_CATCH_GLIDE = 0.8f

/** Where the room's playhead is at [t]. */
internal fun ownRoomHead(t: Float): Float = t.mod(OWN_LOOP) / OWN_SONG

/** Which of two songs the room plays at [t]: a new one each loop. */
internal fun ownRoomSong(t: Float): Int = floor(t / OWN_LOOP).toInt().mod(2)

/** Where your playhead is at [t]: with the room's, at rest while paused, then your own song's, then the room's again. */
internal fun ownHead(t: Float): Float {
    val u = t.mod(OWN_LOOP)
    val room = u / OWN_SONG
    // Paused: it slows to a stop, from the room's pace to none, with no corner.
    fun paused(at: Float): Float {
        val x = ((at - OWN_PAUSE) / OWN_STOP).coerceIn(0f, 1f)
        return (OWN_PAUSE + OWN_STOP * (x - x * x / 2f)) / OWN_SONG
    }
    fun own(at: Float) = (at - OWN_OTHER) / OWN_SONG
    return when {
        u < OWN_PAUSE -> room
        u < OWN_PLAY -> paused(u)
        u < OWN_OTHER -> paused(OWN_PLAY) + (room - paused(OWN_PLAY)) * tourStep(u, OWN_PLAY, OWN_GLIDE)
        u < OWN_CATCH -> own(u)
        else -> own(u) + (room - own(u)) * tourStep(u, OWN_CATCH, OWN_CATCH_GLIDE)
    }
}

/** How much your phone plays its own song at [t], 0 the room's and 1 its own. */
internal fun ownSongMix(t: Float): Float {
    val u = t.mod(OWN_LOOP)
    return tourStep(u, OWN_OTHER, COVER_FADE) - tourStep(u, OWN_CATCH + 0.15f, COVER_FADE)
}

/** How much your phone is in step with the room at [t]: the waves between the two phones. */
internal fun ownLinked(t: Float): Float {
    val u = t.mod(OWN_LOOP)
    return 1f - tourStep(u, OWN_PAUSE, 0.4f) + tourStep(u, OWN_PLAY, 0.5f) - tourStep(u, OWN_OTHER, 0.4f) + tourStep(u, OWN_CATCH + 0.4f, 0.5f)
}

/** How far your phone has turned away from the room at [t], while it plays on its own. */
internal fun ownAway(t: Float): Float {
    val u = t.mod(OWN_LOOP)
    return tourStep(u, OWN_OTHER, 0.6f) - tourStep(u, OWN_CATCH, OWN_CATCH_GLIDE)
}

/** How much your playhead shows at [t] for your own song's sake: it fades as you jump to it, and back. */
internal fun ownJumpShown(t: Float): Float {
    val u = t.mod(OWN_LOOP)
    return if (u < OWN_OTHER) 1f - tourStep(u, OWN_OTHER - DIP_OUT, DIP_OUT) else tourStep(u, OWN_OTHER, DIP_IN)
}

/** How much your playhead shows at [t]: it fades over each jump, a change of the room's song or to your own. */
internal fun ownHeadShown(t: Float): Float = min(songShown(t.mod(OWN_LOOP), OWN_LOOP), ownJumpShown(t))

/** The pause sign over your cover at [t]. */
internal fun ownPauseSign(t: Float): Float {
    val u = t.mod(OWN_LOOP)
    return tourStep(u, OWN_PAUSE, 0.2f) * (1f - tourStep(u, OWN_PLAY - 0.05f, 0.25f))
}

/** The Catch up sign over your phone at [t]: how much it shows, and how far round it has turned, 0 to 1. */
internal fun ownCatchSign(t: Float): Pair<Float, Float> {
    val u = t.mod(OWN_LOOP)
    val shown = tourStep(u, OWN_CATCH - 0.15f, 0.2f) * (1f - tourStep(u, OWN_CATCH + 0.75f, 0.3f))
    return shown to tourStep(u, OWN_CATCH - 0.15f, 1.1f)
}

/**
 * The moment drawn on a phone with animations off, one where the picture says what its page says: everyone in step,
 * the host's ring, a song just added, a phone on its own.
 */
internal fun stillMoment(scene: RoomTourScene): Float = when (scene) {
    RoomTourScene.IN_STEP -> 4.3f
    RoomTourScene.CONTROL -> 0.6f
    RoomTourScene.ADD -> 2.6f
    RoomTourScene.OWN -> 5.4f
}

/** How tall a picture is upright; on a phone on its side it is given less. */
val RoomTourPictureHeight: Dp = 176.dp

/**
 * The picture of [scene], moving while [running] (the page is the one in view) and animations are on. It starts from
 * its beginning each time its page comes into view, and holds its last frame while it is out of it.
 */
@Composable
fun RoomTourPicture(scene: RoomTourScene, running: Boolean, modifier: Modifier = Modifier, height: Dp = RoomTourPictureHeight) {
    val pace = Animations.current.scaleFactor
    val moving = pace > 0f
    // Read where the picture is drawn and nowhere else: a new frame draws again and composes nothing.
    val seconds = remember { mutableFloatStateOf(0f) }
    if (moving && running) {
        LaunchedEffect(pace, scene) {
            val began = withFrameNanos { it }
            while (true) withFrameNanos { seconds.floatValue = (it - began) / 1e9f / pace }
        }
    } else if (moving) {
        // Out of view, back at its beginning: that is the frame it slides in with, and where it starts once in view.
        SideEffect { seconds.floatValue = 0f }
    }
    val scheme = MaterialTheme.colorScheme
    val ink = TourInk(
        body = scheme.surfaceContainerHigh,
        edge = scheme.outlineVariant,
        line = scheme.onSurfaceVariant.copy(alpha = 0.32f),
        track = scheme.outlineVariant,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        blank = scheme.surfaceContainerHighest,
        person = scheme.onSurfaceVariant.copy(alpha = 0.55f),
        ground = scheme.surfaceContainerLowest,
        songs = listOf(
            TourSong(scheme.primary, lerp(scheme.primary, scheme.primaryContainer, 0.55f)),
            TourSong(scheme.tertiary, lerp(scheme.tertiary, scheme.tertiaryContainer, 0.55f)),
        ),
        // The player's Pause and the room sheet's Catch up, as people see them there.
        // The mute a guest's play button is in a room (RoomListen, 26 Nov 2026), drawn where the Pause sign was.
        pause = rememberVectorPainter(Icons.AutoMirrored.Rounded.VolumeOff),
        catchUp = rememberVectorPainter(Icons.Rounded.Sync),
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(ink.ground)
            // A picture of what the words beside it say: a screen reader reads the words.
            .clearAndSetSemantics { }
    ) {
        val t = if (moving) seconds.floatValue else stillMoment(scene)
        when (scene) {
            RoomTourScene.IN_STEP -> inStep(t, ink)
            RoomTourScene.CONTROL -> control(t, ink)
            RoomTourScene.ADD -> adding(t, ink)
            RoomTourScene.OWN -> ownWay(t, ink)
        }
    }
}

/** The theme's colours, as the pictures use them. */
internal class TourInk(
    val body: Color,
    val edge: Color,
    val line: Color,
    val track: Color,
    val accent: Color,
    val onAccent: Color,
    val blank: Color,
    val person: Color,
    val ground: Color,
    val songs: List<TourSong>,
    val pause: Painter,
    val catchUp: Painter,
)

/** A song's cover: a colour of the theme, a little deeper at the foot. */
internal class TourSong(val top: Color, val foot: Color)

private fun mix(from: TourSong, to: TourSong, share: Float) =
    TourSong(lerp(from.top, to.top, share), lerp(from.foot, to.foot, share))

/** A phone's height for its width. */
private const val PHONE_TALL = 1.48f

/**
 * A phone as the room's sheet draws one: a cover, two lines of names, and a line with a playhead. [screen] is how much
 * of the song it shows, 0 for a phone not in the room yet; [shown] how much of the playhead shows.
 */
private fun DrawScope.phone(
    centre: Offset,
    width: Float,
    ink: TourInk,
    song: TourSong,
    screen: Float,
    head: Float,
    shown: Float,
    turn: Float = 0f,
    sign: (DrawScope.(cover: Offset, side: Float) -> Unit)? = null,
) = rotate(turn, centre) {
    val tall = width * PHONE_TALL
    val left = centre.x - width / 2f
    val top = centre.y - tall / 2f
    drawRoundRect(ink.body, Offset(left, top), Size(width, tall), CornerRadius(width * 0.2f))
    drawRoundRect(ink.edge, Offset(left, top), Size(width, tall), CornerRadius(width * 0.2f), style = Stroke(1.5.dp.toPx()))
    val side = width * 0.64f
    val coverTop = top + width * 0.18f
    val coverLeft = centre.x - side / 2f
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(lerp(ink.blank, song.top, screen), lerp(ink.blank, song.foot, screen)),
            startY = coverTop,
            endY = coverTop + side,
        ),
        topLeft = Offset(coverLeft, coverTop),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.14f),
    )
    val lineTall = width * 0.055f
    val first = coverTop + side + width * 0.12f
    val names = ink.line.copy(alpha = ink.line.alpha * (0.4f + 0.6f * screen))
    drawRoundRect(names, Offset(centre.x - width * 0.26f, first), Size(width * 0.52f, lineTall), CornerRadius(lineTall / 2f))
    drawRoundRect(names.copy(alpha = names.alpha * 0.7f), Offset(centre.x - width * 0.17f, first + lineTall * 2f), Size(width * 0.34f, lineTall), CornerRadius(lineTall / 2f))
    val y = first + lineTall * 3f + width * 0.13f
    val stroke = width * 0.035f
    drawLine(ink.track, Offset(coverLeft, y), Offset(coverLeft + side, y), strokeWidth = stroke, cap = StrokeCap.Round)
    val visible = shown * screen
    if (visible > 0f) {
        val x = coverLeft + side * head.coerceIn(0f, 1f)
        drawLine(ink.accent.copy(alpha = visible), Offset(coverLeft, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(ink.accent.copy(alpha = visible), radius = width * 0.055f, center = Offset(x, y))
    }
    sign?.invoke(this, Offset(coverLeft, coverTop), side)
}

/** Waves from [from] towards [to], as the sheet's own picture sends them from the host to the other phone. */
private fun DrawScope.waves(from: Offset, to: Offset, seconds: Float, color: Color, shown: Float) {
    if (shown <= 0f) return
    val gap = to.x - from.x
    val reach = kotlin.math.abs(gap)
    val facing = if (gap >= 0f) 0f else 180f
    for (number in 0 until RIPPLES) {
        val (far, strength) = ripple(number, seconds)
        val r = reach * (0.15f + 0.85f * far)
        drawArc(
            color = color.copy(alpha = 0.8f * strength * shown),
            startAngle = facing - 32f,
            sweepAngle = 64f,
            useCenter = false,
            topLeft = Offset(from.x - r, from.y - r),
            size = Size(2 * r, 2 * r),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Someone in the room, as a circle with a figure in it, the ring round the one in control drawn apart (ring). */
private fun DrawScope.person(centre: Offset, radius: Float, ink: TourInk, host: Boolean, fill: Color = ink.blank) {
    drawCircle(fill, radius, centre)
    val round = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, radius)) }
    clipPath(round) {
        drawCircle(ink.person, radius * 0.33f, Offset(centre.x, centre.y - radius * 0.2f))
        drawCircle(ink.person, radius * 0.6f, Offset(centre.x, centre.y + radius * 0.78f))
    }
    if (host) star(Offset(centre.x + radius * 0.74f, centre.y + radius * 0.74f), radius * 0.42f, ink)
}

/** The host's star, as the sheet pins it to the host's circle. */
private fun DrawScope.star(centre: Offset, radius: Float, ink: TourInk) {
    drawCircle(ink.ground, radius * 1.22f, centre)
    drawCircle(ink.accent, radius, centre)
    val points = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius * 0.66f else radius * 0.28f
        val a = -PI / 2 + i * PI / 5
        val p = Offset(centre.x + (r * cos(a)).toFloat(), centre.y + (r * sin(a)).toFloat())
        if (i == 0) points.moveTo(p.x, p.y) else points.lineTo(p.x, p.y)
    }
    points.close()
    drawPath(points, ink.onAccent)
}

/** The ring the sheet draws round the one in control. */
private fun DrawScope.ring(centre: Offset, radius: Float, ink: TourInk, alpha: Float = 1f) {
    drawCircle(ink.accent.copy(alpha = alpha), radius, centre, style = Stroke(width = 2.5.dp.toPx()))
}

/** [painter], one of the app's own icons, white, [side] across round [centre]. */
private fun DrawScope.icon(painter: Painter, centre: Offset, side: Float, alpha: Float) {
    translate(centre.x - side / 2f, centre.y - side / 2f) {
        with(painter) { draw(Size(side, side), alpha = alpha, colorFilter = ColorFilter.tint(Color.White)) }
    }
}

/** A point on the curve from [from] to [to] that bends through [via], at [p] of the way. */
private fun curve(from: Offset, via: Offset, to: Offset, p: Float): Offset {
    val q = 1f - p
    return Offset(q * q * from.x + 2 * q * p * via.x + p * p * to.x, q * q * from.y + 2 * q * p * via.y + p * p * to.y)
}

/** A cover on its own, as a song on its way somewhere. */
private fun DrawScope.cover(centre: Offset, side: Float, song: TourSong, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(song.top, song.foot), startY = centre.y - side / 2f, endY = centre.y + side / 2f),
        topLeft = Offset(centre.x - side / 2f, centre.y - side / 2f),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.14f),
        alpha = alpha,
    )
}

/**
 * The host's phone in the middle plays; its invite, the code's three groups, flies to the phone on the left, which
 * shows the same song and glides into step, then to the one on the right. From then on the three play as one, and
 * change song at the same moment.
 */
private fun DrawScope.inStep(t: Float, ink: TourInk) {
    val width = min(size.height * 0.46f, size.width * 0.19f)
    val middle = Offset(size.width / 2f, size.height / 2f)
    val apart = size.width * 0.31f
    val phones = listOf(middle.copy(x = middle.x - apart), middle.copy(x = middle.x + apart))
    val now = ink.songs[stepSong(t)]
    // Before the first change there is no song before: the first song is there from the start.
    val song = if (t < STEP_SONG) now else mix(ink.songs[1 - stepSong(t)], now, stepCover(t))
    val shown = if (t < STEP_SONG) 1f - tourStep(t.mod(STEP_SONG), STEP_SONG - DIP_OUT, DIP_OUT) else songShown(t.mod(STEP_SONG), STEP_SONG)
    val edge = width / 2f + 4.dp.toPx()
    for (guest in 0..1) {
        val to = phones[guest]
        val from = middle.copy(x = middle.x + (if (to.x < middle.x) -edge else edge))
        waves(from, to.copy(x = to.x + (if (to.x < middle.x) edge else -edge)), t, ink.accent, stepLit(guest, t))
    }
    for (guest in 0..1) {
        phone(phones[guest], width, ink, song, screen = stepLit(guest, t), head = stepGuestHead(guest, t), shown = shown)
    }
    phone(middle, width, ink, song, screen = 1f, head = stepHead(t), shown = shown)
    // The invite on its way: the code's three groups, out of the host's phone and down into the other's.
    for (guest in 0..1) {
        val p = stepInvite(guest, t) ?: continue
        val from = Offset(middle.x, middle.y - width * 0.55f)
        val to = Offset(phones[guest].x, phones[guest].y - width * 0.25f)
        val via = Offset((from.x + to.x) / 2f, size.height * 0.06f)
        val at = curve(from, via, to, tourEase(p))
        val alpha = min(tourStep(p, 0f, 0.15f), 1f - tourStep(p, 0.82f, 0.18f))
        val chip = width * 0.2f * (1f - 0.35f * tourStep(p, 0.6f, 0.4f))
        val tall = chip * 0.55f
        for (group in -1..1) {
            drawRoundRect(
                ink.accent.copy(alpha = alpha),
                Offset(at.x + group * chip * 1.18f - chip / 2f, at.y - tall / 2f),
                Size(chip, tall),
                CornerRadius(tall * 0.4f),
            )
        }
    }
}

/**
 * A song playing at the top, as the room's sheet shows it, and the people in the room under it: the host in the
 * middle with the star, and the ring round them. The ring goes to the guest on the right, whose song rises to the
 * cover; then back to the host, whose song does.
 */
private fun DrawScope.control(t: Float, ink: TourInk) {
    val h = size.height
    val w = size.width
    val radius = h * 0.105f
    val row = h * 0.75f
    val people = listOf(Offset(w / 2f - w * 0.24f, row), Offset(w / 2f, row), Offset(w / 2f + w * 0.24f, row))
    val host = people[1]
    val guest = people[2]

    // The song playing: a cover, its names and its line, on a card like the sheet's own.
    val card = Size(w * 0.6f, h * 0.36f)
    val cardTop = h * 0.09f
    val cardLeft = (w - card.width) / 2f
    drawRoundRect(ink.body, Offset(cardLeft, cardTop), card, CornerRadius(h * 0.08f))
    val pad = card.height * 0.16f
    val side = card.height - 2 * pad
    val coverAt = Offset(cardLeft + pad + side / 2f, cardTop + pad + side / 2f)
    val (whose, age) = controlSong(t)
    val before = ink.songs[if (whose == 0) 1 else 0]
    val playing = mix(before, ink.songs[if (whose == 0) 0 else 1], tourStep(age, 0f, COVER_FADE))
    cover(coverAt, side, playing)
    val textLeft = cardLeft + pad * 2f + side
    val textWidth = card.width - side - pad * 3f
    val lineTall = card.height * 0.085f
    drawRoundRect(ink.line, Offset(textLeft, cardTop + pad + side * 0.1f), Size(textWidth * 0.8f, lineTall), CornerRadius(lineTall / 2f))
    drawRoundRect(ink.line.copy(alpha = ink.line.alpha * 0.7f), Offset(textLeft, cardTop + pad + side * 0.1f + lineTall * 2f), Size(textWidth * 0.5f, lineTall), CornerRadius(lineTall / 2f))
    val y = cardTop + pad + side * 0.86f
    val stroke = card.height * 0.05f
    drawLine(ink.track, Offset(textLeft, y), Offset(textLeft + textWidth, y), strokeWidth = stroke, cap = StrokeCap.Round)
    val shown = controlShown(t)
    val x = textLeft + textWidth * (age / CONTROL_SONG_LENGTH)
    drawLine(ink.accent.copy(alpha = shown), Offset(textLeft, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
    drawCircle(ink.accent.copy(alpha = shown), radius = stroke * 1.4f, center = Offset(x, y))

    for (someone in people) person(someone, radius, ink, host = someone == host)

    // The ring, from the host to the guest and back, over a small rise.
    val p = controlRing(t)
    val lift = sin(PI * p).toFloat() * h * 0.12f
    val ringAt = Offset(host.x + (guest.x - host.x) * p, row - lift)
    ring(ringAt, radius + 5.dp.toPx(), ink)

    // The song chosen, rising from its chooser to the cover, and fading into it as the cover changes.
    controlCard(t)?.let { (who, q) ->
        val from = if (who == 0) host else guest
        val via = Offset((from.x + coverAt.x) / 2f, h * 0.55f)
        val at = curve(from, via, coverAt, tourEase(q.coerceAtMost(1f)))
        val grown = side * (0.45f + 0.55f * tourEase(q.coerceAtMost(1f)))
        val alpha = if (q <= 1f) tourStep(q, 0f, 0.2f) else 1f - tourEase((q - 1f) * CONTROL_PICK / COVER_FADE)
        cover(at, grown, ink.songs[who], alpha)
    }
}

/**
 * The room's queue, the song playing first on its own light, and a guest on the right. The guest's song flies into
 * the queue right after the one playing, the others move down to make room, and when the one playing ends the queue
 * moves up: the song added plays.
 */
private fun DrawScope.adding(t: Float, ink: TourInk) {
    val h = size.height
    val w = size.width
    val rowTall = h * 0.2f
    val top = h * 0.1f
    val left = w * 0.08f
    val rowWide = w * 0.58f
    val side = rowTall * 0.74f
    val pad = (rowTall - side) / 2f
    val added = ink.songs[addAdded(t)]

    // The light of the song playing stays where it is: the songs move under it.
    drawRoundRect(ink.body, Offset(left, top), Size(rowWide, rowTall), CornerRadius(rowTall * 0.3f))
    // The song just added, lit a moment as it lands, right after the one playing.
    val glow = addGlow(t)
    if (glow > 0f) drawRoundRect(ink.accent.copy(alpha = glow), Offset(left, top + rowTall), Size(rowWide, rowTall), CornerRadius(rowTall * 0.3f))

    for (row in addRows(t)) {
        if (row.alpha <= 0f) continue
        val y = top + row.slot * rowTall
        val coverAt = Offset(left + pad + side / 2f, y + rowTall / 2f)
        if (row.song != null) cover(coverAt, side, ink.songs[row.song], row.alpha)
        else drawRoundRect(ink.blank.copy(alpha = ink.blank.alpha * row.alpha), Offset(coverAt.x - side / 2f, coverAt.y - side / 2f), Size(side, side), CornerRadius(side * 0.14f))
        val textLeft = left + pad * 2f + side + rowTall * 0.12f
        val lineTall = rowTall * 0.11f
        val names = ink.line.copy(alpha = ink.line.alpha * row.alpha * row.words)
        drawRoundRect(names, Offset(textLeft, y + rowTall * 0.3f), Size(rowWide * 0.42f, lineTall), CornerRadius(lineTall / 2f))
        drawRoundRect(names.copy(alpha = names.alpha * 0.7f), Offset(textLeft, y + rowTall * 0.3f + lineTall * 2f), Size(rowWide * 0.27f, lineTall), CornerRadius(lineTall / 2f))
        val head = row.head
        if (head != null && row.shown > 0f) {
            val lineY = y + rowTall * 0.82f
            val length = rowWide * 0.5f
            val stroke = rowTall * 0.06f
            drawLine(ink.track.copy(alpha = ink.track.alpha * row.alpha), Offset(textLeft, lineY), Offset(textLeft + length, lineY), strokeWidth = stroke, cap = StrokeCap.Round)
            val x = textLeft + length * head.coerceIn(0f, 1f)
            drawLine(ink.accent.copy(alpha = row.alpha * row.shown), Offset(textLeft, lineY), Offset(x, lineY), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(ink.accent.copy(alpha = row.alpha * row.shown), radius = stroke * 1.3f, center = Offset(x, lineY))
        }
    }

    // The guest who adds it.
    val radius = h * 0.11f
    val guest = Offset(w * 0.85f, h * 0.52f)
    person(guest, radius, ink, host = false)
    // The song on its way: out of the guest, over the queue, into its place.
    val flight = addFlight(t)
    if (flight in 0f..1f) {
        val to = Offset(left + pad + side / 2f, top + rowTall * 1.5f)
        val via = Offset(w * 0.62f, h * 0.02f)
        val p = tourEase(flight)
        val at = curve(guest, via, to, p)
        cover(at, side * (0.5f + 0.5f * p), added, tourStep(flight, 0f, 0.15f))
    }
}

/**
 * The room's phone on the left, yours on the right, in step with waves between them, on the same song at the same place
 * all along (his word of 26 Nov 2026, Spotify's Jam: guests listen, and nobody goes their own way in a room). Where you
 * paused before, you mute now: the mute sign comes over your cover and goes, your playhead going on with the room's.
 */
private fun DrawScope.ownWay(t: Float, ink: TourInk) {
    val width = min(size.height * 0.46f, size.width * 0.2f)
    val middle = Offset(size.width / 2f, size.height / 2f)
    val apart = size.width * 0.2f
    val roomAt = middle.copy(x = middle.x - apart)
    val yoursAt = middle.copy(x = middle.x + apart)
    val u = t.mod(OWN_LOOP)
    val now = ink.songs[ownRoomSong(t)]
    val roomSong = if (t < OWN_LOOP) now else mix(ink.songs[1 - ownRoomSong(t)], now, tourStep(u, 0f, COVER_FADE))
    // Before the first change there is no song before: the first is there from the start, its playhead too.
    val roomShown = if (t < OWN_LOOP) 1f - tourStep(u, OWN_LOOP - DIP_OUT, DIP_OUT) else songShown(u, OWN_LOOP)
    val edge = width / 2f + 4.dp.toPx()
    waves(roomAt.copy(x = roomAt.x + edge), yoursAt.copy(x = yoursAt.x - edge), t, ink.accent, 1f)
    phone(roomAt, width, ink, roomSong, screen = 1f, head = ownRoomHead(t), shown = roomShown)
    val mute = ownPauseSign(t)
    phone(
        yoursAt, width, ink, roomSong, screen = 1f, head = ownRoomHead(t), shown = roomShown,
        sign = { coverAt, side ->
            val centre = Offset(coverAt.x + side / 2f, coverAt.y + side / 2f)
            // The player's own Mute, over the cover, where Pause was.
            if (mute > 0f) {
                drawCircle(Color.Black.copy(alpha = 0.3f * mute), side * 0.32f, centre)
                icon(ink.pause, centre, side * 0.4f, mute)
            }
        },
    )
}
