/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueToPlaylistTest {

    @Test
    fun `a queue with no title is called Queue`() {
        assertEquals("Queue", QueueToPlaylist.defaultName(null, emptyList()))
        assertEquals("Queue", QueueToPlaylist.defaultName("", emptyList()))
        assertEquals("Queue", QueueToPlaylist.defaultName("   ", emptyList()))
    }

    @Test
    fun `the translated fallback replaces Queue when the title is blank`() {
        assertEquals("File d\'attente", QueueToPlaylist.defaultName(null, emptyList(), "File d\'attente"))
        assertEquals("Queue", QueueToPlaylist.defaultName(null, emptyList(), " "))
    }

    @Test
    fun `the title is trimmed`() {
        assertEquals("My mix", QueueToPlaylist.defaultName("  My mix  ", emptyList()))
    }

    @Test
    fun `a free title is used as it is`() {
        assertEquals("Radio", QueueToPlaylist.defaultName("Radio", listOf("Queue", "Liked")))
    }

    @Test
    fun `a taken name gets a counter that climbs past the ones already used`() {
        assertEquals("Queue (2)", QueueToPlaylist.defaultName("Queue", listOf("Queue")))
        assertEquals("Queue (3)", QueueToPlaylist.defaultName("Queue", listOf("Queue", "Queue (2)")))
    }

    @Test
    fun `a gap in the numbering is filled before going higher`() {
        assertEquals("Queue (3)", QueueToPlaylist.defaultName("Queue", listOf("Queue", "Queue (2)", "Queue (4)")))
    }

    @Test
    fun `names collide regardless of case`() {
        assertEquals("Queue (2)", QueueToPlaylist.defaultName("Queue", listOf("queue")))
        assertEquals("radio (3)", QueueToPlaylist.defaultName("radio", listOf("RADIO", "Radio (2)")))
    }

    @Test
    fun `a blank title still gets a counter when Queue is taken`() {
        assertEquals("Queue (2)", QueueToPlaylist.defaultName(null, listOf("Queue")))
    }

    @Test
    fun `positions follow the order given and keep a repeated song in both places`() {
        assertEquals(
            listOf("a" to 0, "b" to 1, "a" to 2),
            QueueToPlaylist.positions(listOf("a", "b", "a"))
        )
    }

    @Test
    fun `an empty queue gives no positions`() {
        assertTrue(QueueToPlaylist.positions(emptyList()).isEmpty())
    }
}
