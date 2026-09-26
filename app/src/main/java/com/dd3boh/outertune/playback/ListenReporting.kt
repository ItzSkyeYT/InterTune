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
}
