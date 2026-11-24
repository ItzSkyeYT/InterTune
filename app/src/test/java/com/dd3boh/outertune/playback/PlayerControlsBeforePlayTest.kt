/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * After a restart the full player shows the saved queue while the player holds nothing. Its shuffle, repeat and seek
 * step buttons asked the player itself whether it held a song, which is no state the screen follows: they stayed greyed
 * out until play (the walks on the 0.13 integration, 19 Nov 2026). They work for the saved queue now, the first tap
 * loading it where it needs it, as skip and play already did and as the queue's own bar does (#127,
 * QueueBarBeforePlayTest).
 */
class PlayerControlsBeforePlayTest {
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
    private val controls = player.substring(
        player.indexOf("ShuffleButton("),
        player.indexOf("playerConnection.player.toggleRepeatMode()"),
    )

    @Test
    fun `no control of the full player waits for the player to hold a song`() {
        assertFalse("a control still greyed out until play", controls.contains("currentMediaItem != null"))
        // Repeat is the player's own setting: an empty player takes it, and loading the saved queue keeps it.
        assertTrue(controls.contains("enabled = true,"))
    }

    @Test
    fun `shuffle and the seek steps load the saved queue first, as skip and play do`() {
        // Shuffle belongs to the saved queue, which brings its own shuffle with it (QueueBoard.setCurrQueue): loaded on
        // the line before, then turned.
        assertTrue(Regex("""playerConnection\.loadQueueIfEmpty\(\)\n\s*playerConnection\.triggerShuffle\(\)""").containsMatchIn(controls))
        assertEquals(
            "both seek steps",
            2,
            Regex("""playerConnection\.loadQueueIfEmpty\(\)\.let \{ it\.seekTo\(it\.currentPosition [-+] seekIncrement\.millisec\) \}""")
                .findAll(controls).count(),
        )
        assertTrue(controls.contains("playerConnection.loadQueueIfEmpty().seekToPrevious()"))
        // Next goes through the connection, which loads the saved queue before it moves on (and, in a listening room,
        // takes a guest with nothing after its song its own way: RoomSkip).
        assertTrue(controls.contains("playerConnection.skipToNext()"))
        assertTrue(File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText().contains("fun skipToNext(player: Player = loadQueueIfEmpty())"))
        assertTrue(controls.contains("playerConnection.togglePlayPause()"))
    }
}
