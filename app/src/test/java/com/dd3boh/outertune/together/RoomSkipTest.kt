/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.Queue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Next for whoever is not in control of a listening room (his words, 23 Nov 2026: "there are moments i can't skip songs
 * to listen independantly"): with nothing after the room's song in its own copy of the queue, the player offered no Next
 * at all. It stays on, and takes that phone its own way to a radio of the song, from the song after it.
 */
class RoomSkipTest {
    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 1, thumbnailUrl = null, genre = null)

    @Test
    fun `Next stays on for a guest with nothing after the room's song, and only then`() {
        assertTrue(RoomSkip.onOwn(follows = true, hasNext = false, songId = "dQw4w9WgXcQ", local = false))
        // Something after it: the player's own Next, to that song, as before.
        assertFalse(RoomSkip.onOwn(follows = true, hasNext = true, songId = "dQw4w9WgXcQ", local = false))
        // Whoever is in control skips the room as ever: the player's own Next.
        assertFalse(RoomSkip.onOwn(follows = false, hasNext = false, songId = "dQw4w9WgXcQ", local = false))
        // Nothing playing, or a file on this phone, which has no radio to go to.
        assertFalse(RoomSkip.onOwn(follows = true, hasNext = false, songId = null, local = false))
        assertFalse(RoomSkip.onOwn(follows = true, hasNext = false, songId = "LSabcdefgh", local = false))
        assertFalse(RoomSkip.onOwn(follows = true, hasNext = false, songId = "dQw4w9WgXcQ", local = true))
    }

    @Test
    fun `the radio starts on the song after the one playing`() {
        assertEquals(1, RoomSkip.startAfter(listOf("x", "a", "b"), "x", answered = 0))
        assertEquals(3, RoomSkip.startAfter(listOf("a", "b", "x", "c"), "x", answered = 0))
        // Another version of it in its place: after the place YouTube said the radio starts from.
        assertEquals(1, RoomSkip.startAfter(listOf("x-live", "a", "b"), "x", answered = 0))
        // Nothing after it: it plays on, rather than a place past the end.
        assertEquals(0, RoomSkip.startAfter(listOf("x"), "x", answered = 0))
        assertEquals(0, RoomSkip.startAfter(emptyList(), "x", answered = 0))
        assertEquals(1, RoomSkip.startAfter(listOf("y", "z"), "x", answered = 7))
    }

    @Test
    fun `the radio puts no song of its own in first, and starts after the song`() = runBlocking {
        val radio = object : Queue {
            override val preloadItem = song("x")
            override val playlistId: String? = null
            override val startShuffled = false
            override suspend fun getInitialStatus() = Queue.Status("x radio", listOf(song("x"), song("a"), song("b")), 0)
            override fun hasNextPage() = true
            override suspend fun nextPage() = listOf(song("c"))
        }
        val after = RoomSkip.RadioAfter(song("x"), radio)
        // A song put in first would be the song playing, again from its top.
        assertNull(after.preloadItem)
        assertFalse(after.songPicked)
        val status = after.getInitialStatus()
        assertEquals("a", status.items[status.mediaItemIndex].id)
        assertEquals("x radio", status.title)
        // The radio's next pages are its own.
        assertTrue(after.hasNextPage())
        assertEquals(listOf("c"), after.nextPage().map { it.id })
    }

    // The wiring, read from the source.

    private val root = File("src/main/java/com/dd3boh/outertune")
    private fun read(path: String) = File(root, path).readText()

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
        error("$head never closes")
    }

    @Test
    fun `Next is on for such a guest wherever the player shows it, and is looked at again when control moves`() {
        val connection = read("playback/PlayerConnection.kt")
        assertTrue("|| roomSkipsOnOwn()" in body(connection, "private fun updateCanSkipPreviousAndNext("))
        val onOwn = connection.substringAfter("private fun roomSkipsOnOwn(").substringBefore("fun skipToNext(")
        assertTrue("follows = service.together.value?.following == true" in onOwn)
        assertTrue("hasNext = player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)" in onOwn)
        // Who is in control comes from the room, not from the player.
        assertTrue("service.together.flatMapLatest { room -> room?.status?.map { it.leading } ?: flowOf(null) }" in connection)
        // The full player, the queue's bar and the mini player all press Next through the connection.
        assertTrue("playerConnection.skipToNext()" in read("ui/player/Player.kt"))
        assertTrue("playerConnection.skipToNext()" in read("ui/player/Queue.kt"))
        assertTrue("playerConnection.skipToNext(playerConnection.player)" in read("ui/player/MiniPlayer.kt"))
    }

    @Test
    fun `such a Next goes its own way before it asks for the radio, and anyone else's Next is the player's`() {
        val skip = body(read("playback/PlayerConnection.kt"), "fun skipToNext(")
        val own = skip.indexOf("room.skipOnOwn()")
        assertTrue(own >= 0 && own < skip.indexOf("playQueue(RoomSkip.RadioAfter(song), isRadio = true)"))
        assertTrue("player.seekToNext()" in skip)
        // In the room: on its own way at once, even in the moment after a word of the room's, and never the one in control.
        val session = read("together/TogetherSession.kt")
        val skipOnOwn = body(session, "fun skipOnOwn(")
        assertTrue("if (leading) return" in skipOnOwn)
        assertTrue("pressed = true" in skipOnOwn)
        assertTrue("(!pressed && System.currentTimeMillis() < ownCommandUntil)" in body(session, "private fun listenerChose("))
    }
}
