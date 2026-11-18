/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackParameters
import kotlin.math.pow

/**
 * Varispeed (Unreleased.VARISPEED, #225): the pitch follows the speed, as on a record player or a tape machine.
 *
 * With the speed and the pitch the same, media3's Sonic stretches nothing (its time stretch runs at speed / pitch,
 * which is 1) and only resamples, which is exactly what a turntable does: no stretching sound at any speed.
 */
object Varispeed {

    /** What Tempo and Pitch asks the player for: the pitch from its semitones, or the speed itself when linked. */
    fun parameters(speed: Float, semitones: Int, linked: Boolean): PlaybackParameters =
        PlaybackParameters(speed, if (linked) speed else 2f.pow(semitones / 12f))

    /**
     * What the player gets when the switch turns. On: the pitch follows the speed at once. Off: a pitch that
     * followed the speed goes back to the song's own key, as a DJ app's key lock does by default (Mixxx's
     * "Lock original key"), and a pitch set by hand in Tempo and Pitch stays.
     */
    fun switched(now: PlaybackParameters, linked: Boolean): PlaybackParameters = when {
        linked -> PlaybackParameters(now.speed, now.speed)
        now.pitch == now.speed -> PlaybackParameters(now.speed, 1f)
        else -> now
    }
}
