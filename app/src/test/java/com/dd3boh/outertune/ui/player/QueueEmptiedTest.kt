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
}
