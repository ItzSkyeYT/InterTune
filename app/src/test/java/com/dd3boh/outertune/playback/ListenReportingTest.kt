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
}
