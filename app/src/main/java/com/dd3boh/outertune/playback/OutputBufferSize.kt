/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/**
 * How much music the output holds in front of the speaker: [normalUs] as a floor, or [shortUs]
 * while [short] says so, asked each time the output is set up for a stream (a new player, a song
 * in another format, the rebuild after a change of spatial audio). Asked on the player's thread.
 *
 * The short buffer only buys anything while the soundstage follows a head: it cuts the lag between
 * a head turn and the sound turning with it, and costs headroom against underruns and wakes the
 * player about three times as often. It was used whenever the head tracking switch was on, read
 * once when the service started, so a listener who left the switch on with Spatial audio off, or
 * with no tracker in reach, paid for it and got nothing.
 */
@OptIn(UnstableApi::class)
class OutputBufferSize(
    normalUs: Int,
    shortUs: Int,
    private val short: () -> Boolean,
) : DefaultAudioSink.AudioTrackBufferSizeProvider {
    private val normal = DefaultAudioTrackBufferSizeProvider.Builder().setMinPcmBufferDurationUs(normalUs).build()
    private val low = DefaultAudioTrackBufferSizeProvider.Builder().setMinPcmBufferDurationUs(shortUs).build()

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int = (if (short()) low else normal).getBufferSizeInBytes(
        minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed,
    )

    companion object {
        /**
         * Head tracking will follow the listener as the song plays: they asked for it, Spatial
         * audio is on Headphones, and a tracker is connected. The tracker is asked last, and only
         * then, since asking it reaches for the sensors.
         */
        fun wantShort(trackingWanted: Boolean, headphones: Boolean, trackerConnected: () -> Boolean): Boolean =
            trackingWanted && headphones && trackerConnected()
    }
}
