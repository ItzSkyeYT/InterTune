/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queue's Undo message stays its ten seconds when the queue is put down (review of 0.13's gaps, 18 Nov 2026).
 * It was shown from the queue sheet's own scope, and the sheet's content is there only while it is open: a drag
 * down, Back or closing the player cancelled the scope, material3 cleared the message at once, and the songs could
 * no longer be put back. It is shown from the app's scope now, as Saved twice's Undo is, and Undo works on the queue
 * board and the player. Read from the source, as the other tests of the sheet do.
 */
class QueueUndoOutlivesSheetTest {
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    @Test
    fun `the sheet's content goes when it is put down, so nothing that must outlive it can run in its scope`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/component/BottomSheet.kt").readText()
        assertTrue("        // main\n        if (!state.isCollapsed) {" in sheet)
    }

    @Test
    fun `the message is shown from the app's scope, the activity's own`() {
        val offer = queue.substringAfter("val offerUndo").substringBefore("// Undo ends here")
        assertTrue("appScope.launch {" in offer)
        assertFalse("not from the sheet's scope", "coroutineScope.launch" in offer)
        assertTrue("val appScope = LocalAppScope.current" in queue)
        // The same scope the nav graph has as appScope, where Saved twice's take out and Undo run.
        assertEquals(1, Regex("""val coroutineScope = rememberCoroutineScope\(\)""").findAll(main).count())
        assertTrue("LocalAppScope provides coroutineScope," in main)
        assertTrue("appScope = coroutineScope," in main)
    }

    @Test
    fun `Undo puts back through the board and the player, and the sheet's own rows only while it is there`() {
        val back = queue.substringAfter("val putBackTaken").substringBefore("val offerUndo")
        assertTrue("val held = qb.putBack(queue, at.place, at.song)" in back)
        assertTrue("if (loaded) playerConnection.player.addMediaItem(at.place, held.toMediaItem())" in back)
        val rows = back.lines().filter { "mutableSongs" in it }
        assertTrue(rows.isNotEmpty())
        for (line in rows) assertTrue(line, line.trim().startsWith("if (sheetHere[0]) "))
        assertTrue("DisposableEffect(Unit) { onDispose { sheetHere[0] = false } }" in queue)
    }
}
