/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The song playing is never moved into the download folder nor given another stream under the
 * player (review of 0.13, F1). The player reads a song at its container's byte offsets for as long
 * as it has it open; the move put an Ogg or M4A file where it read WebM or MP4 from the cache, and a
 * Download quality of its own fetched another stream and dropped the copy being played, so the next
 * Previous, seek back or reconnect read garbage. The move waits until the song is let go.
 */
class HeldWhilePlayingTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `a song the player has open is held, the others move now`() {
        val held = HeldWhilePlaying()
        assertTrue(held.playerHas(setOf("a", "b")).isEmpty())
        assertEquals(setOf("c"), held.movableNow(listOf("a", "c")))
        assertTrue(held.isHeld("a"))
        assertFalse(held.isHeld("c"))
    }

    @Test
    fun `the held song moves once the player lets it go, and only once`() {
        val held = HeldWhilePlaying()
        held.playerHas(setOf("a", "b"))
        held.movableNow(listOf("a", "b"))
        // The next song: "a" is let go, "b" plays.
        assertEquals(setOf("a"), held.playerHas(setOf("b", "c")))
        assertFalse(held.isHeld("a"))
        assertTrue(held.isHeld("b"))
        // Nothing open: the rest goes.
        assertEquals(setOf("b"), held.playerHas(emptySet()))
        assertTrue(held.playerHas(emptySet()).isEmpty())
    }

    @Test
    fun `with nothing open everything moves at once`() {
        val held = HeldWhilePlaying()
        assertEquals(setOf("a", "b"), held.movableNow(listOf("a", "b")))
        assertTrue(held.playerHas(setOf("a")).isEmpty())
    }

    @Test
    fun `the move leaves out the songs the player has open, before it reads any of them`() {
        val migrate = util.substringAfter("suspend fun migrateDownloads(").substringBefore("    private suspend fun saveSongFile(")
        val filter = migrate.indexOf("inPlay.movableNow(")
        assertTrue(filter >= 0)
        assertTrue(filter < migrate.indexOf("val toMigrate = completed.filterKeys { it in movable }"))
        assertTrue(migrate.indexOf("val toMigrate") < migrate.indexOf("toMigrate.forEach"))
    }

    @Test
    fun `a held song is moved when the player lets it go, and its toast waits for it`() {
        val has = util.substringAfter("fun playerHas(ids: Set<String>) {").substringBefore("\n    }\n")
        assertTrue(has.contains("inPlay.playerHas(ids)"))
        assertTrue(has.contains("migrateDownloads(quiet = true, only = free)"))
        assertTrue(has.contains("says.whenDone(free)"))
        val idle = util.substringAfter("override fun onIdle(downloadManager: DownloadManager) {").substringBefore("override fun onDownloadChanged(")
        assertTrue(idle.contains("!inPlay.isHeld(it)"))
    }

    @Test
    fun `the download waits before it drops the copy being played or fetches a quality of its own`() {
        val resolver = util.substringAfter("val (asked, playing) = qualitiesNow()").substringBefore("fun paced()")
        val stale = resolver.indexOf("staleCopy = true")
        assertTrue(resolver.lastIndexOf("waitWhileOpen(mediaId)", stale) in 0 until stale)
        // Still open past the wait, the copy being read is taken as it is: nothing dropped, nothing fetched.
        assertTrue(resolver.contains("if (!waitWhileOpen(mediaId)) return@Factory dataSpec"))
        // A stream of its own quality is waited for from the start, before the player's part is dropped,
        // and after the shortcut that takes the player's own copy as it is.
        val own = resolver.indexOf("if (asked != playing && !waitWhileOpen(mediaId)) asPlayed = true")
        assertTrue(own > resolver.indexOf("return@Factory dataSpec"))
        assertTrue(own < resolver.indexOf("if (playerCache.holdsPartFromStart(mediaId))"))
        assertTrue(own < resolver.indexOf("} else if (playerCache.isCached(mediaId, dataSpec.position, length)"))
        // Still open past the wait, the download asks for what the player plays, and no url kept at its own quality.
        assertTrue(resolver.contains("val quality = if (asPlayed) playing else asked"))
        assertTrue(resolver.contains("songUrlCache[mediaId]?.takeIf { !staleCopy && !asPlayed && stillUsable(it.first, it.second) }"))
        // Nor is the song cache's copy dropped under the player once its download lands.
        val drop = util.substringAfter("private fun dropSongCacheCopy(id: String, reason: String) {").substringBefore("\n    }\n")
        assertTrue(drop.indexOf("if (inPlay.isOpen(id)) {") in 0 until drop.indexOf("playerCache.removeResource(id)"))
        // The set exists before media3 can call the resolver.
        assertTrue(util.indexOf("private val inPlay = HeldWhilePlaying()") in 0 until util.indexOf("private val dataSourceFactory"))
        // A few minutes at most, through the wait tested below; a stopped download ends it, Thread.sleep hears the interrupt.
        val wait = util.substringAfter("private fun waitWhileOpen(id: String): Boolean {").substringBefore("\n    }\n")
        assertTrue(wait.contains("inPlay.awaitLetGo(id, OPEN_SONG_WAIT_MS, OPEN_SONG_POLL_MS, now = { SystemClock.elapsedRealtime() }, sleep = { Thread.sleep(it) })"))
        assertTrue(util.contains("private const val OPEN_SONG_WAIT_MS = 3 * 60_000L"))
    }

    // ---- the wait ends (review of the branch, R1: it had no end, and a song paused or ended stays open)

    @Test
    fun `a song never let go ends the wait at its limit, without sleeping past it`() {
        val held = HeldWhilePlaying()
        held.playerHas(setOf("a"))
        var clock = 0L
        var sleeps = 0
        val letGo = held.awaitLetGo("a", limitMs = 180_000, pollMs = 1_000, now = { clock }, sleep = { clock += it; sleeps++ })
        assertFalse(letGo)
        assertEquals(180_000L, clock)
        assertEquals(180, sleeps)
    }

    @Test
    fun `the wait ends as soon as the player lets the song go`() {
        val held = HeldWhilePlaying()
        held.playerHas(setOf("a", "b"))
        var clock = 0L
        var sleeps = 0
        val letGo = held.awaitLetGo("a", limitMs = 180_000, pollMs = 1_000, now = { clock }, sleep = {
            clock += it
            if (++sleeps == 3) held.playerHas(setOf("b"))
        })
        assertTrue(letGo)
        assertEquals(3, sleeps)
    }

    @Test
    fun `a song not open is not waited for`() {
        val held = HeldWhilePlaying()
        held.playerHas(setOf("b"))
        assertTrue(held.awaitLetGo("a", limitMs = 180_000, pollMs = 1_000, now = { 0L }, sleep = { throw AssertionError("slept") }))
    }

    @Test
    fun `a real wait on another thread ends when the player moves on, and when the download is stopped`() {
        val held = HeldWhilePlaying()
        held.playerHas(setOf("a"))
        var letGo: Boolean? = null
        val waiting = Thread { letGo = held.awaitLetGo("a", 10_000, 5, now = { System.nanoTime() / 1_000_000 }, sleep = { Thread.sleep(it) }) }
        waiting.start()
        Thread.sleep(50)
        held.playerHas(emptySet())
        waiting.join(2_000)
        assertFalse("still waiting", waiting.isAlive)
        assertEquals(true, letGo)

        // media3 stops a download by interrupting its thread: the sleep throws and the wait is over.
        held.playerHas(setOf("a"))
        var stopped = false
        val stopping = Thread {
            try {
                held.awaitLetGo("a", 10_000, 5, now = { System.nanoTime() / 1_000_000 }, sleep = { Thread.sleep(it) })
            } catch (e: InterruptedException) {
                stopped = true
            }
        }
        stopping.start()
        Thread.sleep(50)
        stopping.interrupt()
        stopping.join(2_000)
        assertFalse("still waiting", stopping.isAlive)
        assertTrue(stopped)
    }

    @Test
    fun `the player says what it has open at each change of song, queue or state, and none once it ends`() {
        val events = service.substringAfter("override fun onEvents(player: Player, events: Player.Events) {").substringBefore("    private fun openSongs(")
        assertTrue(events.contains("downloadUtil.playerHas(openSongs(player))"))
        for (event in listOf("EVENT_MEDIA_ITEM_TRANSITION", "EVENT_TIMELINE_CHANGED", "EVENT_PLAYBACK_STATE_CHANGED")) {
            assertTrue(event, events.substringAfterLast("events.containsAny(").contains(event))
        }
        val open = service.substringAfter("private fun openSongs(player: Player): Set<String> {").substringBefore("\n    }\n")
        assertTrue(open.contains("if (player.playbackState == Player.STATE_IDLE) return emptySet()"))
        assertTrue(open.contains("player.nextMediaItemIndex"))
        val destroy = service.substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        assertTrue(destroy.contains("downloadUtil.playerHas(emptySet())"))
    }
}
