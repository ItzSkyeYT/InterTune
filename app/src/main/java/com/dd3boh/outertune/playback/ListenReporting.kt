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
}
