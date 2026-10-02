/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A queue made with Add to queue says where it began, so its listens no longer read "not
 * recorded" on How it's doing. See MultiQueueObject.startedByHand, which QueueBoard.addQueue calls
 * for the new queue or the one it adds to.
 */
class QueueStartedByHandTest {

    private fun queue(origin: Int = 0, runId: Long = 0) =
        MultiQueueObject(id = 1, title = "Q", queue = mutableListOf(), index = 0, origin = origin, runId = runId)

    @Test
    fun `a new queue made by hand says queue and starts a run`() {
        val q = queue()
        q.startedByHand(PlayOrigin.QUEUE, now = 1_000L)
        assertEquals(PlayOrigin.QUEUE.code, q.origin)
        assertEquals(1_000L, q.runId)
    }

    @Test
    fun `an old queue that said nothing takes it when picked by hand`() {
        // Queue "Africa" on the emulator: made with Create queue on 28 Sep, origin 0, and still
        // making listens with no origin on 1 Oct.
        val q = queue(origin = 0, runId = 500L)
        q.startedByHand(PlayOrigin.QUEUE, now = 1_000L)
        assertEquals(PlayOrigin.QUEUE.code, q.origin)
        assertEquals(1_000L, q.runId)
    }

    @Test
    fun `a queue that already says where it began keeps that`() {
        val q = queue(origin = PlayOrigin.ALBUM.code, runId = 500L)
        q.startedByHand(PlayOrigin.QUEUE, now = 1_000L)
        assertEquals(PlayOrigin.ALBUM.code, q.origin)
        assertEquals(500L, q.runId)
    }

    @Test
    fun `no origin changes nothing`() {
        // playQueue and the car's lists set the origin themselves, after addQueue.
        val q = queue()
        q.startedByHand(null, now = 1_000L)
        assertEquals(PlayOrigin.UNKNOWN.code, q.origin)
        assertEquals(0L, q.runId)
    }
}
