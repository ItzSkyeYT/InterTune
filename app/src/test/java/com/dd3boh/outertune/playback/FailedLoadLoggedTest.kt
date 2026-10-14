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
 * A load that fails is written to the log, so Report a problem has something to show for a song
 * that stopped "with no error" (issue #20): before, nothing was written until the player gave up.
 * Reads the source, as the other guards of MusicService do.
 */
class FailedLoadLoggedTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val listener = service.substringAfter("override fun onLoadError(").substringBefore("override fun onLoadCompleted(")

    @Test
    fun `a failed load is written as a warning, with its causes and without addresses`() {
        assertTrue(listener.contains("Log.w(TAG, \"PLAYING: a load of \$id failed, the player tries again: \${ErrorText.withoutAddresses(causes)}\")"))
        assertTrue("its causes are walked", listener.contains("generateSequence(error as Throwable) { it.cause }"))
    }

    @Test
    fun `at most one line a minute a song, since the player retries every few seconds`() {
        assertTrue(listener.contains("now - last >= 60_000L"))
        assertTrue("a cancelled load is not a failure", listener.contains("if (wasCanceled) return"))
        assertTrue("the damaged copy is still counted", listener.contains("badCachedCopy.loadFailed(id, error)"))
    }
}
