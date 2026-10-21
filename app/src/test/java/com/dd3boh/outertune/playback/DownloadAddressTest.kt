/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.sabr.SabrStarts
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadAddressTest {
    @Test
    fun `a direct address asks for the whole file as one range`() {
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=x&range=0-4092136", downloadAddress("https://rr1.googlevideo.com/videoplayback?id=x", 4_092_136L))
        assertEquals("https://h/v?a=1&range=0-10000000", downloadAddress("https://h/v?a=1", null))
    }

    @Test
    fun `a sabr address stays the exact text its start is kept under`() {
        val address = SabrStarts.addressOf("CLSuAhT71yU", 251, 1_700_000_000_000L)
        assertEquals(address, downloadAddress(address, 4_092_136L))
    }
}
