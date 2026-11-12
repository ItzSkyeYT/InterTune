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
        assertTrue(queue.contains("if (!lockQueue && !inSelectMode && (!detachedHead || movesStoredQueue)) {\n                            SwipeToRemoveBox("))
    }

    @Test
    fun `a swipe takes the song from the player only when it holds the queue, and reads the saved one again when not`() {
        // One function for both sides, since the swipe goes either way (the row's swipe box asks for it
        // once the finger has let go: SwipeToRemoveBox).
        val swipe = queue.substringAfter("val removeSwiped: () -> Boolean = swipe@{").substringBefore("val onCheckedChange")
        assertTrue("val loaded = playerConnection.player.mediaItemCount > 0" in swipe)
        assertTrue("if (loaded) playerConnection.player.removeMediaItem(swipedIndex)" in swipe)
        assertTrue("if (!loaded) playerConnection.storedQueueEdited()" in swipe)
        assertTrue("after an emptied queue has gone", swipe.indexOf("dropIfEmptied()") < swipe.indexOf("if (!loaded) playerConnection.storedQueueEdited()"))
    }
}
