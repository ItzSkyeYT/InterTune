/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The queue save runs on IO while the player keeps changing the queue. Walking the live list
 * crashed on 24 Sep with "Index 306 out of bounds for length 306" when replaceAll cleared it under
 * the loop, so the save is handed a copy taken on the player's thread.
 */
class MultiQueueSnapshotTest {

    private fun song(i: Int) = MediaMetadata(id = "s$i", title = "Song $i", artists = emptyList(), duration = 180, genre = null)

    @Test
    fun snapshotOutlivesChangesToTheQueue() {
        val live = MultiQueueObject(id = 7, title = "Q", queue = MutableList(306) { song(it) }, queuePos = 12, index = 0, origin = 3, runId = 99)
        val saved = live.snapshot()

        // What replaceAll does on the player's thread while the save is part way through.
        live.replaceAll(List(40) { song(1000 + it) })
        live.queuePos = 0

        assertNotSame(live.queue, saved.queue)
        assertEquals(306, saved.getSize())
        var i = 0
        while (i < saved.getSize()) {
            assertEquals("s$i", saved.queue[i].id)
            i++
        }
        assertEquals(12, saved.queuePos)
        assertEquals(3, saved.origin)
        assertEquals(99L, saved.runId)
        assertEquals(40, live.getSize())
    }
}
