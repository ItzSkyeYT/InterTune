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
        val resolver = util.substringAfter("val quality = qualityNow()").substringBefore("fun paced()")
        val stale = resolver.indexOf("staleCopy = true")
        assertTrue(resolver.lastIndexOf("waitWhileOpen(mediaId)", stale) in 0 until stale)
        // A stream of its own quality is waited for from the start, before the player's part is dropped,
        // and after the shortcut that takes the player's own copy as it is.
        val own = resolver.indexOf("if (quality != audioQuality) waitWhileOpen(mediaId)")
        assertTrue(own > resolver.indexOf("return@Factory dataSpec"))
        assertTrue(own < resolver.indexOf("if (playerCache.holdsPartFromStart(mediaId))"))
        assertTrue(own < resolver.indexOf("} else if (playerCache.isCached(mediaId, dataSpec.position, length)"))
        // Nor is the song cache's copy dropped under the player once its download lands.
        val drop = util.substringAfter("private fun dropSongCacheCopy(id: String, reason: String) {").substringBefore("\n    }\n")
        assertTrue(drop.indexOf("if (inPlay.isOpen(id)) {") in 0 until drop.indexOf("playerCache.removeResource(id)"))
        // The set exists before media3 can call the resolver.
        assertTrue(util.indexOf("private val inPlay = HeldWhilePlaying()") in 0 until util.indexOf("private val dataSourceFactory"))
        // A stopped download ends the wait: media3 interrupts its thread, which Thread.sleep hears.
        val wait = util.substringAfter("private fun waitWhileOpen(id: String) {").substringBefore("\n    }\n")
        assertTrue(wait.contains("while (inPlay.isOpen(id)) Thread.sleep("))
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
