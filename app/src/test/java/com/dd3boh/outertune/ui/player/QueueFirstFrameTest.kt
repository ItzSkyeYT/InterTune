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
 * The queue's sheet opens with its lists in place. The list of queues was filled by an effect that ran a frame after
 * the first, so an opening on a phone's side began with the right half empty and then its rows came, the queue playing
 * lit last (a tester's "that looked confused at first", 15 Nov 2026). This reads the source, because a frame needs a screen.
 */
class QueueFirstFrameTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `the list of queues and the queue playing are known as the sheet is composed`() {
        assertTrue("val queuesKnown by playerConnection.service.qbInit.collectAsState()" in queue)
        assertTrue("addAll(qb.getAllQueues().distinctBy { it.id })" in queue.substringAfter("val queuesKnown").substringBefore("var playingQueue"))
        assertTrue("var playingQueue by remember { mutableIntStateOf(if (queuesKnown) qb.getAllQueues().indexOf(qb.getCurrentQueue()) else -1) }" in queue)
    }

    @Test
    fun `the effect still keeps them up to date, and never two queues with the same id`() {
        val effect = queue.substringAfter("Trigger loading queue. init = ")
        assertTrue("mutableQueues.clear()" in effect)
        assertTrue("mutableQueues.addAll(qb.getAllQueues().distinctBy { it.id })" in effect)
        assertTrue("playingQueue = updatedList.indexOf(qb.getCurrentQueue())" in effect)
    }
}
