/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's own buttons have a name for a screen reader.
 *
 * They are pictures with nothing written on them. On 9 Oct 2026 the full player on a Pixel 5 held
 * two named things, the sleep timer and the menu: shuffle, previous, play, next, repeat and the
 * heart were buttons with nothing to say, and so was that row in the queue. This reads the
 * source, as PlayOriginCoverageTest does, because a button that loses its name still compiles
 * and still works for everybody who can see it.
 */
class ControlNamesTest {
    private val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    private val row = listOf(
        ".named(shuffleName(shuffleModeEnabled))",
        ".named(stringResource(R.string.widget_previous))",
        ".named(stringResource(R.string.widget_next))",
        ".named(repeatName(repeatMode))",
    )

    @Test
    fun `the full player names shuffle, previous, play, next, repeat and the heart`() {
        for (name in row) assertTrue("Player.kt has no $name", name in player)
        assertTrue("contentDescription = playPauseName(isPlaying && playbackState != STATE_ENDED)" in player)
        assertEquals("both hearts, the round one and the one in the joined row", 2, Regex("""contentDescription = likeName\(""").findAll(player).count())
    }

    @Test
    fun `the queue names the same row, and the arrow that opens it`() {
        for (name in row) assertTrue("Queue.kt has no $name", name in queue)
        assertTrue(".named(playPauseName(isPlaying && playbackState != STATE_ENDED))" in queue)
        assertTrue("contentDescription = stringResource(R.string.queue)" in queue)
    }

    @Test
    fun `no picture of the heart is left without a name`() {
        // Each place the heart is drawn in the player is followed by its name.
        val hearts = Regex("""R\.drawable\.favorite else R\.drawable\.favorite_border\),\s*\n\s*contentDescription = (\w+)""").findAll(player).map { it.groupValues[1] }.toList()
        assertTrue("no heart found in Player.kt: has the code changed shape?", hearts.isNotEmpty())
        assertEquals(hearts.map { "likeName" }, hearts)
    }
}
