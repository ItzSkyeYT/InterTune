/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Started with no network, the player used to take itself for online, give up its wait after
 * 50 s and never come back when the network did. The network callbacks say nothing while there
 * is none, so the state at start has to be read. Read from the source: the service cannot run here.
 */
class OfflineAtStartTest {
    @Test
    fun `offline at launch is known before the first network callback`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val observer = File("src/main/java/com/dd3boh/outertune/utils/NetworkConnectivityObserver.kt").readText()
        val assumedOnline = service.contains("private val isNetworkConnected = MutableStateFlow(true)")
        val toldAtStart = observer.substringAfter("init {").contains("activeNetwork")
        assertTrue("starts online and nothing says otherwise while there is no network", !assumedOnline || toldAtStart)
    }
}
