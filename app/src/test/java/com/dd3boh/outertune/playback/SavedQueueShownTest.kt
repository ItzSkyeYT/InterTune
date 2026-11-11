/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tester's mini player, each time Android had closed the app: one old song at one old point,
 * with Play, Next and Previous doing nothing (10 Nov 2026, the released 0.12.9). Persistent queue
 * was off, so the board had nothing to load, the song came from the rows saved before the switch
 * went off, and the sheet was up because Android brings it back where it was left.
 */
class SavedQueueShownTest {
    private val connection = File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText()
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `the saved song stands in only for an empty player, and only with Persistent queue on`() {
        assertTrue(SavedQueueShown.song(playerHoldsSong = false, persistentQueue = true))
        assertFalse("the rows saved before the switch went off are not shown", SavedQueueShown.song(playerHoldsSong = false, persistentQueue = false))
        assertFalse(SavedQueueShown.song(playerHoldsSong = true, persistentQueue = true))
        assertFalse(SavedQueueShown.song(playerHoldsSong = true, persistentQueue = false))
    }

    @Test
    fun `a sheet that is up with no queue and no song goes down, once the queues are read`() {
        assertTrue(SavedQueueShown.sheetOverNothing(queuesRead = true, queues = 0, songShown = false, sheetUp = true))
        // Not before the queues are read: the board is empty until then on every start.
        assertFalse(SavedQueueShown.sheetOverNothing(queuesRead = false, queues = 0, songShown = false, sheetUp = true))
        // A saved queue is what Play loads.
        assertFalse(SavedQueueShown.sheetOverNothing(queuesRead = true, queues = 1, songShown = false, sheetUp = true))
        // A song in the player, whatever the board says.
        assertFalse(SavedQueueShown.sheetOverNothing(queuesRead = true, queues = 0, songShown = true, sheetUp = true))
        // Already down, as after a force stop or a swipe.
        assertFalse(SavedQueueShown.sheetOverNothing(queuesRead = true, queues = 0, songShown = false, sheetUp = false))
    }

    @Test
    fun `the connection asks before it reads the saved queue`() {
        val init = connection.substringAfter("init {").substringBefore("fun togglePlayPause()")
        val asked = init.indexOf("SavedQueueShown.song(player.currentMetadata != null, service.dataStore.get(PersistentQueueKey, true))")
        val read = init.indexOf("database.getResumptionQueue(")
        assertTrue("the saved queue is read without asking whether it is shown", asked in 0 until read)
        assertTrue("one read of the saved queue", init.indexOf("database.getResumptionQueue(", read + 1) < 0)
    }

    @Test
    fun `the player's sheet goes down over nothing, by where it was sent and not where it is drawn`() {
        val rule = player.substringAfter("val sheetOverNothing = SavedQueueShown.sheetOverNothing(").substringBefore("val isLandscape")
        assertTrue(rule.contains("queuesRead = qbInit,"))
        assertTrue(rule.contains("queues = playerConnection.service.queueBoard.masterQueues.size,"))
        assertTrue(rule.contains("songShown = mediaMetadata != null,"))
        // The anchor: a sheet brought back is still drawn at the bottom for its first frames.
        assertTrue(rule.contains("sheetUp = state.anchor != dismissedAnchor,"))
        val effect = rule.substringAfter("LaunchedEffect(sheetOverNothing) {")
        assertTrue(effect.substringBefore("\n    }").contains("if (sheetOverNothing) {"))
        assertTrue(effect.substringBefore("\n    }").contains("state.dismiss()"))
    }

    @Test
    fun `with Persistent queue off the board starts with no queue`() {
        // What both rules above stand on. A board that reads the saved queues whatever the switch
        // says would need them looked at again.
        val init = service.substringAfter("suspend fun initQueue(").substringBefore("private suspend fun resumeOnLaunchIfAsked()")
        assertTrue(init.contains("val persistQueue = dataStore.get(PersistentQueueKey, true)"))
        val off = init.substringAfter("if (persistQueue) {").substringAfter("} else {").substringBefore("}")
        assertTrue(off.contains("queueBoard = QueueBoard(this, queueBoard.masterQueues, maxQueues = maxQueues)"))
        assertFalse(off.contains("readQueue"))
    }
}
