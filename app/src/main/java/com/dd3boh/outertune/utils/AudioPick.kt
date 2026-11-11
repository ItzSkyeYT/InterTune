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
 * drops on mobile data would be lying about what it does. Auto takes what they take on a connection
 * that is not metered. Low takes the smallest stream, with the old 10 kbps nudge towards Opus between
 * streams of about the same size.
 *
 * Auto on metered data takes a middle stream (#88, 8 Nov 2026), where it took the smallest. With
 * two or more Opus streams on offer, the middle one of those, the lower one when two share the
 * middle: of the five streams YouTube sends for a song, 139, 140, 249, 250 and 251, that is Opus
 * 250 at about 75 kbps in place of 249 at about 55, clearly better for about a third more data.
 * Without 250 it is 249, and with a Premium account's 141 and 774 on offer as well, still 250,
 * where the middle of all seven would be AAC 140 at about 130 kbps. With fewer Opus streams, the
 * middle of them all as the higher tiers rank them, but never one worth less than the one Low
 * takes. Of two streams it takes the one Low takes, and of one, that one.
 */
internal object AudioPick {
    const val OPUS_WORTH = 1.5
    private const val OPUS_NUDGE = 10240

    private val PlayerResponse.StreamingData.Format.isOpus: Boolean
        get() = mimeType.startsWith("audio/webm")

    /** What a stream is worth to the ear, in bits per second of AAC. */
    fun worth(format: PlayerResponse.StreamingData.Format): Double =
        if (format.isOpus) format.bitrate * OPUS_WORTH else format.bitrate.toDouble()

    /** The higher tiers' order: what a stream is worth, then its sample rate. */
    private val byWorth = compareBy<PlayerResponse.StreamingData.Format> { worth(it) }.thenBy { it.audioSampleRate ?: 0 }

    fun choose(
        formats: List<PlayerResponse.StreamingData.Format>,
        quality: AudioQuality,
        metered: Boolean,
    ): PlayerResponse.StreamingData.Format? = when {
        quality == AudioQuality.LOW -> smallest(formats)
        quality == AudioQuality.AUTO && metered -> middle(formats)
        else -> formats.maxWithOrNull(byWorth)
    }

    private fun smallest(formats: List<PlayerResponse.StreamingData.Format>) =
        formats.maxByOrNull { -it.bitrate + if (it.isOpus) OPUS_NUDGE else 0 }

    private fun middle(formats: List<PlayerResponse.StreamingData.Format>): PlayerResponse.StreamingData.Format? {
        val opus = formats.filter { it.isOpus }
        if (opus.size >= 2) return lowerMiddle(opus)
        return listOfNotNull(lowerMiddle(formats), smallest(formats)).maxWithOrNull(byWorth)
    }

    /** The middle one as [byWorth] ranks them, the lower one when two share the middle. */
    private fun lowerMiddle(formats: List<PlayerResponse.StreamingData.Format>) =
        formats.sortedWith(byWorth).let { it.getOrNull((it.size - 1) / 2) }
}
