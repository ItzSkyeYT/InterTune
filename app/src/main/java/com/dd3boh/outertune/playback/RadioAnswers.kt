/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.os.SystemClock
import android.util.Log
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.utils.NetworkPace
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.pages.NextResult

/**
 * The radio ladder the app asks through (Unreleased.RADIO_FALLBACK), with YouTube for its wire:
 * a radio's start and its next pages (YouTubeQueue), and so the radio's top-ups and the adaptive
 * queue's pages too. One for the app, so what one radio learned of a refusal the next one uses.
 */
internal object RadioAnswers {
    private const val TAG = "RadioAnswers"

    private val ladder = RadioLadder(
        object : RadioLadder.Wire {
            override suspend fun music(endpoint: WatchEndpoint, continuation: String?): Result<NextResult> =
                YouTube.next(endpoint, continuation)

            override suspend fun musicAsVisitor(endpoint: WatchEndpoint, visitorData: String?): Result<NextResult> =
                YouTube.nextAsVisitor(endpoint, visitorData)

            override suspend fun issuedVisitorData(videoId: String): String? =
                YouTube.issuedVisitorData(videoId).onFailure { Log.d(TAG, "No visitorData issued: ${RadioLadder.describe(it)}") }.getOrNull()

            override suspend fun mix(videoId: String, rung: RadioLadder.Rung, visitorData: String?): Result<NextResult> =
                YouTube.mix(
                    videoId,
                    if (rung == RadioLadder.Rung.WEB_MIX) YouTubeClient.WEB else YouTubeClient.ANDROID_VR_NO_AUTH,
                    visitorData ?: YouTube.visitorData,
                )

            override val mixesAllowed: Boolean
                get() = ContentFilter.current.let { !it.explicit && !it.videoSongs }

            // A YouTube Music answer with its automix is two requests of about half a megabyte.
            override val capMs: Long
                get() = if (NetworkPace.isSlow()) 25_000L else 12_000L
        },
        clock = { SystemClock.elapsedRealtime() },
    )

    /** See [RadioLadder.radio]. The walk is written to the log when it took more than one step. */
    suspend fun radio(endpoint: WatchEndpoint, continuation: String? = null): RadioLadder.Answer =
        ladder.radio(endpoint, continuation).also { answer ->
            if (answer.trail.size > 1 || answer.from != RadioLadder.Rung.MUSIC) {
                Log.i(TAG, "[${endpoint.videoId ?: endpoint.playlistId}] radio: ${answer.trail.joinToString(", ")}")
            }
        }

    /** Another network or another sign-in. */
    fun forget() = ladder.forget()
}
