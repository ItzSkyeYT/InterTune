/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadClockTest {
    @Test
    fun `a download's clock runs faster than real time from where its stream began`() {
        var now = 1_000L
        val clock = SabrOver.downloadClock(30_000L) { now }
        assertEquals(30_000L, clock())
        now += 10_000L
        assertEquals(30_000L + 10_000L * SabrOver.DOWNLOAD_PACE, clock())
    }
}
