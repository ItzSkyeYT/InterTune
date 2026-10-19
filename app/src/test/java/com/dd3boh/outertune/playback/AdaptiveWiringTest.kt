/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the adaptive queue is wired into the service and the queue sheet. No player runs on the
 * JVM, so these read the source, as AdaptiveQueueGoingBackTest does, and each guards one rule
 * that a later change could quietly undo.
 */
class AdaptiveWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a rule is not taken for the rule. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun body(text: String, start: String) = text.substringAfter(start).substringBefore("\n    }\n")

    private val service get() = code("playback/MusicService.kt")

    @Test
    fun `only a radio is planned, whatever the setting says`() {
        val pass = body(service, "private fun replanQueueTail(")
        assertTrue("the pass must stop at a queue with no radio seed", "if (q.playlistId == null) {" in pass)
        assertFalse("Any queue no longer reaches a playlist", "AdaptiveQueueMode.ALWAYS" in pass || "AdaptiveQueueMode.AUTOPLAY_ONLY" in pass)
        assertTrue("the setting is read as on or off", "adaptiveQueueMode.isOn" in pass)
        val fold = body(service, "fun refreshAdaptiveTail(")
        assertTrue("the fold is a radio's only", "adaptiveQueueMode.isOn && queueBoard.getCurrentQueue()?.playlistId != null" in fold)
    }

    @Test
    fun `the fold is said again on every song change and every queue load`() {
        val transition = body(service, "override fun onMediaItemTransition(")
        val refresh = transition.indexOf("refreshAdaptiveTail()")
        assertTrue("every transition, the queue just loaded included", refresh >= 0 &&
            refresh < transition.indexOf("if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {"))
        val load = body(code("playback/QueueBoard.kt"), "fun setCurrQueue(item: MultiQueueObject?")
        assertTrue("a queue loaded under the song playing makes no transition", "player.refreshAdaptiveTail()" in load)
    }

    @Test
    fun `the queue sheet counts as open only while the app is on screen, and a pass it held back runs when it closes`() {
        val player = code("ui/player/Player.kt")
        assertTrue("onScreen = onScreen," in player)
        assertTrue("screenLifecycle.isAtLeast(Lifecycle.State.STARTED)" in player)
        val flag = service.substringAfter("@Volatile var queueSheetOpen = false").substringBefore("\n\n")
        assertTrue("closing the sheet asks for the pass it held back", "if (closed) scope.launch { askAgainAfterSheet() }" in flag)
    }
}
