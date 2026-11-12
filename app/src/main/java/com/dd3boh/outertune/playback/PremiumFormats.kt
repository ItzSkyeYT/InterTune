/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.zionhuang.innertube.models.response.PlayerResponse

/**
 * Which audio streams an account is offered, for the first step of "Premium accounts get the 256 kbps
 * stream" (0.13 roadmap; experiments/research/opentune/DESIGN-2026-10-21.md, step 0): nobody here has
 * Premium, and which client offers a Premium account the 256 kbps streams is the one fact the rest
 * depends on. Nothing about the song or the library is in its lines.
 *
 * No Android in here, so it is tested.
 */
object PremiumFormats {

    /** The audio streams YouTube keeps for Premium: Opus and AAC at 256 kbps. */
    val PREMIUM_ITAGS = setOf(774, 141)

    data class Stream(val itag: Int, val codec: String, val kbps: Int)

    /** The audio streams in an answer's formats, each once, by itag. */
    fun audioOf(formats: List<PlayerResponse.StreamingData.Format>?): List<Stream> = formats.orEmpty()
        .filter { it.mimeType.startsWith("audio/") }
        .map { Stream(it.itag, codecOf(it.mimeType), ((it.averageBitrate ?: it.bitrate) + 500) / 1000) }
        .distinctBy { it.itag }
        .sortedBy { it.itag }

    private fun codecOf(mimeType: String): String = when {
        "opus" in mimeType -> "Opus"
        "mp4a" in mimeType -> "AAC"
        else -> mimeType.substringAfter('/').substringBefore(';')
    }

    /** The log's line after a counted play: the Premium streams the account was offered, or none. */
    fun offeredLine(formats: List<PlayerResponse.StreamingData.Format>?): String {
        val premium = audioOf(formats).filter { it.itag in PREMIUM_ITAGS }
        return "Premium formats offered: " + if (premium.isEmpty()) "none" else premium.joinToString { "${it.itag} (${it.codec} ${it.kbps} kbps)" }
    }

    /** The check's line for one client: every audio stream it offered, with its rate. */
    fun streamsLine(client: String, formats: List<PlayerResponse.StreamingData.Format>?): String {
        val streams = audioOf(formats)
        return "$client (account): " + if (streams.isEmpty()) "no audio streams" else streams.joinToString { "${it.itag} ${it.codec} ${it.kbps}k" }
    }
}
