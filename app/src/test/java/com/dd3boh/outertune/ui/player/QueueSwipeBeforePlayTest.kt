/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A swipe takes a song out of the saved queue before play too, after the app came back with a song on the mini
 * player it has not started (9 Nov 2026), as Remove from queue on picked songs does since that day: the swipe is
 * what most people try first. The player holds nothing then, so it is not asked to remove anything; the saved
 * queue alone loses the song, and the mini player reads the queue again.
 */
class QueueSwipeBeforePlayTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `the swipe is there before play, on the queue that will play`() {
        assertTrue(queue.contains("if (!lockQueue && !inSelectMode && (!detachedHead || movesStoredQueue)) {\n                            SwipeToDismissBox("))
    }

    @Test
    fun `both swipes take the song from the player only when it holds the queue, and read the saved one again when not`() {
        for (side in listOf("SwipeToDismissBoxValue.StartToEnd ->" to "SwipeToDismissBoxValue.EndToStart ->", "SwipeToDismissBoxValue.EndToStart ->" to "SwipeToDismissBoxValue.Settled ->")) {
            val swipe = queue.substringAfter(side.first).substringBefore(side.second)
            assertTrue(side.first, "val loaded = playerConnection.player.mediaItemCount > 0" in swipe)
            assertTrue(side.first, "if (loaded) playerConnection.player.removeMediaItem(swipedIndex)" in swipe)
            assertTrue(side.first, "if (!loaded) playerConnection.storedQueueEdited()" in swipe)
            assertTrue("after an emptied queue has gone", swipe.indexOf("dropIfEmptied()") < swipe.indexOf("if (!loaded) playerConnection.storedQueueEdited()"))
        }
    }
}
