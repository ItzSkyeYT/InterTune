/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The song pill's sound bars move while the slide's song sounds (his ask of 22 Nov 2026, "those
 * little bars at the bottom need to be animated"), as the app's own playing indicator does, and rest
 * with it: the sound off, the story paused with its button or held under a finger, the app out of
 * view, Remove animations on. They are the one thing in the Recap that keeps moving while a slide
 * rests, and only while there is a song to hear (RecapRestTest keeps the rest of it from beating).
 * A JVM test cannot draw, so this reads the source.
 */
class RecapSoundBarsTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")
    private val screen = File(sources, "ui/screens/recap/RecapScreen.kt").readText()

    @Test
    fun `the bars are the app's playing indicator, moving while the song sounds and resting with it`() {
        val bars = screen.substringAfter("private fun SoundBars(").substringBefore("\n}\n")
        assertTrue("PlayingIndicator(" in bars)
        assertTrue("isPlaying = sounding && !muted && !still" in bars)
        // In the pill's lemon, four of them in the room the still ones took.
        assertTrue("color = RecapInk.Lemon" in bars)
        assertTrue("size(19.dp, 16.dp)" in bars && "bars = 4" in bars)
        // The Recap is over everything: the bars are never held for being under the player.
        assertTrue("LocalHiddenByPlayer provides { false }" in bars)
        // The song sounds unless the story is held, paused with its button or away from the app (RecapSound.pause).
        assertTrue("LaunchedEffect(held, stopped, away) { if (held || stopped || away) sound.pause() else sound.resume() }" in screen)
        assertTrue("sounding = !(held || stopped || away)," in screen)
        assertTrue("SoundBars(muted, still, sounding)" in screen)
    }

    @Test
    fun `nothing else in the Recap moves with the music`() {
        val files = listOf("recap", "ui/screens/recap").flatMap { File(sources, it).listFiles()?.toList().orEmpty() }.filter { it.extension == "kt" }
        assertEquals(1, files.sumOf { Regex("""PlayingIndicator\(""").findAll(it.readText()).count() })
    }
}
