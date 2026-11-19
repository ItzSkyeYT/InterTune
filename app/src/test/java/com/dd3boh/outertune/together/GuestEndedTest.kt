/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest whose player has ended on the host's song (back in the room after playing it to its end, or the host gone
 * back in it) stayed silent under "Following the host": the look went on only for a player ready or buffering, and an
 * ended player never moves by itself (the walks on the 0.13 integration, 19 Nov 2026; Play, then Catch up, got it
 * back). It now goes back to the host's spot, unless the host is about to move on to its next song.
 */
class GuestEndedTest {

    @Test
    fun `an ended guest goes back to the host's spot while the host is still in the song`() {
        assertEquals(95_000L, Follow.wake(95_000, 240_000))
        // A spot just before the start, from a host on Bluetooth, is the start.
        assertEquals(0L, Follow.wake(-120, 240_000))
        // The song's length not known: the host's spot all the same.
        assertEquals(95_000L, Follow.wake(95_000, null))
    }

    @Test
    fun `near the end it waits for the host's next song`() {
        assertEquals(240_000L - Follow.END_GAP_MS, Follow.wake(240_000 - Follow.END_GAP_MS, 240_000))
        assertNull(Follow.wake(240_000 - Follow.END_GAP_MS + 1, 240_000))
        assertNull(Follow.wake(240_000, 240_000))
        assertNull(Follow.wake(250_000, 240_000))
    }

    @Test
    fun `the look wakes an ended player before it gives up on one neither ready nor buffering`() {
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        val follow = session.substringAfter("private fun follow()").substringBefore("\n    private fun ")
        val ended = follow.indexOf("player.playbackState == Player.STATE_ENDED")
        val gaveUp = follow.indexOf("player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return")
        assertTrue("follow() looks at an ended player", ended >= 0)
        assertTrue("before it returns for a player neither ready nor buffering", gaveUp > ended)
        assertTrue("and takes the spot from Follow.wake", follow.contains("Follow.wake("))
    }
}
