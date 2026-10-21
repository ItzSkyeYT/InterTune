/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.sabr.SabrStarts
import com.dd3boh.outertune.sabr.SabrFile
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A download remembers the address it was given for about six hours. A sabr: one stands for a
 * start the app keeps (SabrStarts), and one SABR fault, in the download's own read or the player's
 * of the same song, forgets that start. The remembered address then failed every retry at once
 * with a 410, and a retry by hand failed the same way until the six hours were over.
 */
class DownloadCachedAddressTest {

    @After
    fun forget() = SabrStarts.forget(SONG)

    private fun start(expires: Long = Long.MAX_VALUE) = SabrStarts.Start(
        SONG, 251, 1_700_000_000_000L, SabrFile({ emptyFlow() }, contentLength = 1_000, durationMs = 10_000), expires, pacedByPlayer = false,
    )

    @Test
    fun `a sabr address whose start was forgotten is asked for again`() {
        val address = SabrStarts.put(start())
        val expires = System.currentTimeMillis() + 6 * 3_600_000L
        assertTrue("while its start is kept", stillUsable(address, expires))
        SabrStarts.forget(SONG)
        assertFalse("its start is gone: the read would fail at once", stillUsable(address, expires))
    }

    @Test
    fun `a direct address is used until it runs out`() {
        val address = "https://rr1.googlevideo.com/videoplayback?id=x&range=0-4092136"
        assertTrue(stillUsable(address, expiresAtMs = 2_000, nowMs = 1_000))
        assertFalse(stillUsable(address, expiresAtMs = 1_000, nowMs = 1_000))
    }

    @Test
    fun `the resolver hands out a remembered address only while it can still be read`() {
        val text = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        assertTrue(text.contains("songUrlCache[mediaId]?.takeIf { !staleCopy && stillUsable(it.first, it.second) }"))
    }

    private companion object {
        const val SONG = "DLCACHESONG"
    }
}
