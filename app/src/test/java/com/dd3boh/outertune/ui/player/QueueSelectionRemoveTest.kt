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
 * From the 0.12.7 thank-you poll (8 Nov 2026): songs picked in the queue could not be taken out of
 * it. The queue's selection menu has Remove from queue, and it takes them as a swipe takes one. The
 * pill that shows the selection is the lists' own, over the queue's bar (10 Nov 2026).
 */
class QueueSelectionRemoveTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    @Test
    fun `the queue's one pill offers it, searching or not, upright or on its side`() {
        // One pill for every case, as the lists have one: not a header per layout.
        assertEquals(1, Regex("""SelectHeader\(""").findAll(queue).count())
        assertEquals(1, Regex("""onRemoveFromQueue = removeSelected,""").findAll(queue).count())
    }

    @Test
    fun `the queue picks songs in the lists' own pill, not a copy of it, and the bar keeps its info row`() {
        // From the 0.12.7 testers (Ti-Die, 10 Nov 2026): picking songs in the queue replaced the bar's
        // top row (the queue's name, its place and its length) with the selection, where every other
        // list floats a pill. The same widget, in the same place.
        assertEquals(1, Regex("""FloatingFooter\(""").findAll(queue).count())
        val pill = queue.substringAfter("val selectionPill").substringBefore("// finally render ui")
        assertTrue("FloatingFooter(" in pill && "SelectHeader(" in pill)
        assertTrue("no pill of its own: the shared one carries it", "Surface(" !in pill && "RoundedCornerShape" !in pill)
        assertTrue("shown with the selection", "visible = inSelectMode," in pill)
        // Drawn last of all, over the list and the bar, whichever layout is up.
        assertTrue(queue.substringAfter("// Over everything else").substringAfter("\n").trim().startsWith("selectionPill()"))
        // The info row has no selection in it any more: the title, the place and the length stay.
        val info = queue.substringAfter("// queue info\n").substringBefore("// player controls")
        assertTrue("the queue's title", "QueueStart.shownTitle(" in info)
        assertTrue("its place", "fun getQueuePositionStr()" in info)
        assertTrue("its length", "queueLengthText(getQueueLengths())" in info)
        assertTrue("picking songs does not replace any of it", "inSelectMode" !in info && "SelectHeader" !in info)
    }

    @Test
    fun `the pill floats over the bar by the bar's own height, glass or not, and the other lists are as they were`() {
        // The bar reports its height itself, so the classic bar and the glass pane both lift the pill.
        val nav = queue.substringAfter("val bottomNav").substringBefore("// queue info")
        assertTrue(".onSizeChanged { barHeight = with(density) { it.height.toDp() } }" in nav)
        assertEquals(1, Regex("""barHeight = with\(density\)""").findAll(queue).count())
        val pill = queue.substringAfter("val selectionPill").substringBefore("// finally render ui")
        assertTrue("lift = if (overBar) barHeight else 0.dp," in pill)
        assertTrue("upright and not searching the bar stands under the songs", "val overBar = !landscape && !isSearching" in pill)
        // The shared footer: its extra room is optional, and no other page passes any.
        val footer = File("src/main/java/com/dd3boh/outertune/ui/component/FloatingFooter.kt").readText()
        assertTrue("insets: WindowInsets = LocalPlayerAwareWindowInsets.current," in footer)
        assertTrue("lift: Dp = 0.dp," in footer)
        assertTrue(".align(Alignment.BottomCenter)" in footer)
        val others = File("src/main/java/com/dd3boh/outertune").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Queue.kt" && it.name != "FloatingFooter.kt" }
        for (file in others) {
            val text = file.readText()
            var at = text.indexOf("FloatingFooter(")
            while (at >= 0) {
                val brace = text.indexOf("{", at)
                val call = text.substring(at, if (brace < 0) text.length else brace)
                assertTrue("${file.name} keeps the pages' own insets and room: $call", "insets" !in call && "lift" !in call)
                at = text.indexOf("FloatingFooter(", at + 1)
            }
        }
    }

    @Test
    fun `the picked songs go as a swipe takes one, the last place first, only where a swipe is`() {
        val body = queue.substringAfter("val removeSelected").substringBefore("\n    }\n")
        assertTrue(
            "not on a locked queue, nor on another queue looked at; on the saved queue before play, where the player holds nothing (QueueRemoveBeforePlayTest)",
            "if (lockQueue || (detachedHead && !movesStoredQueue)) null" in body
        )
        assertTrue("the last place first", ".sortedDescending()" in body)
        for (step in listOf(
            "qb.removeCurrentQueueSong(place)",
            "noteRemovedByHand(song)",
            "SignalKind.REMOVED_FROM_QUEUE",
            "player.removeMediaItem(place)",
            "mutableSongs.removeAt(place)",
            "onExitSelectionMode()",
        )) assertTrue(step, step in body)
        val swipe = queue.substringAfter("val removeSwiped: () -> Boolean = swipe@{").substringBefore("val onCheckedChange")
        assertTrue("the swipe still takes one the same way", "qb.removeCurrentQueueSong(swipedIndex)" in swipe && "player.removeMediaItem(swipedIndex)" in swipe)
    }

    @Test
    fun `the menu shows it only when given, last of all`() {
        val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/SelectionSongsMenu.kt").readText()
        assertTrue("onRemoveFromQueue: (() -> Unit)? = null" in menu)
        val item = menu.indexOf("title = R.string.remove_from_queue")
        assertTrue(item > menu.indexOf("title = R.string.remove_from_playlist"))
        assertTrue("if (onRemoveFromQueue != null) {" in menu.substring(0, item))
        val header = File("src/main/java/com/dd3boh/outertune/ui/component/SelectHeader.kt").readText()
        assertTrue("onRemoveFromQueue = onRemoveFromQueue," in header)
    }

    @Test
    fun `the selection pill has it as a button of its own, at its end, where nothing moves for it`() {
        // Asked for on 9 Nov 2026: "the remove from queue button needs to be easily accessible, not in a ... menu".
        val header = File("src/main/java/com/dd3boh/outertune/ui/component/SelectHeader.kt").readText()
        val button = header.indexOf("onClick = onRemoveFromQueue,")
        assertTrue("a tap on the bar removes the songs picked", button > 0)
        assertTrue("named for TalkBack", "contentDescription = stringResource(R.string.remove_from_queue)" in header.substring(button))
        assertTrue("nothing to remove, nothing to tap", "enabled = selectedItems.isNotEmpty()," in header.substring(button))
        // After close, the last of the row's buttons, so the count, the menu, select all and close stay put.
        assertTrue(button > header.indexOf("contentDescription = stringResource(R.string.cancel_selection)"))
        assertTrue("only where the queue gives it", "if (onRemoveFromQueue != null) {" in header.substring(0, button))
    }
}
