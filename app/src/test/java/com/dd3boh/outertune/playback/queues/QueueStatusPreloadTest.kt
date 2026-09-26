/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.queues

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the song already playing lands once YouTube's answer for the rest of the queue arrives. */
class QueueStatusPreloadTest {

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = -1, genre = null)

    private fun ids(result: Pair<List<MediaMetadata>, Int>) = result.first.map { it.id } to result.second

    @Test
    fun `a radio that starts with the tapped song keeps it first`() {
        val status = Queue.Status(null, listOf(song("a"), song("b"), song("c")), mediaItemIndex = 0)
        val tapped = song("a")
        val result = status.withPreload(tapped)
        assertEquals(listOf("a", "b", "c") to 0, ids(result))
        // The tapped copy, not YouTube's, since it is the one already playing.
        assertEquals(tapped, result.first[0])
    }

    @Test
    fun `a list that starts further down keeps its first song and holds the tapped one once`() {
        val status = Queue.Status(null, listOf(song("a"), song("b"), song("c")), mediaItemIndex = 2)
        val result = status.withPreload(song("c"))
        assertEquals(listOf("a", "b", "c") to 2, ids(result))
    }

    @Test
    fun `YouTube's own version of the song gives way to the one playing, in its place`() {
        // A video tapped, its audio-only counterpart marked as playing.
        val status = Queue.Status(null, listOf(song("x"), song("c-song")), mediaItemIndex = 1)
        assertEquals(listOf("x", "c-video") to 1, ids(status.withPreload(song("c-video"))))
    }

    @Test
    fun `no preload leaves the list alone`() {
        val status = Queue.Status(null, listOf(song("a"), song("b")), mediaItemIndex = 1)
        assertEquals(listOf("a", "b") to 1, ids(status.withPreload(null)))
    }

    @Test
    fun `an index past either end is held to the list`() {
        val status = Queue.Status(null, listOf(song("a"), song("b")), mediaItemIndex = 5)
        assertEquals(listOf("a", "t") to 1, ids(status.withPreload(song("t"))))
        val negative = Queue.Status(null, listOf(song("a"), song("b")), mediaItemIndex = -1)
        assertEquals(listOf("t", "b") to 0, ids(negative.withPreload(song("t"))))
    }
}
