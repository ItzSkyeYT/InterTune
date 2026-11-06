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
 * A playlist shuffled again keeps its shuffle after the app is closed in the background (#87, 6 Nov
 * 2026). Shuffling a playlist whose queue was already there with the same songs took the "jump only"
 * way through QueueBoard.addQueue: the new order was made in memory and only the queue's own row was
 * saved (saveQueue writes the shuffle flag and the place, not each song's place in the shuffle). The
 * songs' order reached the database only at a clean shutdown, so after the system closed the app the
 * queue came back marked shuffled, in the playlist's order.
 *
 * QueueBoard needs a live MusicService and cannot be built in a unit test, so this reads its source.
 */
class QueueShuffleSavedTest {
    private val board = File("src/main/java/com/dd3boh/outertune/playback/QueueBoard.kt").readText()

    private val addQueue by lazy {
        board.substringAfter("    fun addQueue(").substringBefore("     * Add songs to end of CURRENT QUEUE")
    }

    @Test
    fun `a playlist shuffled again saves its songs' new order, not only the queue's row`() {
        val jumpOnly = addQueue.substringAfter("Adding to queue: jump only").substringBefore("} else if (delta)")
        assertTrue(jumpOnly, jumpOnly.contains("shuffle(match, startFirst, true)"))
        assertTrue(jumpOnly, jumpOnly.contains("if (shuffled) saveQueueSongs(match) else saveQueue(match)"))
    }

    @Test
    fun `every way through addQueue that shuffles saves the songs after`() {
        // shuffle(..., bypassSaveToDb = true) leaves the save to its caller, and saveQueue alone
        // would keep the old order in the database.
        for (way in addQueue.split("Adding to queue: ").drop(1)) {
            if ("shuffle(" !in way) continue
            assertTrue(way.take(80), "saveQueueSongs(" in way)
        }
    }
}
