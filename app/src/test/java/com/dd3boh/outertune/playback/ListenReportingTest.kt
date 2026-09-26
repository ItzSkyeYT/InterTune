/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenReportingTest {

    @Test
    fun `paused listen history sends no now playing`() {
        assertFalse(ListenReporting.sendsNowPlaying(listenHistoryPaused = true))
        assertTrue(ListenReporting.sendsNowPlaying(listenHistoryPaused = false))
    }

    @Test
    fun `the YouTube history ping needs someone signed in`() {
        assertTrue(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = true, remoteHistoryPaused = false, throttled = false))
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = false, remoteHistoryPaused = false, throttled = false))
    }

    @Test
    fun `the YouTube history ping keeps its other conditions`() {
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = true, loggedIn = true, remoteHistoryPaused = false, throttled = false))
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = true, remoteHistoryPaused = true, throttled = false))
        assertFalse(ListenReporting.pingsYouTubeHistory(isLocal = false, loggedIn = true, remoteHistoryPaused = false, throttled = true))
    }
}
