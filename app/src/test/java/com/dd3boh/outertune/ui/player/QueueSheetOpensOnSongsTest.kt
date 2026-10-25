/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue opens on its songs. After an update or a restart, before anything plays, the player
 * holds nothing, and the sheet used to open with the list of queues spread over the songs; a tap on
 * the queue playing then only left the preview and kept the list open, and that preview showed every
 * song ahead even with the Auto queue folding the far end away.
 */
class QueueSheetOpensOnSongsTest {
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `the list of queues opens from its button only`() {
        assertTrue(queue.contains("var mqExpand by remember { mutableStateOf(false) }"))
        // Its only other writes: the button's toggles and closing it.
        assertEquals(0, Regex("""mqExpand = true""").findAll(queue).count())
    }

    @Test
    fun `a tap on the queue playing closes the list and shows its songs`() {
        val tap = queue.substringAfter("// Already the playing queue, so there is nothing to switch").substringBefore("} else {\n                                        coroutineScope")
        assertTrue(tap, tap.contains("mqExpand = false"))
        // With the player still empty, the songs stay on the preview of this queue rather than a
        // list of songs the player does not have.
        assertTrue(tap, tap.contains("if (queueWindows.isEmpty())"))
        assertTrue(tap, tap.contains("detachedQueue = mq"))
    }

    @Test
    fun `the preview of the queue playing folds its far end away like the live queue`() {
        assertTrue(queue.contains("val foldTail = adaptiveTailActive && !isSearching && (detachedQueue == null || previewOfPlaying)"))
        assertTrue(queue.contains("if (!foldTail) mutableSongs"))
        assertTrue(queue.contains("detachedQueue?.let { AdaptiveQueue.settledThrough(it.getQueuePosShuffled(), mutableSongs, { it.id }, reachedInQueue) }"))
    }

    @Test
    fun `a restored queue says at once whether its far end is folded`() {
        val loaded = service.substringAfter("qbInit.value = true").substringBefore("queuesLoadedOnce.complete(Unit)")
        assertTrue(loaded, loaded.contains("refreshAdaptiveTail()"))
    }
}
