/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tester's video (Discord, 10 Nov 2026): three songs
 * picked in the queue before play, Remove from queue on the pill, and the selection closed over
 * the same 35 rows, twice. The sheet was showing the saved queue as the object it was seeded with
 * when it opened; the queue board had been built again since (the music service ends when the app
 * is left with nothing loaded, and a new one reads the queues from the database when the app comes
 * back), so the board's current queue was another object. Remove from queue goes to the board's:
 * the songs left the saved queue, and the rows came straight back from the sheet's old object.
 */
class QueueSeedFollowsBoardTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    /** The saved queue of the video, read from the database: 35 songs, on the first. */
    private fun read() = MultiQueueObject(id = 1, title = "early eyes", queue = MutableList(35) { song("s$it") }, shuffled = false, queuePos = 0, index = 0)

    @Test
    fun `two reads of one saved queue are two objects, and what leaves one stays in the other`() {
        val sheets = read()   // what the sheet was seeded with when it opened
        val boards = read()   // what the board holds once the service has been built again
        // Rows 5, 6 and 7 picked, the last place first, as removeSelected asks the board.
        listOf(6, 5, 4).forEach { assertTrue(boards.removeAtPlayIndex(it, currentPlayIndex = 0)) }
        assertEquals("the saved queue lost them", 32, boards.getSize())
        assertEquals("the sheet's object still counts them: 0/35 selected, 1 / 35", 35, sheets.getSize())
        assertEquals("and gives the rows back", listOf("s4", "s5", "s6"), sheets.getCurrentQueueShuffled().subList(4, 7).map { it.id })
        // The second try, on the same rows of the sheet, takes three other songs.
        listOf(6, 5, 4).forEach { boards.removeAtPlayIndex(it, currentPlayIndex = 0) }
        assertEquals(listOf("s0", "s1", "s2", "s3", "s10"), boards.getCurrentQueueShuffled().take(5).map { it.id })
    }

    @Test
    fun `the sheet takes the board's object in place of its seed as soon as they differ`() {
        // The seed can be replaced, and is told apart by identity: two reads can hold the same songs.
        assertTrue("var fallbackSeed by remember { mutableStateOf(fallBackQueue, referentialEqualityPolicy()) }" in queue)
        val reseed = queue.substringAfter("val seedReplacedBy = ").substringBefore("\n    /**")
        assertTrue("only the seed, only while it is the queue shown", "detachedHead && detachedQueue === fallbackSeed && it !== fallbackSeed" in reseed)
        assertTrue("fallbackSeed = seedReplacedBy" in reseed && "detachedQueue = seedReplacedBy" in reseed)
        assertTrue("a selection is of the old rows", "onExitSelectionMode()" in reseed)
    }
}
