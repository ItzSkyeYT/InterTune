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
 * Nothing is written to the song cache while it is off.
 *
 * Off is a size of 0, picked in Settings or in setup, or none stored on an older install that never
 * picked one (a new install starts at 256 MB: NewInstallSongCacheTest). The cache is then built
 * with an evictor of 0 bytes, which deleted each piece the moment it was in, while the write sink
 * still wrote every streamed byte to storage first.
 */
class SongCacheOffWritesNothingTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    /** The player's write sink for the song cache, its whole lambda. */
    private val sink = service.substringAfter("HybridCacheDataSinkFactory(playerCache) { dataSpec ->")
        .substringBefore(".setFlags(")

    /**
     * Asked in the lambda, which the sink runs for each write it opens, so the setting is read as
     * it is then and not once when the cache was built: turning the cache off stops the writes.
     */
    @Test
    fun theSinkWritesOnlyWhileTheCacheHasASize() {
        // The size as the cache is built with it: the one picked, or none, or Battery use's on Saver.
        assertTrue(sink, Regex("""val write = BatteryUse\.songCacheMb\(dataStore\[MaxSongCacheSizeKey], batteryMode\) != 0 &&""").containsMatchIn(sink))
        assertTrue("and still never a song on the phone", "?.isLocal != true" in sink)
        assertTrue(Regex("""\n\s*write\n""").containsMatchIn(sink))
    }
}
