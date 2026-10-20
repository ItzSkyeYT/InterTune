/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The time limits on the way to sound, read together: each has to fit the others, and no client
 * on that way may be left with OkHttp's defaults, whose idle connections were the trap of
 * 20 Oct 2026.
 */
class RouteTimesTest {
    @Test
    fun `a second try starts before its step is given up`() {
        assertTrue(RouteTimes.PLAYER_FRESH_MS < RouteTimes.PLAYER_ALONGSIDE_MS)
        assertTrue(RouteTimes.PLAYER_ALONGSIDE_MS < RouteTimes.PLAYER_CAP_MS)
        assertTrue(RouteTimes.PLAYER_CAP_MS < RouteTimes.PLAYER_SLOW_CAP_MS)
        assertTrue(RouteTimes.HEAD_FRESH_MS < RouteTimes.HEAD_WAIT_MS)
        assertTrue(RouteTimes.HEAD_WAIT_MS < RouteTimes.HEAD_LATE_MS)
    }

    @Test
    fun `the walk stops waiting for a check before the player would call the song stalled`() {
        assertTrue(RouteTimes.HEAD_WAIT_MS < RouteTimes.STALL_MS)
    }

    @Test
    fun `one dead route and one silent check cost a song under ten seconds, and four routes fit the resolve`() {
        assertTrue(RouteTimes.PLAYER_CAP_MS + RouteTimes.HEAD_WAIT_MS < 10_000)
        assertTrue(4 * RouteTimes.PLAYER_CAP_MS <= RouteTimes.RESOLVE_MS)
        assertTrue(RouteTimes.PLAYER_SLOW_CAP_MS < RouteTimes.RESOLVE_MS)
    }

    @Test
    fun `the tests' copies start as the numbers`() {
        RouteTimes.playerCapMs = 1
        RouteTimes.reset()
        assertEquals(RouteTimes.PLAYER_CAP_MS, RouteTimes.playerCapMs)
        assertEquals(RouteTimes.HEAD_WAIT_MS, RouteTimes.headWaitMs)
        assertEquals(RouteTimes.RESOLVE_MS, RouteTimes.resolveMs)
    }

    private val onTheWay = listOf(
        "src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt",
        "src/main/java/com/dd3boh/outertune/utils/StreamFamily.kt",
        "src/main/java/com/dd3boh/outertune/playback/MusicService.kt",
        "../innertube/src/main/java/com/zionhuang/innertube/NewPipe.kt",
    )

    @Test
    fun `no client on the way to sound is built with OkHttp's defaults`() {
        for (path in onTheWay) {
            val text = File(path).readText()
            assertFalse("$path builds a bare OkHttpClient()", Regex("""OkHttpClient\(\)""").containsMatchIn(text))
            for (match in Regex("""OkHttpClient\.Builder\(\)""").findAll(text)) {
                val built = text.substring(match.range.first, text.indexOf(".build()", match.range.first))
                assertTrue("$path: a client without a read limit: $built", "readTimeout(" in built)
                assertTrue("$path: a client without its own pool: $built", "connectionPool(" in built)
            }
        }
    }

    @Test
    fun `the player's resolver gives a song's whole resolve a deadline`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long): ResolvedSong {")
            .substringBefore("val playbackData = played.data")
        assertTrue(resolve.contains("withinResolveTime(context = wish) { standIns.playbackData("))
        assertTrue("a timeout of our own is a failure like any other", resolve.contains("catch (e: StepTimedOut)"))
    }

    @Test
    fun `the walk asks with a deadline, and our own cuts never reach the throttle`() {
        val walk = File("src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt").readText()
        val flat = walk.replace(Regex("""\s+"""), "")
        assertTrue(flat.contains("valhedgeAfterMs=NetworkPace.hedgeAfter(RouteTimes.playerFreshMs,capMs)"))
        assertTrue(flat.contains("hedged(\"/player\",hedgeAfterMs,capMs"))
        assertTrue(walk.contains("StreamOrder.endsWalkAtOnce(failure)"))
        val throttle = File("src/main/java/com/dd3boh/outertune/utils/Throttle.kt").readText()
        assertTrue(throttle.substringAfter("fun noteFailure(t: Throwable) {").substringBefore("}").contains("t is StepTimedOut"))
    }
}
