/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.playback.ListenReporting.AddressFrom
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Check the history address" in developer options, in debug builds.
 *
 * It asks for the address a play of a song would be reported to, as a play does: the account's
 * request and the visitor's. Both, where a play stops at the first that gives an address, and it
 * says of each what came back, whether an address was in it and how long it took. Then it stops.
 * Nothing is reported, so it can be tapped on any phone without a play landing in anybody's
 * history, and it is what somebody whose plays do not arrive can be asked to tap.
 *
 * It asks what a play would ask and no more: the account's request is not made signed out, nor
 * when playback as the account is set to never.
 */
object HistoryAddressCheck {
    private const val TAG = "HistoryAddressCheck"

    /**
     * The song asked about when nothing from YouTube is playing: Queen, Bohemian Rhapsody, the
     * official upload of 2008, which StreamChainProbe asks about too.
     */
    const val FIXED_SONG = "fJ9rUzIMcZQ"

    /**
     * The lines to show, each written to the log as well. [nowPlaying] says whether [videoId] is
     * the song in the player. [historyPaused] and [remoteHistoryPaused] are the two settings
     * that keep a play from YouTube's history, for the last line.
     */
    suspend fun run(
        videoId: String,
        nowPlaying: Boolean,
        historyPaused: Boolean,
        remoteHistoryPaused: Boolean,
    ): List<String> = withContext(Dispatchers.IO) {
        val loggedIn = YouTube.cookie != null
        val authMode = YTPlayerUtils.authMode
        val lines = mutableListOf<String>()
        fun say(line: String) {
            Log.i(TAG, line)
            lines += line
        }
        say(if (nowPlaying) "Song $videoId, now playing" else "Song $videoId, a fixed one: nothing from YouTube is playing")

        // The first request that gave an address, which is the one a play would report to.
        var found: AddressFrom? = null
        ListenReporting.accountNotAskedLine(loggedIn, authMode)?.let { say(it) }
        for (from in listOf(AddressFrom.ACCOUNT, AddressFrom.VISITOR)) {
            if (from == AddressFrom.ACCOUNT && from !in ListenReporting.addressRequests(loggedIn, authMode)) continue
            val line = when (from) {
                AddressFrom.ACCOUNT -> {
                    val asked = YTPlayerUtils.playerResponseAsAccount(videoId)
                    val failure = asked.answer?.exceptionOrNull()
                    val status = asked.answer?.getOrNull()?.playabilityStatus
                    if (asked.address != null && found == null) found = from
                    ListenReporting.addressCheckLine(
                        from,
                        ListenReporting.ACCOUNT_ADDRESS_CLIENT.clientName,
                        ListenReporting.requestAnswer(
                            status?.status, failure, (failure as? ResponseException)?.response?.status?.value,
                            asked.without, asked.outOfTimeAt,
                        ),
                        hasAddress = asked.address != null,
                        tookMs = asked.tookMs,
                        reason = status?.reason,
                        steps = asked.steps,
                    )
                }
                AddressFrom.VISITOR -> {
                    val started = System.nanoTime()
                    val answer = YTPlayerUtils.playerResponseForMetadata(videoId)
                    val tookMs = (System.nanoTime() - started) / 1_000_000
                    val failure = answer.exceptionOrNull()
                    val status = answer.getOrNull()?.playabilityStatus
                    val hasAddress = YTPlayerUtils.addressIn(answer) != null
                    if (hasAddress && found == null) found = from
                    ListenReporting.addressCheckLine(
                        from,
                        YouTubeClient.VISIONOS.clientName,
                        ListenReporting.requestAnswer(status?.status, failure, (failure as? ResponseException)?.response?.status?.value),
                        hasAddress = hasAddress,
                        tookMs = tookMs,
                        reason = status?.reason,
                    )
                }
            }
            say(line)
        }
        say(ListenReporting.checkVerdictLine(found, loggedIn, historyPaused, remoteHistoryPaused, Throttle.isBlocked))
        lines
    }
}
