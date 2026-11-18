/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.ui.component.SwipeToRemoveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #141, Ti-Die's extra (11 Nov 2026): "instead of having a remove from queue button when multiple songs selected
 * (tester build), allow swiping away an entire selection, the same way you'd do with one song". While songs are
 * picked in the queue, a swipe on a picked row moves every picked row on screen as one, each with the bin behind it,
 * and they go together when the finger lets go past the line, with Undo for all of them. A row not picked does not
 * swipe then. Held back behind Unreleased.QUEUE_SWIPE_PICKED. Read from the source, as the queue's other tests are.
 */
class QueueSwipePickedTest {
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()

    /** The row's swipe, from the line that says whether it takes the picked songs to the end of its call. */
    private val rowSwipe = queue.substringAfter("val swipesPicked =").substringBefore("} else {\n                            content()")

    /** What a swipe on a picked row asks for once the finger has let go and the rows have slid out. */
    private val picked = queue.substringAfter("val removePickedSwiped: () -> Boolean = swipe@{").substringBefore("\n    }\n")

    @Test
    fun `held back for the debug app, the Preview and the Tester app`() {
        assertTrue("val QUEUE_SWIPE_PICKED = BuildConfig.PREVIEW_FEATURES" in flags)
    }

    @Test
    fun `a picked row swipes the whole selection, a row not picked does not, and out of a selection nothing changed`() {
        assertTrue("Unreleased.QUEUE_SWIPE_PICKED && inSelectMode && window.hashCode() in selectedItems" in rowSwipe)
        assertTrue("onRemove = if (swipesPicked) removePickedSwiped else removeSwiped," in rowSwipe)
        // The picked rows share one swipe, so they move together; any other row has its own, as before.
        assertTrue("state = if (swipesPicked || window.hashCode() in pickedLeaving) pickedSwipe else ownSwipe," in rowSwipe)
        assertTrue("enabled = !inSelectMode || swipesPicked," in rowSwipe)
        // Without the flag no row swipes while songs are picked: the box is not there, as before.
        assertTrue("if (!lockQueue && (!inSelectMode || Unreleased.QUEUE_SWIPE_PICKED) && (!detachedHead || movesStoredQueue)) {" in queue)
        assertEquals("one swipe box for every row", 1, Regex("""SwipeToRemoveBox\(""").findAll(queue).count())
    }

    @Test
    fun `the selection goes the way the menu's Remove from queue takes it, Undo and all`() {
        // The very same action (QueueSelectionRemoveTest): offered where it is, the last place first, the signals,
        // the bar with Undo, the selection closed. No second copy of the removal, which AdaptiveWiringTest counts.
        assertTrue("val remove = removeSelected ?: return@swipe false" in picked)
        assertTrue("remove()" in picked)
        assertFalse("removeCurrentQueueSong" in picked)
        // Yes only when songs went: if none could go, the rows slide back instead of staying slid out.
        assertTrue("val went = mutableSongs.size < before" in picked)
        assertTrue(picked.trim().endsWith("went"))
    }

    @Test
    fun `the rows it took stay slid out on their way out, and it rests again for the next selection or an Undo`() {
        // Marked before the removal, which clears the selection.
        val marked = picked.indexOf("pickedLeaving = selectedItems.toSet()")
        assertTrue(marked >= 0 && marked < picked.indexOf("remove()"))
        val rests = queue.substringAfter("val pickedSwipeRests = {").substringBefore("}")
        assertTrue("pickedLeaving = emptySet()" in rests && "pickedSwipe.rest()" in rests)
        // A second after, at an Undo, and when a new selection starts.
        assertTrue("LaunchedEffect(pickedLeaving) {\n        if (pickedLeaving.isEmpty()) return@LaunchedEffect\n        delay(1_000)\n        pickedSwipeRests()" in queue)
        assertTrue("pickedSwipeRests()" in queue.substringAfter("val putBackTaken").substringBefore("for (at in QueueUndo.insertions("))
        assertTrue("pickedSwipeRests()" in queue.substringAfter("onLongClick = {").substringBefore("inSelectMode = true"))
    }

    @Test
    fun `a shared swipe comes back to rest`() {
        val state = SwipeToRemoveState()
        state.offset = -640f
        state.ready = true
        state.rest()
        assertEquals(0f, state.offset, 0f)
        assertFalse(state.ready)
    }
}
