/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A download that lands says so in the log, with its size and how long it took (landedLine). Report a
 * problem carries DownloadUtil's lines, and until 12 Nov 2026 a download that landed wrote none: a
 * tester's "downloading is very slow" could not be read as slow bytes or as a slow conversion after them.
 */
class DownloadLandedLineTest {

    @Test
    fun `the line says the size and the time from asked to landed`() {
        assertEquals(
            "Download of dQw4w9WgXcQ landed: 3.62 MB, 2.4 s after it was asked for",
            landedLine("dQw4w9WgXcQ", 3_620_000L, askedAtMs = 1_000_000L, landedAtMs = 1_002_400L),
        )
    }

    @Test
    fun `a stream that never said its length, and a clock that went back, are said plainly`() {
        assertEquals(
            "Download of dQw4w9WgXcQ landed: its size not known, 0.0 s after it was asked for",
            landedLine("dQw4w9WgXcQ", -1L, askedAtMs = 5_000L, landedAtMs = 4_000L),
        )
    }

    @Test
    fun `the download listener writes it when a song's bytes are all in`() {
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val completed = util.substringAfter("if (download.state == Download.STATE_COMPLETED) {").substringBefore("if (download.state == Download.STATE_FAILED)")
        assertTrue("Log.i(TAG, landedLine(download.request.id, download.contentLength, download.startTimeMs, download.updateTimeMs))" in completed)
    }
}
