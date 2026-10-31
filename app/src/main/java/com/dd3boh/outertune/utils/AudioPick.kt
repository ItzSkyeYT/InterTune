/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.models.response.PlayerResponse

/**
 * Which of the audio streams YouTube offers each quality tier takes.
 *
 * Opus (audio/webm) sounds as good as AAC (audio/mp4) at about two thirds of the bits, so the higher
 * tiers count an Opus stream at [OPUS_WORTH] times its size. Before, Opus had a head start of 10 kbps
 * at High and none at Highest, so Highest took the AAC stream whenever it was the larger by a few
 * bits (both were 148 kbps on Bohemian Rhapsody, 28 Oct 2026), and High did on quiet songs, where
 * Opus needs fewer bits (#57). The weight still lets Highest take a Premium account's 256 kbps AAC
 * over the 160 kbps Opus.
 *
 * Highest and High ignore whether the connection is metered: a tier called highest that quietly
 * drops on mobile data would be lying about what it does. Low, and Auto on metered data, take the
 * smallest stream, with the old 10 kbps nudge towards Opus between streams of about the same size.
 */
internal object AudioPick {
    const val OPUS_WORTH = 1.5
    private const val OPUS_NUDGE = 10240

    private val PlayerResponse.StreamingData.Format.isOpus: Boolean
        get() = mimeType.startsWith("audio/webm")

    /** What a stream is worth to the ear, in bits per second of AAC. */
    fun worth(format: PlayerResponse.StreamingData.Format): Double =
        if (format.isOpus) format.bitrate * OPUS_WORTH else format.bitrate.toDouble()

    fun choose(
        formats: List<PlayerResponse.StreamingData.Format>,
        quality: AudioQuality,
        metered: Boolean,
    ): PlayerResponse.StreamingData.Format? {
        val smallest = quality == AudioQuality.LOW || (quality == AudioQuality.AUTO && metered)
        if (smallest) return formats.maxByOrNull { -it.bitrate + if (it.isOpus) OPUS_NUDGE else 0 }
        return formats.maxWithOrNull(
            compareBy<PlayerResponse.StreamingData.Format> { worth(it) }.thenBy { it.audioSampleRate ?: 0 }
        )
    }
}
