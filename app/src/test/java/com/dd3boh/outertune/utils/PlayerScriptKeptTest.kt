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

/**
 * YouTube's player script is kept on disk between launches, and fetched ahead of the first song
 * even when the player holds none yet.
 *
 * NewPipe's client kept nothing, so every new process fetched the script again, about 1 MB, and
 * the first streamed song waited for it (11 s once, see Warmup). It is served for a year at an
 * address that names its version. The warm-up ran only with a song in the player, which after a
 * cold start holds none.
 */
class PlayerScriptKeptTest {
    private val newPipe = File("../innertube/src/main/java/com/zionhuang/innertube/NewPipe.kt").readText()
    private val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun newPipesClientKeepsWhatYouTubeLetsItKeep() {
        val start = newPipe.indexOf("OkHttpClient.Builder()")
        val built = newPipe.substring(start, newPipe.indexOf(".build()", start))
        assertTrue(built, "cache(Cache(cacheDir, CACHE_BYTES))" in built)
        assertTrue("NewPipeDownloaderImpl(YouTube.proxy, YouTube.newPipeCacheDir)" in newPipe)
    }

    @Test
    fun theAppGivesItAFolderBeforeAnythingCanAskForTheScript() {
        val set = app.indexOf("YouTube.newPipeCacheDir = cacheDir.resolve(\"newpipe-http\")")
        assertTrue("set in App.onCreate", set > app.indexOf("override fun onCreate()"))
        assertTrue("before the first thing there that reaches the stream code", set < app.indexOf("YTPlayerUtils."))
    }

    @Test
    fun theWarmUpNeedsNoSongInThePlayer() {
        val warmUp = service.substringAfter("delay(Warmup.AFTER_MS)").substringBefore("NewPipeUtils.warm(")
        assertFalse("id != null" in warmUp)
        assertTrue("the saved queue's song when the player holds none", "queueBoard.getCurrentQueue()?.getCurrentSong()?.id" in warmUp)
        assertTrue(Regex("""if \(warm\) \{""").containsMatchIn(warmUp))
    }
}
