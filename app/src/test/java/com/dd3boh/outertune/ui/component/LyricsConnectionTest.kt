/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lyrics follow the song again after the app comes back to the front (his report, 4 Nov 2026:
 * lines shared to Discord, back in the app, the song started again, and the lyrics stood still
 * until they were closed and opened).
 *
 * MainActivity makes a new PlayerConnection for every controller, which comes with every return to
 * the front, and stops the old one listening. The lyrics' two loops wait on the connection's
 * "playing": kept on the old one, a song paused when the app went away left them waiting for good.
 * Read from the source, as the flag tests are.
 */
class LyricsConnectionTest {
    private val lyrics = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()

    @Test
    fun `every loop that waits on the connection starts again with a new one`() {
        val effects = Regex("""LaunchedEffect\(([^)]*)\) \{""").findAll(lyrics).toList()
        var waiting = 0
        for ((i, effect) in effects.withIndex()) {
            val end = effects.getOrNull(i + 1)?.range?.first ?: lyrics.length
            val body = lyrics.substring(effect.range.last, end)
            if ("playerConnection.isPlaying.first" in body) {
                waiting++
                assertTrue("LaunchedEffect(${effect.groupValues[1]}) waits on the connection", "playerConnection" in effect.groupValues[1])
            }
        }
        assertTrue("both the lines' clock and the word by word line wait on it", waiting >= 2)
    }

    @Test
    fun `the activity still makes a new connection for each controller`() {
        // What the keys guard against. Should the activity keep one connection, they can go.
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("playerConnection = PlayerConnection(controllerViewModel, database)" in main)
    }
}
