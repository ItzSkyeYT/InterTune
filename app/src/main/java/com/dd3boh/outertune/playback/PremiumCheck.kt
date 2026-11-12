/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Check Premium formats" in developer options, in debug builds: asks /player as the account for the
 * song playing, or HistoryAddressCheck.FIXED_SONG when nothing from YouTube plays, on the web client (the
 * request a play already makes for its history address), the phone app's and the TV's, and lists the audio
 * streams each offered with their rates (PremiumFormats), then the Premium ones among them all. A tester with Premium taps it and sends the lines: 774 there, the 256 kbps copy can
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
        val offered = mutableListOf<PlayerResponse.StreamingData.Format>()
        // The web client, asked as a play asks it for the history address.
        val web = ListenReporting.ACCOUNT_ADDRESS_CLIENT.clientName
        val asked = YTPlayerUtils.playerResponseAsAccount(videoId)
        val response = asked.answer?.getOrNull()
        when {
            asked.answer == null -> say("$web (account): no answer in time")
            response == null -> say("$web (account): failed, ${failure(asked.answer.exceptionOrNull())}")
            else -> {
                say(PremiumFormats.streamsLine(web, response.streamingData?.adaptiveFormats))
                offered += response.streamingData?.adaptiveFormats.orEmpty()
            }
        }
        // The phone's and the TV's own clients as the account too: one page says the 256 kbps streams are
        // kept for the phone and TV apps, so a "none" from the web client alone would settle nothing.
        for (client in OTHER_CLIENTS) {
            val answer = runCatching { YouTube.player(videoId, client = client, hlOverride = "en").getOrThrow() }
            say(
                answer.fold(
                    onSuccess = { r ->
                        offered += r.streamingData?.adaptiveFormats.orEmpty()
                        if (r.streamingData != null) PremiumFormats.streamsLine(client.clientName, r.streamingData?.adaptiveFormats)
                        else "${client.clientName} (account): ${r.playabilityStatus.status}"
                    },
                    onFailure = { e -> "${client.clientName} (account): failed, ${failure(e)}" },
                ),
            )
        }
        say(PremiumFormats.offeredLine(offered))
        lines
    }

    /** The clients asked as the account besides the web one: the phone app's and the TV's. */
    val OTHER_CLIENTS = listOf(YouTubeClient.ANDROID, YouTubeClient.TVHTML5)

    private fun failure(e: Throwable?): String =
        (e as? ResponseException)?.response?.status?.value?.let { "HTTP $it" } ?: e?.javaClass?.simpleName ?: "no answer"
}
