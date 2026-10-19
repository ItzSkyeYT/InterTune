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

    @Test
    fun `the pass that puts picks in is worked out off the main thread and applied only to what it was planned for`() {
        val pass = body(service, "private suspend fun runPass(")
        assertTrue("withContext(adaptiveDispatcher) { computePass(snap) }" in pass)
        assertTrue("a cancelled pass is not swallowed", "catch (e: CancellationException) {\n            throw e" in pass)
        assertTrue("a pass a newer ask cancelled never applies", "currentCoroutineContext().ensureActive()" in pass)
        val apply = body(service, "private fun applyPass(")
        assertTrue("the sheet is asked again at apply", "if (queueSheetOpen) {" in apply)
        assertTrue("and casting", "if (isCasting) return abandon(\"casting\")" in apply)
        assertTrue("and the queue and its run", "q.id != snap.queueId || q.runId != snap.runId" in apply)
        assertTrue("and the song playing", "activePlayer.currentMediaItemIndex != snap.current" in apply)
        assertTrue("a pick the queue or the run holds by now is left out", "fun stillNew(id: String)" in apply)
        for (name in listOf("computePass", "listFor", "tasteFor")) {
            assertFalse("$name shares no stall guard with the player's own reads", "readOrNull" in body(service, "fun $name("))
        }
        assertTrue("nothing stored is picked while explicit songs are hidden", "if (explicitHidden) return null" in body(service, "private fun listFor("))
        assertTrue("one flag holds the whole pass back", "if (Unreleased.ADAPTIVE_PASS) {" in body(service, "private fun replanQueueTail("))
    }

    @Test
    fun `a run is noted by its run, and a move made by skip on error is no skip`() {
        val transition = body(service, "override fun onMediaItemTransition(")
        assertTrue("runId = q?.runId ?: 0L, radio = q?.playlistId != null," in transition)
        assertTrue("fromError -> AdaptiveQueue.Leave.NEITHER" in transition)
        assertTrue("only the leave just read makes a skip pass", "skip = leftAs == AdaptiveQueue.Leave.SKIPPED)" in transition)
        assertTrue("a tap on another song is no skip", "!transitionWentBack && !transitionChosen" in transition)
        val skip = body(service, "fun skipOnError(")
        assertTrue(skip.indexOf("skippingOnError = true") in 0 until skip.indexOf("activePlayer.seekTo(nextWindowIndex, C.TIME_UNSET)"))
    }

    @Test
    fun `songs put in, moved or swiped by hand are noted`() {
        assertTrue("noteByHand(q, items)" in body(service, "fun enqueueNext("))
        assertTrue("queueBoard.getCurrentQueue()?.let { noteByHand(it, items) }" in body(service, "fun enqueueEnd("))
        val sheet = code("ui/player/Queue.kt")
        assertEquals("both swipes", 2, Regex("""playerConnection\.service\.noteRemovedByHand\(it\)""").findAll(sheet).count())
        assertTrue("playerConnection.service.noteMovedByHand(it)" in sheet)
    }

    @Test
    fun `the note is said under the songs on show, not over them`() {
        val sheet = code("ui/player/Queue.kt")
        assertFalse("R.string.adaptive_queue_note)" in sheet || "R.string.adaptive_queue_note_detail" in sheet)
        assertTrue("isNew = adaptiveSaying && window.id in adaptivePicks," in sheet)
        assertTrue("text = note?.let { adaptiveNoteText(it) } ?: stringResource(R.string.adaptive_tail_title)," in sheet)
    }

    @Test
    fun `the learning rules are no longer a flag, and the new pass is read in one place`() {
        val flags = code("constants/Unreleased.kt")
        assertFalse("ADAPTIVE_LEARNS" in flags)
        assertTrue("val ADAPTIVE_PASS = BuildConfig.DEBUG" in flags)
        val readers = sources.walkTopDown().filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.ADAPTIVE_PASS" in code(it.relativeTo(sources).path) }.map { it.name }.toList()
        assertEquals(listOf("MusicService.kt"), readers)
    }
}
