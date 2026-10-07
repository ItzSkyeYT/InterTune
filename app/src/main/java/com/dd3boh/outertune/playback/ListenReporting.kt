/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * What a play is allowed to tell services outside the phone, kept apart from the service so the
 * rules can be tested.
 */
object ListenReporting {

    /**
     * Last.fm's "now playing". Paused listen history keeps what is playing to the phone, and
     * Last.fm shows now playing on the profile for anyone to see; scrobbles were already held
     * back, now playing went out regardless.
     */
    fun sendsNowPlaying(listenHistoryPaused: Boolean): Boolean = !listenHistoryPaused

    /**
     * Whether a counted play is also registered in YouTube's watch history. Not for local files,
     * not while remote history is paused or YouTube is throttling us, and not signed out: the ping
     * then had no account to land in, and cost a whole extra /player request per song.
     */
    fun pingsYouTubeHistory(isLocal: Boolean, loggedIn: Boolean, remoteHistoryPaused: Boolean, throttled: Boolean): Boolean =
        !isLocal && loggedIn && !remoteHistoryPaused && !throttled

    /** Whether YouTube took the report. A 2xx says it was well formed, not that the play is in the history. */
    fun historyReportTaken(status: Int): Boolean = status in 200..299

    /**
     * The line the log gets for a play reported to YouTube's history: the status YouTube answered
     * with and nothing else. The report used to leave no trace when it was refused, so "my
     * history does not sync" could not be told from a log.
     */
    fun historyAnswerLine(status: Int): String =
        if (historyReportTaken(status)) "Remote history: YouTube answered $status"
        else "Remote history: YouTube answered $status, the play was refused"

    /**
     * The line for a report that got no answer: the kind of failure and its class, never its
     * message. A timeout's message quotes the address the report went to, and that address names
     * the video, the session and the player.
     */
    fun historyFailureLine(failure: Throwable): String {
        val kind = when (failure) {
            is java.net.UnknownHostException, is java.net.ConnectException -> "no connection"
            is java.net.SocketTimeoutException -> "timed out"
            is java.io.IOException -> "network error"
            else -> "failed"
        }
        return "Remote history: the report got no answer ($kind, ${failure.javaClass.simpleName})"
    }

    /**
     * What the log may say about the address a play is reported to: its host and how many
     * parameters it carries, or that there is none. Its parameters are the identifiers.
     */
    fun trackingAddressForLog(address: String?): String {
        if (address == null) return "none"
        val host = address.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        val parameters = address.substringAfter('?', "").substringBefore('#').split('&').count { it.isNotEmpty() }
        return "${host.ifEmpty { "no host" }}, parameters: $parameters"
    }
}
