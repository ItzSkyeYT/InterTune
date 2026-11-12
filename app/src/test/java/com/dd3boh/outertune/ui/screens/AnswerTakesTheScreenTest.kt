/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import com.dd3boh.outertune.recognition.Recognised
import com.dd3boh.outertune.recognition.RecognitionEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The song What's playing? found comes in front of the player left open over it (his note of
 * 30 Oct 2026: "the queue should close just to show the results"), as it comes in and only then.
 */
class AnswerTakesTheScreenTest {

    private fun found() = RecognitionEngine.State.Found(
        Recognised("Lush Life (Riot Remix)", "Zara Larsson", null, null, "key"),
        candidates = emptyList(),
        certain = true,
    )

    @Test
    fun `a song the screen's own Listen once has just found takes the screen`() {
        assertTrue(answerTakesTheScreen(found(), onShow = null, own = true, continuous = false))
        // The same song found a second time is a second answer, though it equals the first.
        assertTrue(answerTakesTheScreen(found(), onShow = found(), own = true, continuous = false))
    }

    @Test
    fun `the answer on show when the screen came up leaves the player where it is`() {
        // After a rotation, or on coming back to the screen with the player open over it.
        val shown = found()
        assertFalse(answerTakesTheScreen(shown, onShow = shown, own = true, continuous = false))
    }

    @Test
    fun `Keep listening never takes the screen, nor another run's answer`() {
        assertFalse(answerTakesTheScreen(found(), onShow = null, own = true, continuous = true))
        assertFalse(answerTakesTheScreen(found(), onShow = null, own = false, continuous = false))
    }

    @Test
    fun `only a song found does`() {
        for (state in listOf(
            RecognitionEngine.State.Idle,
            RecognitionEngine.State.NoMatch,
            RecognitionEngine.State.Listening(0.5f, identifying = true),
            RecognitionEngine.State.Failed("network", heardNothing = false),
        )) {
            assertFalse(state.toString(), answerTakesTheScreen(state, onShow = null, own = true, continuous = false))
        }
    }

    @Test
    fun `the screen puts the player away for it, as the answer comes in`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/RecognitionScreen.kt").readText()
        val effect = screen.substring(screen.indexOf("LaunchedEffect(state)"))
            .substringBefore("\n    }\n")
        assertTrue("LocalPlayerSheet.current" in screen)
        assertTrue("answerTakesTheScreen(state, answerOnShow, own, continuous)" in effect)
        assertTrue("playerSheet.isExpanded" in effect)
        assertTrue("playerSheet.collapseSoft()" in effect)
        // What was on show is remembered with the screen, so a rotation does not count as an answer.
        assertTrue("var answerOnShow by remember { mutableStateOf(state as? RecognitionEngine.State.Found) }" in screen)
    }
}
