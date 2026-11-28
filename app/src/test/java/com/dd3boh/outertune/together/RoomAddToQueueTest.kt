/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest's Add to queue in a listening room goes to the end of the room's queue (his word of 27 Nov 2026, Spotify's
 * Jam; 0.13's notes). Every menu's Add to queue opens the phone's own choice of queues (AddToQueueDialog), and it wrote
 * into the room's copy of the queue, put back without a word by the room's next queue, or into the queue from before
 * the room, out of sight (28 Nov 2026, 3 times out of 3). The choice decides by the room as it opens, as Play next
 * does, so no menu goes round it. The host, a guest on its own way and a phone in no room choose a queue as before.
 */
class RoomAddToQueueTest {

    private val root = File("src/main/java/com/dd3boh/outertune")

    private fun main(path: String) = File(root, path).readText()

    @Test
    fun `the choice of queues sends a guest's songs to the end of the room's queue, and lists no queue`() {
        val dialog = body(main("ui/dialog/QueueDialogs.kt"), "fun AddToQueueDialog(")
        val asked = dialog.indexOf("val guest = remember { playerConnection?.takeIf { it.addsToRoom } }")
        assertTrue(dialog, asked >= 0)
        // Before the phone's queues are read or shown: none of them is the room's.
        for (later in listOf("getAllQueues()", "ListDialog(", "TextFieldDialog(")) {
            assertTrue(later, dialog.indexOf(later) > asked)
        }
        val guest = body(dialog, "if (guest != null) {")
        assertTrue(guest, "guest.enqueueEnd(items)" in guest)
        // On the connection's scope: the menu may close before a playlist's songs have come.
        assertTrue(guest, "guest.scope.launch {" in guest)
        assertTrue(guest, "onDismiss()" in guest)
        assertTrue(guest, Regex("""\n\s*return\s*\n\s*}$""").containsMatchIn(guest))
    }

    @Test
    fun `the room is decided as for Play next`() {
        val connection = main("playback/PlayerConnection.kt")
        assertTrue("service.together.value?.takeIf { it.following && !it.status.value.ownWay }" in body(connection, "private fun followedRoom("))
        assertTrue(connection.substringAfter("val addsToRoom: Boolean get() = ").substringBefore("\n") == "followedRoom() != null")
        assertTrue("val room = followedRoom() ?: return false" in body(connection, "private fun toRoom("))
        // The room's own words and its end, the way the swipe to the queue goes too.
        assertTrue("if (toRoom(items, atEnd = true)) return" in body(connection, "fun enqueueEnd(items: List<MediaItem>)"))
    }

    @Test
    fun `every Add to queue hands the choice its songs`() {
        val callers = root.walkTopDown().filter { it.extension == "kt" && it.name != "QueueDialogs.kt" }
            .map { it.name to it.readText() }
            .filter { (_, text) -> "AddToQueueDialog(" in text }
            .toList()
        assertTrue("the dialog's callers were not found", callers.size >= 11)
        for ((name, text) in callers) {
            assertTrue(name, "songs = {" in text.substringAfter("AddToQueueDialog(").substringBefore("onAdd ="))
        }
        // Asked for, with no default: a menu made later cannot leave the room out.
        val head = main("ui/dialog/QueueDialogs.kt").substringAfter("fun AddToQueueDialog(").substringBefore(") {")
        assertTrue(head, "songs: suspend () -> List<MediaMetadata>,\n" in head)
        // A menu's Add to queue opens the choice: one that wrote into the phone's queues itself would go round the room.
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if ("title = R.string.add_to_queue" in text) assertTrue(file.name, "AddToQueueDialog(" in text)
        }
    }

    /** The text of the function or block whose head is [head], braces matched. */
    private fun body(text: String, head: String): String {
        val start = text.indexOf(head)
        assertTrue("no $head", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return text.substring(start)
    }
}
