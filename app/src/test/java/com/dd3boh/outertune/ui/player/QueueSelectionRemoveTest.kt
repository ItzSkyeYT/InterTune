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
 * From the 0.12.7 thank-you poll (8 Nov 2026): songs picked in the queue could not be taken out of
 * it. The queue's selection menu has Remove from queue, and it takes them as a swipe takes one.
 */
class QueueSelectionRemoveTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `each of the queue's selection headers offers it, searching or not, upright or on its side`() {
        assertEquals(3, Regex("""SelectHeader\(""").findAll(queue).count())
        assertEquals(3, Regex("""onRemoveFromQueue = removeSelected,""").findAll(queue).count())
    }

    @Test
    fun `the picked songs go as a swipe takes one, the last place first, only where a swipe is`() {
        val body = queue.substringAfter("val removeSelected").substringBefore("\n    }\n")
        assertTrue(
            "not on a locked queue, nor on another queue looked at; on the saved queue before play, where the player holds nothing (QueueRemoveBeforePlayTest)",
            "if (lockQueue || (detachedHead && !movesStoredQueue)) null" in body
        )
        assertTrue("the last place first", ".sortedDescending()" in body)
        for (step in listOf(
            "qb.removeCurrentQueueSong(place)",
            "noteRemovedByHand(song)",
            "SignalKind.REMOVED_FROM_QUEUE",
            "player.removeMediaItem(place)",
            "mutableSongs.removeAt(place)",
            "onExitSelectionMode()",
        )) assertTrue(step, step in body)
        val swipe = queue.substringAfter("SwipeToDismissBoxValue.StartToEnd ->").substringBefore("SwipeToDismissBoxValue.EndToStart ->")
        assertTrue("the swipe still takes one the same way", "qb.removeCurrentQueueSong(swipedIndex)" in swipe && "player.removeMediaItem(swipedIndex)" in swipe)
    }

    @Test
    fun `the menu shows it only when given, last of all`() {
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/SelectionSongsMenu.kt").readText()
        assertTrue("onRemoveFromQueue: (() -> Unit)? = null" in menu)
        val item = menu.indexOf("title = R.string.remove_from_queue")
        assertTrue(item > menu.indexOf("title = R.string.remove_from_playlist"))
        assertTrue("if (onRemoveFromQueue != null) {" in menu.substring(0, item))
        val header = File("src/main/java/com/dd3boh/outertune/ui/component/SelectHeader.kt").readText()
        assertTrue("onRemoveFromQueue = onRemoveFromQueue," in header)
    }
}
