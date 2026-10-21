/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WarmupTest {
    private fun warms(validated: Boolean = true, dataSaver: Boolean = false, unmetered: Boolean = false, warmed: Boolean = false) =
        Warmup.newPipe(validated, dataSaver, unmetered, warmed)

    @Test
    fun `never on mobile data whatever the song, where the first song that needs the script fetches it`() {
        assertFalse("0.7 MB at every start of the service, the widget's and the car's too", warms(unmetered = false))
        assertTrue("on Wi-Fi whatever the song", warms(unmetered = true))
    }

    @Test
    fun `never with Data Saver on, offline, or twice in a process`() {
        assertFalse(warms(dataSaver = true, unmetered = true))
        assertFalse(warms(validated = false, unmetered = true))
        assertFalse(warms(warmed = true, unmetered = true))
    }

    @Test
    fun `the service warms through Warmup, and NewPipe fetches its script once at a time`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(service.contains("Warmup.newPipe("))
        assertTrue(service.contains("NewPipeUtils.warm("))
        val newPipe = File("../innertube/src/main/java/com/zionhuang/innertube/NewPipe.kt").readText()
        // One use at a time, waited for a bounded time, not every use forever behind a slow fetch.
        assertTrue(newPipe.contains("scriptLock.tryLock("))
        assertTrue(newPipe.contains(".callTimeout("))
    }

    @Test
    fun `the service says whether this process warmed already, so a new service does not fetch again`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertFalse(service.contains("alreadyWarmed = false"))
        assertTrue(service.contains("alreadyWarmed = Warmup.warmedInProcess"))
        assertTrue(service.contains("Warmup.warmedInProcess = true"))
    }
}
