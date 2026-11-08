/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Test

/** Library > Playlists in Custom order: the order dragged, kept as the playlists' ids. */
class PlaylistOrderTest {

    // Oldest first, as the database lists them for Custom order.
    private val library = listOf("a", "b", "c", "d")

    private fun apply(saved: List<String>) = PlaylistOrder.apply(library, saved) { it }

    @Test
    fun `with nothing dragged yet, the newest first, as the library opens`() {
        assertEquals(listOf("d", "c", "b", "a"), apply(emptyList()))
    }

    @Test
    fun `the order dragged is kept, a playlist made since comes first, a deleted one is left out`() {
        assertEquals(listOf("b", "a", "c", "d"), apply(listOf("b", "a", "c", "d")))
        // d was made after the drag, x deleted since.
        assertEquals(listOf("d", "c", "a", "b"), apply(listOf("c", "x", "a", "b")))
        // Two made since: the newer first.
        assertEquals(listOf("d", "c", "b", "a"), apply(listOf("b", "a")))
    }

    @Test
    fun `a drag moves one playlist to where it was dropped`() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PlaylistOrder.moved(ids, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PlaylistOrder.moved(ids, 3, 0))
        assertEquals(ids, PlaylistOrder.moved(ids, 1, 1))
        assertEquals("a place off the list changes nothing", ids, PlaylistOrder.moved(ids, 0, 9))
    }

    @Test
    fun `the order is kept one id a line, blanks and repeats left out`() {
        val ids = listOf("LOCALabc", "VLPL123", "LOCALdef")
        assertEquals(ids, PlaylistOrder.decode(PlaylistOrder.encode(ids)))
        assertEquals(listOf("a", "b"), PlaylistOrder.decode("a\n\n b \na\n"))
        assertEquals(emptyList<String>(), PlaylistOrder.decode(null))
    }
}
