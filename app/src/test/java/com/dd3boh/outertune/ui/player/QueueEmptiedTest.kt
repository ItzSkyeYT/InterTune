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
 * back at the next start, since an empty queue is never saved. In 0.12.x songs leave the queue by a
 * swipe only: Remove from queue on a selection came with 0.13.
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
    fun `a swipe to either side ends with it`() {
        for (side in listOf("SwipeToDismissBoxValue.StartToEnd ->" to "SwipeToDismissBoxValue.EndToStart ->", "SwipeToDismissBoxValue.EndToStart ->" to "SwipeToDismissBoxValue.Settled ->")) {
            val swipe = queue.substringAfter(side.first).substringBefore(side.second)
            assertTrue("a swipe ${side.first}", "dropIfEmptied()" in swipe)
        }
    }
}
