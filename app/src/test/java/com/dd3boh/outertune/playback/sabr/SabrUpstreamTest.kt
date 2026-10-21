/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import com.dd3boh.outertune.sabr.SabrFile
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song's bytes reach the player, downloads and the cast server through one upstream that knows
 * sabr: addresses ([SabrOrHttp]). A place that built its own plain HTTP source again would play a
 * song started over SABR nowhere but where it was started, and not cache or download it.
 */
class SabrUpstreamTest {

    private val playback = File("src/main/java/com/dd3boh/outertune/playback")

    private fun wrapped(file: String): Pair<Int, Int> {
        val text = File(playback, file).readText()
        val http = Regex("""OkHttpDataSource\.Factory\(""").findAll(text).count()
        val inSabr = Regex("""SabrOrHttp\.Factory\(\s*OkHttpDataSource\.Factory\(""").findAll(text).count()
        return http to inSabr
    }

    @Test
    fun `the player's cache chain and the cast server go through it`() {
        val (http, inSabr) = wrapped("MusicService.kt")
        assertEquals("every HTTP source of MusicService sits inside SabrOrHttp", http, inSabr)
        assertEquals(2, inSabr)
    }

    @Test
    fun `downloads go through it`() {
        val text = File(playback, "DownloadUtil.kt").readText()
        // The one that downloads songs. The other, further down, only reads the downloads of the
        // old download manager back out of the cache.
        val downloads = text.substring(text.indexOf("private val dataSourceFactory = ResolvingDataSource.Factory("))
            .substringBefore("{ dataSpec ->")
        assertTrue("SabrOrHttp.Factory(" in downloads)
    }

    @Test
    fun `SABR is the last rung, only after YouTube turned the walk down and never for a song it calls gone`() {
        val utils = File("src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt").readText()
        assertTrue(Regex("""if \(result\.isFailure && [^\n]*sabrMayTakeOver\(failure\)\) \{""").containsMatchIn(utils))
        val mayTakeOver = utils.substringAfter("internal fun sabrMayTakeOver(").substringBefore("\n\n")
        assertTrue(mayTakeOver, "!isUnavailableSong(failure)" in mayTakeOver)
        assertTrue(mayTakeOver, "!StreamOrder.networkDidNotAnswer(failure)" in mayTakeOver)
        // and from a VISIONOS answer of this walk, which the wire already has: no request of its own
        val rung = utils.substringAfter("private fun sabrRung(").substringBefore("\n    }\n")
        assertTrue("wire.answers().of(videoId, VISIONOS.clientName, since)" in rung)
        assertTrue("no request of its own", "wire.player(" !in rung && "wire.head(" !in rung && "YouTube.player(" !in rung)
    }

    @Test
    fun `the switch that plays everything over SABR is never read in a release`() {
        val service = File(playback, "MusicService.kt").readText()
        val read = service.substringAfter("// A test tool like the switches below: never read in a release.").substringBefore("}\n")
        assertTrue("if (BuildConfig.DEBUG && Unreleased.SABR) {" in read)
        assertTrue("if (BuildConfig.DEBUG && Unreleased.SABR && YTPlayerUtils.sabrOnly) {" in service)
    }

    private fun start(id: String, expires: Long) =
        SabrStarts.Start(id, 251, 1_700_000_000_000_000, SabrFile({ emptyFlow() }, contentLength = 1_000, durationMs = 10_000), expires)

    @Test
    fun `an address names the song and its format, and nothing else`() {
        val address = SabrStarts.put(start("abcdefghijk", Long.MAX_VALUE))
        assertEquals("sabr://abcdefghijk?itag=251&lmt=1700000000000000", address)
        assertNotNull(SabrStarts.forAddress(address))
        SabrStarts.forget("abcdefghijk")
    }

    @Test
    fun `a start that has run out, or was forgotten, is gone`() {
        val old = SabrStarts.put(start("oldoldoldol", 1_000))
        assertNull("ran out", SabrStarts.forAddress(old, nowMs = 1_000))
        val kept = SabrStarts.put(start("keptkeptkep", Long.MAX_VALUE))
        SabrStarts.forget("keptkeptkep")
        assertNull("forgotten", SabrStarts.forAddress(kept))
    }
}
