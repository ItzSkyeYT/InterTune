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
 * #127: after a restart the queue sheet shows the saved queue while the player holds nothing, and
 * every control of its bar was greyed out and did nothing. They work for that queue now, the first
 * tap loading it into the player; a queue picked from the list only to look at still leaves them off.
 */
class QueueBarBeforePlayTest {
    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val bar = sheet.substring(
        sheet.indexOf("if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off,"),
        sheet.indexOf("playerConnection.player.toggleRepeatMode()"),
    )

    @Test
    fun `the bar works for the saved queue before play, not for a queue only looked at`() {
        assertTrue(sheet.contains("val barWorks = !detachedHead || movesStoredQueue"))
        assertFalse("a control of the bar still off for the saved queue", bar.contains("!detachedHead"))
        assertEquals("shuffle, back, play, forward and repeat", 5, Regex("""enabled = barWorks,""").findAll(bar).count())
        assertTrue(bar.contains("enabled = barWorks && (canSkipPrevious || movesStoredQueue),"))
        assertTrue(bar.contains("enabled = barWorks && (canSkipNext || movesStoredQueue),"))
    }

    @Test
    fun `every control of the bar loads the saved queue first`() {
        assertTrue(bar.contains("playerConnection.loadQueueIfEmpty().seekToPrevious()"))
        assertTrue(bar.contains("playerConnection.loadQueueIfEmpty().seekToNext()"))
        assertEquals("both seek buttons", 2, Regex("""playerConnection\.loadQueueIfEmpty\(\)\.let \{ it\.seekTo\(""").findAll(bar).count())
        assertTrue(bar.contains("playerConnection.loadQueueIfEmpty()\n                                    playerConnection.triggerShuffle()"))
        // Play goes through togglePlayPause, which loads the saved queue itself.
        assertTrue(bar.contains("playerConnection.togglePlayPause()"))
        val connection = File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText()
        assertTrue(connection.contains("fun togglePlayPause() {\n        loadQueueIfEmpty().togglePlayPause()"))
    }
}
