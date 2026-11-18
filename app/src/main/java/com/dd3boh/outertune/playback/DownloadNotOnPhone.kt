/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.io.IOException

/**
 * A song the app counts as downloaded that the player could not read from the phone, and could not fetch
 * instead: no network, or one that did not answer.
 *
 * It failed as "No network connection" (#179, 14 Nov 2026: two downloaded songs of many, offline, on 0.12.9),
 * which was true and said nothing: the person had downloaded the song so that it would play without one. What
 * is wrong is on the phone, and it is one of three things the log could not tell apart: a download that holds
 * only part of the song, a file the download folder no longer has (or not yet listed), or the download kept
 * under another id. The error now says the download is missing or incomplete, and the log line says which
 * ([said]). The network is still waited for, as before: once back, the song streams.
 *
 * [failure] is what the fetch failed with: a connection that could not be made.
 */
class DownloadNotOnPhone(failure: Throwable) : IOException("Counted as downloaded, but not on the phone", failure) {
    companion object {
        /** Whether [error], or what caused it, is one: the player's error screen then says so in plain words. */
        fun isIt(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(10).any { it is DownloadNotOnPhone }

        /**
         * The log line for [mediaId], read from byte [position]: how much of it the download cache holds ([held] of
         * [length] bytes, the length -1 or 0 when the cache never learnt it), whether a download folder has a
         * file for it ([inFolder]), and what is known of the network: the fetch failed with [failure] (the
         * exception's name), while the phone had a network or not ([online]). A refused connection or one that
         * timed out comes on a phone that is online too (a VPN, a blocked network), so the line never says
         * there is no network when the phone had one.
         */
        fun said(mediaId: String, position: Long, held: Long, length: Long, inFolder: Boolean, failure: String, online: Boolean): String {
            val cache = when {
                held <= 0 -> "the download cache holds none of it"
                length > 0 -> "the download cache holds $held of its $length bytes"
                else -> "the download cache holds $held bytes of it, its length not known"
            }
            val folder = if (inFolder) "a download folder has a file for it now" else "no download folder has a file for it"
            val network = "fetching it failed ($failure) while the phone was ${if (online) "online" else "offline"}"
            return "[$mediaId] counted as downloaded, but not read from the phone from byte $position, and $network: $cache, $folder"
        }
    }
}
