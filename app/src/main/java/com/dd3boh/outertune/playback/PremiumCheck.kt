/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Check Premium formats" in developer options, in debug builds: asks /player as the account for the
 * song playing, or HistoryAddressCheck.FIXED_SONG when nothing from YouTube plays, the request a play
 * already makes for its history address, and lists the audio streams that came back with their rates
 * (PremiumFormats). A tester with Premium taps it and sends the lines: 774 there, the 256 kbps copy can
 * be built; nothing, it is parked with the reason written down. Signed out it asks nothing.
 */
object PremiumCheck {
    private const val TAG = "PremiumCheck"

    suspend fun run(videoId: String, nowPlaying: Boolean): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        fun say(line: String) {
            Log.i(TAG, line)
            lines += line
        }
        if (YouTube.cookie == null) {
            say("Signed out: only an account can be offered Premium streams. Sign in and tap again.")
            return@withContext lines
        }
        say(if (nowPlaying) "Song $videoId, now playing" else "Song $videoId, a fixed one: nothing from YouTube is playing")
        val asked = YTPlayerUtils.playerResponseAsAccount(videoId)
        val response = asked.answer?.getOrNull()
        when {
            asked.answer == null -> say("No answer in time")
            response == null -> say("The account's request failed: ${asked.answer.exceptionOrNull()?.javaClass?.simpleName}")
            else -> {
                say(PremiumFormats.streamsLine(ListenReporting.ACCOUNT_ADDRESS_CLIENT.clientName, response.streamingData?.adaptiveFormats))
                say(PremiumFormats.offeredLine(response.streamingData?.adaptiveFormats))
            }
        }
        lines
    }
}
