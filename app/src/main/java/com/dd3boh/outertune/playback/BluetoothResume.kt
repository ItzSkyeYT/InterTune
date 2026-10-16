/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.media3.common.Player

/**
 * Resume when Bluetooth connects, under Settings > Player and audio: music that stopped because
 * the headphones, the car or the speaker it played through went away starts again when Bluetooth
 * audio connects, if that is soon enough after. This is the whole decision, with no Android in it
 * but the player's numbers and the audio device types, so that it is tested. MusicService hands it
 * what the player and the audio manager report, from the main thread, and does what it says.
 *
 * What it must never do is start music somebody stopped. A pause looks the same to the player
 * whoever made it, so what is kept is why the music stopped last, and only two stops count:
 *
 * - The player pausing as the sound was about to come out of the phone instead
 *   (PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) while it played. That is what a
 *   Bluetooth output going away does to the music playing through it, and a wired one too.
 * - A pause sent by the device itself as it went: a car sends one as it is switched off, and some
 *   headphones do as they power down, a moment before the link drops. A pause the output follows
 *   within [GOING_MS] is taken for that. Two seconds is a guess, not a measurement: long enough for
 *   a device that pauses and then lets go, short enough that somebody pressing pause and then
 *   holding the headphones' power button keeps the music paused.
 *
 * The becoming noisy reason alone is not enough. media3 1.8.0 reports it again for music that was
 * already paused, because the reason changes (ExoPlayerImpl.updatePlayWhenReady), so a listener
 * who paused and then switched the headphones off would have had the music started for them. So
 * the music has to have been playing. Anything else that stops or starts it forgets the stop.
 */
class BluetoothResume {
    /** playWhenReady as the player last reported it. */
    private var playing = false

    /** When a USER_REQUEST pause last stopped music that was playing: maybe the device's own. */
    private var pausedAt: Long? = null

    /** When the music stopped because its output went away, or null when it did not. */
    var outputLostAt: Long? = null
        private set

    /** The Bluetooth outputs for music the phone has now, by the audio manager's id for each. */
    private val outputs = HashSet<Int>()

    /** Whether any Bluetooth output for music is connected now, as far as the watch knows. */
    val anyOutput: Boolean get() = outputs.isNotEmpty()

    /**
     * What the player reported, at [now] on the clock the other calls are given
     * (SystemClock.elapsedRealtime, which goes on counting while the phone sleeps).
     */
    fun playWhenReadyChanged(playWhenReady: Boolean, reason: Int, now: Long) {
        val wasPlaying = playing
        playing = playWhenReady
        when {
            playWhenReady -> {
                outputLostAt = null
                pausedAt = null
            }

            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                // Paused already, the stop was somebody's, or the device's own, which
                // outputsRemoved has kept: either way this one is not news.
                if (wasPlaying) outputLostAt = now
            }

            else -> {
                outputLostAt = null
                pausedAt = if (wasPlaying && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) now else null
            }
        }
    }

    /**
     * The outputs the audio manager had when the watch began. They are not arrivals: it hands every
     * device it has to a callback as the callback is registered.
     */
    fun outputsThere(ids: Collection<Int>) {
        outputs.clear()
        outputs.addAll(ids)
    }

    /** Outputs reported connected. True when one of them was not there before, which is an arrival. */
    fun outputsAdded(ids: Collection<Int>): Boolean = ids.count { outputs.add(it) } > 0

    /** Outputs reported gone, at [now]. A pause just before is then the device's own, as it went. */
    fun outputsRemoved(ids: Collection<Int>, now: Long) {
        if (ids.count { outputs.remove(it) } == 0) return
        val paused = pausedAt ?: return
        if (!playing && now - paused in 0..GOING_MS) outputLostAt = paused
    }

    /**
     * Whether to start the music again now that a Bluetooth output has connected: the setting is
     * on, the music stopped because its output went away no longer than [RESUME_WITHIN_MS] ago,
     * and the player still holds a song it is ready to play. A player that was stopped since, or
     * emptied, is left as it is, and music already playing is too.
     */
    fun shouldResume(enabled: Boolean, now: Long, playWhenReady: Boolean, playbackState: Int, hasSong: Boolean): Boolean {
        val lost = outputLostAt ?: return false
        return enabled && !playWhenReady && hasSong &&
            (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) &&
            now - lost in 0..RESUME_WITHIN_MS
    }

    companion object {
        /**
         * Half an hour, from the stop. In practice it can be less: media3 keeps a paused service in
         * the foreground for ten minutes (MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS),
         * and after that Android may stop a service whose app is out of sight, and with it the
         * watch. Nothing is started by a service that is not running.
         */
        const val RESUME_WITHIN_MS = 30L * 60 * 1000

        /** How soon after a pause its output may go for the pause to be the device's own. */
        const val GOING_MS = 2_000L

        /**
         * How long after the output connects the music starts: the route to it is new, and a car
         * often sends a play or a pause of its own as it connects. Checked again when it is up.
         */
        const val SETTLE_MS = 1_500L

        /**
         * Whether an audio device is a Bluetooth output music plays through: classic A2DP, and LE
         * Audio's headsets and speakers from Android 12, where those types begin. Not SCO, the
         * link for calls: a headset connects it beside A2DP, and a car that offers calls alone
         * would have the music started out of the phone's own speaker. Not the microphones that
         * come with a headset either, which the audio manager reports among its devices too.
         */
        // TYPE_BLE_HEADSET and TYPE_BLE_SPEAKER are compile-time constants that are only compared,
        // so reading them below S does no harm.
        @SuppressLint("InlinedApi")
        fun isMusicOutput(type: Int, isSink: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
            isSink && when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> sdkInt >= Build.VERSION_CODES.S
                else -> false
            }
    }
}
