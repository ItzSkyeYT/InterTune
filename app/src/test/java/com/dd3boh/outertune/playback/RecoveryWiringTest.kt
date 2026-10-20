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
 * The player's own recovery is wired where it has to be: read from the source, as the other
 * guards of MusicService do, since the service cannot run here.
 */
class RecoveryWiringTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    private fun body(after: String, before: String) = service.substringAfter(after).substringBefore(before)

    @Test
    fun `a failed load is judged, and a route that keeps failing is changed while the buffer plays`() {
        val listener = body("override fun onLoadError(", "override fun onLoadCompleted(")
        assertTrue(listener.contains("loadFailures.decide(id, error, SystemClock.elapsedRealtime())"))
        assertTrue(listener.contains("changeRoute(id,"))
    }

    @Test
    fun `a stopped song asks whether its fault is passing before it is stopped or skipped`() {
        val handler = body("override fun onPlayerError(error: PlaybackException) {", "private fun scheduleQuietRetry(")
        val asked = handler.indexOf("Recovery.kind(error)")
        assertTrue(asked > 0)
        assertTrue("before the stop", asked < handler.indexOf("stopOnError()"))
        assertTrue("after the wait for the network", handler.indexOf("waitOnNetworkError()") < asked)
        assertTrue("within the budget", handler.contains("recoveryBudget.take(failedId, now)"))
        assertTrue("the route it failed on is read before its address is forgotten",
            handler.indexOf("streamAddresses.clientOf(failedId)") < handler.indexOf("streamAddresses.playerFailedOn(failedId)"))
    }

    @Test
    fun `the stall watch is started and stopped only by the player's events`() {
        val starts = Regex("""stallHandler\.postDelayed\(""").findAll(service).count()
        assertEquals("one start, one repeat", 2, starts)
        assertTrue(body("private fun updateStallWatch(", "private fun watchStall(").contains("stallHandler.postDelayed(stallTick, 1_000L)"))
        assertTrue(body("override fun onEvents(player: Player, events: Player.Events) {", "private fun lastCompletedId(").contains("updateStallWatch(player)"))
    }

    @Test
    fun `every byte the player's stream reads brings in is counted`() {
        val guard = File("src/main/java/com/dd3boh/outertune/playback/PaceGuardDataSource.kt").readText()
        val read = guard.substringAfter("override fun read(").substringBefore("override fun getUri()")
        assertEquals("both ways through the read", 2, Regex("""StreamProgress\.bytes\.addAndGet""").findAll(read).count())
    }

    @Test
    fun `the stall watch cuts the player's own calls, never the cast server's`() {
        assertTrue(service.contains("createCacheDataSource(playerCalls)"))
        assertEquals(2, Regex("""playerCalls\.cancelInFlight\(\)""").findAll(service).count())
    }

    @Test
    fun `a watch started again forgets the silence it counted before`() {
        val start = body("private fun updateStallWatch(", "private fun watchStall(")
        assertTrue(start.indexOf("stallWatch.reset()") in 0 until start.indexOf("stallHandler.postDelayed(stallTick, 1_000L)"))
    }

    @Test
    fun `the stall watch's own cut is not counted as a failed load`() {
        val cut = body("StallWatch.Action.CUT_CONNECTION ->", "StallWatch.Action.CHANGE_ROUTE ->")
        assertTrue(cut.contains("loadFailures.cutByUs(id, now)"))
    }
}
