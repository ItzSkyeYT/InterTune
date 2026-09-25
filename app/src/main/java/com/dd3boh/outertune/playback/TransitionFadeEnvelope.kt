/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The volume curve for fading between songs, and the rules for when there is a fade at all. No
 * Android in here, so it is tested; [TransitionFade] feeds it from the player.
 *
 * The curve is the smaller of a fade in measured from the start and a fade out measured from the
 * end, so a song plays at exactly full volume everywhere except within one fade length of an end
 * that fades. Being a function of the position and nothing else is what makes it safe: a pause, a
 * seek or a skip cannot leave the volume low, because the next reading starts again from wherever
 * the song now is.
 */
object TransitionFadeEnvelope {

    /** The lengths offered in the settings, in seconds. */
    val SECONDS_CHOICES = listOf(2, 4, 6, 8, 10, 12)

    /** Long enough to be heard as a fade, short enough not to eat the end of every song. */
    const val DEFAULT_SECONDS = 6

    /** How often the factor moves while a fade runs. The sleep timer's rate, about 25 steps a second. */
    const val TICK_MS = 40L

    /**
     * The factor moves in steps of 1/256 rather than by whatever each tick works out.
     *
     * Every distinct volume set on the player goes out to each connected controller as a fresh
     * copy of the player's state, so a value that differs from the last in the sixth decimal place
     * is a broadcast for nothing. Rounded, the long flat stretch at the top of the curve sets the
     * volume once instead of on every tick, and one step only grows to a decibel at about -30 dB,
     * where the song has all but gone.
     */
    const val STEPS = 256

    /**
     * How far into a song an automatic change may land and still count as its start.
     *
     * An automatic change starts the next song at its beginning, and media3 reports exactly that
     * position with the change, so in practice this is always zero. The slack is only there so that
     * a song given a start point of its own is judged by whether it starts near the top, not by
     * whether it starts on the exact millisecond.
     */
    const val START_SLACK_MS = 1_000L

    /** The two things about a song that decide whether it fades into its neighbour. */
    data class Track(val id: String, val albumId: String?)

    /** The stored setting in milliseconds, kept inside the range the settings offer. */
    fun fadeMsFor(seconds: Int): Long =
        seconds.coerceIn(SECONDS_CHOICES.first(), SECONDS_CHOICES.last()) * 1000L

    /**
     * How long each fade actually lasts in a song [durationMs] long: the chosen length, but never
     * more than half the song, so a short interlude still reaches full volume between its two fades
     * instead of peaking somewhere below it. Zero when the length is not known yet, because a song
     * with no known end has nothing to fade towards, so it plays at full volume.
     */
    fun lengthMs(durationMs: Long, fadeMs: Long): Long =
        if (durationMs <= 0L || fadeMs <= 0L) 0L else min(fadeMs, durationMs / 2)

    /**
     * The volume factor at [positionMs] into a song [durationMs] long. [fadeIn] and [fadeOut] say
     * which ends fade; with neither, or with the length unknown, it is exactly 1.
     */
    fun factor(
        positionMs: Long,
        durationMs: Long,
        fadeMs: Long,
        fadeIn: Boolean,
        fadeOut: Boolean,
    ): Float {
        val length = lengthMs(durationMs, fadeMs)
        if (length <= 0L || (!fadeIn && !fadeOut)) return 1f
        val position = positionMs.coerceIn(0L, durationMs)
        var gain = 1f
        if (fadeIn) gain = min(gain, quarterSine(position, length))
        if (fadeOut) gain = min(gain, quarterSine(durationMs - position, length))
        return quantise(gain)
    }

    /**
     * How long the factor can be left before it might change by itself.
     *
     * A tick while a fade is running. Otherwise the time until the fade out begins, divided by the
     * playback speed, because the song's own clock runs that much faster than the wall clock. Null
     * when nothing will change until the player reports something, which it does for every seek,
     * queue change, new song, speed change, pause and play; that is what makes one long wait
     * before a fade out safe to take instead of polling all the way through the song.
     */
    fun nextCheckMs(
        positionMs: Long,
        durationMs: Long,
        fadeMs: Long,
        fadeIn: Boolean,
        fadeOut: Boolean,
        speed: Float,
    ): Long? {
        val length = lengthMs(durationMs, fadeMs)
        if (length <= 0L) return null
        val position = positionMs.coerceIn(0L, durationMs)
        if (fadeIn && position < length) return TICK_MS
        if (!fadeOut) return null
        val untilFadeOut = durationMs - position - length
        if (untilFadeOut <= 0L) return TICK_MS
        val wallMs = if (speed > 0f) (untilFadeOut / speed).toLong() else untilFadeOut
        return wallMs.coerceAtLeast(TICK_MS)
    }

    /**
     * Whether there is a fade between one song and the song after it.
     *
     * Not between two songs of one album. An album that runs straight from one track into the next
     * is the one place a fade does real harm, punching a hole in something made to be continuous,
     * so those play through exactly as they would with this off. Not into the same song again
     * either, which is what repeat one plays, and not into nothing, since the end of the queue is
     * not a change between two songs.
     */
    fun fadesBetween(from: Track?, to: Track?): Boolean {
        if (from == null || to == null) return false
        if (from.id == to.id) return false
        val album = from.albumId?.takeIf { it.isNotBlank() } ?: return true
        return album != to.albumId
    }

    /**
     * Whether a song that has just become current fades in.
     *
     * Only when the last song played straight into it, from its start, and there is a fade between
     * the two at all. The caller decides the first part, since only an automatic change counts: a
     * song skipped to, picked by hand, resumed or seeked into has not just come out of a fade, so
     * fading it in would only make it start quietly for no reason.
     */
    fun fadesIn(from: Track?, to: Track?, startPositionMs: Long): Boolean =
        startPositionMs in 0L..START_SLACK_MS && fadesBetween(from, to)

    /**
     * A quarter sine from silence at the edge to full volume [lengthMs] away from it.
     *
     * The equal power curve a crossfade uses, rather than a straight line. It holds the level up
     * longer and falls late, so the quiet stretch where one song hands over to the next is
     * shorter: halfway through a fade it is 3 dB down where a straight line is already 6. The fade
     * out is its mirror image, and the two squared always add up to one.
     */
    internal fun quarterSine(distanceMs: Long, lengthMs: Long): Float {
        if (distanceMs >= lengthMs) return 1f
        if (distanceMs <= 0L) return 0f
        return sin(PI / 2 * distanceMs / lengthMs).toFloat()
    }

    internal fun quantise(gain: Float): Float =
        (gain * STEPS).roundToInt().coerceIn(0, STEPS) / STEPS.toFloat()
}
