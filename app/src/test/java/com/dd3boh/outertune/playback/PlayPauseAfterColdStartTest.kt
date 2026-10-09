/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play or pause goes through PlayerConnection, never straight to the player.
 *
 * After a cold start the player is empty and the song on screen comes from the saved queue. A
 * toggle on an empty player does nothing. The two play buttons loaded the saved queue first;
 * the rows, cards and tiles that stand for the current song did not, fifteen of them, so a tap
 * on the highlighted song stayed silent (seen on the emulator on 9 Oct 2026, in
 * experiments/bugs/row-tap-after-reopen). PlayerConnection loads it for all of them.
 *
 * This reads the source, as PlayOriginCoverageTest does: the next row written by copying an old
 * one compiles and plays in every test that does not start from a stopped app.
 */
class PlayPauseAfterColdStartTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The extension is the player's own toggle, and the connection the one place that may call it. */
    private val allowed = setOf("PlayerConnection.kt", "PlayerExt.kt")

    private val straightToThePlayer = Regex("""\bplayer\s*\??\.\s*togglePlayPause\s*\(""")

    @Test
    fun `nothing toggles the player without going through the connection`() {
        val found = sources.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> straightToThePlayer.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .map { (n, line) -> "${file.relativeTo(sources)}:${n + 1}: ${line.trim()}" }
        }.toList()

        assertEquals("these toggle an empty player after a cold start, and nothing happens:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `the search finds the call it is there for`() {
        assertTrue(straightToThePlayer.containsMatchIn("                        playerConnection.player.togglePlayPause()"))
        assertTrue(straightToThePlayer.containsMatchIn("player?.togglePlayPause()"))
        assertTrue(!straightToThePlayer.containsMatchIn("                        playerConnection.togglePlayPause()"))
    }

    @Test
    fun `the connection loads the saved queue into an empty player before it toggles`() {
        val connection = File(sources, "playback/PlayerConnection.kt").readText()
        val toggle = connection.substringAfter("    fun togglePlayPause() {").substringBefore("\n    }")
        val load = connection.substringAfter("    fun loadSavedQueue() {").substringBefore("\n    }")

        assertTrue("it no longer loads the saved queue:\n$load", "if (player.currentMediaItem == null) service.queueBoard.setCurrQueue()" in load)
        assertTrue("it no longer loads before it toggles:\n$toggle", toggle.indexOf("loadSavedQueue()") in 0 until toggle.indexOf("player.togglePlayPause()"))
    }

    @Test
    fun `the heart and the library button act on the song on screen, which the player may not hold yet`() {
        // The service knows the current song from the player, and after a cold start the player
        // is empty: the heart in the full player took the tap and changed nothing (Pixel 5,
        // 9 Oct 2026). The connection knows the song on screen and hands it over.
        val connection = File(sources, "playback/PlayerConnection.kt").readText()

        assertTrue("service.toggleLike(mediaMetadata.value?.id)" in connection)
        assertTrue("service.toggleLibrary(mediaMetadata.value?.id)" in connection)
    }
}
