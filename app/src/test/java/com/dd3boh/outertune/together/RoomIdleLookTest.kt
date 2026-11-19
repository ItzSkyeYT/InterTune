/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A room looked at its player four times a second on the main thread for its whole life, paused or not, and a paused
 * room is often left open (performance review, 18 Nov 2026). Now it looks every two seconds while the room is paused
 * there and here, or while this phone leads, and what needs a look sooner wakes it at once.
 */
class RoomIdleLookTest {

    @Test
    fun `four looks a second while the room plays, one every two seconds while paused or leading`() {
        assertEquals(250L, lookEveryMs(leading = false, roomPlaying = true, playingHere = true))
        // the room plays and this phone not yet: it must start
        assertEquals(250L, lookEveryMs(leading = false, roomPlaying = true, playingHere = false))
        // paused there, playing here: the pause must reach it
        assertEquals(250L, lookEveryMs(leading = false, roomPlaying = false, playingHere = true))
        assertEquals(2_000L, lookEveryMs(leading = false, roomPlaying = false, playingHere = false))
        // waiting for the host's first word
        assertEquals(2_000L, lookEveryMs(leading = false, roomPlaying = null, playingHere = false))
        // the one who leads follows nobody
        assertEquals(2_000L, lookEveryMs(leading = true, roomPlaying = true, playingHere = true))
    }

    @Test
    fun `what needs a look sooner wakes it`() {
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        assertTrue("private val wake = Channel<Unit>(Channel.CONFLATED)" in session)
        val loop = session.substringAfter("if (!leading) follow()").substringBefore("publish()")
        assertTrue("val every = lookEveryMs(leading, lastState?.playing, service.exoPlayer.playWhenReady)" in loop)
        assertTrue("withTimeoutOrNull(every) { wake.receive() }" in loop)
        assertFalse("delay(SYNC_TICK_MS)" in session)
        // a play or a pause here
        assertTrue("wake.trySend(Unit)" in session.substringAfter("override fun onPlayWhenReadyChanged(").substringBefore("override fun onMediaItemTransition("))
        // a word from the leader
        assertTrue("lastState = message\n                    lastStateAt = now\n                    wake.trySend(Unit)" in session)
        // a new lead, and Catch up
        assertTrue("wake.trySend(Unit)" in session.substringAfter("private fun lead(").substringBefore("private fun publish("))
        assertTrue("wake.trySend(Unit)" in session.substringAfter("fun catchUp()").substringBefore("private fun listenerChose("))
    }
}
