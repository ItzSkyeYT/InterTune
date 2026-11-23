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
 * A queue with every song taken out of it goes, as the queue list's own Delete takes one (9 Nov
 * 2026). It used to stay, empty: the sheet said "No queues" over a bar still naming it "1 / 0", the
 * mini player stayed on with no song and no cover (a tester's screenshot), and its last songs came
 * back at the next start, since an empty queue is never saved. Picking every song and Remove from
 * queue makes that one tap away.
 *
 * It still goes at once, and since 19 Nov 2026 (his pick, "just a undo toast after removing songs
 * from queue") the removal's Undo brings it back: held aside while the message shows, so nothing
 * empty is left on screen and an app closed meanwhile starts without it.
 */
class QueueEmptiedTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val drop = queue.substringAfter("val dropIfEmptied").substringBefore("\n    }\n")

    @Test
    fun `an emptied queue is deleted as the list's Delete deletes one`() {
        assertTrue("only the queue left with nothing", "getSize() == 0" in drop)
        assertTrue("nothing starts by itself", "player.pause()" in drop)
        for (step in listOf("qb.deleteQueue(", "qb.setCurrQueue()", "exitDetachHead()", "onTerminate()")) {
            assertTrue(step, step in drop)
        }
        assertTrue("the pause comes before the next queue is loaded", drop.indexOf("player.pause()") < drop.indexOf("qb.setCurrQueue()"))
        // the list's own Delete, which it follows
        val delete = queue.substringAfter("qb.deleteQueue(mq)").substringBefore("Text(").let { "qb.deleteQueue(mq)$it" }
        assertTrue("qb.deleteQueue(mq)" in delete && "qb.setCurrQueue()" in delete && "onTerminate.invoke()" in delete)
    }

    @Test
    fun `both ways of taking songs out end with it`() {
        val picked = queue.substringAfter("val removeSelected").substringBefore("\n    }\n")
        assertTrue("Remove from queue", "dropIfEmptied()" in picked)
        assertTrue("before the mini player is read again, which then finds no queue", picked.indexOf("dropIfEmptied()") < picked.indexOf("storedQueueEdited()"))
        val swipe = queue.substringAfter("val removeSwiped: () -> Boolean = swipe@{").substringBefore("val onCheckedChange")
        assertTrue("a swipe", "dropIfEmptied()" in swipe)
    }

    @Test
    fun `the queue that went is held for its Undo, which brings it back as it stood, playing if it played`() {
        // What went, emptied, and the queue current after it (null with none left).
        assertTrue("QueueUndo.Dropped(emptied, qb.getCurrentQueue()?.id)" in drop)
        // Both ways keep the queue as it stood, read before the first song goes, when they take every song it has.
        val picked = queue.substringAfter("val removeSelected").substringBefore("\n    }\n")
        val swipe = queue.substringAfter("val removeSwiped: () -> Boolean = swipe@{").substringBefore("val onCheckedChange")
        for ((way, keep) in listOf(picked to "whole = wholeBefore(places.size)", swipe to "whole = wholeBefore(1)")) {
            assertTrue(keep, keep in way)
            assertTrue(keep, way.indexOf(keep) < way.indexOf("qb.removeCurrentQueueSong("))
            assertTrue(keep, "notes.dropped = dropIfEmptied()" in way)
        }
        val whole = queue.substringAfter("val wholeBefore").substringBefore("\n    }\n")
        assertTrue("only a removal of every song it has", "it.getSize() in 1..count" in whole)
        assertTrue("where the player was, and whether it played", "player.currentPosition" in whole && "player.playWhenReady" in whole)
        // The message offers Undo for it, and Undo brings it back whole instead of putting songs back into a queue.
        val offer = queue.substringAfter("val offerUndo").substringBefore("// Undo ends here")
        assertTrue("notes.whole?.takeIf { notes.dropped?.queue?.id == queueId }" in offer)
        assertTrue("bringBackDropped(dropped, whole, taken, notes)" in offer)
        val back = queue.substringAfter("val bringBackDropped").substringBefore("\n    }\n")
        assertTrue("not over music put on meanwhile", "if (qb.getCurrentQueue()?.id != dropped.after) return@back" in back)
        assertTrue(back.indexOf("QueueUndo.refill(dropped.queue, whole)") in 0 until back.indexOf("qb.bringBack(dropped.queue)"))
        assertTrue("plays on only if it was playing", "if (whole.playing) {" in back)
        assertTrue("for (removal in notes.removals.asReversed()) playerConnection.service.notePutBackByHand(removal)" in back)
        assertTrue("ActivityLog.takeBack(database, t.song.id, SignalKind.REMOVED_FROM_QUEUE, notes.at)" in back)
        // The board takes it back: saved again, then made current and loaded on its song; never twice.
        val board = File("src/main/java/com/dd3boh/outertune/playback/QueueBoard.kt").readText()
        val bring = board.substringAfter("fun bringBack(q: MultiQueueObject): Boolean {").substringBefore("\n    }\n")
        assertTrue("masterQueues.any { it.id == q.id || it.title == q.title }" in bring)
        assertTrue("masterQueues.size >= maxQueues" in bring)
        assertTrue(bring.indexOf("saveQueueSongs(q)") in 0 until bring.indexOf("setCurrQueue(q)"))
    }
}
